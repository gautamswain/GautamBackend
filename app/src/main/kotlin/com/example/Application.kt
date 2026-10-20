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
    // Read pre-generated description from Google Sheet A4 Cell
    val descriptionFromSheet = GoogleSheetsBackendService.readCell("Sheet1!A4")
    
    val finalDescription = if (!descriptionFromSheet.isNullOrBlank()) {
        descriptionFromSheet
    } else {
        // Fallback to live Gemini API call if sheet cell is empty
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