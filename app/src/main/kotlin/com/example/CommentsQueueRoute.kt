package com.example

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL

typealias CommentRecordMap = Map<String, String>

private fun triggerReportMeServiceAsync() {
    CoroutineScope(Dispatchers.IO).launch {
        var reportConn: java.sql.Connection? = null
        try {
            reportConn = TursoDatabaseService.getConnection()
            if (reportConn != null) {
                ReportMeService.processAndSave(reportConn)
                println("REPORTME SERVICE: reports table updated successfully")
            } else {
                println("REPORTME SERVICE WARNING: DB connection null")
            }
        } catch (e: Exception) {
            println("REPORTME SERVICE ERROR: ${e.message}")
        } finally {
            try { reportConn?.close() } catch (_: Exception) {}
        }
    }
}

private fun executeTursoHttpSql(sql: String, args: List<String> = emptyList()): Pair<Boolean, String> {
    val rawUrl = System.getenv("TURSO_DATABASE_URL")?.trim().orEmpty()
        .ifBlank { "https://adhikarhr-gautamswain.aws-ap-south-1.turso.io" }
    val authToken = System.getenv("TURSO_AUTH_TOKEN")?.trim().orEmpty()
        .ifBlank { throw IllegalStateException("TURSO_AUTH_TOKEN missing") }

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

    // 1. Add any record (MUP / NewJ / RewJ / MSG) to Comments Queue
    //    Automatically detects duplicate in MasterData -> sets separater = 'RewJ' & attaches existing Code_2
    post("/api/turso/add-to-queue") {
        try {
            val bodyText = call.receiveText()
            val jsonElement = try {
                Json.parseToJsonElement(bodyText).jsonObject
            } catch (_: Exception) {
                null
            }

            val slno = jsonElement?.get("slno")?.jsonPrimitive?.contentOrNull?.ifBlank { "1" } ?: "1"
            var separater = (jsonElement?.get("separater")?.jsonPrimitive?.contentOrNull
                ?: jsonElement?.get("separator")?.jsonPrimitive?.contentOrNull
                ?: "MUP").ifBlank { "MUP" }
            var jsonDetails = jsonElement?.get("jsondetails")?.jsonPrimitive?.contentOrNull
                ?: jsonElement?.get("jsonDetails")?.jsonPrimitive?.contentOrNull
                ?: bodyText
            val wef = (jsonElement?.get("wef")?.jsonPrimitive?.contentOrNull
                ?: java.time.LocalDate.now().toString()).ifBlank { java.time.LocalDate.now().toString() }

            var matchedOldCode = ""

            if (separater.equals("NewJ", ignoreCase = true) || separater.equals("RewJ", ignoreCase = true)) {
                val conn = TursoDatabaseService.getConnection()
                if (conn != null) {
                    try {
                        val detailsObj = Json.parseToJsonElement(jsonDetails).jsonObject
                        fun dStr(vararg keys: String): String {
                            for (k in keys) {
                                val v = detailsObj[k]?.jsonPrimitive?.contentOrNull?.trim()
                                if (!v.isNullOrBlank()) return v
                            }
                            return ""
                        }

                        val explicitOldCode = dStr("Code_2", "existing_emp_id", "empId")
                        if (explicitOldCode.isNotBlank()) {
                            matchedOldCode = explicitOldCode
                        } else {
                            val aadhar = dStr("aadhar_no", "aadharNo").replace(Regex("[^0-9]"), "")
                            val pan = dStr("pan_no", "panNo").uppercase().replace(Regex("[^A-Z0-9]"), "")
                            val acc = dStr("account_no", "accountNo").replace(Regex("[^0-9]"), "")
                            val empName = dStr("emp_name", "empName").lowercase()
                            val fatherName = dStr("fathers_name", "fathersName").lowercase()

                            val conditions = mutableListOf<String>()
                            val params = mutableListOf<String>()

                            if (aadhar.length >= 10) {
                                conditions.add("REPLACE(TRIM(aadhar_card_no_16), ' ', '') = ?")
                                params.add(aadhar)
                            }
                            if (pan.length == 10) {
                                conditions.add("UPPER(TRIM(pan_card_no_17)) = ?")
                                params.add(pan)
                            }
                            if (acc.length >= 9) {
                                conditions.add("TRIM(bank_account_number_20) = ?")
                                params.add(acc)
                            }
                            if (empName.isNotBlank() && fatherName.isNotBlank()) {
                                conditions.add("(LOWER(TRIM(employee_name_3)) = ? AND LOWER(TRIM(fathers_name_4)) = ?)")
                                params.add(empName)
                                params.add(fatherName)
                            }

                            if (conditions.isNotEmpty()) {
                                val dupSql = "SELECT TRIM(Code_2) FROM MasterData WHERE UPPER(TRIM(Code_2)) LIKE 'ADKR%' AND (${conditions.joinToString(" OR ")}) ORDER BY rowid DESC LIMIT 1"
                                val ps = conn.prepareStatement(dupSql)
                                params.forEachIndexed { idx, p -> ps.setString(idx + 1, p) }
                                val rs = ps.executeQuery()
                                if (rs.next()) {
                                    matchedOldCode = rs.getString(1)?.trim().orEmpty()
                                }
                                rs.close()
                                ps.close()
                            }
                        }

                        if (matchedOldCode.isNotBlank()) {
                            separater = "RewJ"
                            val updatedMap = detailsObj.toMutableMap()
                            updatedMap["Code_2"] = JsonPrimitive(matchedOldCode)
                            updatedMap["existing_emp_id"] = JsonPrimitive(matchedOldCode)
                            jsonDetails = JsonObject(updatedMap).toString()
                        }
                    } catch (e: Exception) {
                        println("DUPLICATE CHECK WARNING: ${e.message}")
                    } finally {
                        try { conn.close() } catch (_: Exception) {}
                    }
                }
            }

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
                val msg = if (separater == "RewJ") {
                    "Duplicate found ($matchedOldCode) -> Added to queue as RewJ with Code attached"
                } else {
                    "Record successfully added to queue"
                }
                println("TURSO HTTP INSERT SUCCESS: separater=$separater, slno=$slno, code=$matchedOldCode")
                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "status" to "success",
                        "separater" to separater,
                        "code" to matchedOldCode,
                        "message" to msg
                    )
                )
            } else {
                println("TURSO HTTP INSERT FAILED: $resp")
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Turso Error: $resp"))
            }
        } catch (t: Throwable) {
            println("ADD-TO-QUEUE ERROR: ${t.javaClass.name}: ${t.message}")
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "${t.javaClass.simpleName}: ${t.message}"))
        }
    }

    // 2. STAGE 1: Fetch Comments with formatted 'display_message' for NewJ & RewJ
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

                val displayMsg = when {
                    rowSep.equals("NewJ", ignoreCase = true) -> {
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
                    }
                    rowSep.equals("RewJ", ignoreCase = true) -> {
                        try {
                            val obj = Json.parseToJsonElement(rawJson).jsonObject
                            val empName = (obj["emp_name"]?.jsonPrimitive?.contentOrNull
                                ?: obj["empName"]?.jsonPrimitive?.contentOrNull).orEmpty().trim()
                            val designation = obj["designation"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                            val branchName = (obj["branch_name"]?.jsonPrimitive?.contentOrNull
                                ?: obj["branchName"]?.jsonPrimitive?.contentOrNull).orEmpty().trim()
                            val oldCode = (obj["Code_2"]?.jsonPrimitive?.contentOrNull
                                ?: obj["existing_emp_id"]?.jsonPrimitive?.contentOrNull).orEmpty().trim()
                            "Rejaining Mr $empName at $branchName Branch as $designation on existing Code $oldCode - Click to proceed"
                        } catch (_: Exception) {
                            rawJson
                        }
                    }
                    else -> rawJson
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

    // 3A. STAGE 2A: Confirm NewJ ->
    //     a) Cross-verify last 5th row + 5 AND last 3rd row + 3 for sl_no_1 & Code_2 (ADKRXXXX)
    //     b) Insert into MasterData with generated Code_2 and emp_status_35='1', emp_status_temp_36='1', slary_37='1'
    //     c) Update existing NewJ comment row separater = 'CONF'
    //     d) Append new MSG row & run ReportMeService in background
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

            val st = conn.createStatement()
            val rsRecent = st.executeQuery(
                "SELECT TRIM(sl_no_1), TRIM(Code_2) FROM MasterData WHERE UPPER(TRIM(Code_2)) LIKE 'ADKR%' ORDER BY rowid DESC LIMIT 5"
            )
            val recentRows = mutableListOf<Pair<Int, Int>>()
            while (rsRecent.next()) {
                val sNum = rsRecent.getString(1)?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0
                val cRaw = rsRecent.getString(2) ?: ""
                val cNum = cRaw.replace(Regex("[^0-9]"), "").takeLast(4).toIntOrNull() ?: 0
                recentRows.add(sNum to cNum)
            }
            rsRecent.close()
            st.close()

            if (recentRows.size < 5) {
                conn.close()
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "MasterData has fewer than 5 rows for sequence check"))
                return@post
            }

            val (sl3rd, code3rd) = recentRows[2]
            val (sl5th, code5th) = recentRows[4]

            val expectedSlFrom3rd = sl3rd + 3
            val expectedSlFrom5th = sl5th + 5

            val expectedCodeFrom3rd = code3rd + 3
            val expectedCodeFrom5th = code5th + 5

            if (expectedCodeFrom3rd != expectedCodeFrom5th || expectedSlFrom3rd != expectedSlFrom5th) {
                conn.close()
                call.respond(
                    HttpStatusCode.Conflict,
                    mapOf(
                        "error" to "Sequence mismatch in MasterData: 5th-last ($code5th + 5 = $expectedCodeFrom5th) != 3rd-last ($code3rd + 3 = $expectedCodeFrom3rd)"
                    )
                )
                return@post
            }

            val nextSlNo = expectedSlFrom5th
            val generatedCode = "ADKR${String.format("%04d", expectedCodeFrom5th)}"
            conn.close()

            val insertMasterSql = """
                INSERT INTO MasterData (
                    sl_no_1, Code_2, employee_name_3, fathers_name_4, gender_5, dob_6,
                    designation_7, branch_name_9, doj_in_adhikar_10, present_address_11,
                    permanent_address_12, highest_educational_qualification_13,
                    personal_contact_number_14, contact_no_2_15, aadhar_card_no_16,
                    pan_card_no_17, bank_name_19, bank_account_number_20,
                    ifsc_code_bank_branch_name_21, esi_no_22, epf_uan_no_23,
                    company_name_starting_from_current_to_old_26, interviewed_31,
                    emp_status_35, emp_status_temp_36, slary_37, email61_personal_52
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '1', '1', '1', ?)
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

            executeTursoHttpSql(
                "UPDATE Comments SET separater = 'CONF' WHERE id = ?",
                listOf(commentId)
            )

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

            // Refresh reports table in background
            triggerReportMeServiceAsync()

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

    // 3B. STAGE 2B: Confirm RewJ (1-Click Re-Joining) ->
    //     a) Update existing ADKRXXXX row in MasterData (emp_status_35='1', emp_status_temp_36='1', slary_37='1')
    //     b) Update RewJ comment row -> CONF
    //     c) Post MSG & run ReportMeService in background
    post("/api/turso/confirm-rewj/{id}") {
        val commentId = call.parameters["id"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))

        val conn = TursoDatabaseService.getConnection()
            ?: return@post call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))

        try {
            var storedJson = ""
            var wefDate = java.time.LocalDate.now().toString()

            val selectPs = conn.prepareStatement("SELECT jsondetails, wef FROM Comments WHERE id = ?")
            selectPs.setString(1, commentId)
            val rs = selectPs.executeQuery()
            if (rs.next()) {
                storedJson = rs.getString("jsondetails") ?: ""
                val dbWef = rs.getString("wef") ?: ""
                if (dbWef.isNotBlank()) wefDate = dbWef
            }
            rs.close()
            selectPs.close()
            conn.close()

            if (storedJson.isBlank()) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "RewJ record not found"))
                return@post
            }

            val obj = Json.parseToJsonElement(storedJson).jsonObject
            fun str(vararg keys: String): String {
                for (k in keys) {
                    val v = obj[k]?.jsonPrimitive?.contentOrNull?.trim()
                    if (!v.isNullOrBlank()) return v
                }
                return ""
            }

            val existingEmpId = str("Code_2", "existing_emp_id", "empId")
            val empName = str("emp_name", "empName")
            val designation = str("designation")
            val branchName = str("branch_name", "branchName")
            val dojVal = str("doj_in_adhikar", "dojInAdhikar").ifBlank { wefDate }

            if (existingEmpId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing existing Code in RewJ record"))
                return@post
            }

            val setParts = mutableListOf(
                "emp_status_35 = '1'",
                "emp_status_temp_36 = '1'",
                "slary_37 = '1'",
                "mast_rem_38 = COALESCE(NULLIF(TRIM(mast_rem_38), ''), '') || ' | Re-joined on ' || ?"
            )
            val args = mutableListOf(dojVal)

            fun addIfPresent(colName: String, value: String) {
                if (value.isNotBlank()) {
                    setParts.add("$colName = ?")
                    args.add(value)
                }
            }

            addIfPresent("employee_name_3", empName)
            addIfPresent("fathers_name_4", str("fathers_name", "fathersName"))
            addIfPresent("gender_5", str("gender"))
            addIfPresent("dob_6", str("dob"))
            addIfPresent("designation_7", designation)
            addIfPresent("branch_name_9", branchName)
            addIfPresent("doj_in_adhikar_10", dojVal)
            addIfPresent("present_address_11", str("present_address", "presentAddress"))
            addIfPresent("permanent_address_12", str("permanent_address", "permanentAddress"))
            addIfPresent("highest_educational_qualification_13", str("education"))
            addIfPresent("personal_contact_number_14", str("contact_no", "contactNo"))
            addIfPresent("contact_no_2_15", str("contactno_2", "contactno2"))
            addIfPresent("aadhar_card_no_16", str("aadhar_no", "aadharNo"))
            addIfPresent("pan_card_no_17", str("pan_no", "panNo"))
            addIfPresent("bank_name_19", str("bank_name", "bankName"))
            addIfPresent("bank_account_number_20", str("account_no", "accountNo"))
            addIfPresent("ifsc_code_bank_branch_name_21", str("ifsc_code", "ifscCode"))
            addIfPresent("esi_no_22", str("esi"))
            addIfPresent("epf_uan_no_23", str("uan_no", "uanNo"))
            addIfPresent("email61_personal_52", str("email"))

            args.add(existingEmpId)
            val updateSql = "UPDATE MasterData SET ${setParts.joinToString(", ")} WHERE UPPER(TRIM(Code_2)) = UPPER(TRIM(?))"
            val (updateOk, updateResp) = executeTursoHttpSql(updateSql, args)

            if (!updateOk) {
                println("MASTERDATA REWJ UPDATE FAILED: $updateResp")
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "MasterData RewJ Update Failed: $updateResp"))
                return@post
            }

            executeTursoHttpSql("UPDATE Comments SET separater = 'CONF' WHERE id = ?", listOf(commentId))

            val rejoinMsg = "Mr $empName at $branchName Branch as $designation rejained confirmed"
            val (msgOk, msgResp) = executeTursoHttpSql(
                "INSERT INTO Comments (slno, separater, jsondetails, wef) VALUES (?, ?, ?, ?)",
                listOf("0", "MSG", rejoinMsg, dojVal)
            )
            if (!msgOk && msgResp.contains("Comments.id", ignoreCase = true)) {
                executeTursoHttpSql(
                    "INSERT INTO Comments (id, slno, separater, jsondetails, wef) VALUES ((SELECT COALESCE(MAX(CAST(id AS INTEGER)), 0) + 1 FROM Comments), ?, ?, ?, ?)",
                    listOf("0", "MSG", rejoinMsg, dojVal)
                )
            }

            // Refresh reports table in background
            triggerReportMeServiceAsync()

            println("REWJ CONFIRMED: code=$existingEmpId | msg=$rejoinMsg")
            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "status" to "confirmed",
                    "code" to existingEmpId,
                    "message" to rejoinMsg
                )
            )
        } catch (e: Exception) {
            try { conn.close() } catch (_: Exception) {}
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Confirm RewJ failed")))
        }
    }

    // 4. Confirm MUP -> Update MasterData, mark CONF & run ReportMeService in background
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

            // Refresh reports table in background
            triggerReportMeServiceAsync()

            call.respond(HttpStatusCode.OK, mapOf("status" to "confirmed", "message" to "Confirmed & MasterData updated"))
        } catch (e: Exception) {
            try { conn.close() } catch (_: Exception) {}
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Confirm failed")))
        }
    }

    // 5. Reject any pending comment (MUP / NewJ / RewJ)
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