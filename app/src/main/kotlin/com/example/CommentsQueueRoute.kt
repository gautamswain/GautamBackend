package com.example

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

fun Route.commentsQueueRoute() {
    post("/api/turso/add-to-queue") {
        try {
            val bodyText = call.receiveText()
            if (bodyText.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Request body is empty"))
                return@post
            }

            val jsonElement = try {
                Json.parseToJsonElement(bodyText).jsonObject
            } catch (e: Exception) {
                null
            }

            val slNo = jsonElement?.get("sl_no"]?.jsonPrimitive?.content 
                ?: jsonElement?.get("slNo"]?.jsonPrimitive?.content 
                ?: "1"
            
            val separator = "NewJ"
            val jsonDetails = bodyText
            val wefDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

            val conn = TursoDatabaseService.getConnection()
            if (conn == null) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "TursoDatabaseService.getConnection() returned null"))
                return@post
            }

            try {
                // 1. Ensure Comments table exists
                val createTableQuery = """
                    CREATE TABLE IF NOT EXISTS Comments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        Slno TEXT,
                        separater TEXT,
                        jsondetails TEXT,
                        wef TEXT
                    )
                """
                conn.createStatement().execute(createTableQuery)

                // 2. Insert record using execute() instead of executeUpdate() for LibSQL JDBC driver compatibility
                val insertQuery = "INSERT INTO Comments (Slno, separater, jsondetails, wef) VALUES (?, ?, ?, ?)"
                conn.prepareStatement(insertQuery).use { pstmt ->
                    pstmt.setString(1, slNo)
                    pstmt.setString(2, separator)
                    pstmt.setString(3, jsonDetails)
                    pstmt.setString(4, wefDate)
                    pstmt.execute() // <--- Fixed from executeUpdate() to execute()
                }

                call.respond(HttpStatusCode.OK, mapOf("success" to true, "message" to "Successfully added to queue!"))
            } catch (dbEx: Throwable) {
                dbEx.printStackTrace()
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf(
                        "error" to (dbEx.localizedMessage ?: "Database error"),
                        "exception" to dbEx.javaClass.name
                    )
                )
            } finally {
                try {
                    conn.close()
                } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf(
                    "error" to (t.localizedMessage ?: "Server error"),
                    "exception" to t.javaClass.name
                )
            )
        }
    }
}