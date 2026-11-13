package com.example

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL

typealias CommentRecordMap = Map<String, String>

private fun executeTursoHttpSql(sql: String, args: List<String> = emptyList()): Pair<Boolean, String> {
    val rawUrl = System.getenv("TURSO_DATABASE_URL")?.trim().orEmpty()
        .ifBlank { "https://adhikarhr-gautamswain.aws-ap-south-1.turso.io" }
    val authToken = System.getenv("TURSO_AUTH_TOKEN")?.trim().orEmpty()
        .ifBlank { "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJpYXQiOjE3OTI5MzM2NTIsImlkIjoiMDFhMTczMGItZGYwMS03ODc3LWEwZTctZDI4MGY3ODczYmMzIiwia2lkIjoiSFNDdE1aWHdidGJ4allfeERJMWV2ZjBERHJ3WU1odlVyb0k4dnBHX25EayIsInJpZCI6ImUzNzM5ODAzLWE2NjEtNDlhNS1hMmFkLTg4YzMzNDk2Y2FmNiJ9.HXc7gy5g_F_qilsgjsSC1aG_GrV1-TvX-3i6Py1EwAM77R6EuUpB4_0pmnE5215WIIbk3sD7lqI72lTF3mo8Cg" }

    val host = rawUrl
        .removePrefix("jdbc:sqlite:")
        .removePrefix("jdbc:dbeaver:libsql:")
        .removePrefix("libsql://")
        .removePrefix("https://")
        .split("]").first().trim().trimEnd('/')

    val endpoint = "https://$host/v2/pipeline"

    val argsJson = args.joinToString(",") { arg ->
        val escaped = JsonPrimitive(arg).toString()
        """{"type":"text","value":$escaped}"""
    }
    val escapedSql = JsonPrimitive(sql).toString()
    val bodyPayload = """
        {
          "requests": [
            { "type": "execute", "stmt": { "sql": $escapedSql, "args": [$argsJson] } },
            { "type": "close" }
          ]
        }
    """.trimIndent()

    val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 15000
        readTimeout = 15000
        doOutput = true
        setRequestProperty("Authorization", "Bearer $authToken")
        setRequestProperty("Content-Type", "application/json")
    }

    conn.outputStream.use { it.write(bodyPayload.toByteArray(Charsets.UTF_8)) }
    val code = conn.responseCode
    val resp = try {
        (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
    } catch (_: Exception) { "" }

    val hasSqlError = resp.contains("\"type\":\"error\"") || code !in 200..299
    return (!hasSqlError) to resp
}

fun Route.commentsQueueRoute() {
    post("/api/turso/add-to-queue") {
        try {
            val bodyText = call.receiveText()
            val jsonElement = try {
                Json.parseToJsonElement(bodyText).jsonObject
            } catch (_: Exception) {
                null
            }

            val slno = jsonElement?.get("slno")?.jsonPrimitive?.contentOrNull?.ifBlank { "1" } ?: "1"
            val separater = (jsonElement?.get("separater")?.jsonPrimitive?.contentOrNull
                ?: jsonElement?.get("separator")?.jsonPrimitive?.contentOrNull
                ?: "MUP").ifBlank { "MUP" }
            val jsonDetails = jsonElement?.get("jsondetails")?.jsonPrimitive?.contentOrNull
                ?: jsonElement?.get("jsonDetails")?.jsonPrimitive?.contentOrNull
                ?: bodyText
            val wef = (jsonElement?.get("wef")?.jsonPrimitive?.contentOrNull
                ?: java.time.LocalDate.now().toString()).ifBlank { java.time.LocalDate.now().toString() }

            // 1. Try INSERT without id (AUTOINCREMENT) via direct Turso HTTP API
            var (ok, resp) = executeTursoHttpSql(
                "INSERT INTO Comments (slno, separater, jsondetails, wef) VALUES (?, ?, ?, ?)",
                listOf(slno, separater, jsonDetails, wef)
            )

            // 2. If id is NOT NULL without AUTOINCREMENT, insert with computed next id
            if (!ok && resp.contains("Comments.id", ignoreCase = true)) {
                val (ok2, resp2) = executeTursoHttpSql(
                    "INSERT INTO Comments (id, slno, separater, jsondetails, wef) VALUES ((SELECT COALESCE(MAX(CAST(id AS INTEGER)), 0) + 1 FROM Comments), ?, ?, ?, ?)",
                    listOf(slno, separater, jsonDetails, wef)
                )
                ok = ok2
                resp = resp2
            }

            if (ok) {
                println("TURSO HTTP INSERT SUCCESS: separater=$separater, slno=$slno")
                call.respond(HttpStatusCode.OK, mapOf("status" to "success", "message" to "Record successfully added to queue"))
            } else {
                println("TURSO HTTP INSERT FAILED: $resp")
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Turso Error: $resp"))
            }
        } catch (t: Throwable) {
            println("ADD-TO-QUEUE ERROR: ${t.javaClass.name}: ${t.message}")
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "${t.javaClass.simpleName}: ${t.message}"))
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
                val empCode = obj["Code_2"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
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

            executeTursoHttpSql("UPDATE Comments SET separater = 'CONF' WHERE id = ?", listOf(commentId))
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

        val (ok, resp) = executeTursoHttpSql("UPDATE Comments SET separater = 'REJ' WHERE id = ?", listOf(commentId))
        if (ok) {
            call.respond(HttpStatusCode.OK, mapOf("status" to "rejected", "message" to "Rejected"))
        } else {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Reject failed: $resp"))
        }
    }
}