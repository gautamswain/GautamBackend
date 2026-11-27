package com.example

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import net.sourceforge.tess4j.Tesseract
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.sql.Connection
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO

@Serializable
data class KycMasterRecord(
    val code: String,
    val name: String,
    val dob: String,
    val fathersName: String,
    val aadhaar: String,
    val pan: String,
    val drivingLicence: String,
    val accountNo: String,
    val ifsc: String
)

@Serializable
data class FileOcrDetail(
    val fileName: String,
    val ocrEngineUsed: String,
    val extractedCharCount: Int,
    val unreadableScan: Boolean,
    val matchedCategories: List<String>
)

@Serializable
data class KycVerificationResponse(
    val code: String,
    val employeeName: String,
    val statusMap: Map<String, String>,
    val verifiedSummary: String,
    val alerts: List<String>,
    val filesProcessed: List<FileOcrDetail>
)

data class ExtractedDocPayload(
    val fileName: String,
    val mimeType: String,
    val rawBytes: ByteArray,
    var extractedText: String,
    var engineUsed: String
)

object KycDocumentVerifier {

    private val jsonParser = Json { ignoreUnknownKeys = true }

    private fun createOcrEngine(): Tesseract {
        return Tesseract().apply {
            val tessPath = listOfNotNull(
                System.getenv("TESSDATA_PREFIX"),
                "/usr/share/tesseract-ocr/5/tessdata",
                "/usr/share/tesseract-ocr/4.00/tessdata",
                "/usr/share/tessdata"
            ).firstOrNull { File(it).exists() } ?: "/usr/share/tesseract-ocr/5/tessdata"
            setDatapath(tessPath)
            setLanguage("eng")
            setOcrEngineMode(1)
            setPageSegMode(3)
        }
    }

    private fun guessMimeType(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".webp") -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    /**
     * Unzips .zip archives recursively and runs Pass 1 (PDFBox + Tesseract OCR).
     */
    fun extractFilesAndTextPass1(rawFiles: List<Pair<String, ByteArray>>): List<ExtractedDocPayload> {
        val flattenedFiles = mutableListOf<Pair<String, ByteArray>>()

        fun collect(fileName: String, bytes: ByteArray) {
            val lower = fileName.lowercase().trim()
            if (lower.endsWith(".zip")) {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val entryName = entry.name.substringAfterLast('/').trim()
                        if (!entry.isDirectory &&
                            entryName.isNotBlank() &&
                            !entry.name.contains("__MACOSX/") &&
                            !entryName.startsWith("._")
                        ) {
                            val out = ByteArrayOutputStream()
                            zis.copyTo(out)
                            collect(entryName, out.toByteArray())
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } else {
                flattenedFiles.add(fileName to bytes)
            }
        }

        for ((name, bytes) in rawFiles) {
            collect(name, bytes)
        }

        val ocr = createOcrEngine()
        val results = mutableListOf<ExtractedDocPayload>()

        for ((fileName, bytes) in flattenedFiles) {
            val lower = fileName.lowercase()
            val mimeType = guessMimeType(fileName)
            var engine = "Tesseract"

            val text = try {
                when {
                    lower.endsWith(".pdf") -> {
                        val (pdfText, usedEngine) = extractTextFromPdf(bytes, ocr)
                        engine = usedEngine
                        pdfText
                    }
                    lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") -> {
                        val img: BufferedImage? = ImageIO.read(ByteArrayInputStream(bytes))
                        if (img != null) ocr.doOCR(img) else ""
                    }
                    lower.endsWith(".txt") || lower.endsWith(".csv") -> {
                        engine = "PlainText"
                        bytes.toString(Charsets.UTF_8)
                    }
                    else -> ""
                }
            } catch (e: Exception) {
                println("Pass 1 OCR warning for $fileName: ${e.message}")
                ""
            }

            results.add(
                ExtractedDocPayload(
                    fileName = fileName,
                    mimeType = mimeType,
                    rawBytes = bytes,
                    extractedText = text,
                    engineUsed = engine
                )
            )
        }

        return results
    }

    private fun extractTextFromPdf(pdfBytes: ByteArray, ocr: Tesseract): Pair<String, String> {
        Loader.loadPDF(pdfBytes).use { doc ->
            val stripper = PDFTextStripper()
            val embeddedText = stripper.getText(doc).orEmpty()
            if (embeddedText.replace(Regex("\\s+"), "").length >= 40) {
                return embeddedText to "PDFBox-Text"
            }

            val renderer = PDFRenderer(doc)
            val sb = StringBuilder()
            val maxPages = minOf(doc.numberOfPages, 6)
            for (pageIdx in 0 until maxPages) {
                val pageImage = renderer.renderImageWithDPI(pageIdx, 200f, ImageType.GRAY)
                sb.append(ocr.doOCR(pageImage)).append("\n\u000C\n")
            }
            return sb.toString() to "Tesseract-PDF"
        }
    }

    /**
     * Pass 2 Fallback: Calls Gemini 3.5 Flash-Lite API on blurry/unreadable scans or unmatched KYC docs.
     */
    private fun extractWithGeminiFlashFallback(doc: ExtractedDocPayload): String {
        val apiKey = System.getenv("GEMINI_API_KEY")?.trim().orEmpty()
        if (apiKey.isBlank()) return ""
        if (doc.mimeType == "application/octet-stream" || doc.rawBytes.size > 15 * 1024 * 1024) return ""

        val modelId = System.getenv("GEMINI_OCR_MODEL")?.trim().takeIf { !it.isNullOrBlank() }
            ?: "gemini-3.5-flash-lite"
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent?key=$apiKey"

        val base64Data = Base64.getEncoder().encodeToString(doc.rawBytes)
        val prompt = """
            Extract all visible text and numbers accurately from this Indian KYC/employee document (even if tilted, stamped, or low resolution).
            Return a JSON object with:
            - "documentType": detected type (PAN Card, Aadhaar Card, Bank Passbook/Cheque, Driving Licence, Voter ID, Biodata, Matric Certificate, Intermediate Certificate, Police Verification, or Other)
            - "fullText": complete verbatim OCR text from all pages
            - "extractedNumbers": any PAN, Aadhaar, Bank Account Number, IFSC, DL number, or Voter EPIC number visible
        """.trimIndent()

        val requestJson = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        addJsonObject { put("text", prompt) }
                        addJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", doc.mimeType)
                                put("data", base64Data)
                            }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", 0.0)
                put("responseMimeType", "application/json")
            }
        }.toString()

        return try {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
            }

            conn.outputStream.use { it.write(requestJson.toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode != 200) {
                println("Gemini OCR fallback HTTP ${conn.responseCode} for ${doc.fileName}")
                return ""
            }

            val responseBody = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = jsonParser.parseToJsonElement(responseBody).jsonObject
            val candidateText = root["candidates"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()

            if (candidateText.isBlank()) return ""

            val structured = jsonParser.parseToJsonElement(candidateText).jsonObject
            val docType = structured["documentType"]?.jsonPrimitive?.content.orEmpty()
            val fullText = structured["fullText"]?.jsonPrimitive?.content.orEmpty()
            val nums = structured["extractedNumbers"]?.toString().orEmpty()

            "$docType\n$fullText\n$nums".trim()
        } catch (e: Exception) {
            println("Gemini OCR fallback exception for ${doc.fileName}: ${e.message}")
            ""
        }
    }

    fun fetchMasterRecord(conn: Connection, empCode: String): KycMasterRecord? {
        val sql = """
            SELECT * FROM MasterData
            WHERE UPPER(TRIM(a3)) = UPPER(TRIM(?))
            LIMIT 1
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, empCode)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return null

                fun getCol(vararg names: String): String {
                    for (col in names) {
                        try {
                            val v = rs.getString(col)
                            if (!v.isNullOrBlank()) return v.trim()
                        } catch (_: Exception) {}
                    }
                    return ""
                }

                val rawIfsc = getCol("a22", "ifsc_code_bank_branch_name_21").uppercase()
                val ifscMatch = Regex("[A-Z]{4}0[A-Z0-9]{6}").find(rawIfsc)
                val cleanIfsc = ifscMatch?.value ?: rawIfsc.replace(Regex("[^A-Z0-9]"), "").take(11)

                return KycMasterRecord(
                    code = getCol("a3", "Code_2").uppercase(),
                    name = getCol("a4", "employee_name_3"),
                    dob = getCol("a7", "dob_6"),
                    fathersName = getCol("a5", "fathers_name_4"),
                    aadhaar = getCol("a17", "aadhar_card_no_16").replace(Regex("\\D"), ""),
                    pan = getCol("a18", "pan_card_no_17").uppercase().replace(Regex("[^A-Z0-9]"), ""),
                    drivingLicence = getCol("a19", "driving_18").uppercase().replace(Regex("[^A-Z0-9]"), ""),
                    accountNo = getCol("a21", "bank_account_number_20").replace(Regex("\\D"), ""),
                    ifsc = cleanIfsc
                )
            }
        }
    }

    /**
     * Runs verification on Pass 1 OCR; automatically invokes Gemini Flash fallback on weak/unmatched files.
     */
    fun verifyDocumentsWithFallback(
        master: KycMasterRecord,
        docs: List<ExtractedDocPayload>
    ): KycVerificationResponse {
        val initialResult = evaluateVerification(master, docs)

        val coreMissing = initialResult.statusMap["pan"]?.contains("missing") == true ||
            initialResult.statusMap["aadhaar"]?.contains("missing") == true ||
            initialResult.statusMap["accountNumber"]?.contains("missing") == true ||
            initialResult.statusMap["ifscCode"]?.contains("missing") == true

        var anyFallbackTriggered = false

        docs.forEachIndexed { idx, doc ->
            val detail = initialResult.filesProcessed.getOrNull(idx)
            val needsFallback = (detail?.unreadableScan == true) ||
                (coreMissing && detail?.matchedCategories?.isEmpty() == true)

            if (needsFallback) {
                val geminiText = extractWithGeminiFlashFallback(doc)
                if (geminiText.replace(Regex("\\s+"), "").length >= 25) {
                    doc.extractedText = "${doc.extractedText}\n\u000C\n$geminiText".trim()
                    doc.engineUsed = "${doc.engineUsed}+GeminiFlash"
                    anyFallbackTriggered = true
                }
            }
        }

        return if (anyFallbackTriggered) {
            evaluateVerification(master, docs)
        } else {
            initialResult
        }
    }

    private fun evaluateVerification(
        master: KycMasterRecord,
        extractedDocs: List<ExtractedDocPayload>
    ): KycVerificationResponse {
        var panOk = false
        var aadhaarOk = false
        var accountOk = false
        var ifscOk = false
        var dlOk = false
        var voterOk = false
        var biodataOk = false
        var matricOk = false
        var interOk = false
        var policeOk = false

        val alerts = mutableListOf<String>()
        val fileDetails = mutableListOf<FileOcrDetail>()

        val ignoreTitles = setOf("mr", "mrs", "ms", "miss", "shri", "smt", "kumari", "late", "dr")
        val nameTokens = master.name.lowercase()
            .replace(Regex("[^a-z\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 && it !in ignoreTitles }

        for (doc in extractedDocs) {
            val fileName = doc.fileName
            val rawText = doc.extractedText
            val cleanFile = fileName.lowercase()
            val strippedLen = rawText.replace(Regex("\\s+"), "").length
            val unreadable = strippedLen < 25
            val matchedInThisFile = mutableListOf<String>()

            if (unreadable) {
                alerts.add("\"$fileName\": Blurry or unreadable scan (low OCR text)")
            }

            val pages = rawText.split('\u000C')
            for (pageText in pages) {
                val combined = "$cleanFile\n$pageText"
                val upperNorm = combined.uppercase().replace(Regex("[^A-Z0-9]"), "")
                val lowerPage = pageText.lowercase()

                val biodataHits = Regex(
                    "\\b(?:bio[\\s\\-]?data|curriculum\\s+vitae|resume|career\\s+objective|educational\\s+qualification|academic\\s+qualification|work\\s+experience|hobbies|languages\\s+known|marital\\s+status|declaration)\\b",
                    RegexOption.IGNORE_CASE
                ).findAll(pageText).count()
                val isBiodataPage = biodataHits >= 2 || Regex("\\b(?:bio[\\s\\-]?data|resume|cv)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleanFile)

                val matchedNameCount = nameTokens.count { token ->
                    Regex("\\b${Regex.escape(token)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(lowerPage)
                }
                val nameMatches = nameTokens.isEmpty() ||
                    (nameTokens.size == 1 && matchedNameCount == 1) ||
                    matchedNameCount >= 2

                if (isBiodataPage) {
                    biodataOk = true
                    matchedInThisFile.add("Biodata")
                }

                if (!panOk && !isBiodataPage && master.pan.length == 10 && upperNorm.contains(master.pan)) {
                    val hasPanMarker = Regex("\\b(?:income\\s+tax\\s+department|permanent\\s+account\\s+number|govt\\.?\\s+of\\s+india|pan\\s+card)\\b", RegexOption.IGNORE_CASE).containsMatchIn(pageText) ||
                        Regex("\\bpan\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleanFile)
                    if (hasPanMarker) {
                        panOk = true
                        matchedInThisFile.add("PAN")
                    }
                }

                if (!aadhaarOk && !isBiodataPage && master.aadhaar.length == 12 && upperNorm.contains(master.aadhaar)) {
                    val hasAadhaarMarker = Regex("\\b(?:unique\\s+identification|uidai|government\\s+of\\s+india|mera\\s+aadhaar|enrolment|enrollment|vid\\s*:|aadhaar|aadhar)\\b", RegexOption.IGNORE_CASE).containsMatchIn(pageText) ||
                        Regex("\\b(?:aadhaar|aadhar|uid)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleanFile)
                    if (hasAadhaarMarker) {
                        aadhaarOk = true
                        matchedInThisFile.add("Aadhaar")
                    }
                }

                if (!accountOk && !isBiodataPage && master.accountNo.length >= 6 && upperNorm.contains(master.accountNo)) {
                    val hasBankMarker = Regex("\\b(?:passbook|cancelled\\s+cheque|cheque|account\\s+statement|cif|micr|ifsc|savings|current\\s+account|branch\\s+code|customer\\s+id|bank)\\b", RegexOption.IGNORE_CASE).containsMatchIn(pageText) ||
                        Regex("\\b(?:bank|passbook|cheque|statement)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleanFile)
                    if (hasBankMarker) {
                        accountOk = true
                        matchedInThisFile.add("Bank Account")
                    }
                }

                if (!ifscOk && !isBiodataPage && master.ifsc.length == 11 && upperNorm.contains(master.ifsc)) {
                    ifscOk = true
                    matchedInThisFile.add("IFSC")
                }

                if (!dlOk && !isBiodataPage) {
                    val hasDlMarker = Regex("\\b(?:driving\\s+licen[cs]e|indian\\s+union\\s+driving|transport\\s+department|motor\\s+vehicles|lmv|mcwg|cov|valid\\s+till|dl\\s*no)\\b", RegexOption.IGNORE_CASE).containsMatchIn(pageText) ||
                        Regex("\\b(?:od|or)[0-9]{2}[\\s\\-]?[0-9]{11}\\b", RegexOption.IGNORE_CASE).containsMatchIn(pageText) ||
                        (master.drivingLicence.length >= 8 && upperNorm.contains(master.drivingLicence)) ||
                        Regex("\\b(?:dl|driving|licen[cs]e)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleanFile)
                    if (hasDlMarker && (nameMatches || Regex("\\b(?:dl|driving|licen[cs]e)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleanFile))) {
                        dlOk = true
                        matchedInThisFile.add("Driving Licence")
                    }
                }

                if (!voterOk && !isBiodataPage) {
                    val hasVoterMarker = Regex("\\b(?:election\\s+commission\\s+of\\s+india|elector'?s?\\s+photo\\s+identity|epic\\s*no|voter\\s+id|electoral\\s+registration\\s+officer|assembly\\s+constituency)\\b", RegexOption.IGNORE_CASE).containsMatchIn(pageText) ||
                        Regex("\\b[A-Z]{3}[0-9]{7}\\b").containsMatchIn(pageText) ||
                        Regex("\\b(?:voter|epic|election)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleanFile)
                    if (hasVoterMarker && (nameMatches || Regex("\\b(?:voter|epic)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleanFile))) {
                        voterOk = true
                        matchedInThisFile.add("Voter Card")
                    }
                }

                if (!matricOk && !isBiodataPage) {
                    val hasMatricHeader = Regex("\\b(?:board\\s+of\\s+secondary\\s+education|high\\s+school\\s+certificate|annual\\s+high\\s+school|supplementary\\s+high\\s+school|secondary\\s+school\\s+examination|central\\s+board\\s+of\\s+secondary|all\\s+india\\s+secondary|indian\\s+certificate\\s+of\\s+secondary|matriculation|bse[\\s,]+odisha)\\b", RegexOption.IGNORE_CASE).containsMatchIn(pageText)
                    val hasCertFields = Regex("\\b(?:roll\\s*(?:no|code|number)|regd\\.?\\s*no|registration\\s*no|total\\s+marks|marks\\s+secured|first\\s+division|second\\s+division|third\\s+division|passed\\s+the|date\\s+of\\s+birth|father'?s\\s+name|certified\\s+that)\\b", RegexOption.IGNORE_CASE).containsMatchIn(pageText)
                    val hasMatricFile = Regex("\\b(?:matric|10th|tenth|hsc|bse|high[\\s_\\-]?school)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleanFile)
                    if ((hasMatricHeader && hasCertFields && nameMatches) || (hasMatricFile && hasCertFields)) {
                        matricOk = true
                        matchedInThisFile.add("Matric Certificate")
                    }
                }

                if (!interOk && !isBiodataPage) {
                    val hasInterHeader = Regex("\\b(?:council\\s+of\\s+higher\\s+secondary|higher\\s+secondary|intermediate|senior\\s+secondary|chse|\\+2\\s+examination|class\\s+xii)\\b", RegexOption.IGNORE_CASE).containsMatchIn(pageText)
                    val hasMarksFields = Regex("\\b(?:roll\\s*(?:no|number)|regd\\.?\\s*no|registration\\s*no|total\\s+marks|marks\\s+secured|division|stream|science|commerce|arts|passed)\\b", RegexOption.IGNORE_CASE).containsMatchIn(pageText)
                    val hasInterFile = Regex("\\b(?:plus2|\\+2|12th|twelfth|chse|inter|intermediate)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleanFile)
                    if ((hasInterHeader && hasMarksFields && nameMatches) || (hasInterFile && hasMarksFields)) {
                        interOk = true
                        matchedInThisFile.add("Intermediate Certificate")
                    }
                }

                if (!policeOk && !isBiodataPage) {
                    val hasPoliceMarker = Regex("\\b(?:police\\s+verification|character\\s+certificate|antecedent|criminal\\s+record|no\\s+adverse\\s+report|superintendent\\s+of\\s+police|commissionerate\\s+police|odisha\\s+police|police\\s+station|s\\.?p\\.?\\s+office|pcc)\\b", RegexOption.IGNORE_CASE).containsMatchIn(pageText)
                    val hasPoliceFile = Regex("\\b(?:police|pcc|verification|character[\\s_\\-]?cert)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleanFile)
                    if ((hasPoliceMarker && nameMatches) || (hasPoliceFile && hasPoliceMarker)) {
                        policeOk = true
                        matchedInThisFile.add("Police Verification")
                    }
                }
            }

            fileDetails.add(
                FileOcrDetail(
                    fileName = fileName,
                    ocrEngineUsed = doc.engineUsed,
                    extractedCharCount = strippedLen,
                    unreadableScan = unreadable,
                    matchedCategories = matchedInThisFile.distinct()
                )
            )
        }

        val statusMap = linkedMapOf(
            "pan" to if (panOk) "PAN verified OK" else "PAN missing / not matched",
            "aadhaar" to if (aadhaarOk) "Aadhaar verified OK" else "Aadhaar missing / not matched",
            "accountNumber" to if (accountOk) "Account number verified OK" else "Account number missing / not matched",
            "ifscCode" to if (ifscOk) "IFSC code verified OK" else "IFSC code missing / not matched",
            "drivingLicence" to if (dlOk) "Driving licence verified OK" else "Driving licence missing",
            "voterCard" to if (voterOk) "Voter card verified OK" else "Voter card missing",
            "biodata" to if (biodataOk) "Biodata verified OK" else "Biodata missing",
            "matricCertificate" to if (matricOk) "Matric certificate verified OK" else "Matric certificate missing",
            "intermediateCertificate" to if (interOk) "Intermediate certificate verified OK" else "Intermediate certificate missing",
            "policeVerification" to if (policeOk) "Police verification verified OK" else "Police verification missing"
        )

        return KycVerificationResponse(
            code = master.code,
            employeeName = master.name,
            statusMap = statusMap,
            verifiedSummary = statusMap.values.joinToString(", "),
            alerts = alerts,
            filesProcessed = fileDetails
        )
    }
}

fun Route.kycVerificationRoutes(dbConnectionProvider: () -> Connection) {
    post("/api/turso/verify-kyc") {
        var empCode = call.request.queryParameters["code"].orEmpty().trim().uppercase()
        val uploadedFiles = mutableListOf<Pair<String, ByteArray>>()

        try {
            val multipart = call.receiveMultipart()
            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> {
                        if (part.name.equals("code", ignoreCase = true) ||
                            part.name.equals("empCode", ignoreCase = true)
                        ) {
                            empCode = part.value.trim().uppercase()
                        }
                    }
                    is PartData.FileItem -> {
                        val fileName = part.originalFileName ?: "upload.bin"
                        val bytes = part.streamProvider().readBytes()
                        if (bytes.isNotEmpty()) {
                            uploadedFiles.add(fileName to bytes)
                        }
                    }
                    else -> {}
                }
                part.dispose()
            }

            if (empCode.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing employee code (e.g., ADKR2092)"))
            }
            if (uploadedFiles.isEmpty()) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No files uploaded (attach image, PDF, or ZIP)"))
            }

            val master = dbConnectionProvider().use { conn ->
                KycDocumentVerifier.fetchMasterRecord(conn, empCode)
            } ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Employee $empCode not found in MasterData"))

            val verificationResult = withContext(Dispatchers.IO) {
                val pass1Docs = KycDocumentVerifier.extractFilesAndTextPass1(uploadedFiles)
                KycDocumentVerifier.verifyDocumentsWithFallback(master, pass1Docs)
            }

            call.respond(HttpStatusCode.OK, verificationResult)
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (e.localizedMessage ?: "KYC verification failed"))
            )
        }
    }
}