package com.example

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

typealias CommentRecordMap = Map<String, String>

fun Route.commentsQueueRoute() {
    // UNIVERSAL ADD-TO-QUEUE: Jahabi asu, jeuthu asu, direct Comments table re append kariba
    post("/api/turso/add-to-queue") {
        val bodyText = try { call.receiveText() } catch (_: Exception) { "{}" }
        val jsonObj = try { Json.parseToJsonElement(bodyText).jsonObject } catch (_: Exception) { null }

        val slno = jsonObj?.get("slno")?.jsonPrimitive?.contentOrNull?.ifBlank { "1" } ?: "1"
        val separater = (jsonObj?.get("separater")?.jsonPrimitive?.contentOrNull
            ?: jsonObj?.get("separator")?.jsonPrimitive?.contentOrNull
            ?: "MUP").ifBlank { "MUP" }
        val jsonDetails = jsonObj?.get("jsondetails")?.jsonPrimitive?.contentOrNull
            ?: jsonObj?.get("jsonDetails")?.jsonPrimitive?.contentOrNull
            ?: bodyText
        val wef = (jsonObj?.get("wef")?.jsonPrimitive?.contentOrNull
            ?: java.time.LocalDate.now().toString()).ifBlank { java.time.LocalDate.now().toString() }

        val conn = TursoDatabaseService.getConnection()
        if (conn == null) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
            return@post
        }

        try {
            conn.use { c ->
                // Safe insert (works for both AUTOINCREMENT and manual id tables)
                try {
                    c.prepareStatement("INSERT INTO Comments (slno, separater, jsondetails, wef) VALUES (?, ?, ?, ?)").use { ps ->
                        ps.setString(1, slno)
                        ps.setString(2, separater)
                        ps.setString(3, jsonDetails)
                        ps.setString(4, wef)
                        ps.executeUpdate()
                    }
                } catch (_: Exception) {
                    val nextId = c.createStatement().use { st ->
                        val rs = st.executeQuery("SELECT COALESCE(MAX(CAST(id AS INTEGER)), 0) + 1 FROM Comments")
                        if (rs.next()) rs.getInt(1) else 1
                    }
                    c.prepareStatement("INSERT INTO Comments (id, slno, separater, jsondetails, wef) VALUES (?, ?, ?, ?, ?)").use { ps ->
                        ps.setInt(1, nextId)
                        ps.setString(2, slno)
                        ps.setString(3, separater)
                        ps.setString(4, jsonDetails)
                        ps.setString(5, wef)
                        ps.executeUpdate()
                    }
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "success", "message" to "Appended to Comments"))
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Append failed")))
        }
    }

    // Fetch PENDING or specific separater ("MUP", "NewJ", "PENDING")
    get("/api/turso/comments/{separater}") {
        val separater = call.parameters["separater"] ?: "PENDING"
        val conn = TursoDatabaseService.getConnection()
        if (conn == null) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
            return@get
        }

        try {
            val records = mutableListOf<CommentRecordMap>()
            conn.use { c ->
                val pstmt = if (separater.equals("PENDING", ignoreCase = true) || separater.equals("ALL", ignoreCase = true)) {
                    c.prepareStatement("SELECT * FROM Comments WHERE separater NOT IN ('CONF', 'REJ') ORDER BY id DESC")
                } else {
                    c.prepareStatement("SELECT * FROM Comments WHERE separater = ? ORDER BY id DESC").apply {
                        setString(1, separater)
                    }
                }
                pstmt.use { ps ->
                    ps.executeQuery().use { rs ->
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
                    }
                }
            }
            call.respond(records)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    // Confirm MUP -> MasterData update + separater = 'CONF'
    post("/api/turso/confirm-mup/{id}") {
        val commentId = call.parameters["id"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
        val conn = TursoDatabaseService.getConnection()
            ?: return@post call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "DB failed"))

        try {
            conn.use { c ->
                var separater = ""
                var jsonDetails = ""
                c.prepareStatement("SELECT separater, jsondetails FROM Comments WHERE id = ?").use { ps ->
                    ps.setString(1, commentId)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            separater = rs.getString("separater") ?: ""
                            jsonDetails = rs.getString("jsondetails") ?: ""
                        }
                    }
                }

                if (separater == "MUP" && jsonDetails.isNotBlank()) {
                    val obj = Json.parseToJsonElement(jsonDetails).jsonObject
                    val empCode = obj["Code_2"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
                    val skipCols = setOf("id", "sl_no_1", "Code_2", "employee_name_3")
                    val updates = obj.filterKeys { it !in skipCols }

                    if (empCode.isNotBlank() && updates.isNotEmpty()) {
                        val setClause = updates.keys.joinToString(", ") { "$it = ?" }
                        c.prepareStatement("UPDATE MasterData SET $setClause WHERE LOWER(TRIM(Code_2)) = LOWER(TRIM(?))").use { ps ->
                            var idx = 1
                            updates.values.forEach { v -> ps.setString(idx++, v.jsonPrimitive.content) }
                            ps.setString(idx, empCode)
                            ps.executeUpdate()
                        }
                    }
                }

                c.prepareStatement("UPDATE Comments SET separater = 'CONF' WHERE id = ?").use { ps ->
                    ps.setString(1, commentId)
                    ps.executeUpdate()
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "confirmed", "message" to "Confirmed & MasterData updated"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Confirm failed")))
        }
    }

    // Reject -> separater = 'REJ'
    post("/api/turso/reject-mup/{id}") {
        val commentId = call.parameters["id"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
        val conn = TursoDatabaseService.getConnection()
            ?: return@post call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "DB failed"))

        try {
            conn.use { c ->
                c.prepareStatement("UPDATE Comments SET separater = 'REJ' WHERE id = ?").use { ps ->
                    ps.setString(1, commentId)
                    ps.executeUpdate()
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "rejected", "message" to "Rejected"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Reject failed")))
        }
    }
}