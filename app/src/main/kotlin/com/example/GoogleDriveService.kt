package com.example

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.InputStreamContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.UserCredentials
import java.io.ByteArrayInputStream
import java.io.File as JavaFile

class GoogleDriveService(
    private val clientId: String,
    private val clientSecret: String,
    private val refreshToken: String
) {
    private val applicationName = "AdhikarHR Drive Service"

    private fun getDriveService(): Drive {
        val credentials = UserCredentials.newBuilder()
            .setClientId(clientId)
            .setClientSecret(clientSecret)
            .setRefreshToken(refreshToken)
            .build()
            .createScoped(listOf(DriveScopes.DRIVE, DriveScopes.DRIVE_FILE))

        val transport = GoogleNetHttpTransport.newTrustedTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()

        return Drive.Builder(transport, jsonFactory, HttpCredentialsAdapter(credentials))
            .setApplicationName(applicationName)
            .build()
    }

    // Google Drive ରେ ଫାଇଲ୍ ଅପ୍‌ଲୋଡ୍ କରିବା ପାଇଁ
    fun uploadFile(uploadFileObj: JavaFile, mimeType: String): String {
        val driveService = getDriveService()

        // ଫୋଲଡର୍ ଯାଞ୍ଚ କିମ୍ବା ତିଆରି କରିବା
        val folderQuery = "mimeType = 'application/vnd.google-apps.folder' and name = 'AdhikarHR' and trashed = false"
        val folderResult = driveService.files().list().setQ(folderQuery).execute()
        val folderId = folderResult.files.firstOrNull()?.id ?: run {
            val folderMetadata = File().apply {
                name = "AdhikarHR"
                this.mimeType = "application/vnd.google-apps.folder"
            }
            driveService.files().create(folderMetadata).setFields("id").execute().id
        }

        // ଫାଇଲ୍ ମେଟାଡାଟା ଏବଂ କଣ୍ଟେଣ୍ଟ୍
        val fileMetadata = File().apply {
            name = uploadFileObj.name
            parents = listOf(folderId)
        }
        val mediaContent = InputStreamContent(mimeType, ByteArrayInputStream(uploadFileObj.readBytes()))

        val uploadedFile = driveService.files().create(fileMetadata, mediaContent)
            .setFields("id, name")
            .execute()

        return "File uploaded successfully: ${uploadedFile.name} (ID: ${uploadedFile.id})"
    }
}