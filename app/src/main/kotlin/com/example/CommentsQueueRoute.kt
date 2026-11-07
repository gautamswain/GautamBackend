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
                null
            }

            // Safely extract fields, falling back or packing raw DTO if sent directly
            val slno = jsonElement?.get("slno")?.jsonPrimitive?.content ?: "1"
            val separater = jsonElement?.get("separater")?.jsonPrimitive?.content ?: jsonElement?.get("separator")?.jsonPrimitive?.content ?: "NewJ"
            val jsonDetails = jsonElement?.get("jsondetails")?.jsonPrimitive?.content 
                ?: jsonElement?.get("jsonDetails")?.jsonPrimitive?.content 
                ?: bodyText
            val wef = jsonElement?.get("wef")?.jsonPrimitive?.content ?: java.time.LocalDate.now().toString()

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

    get("/api/turso/comments/{separater}") {
        val separater = call.parameters["separater"] ?: "NewJ"
        val conn = TursoDatabaseService.getConnection()
        if (conn == null) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
            return@get
        }

        try {
            val pstmt = conn.prepareStatement("SELECT * FROM Comments WHERE separater = ? ORDER BY id DESC")
            pstmt.setString(1, separater)
            val rs = pstmt.executeQuery()

            val records = mutableListOf<Map<String, String>>()
            while (rs.next()) {
                records.add(
                    mapOf(
                        "id" to (rs.getString("id") ?: ""),
                        "slno" to (rs.getString("slno") ?: ""),
                        "separater" to (rs.getString("separater") ?: ""),
                        "jsondetails" to (rs.getString("jsondetails") ?: ""),
                        "wef" to (rs.getString("wef") ?: "")
                    )
                )
            }
            rs.close()
            pstmt.close()
            conn.close()

            call.respond(records)
        } catch (e: Exception) {
            try { conn.close() } catch (_: Exception) {}
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Unknown error")))
        }
    }
}