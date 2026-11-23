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

fun Route.configureSalaryRoutes() { // { #1 (L1) - fun Route.configureSalaryRoutes
    get("/api/turso/salaries-batch") { // { #2 (L2) - get /api/turso/salaries-batch
        val lastIds = call.request.queryParameters["history"].orEmpty()
            .split('~')
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "0" }
            .distinct()
            .takeLast(MAX_BATCH)

        if (lastIds.isEmpty()) { // { #3 (L3) - if lastIds.isEmpty
            call.respond(HttpStatusCode.OK, emptyList<SalaryDto>())
            return@get
        } // } #3 (L3) - if lastIds.isEmpty

        val payload = withContext(Dispatchers.IO) { // { #4 (L3) - withContext Dispatchers.IO
            try { // { #5 (L4) - try
                fetchSalariesDirectHttp(lastIds)
            } catch (e: Exception) { // } #5 (L4) - try / { #6 (L4) - catch Exception
                application.log.error("salaries-batch failed for ${lastIds.size} ids", e)
                null
            } // } #6 (L4) - catch Exception
        } // } #4 (L3) - withContext Dispatchers.IO

        if (payload == null) { // { #7 (L3) - if payload == null
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "query_failed"))
            return@get
        } // } #7 (L3) - if payload == null
        call.respond(HttpStatusCode.OK, payload)
    } // } #2 (L2) - get /api/turso/salaries-batch

    // 1. GET /api/turso/salaryinfo
    get("/api/turso/salaryinfo") { // { #8 (L2) - get /api/turso/salaryinfo
        val conn = TursoDatabaseService.getConnection()
        if (conn == null) { // { #9 (L3) - if conn == null
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
            return@get
        } // } #9 (L3) - if conn == null

        try { // { #10 (L3) - try
            conn.createStatement().use { stmt -> // { #11 (L4) - stmt.use
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
            } // } #11 (L4) - stmt.use

            val list = mutableListOf<SalaryInfoDto>()
            conn.createStatement().use { stmt -> // { #12 (L4) - stmt.use
                stmt.executeQuery("SELECT * FROM salaryinfo ORDER BY rowid DESC").use { rs -> // { #13 (L5) - rs.use
                    val meta = rs.metaData
                    val colMap = mutableMapOf<String, Int>()
                    for (i in 1..meta.columnCount) { // { #14 (L6) - for columnCount
                        colMap[meta.getColumnName(i).trim().lowercase()] = i
                    } // } #14 (L6) - for columnCount

                    fun getVal(rs: java.sql.ResultSet, vararg keys: String): String { // { #15 (L6) - fun getVal
                        for (k in keys) { // { #16 (L7) - for keys
                            val idx = colMap[k.lowercase()]
                            if (idx != null) { // { #17 (L8) - if idx != null
                                val v = rs.getString(idx)?.trim().orEmpty()
                                if (v.isNotBlank()) return v
                            } // } #17 (L8) - if idx != null
                        } // } #16 (L7) - for keys
                        return ""
                    } // } #15 (L6) - fun getVal

                    while (rs.next()) { // { #18 (L6) - while rs.next
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
                    } // } #18 (L6) - while rs.next
                } // } #13 (L5) - rs.use
            } // } #12 (L4) - stmt.use
            conn.close()
            call.respond(HttpStatusCode.OK, list)
        } catch (e: Exception) { // } #10 (L3) - try / { #19 (L3) - catch Exception
            try { conn.close() } catch (_: Exception) {}
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Failed to fetch salary info")))
        } // } #19 (L3) - catch Exception
    } // } #8 (L2) - get /api/turso/salaryinfo

    // 2. GET /api/turso/salaryinfo/{monthc}
    get("/api/turso/salaryinfo/{monthc}") { // { #20 (L2) - get /api/turso/salaryinfo/{monthc}
        val targetMonth = call.parameters["monthc"]?.trim().orEmpty()
        if (targetMonth.isBlank()) { // { #21 (L3) - if targetMonth.isBlank
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing monthc parameter"))
            return@get
        } // } #21 (L3) - if targetMonth.isBlank

        val conn = TursoDatabaseService.getConnection()
        if (conn == null) { // { #22 (L3) - if conn == null
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
            return@get
        } // } #22 (L3) - if conn == null

        try { // { #23 (L3) - try
            val sql = "SELECT * FROM salaryinfo WHERE LOWER(TRIM(monthc)) = LOWER(TRIM(?)) OR LOWER(TRIM(month)) = LOWER(TRIM(?)) LIMIT 1"
            var foundInfo: SalaryInfoDto? = null

            conn.prepareStatement(sql).use { ps -> // { #24 (L4) - ps.use
                ps.setString(1, targetMonth)
                ps.setString(2, targetMonth)
                ps.executeQuery().use { rs -> // { #25 (L5) - rs.use
                    if (rs.next()) { // { #26 (L6) - if rs.next
                        val meta = rs.metaData
                        val colMap = mutableMapOf<String, Int>()
                        for (i in 1..meta.columnCount) { // { #27 (L7) - for columnCount
                            colMap[meta.getColumnName(i).trim().lowercase()] = i
                        } // } #27 (L7) - for columnCount

                        fun getVal(rs: java.sql.ResultSet, vararg keys: String): String { // { #28 (L7) - fun getVal
                            for (k in keys) { // { #29 (L8) - for keys
                                val idx = colMap[k.lowercase()]
                                if (idx != null) { // { #30 (L9) - if idx != null
                                    val v = rs.getString(idx)?.trim().orEmpty()
                                    if (v.isNotBlank()) return v
                                } // } #30 (L9) - if idx != null
                            } // } #29 (L8) - for keys
                            return ""
                        } // } #28 (L7) - fun getVal

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
                    } // } #26 (L6) - if rs.next
                } // } #25 (L5) - rs.use
            } // } #24 (L4) - ps.use
            conn.close()

            if (foundInfo != null) { // { #31 (L3) - if foundInfo != null
                call.respond(HttpStatusCode.OK, foundInfo)
            } else { // } #31 (L3) - if foundInfo != null / { #32 (L3) - else
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Salary info not found for monthc"))
            } // } #32 (L3) - else
        } catch (e: Exception) { // } #23 (L3) - try / { #33 (L3) - catch Exception
            try { conn.close() } catch (_: Exception) {}
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Failed to fetch salary info")))
        } // } #33 (L3) - catch Exception
    } // } #20 (L2) - get /api/turso/salaryinfo/{monthc}

    // 3. POST /api/turso/salaryinfo/save
    post("/api/turso/salaryinfo/save") { // { #34 (L2) - post /api/turso/salaryinfo/save
        try { // { #35 (L3) - try
            val req = call.receive<SalaryInfoDto>()
            val cleanMonth = req.month.trim()
            val cleanMonthc = req.monthc.trim()

            if (cleanMonth.isBlank() || cleanMonthc.isBlank()) { // { #36 (L4) - if cleanMonth.isBlank
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Month and monthc cannot be blank"))
                return@post
            } // } #36 (L4) - if cleanMonth.isBlank

            val formattedMonth = SalarySlipGenerator.formatMonthName(cleanMonth)

            TursoDatabaseService.getConnection()?.use { conn -> // { #37 (L4) - conn.use
                conn.createStatement().use { stmt -> // { #38 (L5) - stmt.use
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
                } // } #38 (L5) - stmt.use

                val checkSql = "SELECT COUNT(*) FROM salaryinfo WHERE LOWER(TRIM(monthc)) = LOWER(TRIM(?)) OR LOWER(TRIM(month)) = LOWER(TRIM(?))"
                val exists = conn.prepareStatement(checkSql).use { ps -> // { #39 (L5) - ps.use
                    ps.setString(1, cleanMonthc)
                    ps.setString(2, cleanMonth)
                    ps.executeQuery().use { rs -> rs.next() && rs.getInt(1) > 0 }
                } // } #39 (L5) - ps.use

                if (exists) { // { #40 (L5) - if exists
                    val updateSql = "UPDATE salaryinfo SET month = ?, totday = ?, sunday = ?, holiday = ? WHERE LOWER(TRIM(monthc)) = LOWER(TRIM(?)) OR LOWER(TRIM(month)) = LOWER(TRIM(?))"
                    conn.prepareStatement(updateSql).use { ps -> // { #41 (L6) - ps.use
                        ps.setString(1, formattedMonth)
                        ps.setString(2, req.totday)
                        ps.setString(3, req.sunday)
                        ps.setString(4, req.holiday)
                        ps.setString(5, cleanMonthc)
                        ps.setString(6, cleanMonth)
                        ps.execute()
                    } // } #41 (L6) - ps.use
                } else { // } #40 (L5) - if exists / { #42 (L5) - else
                    val insertSql = "INSERT INTO salaryinfo (month, totday, sunday, holiday, monthc) VALUES (?, ?, ?, ?, ?)"
                    conn.prepareStatement(insertSql).use { ps -> // { #43 (L6) - ps.use
                        ps.setString(1, formattedMonth)
                        ps.setString(2, req.totday)
                        ps.setString(3, req.sunday)
                        ps.setString(4, req.holiday)
                        ps.setString(5, cleanMonthc)
                        ps.execute()
                    } // } #43 (L6) - ps.use
                } // } #42 (L5) - else

                ReportMeService.triggerAsync()
            } // } #37 (L4) - conn.use

            call.respond(HttpStatusCode.OK, mapOf("status" to "success", "message" to "Salary info saved"))
        } catch (e: Exception) { // } #35 (L3) - try / { #44 (L3) - catch Exception
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Failed to save salary info")))
        } // } #44 (L3) - catch Exception
    } // } #34 (L2) - post /api/turso/salaryinfo/save

    // =========================================================================
    // 4. GET /api/turso/download-attendance-csv?monthc=M116
    // =========================================================================
    get("/api/turso/download-attendance-csv") { // { #45 (L2) - get /api/turso/download-attendance-csv
        val targetMonthc = call.request.queryParameters["monthc"]?.trim().orEmpty()
        if (targetMonthc.isBlank()) { // { #46 (L3) - if targetMonthc.isBlank
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Please provide monthc parameter (e.g. ?monthc=M116)")
            )
            return@get
        } // } #46 (L3) - if targetMonthc.isBlank

        val conn = TursoDatabaseService.getConnection()
        if (conn == null) { // { #47 (L3) - if conn == null
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
            return@get
        } // } #47 (L3) - if conn == null

        try { // { #48 (L3) - try
            // Step 1: Fetch totday, sunday, holiday from salaryinfo matching monthc (e.g. M116)
            var totday = 0
            var sunday = 0
            var holiday = 0
            var foundSal = false

            val salSql = "SELECT totday, sunday, holiday FROM salaryinfo WHERE LOWER(TRIM(monthc)) = LOWER(TRIM(?)) LIMIT 1"
            conn.prepareStatement(salSql).use { ps -> // { #49 (L4) - ps.use
                ps.setString(1, targetMonthc)
                ps.executeQuery().use { rs -> // { #50 (L5) - rs.use
                    if (rs.next()) { // { #51 (L6) - if rs.next
                        foundSal = true
                        totday = rs.getString("totday")?.trim()?.toIntOrNull() ?: 0
                        sunday = rs.getString("sunday")?.trim()?.toIntOrNull() ?: 0
                        holiday = rs.getString("holiday")?.trim()?.toIntOrNull() ?: 0
                    } // } #51 (L6) - if rs.next
                } // } #50 (L5) - rs.use
            } // } #49 (L4) - ps.use

            if (!foundSal) { // { #52 (L3) - if !foundSal
                conn.close()
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "No salaryinfo record found for monthc = $targetMonthc")
                )
                return@get
            } // } #52 (L3) - if !foundSal

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

            // Step 2: Fetch 1f (Branch) and 1g (Head Office) from Reports (fields 'f' and 'g')
            data class StaffEntry(val unitType: String, val groupName: String, val obj: JsonObject)
            val staffEntries = mutableListOf<StaffEntry>()

            fun extractStaffFromJsonElement(el: JsonElement, unitType: String, inheritedGroup: String = "") { // { #53 (L3) - fun extractStaffFromJsonElement
                when (el) { // { #54 (L4) - when el
                    is JsonPrimitive -> { // { #55 (L5) - is JsonPrimitive
                        val inner = el.content.trim()
                        if (inner.startsWith("[") || inner.startsWith("{")) { // { #56 (L6) - if inner.startsWith
                            runCatching { extractStaffFromJsonElement(Json.parseToJsonElement(inner), unitType, inheritedGroup) }
                        } // } #56 (L6) - if inner.startsWith
                    } // } #55 (L5) - is JsonPrimitive
                    is JsonArray -> { // { #57 (L5) - is JsonArray
                        el.forEach { child -> extractStaffFromJsonElement(child, unitType, inheritedGroup) }
                    } // } #57 (L5) - is JsonArray
                    is JsonObject -> { // { #58 (L5) - is JsonObject
                        val groupLabel = listOf("branchName", "branch_name", "departmentName", "department")
                            .firstNotNullOfOrNull { k -> el[k]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() } }
                            ?: inheritedGroup

                        val nestedList = el["staffList"] ?: el["staff"] ?: el["employees"] ?: el["records"]
                        if (nestedList is JsonArray) { // { #59 (L6) - if nestedList is JsonArray
                            nestedList.forEach { emp -> // { #60 (L7) - forEach emp
                                if (emp is JsonObject) { // { #61 (L8) - if emp is JsonObject
                                    staffEntries.add(StaffEntry(unitType, groupLabel, emp))
                                } // } #61 (L8) - if emp is JsonObject
                            } // } #60 (L7) - forEach emp
                        } else { // } #59 (L6) - if nestedList is JsonArray / { #62 (L6) - else
                            staffEntries.add(StaffEntry(unitType, groupLabel, el))
                        } // } #62 (L6) - else
                    } // } #58 (L5) - is JsonObject
                } // } #54 (L4) - when el
            } // } #53 (L3) - fun extractStaffFromJsonElement

            // Detect whether Reports uses 'cr' or 'id'
            val reportCols = mutableSetOf<String>()
            conn.createStatement().use { stmt -> // { #63 (L4) - stmt.use
                stmt.executeQuery("PRAGMA table_info(Reports)").use { rs -> // { #64 (L5) - rs.use
                    while (rs.next()) reportCols.add(rs.getString("name").trim().lowercase())
                } // } #64 (L5) - rs.use
            } // } #63 (L4) - stmt.use
            val keyCol = if ("cr" in reportCols) "cr" else "id"

            conn.createStatement().use { stmt -> // { #65 (L4) - stmt.use
                stmt.executeQuery("SELECT f, g FROM Reports WHERE TRIM($keyCol) = '1'").use { rs -> // { #66 (L5) - rs.use
                    while (rs.next()) { // { #67 (L6) - while rs.next
                        val rawF = rs.getString("f")?.trim().orEmpty()
                        if (rawF.isNotBlank()) { // { #68 (L7) - if rawF.isNotBlank
                            runCatching { extractStaffFromJsonElement(Json.parseToJsonElement(rawF), "Branch") }
                        } // } #68 (L7) - if rawF.isNotBlank

                        val rawG = rs.getString("g")?.trim().orEmpty()
                        if (rawG.isNotBlank()) { // { #69 (L7) - if rawG.isNotBlank
                            runCatching { extractStaffFromJsonElement(Json.parseToJsonElement(rawG), "Head Office") }
                        } // } #69 (L7) - if rawG.isNotBlank
                    } // } #67 (L6) - while rs.next
                } // } #66 (L5) - rs.use
            } // } #65 (L4) - stmt.use
            conn.close()

            fun JsonObject.getField(vararg keys: String): String { // { #70 (L3) - fun getField
                for (k in keys) { // { #71 (L4) - for keys
                    val matchKey = this.keys.firstOrNull { it.equals(k, ignoreCase = true) }
                    if (matchKey != null) { // { #72 (L5) - if matchKey != null
                        val v = this[matchKey]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                        if (v.isNotBlank()) return v
                    } // } #72 (L5) - if matchKey != null
                } // } #71 (L4) - for keys
                return ""
            } // } #70 (L3) - fun getField

            // Step 3: Build CSV with exact required headers and a1..a67 mappings
            val headers = listOf(
                "SL.NO", "Code", "Name", "Designation", "Department", "Branch", "PORTFOLIO", "DOJ",
                "Present Days", "Weekly Offs", "Declared Holidays",
                "Casual Leave", "Sick Leave", "Earned Leave", "Marriage Leave", "Maternity Leave", "Paternity Leave",
                "Loss of Pay", "Total Paid Days", "Status", "REMARKS", "Prepare By"
            )

            val csvBuilder = StringBuilder()
            csvBuilder.appendLine(headers.joinToString(",") { escapeCsvVal(it) })

            staffEntries.forEachIndexed { idx, entry -> // { #73 (L3) - forEachIndexed
                val obj = entry.obj
                val slNo = (idx + 1).toString()
                val code = obj.getField("a3", "Code", "empCode", "Code_2")
                val name = obj.getField("a4", "Name", "empName", "employee_name_3")
                val designation = obj.getField("a8", "Designation", "designation_7")
                val department = obj.getField("a9", "Department", "department_8")
                    .ifBlank { if (entry.unitType == "Head Office") entry.groupName else "Operation" }
                val branch = obj.getField("a10", "Branch", "branchName", "branch_name_9")
                    .ifBlank { entry.groupName.ifBlank { entry.unitType } }
                val portfolio = obj.getField("a52", "PORTFOLIO", "Portfolio", "portfolio_7")
                val doj = obj.getField("a11", "DOJ", "dojInAdhikar", "doj_in_adhikar_10")

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
            } // } #73 (L3) - forEachIndexed

            val fileName = "Attendance_${targetMonthc.uppercase()}.csv"
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName,
                    fileName
                ).toString()
            )
            call.respondText(csvBuilder.toString(), ContentType.Text.CSV)
        } catch (e: Exception) { // } #48 (L3) - try / { #74 (L3) - catch Exception
            try { conn.close() } catch (_: Exception) {}
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (e.localizedMessage ?: "Failed to generate attendance CSV"))
            )
        } // } #74 (L3) - catch Exception
    } // } #45 (L2) - get /api/turso/download-attendance-csv

    // =========================================================================
    // 5. GET /api/turso/checknewstaffaccount (1e Staff Bank Account Check CSV)
    // =========================================================================
    get("/api/turso/checknewstaffaccount") { // { #75 (L2) - get /api/turso/checknewstaffaccount
        val conn = TursoDatabaseService.getConnection()
        if (conn == null) { // { #76 (L3) - if conn == null
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
            return@get
        } // } #76 (L3) - if conn == null

        try { // { #77 (L3) - try
            // Step 1: Read 1e staff list from Reports (cr = '1', field 'e')
            val codesFrom1e = mutableListOf<String>()
            val fallbackObjByCode = mutableMapOf<String, JsonObject>()

            conn.createStatement().use { stmt -> // { #78 (L4) - stmt.use
                stmt.executeQuery("SELECT cr, e FROM Reports WHERE TRIM(cr) = '1'").use { rs -> // { #79 (L5) - rs.use
                    while (rs.next()) { // { #80 (L6) - while rs.next
                        val rawE = rs.getString("e")?.trim().orEmpty()
                        if (rawE.startsWith("[")) { // { #81 (L7) - if rawE.startsWith
                            runCatching { // { #82 (L8) - runCatching
                                Json.parseToJsonElement(rawE).jsonArray.forEach { el -> // { #83 (L9) - forEach
                                    if (el is JsonObject) { // { #84 (L10) - if el is JsonObject
                                        val codeKey = el.keys.firstOrNull {
                                            it.equals("Code", true) || it.equals("empCode", true) || it.equals("a1", true)
                                        }
                                        val codeVal = codeKey?.let { el[it]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase() }.orEmpty()
                                        if (codeVal.isNotBlank()) { // { #85 (L11) - if codeVal.isNotBlank
                                            codesFrom1e.add(codeVal)
                                            fallbackObjByCode[codeVal] = el
                                        } // } #85 (L11) - if codeVal.isNotBlank
                                    } else if (el is JsonPrimitive) { // } #84 (L10) - if el is JsonObject / { #86 (L10) - else if
                                        val codeVal = el.content.trim().uppercase()
                                        if (codeVal.isNotBlank()) codesFrom1e.add(codeVal)
                                    } // } #86 (L10) - else if
                                } // } #83 (L9) - forEach
                            } // } #82 (L8) - runCatching
                        } else if (rawE.startsWith("{")) { // } #81 (L7) - if rawE.startsWith / { #87 (L7) - else if
                            runCatching { // { #88 (L8) - runCatching
                                val el = Json.parseToJsonElement(rawE).jsonObject
                                val codeKey = el.keys.firstOrNull {
                                    it.equals("Code", true) || it.equals("empCode", true) || it.equals("a1", true)
                                }
                                val codeVal = codeKey?.let { el[it]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase() }.orEmpty()
                                if (codeVal.isNotBlank()) { // { #89 (L9) - if codeVal.isNotBlank
                                    codesFrom1e.add(codeVal)
                                    fallbackObjByCode[codeVal] = el
                                } // } #89 (L9) - if codeVal.isNotBlank
                            } // } #88 (L8) - runCatching
                        } else if (rawE.isNotBlank()) { // } #87 (L7) - else if / { #90 (L7) - else if
                            // Supports comma/newline/tilde-separated codes in Reports.e as well
                            rawE.split(',', '\n', '~', ';').forEach { token ->
                                val codeVal = token.trim().uppercase()
                                if (codeVal.isNotBlank()) codesFrom1e.add(codeVal)
                            }
                        } // } #90 (L7) - else if
                    } // } #80 (L6) - while rs.next
                } // } #79 (L5) - rs.use
            } // } #78 (L4) - stmt.use

            // Step 2: Fetch Bank, Account No & IFSC from MasterData (auto-detecting code column)
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
            val distinctCodes = codesFrom1e.distinct()

            if (distinctCodes.isNotEmpty()) { // { #91 (L3) - if distinctCodes.isNotEmpty
                val availableCols = mutableListOf<String>()
                conn.createStatement().use { stmt -> // { #92 (L4) - stmt.use
                    stmt.executeQuery("PRAGMA table_info(MasterData)").use { rs -> // { #93 (L5) - rs.use
                        while (rs.next()) { // { #94 (L6) - while rs.next
                            availableCols.add(rs.getString("name").trim())
                        } // } #94 (L6) - while rs.next
                    } // } #93 (L5) - rs.use
                } // } #92 (L4) - stmt.use

                val codeColName = availableCols.firstOrNull { col ->
                    col.equals("a1", true) ||
                    col.equals("code", true) ||
                    col.equals("empcode", true) ||
                    col.equals("emp_code", true) ||
                    col.equals("empid", true) ||
                    col.equals("emp_id", true) ||
                    col.equals("id", true)
                }

                if (codeColName != null) { // { #95 (L4) - if codeColName != null
                    val placeholders = distinctCodes.joinToString(",") { "?" }
                    val sql = """SELECT * FROM MasterData WHERE UPPER(TRIM("$codeColName")) IN ($placeholders)"""
                    conn.prepareStatement(sql).use { ps -> // { #96 (L5) - ps.use
                        distinctCodes.forEachIndexed { idx, code -> ps.setString(idx + 1, code) }
                        ps.executeQuery().use { rs -> // { #97 (L6) - rs.use
                            val meta = rs.metaData
                            val colMap = (1..meta.columnCount).associate {
                                meta.getColumnName(it).trim().lowercase() to it
                            }
                            fun col(vararg keys: String): String { // { #98 (L7) - fun col
                                for (k in keys) { // { #99 (L8) - for keys
                                    val i = colMap[k.lowercase()]
                                    if (i != null) { // { #100 (L9) - if i != null
                                        val v = rs.getString(i)?.trim().orEmpty()
                                        if (v.isNotBlank()) return v
                                    } // } #100 (L9) - if i != null
                                } // } #99 (L8) - for keys
                                return ""
                            } // } #98 (L7) - fun col

                            while (rs.next()) { // { #101 (L7) - while rs.next
                                val code = col(codeColName, "a1", "code", "empcode", "emp_code", "empid", "emp_id").uppercase()
                                if (code.isNotBlank()) { // { #102 (L8) - if code.isNotBlank
                                    masterMap[code] = AccountRow(
                                        code = code,
                                        name = col("a4", "name", "empname", "emp_name", "employee_name"),
                                        designation = col("a8", "designation", "desig"),
                                        branch = col("a10", "branch", "branchname", "branch_name"),
                                        bank = col("a20", "bankname", "bank_name", "bank"),
                                        accountNo = col("a21", "bankaccountnumber", "bank_account_number", "accountno", "account_no", "acno"),
                                        ifscCode = col("a22", "ifsccodebankbranchname", "ifsccode", "ifsc_code", "ifsc")
                                    )
                                } // } #102 (L8) - if code.isNotBlank
                            } // } #101 (L7) - while rs.next
                        } // } #97 (L6) - rs.use
                    } // } #96 (L5) - ps.use
                } // } #95 (L4) - if codeColName != null
            } // } #91 (L3) - if distinctCodes.isNotEmpty
            conn.close()

            // Step 3: Build CSV: slno,code,name,designation,branch,bank,accountno,ifsccode
            val csvBuilder = StringBuilder()
            csvBuilder.appendLine("slno,code,name,designation,branch,bank,accountno,ifsccode")

            distinctCodes.forEachIndexed { idx, code -> // { #103 (L3) - forEachIndexed
                val slno = (idx + 1).toString()
                val m = masterMap[code]
                val fb = fallbackObjByCode[code]

                fun fbVal(vararg keys: String): String { // { #104 (L4) - fun fbVal
                    if (fb == null) return ""
                    for (k in keys) { // { #105 (L5) - for keys
                        val mk = fb.keys.firstOrNull { it.equals(k, true) }
                        if (mk != null) { // { #106 (L6) - if mk != null
                            val v = fb[mk]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                            if (v.isNotBlank()) return v
                        } // } #106 (L6) - if mk != null
                    } // } #105 (L5) - for keys
                    return ""
                } // } #104 (L4) - fun fbVal

                val name = m?.name?.ifBlank { fbVal("Name", "empName", "a4") } ?: fbVal("Name", "empName", "a4")
                val designation = m?.designation?.ifBlank { fbVal("Designation", "a8") } ?: fbVal("Designation", "a8")
                val branch = m?.branch?.ifBlank { fbVal("Branch", "branchName", "a10") } ?: fbVal("Branch", "branchName", "a10")
                val bank = m?.bank?.ifBlank { fbVal("Bank", "bankName", "a20") } ?: fbVal("Bank", "bankName", "a20")
                val accountNo = m?.accountNo?.ifBlank { fbVal("AccountNo", "bankAccountNumber", "a21") } ?: fbVal("AccountNo", "bankAccountNumber", "a21")
                val ifscCode = m?.ifscCode?.ifBlank { fbVal("IFSC", "ifscCode", "a22") } ?: fbVal("IFSC", "ifscCode", "a22")

                val row = listOf(slno, code, name, designation, branch, bank, accountNo, ifscCode)
                csvBuilder.appendLine(row.joinToString(",") { escapeCsvVal(it) })
            } // } #103 (L3) - forEachIndexed

            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName,
                    "NewStaffAccountCheck_1e.csv"
                ).toString()
            )
            call.respondText(csvBuilder.toString(), ContentType.Text.CSV)
        } catch (e: Exception) { // } #77 (L3) - try / { #107 (L3) - catch Exception
            try { conn.close() } catch (_: Exception) {}
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (e.localizedMessage ?: "Failed to generate account check CSV"))
            )
        } // } #107 (L3) - catch Exception
    } // } #75 (L2) - get /api/turso/checknewstaffaccount
} // } #1 (L1) - fun Route.configureSalaryRoutes

private fun fetchSalariesDirectHttp(ids: List<String>): List<SalaryDto> { // { #108 (L1) - fun fetchSalariesDirectHttp
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

    val argsJson = buildJsonArray { // { #109 (L2) - buildJsonArray
        ids.forEach { id -> // { #110 (L3) - forEach
            addJsonObject { // { #111 (L4) - addJsonObject
                put("type", "text")
                put("value", id.trim())
            } // } #111 (L4) - addJsonObject
        } // } #110 (L3) - forEach
    } // } #109 (L2) - buildJsonArray

    val requestBody = buildJsonObject { // { #112 (L2) - buildJsonObject
        putJsonArray("requests") { // { #113 (L3) - putJsonArray
            addJsonObject { // { #114 (L4) - addJsonObject
                put("type", "execute")
                putJsonObject("stmt") { // { #115 (L5) - putJsonObject
                    put("sql", sql)
                    put("args", argsJson)
                } // } #115 (L5) - putJsonObject
            } // } #114 (L4) - addJsonObject
            addJsonObject { // { #116 (L4) - addJsonObject
                put("type", "close")
            } // } #116 (L4) - addJsonObject
        } // } #113 (L3) - putJsonArray
    }.toString()

    val url = java.net.URI(tursoUrl).toURL()
    val conn = (url.openConnection() as HttpURLConnection).apply { // { #117 (L2) - apply
        requestMethod = "POST"
        connectTimeout = 15000
        readTimeout = 15000
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Authorization", "Bearer $authToken")
        doOutput = true
        outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
    } // } #117 (L2) - apply

    val responseText = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    val root = Json.parseToJsonElement(responseText).jsonObject
    val resultObj = root["results"]?.jsonArray?.get(0)?.jsonObject
        ?.get("response")?.jsonObject
        ?.get("result")?.jsonObject ?: return emptyList()

    val cols = resultObj["cols"]?.jsonArray?.map { it.jsonObject["name"]?.jsonPrimitive?.content.orEmpty() } ?: emptyList()
    val rows = resultObj["rows"]?.jsonArray ?: return emptyList()

    val dtoList = mutableListOf<SalaryDto>()

    for (rowElement in rows) { // { #118 (L2) - for rowElement in rows
        val rowArr = rowElement.jsonArray
        val map = mutableMapOf<String, String>()
        for (i in cols.indices) { // { #119 (L3) - for i in cols.indices
            val cellObj = rowArr[i].jsonObject
            val value = cellObj["value"]?.jsonPrimitive?.contentOrNull ?: ""
            map[cols[i].lowercase()] = value
        } // } #119 (L3) - for i in cols.indices

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
    } // } #118 (L2) - for rowElement in rows

    val byId = dtoList.associateBy { it.id }
    return ids.mapNotNull { byId[it] }
} // } #108 (L1) - fun fetchSalariesDirectHttp

private fun escapeCsvVal(value: String): String { // { #120 (L1) - fun escapeCsvVal
    val clean = value.trim()
    return if (clean.contains(",") || clean.contains("\"") || clean.contains("\n") || clean.contains("\r")) { // { #121 (L2) - if clean.contains
        "\"${clean.replace("\"", "\"\"")}\""
    } else { // } #121 (L2) - if clean.contains / { #122 (L2) - else
        clean
    } // } #122 (L2) - else
} // } #120 (L1) - fun escapeCsvVal