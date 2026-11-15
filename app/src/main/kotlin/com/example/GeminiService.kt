package com.example

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration.Companion.seconds

@Serializable
data class AiChatTurn(
    val role: String,
    val text: String
)

@Serializable
data class AiAssistantRequest(
    val messages: List<AiChatTurn>
)

@Serializable
data class HrLetterDraft(
    val empId: String,
    val employeeName: String,
    val recipientEmail: String,
    val letterType: String,
    val subject: String,
    val body: String
)

@Serializable
data class AiAssistantResponse(
    val reply: String,
    val quickOptions: List<String> = emptyList(),
    val letterDraft: HrLetterDraft? = null
)

@Serializable
data class AiTeachRequest(
    val logId: Int? = null,
    val category: String,
    val samplePrompt: String,
    val expectedTemplate: String
)

object GeminiService {
    private val logger = LoggerFactory.getLogger(GeminiService::class.java)
    private val apiKey: String = System.getenv("GEMINI_API_KEY")?.trim() ?: ""

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun postJsonHttp(endpoint: String, jsonBody: String): Pair<Int, String> {
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            conn.outputStream.use { os ->
                os.write(jsonBody.toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            Pair(code, text)
        } finally {
            conn.disconnect()
        }
    }

    suspend fun generateText(prompt: String, systemInstruction: String = ""): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext "ERROR_NO_KEY"
        }
        val models = listOf("gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.5-flash-lite")
        var lastError = "ERROR_API_FAIL"

        for ((index, model) in models.withIndex()) {
            try {
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

                val requestBody = buildJsonObject {
                    if (systemInstruction.isNotBlank()) {
                        putJsonObject("systemInstruction") {
                            putJsonArray("parts") {
                                addJsonObject {
                                    put("text", systemInstruction)
                                }
                            }
                        }
                    }
                    putJsonArray("contents") {
                        addJsonObject {
                            put("role", "user")
                            putJsonArray("parts") {
                                addJsonObject {
                                    put("text", prompt)
                                }
                            }
                        }
                    }
                    putJsonObject("generationConfig") {
                        put("responseMimeType", "application/json")
                        put("temperature", 0.4)
                    }
                }

                val (statusCode, rawBody) = postJsonHttp(endpoint, requestBody.toString())
                if (statusCode in 200..299) {
                    val jsonElement = jsonParser.parseToJsonElement(rawBody)
                    val partsArray = jsonElement.jsonObject["candidates"]?.jsonArray?.getOrNull(0)
                        ?.jsonObject?.get("content")?.jsonObject
                        ?.get("parts")?.jsonArray

                    val textPart = partsArray?.mapNotNull { part ->
                        val obj = part.jsonObject
                        if (obj["thought"]?.jsonPrimitive?.booleanOrNull == true) null
                        else obj["text"]?.jsonPrimitive?.contentOrNull
                    }?.lastOrNull()

                    if (!textPart.isNullOrBlank()) {
                        logger.info("Gemini LLM success using model: $model")
                        return@withContext textPart
                    }
                } else {
                    lastError = "ERROR_HTTP_$statusCode"
                    logger.warn("Gemini API ($statusCode) [$model attempt ${index + 1}]:$rawBody")
                    if (statusCode == 503 || statusCode == 429) delay(1000)
                }
            } catch (e: Exception) {
                lastError = "ERROR_EXCEPTION: ${e.message}"
                logger.warn("Gemini exception [$model]:${e.message}")
            }
        }
        return@withContext lastError
    }

    private data class ReportsSnapshot(
        val allowedCodes: Set<String>,
        val count1a: Int,
        val count1b: Int,
        val headOfficeSummary: String
    )

    private fun fetchReportsSnapshot(): ReportsSnapshot {
        val allowed = mutableSetOf<String>()
        var c1a = 0
        var c1b = 0
        var hoSummary = ""

        try {
            TursoDatabaseService.getConnection()?.use { conn ->
                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery("SELECT a, b, g FROM reports WHERE id = '1'")
                    if (rs.next()) {
                        val jsonA = rs.getString("a") ?: "[]"
                        val jsonB = rs.getString("b") ?: "[]"
                        val jsonG = rs.getString("g") ?: "[]"

                        val listA = try { jsonParser.decodeFromString<List<String>>(jsonA).map { it.trim().uppercase() }.filter { it.isNotBlank() } } catch (_: Exception) { emptyList() }
                        val listB = try { jsonParser.decodeFromString<List<String>>(jsonB).map { it.trim().uppercase() }.filter { it.isNotBlank() } } catch (_: Exception) { emptyList() }

                        c1a = listA.size
                        c1b = listB.size
                        allowed.addAll(listA)
                        allowed.addAll(listB)

                        try {
                            val gArray = jsonParser.parseToJsonElement(jsonG).jsonArray
                            hoSummary = gArray.joinToString("\n") { deptEl ->
                                val dObj = deptEl.jsonObject
                                val dName = dObj["departmentName"]?.jsonPrimitive?.contentOrNull ?: ""
                                val dCount = dObj["totalStaffCount"]?.jsonPrimitive?.intOrNull ?: 0
                                val sList = dObj["staffList"]?.jsonArray?.joinToString(", ") { sEl ->
                                    val s = sEl.jsonObject
                                    val id = s["a3"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val nm = s["a4"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val des = s["a8"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val sal = s["a48"]?.jsonPrimitive?.contentOrNull ?: ""
                                    "$nm ($id, $des, ₹$sal)"
                                } ?: ""
                                "• $dName ($dCount staff):$sList"
                            }
                        } catch (_: Exception) {}
                    }
                    rs.close()
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch reports snapshot: ${e.message}")
        }
        return ReportsSnapshot(allowed, c1a, c1b, hoSummary)
    }

    /**
     * Dynamically inspects all tables in Turso DB (salary history, comments, approvals, etc.)
     * and fetches matching rows for the matched active employees or search keywords.
     */
    private fun fetchSalaryHistoryAndOtherTables(
        matchedEmpIds: List<String>,
        keywords: List<String>
    ): String {
        val sb = StringBuilder()
        try {
            TursoDatabaseService.getConnection()?.use { conn ->
                // 1. Discover all tables and their columns in Turso DB
                val tableSchemas = mutableMapOf<String, List<String>>()
                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
                    )
                    val tableNames = mutableListOf<String>()
                    while (rs.next()) {
                        tableNames.add(rs.getString("name"))
                    }
                    rs.close()

                    for (tbl in tableNames) {
                        if (tbl.equals("MasterData", ignoreCase = true) ||
                            tbl.equals("reports", ignoreCase = true) ||
                            tbl.equals("ai_interaction_logs", ignoreCase = true)
                        ) continue

                        try {
                            val colRs = stmt.executeQuery("PRAGMA table_info(\"$tbl\")")
                            val cols = mutableListOf<String>()
                            while (colRs.next()) {
                                cols.add(colRs.getString("name"))
                            }
                            colRs.close()
                            if (cols.isNotEmpty()) {
                                tableSchemas[tbl] = cols
                            }
                        } catch (_: Exception) {}
                    }
                }

                if (tableSchemas.isNotEmpty()) {
                    sb.append("\nAvailable Additional Database Tables & Columns:\n")
                    tableSchemas.forEach { (tbl, cols) ->
                        sb.append("- Table `$tbl`: columns = ${cols.joinToString(", ")}\n")
                    }
                }

                // 2. Query matching rows across salary & other tables for matched EmpIDs or Keywords
                val searchTerms = (matchedEmpIds + keywords).map { it.trim() }.filter { it.length >= 3 }.distinct().take(5)
                if (searchTerms.isNotEmpty()) {
                    for ((tbl, cols) in tableSchemas) {
                        val candidateCols = cols.filter { c ->
                            val lc = c.lowercase()
                            lc in setOf("a3", "emp_id", "empid", "employee_id", "code", "a4", "name", "employee_name", "status", "month", "year", "comment", "remarks") ||
                            lc.startsWith("a") && lc.length <= 3
                        }.ifEmpty { cols.take(6) }

                        val whereClause = candidateCols.joinToString(" OR ") { "\"$it\" LIKE ?" }
                        val sql = "SELECT * FROM \"$tbl\" WHERE $whereClause ORDER BY rowid DESC LIMIT 15"

                        for (term in searchTerms) {
                            try {
                                conn.prepareStatement(sql).use { ps ->
                                    for (i in candidateCols.indices) {
                                        ps.setString(i + 1, "%$term%")
                                    }
                                    val rs = ps.executeQuery()
                                    val rowsText = mutableListOf<String>()
                                    while (rs.next()) {
                                        val rowPairs = cols.mapNotNull { colName ->
                                            val v = rs.getString(colName)?.trim()
                                            if (!v.isNullOrBlank() && v != "0" && v != "null") "$colName=$v" else null
                                        }
                                        if (rowPairs.isNotEmpty()) {
                                            rowsText.add("  * " + rowPairs.take(18).joinToString(" | "))
                                        }
                                    }
                                    rs.close()
                                    if (rowsText.isNotEmpty()) {
                                        sb.append("\nRecords from `$tbl` matching '$term' (Latest first):\n")
                                        sb.append(rowsText.joinToString("\n")).append("\n")
                                        break // Avoid duplicate queries on same table
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error fetching salary history & other tables: ${e.message}")
        }
        return sb.toString()
    }

    private fun extractKeywordsFromHistory(messages: List<AiChatTurn>): List<String> {
        val recentText = messages.takeLast(3).joinToString(" ") { it.text }
        val empCodes = Regex("ADKR\\d+", RegexOption.IGNORE_CASE).findAll(recentText).map { it.value.uppercase() }.toList()

        val stopWords = setOf(
            "what", "is", "the", "of", "for", "in", "to", "a", "an", "tell", "me", "about", "show", "give",
            "salary", "branch", "designation", "joining", "date", "staff", "employee", "details", "info",
            "draft", "letter", "warning", "transfer", "notice", "absconding", "experience", "cause",
            "ka", "ra", "nka", "nkara", "kete", "kouthi", "achhanti", "achhi", "data", "diantu", "kahantu",
            "please", "sir", "mobile", "number", "phone", "email", "department", "post", "keun", "kebe",
            "adhikar", "ketoti", "kebala", "sabu", "list", "karantu", "lekha", "lekhantu", "tanka", "tankara",
            "bank", "account", "ifsc", "pan", "aadhaar", "blood", "group", "father", "name", "qualification",
            "history", "last", "month", "previous", "increment", "pending", "comment", "table"
        )

        val cleaned = recentText.replace(Regex("[^a-zA-Z0-9\\s]"), " ")
        val words = cleaned.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 3 && it.lowercase() !in stopWords }
            .distinct()

        return (empCodes + words).distinct().take(6)
    }

    private fun buildEmergencyFallbackResponse(searchResults: List<MasterDataDto>): AiAssistantResponse {
        if (searchResults.isEmpty()) {
            return AiAssistantResponse(
                reply = "Khyama karibe, ehi naam ba EmpID re kounasi Active Staff (1a & 1b list re) milila nahi. Dayakari exact Name, Branch ki ADKR code lekhantu."
            )
        }
        if (searchResults.size > 1) {
            return AiAssistantResponse(
                reply = "Ei search re ${searchResults.size} jana Active (1a/1b) staff milile. Dayakari tala option ru jananku select karantu:",
                quickOptions = searchResults.take(10).map { "${it.employeeName} (${it.empId})" }
            )
        }
        val emp = searchResults.first()
        val sal = emp.a48.ifBlank { emp.salaryCode }.ifBlank { "N/A" }
        return AiAssistantResponse(
            reply = "${emp.employeeName} (${emp.empId}) वर्तमान ${emp.branchName} branch re ${emp.designation} (${emp.department}) bhabare kama karuchhanti. Tanka salary ₹$sal, joining date ${emp.dojInAdhikar}, o mobile number ${emp.personalContactNumber}.",
            quickOptions = listOf("Salary History ${emp.empId}", "Draft Warning Letter ${emp.empId}")
        )
    }

    suspend fun processHrAssistant(request: AiAssistantRequest): AiAssistantResponse = withContext(Dispatchers.IO) {
        val lastUserMessage = request.messages.lastOrNull()?.text?.trim() ?: ""
        if (lastUserMessage.isBlank()) {
            return@withContext AiAssistantResponse(reply = "Namaskar! Mu AdhikarHR AI Assistant. Apana kounasi Active staff, salary history, pending approvals, branch ba HR letter bisayare pachariparibe.")
        }

        val snapshot = fetchReportsSnapshot()
        val allowedActiveCodes = snapshot.allowedCodes
        val keywords = extractKeywordsFromHistory(request.messages)

        val matchedMap = linkedMapOf<String, MasterDataDto>()
        for (kw in keywords) {
            if (kw.isBlank()) continue
            try {
                TursoDatabaseService.searchRecords(kw)
                    .filter { allowedActiveCodes.isEmpty() || it.empId.trim().uppercase() in allowedActiveCodes }
                    .forEach { emp ->
                        matchedMap[emp.empId.trim().uppercase()] = emp
                    }
            } catch (_: Exception) {}
        }

        val searchResults = matchedMap.values.take(30).toList()
        val matchedEmpIds = searchResults.map { it.empId.trim().uppercase() }

        // Fetch Salary History & Other Tables data dynamically
        val extraTablesContext = fetchSalaryHistoryAndOtherTables(matchedEmpIds, keywords)

        val dynamicTemplates = mutableListOf<String>()
        try {
            TursoDatabaseService.getConnection()?.use { conn ->
                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery("SELECT category, sample_prompt, expected_behavior_or_template FROM ai_prompt_templates")
                    while (rs.next()) {
                        val cat = rs.getString("category") ?: ""
                        val prompt = rs.getString("sample_prompt") ?: ""
                        val expected = rs.getString("expected_behavior_or_template") ?: ""
                        dynamicTemplates.add("- [$cat] When asked '$prompt':$expected")
                    }
                    rs.close()
                }
            }
        } catch (_: Exception) {}

        val templatesSection = if (dynamicTemplates.isNotEmpty()) {
            "\nCustom HR Rules/Templates:\n" + dynamicTemplates.joinToString("\n")
        } else ""

        val staffContext = if (searchResults.isNotEmpty()) {
            "\nMatched Active Staff Records from `MasterData` (1a & 1b Verified - ${searchResults.size} records):\n" + searchResults.joinToString("\n") {
                "- EmpId(a3): ${it.empId} | Name(a4): ${it.employeeName} | Father(a5): ${it.fathersName} | Gender(a6): ${it.gender} | Desig(a8): ${it.designation} | Dept(a9): ${it.department} | Branch(a10): ${it.branchName} | DOJ(a11): ${it.dojInAdhikar} | CurrentGrossSalary(a48): ₹${it.a48.ifBlank { it.salaryCode }} | Mobile(a15): ${it.personalContactNumber} | Email(a53): ${it.email61Personal.ifBlank { it.mailIdOffice }} | Qual(a14): ${it.highestEducationalQualification} | PAN(a18): ${it.panCardNo} | Aadhaar(a17): ${it.aadharCardNo} | Bank(a20): ${it.bankName} A/C(a21): ${it.bankAccountNumber} IFSC(a22): ${it.ifscCodeBankBranchName} | Blood(a29): ${it.bloodGroup} | SalarySerialHistoryCodes(a64): ${it.salaryHistory}"
            }
        } else {
            "\nMatched Active Staff Records: No direct keyword match for $keywords."
        }

        val hoContext = if (snapshot.headOfficeSummary.isNotBlank()) {
            "\nComplete Head Office Active Staff Directory (from `reports` 1g, Sorted by Salary Ascending):\n${snapshot.headOfficeSummary}"
        } else ""

        val systemInstruction = """
            You are AdhikarHR AI Assistant, a full-fledged, intelligent conversational LLM for Adhikar Microfinance Pvt. Ltd.
            You understand Odia (Odia script), Romanized Odia (Odia in English letters), and English. Always reply in the exact language/script the user used.

            DATABASE STRUCTURE & FIELD MAPPING GUIDE (Understand how AdhikarHR tables work):
            1. `MasterData` (Active Staff verified in `reports` 1a & 1b):
               - `a3` = Employee ID (e.g., ADKR1146), `a4` = Full Name, `a5` = Father's Name, `a8` = Designation, `a9` = Department, `a10` = Branch Name, `a11` = Joining Date (Excel serial or date), `a14` = Qualification, `a15` = Mobile, `a17` = Aadhaar, `a18` = PAN, `a20/a21/a22` = Bank/Account/IFSC, `a29` = Blood Group, `a48` = Current Monthly Gross Salary, `a53` = Email.
               - `a64` = Tilde-separated (`~`) list of Salary Serial IDs representing the employee's historical monthly payroll records (chronological from oldest to newest).
            2. `reports` Table (`id = '1'`):
               - `a` (`1a`) = Active staff who received salary in the last 2 months (${snapshot.count1a} staff).
               - `b` (`1b`) = Master active staff list (${snapshot.count1b} staff).
               - `g` (`1g`) = Head Office department-wise directory sorted by salary ascending.
            3. Salary History & Other Tables (provided below when matched):
               - Any rows from `salary` / payroll tables show monthly salary disbursements, deductions (PF/ESI/Advance), net payable, and month/year.
               - Any rows from `comments`, `approvals`, `joining`, or other tables show HR remarks, pending approvals, status updates, or disciplinary notes.

            LIVE DATABASE CONTEXT:
            $hoContext$staffContext
            $extraTablesContext$templatesSection

            CAPABILITIES & RULES:
            1. FULL CONVERSATIONAL INTELLIGENCE:
               - Answer any question about employee salary, month-by-month salary history, increments, deductions, HR comments/approvals, qualification, bank/PAN/Aadhaar, blood group, branch, department, Head Office structure, or comparisons naturally and accurately.
               - Use conversation history to answer follow-up questions (e.g. "Tanka salary history dekha", "Last month net salary kete thila?").
            2. STRICT 1a & 1b ACTIVE RULE:
               - Only provide employee data for staff verified in the Active (1a & 1b) records. If someone is not in the active list, politely inform the user.
            3. DISAMBIGUATION:
               - If multiple active staff match a first name and the user hasn't specified which one, mention them in `reply` and put `"Full Name (EmpID)"` in `quickOptions`.
            4. HR LETTER DRAFTING:
               - When asked to draft/write a Warning Letter, Show Cause Notice, Absconding Notice, Transfer Letter, or Experience Certificate, populate `letterDraft` (`letterType` = "WARNING", "SHOW_CAUSE", "ABSCONDING_NOTICE", "TRANSFER", or "EXPERIENCE") with a complete formal letter and summarize in `reply`. Otherwise set `letterDraft` to `null`.
            5. HELPFUL FOLLOW-UPS:
               - Always include 2–3 relevant next-step suggestions in `quickOptions`.

            Return ONLY a valid JSON object matching this exact schema:
            {
              "reply": "Your complete, natural conversational LLM answer",
              "quickOptions": ["Option 1", "Option 2"],
              "letterDraft": null
            }
        """.trimIndent()

        val conversationPrompt = buildString {
            request.messages.takeLast(8).forEach { turn ->
                append("${turn.role.uppercase()}:${turn.text}\n")
            }
            append("ASSISTANT:")
        }

        val finalResponse = try {
            withTimeout(45.seconds) {
                val rawResponseText = generateText(conversationPrompt, systemInstruction)
                if (rawResponseText.startsWith("ERROR_")) {
                    buildEmergencyFallbackResponse(searchResults)
                } else {
                    val cleanedJson = rawResponseText
                        .removePrefix("```json")
                        .removePrefix("```")
                        .removeSuffix("```")
                        .trim()
                    try {
                        jsonParser.decodeFromString<AiAssistantResponse>(cleanedJson)
                    } catch (_: Exception) {
                        AiAssistantResponse(reply = cleanedJson)
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("LLM call timed out, using fallback: ${e.message}")
            buildEmergencyFallbackResponse(searchResults)
        }

        try {
            TursoDatabaseService.getConnection()?.use { conn ->
                val sql = "INSERT INTO ai_interaction_logs (user_prompt, ai_reply, detected_intent, status, created_at) VALUES (?, ?, ?, ?, ?);"
                conn.prepareStatement(sql).use { ps ->
                    ps.setString(1, lastUserMessage)
                    ps.setString(2, finalResponse.reply)
                    ps.setString(3, finalResponse.letterDraft?.letterType ?: "GENERAL_CHAT")
                    ps.setString(4, "SUCCESS")
                    ps.setString(5, java.time.LocalDateTime.now().toString())
                    ps.executeUpdate()
                }
            }
        } catch (_: Exception) {}

        return@withContext finalResponse
    }

    suspend fun generateAiAssistantResponse(request: AiAssistantRequest): AiAssistantResponse = processHrAssistant(request)
}