package com.example

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.ValueRange
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

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
            logger.error("Read cell error: ${e.message}", e)
            null
        }
    }

    suspend fun appendEventToSheet(type: Int, date: String, event: String): Boolean = withContext(Dispatchers.IO) {
        val service = sheetsService ?: return@withContext false
        try {
            if (type == 2) {
                val range = "Sheet1!N:O"
                val values = listOf(listOf(date, event))
                val body = ValueRange().setValues(values)

                service.spreadsheets().values()
                    .append(SPREADSHEET_ID, range, body)
                    .setValueInputOption("USER_ENTERED")
                    .execute()

                logger.info("Successfully added Type 2 Event: Date=$date, Event=$event")
                true
            } else false
        } catch (e: Exception) {
            logger.error("Failed to append event: ${e.message}", e)
            false
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