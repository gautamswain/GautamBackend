package com.example.plugins

import com.example.services.GoogleDriveService
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

fun Application.configureRouting() {
    val config = environment.config
    val clientId = config.property("google.clientId").getString()
    val clientSecret = config.property("google.clientSecret").getString()
    val refreshToken = config.property("google.refreshToken").getString()

    val driveService = GoogleDriveService(clientId, clientSecret, refreshToken)

    routing {
        get("/upload-test") {
            try {
                // ଉଦାହରଣ ସ୍ୱରୂପ ଏକ ଟେଷ୍ଟ୍ ଫାଇଲ୍ ତିଆରି କରାଗଲା
                val tempFile = File.createTempFile("test_upload", ".txt").apply {
                    writeText("হ্যালো Ktor Google Drive Integration!")
                }

                val uploadResult = driveService.uploadFile(tempFile, "text/plain")
                call.respondText("File Uploaded Successfully!\nResponse: $uploadResult")
            } catch (e: Exception) {
                call.respondText("Upload Failed: ${e.localizedMessage}")
            }
        }
    }
}