package com.example

import kotlinx.coroutines.Dispatchers
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
            return@withContext "Technical error: GEMINI_API_KEY is missing in Render Environment Variables."
        }
        val models = listOf("gemini-3.8-flash", "gemini-flash-latest", "gemini-2.5-flash")
        var lastError = "No response generated."

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
                    }
                }

                val (statusCode, rawBody) = postJsonHttp(endpoint, requestBody.toString())
                if (statusCode in 200..299) {
                    val jsonElement = Json.parseToJsonElement(rawBody)
                    val textPart = jsonElement.jsonObject["candidates"]?.jsonArray?.getOrNull(0)
                        ?.jsonObject?.get("content")?.jsonObject
                        ?.get("parts")?.jsonArray?.getOrNull(0)
                        ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull

                    if (!textPart.isNullOrBlank()) {
                        return@withContext textPart
                    }
                } else {
                    lastError = "Gemini API Error ($statusCode) [$model]:$rawBody"
                    logger.warn(lastError)
                }
            } catch (e: Exception) {
                lastError = "Technical error [$model]:${e.localizedMessage ?: e.message}"
                logger.warn(lastError)
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

                        val listA = Json.decodeFromString<List<String>>(jsonA).map { it.trim().uppercase() }.filter { it.isNotBlank() }
                        val listB = Json.decodeFromString<List<String>>(jsonB).map { it.trim().uppercase() }.filter { it.isNotBlank() }

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
        val stopWords = setOf(
            "what", "is", "the", "of", "for", "in", "to", "a", "an", "tell", "me", "about", "show", "give",
            "salary", "branch", "designation", "joining", "date", "staff", "employee", "details", "info",
            "draft", "letter", "warning", "transfer", "notice", "absconding", "experience", "cause",
            "ka", "ra", "kete", "kouthi", "achhanti", "achhi", "data", "diantu", "kahantu", "please", "sir"
        )
        val empCodeMatch = Regex("ADKR\\d+", RegexOption.IGNORE_CASE).find(message)?.value
        if (!empCodeMatch.isNullOrBlank()) return listOf(empCodeMatch.uppercase())

        val cleaned = message.replace(Regex("[^a-zA-Z0-9\\s]"), " ")
        val words = cleaned.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 3 && it.lowercase() !in stopWords }
            .distinct()

        return if (words.isNotEmpty()) words.take(3) else listOf(message.trim())
    }

    suspend fun processHrAssistant(request: AiAssistantRequest): AiAssistantResponse = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext AiAssistantResponse(
                reply = "Technical error: GEMINI_API_KEY is not configured in Render."
            )
        }

        val lastUserMessage = request.messages.lastOrNull()?.text ?: ""
        try {
            withTimeout(55.seconds) {
                val allowedActiveCodes = fetchAllowedActiveCodes()
                val keywords = extractKeywords(lastUserMessage)

                val matchedMap = linkedMapOf<String, MasterDataDto>()
                for (kw in keywords) {
                    if (kw.isBlank()) continue
                    try {
                        TursoDatabaseService.searchRecords(kw)
                            .filter { it.empId.trim().uppercase() in allowedActiveCodes }
                            .forEach { emp ->
                                matchedMap[emp.empId.trim().uppercase()] = emp
                            }
                    } catch (_: Exception) {}
                }

                val searchResults = matchedMap.values.take(12)

                val staffContext = if (searchResults.isNotEmpty()) {
                    val disambiguationNote = if (searchResults.size > 1) {
                        "Multiple active staff matched (${searchResults.size}). If user asked about a specific person, list 'Full Name (EmpID)' in quickOptions."
                    } else "Exact 1 active staff matched."

                    "\n\nVerified Active Staff (1a & 1b) [$disambiguationNote]:\n" + searchResults.joinToString("\n") {
                        "EmpId: ${it.empId} | Name: ${it.employeeName} \vert{} Branch:${it.branchName} | Dept: ${it.department} \vert{} Desig:${it.designation} | Joining: ${it.dojInAdhikar} \vert{} Salary(a48):${it.a48.ifBlank { it.salaryCode }} | Mobile: ${it.mobile} \vert{} Email:${it.email}"
                    }
                } else {
                    "\n\nVerified Active Staff (1a & 1b): No matching record found for keywords $keywords."
                }

                val systemInstruction = """
                    You are AdhikarHR AI Assistant for Adhikar Microfinance.
                    You understand Odia, Romanized Odia (Odia in English letters), and English.
                    
                    STRICT RULES:
                    1. Answer ONLY using the Verified Active Staff (1a & 1b) data provided below. Never invent names, salaries, or dates.
                    2. If no matching record is found in the 1a & 1b list, clearly tell the user in their language that this person is not in the Active (1a/1b) staff list.
                    3. If multiple active employees match a name, list them in `quickOptions` as ["Full Name (EmpID)", ...] and ask the user to choose one.
                    4. If asked to draft a letter (ABSCONDING_NOTICE, SHOW_CAUSE, WARNING, TRANSFER, EXPERIENCE), fill `letterDraft` completely. Otherwise keep `letterDraft` null.
                    5. Reply in the exact same language the user used (Odia script, Romanized Odia, or English).
                    $staffContext
                    
                    Return ONLY valid JSON matching this schema:
                    {
                      "reply": "Your direct helpful answer",
                      "quickOptions": [],
                      "letterDraft": null
                    }
                """.trimIndent()

                val conversationPrompt = buildString {
                    request.messages.takeLast(4).forEach { turn ->
                        append("${turn.role.uppercase()}:${turn.text}\n")
                    }
                    append("ASSISTANT:")
                }

                val rawResponseText = generateText(conversationPrompt, systemInstruction)
                if (rawResponseText.startsWith("Technical error:") || rawResponseText.startsWith("Gemini API Error")) {
                    return@withTimeout AiAssistantResponse(reply = rawResponseText)
                }

                val cleanedJson = rawResponseText
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

                val jsonParser = Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    coerceInputValues = true
                }

                val assistantResponse = try {
                    jsonParser.decodeFromString<AiAssistantResponse>(cleanedJson)
                } catch (_: Exception) {
                    AiAssistantResponse(reply = cleanedJson)
                }

                try {
                    TursoDatabaseService.getConnection()?.use { conn ->
                        val sql = "INSERT INTO ai_interaction_logs (user_prompt, ai_reply, detected_intent, status, created_at) VALUES (?, ?, ?, ?, ?);"
                        conn.prepareStatement(sql).use { ps ->
                            ps.setString(1, lastUserMessage)
                            ps.setString(2, assistantResponse.reply)
                            ps.setString(3, assistantResponse.letterDraft?.letterType ?: "GENERAL_CHAT")
                            ps.setString(4, "SUCCESS")
                            ps.setString(5, java.time.LocalDateTime.now().toString())
                            ps.executeUpdate()
                        }
                    }
                } catch (_: Exception) {}

                assistantResponse
            }
        } catch (e: Exception) {
            logger.error("ProcessHrAssistant failed: ${e.message}", e)
            AiAssistantResponse(reply = "Technical error: ${e.localizedMessage ?: e.message ?: "Request failed"}")
        }
    }

    suspend fun generateAiAssistantResponse(request: AiAssistantRequest): AiAssistantResponse = processHrAssistant(request)
}