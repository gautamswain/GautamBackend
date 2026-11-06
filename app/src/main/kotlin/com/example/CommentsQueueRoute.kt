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
            val jsonElement = Json.parseToJsonElement(bodyText).jsonObject

            val slNo = jsonElement["sl_no"]?.jsonPrimitive?.content 
                ?: jsonElement["slNo"]?.jsonPrimitive?.content 
                ?: "1"
            
            val separator = "NewJ"
            val jsonDetails = bodyText
            val wefDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

            val conn = TursoDatabaseService.getConnection()
            if (conn != null) {
                try {
                    // 1. Ensure Comments table exists with required columns
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

                    // 2. Insert record
                    val insertQuery = "INSERT INTO Comments (Slno, separater, jsondetails, wef) VALUES (?, ?, ?, ?)"
                    val pstmt = conn.prepareStatement(insertQuery)
                    pstmt.setString(1, slNo)
                    pstmt.setString(2, separator)
                    pstmt.setString(3, jsonDetails)
                    pstmt.setString(4, wefDate)
                    pstmt.executeUpdate()
                    pstmt.close()

                    call.respond(HttpStatusCode.OK, mapOf("success" to true, "message" + "" to "Successfully added to queue!"))
                } catch (dbEx: Exception) {
                    dbEx.printStackTrace()
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Database Error: ${dbEx.localizedMessage}", "exception" to dbEx.javaClass.simpleName)
                    )
                } finally {
                    conn.close()
                }
            } else {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (e.localizedMessage ?: "Failed to add to queue"), "exception" to e.javaClass.simpleName)
            )
        }
    }
}