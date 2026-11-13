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

    // 1. Add any record (MUP / NewJ / MSG) to Comments Queue
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

            var (ok, resp) = executeTursoHttpSql(
                "INSERT INTO Comments (slno, separater, jsondetails, wef) VALUES (?, ?, ?, ?)",
                listOf(slno, separater, jsonDetails, wef)
            )

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

    // 2. STAGE 1: Fetch Comments with formatted 'display_message' for NewJ
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
                val rowId = rs.getString("id") ?: ""
                val rowSlno = rs.getString("slno") ?: ""
                val rowSep = rs.getString("separater") ?: ""
                val rawJson = rs.getString("jsondetails") ?: ""
                val rowWef = rs.getString("wef") ?: ""

                val displayMsg = if (rowSep.equals("NewJ", ignoreCase = true)) {
                    try {
                        val obj = Json.parseToJsonElement(rawJson).jsonObject
                        val empName = (obj["emp_name"]?.jsonPrimitive?.contentOrNull
                            ?: obj["empName"]?.jsonPrimitive?.contentOrNull).orEmpty().trim()
                        val designation = obj["designation"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                        val branchName = (obj["branch_name"]?.jsonPrimitive?.contentOrNull
                            ?: obj["branchName"]?.jsonPrimitive?.contentOrNull).orEmpty().trim()
                        "Approval for NewJoining- Mr. $empName, $designation, $branchName- Proceed"
                    } catch (_: Exception) {
                        rawJson
                    }
                } else {
                    rawJson
                }

                records.add(
                    mapOf(
                        "id" to rowId,
                        "slno" to rowSlno,
                        "separater" to rowSep,
                        "jsondetails" to rawJson,
                        "display_message" to displayMsg,
                        "wef" to rowWef
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

    // 3. STAGE 2: Confirm NewJ ->
    //    a) Insert into MasterData with Code_2 = ADKRXXXX
    //    b) Update existing NewJ comment row separater = 'CONF'
    //    c) Append new MSG row: "Mr. <Name> Joined at <Branch> branch as <Designation> - Code- ADKRXXXX"
    post("/api/turso/confirm-newj/{id}") {
        val commentId = call.parameters["id"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))

        val conn = TursoDatabaseService.getConnection()
            ?: return@post call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))

        try {
            var jsonDetails = ""
            var wefDate = java.time.LocalDate.now().toString()

            val selectPs = conn.prepareStatement("SELECT jsondetails, wef FROM Comments WHERE id = ?")
            selectPs.setString(1, commentId)
            val rs = selectPs.executeQuery()
            if (rs.next()) {
                jsonDetails = rs.getString("jsondetails") ?: ""
                val dbWef = rs.getString("wef") ?: ""
                if (dbWef.isNotBlank()) wefDate = dbWef
            }
            rs.close()
            selectPs.close()

            if (jsonDetails.isBlank()) {
                conn.close()
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "NewJ record not found"))
                return@post
            }

            // Allow optional edited JSON body from NewJoiningScreen, else use stored JSON
            val requestBody = try { call.receiveText() } catch (_: Exception) { "" }
            val effectiveJson = if (requestBody.trim().startsWith("{")) requestBody else jsonDetails

            val obj = Json.parseToJsonElement(effectiveJson).jsonObject
            fun str(vararg keys: String): String {
                for (k in keys) {
                    val v = obj[k]?.jsonPrimitive?.contentOrNull?.trim()
                    if (!v.isNullOrBlank()) return v
                }
                return ""
            }

            val empName = str("emp_name", "empName")
            val designation = str("designation")
            val branchName = str("branch_name", "branchName")
            val dojVal = str("doj_in_adhikar", "dojInAdhikar").ifBlank { wefDate }

            // Generate next sl_no_1 and ADKRXXXX Code_2
            var nextSlNo = 1
            val st = conn.createStatement()
            val rsMax = st.executeQuery("SELECT COALESCE(MAX(CAST(sl_no_1 AS INTEGER)), 0) + 1 FROM MasterData")
            if (rsMax.next()) nextSlNo = rsMax.getInt(1)
            rsMax.close()
            st.close()

            val generatedCode = "ADKR${String.format("%04d", nextSlNo)}"
            conn.close()

            // Insert into MasterData via fast Turso HTTP pipeline (avoids 10s JDBC write timeout)
            val insertMasterSql = """
                INSERT INTO MasterData (
                    sl_no_1, Code_2, employee_name_3, fathers_name_4, gender_5, dob_6,
                    designation_7, branch_name_9, doj_in_adhikar_10, present_address_11,
                    permanent_address_12, highest_educational_qualification_13,
                    personal_contact_number_14, contact_no_2_15, aadhar_card_no_16,
                    pan_card_no_17, bank_name_19, bank_account_number_20,
                    ifsc_code_bank_branch_name_21, esi_no_22, epf_uan_no_23,
                    company_name_starting_from_current_to_old_26, interviewed_31,
                    emp_status_35, email61_personal_52
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'Active', ?)
            """.trimIndent()

            val masterArgs = listOf(
                nextSlNo.toString(),
                generatedCode,
                empName,
                str("fathers_name", "fathersName"),
                str("gender"),
                str("dob"),
                designation,
                branchName,
                str("doj_in_adhikar", "dojInAdhikar"),
                str("present_address", "presentAddress"),
                str("permanent_address", "permanentAddress"),
                str("education"),
                str("contact_no", "contactNo"),
                str("contactno_2", "contactno2"),
                str("aadhar_no", "aadharNo"),
                str("pan_no", "panNo"),
                str("bank_name", "bankName"),
                str("account_no", "accountNo"),
                str("ifsc_code", "ifscCode"),
                str("esi"),
                str("uan_no", "uanNo"),
                str("company_experience", "companyExperience"),
                str("interviewd_by", "interviewdBy"),
                str("email")
            )

            val (masterOk, masterResp) = executeTursoHttpSql(insertMasterSql, masterArgs)
            if (!masterOk) {
                println("MASTERDATA INSERT FAILED: $masterResp")
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "MasterData Insert Failed: $masterResp"))
                return@post
            }

            // Update NewJ comment row -> CONF
            executeTursoHttpSql(
                "UPDATE Comments SET separater = 'CONF' WHERE id = ?",
                listOf(commentId)
            )

            // Append new joining announcement message -> MSG
            val joiningMsg = "Mr. $empName Joined at $branchName branch as $designation - Code- $generatedCode"
            val (msgOk, msgResp) = executeTursoHttpSql(
                "INSERT INTO Comments (slno, separater, jsondetails, wef) VALUES (?, ?, ?, ?)",
                listOf(nextSlNo.toString(), "MSG", joiningMsg, dojVal)
            )
            if (!msgOk && msgResp.contains("Comments.id", ignoreCase = true)) {
                executeTursoHttpSql(
                    "INSERT INTO Comments (id, slno, separater, jsondetails, wef) VALUES ((SELECT COALESCE(MAX(CAST(id AS INTEGER)), 0) + 1 FROM Comments), ?, ?, ?, ?)",
                    listOf(nextSlNo.toString(), "MSG", joiningMsg, dojVal)
                )
            }

            println("NEWJ CONFIRMED: code=$generatedCode | msg=$joiningMsg")
            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "status" to "confirmed",
                    "code" to generatedCode,
                    "message" to joiningMsg
                )
            )
        } catch (e: Exception) {
            try { conn.close() } catch (_: Exception) {}
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Confirm NewJ failed")))
        }
    }

    // 4. Confirm MUP -> Update MasterData & mark CONF
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
            conn.close()

            if (separater == "MUP" && jsonDetails.isNotBlank()) {
                val obj = Json.parseToJsonElement(jsonDetails).jsonObject
                val empCode = obj["Code_2"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
                val skipCols = setOf("id", "sl_no_1", "Code_2", "employee_name_3")
                val updates = obj.filterKeys { it !in skipCols }

                if (empCode.isNotBlank() && updates.isNotEmpty()) {
                    val setClause = updates.keys.joinToString(", ") { "$it = ?" }
                    val args = updates.values.map { it.jsonPrimitive.content } + empCode
                    executeTursoHttpSql("UPDATE MasterData SET $setClause WHERE LOWER(TRIM(Code_2)) = LOWER(TRIM(?))", args)
                }
            }

            executeTursoHttpSql("UPDATE Comments SET separater = 'CONF' WHERE id = ?", listOf(commentId))
            call.respond(HttpStatusCode.OK, mapOf("status" to "confirmed", "message" to "Confirmed & MasterData updated"))
        } catch (e: Exception) {
            try { conn.close() } catch (_: Exception) {}
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Confirm failed")))
        }
    }

    // 5. Reject any pending comment (MUP / NewJ)
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