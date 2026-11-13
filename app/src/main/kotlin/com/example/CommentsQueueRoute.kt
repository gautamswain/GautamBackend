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
        val bodyText = try { call.receiveText() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid body: ${e.message}"))
            return@post
        }

        val jsonObj = try {
            Json.parseToJsonElement(bodyText).jsonObject
        } catch (_: Exception) {
            null
        }

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
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Turso connection is null"))
            return@post
        }

        try {
            conn.use { c ->
                // Check actual columns of Comments table first
                val existingCols = mutableSetOf<String>()
                try {
                    c.createStatement().use { st ->
                        st.executeQuery("PRAGMA table_info(Comments)").use { rs ->
                            while (rs.next()) {
                                existingCols.add(rs.getString("name").lowercase())
                            }
                        }
                    }
                } catch (_: Exception) {}

                if (existingCols.isEmpty()) {
                    c.createStatement().use { st ->
                        st.execute(
                            """
                            CREATE TABLE IF NOT EXISTS Comments (
                                id INTEGER PRIMARY KEY AUTOINCREMENT,
                                slno TEXT,
                                separater TEXT,
                                jsondetails TEXT,
                                wef TEXT
                            )
                            """.trimIndent()
                        )
                    }
                }

                // Dynamic INSERT matching existing columns in Comments
                val nextId = try {
                    c.createStatement().use { st ->
                        st.executeQuery("SELECT COALESCE(MAX(CAST(id AS INTEGER)), 0) + 1 FROM Comments").use { rs ->
                            if (rs.next()) rs.getInt(1) else 1
                        }
                    }
                } catch (_: Exception) { 1 }

                val inserted = try {
                    c.prepareStatement("INSERT INTO Comments (slno, separater, jsondetails, wef) VALUES (?, ?, ?, ?)").use { ps ->
                        ps.setString(1, slno)
                        ps.setString(2, separater)
                        ps.setString(3, jsonDetails)
                        ps.setString(4, wef)
                        ps.executeUpdate() > 0
                    }
                } catch (e1: Exception) {
                    println("Insert without id failed: ${e1.message}, trying with explicit id=$nextId")
                    c.prepareStatement("INSERT INTO Comments (id, slno, separater, jsondetails, wef) VALUES (?, ?, ?, ?, ?)").use { ps ->
                        ps.setInt(1, nextId)
                        ps.setString(2, slno)
                        ps.setString(3, separater)
                        ps.setString(4, jsonDetails)
                        ps.setString(5, wef)
                        ps.executeUpdate() > 0
                    }
                }

                if (inserted) {
                    call.respond(HttpStatusCode.OK, mapOf("status" to "success", "message" to "Appended to Comments"))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Insert returned 0 rows"))
                }
            }
        } catch (e: Exception) {
            println("CRITICAL ADD-TO-QUEUE ERROR: ${e.javaClass.name}: ${e.message}")
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "${e.javaClass.simpleName}: ${e.message}")
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