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
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    // 1. Turso Database Connection Initialize
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

        // 2. Turso DB Routes
        route("/api/turso") {
            
            get("/columns") {
                call.respond(mapOf("columns" to TursoDatabaseService.getColumns()))
            }

            // Debug route to check SalaryHistory columns
            get("/salary-columns") {
                call.respond(mapOf("columns" to TursoDatabaseService.getSalaryTableColumns()))
            }

            // Branch-wise Staff Summary Route (BM, ABM, CO counts)
            get("/branch-summary") {
                val summaryList = BranchSummaryService.getActiveBranchSummary { TursoDatabaseService.getConnectionInternal() }
                call.respond(summaryList)
            }

            get("/record/{empId}") {
                val empId = call.parameters["empId"]
                if (empId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing empId parameter"))
                } else {
                    TursoDatabaseService.getRecordByEmpId(empId)?.let { call.respond(it) }
                        ?: call.respond(HttpStatusCode.NotFound, mapOf("error" to "Record not found for empId: $empId"))
                }
            }

            // Dynamic Search (Supports BR, DG, LS & Backend Sorting: 0 -> 1/2 -> 3)
            get("/search") {
                val rawQuery = call.request.queryParameters["query"] 
                    ?: call.request.queryParameters["name"]

                if (rawQuery.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing search parameter"))
                    return@get
                }

                val query = rawQuery.trim().replace(Regex("\\s+"), " ")
                val lowerQuery = query.lowercase()

                val rawResults: List<MasterDataDto> = when {
                    // "br " -> Filter Active Staff by Branch Name
                    lowerQuery.startsWith("br ") -> {
                        val branchTerm = query.substring(3).trim()
                        TursoDatabaseService.getActiveStaffByBranch(branchTerm)
                    }

                    // "dg " -> Filter Active Staff by Designation
                    lowerQuery.startsWith("dg ") -> {
                        val desgTerm = query.substring(3).trim()
                        TursoDatabaseService.getActiveStaffByDesignation(desgTerm)
                    }

                    // "ls" -> Fetch Last 20 Recent Records
                    lowerQuery.startsWith("ls") -> {
                        TursoDatabaseService.getLast20Records()
                    }

                    else -> {
                        TursoDatabaseService.searchRecords(query)
                    }
                }

                // Sorting: Score 0 -> Score 1/2 -> Score 3
                val sortedResults = rawResults.sortedBy { dto ->
                    TursoDatabaseService.calculateStatusScore(dto)
                }

                call.respond(sortedResults)
            }

            // Salary History API by Emp ID Last 4 Digits
            get("/salary-history/{empId}") {
                val empId = call.parameters["empId"]
                if (empId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing empId parameter"))
                } else {
                    val salaryRecords = TursoDatabaseService.getSalaryHistoryByEmpIdLast4(empId)
                    if (salaryRecords.isNotEmpty()) {
                        call.respond(salaryRecords)
                    } else {
                        call.respond(
                            HttpStatusCode.NotFound, 
                            mapOf("error" to "No salary history found matching last 4 digits of $empId")
                        )
                    }
                }
            }
        }

        // 3. Google Sheets Routes
        get("/api/combined-data") {
            val rawData = GoogleSheetsBackendService.readCell("Sheet1!A15")
            val fallback = "~1#1#4#No Data Found#(24-10-2026-Saturday) -> Today^0~1~2~3~4~5~6~7~8~9~10^ଆଜିର ବିବରଣୀ ଉପଲବ୍ଧ ନାହିଁ।^$DEFAULT_COLOR_PALETTE"
            call.respondText(if (!rawData.isNullOrBlank()) rawData else fallback)
        }

        get("/api/reminders") {
            val rawData = GoogleSheetsBackendService.readCell("Sheet1!A2") ?: "1#1#3#Maha Ashtami#(19-10-2026-Monday) -> Today~"
            call.respond(parseRemindersFromRawString(rawData))
        }

        get("/api/moon-phase") {
            val rawData = GoogleSheetsBackendService.readCell("Sheet1!A3") ?: "0~1~2~3~4~5~6~7~8~9~10~11~12~13~14~15~15~15~15~15"
            call.respond(MoonPhaseResponse(rawMnData = rawData))
        }

        get("/api/today-description") {
            val date = LocalDate.now().toString()
            val sheetDesc = GoogleSheetsBackendService.readCell("Sheet1!A4")
            val desc = if (!sheetDesc.isNullOrBlank()) sheetDesc else GeminiService.generateText("Today's date is $date. Briefly describe today's significance according to the Odia calendar in 2 simple sentences in Odia.")
            call.respond(DayDescriptionResponse(date = date, description = desc))
        }

        // 4. Gemini AI Routes
        route("/api/chat") {
            get {
                val prompt = call.request.queryParameters["prompt"] ?: "Hello Gemini"
                call.respond(GeminiResponse(reply = GeminiService.generateText(prompt)))
            }
            post {
                val req = call.receive<GeminiRequest>()
                call.respond(GeminiResponse(reply = GeminiService.generateText(req.prompt)))
            }
        }

        // 5. Add Event Route
        post("/api/add-event") {
            var date = ""; var event = ""; var type = 2
            call.receiveMultipart().forEachPart { part ->
                if (part is PartData.FormItem) {
                    when (part.name) {
                        "date" -> date = part.value
                        "event" -> event = part.value
                        "type" -> type = part.value.toIntOrNull() ?: 2
                    }
                }
                part.dispose()
            }
            val status = if (GoogleSheetsBackendService.appendEventToSheet(type, date, event)) {
                HttpStatusCode.OK to "success"
            } else {
                HttpStatusCode.InternalServerError to "error"
            }
            call.respond(status.first, mapOf("status" to status.second))
        }
    }
}