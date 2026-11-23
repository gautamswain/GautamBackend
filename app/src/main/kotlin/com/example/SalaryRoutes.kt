package com.example

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL

private const val MAX_BATCH = 20

@Serializable
data class SalaryInfoDto(
    val month: String = "",
    val totday: String = "30",
    val sunday: String = "4",
    val holiday: String = "1",
    val monthc: String = "",
    val networkingdays: Int = 25
)

fun Route.configureSalaryRoutes() {
    get("/api/turso/salaries-batch") {
        val lastIds = call.request.queryParameters["history"].orEmpty()
            .split('~')
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "0" }
            .distinct()
            .takeLast(MAX_BATCH)

        if (lastIds.isEmpty()) {
            call.respond(HttpStatusCode.OK, emptyList<SalaryDto>())
            return@get
        }

        val payload = withContext(Dispatchers.IO) {
            try {
                fetchSalariesDirectHttp(lastIds)
            } catch (e: Exception) {
                application.log.error("salaries-batch failed for ${lastIds.size} ids", e)
                null
            }
        }

        if (payload == null) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "query_failed"))
            return@get
        }
        call.respond(HttpStatusCode.OK, payload)
    }

    // 1. GET /api/turso/salaryinfo
    get("/api/turso/salaryinfo") {
        val conn = TursoDatabaseService.getConnection()
        if (conn == null) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
            return@get
        }

        try {
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS salaryinfo (
                        month TEXT, 
                        totday TEXT, 
                        sunday TEXT, 
                        holiday TEXT, 
                        monthc TEXT
                    );
                    """.trimIndent()
                )
            }

            val list = mutableListOf<SalaryInfoDto>()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT * FROM salaryinfo ORDER BY rowid DESC").use { rs ->
                    val meta = rs.metaData
                    val colMap = mutableMapOf<String, Int>()
                    for (i in 1..meta.columnCount) {
                        colMap[meta.getColumnName(i).trim().lowercase()] = i
                    }

                    fun getVal(rs: java.sql.ResultSet, vararg keys: String): String {
                        for (k in keys) {
                            val idx = colMap[k.lowercase()]
                            if (idx != null) {
                                val v = rs.getString(idx)?.trim().orEmpty()
                                if (v.isNotBlank()) return v
                            }
                        }
                        return ""
                    }

                    while (rs.next()) {
                        val rawMonth = getVal(rs, "month")
                        val formattedMonth = SalarySlipGenerator.formatMonthName(rawMonth)
                        val totday = getVal(rs, "totday", "tot_day").ifBlank { "30" }
                        val sunday = getVal(rs, "sunday").ifBlank { "4" }
                        val holiday = getVal(rs, "holiday").ifBlank { "1" }
                        val monthc = getVal(rs, "monthc", "month_c").ifBlank { formattedMonth }

                        val totInt = totday.toIntOrNull() ?: 30
                        val sunInt = sunday.toIntOrNull() ?: 4
                        val holInt = holiday.toIntOrNull() ?: 1
                        val networkingDays = maxOf(0, totInt - (sunInt + holInt))

                        list.add(
                            SalaryInfoDto(
                                month = formattedMonth,
                                totday = totday,
                                sunday = sunday,
                                holiday = holiday,
                                monthc = monthc,
                                networkingdays = networkingDays
                            )
                        )
                    }
                }
            }
            conn.close()
            call.respond(HttpStatusCode.OK, list)
        } catch (e: Exception) {
            try { conn.close() } catch (_: Exception) {}
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Failed to fetch salary info")))
        }
    }

    // 2. GET /api/turso/salaryinfo/{monthc}
    get("/api/turso/salaryinfo/{monthc}") {
        val targetMonth = call.parameters["monthc"]?.trim().orEmpty()
        if (targetMonth.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing monthc parameter"))
            return@get
        }

        val conn = TursoDatabaseService.getConnection()
        if (conn == null) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
            return@get
        }

        try {
            val sql = "SELECT * FROM salaryinfo WHERE LOWER(TRIM(monthc)) = LOWER(TRIM(?)) OR LOWER(TRIM(month)) = LOWER(TRIM(?)) LIMIT 1"
            var foundInfo: SalaryInfoDto? = null

            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, targetMonth)
                ps.setString(2, targetMonth)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        val meta = rs.metaData
                        val colMap = mutableMapOf<String, Int>()
                        for (i in 1..meta.columnCount) {
                            colMap[meta.getColumnName(i).trim().lowercase()] = i
                        }

                        fun getVal(rs: java.sql.ResultSet, vararg keys: String): String {
                            for (k in keys) {
                                val idx = colMap[k.lowercase()]
                                if (idx != null) {
                                    val v = rs.getString(idx)?.trim().orEmpty()
                                    if (v.isNotBlank()) return v
                                }
                            }
                            return ""
                        }

                        val rawMonth = getVal(rs, "month")
                        val formattedMonth = SalarySlipGenerator.formatMonthName(rawMonth)
                        val totday = getVal(rs, "totday", "tot_day").ifBlank { "30" }
                        val sunday = getVal(rs, "sunday").ifBlank { "4" }
                        val holiday = getVal(rs, "holiday").ifBlank { "1" }
                        val monthc = getVal(rs, "monthc", "month_c").ifBlank { formattedMonth }

                        val totInt = totday.toIntOrNull() ?: 30
                        val sunInt = sunday.toIntOrNull() ?: 4
                        val holInt = holiday.toIntOrNull() ?: 1
                        val networkingDays = maxOf(0, totInt - (sunInt + holInt))

                        foundInfo = SalaryInfoDto(
                            month = formattedMonth,
                            totday = totday,
                            sunday = sunday,
                            holiday = holiday,
                            monthc = monthc,
                            networkingdays = networkingDays
                        )
                    }
                }
            }
            conn.close()

            if (foundInfo != null) {
                call.respond(HttpStatusCode.OK, foundInfo)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Salary info not found for monthc"))
            }
        } catch (e: Exception) {
            try { conn.close() } catch (_: Exception) {}
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Failed to fetch salary info")))
        }
    }

    // 3. POST /api/turso/salaryinfo/save
    post("/api/turso/salaryinfo/save") {
        try {
            val req = call.receive<SalaryInfoDto>()
            val cleanMonth = req.month.trim()
            val cleanMonthc = req.monthc.trim()

            if (cleanMonth.isBlank() || cleanMonthc.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Month and monthc cannot be blank"))
                return@post
            }

            val formattedMonth = SalarySlipGenerator.formatMonthName(cleanMonth)

            TursoDatabaseService.getConnection()?.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS salaryinfo (
                            month TEXT, 
                            totday TEXT, 
                            sunday TEXT, 
                            holiday TEXT, 
                            monthc TEXT
                        );
                        """.trimIndent()
                    )
                }

                val checkSql = "SELECT COUNT(*) FROM salaryinfo WHERE LOWER(TRIM(monthc)) = LOWER(TRIM(?)) OR LOWER(TRIM(month)) = LOWER(TRIM(?))"
                val exists = conn.prepareStatement(checkSql).use { ps ->
                    ps.setString(1, cleanMonthc)
                    ps.setString(2, cleanMonth)
                    ps.executeQuery().use { rs -> rs.next() && rs.getInt(1) > 0 }
                }

                if (exists) {
                    val updateSql = "UPDATE salaryinfo SET month = ?, totday = ?, sunday = ?, holiday = ? WHERE LOWER(TRIM(monthc)) = LOWER(TRIM(?)) OR LOWER(TRIM(month)) = LOWER(TRIM(?))"
                    conn.prepareStatement(updateSql).use { ps ->
                        ps.setString(1, formattedMonth)
                        ps.setString(2, req.totday)
                        ps.setString(3, req.sunday)
                        ps.setString(4, req.holiday)
                        ps.setString(5, cleanMonthc)
                        ps.setString(6, cleanMonth)
                        ps.execute()
                    }
                } else {
                    val insertSql = "INSERT INTO salaryinfo (month, totday, sunday, holiday, monthc) VALUES (?, ?, ?, ?, ?)"
                    conn.prepareStatement(insertSql).use { ps ->
                        ps.setString(1, formattedMonth)
                        ps.setString(2, req.totday)
                        ps.setString(3, req.sunday)
                        ps.setString(4, req.holiday)
                        ps.setString(5, cleanMonthc)
                        ps.execute()
                    }
                }

                ReportMeService.triggerAsync()
            }

            call.respond(HttpStatusCode.OK, mapOf("status" to "success", "message" to "Salary info saved"))
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Failed to save salary info")))
        }
    }

    // =========================================================================
    // 4. GET /api/turso/download-attendance-csv?monthc=M116
    // =========================================================================
    get("/api/turso/download-attendance-csv") {
        val targetMonthc = call.request.queryParameters["monthc"]?.trim().orEmpty()
        if (targetMonthc.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Please provide monthc parameter (e.g. ?monthc=M116)")
            )
            return@get
        }

        val conn = TursoDatabaseService.getConnection()
        if (conn == null) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
            return@get
        }

        try {
            // Step 1: Fetch totday, sunday, holiday from salaryinfo matching monthc (e.g. M116)
            var totday = 0
            var sunday = 0
            var holiday = 0
            var foundSal = false

            val salSql = "SELECT totday, sunday, holiday FROM salaryinfo WHERE LOWER(TRIM(monthc)) = LOWER(TRIM(?)) LIMIT 1"
            conn.prepareStatement(salSql).use { ps ->
                ps.setString(1, targetMonthc)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        foundSal = true
                        totday = rs.getString("totday")?.trim()?.toIntOrNull() ?: 0
                        sunday = rs.getString("sunday")?.trim()?.toIntOrNull() ?: 0
                        holiday = rs.getString("holiday")?.trim()?.toIntOrNull() ?: 0
                    }
                }
            }

            if (!foundSal) {
                conn.close()
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "No salaryinfo record found for monthc = $targetMonthc")
                )
                return@get
            }

            val presentDays = maxOf(0, totday - sunday - holiday)
            val casualLeave = 0
            val sickLeave = 0
            val earnedLeave = 0
            val marriageLeave = 0
            val maternityLeave = 0
            val paternityLeave = 0
            val lossOfPay = 0
            val totalPaidDays = (presentDays + sunday + holiday +
                casualLeave + sickLeave + earnedLeave + marriageLeave + maternityLeave + paternityLeave) - lossOfPay

            // Step 2: Fetch JSON arrays from Reports table (columns 'f' for 1f and 'g' for 1g)
            val staffObjects = mutableListOf<JsonObject>()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT f, g FROM Reports LIMIT 1").use { rs ->
                    if (rs.next()) {
                        listOf("f", "g").forEach { colName ->
                            val rawJson = rs.getString(colName)?.trim().orEmpty()
                            if (rawJson.startsWith("[")) {
                                runCatching {
                                    Json.parseToJsonElement(rawJson).jsonArray.forEach { el ->
                                        if (el is JsonObject) staffObjects.add(el)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            conn.close()

            // Helper to safely extract field across possible JSON key variations
            fun JsonObject.getField(vararg keys: String): String {
                for (k in keys) {
                    val matchKey = this.keys.firstOrNull { it.equals(k, ignoreCase = true) }
                    if (matchKey != null) {
                        val v = this[matchKey]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                        if (v.isNotBlank()) return v
                    }
                }
                return ""
            }

            // Step 3: Build CSV with exact required headers
            val headers = listOf(
                "SL.NO", "Code", "Name", "Designation", "Department", "Branch", "PORTFOLIO", "DOJ",
                "Present Days", "Weekly Offs", "Declared Holidays",
                "Casual Leave", "Sick Leave", "Earned Leave", "Marriage Leave", "Maternity Leave", "Paternity Leave",
                "Loss of Pay", "Total Paid Days", "Status", "REMARKS", "Prepare By"
            )

            val csvBuilder = StringBuilder()
            csvBuilder.appendLine(headers.joinToString(",") { escapeCsvVal(it) })

            staffObjects.forEachIndexed { idx, obj ->
                val slNo = (idx + 1).toString()
                val code = obj.getField("Code", "empCode", "emp_id_2", "a1")
                val name = obj.getField("Name", "empName", "employee_name_3", "a4")
                val designation = obj.getField("Designation", "designation_7", "a8")
                val department = obj.getField("Department", "department_8", "a9")
                val branch = obj.getField("Branch", "branchName", "branch_name_9", "a10")
                val portfolio = obj.getField("PORTFOLIO", "Portfolio", "portfolio_7", "a11")
                val doj = obj.getField("DOJ", "dojInAdhikar", "a6")

                val row = listOf(
                    slNo,
                    code,
                    name,
                    designation,
                    department,
                    branch,
                    portfolio,
                    doj,
                    presentDays.toString(),
                    sunday.toString(),
                    holiday.toString(),
                    casualLeave.toString(),
                    sickLeave.toString(),
                    earnedLeave.toString(),
                    marriageLeave.toString(),
                    maternityLeave.toString(),
                    paternityLeave.toString(),
                    lossOfPay.toString(),
                    totalPaidDays.toString(),
                    "", // Status
                    "", // REMARKS
                    ""  // Prepare By
                )
                csvBuilder.appendLine(row.joinToString(",") { escapeCsvVal(it) })
            }

            val fileName = "Attendance_${targetMonthc.uppercase()}.csv"
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName,
                    fileName
                ).toString()
            )
            call.respondText(csvBuilder.toString(), ContentType.Text.CSV)
        } catch (e: Exception) {
            try { conn.close() } catch (_: Exception) {}
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (e.localizedMessage ?: "Failed to generate attendance CSV"))
            )
        }
    }

    // =========================================================================
    // 5. GET /api/turso/checknewstaffaccount (1e Staff Bank Account Check CSV)
    // =========================================================================
    get("/api/turso/checknewstaffaccount") {
        val conn = TursoDatabaseService.getConnection()
        if (conn == null) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
            return@get
        }

        try {
            // Step 1: Read 1e staff list from Reports column 'e'
            val codesFrom1e = mutableListOf<String>()
            val fallbackObjByCode = mutableMapOf<String, JsonObject>()

            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT e FROM Reports LIMIT 1").use { rs ->
                    if (rs.next()) {
                        val rawE = rs.getString("e")?.trim().orEmpty()
                        if (rawE.startsWith("[")) {
                            runCatching {
                                Json.parseToJsonElement(rawE).jsonArray.forEach { el ->
                                    if (el is JsonObject) {
                                        val codeKey = el.keys.firstOrNull {
                                            it.equals("Code", true) || it.equals("empCode", true) || it.equals("a1", true)
                                        }
                                        val codeVal = codeKey?.let { el[it]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase() }.orEmpty()
                                        if (codeVal.isNotBlank()) {
                                            codesFrom1e.add(codeVal)
                                            fallbackObjByCode[codeVal] = el
                                        }
                                    } else if (el is JsonPrimitive) {
                                        val codeVal = el.content.trim().uppercase()
                                        if (codeVal.isNotBlank()) codesFrom1e.add(codeVal)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Step 2: Fetch Bank, Account No & IFSC from MasterData for all 1e codes
            data class AccountRow(
                val code: String,
                val name: String,
                val designation: String,
                val branch: String,
                val bank: String,
                val accountNo: String,
                val ifscCode: String
            )

            val masterMap = mutableMapOf<String, AccountRow>()
            if (codesFrom1e.isNotEmpty()) {
                val placeholders = codesFrom1e.joinToString(",") { "?" }
                val sql = "SELECT * FROM MasterData WHERE UPPER(TRIM(a1)) IN ($placeholders)"
                conn.prepareStatement(sql).use { ps ->
                    codesFrom1e.forEachIndexed { idx, code -> ps.setString(idx + 1, code) }
                    ps.executeQuery().use { rs ->
                        val meta = rs.metaData
                        val colMap = (1..meta.columnCount).associate {
                            meta.getColumnName(it).trim().lowercase() to it
                        }
                        fun col(vararg keys: String): String {
                            for (k in keys) {
                                val i = colMap[k.lowercase()]
                                if (i != null) {
                                    val v = rs.getString(i)?.trim().orEmpty()
                                    if (v.isNotBlank()) return v
                                }
                            }
                            return ""
                        }

                        while (rs.next()) {
                            val code = col("a1", "code", "empCode").uppercase()
                            if (code.isNotBlank()) {
                                masterMap[code] = AccountRow(
                                    code = code,
                                    name = col("a4", "name", "empName"),
                                    designation = col("a8", "designation"),
                                    branch = col("a10", "branch", "branchName"),
                                    bank = col("a20", "bankName", "bank"),
                                    accountNo = col("a21", "bankAccountNumber", "accountNo"),
                                    ifscCode = col("a22", "ifscCodeBankBranchName", "ifscCode", "ifsc")
                                )
                            }
                        }
                    }
                }
            }
            conn.close()

            // Step 3: Build CSV: slno,code,name,designation,branch,bank,accountno,ifsccode
            val csvBuilder = StringBuilder()
            csvBuilder.appendLine("slno,code,name,designation,branch,bank,accountno,ifsccode")

            codesFrom1e.distinct().forEachIndexed { idx, code ->
                val slno = (idx + 1).toString()
                val m = masterMap[code]
                val fb = fallbackObjByCode[code]

                fun fbVal(vararg keys: String): String {
                    if (fb == null) return ""
                    for (k in keys) {
                        val mk = fb.keys.firstOrNull { it.equals(k, true) }
                        if (mk != null) {
                            val v = fb[mk]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                            if (v.isNotBlank()) return v
                        }
                    }
                    return ""
                }

                val name = m?.name?.ifBlank { fbVal("Name", "empName", "a4") } ?: fbVal("Name", "empName", "a4")
                val designation = m?.designation?.ifBlank { fbVal("Designation", "a8") } ?: fbVal("Designation", "a8")
                val branch = m?.branch?.ifBlank { fbVal("Branch", "branchName", "a10") } ?: fbVal("Branch", "branchName", "a10")
                val bank = m?.bank?.ifBlank { fbVal("Bank", "bankName", "a20") } ?: fbVal("Bank", "bankName", "a20")
                val accountNo = m?.accountNo?.ifBlank { fbVal("AccountNo", "bankAccountNumber", "a21") } ?: fbVal("AccountNo", "bankAccountNumber", "a21")
                val ifscCode = m?.ifscCode?.ifBlank { fbVal("IFSC", "ifscCode", "a22") } ?: fbVal("IFSC", "ifscCode", "a22")

                val row = listOf(slno, code, name, designation, branch, bank, accountNo, ifscCode)
                csvBuilder.appendLine(row.joinToString(",") { escapeCsvVal(it) })
            }

            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName,
                    "NewStaffAccountCheck_1e.csv"
                ).toString()
            )
            call.respondText(csvBuilder.toString(), ContentType.Text.CSV)
        } catch (e: Exception) {
            try { conn.close() } catch (_: Exception) {}
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (e.localizedMessage ?: "Failed to generate account check CSV"))
            )
        }
    }
}

private fun fetchSalariesDirectHttp(ids: List<String>): List<SalaryDto> {
    if (ids.isEmpty()) return emptyList()

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

    val tursoUrl = "https://$host/v2/pipeline"

    val placeholders = ids.joinToString(",") { "?" }
    val sql = "SELECT * FROM SalaryHistory WHERE id IN ($placeholders)"

    val argsJson = buildJsonArray {
        ids.forEach { id ->
            addJsonObject {
                put("type", "text")
                put("value", id.trim())
            }
        }
    }

    val requestBody = buildJsonObject {
        putJsonArray("requests") {
            addJsonObject {
                put("type", "execute")
                putJsonObject("stmt") {
                    put("sql", sql)
                    put("args", argsJson)
                }
            }
            addJsonObject {
                put("type", "close")
            }
        }
    }.toString()

    val url = URL(tursoUrl)
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 15000
        readTimeout = 15000
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Authorization", "Bearer $authToken")
        doOutput = true
        outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
    }

    val responseText = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    val root = Json.parseToJsonElement(responseText).jsonObject
    val resultObj = root["results"]?.jsonArray?.get(0)?.jsonObject
        ?.get("response")?.jsonObject
        ?.get("result")?.jsonObject ?: return emptyList()

    val cols = resultObj["cols"]?.jsonArray?.map { it.jsonObject["name"]?.jsonPrimitive?.content.orEmpty() } ?: emptyList()
    val rows = resultObj["rows"]?.jsonArray ?: return emptyList()

    val dtoList = mutableListOf<SalaryDto>()

    for (rowElement in rows) {
        val rowArr = rowElement.jsonArray
        val map = mutableMapOf<String, String>()
        for (i in cols.indices) {
            val cellObj = rowArr[i].jsonObject
            val value = cellObj["value"]?.jsonPrimitive?.contentOrNull ?: ""
            map[cols[i].lowercase()] = value
        }

        dtoList.add(
            SalaryDto(
                id = map["id"] ?: map["salaryhistory-slno"] ?: "",
                month = map["month"] ?: "",
                slNo1 = map["sl_no_1"] ?: "",
                empId2 = map["emp_id_2"] ?: "",
                employeeName3 = map["employee_name_3"] ?: "",
                designation7 = map["designation_7"] ?: "",
                department8 = map["department_8"] ?: "",
                branchName9 = map["branch_name_9"] ?: "",
                portfolio7 = map["portfolio_7"] ?: "",
                paidDays8 = map["paid_days_8"] ?: "",
                basicDa9 = map["basic_da_9"] ?: "",
                hra10 = map["hra_10"] ?: "",
                conveyance11 = map["conveyance_11"] ?: "",
                medicalAllowance12 = map["medical_allowance_12"] ?: "",
                educationAllowance13 = map["education_allowance_13"] ?: "",
                lunchCanteenAllowance14 = map["lunch_canteen_allownance_14"] ?: "",
                specialAllowance15 = map["special_allowance_15"] ?: "",
                hraVariable39 = map["hravariable_39"] ?: "",
                otherAllowance17 = map["other_allowance_17"] ?: "",
                daAosa40 = map["daaosa_40"] ?: "",
                arrearsIncrement41 = map["arrears_increment_41"] ?: "",
                epf5549 = map["epf55_49"] ?: "",
                esi5650 = map["esi56_50"] ?: "",
                professionalTax22 = map["professional_tax_22"] ?: "",
                tds42 = map["tds_42"] ?: "",
                staffLoan43 = map["staff_loan_43"] ?: "",
                staffAdvance44 = map["staff_advance_44"] ?: "",
                others45 = map["others_45"] ?: "",
                otherEarnings46 = map["other_earnings_46"] ?: "",
                grossEarnings28 = map["gross_earnings_28"] ?: "",
                grossDeductions29 = map["gross_deductions_29"] ?: "",
                netAmount30 = map["net_amount_30"] ?: "",
                status00 = map["status_00"] ?: "",
                bankAccountNumber20 = map["bank_account_number_20"] ?: "",
                ifscCodeBankBranchName21 = map["ifsc_code_bank_branch_name_21"] ?: "",
                bankName19 = map["bank_name_19"] ?: "",
                backSalaryGive = map["backsalarygive"] ?: ""
            )
        )
    }

    val byId = dtoList.associateBy { it.id }
    return ids.mapNotNull { byId[it] }
}

private fun escapeCsvVal(value: String): String {
    val clean = value.trim()
    return if (clean.contains(",") || clean.contains("\"") || clean.contains("\n") || clean.contains("\r")) {
        "\"${clean.replace("\"", "\"\"")}\""
    } else {
        clean
    }
}