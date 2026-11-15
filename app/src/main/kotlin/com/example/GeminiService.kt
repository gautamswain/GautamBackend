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
            readTimeout = 22_000
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
        // Google's official current models: 3.5-flash-lite is ultra-fast & rarely 503s, 3.8-flash is primary
        val models = listOf("gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.8-flash")
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
                        put("temperature", 0.6)
                    }
                }

                val (statusCode, rawBody) = postJsonHttp(endpoint, requestBody.toString())
                if (statusCode in 200..299) {
                    val jsonElement = jsonParser.parseToJsonElement(rawBody)
                    val partsArray = jsonElement.jsonObject["candidates"]?.jsonArray?.getOrNull(0)
                        ?.jsonObject?.get("content")?.jsonObject
                        ?.get("parts")?.jsonArray

                    // Extract the actual text part (skipping any internal thought part if present)
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
                    if (statusCode == 503 || statusCode == 429) delay(1200)
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
            "please", "sir", "mobile", "number", "phone", "email", "department", "post", "keun", "kebe",
            "head", "office", "adhikar", "ketoti", "kebala", "sabu", "list", "karantu", "lekha", "lekhantu"
        )

        val cleaned = message.replace(Regex("[^a-zA-Z0-9\\s]"), " ")
        val words = cleaned.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 3 && it.lowercase() !in stopWords }
            .distinct()

        return if (words.isNotEmpty()) words.take(4) else listOf(message.trim())
    }

    private fun buildEmergencyFallbackResponse(userMessage: String, searchResults: List<MasterDataDto>): AiAssistantResponse {
        if (searchResults.isEmpty()) {
            return AiAssistantResponse(
                reply = "Khyama karibe, ehi naam ba EmpID re kounasi Active Staff (1a & 1b list re) milila nahi. Dayakari exact Name ki ADKR code lekhantu."
            )
        }
        if (searchResults.size > 1) {
            return AiAssistantResponse(
                reply = "Ei naam re ${searchResults.size} jana Active (1a/1b) staff milile. Dayakari tala option ru jananku select karantu:",
                quickOptions = searchResults.map { "${it.employeeName} (${it.empId})" }
            )
        }
        val emp = searchResults.first()
        val sal = emp.a48.ifBlank { emp.salaryCode }.ifBlank { "N/A" }
        return AiAssistantResponse(
            reply = "${emp.employeeName} (${emp.empId}) वर्तमान ${emp.branchName} branch re ${emp.designation} (${emp.department}) bhabare kama karuchhanti. Tanka salary ₹$sal, joining date ${emp.dojInAdhikar}, o mobile number ${emp.personalContactNumber}.",
            quickOptions = listOf("Draft Warning Letter ${emp.empId}", "Draft Show Cause ${emp.empId}")
        )
    }

    suspend fun processHrAssistant(request: AiAssistantRequest): AiAssistantResponse = withContext(Dispatchers.IO) {
        val lastUserMessage = request.messages.lastOrNull()?.text?.trim() ?: ""
        if (lastUserMessage.isBlank()) {
            return@withContext AiAssistantResponse(reply = "Namaskar! Mu AdhikarHR AI Assistant. Apana kounasi Active staff nka bisayare pachariparibe ba HR letter draft kariparibe.")
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

        val searchResults = matchedMap.values.take(15).toList()

        // Load learned templates from DB if any
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
            "\nVerified Active Staff Records (from 1a & 1b Active List):\n" + searchResults.joinToString("\n") {
                "- EmpId: ${it.empId}, Name: ${it.employeeName}, Gender:${it.gender}, Designation: ${it.designation}, Dept:${it.department}, Branch: ${it.branchName}, JoiningDate:${it.dojInAdhikar}, Salary: ₹${it.a48.ifBlank { it.salaryCode }}, Mobile:${it.personalContactNumber}, Email: ${it.email61Personal.ifBlank { it.mailIdOffice }}, Father:${it.fathersName}, Qualification: ${it.highestEducationalQualification}, BloodGroup:${it.bloodGroup}"
            }
        } else {
            "\nVerified Active Staff Records (1a & 1b): No active employee matched keywords $keywords."
        }

        val systemInstruction = """
            You are AdhikarHR AI Assistant, an intelligent, warm, conversational HR colleague for Adhikar Microfinance Pvt. Ltd.
            You speak natural Odia (in Odia script), Romanized Odia (Odia in English alphabet), and English—always reply in the exact language/script the user used.

            HOW TO RESPOND LIKE A SMART LLM:
            1. Answer the user's specific question naturally and conversationally instead of dumping a rigid bullet list.
               - Example: If the user asks "Gautam nka salary kete?", reply naturally like: "Gautam Charan Swain (ADKR1146) nka current monthly salary ₹40,000 achhi. Se Head Office re Human Resources department re Assistant Manager bhabare achhanti."
               - If the user asks for full details, explain them warmly and clearly.
               - If the user greets you or asks a general HR question, chat naturally and helpfully.
            2. STRICT 1a & 1b ACTIVE STAFF RULE:
               - Only use employee facts from the "Verified Active Staff Records (from 1a & 1b Active List)" below.
               - If the user asks about a specific employee who is NOT in the list below, politely inform them that this person is not in the current Active (1a & 1b) staff list.
            3. DISAMBIGUATION:
               - If multiple employees match the user's query (e.g., 3 people named Basanta) and the user hasn't specified which one, mention them naturally in `reply` and populate `quickOptions` with `["Full Name (EmpID)", ...]`.
            4. HR LETTER DRAFTING:
               - When asked to write/draft a Warning Letter, Show Cause Notice, Absconding Notice, Transfer Order, or Experience Certificate, write a polished, complete formal letter inside `letterDraft` (`letterType` = "WARNING", "SHOW_CAUSE", "ABSCONDING_NOTICE", "TRANSFER", or "EXPERIENCE") and explain it briefly in `reply`. Otherwise set `letterDraft` to `null`.
            $staffContext$templatesSection

            Return ONLY a valid JSON object matching this exact structure:
            {
              "reply": "Your natural, conversational LLM response here",
              "quickOptions": ["Suggested follow-up 1", "Suggested follow-up 2"],
              "letterDraft": null
            }
        """.trimIndent()

        val conversationPrompt = buildString {
            request.messages.takeLast(6).forEach { turn ->
                append("${turn.role.uppercase()}:${turn.text}\n")
            }
            append("ASSISTANT:")
        }

        val finalResponse = try {
            withTimeout(40.seconds) {
                val rawResponseText = generateText(conversationPrompt, systemInstruction)
                if (rawResponseText.startsWith("ERROR_")) {
                    buildEmergencyFallbackResponse(lastUserMessage, searchResults)
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
            buildEmergencyFallbackResponse(lastUserMessage, searchResults)
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