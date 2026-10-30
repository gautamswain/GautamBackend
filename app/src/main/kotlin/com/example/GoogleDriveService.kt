package com.example.services

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class TokenResponse(
    val access_token: String,
    val expires_in: Int,
    val token_type: String
)

class GoogleDriveService(
    private val clientId: String,
    private val clientSecret: String,
    private val refreshToken: String
) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    // Refresh Token ଦ୍ୱାରା ନୂଆ Access Token ଆଣିବା ପାଇଁ
    private suspend fun getAccessToken(): String {
        val response: HttpResponse = client.post("https://oauth2.googleapis.com/token") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("client_id", clientId)
                        append("client_secret", clientSecret)
                        append("refresh_token", refreshToken)
                        append("grant_type", "refresh_token")
                    }
                )
            )
        }
        val tokenResponse = response.body<TokenResponse>()
        return tokenResponse.access_token
    }

    // Google Drive ରେ ଫାଇଲ୍ ଅପ୍‌ଲୋଡ୍ କରିବା ପାଇଁ
    suspend fun uploadFile(file: File, mimeType: String): String {
        val accessToken = getAccessToken()
        
        // Google Drive Metadata
        val metadata = "{\"name\":\"${file.name}\",\"mimeType\":\"$mimeType\"}"

        val response = client.post("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("metadata", metadata, Headers.build {
                            append(HttpHeaders.ContentType, "application/json; charset=UTF-8")
                        })
                        append("file", file.readBytes(), Headers.build {
                            append(HttpHeaders.ContentType, mimeType)
                            append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                        })
                    }
                )
            )
        }

        return response.bodyAsText()
    }
}