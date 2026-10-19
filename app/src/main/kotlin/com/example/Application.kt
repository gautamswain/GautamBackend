package com.example

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

// -------------------------------------------------------------
// Data Transfer Objects
// -------------------------------------------------------------
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

// -------------------------------------------------------------
// Google Sheets Service (Backend Middleman)
// -------------------------------------------------------------
object GoogleSheetsBackendService {
    //private const val SPREADSHEET_ID = "1OuxRl0_vYx2qHKbLt8SHq6aLgvXpn4F4OD8UVdVding"
    private const val SPREADSHEET_ID = "1OuxRl0_vYx2qHKbLt8SHq6aLgvXpn4F4OD8UVdVding"
    private val jsonFactory = GsonFactory.getDefaultInstance()

    private val sheetsService: Sheets by lazy {
        val httpTransport = GoogleNetHttpTransport.newTrustedTransport()
        val inputStream = Thread.currentThread().contextClassLoader.getResourceAsStream("credentials.json")
            ?: throw IllegalStateException("credentials.json not found in resources folder!")

        val credentials = GoogleCredentials.fromStream(inputStream)
            .createScoped(listOf(SheetsScopes.SPREADSHEETS))

        Sheets.Builder(httpTransport, jsonFactory, HttpCredentialsAdapter(credentials))
            .setApplicationName("Ktor Backend Sheets Integration")
            .build()
    }

    suspend fun readCell(cellRange: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val response = sheetsService.spreadsheets().values()
                .get(SPREADSHEET_ID, cellRange)
                .execute()
            response.getValues()?.firstOrNull()?.firstOrNull()?.toString()
        }.getOrNull()
    }
}

// -------------------------------------------------------------
// Parsing Logic
// -------------------------------------------------------------
fun parseRemindersFromRawString(rawString: String): List<ReminderDto> {
    if (rawString.isBlank()) return emptyList()

    val entries = rawString.split("~").filter { it.isNotBlank() }

    return entries.mapIndexedNotNull { index, entry ->
        val parts = entry.trim().split("#")
        if (parts.size >= 2) {
            val id = parts[0].toIntOrNull() ?: (index + 1)
            val id1 = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val id2 = parts.getOrNull(2)?.toIntOrNull() ?: 0
            val title = parts.getOrNull(3)?.trim() ?: ""
            val timeInfo = parts.getOrNull(4)?.trim() ?: ""
            ReminderDto(
                id = id,
                id1 = id1,
                id2 = id2,
                title = title,
                timeInfo = timeInfo,
                isCompleted = false
            )
        } else null
    }
}

// -------------------------------------------------------------
// Ktor Server Engine
// -------------------------------------------------------------
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(IgnoreTrailingSlash)



    install(ContentNegotiation) {
        json()
    }

    routing {
        get("/") {
            call.respondText("Welcome to Ktor Backend connected to Google Sheets!")
        }

        // Endpoint for fetching reminders parsed from Google Sheets cell A2
        get("/api/reminders") {
            val rawA2Value = GoogleSheetsBackendService.readCell("Sheet1!A2") ?: ""
            val reminders = parseRemindersFromRawString(rawA2Value)
            call.respond(reminders)
        }

        // Endpoint for fetching raw moon data string from Google Sheets cell A3
        get("/api/moon-phase") {
            val rawA3Value = GoogleSheetsBackendService.readCell("Sheet1!A3")
                ?: "0~1~2~3~4~5~6~7~8~9~10~11~12~13~14~15~15~15~15~15"
            call.respond(MoonPhaseResponse(rawMnData = rawA3Value))
        }
    }
}