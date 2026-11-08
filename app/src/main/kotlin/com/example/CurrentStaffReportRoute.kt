package com.example

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import java.io.File

fun Route.currentStaffReportRouting() {
    get("/api/turso/generate-current-staff-report") {
        try {
            val tempDir = File(System.getProperty("java.io.tmpdir"), "AdhikarStaffReports")
            if (!tempDir.exists()) tempDir.mkdirs()

            val savedFilePath = CurrentStaffReportService.generateAndSaveReportFromDb(
                { TursoDatabaseService.getConnection() }, 
                tempDir.absolutePath
            )

            call.respond(HttpStatusCode.OK, mapOf(
                "status" to "success",
                "message" to "Report generated successfully.",
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
            // Render ଏବଂ ଲୋକାଲ୍ ମେସିନ୍ ପାଇଁ ସୁରକ୍ଷିତ Temp ଫୋଲ୍ଡର୍
            val tempDir = File(System.getProperty("java.io.tmpdir"), "AdhikarStaffReports")
            if (!tempDir.exists()) {
                tempDir.mkdirs()
            }

            // ରିପୋର୍ଟ ଜେନେରେଟ୍ କରି ଟେମ୍ପ୍ ଫୋଲ୍ଡର୍‌ରେ ସେଭ୍ କରିବା
            val savedFilePath = CurrentStaffReportService.generateAndSaveReportFromDb(
                { TursoDatabaseService.getConnection() }, 
                tempDir.absolutePath
            )

            val file = File(savedFilePath)
            if (file.exists()) {
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(
                        ContentDisposition.Parameters.FileName, 
                        file.name
                    ).toString()
                )
                call.respondFile(file)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Report file not found"))
            }
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.InternalServerError, 
                mapOf("error" to (e.localizedMessage ?: "Unknown error"))
            )
        }
    }
}