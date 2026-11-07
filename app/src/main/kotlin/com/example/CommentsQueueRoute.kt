package com.example

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import kotlinx.serialization.json.*

fun Route.commentsQueueRoute() {
    post("/api/turso/add-to-queue") {
        try {
            val bodyText = call.receiveText()
            val jsonElement = try {
                Json.parseToJsonElement(bodyText).jsonObject
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON format"))
                return@post
            }

            // Safely extract fields with fallbacks
            val slno = jsonElement["slno"]?.jsonPrimitive?.content ?: "1"
            val separater = jsonElement["separater"]?.jsonPrimitive?.content ?: jsonElement["separator"]?.jsonPrimitive?.content ?: "Queue"
            val jsonDetails = jsonElement["jsondetails"]?.jsonPrimitive?.content ?: jsonElement["jsonDetails"]?.jsonPrimitive?.content ?: bodyText
            val wef = jsonElement["wef"]?.jsonPrimitive?.content ?: java.time.LocalDate.now().toString()

            val conn = TursoDatabaseService.getConnection()
            if (conn == null) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
                return@post
            }

            try {
                val sql = "INSERT INTO Comments (slno, separater, jsondetails, wef) VALUES (?, ?, ?, ?);"
                val pstmt = conn.prepareStatement(sql)
                pstmt.setString(1, slno)
                pstmt.setString(2, separater)
                pstmt.setString(3, jsonDetails)
                pstmt.setString(4, wef)
                
                pstmt.executeUpdate()
                pstmt.close()
                conn.close()

                call.respond(HttpStatusCode.OK, mapOf("status" to "success", "message" to "Record successfully added to queue"))
            } catch (dbEx: Exception) {
                try { conn.close() } catch (_: Exception) {}
                dbEx.printStackTrace()
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database Error: ${dbEx.localizedMessage}"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf(
                    "error" to (e.localizedMessage ?: "Failed to process queue request"),
                    "exception" to e.javaClass.simpleName
                )
            )
        }
    }
}