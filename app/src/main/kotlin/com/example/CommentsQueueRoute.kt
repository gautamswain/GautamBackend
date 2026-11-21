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
import java.sql.Connection
import java.util.Locale

typealias CommentRecordMap = Map<String, String>

private fun triggerReportMeServiceAsync() {
    CoroutineScope(Dispatchers.IO).launch {
        var reportConn: Connection? = null
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

fun resolveDesignationFromDb(conn: Connection, rawDesignation: String): String {
    val clean = rawDesignation.trim()
    if (clean.isBlank()) return ""

    val shtcodeMap = mutableMapOf<String, String>()
    val fullDesignationMap = mutableMapOf<String, String>()

    try {
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT designation, shtcode FROM Designation").use { rs ->
                while (rs.next()) {
                    val desig = rs.getString("designation")?.trim().orEmpty()
                    val sht = rs.getString("shtcode")?.trim().orEmpty()
                    if (desig.isNotBlank()) {
                        fullDesignationMap[desig.lowercase()] = desig
                        fullDesignationMap[desig.lowercase().replace(Regex("[^a-z0-9]"), "")] = desig

                        if (sht.isNotBlank()) {
                            val aliases = sht.split(Regex("[,/|;]+"))
                            for (alias in aliases) {
                                val cleanAlias = alias.trim().lowercase().replace(".", "")
                                if (cleanAlias.isNotBlank()) {
                                    shtcodeMap[cleanAlias] = desig
                                    shtcodeMap[cleanAlias.replace(Regex("[^a-z0-9]"), "")] = desig
                                }
                            }
                        }
                    }
                }
            }
        }
    } catch (_: Exception) {}

    val normalized = clean.lowercase().replace(".", "").replace(Regex("\\s+"), " ").trim()
    val alphanumericKey = normalized.replace(Regex("[^a-z0-9]"), "")

    return shtcodeMap[normalized]
        ?: shtcodeMap[alphanumericKey]
        ?: fullDesignationMap[clean.lowercase()]
        ?: fullDesignationMap[alphanumericKey]
        ?: clean.lowercase().split(Regex("\\s+")).joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
        }
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

            if (separater.equals("MUP", ignoreCase = true)) {
                val conn = TursoDatabaseService.getConnection()
                if (conn != null) {
                    try {
                        val detailsObj = Json.parseToJsonElement(jsonDetails).jsonObject
                        val updatedMap = detailsObj.toMutableMap()
                        var modified = false
                        for ((k, v) in detailsObj) {
                            val keyLower = k.lowercase()
                            if (keyLower.contains("designation") || keyLower == "designation_7") {
                                val rawDesig = v.jsonPrimitive.contentOrNull.orEmpty()
                                if (rawDesig.isNotBlank()) {
                                    val resolved = resolveDesignationFromDb(conn, rawDesig)
                                    updatedMap[k] = JsonPrimitive(resolved)
                                    modified = true
                                }
                            }
                        }
                        if (modified) {
                            jsonDetails = JsonObject(updatedMap).toString()
                        }
                    } catch (e: Exception) {
                        println("MUP DESIGNATION RESOLUTION WARNING: ${e.message}")
                    } finally {
                        try { conn.close() } catch (_: Exception) {}
                    }
                }
            }

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
            val list = mutableListOf<CommentRecordMap>()
            val stmt = conn.createStatement()
            val rs = stmt.executeQuery("SELECT rowid AS id, slno, separater, jsondetails, wef FROM Comments WHERE UPPER(TRIM(separater)) = UPPER(TRIM('$separater')) ORDER BY rowid DESC;")
            val meta = rs.metaData
            val colCount = meta.columnCount

            while (rs.next()) {
                val map = mutableMapOf<String, String>()
                for (i in 1..colCount) {
                    val colName = meta.getColumnName(i)
                    val colVal = rs.getString(i) ?: ""
                    map[colName] = colVal
                }
                val jsonStr = map["jsondetails"] ?: ""
                var displayMsg = ""

                if (jsonStr.isNotBlank()) {
                    try {
                        val jsonElement = Json.parseToJsonElement(jsonStr)
                        if (jsonElement is JsonObject) {
                            val empName = jsonElement["emp_name"]?.jsonPrimitive?.contentOrNull
                                ?: jsonElement["empName"]?.jsonPrimitive?.contentOrNull
                                ?: jsonElement["employee_name_3"]?.jsonPrimitive?.contentOrNull
                                ?: jsonElement["name"]?.jsonPrimitive?.contentOrNull
                                ?: "Employee"
                            val code2 = jsonElement["Code_2"]?.jsonPrimitive?.contentOrNull
                                ?: jsonElement["code"]?.jsonPrimitive?.contentOrNull
                                ?: jsonElement["empId"]?.jsonPrimitive?.contentOrNull
                                ?: ""
                            val branch = jsonElement["branch_name"]?.jsonPrimitive?.contentOrNull
                                ?: jsonElement["branchName"]?.jsonPrimitive?.contentOrNull
                                ?: jsonElement["branch_name_9"]?.jsonPrimitive?.contentOrNull
                                ?: ""
                            val desig = jsonElement["designation"]?.jsonPrimitive?.contentOrNull
                                ?: jsonElement["designation_7"]?.jsonPrimitive?.contentOrNull
                                ?: ""

                            displayMsg = when (separater.uppercase()) {
                                "NEWJ" -> "New Joining: $empName ($code2) at $branch as $desig"
                                "REWJ" -> "Re-Joining: $empName ($code2) at $branch as $desig"
                                "MUP" -> "Master Update: $empName ($code2) - Desig: $desig"
                                else -> "Event: $empName ($code2)"
                            }
                        }
                    } catch (_: Exception) {
                        displayMsg = jsonStr
                    }
                }
                map["display_message"] = displayMsg
                list.add(map)
            }
            rs.close()
            stmt.close()
            conn.close()

            call.respond(HttpStatusCode.OK, list)
        } catch (e: Exception) {
            try { conn.close() } catch (_: Exception) {}
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Failed to fetch comments")))
        }
    }

    // 3A. STAGE 2A: Confirm NewJ (1-Click New Joining) ->
    //     a) Fetch jsonDetails from Comments table
    //     b) Insert into MasterData with generated Code_2 and emp_status_35='1', emp_status_temp_36='1'
    //     c) Update existing NewJ comment row -> CONF
    //     d) Post MSG & run ReportMeService in background
    post("/api/turso/confirm-newj/{id}") {
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
                wefDate = rs.getString("wef") ?: java.time.LocalDate.now().toString()
            }
            rs.close()
            selectPs.close()

            if (storedJson.isBlank()) {
                conn.close()
                return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Comment details not found"))
            }

            val detailsObj = Json.parseToJsonElement(storedJson).jsonObject

            fun str(vararg keys: String): String {
                for (k in keys) {
                    val v = detailsObj[k]?.jsonPrimitive?.contentOrNull?.trim()
                    if (!v.isNullOrBlank()) return v
                }
                return ""
            }

            val empName = str("emp_name", "empName", "employee_name_3")
            val fathersName = str("fathers_name", "fathersName", "fathers_name_4")
            val designation = str("designation", "designation_7")
            val branchName = str("branch_name", "branchName", "branch_name_9")

            // 1. Generate new Code_2 (ADKRXXXX)
            var nextNum = 1001
            val maxCodeStmt = conn.createStatement()
            val maxCodeRs = maxCodeStmt.executeQuery("SELECT MAX(CAST(SUBSTR(TRIM(Code_2), 5) AS INTEGER)) FROM MasterData WHERE UPPER(TRIM(Code_2)) LIKE 'ADKR%'")
            if (maxCodeRs.next()) {
                val m = maxCodeRs.getInt(1)
                if (m >= 1001) nextNum = m + 1
            }
            maxCodeRs.close()
            maxCodeStmt.close()

            val generatedCode = String.format(Locale.ENGLISH, "ADKR%04d", nextNum)

            // Get max slno for comments
            var nextSlNo = 1
            val maxSlStmt = conn.createStatement()
            val maxSlRs = maxSlStmt.executeQuery("SELECT COALESCE(MAX(CAST(slno AS INTEGER)), 0) + 1 FROM Comments")
            if (maxSlRs.next()) {
                nextSlNo = maxSlRs.getInt(1)
            }
            maxSlRs.close()
            maxSlStmt.close()
            conn.close()

            val dojVal = DateUtils.toExcelSerial(wef_or_doj(detailsObj))

            val insertMasterSql = """
                INSERT INTO MasterData (
                    sl_no_1, Code_2, employee_name_3, fathers_name_4, gender_5, dob_6, designation_7, department_8, branch_name_9, 
                    doj_in_adhikar_10, present_address_11, permanent_address_12, highest_educational_qualification_13, 
                    personal_contact_number_14, contact_no_2_15, aadhar_card_no_16, pan_card_no_17, bank_name_19, 
                    bank_account_number_20, ifsc_code_bank_branch_name_21, esi_no_22, epf_uan_no_23, company_experience_51, 
                    interviewd_by_56, email61_personal_52, emp_status_35, emp_status_temp_36, slary_37
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '1', '1', '1'
                )
            """.trimIndent()

            val designationVal = resolveDesignationFromDb(TursoDatabaseService.getConnection() ?: conn, designation)

            val masterArgs = listOf(
                nextSlNo.toString(),
                generatedCode,
                empName,
                fathersName,
                str("gender"),
                DateUtils.toExcelSerial(str("dob")),
                designationVal,
                str("department"),
                branchName,
                dojVal,
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

            val joiningMsg = "Mr. $empName Joined at $branchName branch as $designationVal - Code- $generatedCode"
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
                wefDate = rs.getString("wef") ?: java.time.LocalDate.now().toString()
            }
            rs.close()
            selectPs.close()

            if (storedJson.isBlank()) {
                conn.close()
                return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Comment details not found"))
            }

            val detailsObj = Json.parseToJsonElement(storedJson).jsonObject

            fun str(vararg keys: String): String {
                for (k in keys) {
                    val v = detailsObj[k]?.jsonPrimitive?.contentOrNull?.trim()
                    if (!v.isNullOrBlank()) return v
                }
                return ""
            }

            val empName = str("emp_name", "empName", "employee_name_3")
            val branchName = str("branch_name", "branchName", "branch_name_9")
            val designation = str("designation", "designation_7")
            val existingEmpId = str("Code_2", "existing_emp_id", "empId")

            val dojVal = DateUtils.toExcelSerial(wef_or_doj(detailsObj))
            val designationVal = resolveDesignationFromDb(conn, designation)

            val args = mutableListOf<String>()
            val setParts = mutableListOf<String>()

            fun addIfPresent(col: String, value: String) {
                if (value.isNotBlank()) {
                    setParts.add("$col = ?")
                    args.add(value)
                }
            }

            addIfPresent("emp_status_35", "1")
            addIfPresent("emp_status_temp_36", "1")
            addIfPresent("slary_37", "1")
            addIfPresent("employee_name_3", empName)
            addIfPresent("fathers_name_4", str("fathers_name", "fathersName"))
            addIfPresent("gender_5", str("gender"))
            addIfPresent("dob_6", DateUtils.toExcelSerial(str("dob")))
            addIfPresent("designation_7", designationVal)
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

            val rejoinMsg = "Mr $empName at $branchName Branch as $designationVal rejained confirmed"
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

            if (separater == "MUP" && jsonDetails.isNotBlank()) {
                val obj = Json.parseToJsonElement(jsonDetails).jsonObject
                val empCode = obj["Code_2"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
                val skipCols = setOf("id", "sl_no_1", "Code_2", "employee_name_3")
                val updates = obj.filterKeys { it !in skipCols && it.matches(Regex("^[a-zA-Z0-9_]+$")) }.toMutableMap()

                for ((k, v) in updates) {
                    val keyLower = k.lowercase()
                    if (keyLower.contains("designation") || keyLower == "designation_7") {
                        val rawDesig = v.jsonPrimitive.contentOrNull.orEmpty()
                        if (rawDesig.isNotBlank()) {
                            val resolved = resolveDesignationFromDb(conn, rawDesig)
                            updates[k] = JsonPrimitive(resolved)
                        }
                    }
                }

                if (empCode.isNotBlank() && updates.isNotEmpty()) {
                    val setClause = updates.keys.joinToString(", ") { "$it = ?" }
                    val args = updates.values.map { it.jsonPrimitive.content } + empCode
                    executeTursoHttpSql("UPDATE MasterData SET $setClause WHERE LOWER(TRIM(Code_2)) = LOWER(TRIM(?))", args)
                }
            }
            conn.close()

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

private fun wef_or_doj(obj: JsonObject): String {
    fun s(vararg keys: String): String {
        for (k in keys) {
            val v = obj[k]?.jsonPrimitive?.contentOrNull?.trim()
            if (!v.isNullOrBlank()) return v
        }
        return ""
    }
    return s("wef", "doj_in_adhikar", "dojInAdhikar", "doj", "doj_in_branch", "dojInBranch")
}
