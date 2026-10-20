package com.example

import com.google.genai.Client
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

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
}