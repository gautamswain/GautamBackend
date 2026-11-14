package com.example

import com.google.genai.Client
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    private val apiKey: String = System.getenv("GEMINI_API_KEY") ?: ""

    private val client by lazy {
        if (apiKey.isNotBlank()) {
            Client.builder().apiKey(apiKey).build()
        } else null
    }

    suspend fun generateText(prompt: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || client == null) {
            return@withContext "Technical error: GEMINI_API_KEY environment variable is not configured."
        }
        try {
            logger.info("Sending prompt to Gemini: {}", prompt)
            
            // ✅ Fix: Updated to latest recommended model gemini-3.8-flash
            val response = client!!.models.generateContent("gemini-3.8-flash", prompt, null)
            
            response.text() ?: "No response generated."
        } catch (e: Exception) {
            logger.error("Gemini call failed: ${e.message}", e)
            "Technical error: ${e.message}"
        }
    }

    suspend fun processHrAssistant(request: AiAssistantRequest): AiAssistantResponse = withContext(Dispatchers.IO) {
        val lastUserMessage = request.messages.lastOrNull()?.text ?: ""
        try {
            withTimeout(20.seconds) {
                if (apiKey.isBlank() || client == null) {
                    return@withTimeout AiAssistantResponse(
                        reply = "Technical error: GEMINI_API_KEY environment variable is not configured."
                    )
                }

                // 2. Limit matching records to .take(10)
                val staffContext = if (lastUserMessage.isNotBlank()) {
                    try {
                        val results = TursoDatabaseService.searchRecords(lastUserMessage).take(10)
                        if (results.isNotEmpty()) {
                            "\n\nMatching Database Staff Records (Top 10):\n" + results.joinToString("\n") {
                                "EmpId: ${it.empId}, Name: ${it.employeeName}, Branch: ${it.branchName}, Designation: ${it.designation}, Phone: ${it.personalContactNumber}"
                            }
                        } else ""
                    } catch (_: Exception) { "" }
                } else ""

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
                    1. Employee queries & database lookup (staff details, branch, designation, salary).
                    2. Disambiguation when multiple employees match a query.
                    3. Salary update queueing & preview.
                    4. HR letter drafting (ABSCONDING_NOTICE, SHOW_CAUSE, WARNING, TRANSFER, EXPERIENCE).
                    $staffContext
                    $templatesInstruction
                    
                    If the user query is completely unclear, unrecognized, or outside HR capabilities, set reply to explain you didn't understand and suggest options, and we will log it as UNRECOGNIZED.
                    
                    You MUST return your response as a valid JSON object matching this exact schema:
                    {
                      "reply": "Conversational reply in the user's language (Odia/Romanized Odia/English)",
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

                // 1. Maximum 2 iterations limit for loop protection
                var iterations = 0
                var assistantResponse = AiAssistantResponse(reply = "Technical error: No valid response parsed.")

                while (iterations < 2) {
                    iterations++
                    val conversationPrompt = buildString {
                        append("$systemInstruction\n\nConversation History:\n")
                        request.messages.forEach { turn ->
                            append("${turn.role.uppercase()}: ${turn.text}\n")
                        }
                        append("ASSISTANT:")
                    }

                    val rawResponse = generateText(conversationPrompt)
                    val cleanedJson = rawResponse
                        .removePrefix("```json")
                        .removePrefix("```")
                        .removeSuffix("```")
                        .trim()

                    try {
                        val json = Json { ignoreUnknownKeys = true; isLenient = true }
                        assistantResponse = json.decodeFromString<AiAssistantResponse>(cleanedJson)
                        break
                    } catch (parseEx: Exception) {
                        if (iterations >= 2) {
                            throw parseEx
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