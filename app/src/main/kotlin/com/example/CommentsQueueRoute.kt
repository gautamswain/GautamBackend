package com.example

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

typealias CommentRecordMap = Map<String, String>

fun Route.commentsQueueRoute() {
    post("/api/turso/add-to-queue") {
        try {
            val bodyText = call.receiveText()
            val jsonElement = try {
                Json.parseToJsonElement(bodyText).jsonObject
            } catch (e: Exception) {
                null
            }

            val slno = jsonElement?.get("slno")?.jsonPrimitive?.content ?: "1"
            val separater = jsonElement?.get("separater")?.jsonPrimitive?.content
                ?: jsonElement?.get("separator")?.jsonPrimitive?.content
                ?: "NewJ"
            val jsonDetails = jsonElement?.get("jsondetails")?.jsonPrimitive?.content
                ?: jsonElement?.get("jsonDetails")?.jsonPrimitive?.content
                ?: bodyText
            val wef = jsonElement?.get("wef")?.jsonPrimitive?.content
                ?: java.time.LocalDate.now().toString()

            val conn = TursoDatabaseService.getConnection()
            if (conn == null) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
                return@post
            }

            try {
                // Exact working pattern from your original CommentsQueueRoute.kt (No .use block)
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
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database Error: ${dbEx.message}"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf(
                    "error" to (e.message ?: "Failed to process queue request"),
                    "exception" to e.javaClass.simpleName
                )
            )
        }
    }

    get("/api/turso/comments/{separater}") {
        val separater = call.parameters["separater"] ?: "PENDING"
        val conn = TursoDatabaseService.getConnection()
        if (conn == null) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
            return@get
        }

        try {
            val pstmt = if (separater.equals("PENDING", ignoreCase = true) || separater.equals("ALL", ignoreCase = true)) {
                conn.prepareStatement("SELECT * FROM Comments WHERE separater NOT IN ('CONF', 'REJ') ORDER BY id DESC")
            } else {
                val ps = conn.prepareStatement("SELECT * FROM Comments WHERE separater = ? ORDER BY id DESC")
                ps.setString(1, separater)
                ps
            }
            val rs = pstmt.executeQuery()

            val records = mutableListOf<CommentRecordMap>()
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
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    post("/api/turso/confirm-mup/{id}") {
        val commentId = call.parameters["id"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
        val conn = TursoDatabaseService.getConnection()
            ?: return@post call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))

        try {
            var separater = ""
            var jsonDetails = ""

            val selectPs = conn.prepareStatement("SELECT separater, jsondetails FROM Comments WHERE id = ?")
            selectPs.setString(1, commentId)
            val rs = selectPs.executeQuery()
            if (rs.next()) {
                separater = rs.getString("separater") ?: ""
                jsonDetails = rs.getString("jsondetails") ?: ""
            }
            rs.close()
            selectPs.close()

            if (separater == "MUP" && jsonDetails.isNotBlank()) {
                val obj = Json.parseToJsonElement(jsonDetails).jsonObject
                val empCode = obj["Code_2"]?.jsonPrimitive?.content?.trim() ?: ""
                val skipCols = setOf("id", "sl_no_1", "Code_2", "employee_name_3")
                val updates = obj.filterKeys { it !in skipCols }

                if (empCode.isNotBlank() && updates.isNotEmpty()) {
                    val setClause = updates.keys.joinToString(", ") { "$it = ?" }
                    val updateMasterPs = conn.prepareStatement("UPDATE MasterData SET $setClause WHERE LOWER(TRIM(Code_2)) = LOWER(TRIM(?))")
                    var idx = 1
                    updates.values.forEach { v -> updateMasterPs.setString(idx++, v.jsonPrimitive.content) }
                    updateMasterPs.setString(idx, empCode)
                    updateMasterPs.executeUpdate()
                    updateMasterPs.close()
                }
            }

            val confPs = conn.prepareStatement("UPDATE Comments SET separater = 'CONF' WHERE id = ?")
            confPs.setString(1, commentId)
            confPs.executeUpdate()
            confPs.close()
            conn.close()

            call.respond(HttpStatusCode.OK, mapOf("status" to "confirmed", "message" to "Confirmed & MasterData updated"))
        } catch (e: Exception) {
            try { conn.close() } catch (_: Exception) {}
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Confirm failed")))
        }
    }

    post("/api/turso/reject-mup/{id}") {
        val commentId = call.parameters["id"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
        val conn = TursoDatabaseService.getConnection()
            ?: return@post call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))

        try {
            val rejPs = conn.prepareStatement("UPDATE Comments SET separater = 'REJ' WHERE id = ?")
            rejPs.setString(1, commentId)
            rejPs.executeUpdate()
            rejPs.close()
            conn.close()

            call.respond(HttpStatusCode.OK, mapOf("status" to "rejected", "message" to "Rejected"))
        } catch (e: Exception) {
            try { conn.close() } catch (_: Exception) {}
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Reject failed")))
        }
    }
}