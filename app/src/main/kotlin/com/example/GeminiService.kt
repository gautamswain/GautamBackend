package com.example

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

@Serializable
data class AiChatTurn(
    val role: String, // "user" or "model"
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
    val letterType: String, // "ABSCONDING_NOTICE", "SHOW_CAUSE", "WARNING", "TRANSFER", "EXPERIENCE"
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

    val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
            })
        }
    }

    suspend fun generateText(prompt: String, systemInstruction: String = ""): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext "Technical error: GEMINI_API_KEY environment variable is not configured."
        }
        val models = listOf("gemini-3.8-flash", "gemini-2.5-flash", "gemini-2.0-flash", "gemini-flash-latest")
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
                        putJsonObject("thinkingConfig") {
                            put("thinkingBudget", 0)
                        }
                    }
                }

                val response = httpClient.post(endpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody.toString())
                }

                val rawBody = response.bodyAsText()
                if (response.status.isSuccess()) {
                    val jsonElement = Json.parseToJsonElement(rawBody)
                    val textPart = jsonElement.jsonObject["candidates"]?.jsonArray?.getOrNull(0)
                        ?.jsonObject?.get("content")?.jsonObject
                        ?.get("parts")?.jsonArray?.getOrNull(0)
                        ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull

                    if (!textPart.isNullOrBlank()) {
                        return@withContext textPart
                    }
                } else {
                    lastError = "Gemini API Error (${response.status.value}) for model $model: $rawBody"
                    logger.warn(lastError)
                }
            } catch (e: Exception) {
                lastError = "Technical error for model $model: ${e.localizedMessage ?: e.message}"
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

                        val listA = Json.decodeFromString<List<String>>(jsonA)
                        val listB = Json.decodeFromString<List<String>>(jsonB)

                        listA.forEach { if (it.isNotBlank()) allowed.add(it.trim().uppercase()) }
                        listB.forEach { if (it.isNotBlank()) allowed.add(it.trim().uppercase()) }
                    }
                    rs.close()
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch 1a & 1b reports for allowed active codes: ${e.message}")
        }
        return allowed
    }

    suspend fun processHrAssistant(request: AiAssistantRequest): AiAssistantResponse = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext AiAssistantResponse(
                reply = "Technical error: GEMINI_API_KEY environment variable is not configured."
            )
        }

        val lastUserMessage = request.messages.lastOrNull()?.text ?: ""
        try {
            withTimeout(60.seconds) {
                // 1. Strict 1a & 1b Active Staff Filter
                val allowedActiveCodes = fetchAllowedActiveCodes()

                val searchResults = try {
                    if (lastUserMessage.isNotBlank()) {
                        TursoDatabaseService.searchRecords(lastUserMessage)
                            .filter { it.empId.trim().uppercase() in allowedActiveCodes }
                            .take(10)
                    } else emptyList()
                } catch (_: Exception) { emptyList() }

                val staffContext = if (searchResults.isNotEmpty()) {
                    val disambiguationNote = if (searchResults.size > 1) {
                        "\nNOTE: Multiple active staff records matched (${searchResults.size} found). Please provide their full names and IDs in 'quickOptions' as 'Full Name (EmpID)' and ask the user to clarify."
                    } else ""

                    "\n\nMatching Active Database Staff Records ($disambiguationNote):\n" + searchResults.joinToString("\n") {
                        "EmpId: ${it.empId}, Name: ${it.employeeName}, Branch: ${it.branchName}, Designation: ${it.designation}, JoiningDate: ${it.dojInAdhikar}, SalaryCode(47): ${it.salaryCode}"
                    }
                } else "\n\nMatching Active Database Staff Records: None found in active 1a/1b list."

                // Fetch dynamic templates from database
                val dynamicTemplates = mutableListOf<String>()
                try {
                    TursoDatabaseService.getConnection()?.use { conn ->
                        conn.createStatement().use { stmt ->
                            val rs = stmt.executeQuery("SELECT category, sample_prompt, expected_behavior_or_template FROM ai_prompt_templates")
                            while (rs.next()) {
                                val cat = rs.getString("category") ?: ""
                                val prompt = rs.getString("sample_prompt") ?: ""
                                val expected = rs.getString("expected_behavior_or_template") ?: ""
                                dynamicTemplates.add("Category: $cat | Example Prompt: $prompt | Expected Template/Rule: $expected")
                            }
                            rs.close()
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Failed to load ai_prompt_templates: ${e.message}")
                }

                val templatesInstruction = if (dynamicTemplates.isNotEmpty()) {
                    "\n\nLearned Dynamic Templates & Rules from DB:\n" + dynamicTemplates.joinToString("\n")
                } else ""

                val systemInstruction = """
                    You are AdhikarHR AI Assistant for Adhikar microfinance HR management system.
                    You understand Odia, Romanized Odia (Odia in English script), and English.
                    You help HR managers with:
                    1. Employee queries & database lookup (staff details, branch, designation, joining date, salary).
                    2. Disambiguation: If multiple employees match a query (e.g., multiple Madhabas), list them in `quickOptions` as ["Full Name (EmpID)", ...] and ask the user to select.
                    3. Salary update queueing & preview.
                    4. HR letter drafting (ABSCONDING_NOTICE, SHOW_CAUSE, WARNING, TRANSFER, EXPERIENCE).
                    $staffContext
                    $templatesInstruction
                    
                    CRITICAL RULE: You only have access to Active Staff verified in 1a & 1b reports. If a staff member is not in this active list, clearly inform the user that they are not in the active 1a/1b staff list.
                    
                    Respond directly, accurately, and fluently in the user's language (Odia, Romanized Odia, or English).
                    If the user query is completely unclear or outside HR capabilities, set reply accordingly and log as UNRECOGNIZED.
                    
                    You MUST return your response as a valid JSON object matching this exact schema:
                    {
                      "reply": "Conversational reply in the user's language",
                      "quickOptions": ["Option 1", "Option 2"],
                      "letterDraft": {
                        "empId": "ADKRXXXX",
                        "employeeName": "Name",
                        "recipientEmail": "email@example.com",
                        "letterType": "ABSCONDING_NOTICE",
                        "subject": "Subject line",
                        "body": "Drafted letter content..."
                      }
                    }
                """.trimIndent()

                val conversationPrompt = buildString {
                    request.messages.forEach { turn ->
                        append("${turn.role.uppercase()}: ${turn.text}\n")
                    }
                    append("ASSISTANT:")
                }

                // Maximum 2 iterations limit for loop protection & fast execution
                var iterations = 0
                var assistantResponse = AiAssistantResponse(reply = "Technical error: No valid response parsed.")

                while (iterations < 2) {
                    iterations++
                    val rawResponseText = generateText(conversationPrompt, systemInstruction)
                    if (rawResponseText.startsWith("Technical error:") || rawResponseText.startsWith("Gemini API Error")) {
                        return@withTimeout AiAssistantResponse(reply = rawResponseText)
                    }

                    val cleanedJson = rawResponseText
                        .removePrefix("```json")
                        .removePrefix("```")
                        .removeSuffix("```")
                        .trim()

                    try {
                        val json = Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                            coerceInputValues = true
                        }
                        assistantResponse = json.decodeFromString<AiAssistantResponse>(cleanedJson)
                        break
                    } catch (_: Exception) {
                        if (iterations >= 2) {
                            assistantResponse = AiAssistantResponse(reply = cleanedJson)
                        }
                    }
                }

                val lowerReply = assistantResponse.reply.lowercase()
                val isUnrecognized = lowerReply.contains("bujhiparili nahi") ||
                                     lowerReply.contains("did not understand") ||
                                     lowerReply.contains("unknown") ||
                                     lowerReply.contains("unrecognized") ||
                                     lowerReply.contains("maf karantu")

                val status = if (isUnrecognized) "UNRECOGNIZED" else "SUCCESS"
                val detectedIntent = assistantResponse.letterDraft?.letterType ?: if (assistantResponse.quickOptions.isNotEmpty()) "QUERY_WITH_OPTIONS" else "GENERAL_CHAT"

                try {
                    TursoDatabaseService.getConnection()?.use { conn ->
                        val sql = "INSERT INTO ai_interaction_logs (user_prompt, ai_reply, detected_intent, status, created_at) VALUES (?, ?, ?, ?, ?);"
                        conn.prepareStatement(sql).use { ps ->
                            ps.setString(1, lastUserMessage)
                            ps.setString(2, assistantResponse.reply)
                            ps.setString(3, detectedIntent)
                            ps.setString(4, status)
                            ps.setString(5, java.time.LocalDateTime.now().toString())
                            ps.executeUpdate()
                        }
                    }
                } catch (logEx: Exception) {
                    logger.error("Failed to log ai interaction: ${logEx.message}")
                }

                assistantResponse
            }
        } catch (e: Exception) {
            logger.error("ProcessHrAssistant failed: ${e.message}", e)
            val errorReply = "Technical error: ${e.localizedMessage ?: e.message ?: "Unknown error"}"

            try {
                TursoDatabaseService.getConnection()?.use { conn ->
                    val sql = "INSERT INTO ai_interaction_logs (user_prompt, ai_reply, detected_intent, status, created_at) VALUES (?, ?, ?, ?, ?);"
                    conn.prepareStatement(sql).use { ps ->
                        ps.setString(1, lastUserMessage)
                        ps.setString(2, errorReply)
                        ps.setString(3, "ERROR")
                        ps.setString(4, "UNRECOGNIZED")
                        ps.setString(5, java.time.LocalDateTime.now().toString())
                        ps.executeUpdate()
                    }
                }
            } catch (_: Exception) {}

            AiAssistantResponse(reply = errorReply)
        }
    }

    suspend fun generateAiAssistantResponse(request: AiAssistantRequest): AiAssistantResponse = processHrAssistant(request)
}