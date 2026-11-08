package com.example

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import java.io.File

fun Route.currentStaffReportRouting() {
    // ଏବେ ଏହା ଏକ GET ଏଣ୍ଡପ୍ଏଣ୍ଟ୍ ଯାହା ସିଧାସଳଖ ଡାଟାବେସ୍‌ରୁ ରିପୋର୍ଟ ତିଆରି କରିବ
    get("/api/turso/generate-current-staff-report") {
        try {
            val localDir = System.getProperty("user.home") + File.separator + "AdhikarStaffReports"
            
            // ଡାଟାବେସ୍‌ରୁ ତଥ୍ୟ ନେଇ ରିପୋର୍ଟ ଜେନେରେଟ୍ କରିବା
            val savedFilePath = CurrentStaffReportService.generateAndSaveReportFromDb(
                { TursoDatabaseService.getConnection() }, 
                localDir
            )

            call.respond(HttpStatusCode.OK, mapOf(
                "status" to "success",
                "message" to "Report generated successfully from MasterData in Database.",
                "path" to savedFilePath
            ))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "status" to "error",
                "message" to (e.localizedMessage ?: "Unknown error")
            ))
        }
    }
}