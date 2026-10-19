package com.example

import com.google.ai.client.generativeai.GenerativeModel
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

// Data Transfer Objects
@Serializable
data class ReminderDto(
    val id: Int,
    val id1: Int = 0,
    val id2: Int = 0,
    val title: String,
    val timeInfo: String,
    val isCompleted: Boolean = false
)

@Serializable
data class MoonPhaseResponse(
    val rawMnData: String
)

@Serializable
data class GeminiRequest(
    val prompt: String
)

@Serializable
data class GeminiResponse(
    val reply: String
)

// Gemini AI Service
object GeminiService {
    private val logger = LoggerFactory.getLogger(GeminiService::class.java)
    private val apiKey: String = System.getenv("GEMINI_API_KEY") ?: ""

    private val generativeModel by lazy {
        GenerativeModel(
            modelName = "gemini-1.5-flash",
            apiKey = apiKey
        )
    }

    suspend fun generateText(prompt: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext "GEMINI_API_KEY set heini Render environment variables re!"
        }
        try {
            logger.info("Sending prompt to Gemini: {}", prompt)
            val response = generativeModel.generateContent(prompt)
            response.text ?: "No response generated."
        } catch (e: Exception) {
            logger.error("Gemini call failed: ${e.message}", e)
            "Error: ${e.message}"
        }
    }
}

// Google Sheets Service
object GoogleSheetsBackendService {
    private const val SPREADSHEET_ID = "1OuxRl0_vYx2qHKbLt8SHq6aLgvXpn4F4OD8UVdVding"
    private val jsonFactory = GsonFactory.getDefaultInstance()
    private val logger = LoggerFactory.getLogger(GoogleSheetsBackendService::class.java)

    private val sheetsService: Sheets? by lazy {
        try {
            val httpTransport = GoogleNetHttpTransport.newTrustedTransport()
            val envCredentials = System.getenv("GOOGLE_CREDENTIALS_JSON")
            val inputStream = if (!envCredentials.isNullOrBlank()) {
                envCredentials.byteInputStream()
            } else {
                Thread.currentThread().contextClassLoader.getResourceAsStream("credentials.json")
            }

            if (inputStream == null) {
                logger.error("credentials.json missing!")
                null
            } else {
                val credentials = GoogleCredentials.fromStream(inputStream)
                    .createScoped(listOf(SheetsScopes.SPREADSHEETS))

                val requestInitializer = HttpRequestInitializer { httpRequest ->
                    HttpCredentialsAdapter(credentials).initialize(httpRequest)
                    httpRequest.connectTimeout = 5000
                    httpRequest.readTimeout = 5000
                }

                Sheets.Builder(httpTransport, jsonFactory, requestInitializer)
                    .setApplicationName("Ktor Backend Sheets Integration")
                    .build()
            }
        } catch (e: Exception) {
            logger.error("Sheets Init Failed: ${e.message}", e)
            null
        }
    }

    suspend fun readCell(cellRange: String): String? = withContext(Dispatchers.IO) {
        val service = sheetsService ?: return@withContext null
        try {
            val response = service.spreadsheets().values()
                .get(SPREADSHEET_ID, cellRange)
                .execute()
            response.getValues()?.firstOrNull()?.firstOrNull()?.toString()
        } catch (e: Exception) {
            null
        }
    }
}

fun parseRemindersFromRawString(rawString: String): List<ReminderDto> {
    if (rawString.isBlank()) return emptyList()
    val entries = rawString.split("~").filter { it.isNotBlank() }
    return entries.mapIndexedNotNull { index, entry ->
        val parts = entry.trim().split("#")
        if (parts.size >= 2) {
            ReminderDto(
                id = parts[0].toIntOrNull() ?: (index + 1),
                id1 = parts.getOrNull(1)?.toIntOrNull() ?: 0,
                id2 = parts.getOrNull(2)?.toIntOrNull() ?: 0,
                title = parts.getOrNull(3)?.trim() ?: "",
                timeInfo = parts.getOrNull(4)?.trim() ?: "",
                isCompleted = false
            )
        } else null
    }
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/") }
    }

    install(ContentNegotiation) {
        json()
    }

    routing {
        get("/") {
            call.respondText("Welcome to Ktor Backend!")
        }

        get("/api/reminders") {
            val rawA2Value = GoogleSheetsBackendService.readCell("Sheet1!A2")
            val reminders = if (!rawA2Value.isNullOrBlank()) {
                parseRemindersFromRawString(rawA2Value)
            } else {
                parseRemindersFromRawString("1#1#3#Maha Ashtami#(19-10-2026-Monday) -> Today~")
            }
            call.respond(reminders)
        }

        get("/api/moon-phase") {
            val rawA3Value = GoogleSheetsBackendService.readCell("Sheet1!A3")
                ?: "0~1~2~3~4~5~6~7~8~9~10~11~12~13~14~15~15~15~15~15"
            call.respond(MoonPhaseResponse(rawMnData = rawA3Value))
        }

        // Gemini AI API GET Route (Browser Test Pain)
        get("/api/chat") {
            val prompt = call.request.queryParameters["prompt"] ?: "Hello Gemini"
            val aiReply = GeminiService.generateText(prompt)
            call.respond(GeminiResponse(reply = aiReply))
        }

        // Gemini AI API POST Route (App / Postman Pain)
        post("/api/chat") {
            val request = call.receive<GeminiRequest>()
            val aiReply = GeminiService.generateText(request.prompt)
            call.respond(GeminiResponse(reply = aiReply))
        }
    }
}