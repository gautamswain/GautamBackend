package com.example

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import java.io.File

fun Route.currentStaffReportRouting() {
    get("/api/turso/generate-current-staff-report") {
        try {
            val localDir = if (System.getProperty("os.name").lowercase().contains("linux")) {
                "/root/AdhikarStaffReports"
            } else {
                System.getProperty("user.home") + File.separator + "AdhikarStaffReports"
            }
            
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

    get("/api/turso/download-latest-staff-report") {
        try {
            val dirPath = if (System.getProperty("os.name").lowercase().contains("linux")) {
                "/root/AdhikarStaffReports"
            } else {
                System.getProperty("user.home") + File.separator + "AdhikarStaffReports"
            }
            
            val dir = File(dirPath)
            if (!dir.exists()) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Reports directory not found"))
                return@get
            }

            val latestFile = dir.listFiles { _, name -> name.startsWith("Stafflist") && name.endsWith(".xlsx") }
                ?.maxByOrNull { it.lastModified() }

            if (latestFile != null && latestFile.exists()) {
                call.respondFile(latestFile)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "No report file found"))
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Unknown error")))
        }
    }
}