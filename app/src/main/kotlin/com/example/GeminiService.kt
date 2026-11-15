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
            requestTimeoutMillis = 25_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 25_000
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
        try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
            
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
                }
            }

            val response = httpClient.post(endpoint) {
                contentType(ContentType.Application.Json)
                setBody(requestBody.toString())
            }

            val rawBody = response.bodyAsText()
            if (!response.status.isSuccess()) {
                return@withContext "Gemini API Error (${response.status.value}): $rawBody"
            }

            val jsonElement = Json.parseToJsonElement(rawBody)
            // Safe extraction from candidates[0].content.parts[0].text
            val textPart = jsonElement.jsonObject["candidates"]?.jsonArray?.getOrNull(0)
                ?.jsonObject?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray?.getOrNull(0)
                ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull

            textPart ?: rawBody
        } catch (e: Exception) {
            logger.error("Gemini direct REST call failed: ${e.message}", e)
            "Technical error: ${e.localizedMessage ?: e.message}"
        }
    }

    suspend fun processHrAssistant(request: AiAssistantRequest): AiAssistantResponse = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext AiAssistantResponse(
                reply = "Technical error: GEMINI_API_KEY environment variable is not configured."
            )
        }

        val lastUserMessage = request.messages.lastOrNull()?.text ?: ""
        try {
            withTimeout(25.seconds) {
                // Pre-fetch matching staff records using TursoDatabaseService.searchRecords (limited to 10)
                val searchResults = try {
                    if (lastUserMessage.isNotBlank()) {
                        TursoDatabaseService.searchRecords(lastUserMessage).take(10)
                    } else emptyList()
                } catch (_: Exception) { emptyList() }

                val staffContext = if (searchResults.isNotEmpty()) {
                    val disambiguationNote = if (searchResults.size > 1) {
                        "\nNOTE: Multiple staff records matched (${searchResults.size} found). Please provide their full names and IDs in 'quickOptions' as 'Full Name (EmpID)' and ask the user to clarify."
                    } else ""

                    "\n\nMatching Database Staff Records ($disambiguationNote):\n" + searchResults.joinToString("\n") {
                        "EmpId: ${it.empId}, Name: ${it.employeeName}, Branch: ${it.branchName}, Designation: ${it.designation}, JoiningDate: ${it.dojInAdhikar}, SalaryCode(47): ${it.salaryCode}"
                    }
                } else "\n\nMatching Database Staff Records: None found."

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

                val rawResponseText = generateText(conversationPrompt, systemInstruction)
                if (rawResponseText.startsWith("Technical error:") || rawResponseText.startsWith("Gemini API Error")) {
                    return@withTimeout AiAssistantResponse(reply = rawResponseText)
                }

                val cleanedJson = rawResponseText
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

                val json = Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    coerceInputValues = true
                }

                val assistantResponse = try {
                    json.decodeFromString<AiAssistantResponse>(cleanedJson)
                } catch (_: Exception) {
                    // Safe JSON extraction fallback: if JSON parse fails, return raw text as reply instead of throwing/crashing
                    logger.warn("Failed to parse Gemini JSON response, returning raw text: $cleanedJson")
                    AiAssistantResponse(reply = cleanedJson)
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