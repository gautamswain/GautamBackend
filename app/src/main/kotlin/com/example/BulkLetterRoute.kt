package com.example

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.sql.Connection
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Serializable
data class BulkLetterRow(
    val code: String,
    val letter: String,
    val promoTo: String? = null,
    @SerialName("dBranch") val dBranch: String? = null,
    val wef: String? = null,
    val address: String? = null
)

@Serializable
data class GenerateLetterRequest(
    val rows: List<BulkLetterRow>
)

data class MasterEmployeeData(
    val name: String,           // {1}
    val code: String,           // {2}
    val department: String,     // {3}
    val designation: String,    // {4}
    val branch: String,         // {5}
    val gender: String,         // For {17} & {18}
    val doj: String,            // {8}
    val dol: String,            // {9}
    val salary: String,         // {11}
    val fatherName: String,     // {14}
    val presentAddress: String, // {15}
    val mobileNo: String,       // {16}
    val email: String           // {emp_mail}
) {
    val heShe: String
        get() = if (gender.trim().equals("Female", ignoreCase = true) ||
            gender.trim().equals("F", ignoreCase = true)
        ) "She" else "He"

    val hisHer: String
        get() = if (gender.trim().equals("Female", ignoreCase = true) ||
            gender.trim().equals("F", ignoreCase = true)
        ) "Her" else "His"
}

data class BulkLetterTemplateConfig(
    val lettercode: String,
    val templete: String,
    val filenameformat: String,
    val subject: String,
    val body: String,
    val tomail: String,
    val ccmail: String,
    val wef: String
)

object BulkLetterService {

    private val istZone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val displayDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    fun fetchEmployeeFromMasterData(conn: Connection, empCode: String): MasterEmployeeData? {
        val sql = """
            SELECT * FROM MasterData 
            WHERE UPPER(TRIM(Code_2)) = UPPER(TRIM(?)) 
            LIMIT 1
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, empCode)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return null
                val dto = StaffRepository.parseMasterDataDto(rs)
                return MasterEmployeeData(
                    name = dto.employeeName.ifBlank { dto.id },
                    code = dto.empId.ifBlank { empCode },
                    department = dto.department,
                    designation = dto.designation,
                    branch = dto.branchName,
                    gender = dto.gender,
                    doj = dto.dojInAdhikar,
                    dol = dto.dol,
                    salary = dto.salaryCode.ifBlank { dto.slary },
                    fatherName = dto.fathersName,
                    presentAddress = dto.presentAddress,
                    mobileNo = dto.personalContactNumber,
                    email = dto.email61Personal.ifBlank { dto.mailIdOffice }
                )
            }
        }
    }

    fun fetchLetterConfigFromDb(conn: Connection, letterCode: String): BulkLetterTemplateConfig? {
        val sql = """
            SELECT lettercode, templete, filenameformat, subject, body, tomail, ccmail, wef 
            FROM bulkletter 
            WHERE TRIM(lettercode) = TRIM(?) 
            LIMIT 1
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, letterCode)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return BulkLetterTemplateConfig(
                        lettercode = rs.getString("lettercode")?.trim().orEmpty(),
                        templete = rs.getString("templete")?.trim().orEmpty(),
                        filenameformat = rs.getString("filenameformat")?.trim().orEmpty(),
                        subject = rs.getString("subject")?.trim().orEmpty(),
                        body = rs.getString("body")?.trim().orEmpty(),
                        tomail = rs.getString("tomail")?.trim().orEmpty(),
                        ccmail = rs.getString("ccmail")?.trim().orEmpty(),
                        wef = rs.getString("wef")?.trim().orEmpty()
                    )
                }
            }
        }
        return null
    }

    fun fetchResponsibleBranches(conn: Connection, empCode: String): String {
        val sql = "SELECT * FROM branchdetails"
        val matchedBranches = mutableListOf<String>()

        conn.prepareStatement(sql).use { stmt ->
            stmt.executeQuery().use { rs ->
                val meta = rs.metaData
                val colCount = meta.columnCount

                while (rs.next()) {
                    var branchName = ""
                    var isResponsible = false

                    for (i in 1..colCount) {
                        val colName = meta.getColumnName(i).lowercase()
                        val value = rs.getString(i)?.trim().orEmpty()

                        if (branchName.isBlank() &&
                            colName in setOf("branch", "branch_name", "branchname", "name")
                        ) {
                            branchName = value
                        }

                        if (value.equals(empCode.trim(), ignoreCase = true) &&
                            colName !in setOf("branch", "branch_name", "branchname", "id")
                        ) {
                            isResponsible = true
                        }
                    }

                    if (isResponsible && branchName.isNotBlank() && !matchedBranches.contains(branchName)) {
                        matchedBranches.add(branchName)
                    }
                }
            }
        }
        return matchedBranches.joinToString(", ")
    }

    fun fetchProbationDays(conn: Connection, designationName: String): String {
        val cleanDesignation = designationName.trim()
        if (cleanDesignation.isBlank()) return ""

        val sql = """
            SELECT probday FROM designation 
            WHERE UPPER(TRIM(designation)) = UPPER(TRIM(?)) 
               OR UPPER(TRIM(degcode)) = UPPER(TRIM(?))
               OR UPPER(TRIM(shtcode)) = UPPER(TRIM(?))
            LIMIT 1
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, cleanDesignation)
            stmt.setString(2, cleanDesignation)
            stmt.setString(3, cleanDesignation)

            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return rs.getString("probday")?.trim().orEmpty()
                }
            }
        }
        return ""
    }

    fun calculateInterviewDate(dojStr: String): String {
        val cleanDoj = dojStr.trim()
        if (cleanDoj.isBlank()) return ""

        val formats = listOf(
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d-M-yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy")
        )

        for (fmt in formats) {
            try {
                return LocalDate.parse(cleanDoj, fmt)
                    .minusDays(1)
                    .format(displayDateFormatter)
            } catch (_: Exception) { }
        }
        return ""
    }

    private fun xmlEscape(str: String): String {
        return str.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
    }

    fun applyPlaceholders(
        rawTemplate: String,
        emp: MasterEmployeeData,
        row: BulkLetterRow,
        responsibleBranches: String,
        probationPeriod: String,
        resolvedWef: String,
        isEmailField: Boolean = false
    ): String {
        val todayDate = LocalDate.now(istZone).format(displayDateFormatter)
        val ddmmyy = LocalDate.now(istZone).format(DateTimeFormatter.ofPattern("ddMMyy"))
        val interviewDate = calculateInterviewDate(emp.doj)

        val effectiveBranch = row.dBranch?.takeIf { it.isNotBlank() } ?: emp.branch
        val effectiveAddress = row.address?.takeIf { it.isNotBlank() } ?: emp.presentAddress
        val effectiveDesignation = row.promoTo?.takeIf { it.isNotBlank() } ?: emp.designation
        val effectiveDolOrWef = row.wef?.takeIf { it.isNotBlank() } ?: resolvedWef.takeIf { it.isNotBlank() } ?: emp.dol

        val mapper: (String) -> String = if (isEmailField) { { it } } else { { xmlEscape(it) } }

        return rawTemplate
            .replace("{1}", mapper(emp.name))
            .replace("{2}", mapper(emp.code))
            .replace("{3}", mapper(emp.department))
            .replace("{4}", mapper(effectiveDesignation))
            .replace("{5}", mapper(effectiveBranch))
            .replace("{8}", mapper(emp.doj))
            .replace("{9}", mapper(effectiveDolOrWef))
            .replace("{11}", mapper(emp.salary))
            .replace("{13}", mapper(probationPeriod))
            .replace("{14}", mapper(emp.fatherName))
            .replace("{15}", mapper(effectiveAddress))
            .replace("{16}", mapper(emp.mobileNo))
            .replace("{17}", mapper(emp.heShe))
            .replace("{18}", mapper(emp.hisHer))
            .replace("{17_lower}", mapper(emp.heShe.lowercase()))
            .replace("{18_lower}", mapper(emp.hisHer.lowercase()))
            .replace("{19}", mapper(todayDate))
            .replace("{20}", mapper(responsibleBranches))
            .replace("{21}", mapper(interviewDate))
            .replace("{emp_mail}", mapper(emp.email))
            .replace("{ddmmyy}", mapper(ddmmyy))
            .replace("{wef}", mapper(effectiveDolOrWef))
    }

    fun resolveEmailField(conn: Connection, rawToken: String, emp: MasterEmployeeData): String {
        val tokens = rawToken.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val resolved = mutableSetOf<String>()
        for (token in tokens) {
            if (token.equals("own", ignoreCase = true)) {
                val sql = "SELECT mail_id_office, email61_personal, personal_email FROM MasterData WHERE UPPER(TRIM(Code_2)) = UPPER(TRIM(?)) LIMIT 1"
                try {
                    conn.prepareStatement(sql).use { ps ->
                        ps.setString(1, emp.code)
                        ps.executeQuery().use { rs ->
                            if (rs.next()) {
                                val office = (rs.getString("mail_id_office") ?: "").trim()
                                val personal1 = (rs.getString("email61_personal") ?: "").trim()
                                val personal2 = (rs.getString("personal_email") ?: "").trim()
                                if (office.isNotBlank() && office.contains("@")) resolved.add(office)
                                if (personal1.isNotBlank() && personal1.contains("@")) resolved.add(personal1)
                                if (personal2.isNotBlank() && personal2.contains("@")) resolved.add(personal2)
                            }
                        }
                    }
                } catch (_: Exception) {}
                if (emp.email.isNotBlank() && emp.email.contains("@")) resolved.add(emp.email)
            } else if (token.equals("pipe", ignoreCase = true)) {
                if (emp.branch.isNotBlank()) {
                    try {
                        val stmt = conn.createStatement()
                        val rs = stmt.executeQuery("SELECT * FROM BranchDetails")
                        val meta = rs.metaData
                        val colCount = meta.columnCount
                        while (rs.next()) {
                            var bName = ""
                            val rowEmails = mutableListOf<String>()
                            for (i in 1..colCount) {
                                val colName = meta.getColumnName(i).lowercase()
                                val value = rs.getString(i)?.trim().orEmpty()
                                if (colName in setOf("branch", "branch_name", "branchname", "name") && bName.isBlank()) {
                                    bName = value
                                }
                                if (value.contains("@") && (
                                    colName.contains("mail") || colName.contains("br") ||
                                    colName.contains("am") || colName.contains("cm") ||
                                    colName.contains("bh") || colName.contains("1am") ||
                                    colName.contains("2cm") || colName.contains("3bh")
                                )) {
                                    rowEmails.add(value)
                                }
                            }
                            if (bName.equals(emp.branch.trim(), ignoreCase = true)) {
                                resolved.addAll(rowEmails)
                                break
                            }
                        }
                        rs.close()
                        stmt.close()
                    } catch (_: Exception) {}
                }
            } else if (token.contains("@")) {
                resolved.add(token)
            } else {
                val emailSql = "SELECT mail_id_office, email61_personal FROM MasterData WHERE UPPER(TRIM(Code_2)) = UPPER(TRIM(?)) LIMIT 1"
                try {
                    conn.prepareStatement(emailSql).use { ps ->
                        ps.setString(1, token)
                        ps.executeQuery().use { rs ->
                            if (rs.next()) {
                                val mail = (rs.getString("mail_id_office") ?: rs.getString("email61_personal") ?: "").trim()
                                if (mail.isNotBlank() && mail.contains("@")) resolved.add(mail)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        return resolved.filter { it.isNotBlank() && it.contains("@") }.distinct().joinToString(",")
    }

    fun insertLetterEmailRecord(
        conn: Connection,
        code: String,
        letterCode: String,
        toMail: String,
        ccMail: String,
        subject: String,
        body: String,
        attachmentLink: String,
        wef: String
    ) {
        val sql = """
            INSERT INTO letteremail (code, lettercode, tomail, ccmail, subject, body, attachmentlink, wef)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, code)
            stmt.setString(2, letterCode)
            stmt.setString(3, toMail)
            stmt.setString(4, ccMail)
            stmt.setString(5, subject)
            stmt.setString(6, body)
            stmt.setString(7, attachmentLink)
            stmt.setString(8, wef)
            stmt.execute()
        }
    }
}

fun Route.bulkLetterRoutes(dbConnectionProvider: () -> Connection) {
    post("/api/turso/generateletter") {
        try {
            val text = call.receiveText()
            val rows = try {
                Json.decodeFromString<List<BulkLetterRow>>(text)
            } catch (_: Exception) {
                try {
                    Json.decodeFromString<GenerateLetterRequest>(text).rows
                } catch (e: Exception) {
                    emptyList()
                }
            }

            if (rows.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No rows provided"))
                return@post
            }

            val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))
                .format(DateTimeFormatter.ofPattern("dd-MM-yyyy"))
            val zipStream = ByteArrayOutputStream()

            dbConnectionProvider().use { conn ->
                ZipOutputStream(zipStream).use { zip ->
                    rows.forEachIndexed { index, row ->
                        val empData = BulkLetterService.fetchEmployeeFromMasterData(conn, row.code)
                            ?: MasterEmployeeData(
                                name = row.code,
                                code = row.code,
                                department = "",
                                designation = row.promoTo ?: "",
                                branch = row.dBranch ?: "",
                                gender = "Male",
                                doj = "",
                                dol = row.wef ?: "",
                                salary = "",
                                fatherName = "",
                                presentAddress = row.address ?: "",
                                mobileNo = "",
                                email = ""
                            )

                        val letterConfig = BulkLetterService.fetchLetterConfigFromDb(conn, row.letter)
                            ?: error("Letter template not found in bulkletter table for lettercode: ${row.letter}")

                        // Resolve WEF date: CSV row wef > letterConfig.wef > today's date
                        val resolvedWef = row.wef?.takeIf { it.isNotBlank() }
                            ?: letterConfig.wef.takeIf { it.isNotBlank() }
                            ?: today

                        // Strict Date of Leaving (dol) validation for clearance/leaving letters (e.g. lettercode == "21" or template needing {9})
                        val requiresDol = row.letter.trim() == "21" || letterConfig.templete.contains("{9}") || letterConfig.subject.contains("{9}") || letterConfig.body.contains("{9}")
                        if (requiresDol && empData.dol.isBlank()) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Date of Leaving missing for ${row.code}"))
                            return@post
                        }

                        val targetDesignation = effectiveRowPromoTo(row, empData)
                        val probationPeriod = BulkLetterService.fetchProbationDays(conn, targetDesignation)
                        val responsibleBranches = BulkLetterService.fetchResponsibleBranches(conn, row.code)

                        val finalHtmlBody = BulkLetterService.applyPlaceholders(
                            rawTemplate = letterConfig.templete,
                            emp = empData,
                            row = row,
                            responsibleBranches = responsibleBranches,
                            probationPeriod = probationPeriod,
                            resolvedWef = resolvedWef,
                            isEmailField = false
                        )

                        val refNo = "ADH/HR/2026/${index + 1}"
                        val pdfBytes = LetterPdfService.generateLetterPdf(
                            formattedHtmlBody = finalHtmlBody,
                            refNo = refNo,
                            date = today
                        )

                        val safeCode = row.code.ifBlank { "Emp_${index + 1}" }
                            .replace(Regex("[^A-Za-z0-9_-]"), "_")

                        // Dynamic filename formatting from filenameformat
                        val fileNameRaw = letterConfig.filenameformat.takeIf { it.isNotBlank() } ?: "${safeCode}_Letter_${row.letter}"
                        val resolvedFileName = BulkLetterService.applyPlaceholders(
                            fileNameRaw, empData, row, responsibleBranches, probationPeriod, resolvedWef, isEmailField = true
                        ).let { if (it.endsWith(".pdf", ignoreCase = true)) it else "$it.pdf" }

                        // 1. Upload PDF to Google Drive (AdhikarHR/FILE<digits>/)
                        var attachmentLink = ""
                        try {
                            val driveResult = GoogleDriveReportService.uploadStaffFile(
                                empCode = row.code,
                                fileName = resolvedFileName,
                                mimeType = "application/pdf",
                                bytes = pdfBytes
                            )
                            val driveFileId = driveResult["id"].orEmpty()
                            if (driveFileId.isNotBlank()) {
                                attachmentLink = "https://drive.google.com/file/d/$driveFileId/view"
                            }
                        } catch (_: Exception) { }

                        // 2. Resolve Email fields & save into letteremail table
                        val resolvedSubject = BulkLetterService.applyPlaceholders(
                            letterConfig.subject, empData, row, responsibleBranches, probationPeriod, resolvedWef, isEmailField = true
                        )
                        val resolvedBody = BulkLetterService.applyPlaceholders(
                            letterConfig.body, empData, row, responsibleBranches, probationPeriod, resolvedWef, isEmailField = true
                        )
                        val resolvedToMail = BulkLetterService.resolveEmailField(conn, letterConfig.tomail, empData)
                        val resolvedCcMail = BulkLetterService.resolveEmailField(conn, letterConfig.ccmail, empData)

                        BulkLetterService.insertLetterEmailRecord(
                            conn = conn,
                            code = row.code,
                            letterCode = letterConfig.lettercode.ifBlank { row.letter },
                            toMail = resolvedToMail,
                            ccMail = resolvedCcMail,
                            subject = resolvedSubject,
                            body = resolvedBody,
                            attachmentLink = attachmentLink,
                            wef = resolvedWef
                        )

                        // 3. Add PDF to ZIP response
                        zip.putNextEntry(ZipEntry(resolvedFileName))
                        zip.write(pdfBytes)
                        zip.closeEntry()
                    }
                }
            }

            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName,
                    "Adhikar_Bulk_Letters.zip"
                ).toString()
            )
            call.respondBytes(zipStream.toByteArray(), ContentType.Application.Zip)
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf(
                    "error" to (e.localizedMessage ?: e.message ?: "Unknown error"),
                    "exception" to e.javaClass.simpleName
                )
            )
        }
    }
}

private fun effectiveRowPromoTo(row: BulkLetterRow, empData: MasterEmployeeData): String {
    return row.promoTo?.takeIf { it.isNotBlank() } ?: empData.designation
}