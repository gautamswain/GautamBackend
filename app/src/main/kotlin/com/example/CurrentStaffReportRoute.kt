package com.example

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*

fun Route.currentStaffReportRouting() {
    // 1. ରିପୋର୍ଟ ଜେନେରେଟ୍ କରିବା ପାଇଁ ରାଉଟ୍ (ଯଦି ଦରକାର ହୁଏ)
    get("/api/turso/generate-current-staff-report") {
        try {
            call.respond(HttpStatusCode.OK, mapOf(
                "status" to "success",
                "message" to "Ready for direct download."
            ))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "status" to "error",
                "message" to (e.localizedMessage ?: "Unknown error")
            ))
        }
    }

    // 2. ସର୍ଭରରେ ସେଭ୍ ନକରି ସିଧାସଳଖ ଡାଉନଲୋଡ୍ କରିବା ପାଇଁ ରାଉଟ୍
    get("/api/turso/download-latest-staff-report") {
        try {
            // ଡାଟାବେସ୍‌ରୁ ଡାଟା ଆଣಿ ମେମୋରୀ (Bytes) ରେ ଏକ୍ସେଲ୍ ତିଆରି କରିବା
            val excelBytes = CurrentStaffReportService.generateReportBytesFromDb { 
                TursoDatabaseService.getConnection() 
            }

            // ବ୍ରାଉଜର୍‌କୁ ଡାଉନଲୋଡ୍ ହେବା ପାଇଁ ହେଡର୍ ସେଟ୍ କରିବା
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName, 
                    "Stafflist.xlsx"
                ).toString()
            )
            
            // ସିଧାସଳଖ ବ୍ରାଉଜର୍‌ର ଡାଉନଲୋଡ୍ ଫୋଲ୍ଡର୍‌କୁ ପଠାଇବା
            call.respondBytes(excelBytes, ContentType.Application.OctetStream)
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.InternalServerError, 
                mapOf("error" to (e.localizedMessage ?: "Unknown error"))
            )
        }
    }
}