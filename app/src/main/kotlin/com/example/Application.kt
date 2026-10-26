package com.example

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.event.Level
import java.time.LocalDate

private const val DEFAULT_COLOR_PALETTE = "0xFFD8BFD8-0xFFE6E6FA-0xFF332F21-0xFFFBFAE2-0xFF80D8FF-0xFF000080-0xFF000080-0xFF8BC34A-0xFF80D8FF-0xFF000080-0xFF000080"

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    // 1. Initialize Turso Database Connection Pool
    TursoDatabaseService.init()

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

        // 🚀 Turso DB Endpoint: Fetch specific record by Employee ID
        get("/api/turso/record/{empId}") {
            val empId = call.parameters["empId"]
            if (empId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing empId parameter"))
                return@get
            }

            val record = TursoDatabaseService.getRecordByEmpId(empId)
            if (record != null) {
                call.respond(record)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Record not found for empId: $empId"))
            }
        }

        // Fetch Combined Data directly from Sheet1!A15
        get("/api/combined-data") {
            try {
                val rawA15Data = GoogleSheetsBackendService.readCell("Sheet1!A15")

                val finalResponse = if (!rawA15Data.isNullOrBlank()) {
                    rawA15Data
                } else {
                    "~1#1#4#No Data Found#(24-10-2026-Saturday) -> Today^0~1~2~3~4~5~6~7~8~9~10^ଆଜିର ବିବରଣୀ ଉପଲବ୍ଧ ନାହିଁ।^$DEFAULT_COLOR_PALETTE"
                }

                call.respondText(finalResponse)
            } catch (e: Exception) {
                call.respondText("Error reading Sheet1!A15: ${e.message}", status = HttpStatusCode.InternalServerError)
            }
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

        get("/api/chat") {
            val prompt = call.request.queryParameters["prompt"] ?: "Hello Gemini"
            val aiReply = GeminiService.generateText(prompt)
            call.respond(GeminiResponse(reply = aiReply))
        }

        post("/api/chat") {
            val request = call.receive<GeminiRequest>()
            val aiReply = GeminiService.generateText(request.prompt)
            call.respond(GeminiResponse(reply = aiReply))
        }

        get("/api/today-description") {
            val currentDate = LocalDate.now().toString()
            val descriptionFromSheet = GoogleSheetsBackendService.readCell("Sheet1!A4")
            
            val finalDescription = if (!descriptionFromSheet.isNullOrBlank()) {
                descriptionFromSheet
            } else {
                val prompt = "Today's date is $currentDate. Briefly describe today's significance according to the Odia calendar (Panjika/Tithi/Festival) in 2 simple sentences in Odia language."
                GeminiService.generateText(prompt)
            }
            
            call.respond(DayDescriptionResponse(date = currentDate, description = finalDescription))
        }

        post("/api/add-event") {
            val multipart = call.receiveMultipart()
            var date = ""
            var event = ""
            var type = 2

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> {
                        if (part.name == "date") date = part.value
                        if (part.name == "event") event = part.value
                        if (part.name == "type") type = part.value.toIntOrNull() ?: 2
                    }
                    else -> {}
                }
                part.dispose()
            }

            val success = GoogleSheetsBackendService.appendEventToSheet(type, date, event)
            if (success) {
                call.respond(HttpStatusCode.OK, mapOf("status" to "success"))
            } else {
                call.respond(HttpStatusCode.InternalServerError, mapOf("status" to "error"))
            }
        }
    }
}