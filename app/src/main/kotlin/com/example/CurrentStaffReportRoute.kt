package com.example

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.content.*
import io.ktor.http.*
import java.io.File

fun Route.currentStaffReportRouting() {
    post("/api/turso/generate-current-staff-report") {
        val multipart = call.receiveMultipart()
        var savedFilePath = ""

        multipart.forEachPart { part ->
            when (part) {
                is PartData.FileItem -> {
                    val fileBytes = part.streamProvider().readBytes()
                    val inputStream = fileBytes.inputStream()

                    // ଲୋକାଲ୍ ଡାଇରେକ୍ଟରି ପାଥ୍
                    val localDir = System.getProperty("user.home") + File.separator + "AdhikarStaffReports"

                    savedFilePath = CurrentStaffReportService.generateAndSaveCurrentStaffReport(inputStream, localDir)
                }
                else -> {}
            }
            part.dispose()
        }

        if (savedFilePath.isNotBlank()) {
            call.respond(HttpStatusCode.OK, mapOf(
                "status" to "success",
                "message" to "Report generated and saved successfully to local and Google Drive directories.",
                "path" to savedFilePath
            ))
        } else {
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "status" to "error",
                "message" to "Excel file upload failed or missing."
            ))
        }
    }
}