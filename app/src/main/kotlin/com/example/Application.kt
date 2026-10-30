package com.example

import com.example.services.GoogleDriveService
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
import java.io.File
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

    // Google Drive Service Initialization using Environment Variables
    val clientId = System.getenv("GOOGLE_CLIENT_ID") ?: ""
    val clientSecret = System.getenv("GOOGLE_CLIENT_SECRET") ?: ""
    val refreshToken = System.getenv("GOOGLE_REFRESH_TOKEN") ?: ""
    val driveService = GoogleDriveService(clientId, clientSecret, refreshToken)

    routing {
        get("/") { 
            call.respondText("Welcome to Ktor Backend!") 
        }

        // Test Drive Upload Endpoint
        get("/upload-test") {
            try {
                val tempFile = File.createTempFile("test_upload", ".txt").apply {
                    writeText("হ্যালো Ktor Google Drive Integration with OAuth!")
                }
                val uploadResult = driveService.uploadFile(tempFile, "text/plain")
                call.respondText("File Uploaded Successfully!\nResponse: $uploadResult")
            } catch (e: Exception) {
                call.respondText("Upload Failed: ${e.localizedMessage}")
            }
        }

        // 2. Turso DB Routes
        route("/api/turso") {
            get("/columns") {
                call.respond(mapOf("columns" to TursoDatabaseService.getColumns()))
            }

            get("/salary-columns") {
                call.respond(mapOf("columns" to TursoDatabaseService.getSalaryTableColumns()))
            }

            get("/branch-summary") {
                val summaryList = BranchSummaryService.getActiveBranchSummary { TursoDatabaseService.getConnection() }
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
                    lowerQuery.startsWith("br ") -> {
                        val branchTerm = query.substring(3).trim()
                        TursoDatabaseService.getActiveStaffByBranch(branchTerm)
                    }
                    lowerQuery.startsWith("dg ") -> {
                        val designationTerm = query.substring(3).trim()
                        TursoDatabaseService.getActiveStaffByDesignation(designationTerm)
                    }
                    lowerQuery.startsWith("ls") -> {
                        TursoDatabaseService.getLast20Records()
                    }
                    else -> {
                        TursoDatabaseService.searchRecords(query)
                    }
                }

                val sortedResults = rawResults.sortedBy { dto ->
                    TursoDatabaseService.calculateStatusScore(dto)
                }

                call.respond(sortedResults)
            }

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

        // 3. Google Drive Report Upload Routes
        route("/api/drive") {
            post("/upload-branch-summary-report") {
                try {
                    val fileName = GoogleDriveReportService.uploadBranchSummaryReport()
                    call.respond(HttpStatusCode.OK, mapOf("status" to "success", "fileName" to fileName))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("status" to "error", "message" to (e.localizedMessage ?: "Unknown error")))
                }
            }

            post("/upload-staff-status-report") {
                try {
                    val fileName = GoogleDriveReportService.uploadStaffStatusReport()
                    call.respond(HttpStatusCode.OK, mapOf("status" to "success", "fileName" to fileName))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("status" to "error", "message" to (e.localizedMessage ?: "Unknown error")))
                }
            }
        }

        // 4. Google Sheets Routes
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

        // 5. Gemini AI Routes
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

        // 6. Add Event Route
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