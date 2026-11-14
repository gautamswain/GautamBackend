package com.example

import com.google.genai.Client
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

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
            return@withContext "GEMINI_API_KEY set heini Render environment variables re!"
        }
        try {
            logger.info("Sending prompt to Gemini: {}", prompt)
            
            // ✅ Fix: Updated to latest recommended model gemini-3.8-flash
            val response = client!!.models.generateContent("gemini-3.8-flash", prompt, null)
            
            response.text() ?: "No response generated."
        } catch (e: Exception) {
            logger.error("Gemini call failed: ${e.message}", e)
            "Error: ${e.message}"
        }
    }

    suspend fun generateAiAssistantResponse(request: AiAssistantRequest): AiAssistantResponse = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || client == null) {
            return@withContext AiAssistantResponse(
                reply = "GEMINI_API_KEY environment variable is not configured."
            )
        }
        try {
            val systemInstruction = """
                You are AdhikarHR AI Assistant for Adhikar microfinance HR management system.
                You understand Odia, Romanized Odia (Odia in English script), and English.
                You help HR managers with:
                1. Employee queries & database lookup (staff details, branch, designation, salary).
                2. Disambiguation when multiple employees match a query.
                3. Salary update queueing & preview.
                4. HR letter drafting (ABSCONDING_NOTICE, SHOW_CAUSE, WARNING, TRANSFER, EXPERIENCE).
                
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

            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            json.decodeFromString<AiAssistantResponse>(cleanedJson)
        } catch (e: Exception) {
            logger.error("AI Assistant generation failed: ${e.message}", e)
            AiAssistantResponse(
                reply = "Asst Error: ${e.localizedMessage ?: "Failed to process request"}"
            )
        }
    }
}