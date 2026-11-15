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
            connectTimeout = 6_000
            readTimeout = 10_000 // Maximum 10s wait per model so it never hangs
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
        val models = listOf("gemini-3.8-flash", "gemini-flash-lite-latest")
        var lastError = "ERROR_API_FAIL"

        for (model in models) {
            try {
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

                val requestBody = buildJsonObject {
                    if (systemInstruction.isNotBlank()) {
                        putJsonObject("systemInstruction") {
                            putJsonObject("parts") {
                                put("text", systemInstruction)
                            }
                        }
                    }
                    putJsonArray("contents") {
                        addJsonObject {
                            putJsonArray("parts") {
                                addJsonObject {
                                    put("text", prompt)
                                }
                            }
                        }
                    }
                    putJsonObject("generationConfig") {
                        put("responseMimeType", "application/json")
                        put("temperature", 0.2)
                        putJsonObject("thinkingConfig") {
                            put("thinkingBudget", 0)
                        }
                    }
                }

                val (statusCode, rawBody) = postJsonHttp(endpoint, requestBody.toString())
                if (statusCode in 200..299) {
                    val jsonElement = jsonParser.parseToJsonElement(rawBody)
                    val textPart = jsonElement.jsonObject["candidates"]?.jsonArray?.getOrNull(0)
                        ?.jsonObject?.get("content")?.jsonObject
                        ?.get("parts")?.jsonArray?.getOrNull(0)
                        ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull

                    if (!textPart.isNullOrBlank()) {
                        return@withContext textPart
                    }
                } else {
                    lastError = "ERROR_HTTP_$statusCode"
                    logger.warn("Gemini API ($statusCode) [$model]:$rawBody")
                    if (statusCode == 503) delay(800)
                }
            } catch (e: Exception) {
                lastError = "ERROR_EXCEPTION: ${e.message}"
                logger.warn("Gemini exception [$model]:${e.message}")
            }
        }
        return@withContext lastError
    }

    private fun fetchAllowedActiveCodes(): Set<String> {
        val allowed = mutableSetOf<String>()
        try {
            TursoDatabaseService.getConnection()?.use { conn ->
                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery("SELECT a, b FROM reports WHERE id = '1'")
                    if (rs.next()) {
                        val jsonA = rs.getString("a") ?: "[]"
                        val jsonB = rs.getString("b") ?: "[]"

                        val listA = jsonParser.decodeFromString<List<String>>(jsonA).map { it.trim().uppercase() }.filter { it.isNotBlank() }
                        val listB = jsonParser.decodeFromString<List<String>>(jsonB).map { it.trim().uppercase() }.filter { it.isNotBlank() }

                        allowed.addAll(listA)
                        allowed.addAll(listB)
                    }
                    rs.close()
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch 1a & 1b reports: ${e.message}")
        }
        return allowed
    }

    private fun extractKeywords(message: String): List<String> {
        val empCodeMatch = Regex("ADKR\\d+", RegexOption.IGNORE_CASE).find(message)?.value
        if (!empCodeMatch.isNullOrBlank()) return listOf(empCodeMatch.uppercase())

        val stopWords = setOf(
            "what", "is", "the", "of", "for", "in", "to", "a", "an", "tell", "me", "about", "show", "give",
            "salary", "branch", "designation", "joining", "date", "staff", "employee", "details", "info",
            "draft", "letter", "warning", "transfer", "notice", "absconding", "experience", "cause",
            "ka", "ra", "nka", "nkara", "kete", "kouthi", "achhanti", "achhi", "data", "diantu", "kahantu",
            "please", "sir", "mobile", "number", "phone", "email", "department", "post", "keun", "kebe"
        )

        val cleaned = message.replace(Regex("[^a-zA-Z0-9\\s]"), " ")
        val words = cleaned.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 3 && it.lowercase() !in stopWords }
            .distinct()

        return if (words.isNotEmpty()) words.take(3) else listOf(message.trim())
    }

    // 100% Instant Local Smart Engine (Works in 0.5s even if Gemini API is 503 or Timed Out)
    private fun buildInstantLocalResponse(userMessage: String, searchResults: List<MasterDataDto>): AiAssistantResponse {
        val lowerMsg = userMessage.lowercase()

        if (searchResults.isEmpty()) {
            return AiAssistantResponse(
                reply = "Ei naam ba EmpID re kounasi Active Staff (1a & 1b list re) milila nahi. Dayakari exact Name ki ADKR code (e.g. ADKR1146) lekhantu."
            )
        }

        if (searchResults.size > 1) {
            val options = searchResults.map { "${it.employeeName} (${it.empId})" }
            return AiAssistantResponse(
                reply = "Ei naam re ${searchResults.size} jana Active (1a/1b) staff milile. Dayakari tala option ru jananku select karantu:",
                quickOptions = options
            )
        }

        val emp = searchResults.first()
        val sal = emp.a48.ifBlank { emp.salaryCode }.ifBlank { "N/A" }
        val dept = emp.department.ifBlank { "N/A" }
        val branch = emp.branchName.ifBlank { "N/A" }
        val desig = emp.designation.ifBlank { "N/A" }
        val doj = emp.dojInAdhikar.ifBlank { "N/A" }
        val mob = emp.personalContactNumber.ifBlank { "N/A" }
        val mail = emp.email61Personal.ifBlank { emp.mailIdOffice }.ifBlank { "hr@adhikarindia.org" }

        // Check if user requested a letter draft
        val wantsLetter = lowerMsg.contains("letter") || lowerMsg.contains("notice") ||
                          lowerMsg.contains("warning") || lowerMsg.contains("show cause") ||
                          lowerMsg.contains("absconding") || lowerMsg.contains("transfer") ||
                          lowerMsg.contains("experience")

        if (wantsLetter) {
            val letterType = when {
                lowerMsg.contains("abscond") -> "ABSCONDING_NOTICE"
                lowerMsg.contains("show") || lowerMsg.contains("cause") -> "SHOW_CAUSE"
                lowerMsg.contains("transfer") -> "TRANSFER"
                lowerMsg.contains("experience") -> "EXPERIENCE"
                else -> "WARNING"
            }
            val subject = "$letterType - ${emp.employeeName} (${emp.empId})"
            val body = """
                To,
                ${emp.employeeName}
                Emp ID: ${emp.empId}
                Designation: $desig
                Branch: $branch

                Subject: $subject

                Dear ${emp.employeeName},

                This is an official $letterType issued from HR Department, Adhikar Microfinance Pvt. Ltd. regarding your service record at $branch branch. Please report to the HR Department immediately with your explanation.

                Sincerely,
                HR Department
                Adhikar Microfinance
            """.trimIndent()

            return AiAssistantResponse(
                reply = "${emp.employeeName} (${emp.empId}) nka pain$letterType prastuta kara jaichhi:",
                letterDraft = HrLetterDraft(
                    empId = emp.empId,
                    employeeName = emp.employeeName,
                    recipientEmail = mail,
                    letterType = letterType,
                    subject = subject,
                    body = body
                )
            )
        }

        val summary = buildString {
            append("✅ Active Staff (1a/1b) Details:\n")
            append("• Name: ${emp.employeeName} (${emp.empId})\n")
            append("• Designation: $desig\n")
            append("• Department: $dept\n")
            append("• Branch: $branch\n")
            append("• Salary: ₹$sal\n")
            append("• Joining Date: $doj\n")
            append("• Mobile: $mob")
        }

        return AiAssistantResponse(
            reply = summary,
            quickOptions = listOf(
                "Draft Warning Letter ${emp.empId}",
                "Draft Show Cause ${emp.empId}",
                "Draft Transfer Letter ${emp.empId}"
            )
        )
    }

    suspend fun processHrAssistant(request: AiAssistantRequest): AiAssistantResponse = withContext(Dispatchers.IO) {
        val lastUserMessage = request.messages.lastOrNull()?.text?.trim() ?: ""
        if (lastUserMessage.isBlank()) {
            return@withContext AiAssistantResponse(reply = "Dayakari staff nka Name ba ADKR ID lekhantu.")
        }

        val allowedActiveCodes = fetchAllowedActiveCodes()
        val keywords = extractKeywords(lastUserMessage)

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

        val searchResults = matchedMap.values.take(12).toList()

        // Try Gemini with a strict 15-second total cap; if Gemini is slow/503, immediately return Local DB response!
        val finalResponse = try {
            withTimeout(15.seconds) {
                val staffContext = if (searchResults.isNotEmpty()) {
                    "\nVerified Active Staff (1a & 1b):\n" + searchResults.joinToString("\n") {
                        "EmpId: ${it.empId} | Name: ${it.employeeName} | Branch: ${it.branchName} | Dept: ${it.department} | Desig: ${it.designation} | Joining: ${it.dojInAdhikar} | Salary(a48): ${it.a48.ifBlank { it.salaryCode }} | Mobile: ${it.personalContactNumber} | Email: ${it.email61Personal.ifBlank { it.mailIdOffice }}"
                    }
                } else {
                    "\nVerified Active Staff (1a & 1b): None found."
                }

                val systemInstruction = """
                    You are AdhikarHR AI Assistant. Answer strictly using the Verified Active Staff (1a & 1b) data below.
                    If multiple staff match, put "Full Name (EmpID)" in quickOptions.
                    Reply in the user's language (Odia, Romanized Odia, or English).
                    $staffContext
                    Return ONLY JSON: {"reply": "...", "quickOptions": [], "letterDraft": null}
                """.trimIndent()

                val conversationPrompt = request.messages.takeLast(3).joinToString("\n") { "${it.role.uppercase()}:${it.text}" } + "\nASSISTANT:"
                val rawResponseText = generateText(conversationPrompt, systemInstruction)

                if (rawResponseText.startsWith("ERROR_")) {
                    buildInstantLocalResponse(lastUserMessage, searchResults)
                } else {
                    val cleanedJson = rawResponseText.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                    try {
                        jsonParser.decodeFromString<AiAssistantResponse>(cleanedJson)
                    } catch (_: Exception) {
                        buildInstantLocalResponse(lastUserMessage, searchResults)
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Gemini timed out or failed (${e.message}), using instant local DB response.")
            buildInstantLocalResponse(lastUserMessage, searchResults)
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