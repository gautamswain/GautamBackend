package com.example

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.InputStreamContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.UserCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream

object GoogleDriveReportService {
    private const val APPLICATION_NAME = "AdhikarHR Drive Service"
    private val logger = LoggerFactory.getLogger(GoogleDriveReportService::class.java)

    private fun getDriveService(): Drive {
        val clientId = System.getenv("GOOGLE_CLIENT_ID") 
            ?: throw IllegalStateException("GOOGLE_CLIENT_ID environment variable not set")
        val clientSecret = System.getenv("GOOGLE_CLIENT_SECRET") 
            ?: throw IllegalStateException("GOOGLE_CLIENT_SECRET environment variable not set")
        val refreshToken = System.getenv("GOOGLE_REFRESH_TOKEN") 
            ?: throw IllegalStateException("GOOGLE_REFRESH_TOKEN environment variable not set")

        val credentials = UserCredentials.newBuilder()
            .setClientId(clientId)
            .setClientSecret(clientSecret)
            .setRefreshToken(refreshToken)
            .build()
            .createScoped(listOf(DriveScopes.DRIVE, DriveScopes.DRIVE_FILE))

        val transport = GoogleNetHttpTransport.newTrustedTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()

        return Drive.Builder(transport, jsonFactory, HttpCredentialsAdapter(credentials))
            .setApplicationName(APPLICATION_NAME)
            .build()
    }

    private fun getOrCreateAdhikarHrFolder(driveService: Drive): String {
        val folderQuery = "mimeType = 'application/vnd.google-apps.folder' and name = 'AdhikarHR' and trashed = false"
        val folderResult = driveService.files().list().setQ(folderQuery).execute()
        return folderResult.files.firstOrNull()?.id ?: run {
            val folderMetadata = File().apply {
                name = "AdhikarHR"
                mimeType = "application/vnd.google-apps.folder"
            }
            driveService.files().create(folderMetadata).setFields("id").execute().id
        }
    }

    suspend fun uploadBranchSummaryReport(): String = withContext(Dispatchers.IO) {
        try {
            val summaryList = BranchSummaryService.getActiveBranchSummary { TursoDatabaseService.getConnection() }
            val reportContent = StringBuilder("Branch Name | CM | AM | BM | ABM | SCO | CO | Total\n")
            summaryList.forEach { item ->
                val total = item.clusterManagerCount + item.areaManagerCount + item.branchManagerCount + 
                            item.assistantBranchManagerCount + item.seniorCreditOfficerCount + item.creditOfficerCount
                reportContent.append("${item.branchName} | ${item.clusterManagerCount} | ${item.areaManagerCount} | ${item.branchManagerCount} | ${item.assistantBranchManagerCount} | ${item.seniorCreditOfficerCount} | ${item.creditOfficerCount} | $total\n")
            }

            val driveService = getDriveService()
            val folderId = getOrCreateAdhikarHrFolder(driveService)

            val fileMetadata = File().apply {
                name = "Branch_Summary_Report_${System.currentTimeMillis()}.txt"
                parents = listOf(folderId)
            }
            val contentStream = ByteArrayInputStream(reportContent.toString().toByteArray(Charsets.UTF_8))
            val mediaContent = InputStreamContent("text/plain", contentStream)

            val uploadedFile = driveService.files().create(fileMetadata, mediaContent)
                .setFields("id, name")
                .execute()

            uploadedFile.name
        } catch (e: Exception) {
            logger.error("Error in uploadBranchSummaryReport: ${e.message}", e)
            throw e
        }
    }

    suspend fun uploadStaffStatusReport(): String = withContext(Dispatchers.IO) {
        try {
            val summaryList = BranchSummaryService.getActiveBranchSummary { TursoDatabaseService.getConnection() }
            val reportContent = StringBuilder("Branch Name | Emp ID | Employee Name | Designation | Phone 1 | Phone 2\n")
            summaryList.forEach { item ->
                item.staffList.forEach { staff ->
                    reportContent.append("${item.branchName} | ${staff.empId} | ${staff.employeeName} | ${staff.designation} | ${staff.phoneNo1} | ${staff.phoneNo2}\n")
                }
            }

            val driveService = getDriveService()
            val folderId = getOrCreateAdhikarHrFolder(driveService)

            val fileMetadata = File().apply {
                name = "Staff_Status_Report_${System.currentTimeMillis()}.txt"
                parents = listOf(folderId)
            }
            val contentStream = ByteArrayInputStream(reportContent.toString().toByteArray(Charsets.UTF_8))
            val mediaContent = InputStreamContent("text/plain", contentStream)

            val uploadedFile = driveService.files().create(fileMetadata, mediaContent)
                .setFields("id, name")
                .execute()

            uploadedFile.name
        } catch (e: Exception) {
            logger.error("Error in uploadStaffStatusReport: ${e.message}", e)
            throw e
        }
    }
}