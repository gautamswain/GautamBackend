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
            val jsonDetails = bodyText // Full JSON of employee data
            val wefDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) // Today's date (YYYY-MM-DD)

            val conn = TursoDatabaseService.getConnection()
            if (conn != null) {
                try {
                    val query = "INSERT INTO Comments (Slno, separater, jsondetails, wef) VALUES (?, ?, ?, ?)"
                    val pstmt = conn.prepareStatement(query)
                    pstmt.setString(1, slNo)
                    pstmt.setString(2, separator)
                    pstmt.setString(3, jsonDetails)
                    pstmt.setString(4, wefDate)
                    pstmt.executeUpdate()
                    pstmt.close()

                    call.respond(HttpStatusCode.OK, mapOf("success" to true, "message" to "Successfully added to queue!"))
                } finally {
                    conn.close()
                }
            } else {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Failed to add to queue")))
        }
    }
}