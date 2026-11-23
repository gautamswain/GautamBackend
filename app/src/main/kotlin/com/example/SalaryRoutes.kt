package com.example

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
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
    // 4. GET /api/turso/download-attendance-csv (supports no-param, /{monthc}, or ?monthc=M116)
    // =========================================================================
    suspend fun PipelineContext<Unit, ApplicationCall>.handleDownloadAttendanceCsv() { // { #45 (L2) - fun handleDownloadAttendanceCsv
        val requestedMonthc = (
            call.parameters["monthc"]
                ?: call.request.queryParameters["monthc"]
        )?.trim().orEmpty()

        val conn = TursoDatabaseService.getConnection()
        if (conn == null) { // { #46 (L3) - if conn == null
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
            return
        } // } #46 (L3) - if conn == null

        try { // { #47 (L3) - try
            // Step 1: Fetch totday, sunday, holiday from salaryinfo (or latest row if monthc omitted)
            var resolvedMonthc = requestedMonthc
            var totday = 0
            var sunday = 0
            var holiday = 0
            var foundSal = false

            if (requestedMonthc.isNotBlank()) { // { #48 (L4) - if requestedMonthc.isNotBlank
                val salSql = "SELECT monthc, totday, sunday, holiday FROM salaryinfo WHERE LOWER(TRIM(monthc)) = LOWER(TRIM(?)) LIMIT 1"
                conn.prepareStatement(salSql).use { ps -> // { #49 (L5) - ps.use
                    ps.setString(1, requestedMonthc)
                    ps.executeQuery().use { rs -> // { #50 (L6) - rs.use
                        if (rs.next()) { // { #51 (L7) - if rs.next
                            foundSal = true
                            resolvedMonthc = rs.getString("monthc")?.trim().orEmpty().ifBlank { requestedMonthc }
                            totday = rs.getString("totday")?.trim()?.toIntOrNull() ?: 0
                            sunday = rs.getString("sunday")?.trim()?.toIntOrNull() ?: 0
                            holiday = rs.getString("holiday")?.trim()?.toIntOrNull() ?: 0
                        } // } #51 (L7) - if rs.next
                    } // } #50 (L6) - rs.use
                } // } #49 (L5) - ps.use
            } else { // } #48 (L4) - if requestedMonthc.isNotBlank / { #52 (L4) - else latest month
                val latestSql = "SELECT monthc, totday, sunday, holiday FROM salaryinfo ORDER BY rowid DESC LIMIT 1"
                conn.createStatement().use { stmt -> // { #53 (L5) - stmt.use
                    stmt.executeQuery(latestSql).use { rs -> // { #54 (L6) - rs.use
                        if (rs.next()) { // { #55 (L7) - if rs.next
                            foundSal = true
                            resolvedMonthc = rs.getString("monthc")?.trim().orEmpty().ifBlank { "LATEST" }
                            totday = rs.getString("totday")?.trim()?.toIntOrNull() ?: 0
                            sunday = rs.getString("sunday")?.trim()?.toIntOrNull() ?: 0
                            holiday = rs.getString("holiday")?.trim()?.toIntOrNull() ?: 0
                        } // } #55 (L7) - if rs.next
                    } // } #54 (L6) - rs.use
                } // } #53 (L5) - stmt.use
            } // } #52 (L4) - else latest month

            if (!foundSal) { // { #56 (L4) - if !foundSal
                conn.close()
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "No salaryinfo record found${if (requestedMonthc.isNotBlank()) " for monthc = $requestedMonthc" else ""}")
                )
                return
            } // } #56 (L4) - if !foundSal

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

            // Step 2: Fetch 1f (Branch) and 1g (Head Office) from reports (fields 'f' and 'g')
            data class AttendanceRow(
                val code: String,
                val name: String,
                val designation: String,
                val department: String,
                val branch: String,
                val portfolio: String,
                val doj: String
            )

            val branchCodes = mutableListOf<String>()
            val hoCodes = mutableListOf<String>()
            val directRows = mutableListOf<AttendanceRow>()

            fun JsonObject.getField(vararg keys: String): String { // { #57 (L4) - fun getField
                for (k in keys) { // { #58 (L5) - for keys
                    val matchKey = this.keys.firstOrNull { it.equals(k, ignoreCase = true) }
                    if (matchKey != null) { // { #59 (L6) - if matchKey != null
                        val v = this[matchKey]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                        if (v.isNotBlank()) return v
                    } // } #59 (L6) - if matchKey != null
                } // } #58 (L5) - for keys
                return ""
            } // } #57 (L4) - fun getField

            fun parseReportField(el: JsonElement, unitType: String, inheritedGroup: String = "") { // { #60 (L4) - fun parseReportField
                when (el) { // { #61 (L5) - when el
                    is JsonPrimitive -> { // { #62 (L6) - is JsonPrimitive
                        val raw = el.content.trim()
                        if (raw.startsWith("[") || raw.startsWith("{")) { // { #63 (L7) - if raw.startsWith
                            runCatching { parseReportField(Json.parseToJsonElement(raw), unitType, inheritedGroup) }
                        } else if (raw.isNotBlank()) { // } #63 (L7) - if raw.startsWith / { #64 (L7) - else if
                            raw.split(',', '\n', '~', ';').forEach { token -> // { #65 (L8) - forEach token
                                val c = token.trim().uppercase()
                                if (c.isNotBlank()) { // { #66 (L9) - if c.isNotBlank
                                    if (unitType == "Head Office") hoCodes.add(c) else branchCodes.add(c)
                                } // } #66 (L9) - if c.isNotBlank
                            } // } #65 (L8) - forEach token
                        } // } #64 (L7) - else if
                    } // } #62 (L6) - is JsonPrimitive
                    is JsonArray -> { // { #67 (L6) - is JsonArray
                        el.forEach { child -> parseReportField(child, unitType, inheritedGroup) }
                    } // } #67 (L6) - is JsonArray
                    is JsonObject -> { // { #68 (L6) - is JsonObject
                        val groupLabel = listOf("branchName", "branch_name", "departmentName", "department")
                            .firstNotNullOfOrNull { k -> el[k]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() } }
                            ?: inheritedGroup

                        val nestedList = el["staffList"] ?: el["staff"] ?: el["employees"] ?: el["records"]
                        if (nestedList is JsonArray) { // { #69 (L7) - if nestedList is JsonArray
                            nestedList.forEach { child -> parseReportField(child, unitType, groupLabel) }
                        } else { // } #69 (L7) - if nestedList is JsonArray / { #70 (L7) - else
                            val code = el.getField("a3", "Code", "empCode", "Code_2").uppercase()
                            val name = el.getField("a4", "Name", "empName", "employee_name_3")
                            if (code.isNotBlank() && name.isBlank()) { // { #71 (L8) - if code only
                                if (unitType == "Head Office") hoCodes.add(code) else branchCodes.add(code)
                            } else if (code.isNotBlank() || name.isNotBlank()) { // } #71 (L8) - if code only / { #72 (L8) - else if full obj
                                directRows.add(
                                    AttendanceRow(
                                        code = code,
                                        name = name,
                                        designation = el.getField("a8", "Designation", "designation_7"),
                                        department = el.getField("a9", "Department", "department_8")
                                            .ifBlank { if (unitType == "Head Office") groupLabel else "Operation" },
                                        branch = el.getField("a10", "Branch", "branchName", "branch_name_9")
                                            .ifBlank { groupLabel.ifBlank { unitType } },
                                        portfolio = el.getField("a52", "PORTFOLIO", "Portfolio", "portfolio_7"),
                                        doj = el.getField("a11", "DOJ", "dojInAdhikar", "doj_in_adhikar_10")
                                    )
                                )
                            } // } #72 (L8) - else if full obj
                        } // } #70 (L7) - else
                    } // } #68 (L6) - is JsonObject
                } // } #61 (L5) - when el
            } // } #60 (L4) - fun parseReportField

            conn.createStatement().use { stmt -> // { #73 (L5) - stmt.use
                stmt.executeQuery("SELECT f, g FROM reports WHERE id = '1' LIMIT 1").use { rs -> // { #74 (L6) - rs.use
                    if (rs.next()) { // { #75 (L7) - if rs.next
                        val rawF = rs.getString("f")?.trim().orEmpty()
                        if (rawF.isNotBlank()) { // { #76 (L8) - if rawF.isNotBlank
                            runCatching { parseReportField(Json.parseToJsonElement(rawF), "Branch") }
                        } // } #76 (L8) - if rawF.isNotBlank

                        val rawG = rs.getString("g")?.trim().orEmpty()
                        if (rawG.isNotBlank()) { // { #77 (L8) - if rawG.isNotBlank
                            runCatching { parseReportField(Json.parseToJsonElement(rawG), "Head Office") }
                        } // } #77 (L8) - if rawG.isNotBlank
                    } // } #75 (L7) - if rs.next
                } // } #74 (L6) - rs.use
            } // } #73 (L5) - stmt.use

            // Lookup MasterData for all ADKR codes extracted from 1f and 1g
            val orderedCodes = (branchCodes + hoCodes).distinct()
            val hoCodeSet = hoCodes.toSet()
            val masterByCode = mutableMapOf<String, AttendanceRow>()

            if (orderedCodes.isNotEmpty()) { // { #78 (L4) - if orderedCodes.isNotEmpty
                val placeholders = orderedCodes.joinToString(",") { "?" }
                val sql = """
                    SELECT * FROM MasterData
                    WHERE UPPER(TRIM(Code_2)) IN ($placeholders)
                """.trimIndent()

                conn.prepareStatement(sql).use { ps -> // { #79 (L5) - ps.use
                    orderedCodes.forEachIndexed { idx, code -> ps.setString(idx + 1, code) }
                    ps.executeQuery().use { rs -> // { #80 (L6) - rs.use
                        val meta = rs.metaData
                        val colMap = (1..meta.columnCount).associate {
                            meta.getColumnName(it).trim().lowercase() to it
                        }
                        fun col(vararg keys: String): String { // { #81 (L7) - fun col
                            for (k in keys) { // { #82 (L8) - for keys
                                val i = colMap[k.lowercase()]
                                if (i != null) { // { #83 (L9) - if i != null
                                    val v = rs.getString(i)?.trim().orEmpty()
                                    if (v.isNotBlank()) return v
                                } // } #83 (L9) - if i != null
                            } // } #82 (L8) - for keys
                            return ""
                        } // } #81 (L7) - fun col

                        while (rs.next()) { // { #84 (L7) - while rs.next
                            val code = col("Code_2", "a3", "code").uppercase()
                            if (code.isNotBlank()) { // { #85 (L8) - if code.isNotBlank
                                val isHo = code in hoCodeSet
                                val dept = col("department_8", "a9", "department")
                                    .ifBlank { if (isHo) "Head Office" else "Operation" }
                                val br = col("branch_name_9", "a10", "branch")
                                    .ifBlank { if (isHo) "Head Office" else "Branch" }
                                masterByCode[code] = AttendanceRow(
                                    code = code,
                                    name = col("employee_name_3", "a4", "name"),
                                    designation = col("designation_7", "a8", "designation"),
                                    department = dept,
                                    branch = br,
                                    portfolio = col("a52", "portfolio_7", "portfolio"),
                                    doj = col("doj_in_adhikar_10", "a11", "doj")
                                )
                            } // } #85 (L8) - if code.isNotBlank
                        } // } #84 (L7) - while rs.next
                    } // } #80 (L6) - rs.use
                } // } #79 (L5) - ps.use
            } // } #78 (L4) - if orderedCodes.isNotEmpty
            conn.close()

            val finalRows = if (orderedCodes.isNotEmpty()) { // { #86 (L4) - if orderedCodes.isNotEmpty
                orderedCodes.mapNotNull { masterByCode[it] }
            } else { // } #86 (L4) - if orderedCodes.isNotEmpty / { #87 (L4) - else
                directRows
            } // } #87 (L4) - else

            // Step 3: Build CSV with exact required headers
            val headers = listOf(
                "SL.NO", "Code", "Name", "Designation", "Department", "Branch", "PORTFOLIO", "DOJ",
                "Present Days", "Weekly Offs", "Declared Holidays",
                "Casual Leave", "Sick Leave", "Earned Leave", "Marriage Leave", "Maternity Leave", "Paternity Leave",
                "Loss of Pay", "Total Paid Days", "Status", "REMARKS", "Prepare By"
            )

            val csvBuilder = StringBuilder()
            csvBuilder.appendLine(headers.joinToString(",") { escapeCsvVal(it) })

            finalRows.forEachIndexed { idx, emp -> // { #88 (L4) - forEachIndexed
                val row = listOf(
                    (idx + 1).toString(),
                    emp.code,
                    emp.name,
                    emp.designation,
                    emp.department,
                    emp.branch,
                    emp.portfolio,
                    emp.doj,
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
            } // } #88 (L4) - forEachIndexed

            val fileName = "Attendance_${resolvedMonthc.uppercase()}.csv"
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName,
                    fileName
                ).toString()
            )
            call.respondText(csvBuilder.toString(), ContentType.Text.CSV)
        } catch (e: Exception) { // } #47 (L3) - try / { #89 (L3) - catch Exception
            try { conn.close() } catch (_: Exception) {}
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (e.localizedMessage ?: "Failed to generate attendance CSV"))
            )
        } // } #89 (L3) - catch Exception
    } // } #45 (L2) - fun handleDownloadAttendanceCsv

    get("/api/turso/download-attendance-csv") { handleDownloadAttendanceCsv() }
    get("/api/turso/download-attendance-csv/{monthc}") { handleDownloadAttendanceCsv() }

    // =========================================================================
    // 5. GET /api/turso/checknewstaffaccount (1e Staff Bank Account Check CSV)
    // =========================================================================
    get("/api/turso/checknewstaffaccount") { // { #90 (L2) - get /api/turso/checknewstaffaccount
        val conn = TursoDatabaseService.getConnection()
        if (conn == null) { // { #91 (L3) - if conn == null
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
            return@get
        } // } #91 (L3) - if conn == null

        try { // { #92 (L3) - try
            // Step 1: Read 1e staff list from reports (id = '1', field 'e')
            val codesFrom1e = mutableListOf<String>()
            val fallbackObjByCode = mutableMapOf<String, JsonObject>()

            conn.createStatement().use { stmt -> // { #93 (L4) - stmt.use
                stmt.executeQuery("SELECT e FROM reports WHERE id = '1' LIMIT 1").use { rs -> // { #94 (L5) - rs.use
                    while (rs.next()) { // { #95 (L6) - while rs.next
                        val rawE = rs.getString("e")?.trim().orEmpty()
                        if (rawE.startsWith("[")) { // { #96 (L7) - if rawE.startsWith
                            runCatching { // { #97 (L8) - runCatching
                                Json.parseToJsonElement(rawE).jsonArray.forEach { el -> // { #98 (L9) - forEach
                                    if (el is JsonObject) { // { #99 (L10) - if el is JsonObject
                                        val codeKey = el.keys.firstOrNull {
                                            it.equals("Code", true) || it.equals("empCode", true) || it.equals("a3", true) || it.equals("Code_2", true)
                                        }
                                        val codeVal = codeKey?.let { el[it]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase() }.orEmpty()
                                        if (codeVal.isNotBlank()) { // { #100 (L11) - if codeVal.isNotBlank
                                            codesFrom1e.add(codeVal)
                                            fallbackObjByCode[codeVal] = el
                                        } // } #100 (L11) - if codeVal.isNotBlank
                                    } else if (el is JsonPrimitive) { // } #99 (L10) - if el is JsonObject / { #101 (L10) - else if
                                        val codeVal = el.content.trim().uppercase()
                                        if (codeVal.isNotBlank()) codesFrom1e.add(codeVal)
                                    } // } #101 (L10) - else if
                                } // } #98 (L9) - forEach
                            } // } #97 (L8) - runCatching
                        } else if (rawE.startsWith("{")) { // } #96 (L7) - if rawE.startsWith / { #102 (L7) - else if
                            runCatching { // { #103 (L8) - runCatching
                                val el = Json.parseToJsonElement(rawE).jsonObject
                                val codeKey = el.keys.firstOrNull {
                                    it.equals("Code", true) || it.equals("empCode", true) || it.equals("a3", true) || it.equals("Code_2", true)
                                }
                                val codeVal = codeKey?.let { el[it]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase() }.orEmpty()
                                if (codeVal.isNotBlank()) { // { #104 (L9) - if codeVal.isNotBlank
                                    codesFrom1e.add(codeVal)
                                    fallbackObjByCode[codeVal] = el
                                } // } #104 (L9) - if codeVal.isNotBlank
                            } // } #103 (L8) - runCatching
                        } else if (rawE.isNotBlank()) { // } #102 (L7) - else if / { #105 (L7) - else if
                            rawE.split(',', '\n', '~', ';').forEach { token ->
                                val codeVal = token.trim().uppercase()
                                if (codeVal.isNotBlank()) codesFrom1e.add(codeVal)
                            }
                        } // } #105 (L7) - else if
                    } // } #95 (L6) - while rs.next
                } // } #94 (L5) - rs.use
            } // } #93 (L4) - stmt.use

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

            if (distinctCodes.isNotEmpty()) { // { #106 (L3) - if distinctCodes.isNotEmpty
                val availableCols = mutableListOf<String>()
                conn.createStatement().use { stmt -> // { #107 (L4) - stmt.use
                    stmt.executeQuery("PRAGMA table_info(MasterData)").use { rs -> // { #108 (L5) - rs.use
                        while (rs.next()) { // { #109 (L6) - while rs.next
                            availableCols.add(rs.getString("name").trim())
                        } // } #109 (L6) - while rs.next
                    } // } #108 (L5) - rs.use
                } // } #107 (L4) - stmt.use

                val codeColName = availableCols.firstOrNull { col ->
                    col.equals("code_2", true) ||
                    col.equals("a3", true) ||
                    col.equals("code", true) ||
                    col.equals("empcode", true) ||
                    col.equals("emp_code", true) ||
                    col.equals("empid", true) ||
                    col.equals("emp_id", true)
                }

                if (codeColName != null) { // { #110 (L4) - if codeColName != null
                    val placeholders = distinctCodes.joinToString(",") { "?" }
                    val sql = """SELECT * FROM MasterData WHERE UPPER(TRIM("$codeColName")) IN ($placeholders)"""
                    conn.prepareStatement(sql).use { ps -> // { #111 (L5) - ps.use
                        distinctCodes.forEachIndexed { idx, code -> ps.setString(idx + 1, code) }
                        ps.executeQuery().use { rs -> // { #112 (L6) - rs.use
                            val meta = rs.metaData
                            val colMap = (1..meta.columnCount).associate {
                                meta.getColumnName(it).trim().lowercase() to it
                            }
                            fun col(vararg keys: String): String { // { #113 (L7) - fun col
                                for (k in keys) { // { #114 (L8) - for keys
                                    val i = colMap[k.lowercase()]
                                    if (i != null) { // { #115 (L9) - if i != null
                                        val v = rs.getString(i)?.trim().orEmpty()
                                        if (v.isNotBlank()) return v
                                    } // } #115 (L9) - if i != null
                                } // } #114 (L8) - for keys
                                return ""
                            } // } #113 (L7) - fun col

                            while (rs.next()) { // { #116 (L7) - while rs.next
                                val code = col(codeColName, "code_2", "a3", "code").uppercase()
                                if (code.isNotBlank()) { // { #117 (L8) - if code.isNotBlank
                                    masterMap[code] = AccountRow(
                                        code = code,
                                        name = col("employee_name_3", "a4", "name", "empname", "emp_name"),
                                        designation = col("designation_7", "a8", "designation", "desig"),
                                        branch = col("branch_name_9", "a10", "branch", "branchname", "branch_name"),
                                        bank = col("bank_name_19", "a20", "bankname", "bank_name", "bank"),
                                        accountNo = col("bank_account_number_20", "a21", "bankaccountnumber", "accountno", "account_no"),
                                        ifscCode = col("ifsc_code_bank_branch_name_21", "a22", "ifsccode", "ifsc_code", "ifsc")
                                    )
                                } // } #117 (L8) - if code.isNotBlank
                            } // } #116 (L7) - while rs.next
                        } // } #112 (L6) - rs.use
                    } // } #111 (L5) - ps.use
                } // } #110 (L4) - if codeColName != null
            } // } #106 (L3) - if distinctCodes.isNotEmpty
            conn.close()

            // Step 3: Build CSV: slno,code,name,designation,branch,bank,accountno,ifsccode
            val csvBuilder = StringBuilder()
            csvBuilder.appendLine("slno,code,name,designation,branch,bank,accountno,ifsccode")

            distinctCodes.forEachIndexed { idx, code -> // { #118 (L3) - forEachIndexed
                val slno = (idx + 1).toString()
                val m = masterMap[code]
                val fb = fallbackObjByCode[code]

                fun fbVal(vararg keys: String): String { // { #119 (L4) - fun fbVal
                    if (fb == null) return ""
                    for (k in keys) { // { #120 (L5) - for keys
                        val mk = fb.keys.firstOrNull { it.equals(k, true) }
                        if (mk != null) { // { #121 (L6) - if mk != null
                            val v = fb[mk]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                            if (v.isNotBlank()) return v
                        } // } #121 (L6) - if mk != null
                    } // } #120 (L5) - for keys
                    return ""
                } // } #119 (L4) - fun fbVal

                val name = m?.name?.ifBlank { fbVal("Name", "empName", "a4") } ?: fbVal("Name", "empName", "a4")
                val designation = m?.designation?.ifBlank { fbVal("Designation", "a8") } ?: fbVal("Designation", "a8")
                val branch = m?.branch?.ifBlank { fbVal("Branch", "branchName", "a10") } ?: fbVal("Branch", "branchName", "a10")
                val bank = m?.bank?.ifBlank { fbVal("Bank", "bankName", "a20") } ?: fbVal("Bank", "bankName", "a20")
                val accountNo = m?.accountNo?.ifBlank { fbVal("AccountNo", "bankAccountNumber", "a21") } ?: fbVal("AccountNo", "bankAccountNumber", "a21")
                val ifscCode = m?.ifscCode?.ifBlank { fbVal("IFSC", "ifscCode", "a22") } ?: fbVal("IFSC", "ifscCode", "a22")

                val row = listOf(slno, code, name, designation, branch, bank, accountNo, ifscCode)
                csvBuilder.appendLine(row.joinToString(",") { escapeCsvVal(it) })
            } // } #118 (L3) - forEachIndexed

            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName,
                    "NewStaffAccountCheck_1e.csv"
                ).toString()
            )
            call.respondText(csvBuilder.toString(), ContentType.Text.CSV)
        } catch (e: Exception) { // } #92 (L3) - try / { #122 (L3) - catch Exception
            try { conn.close() } catch (_: Exception) {}
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (e.localizedMessage ?: "Failed to generate account check CSV"))
            )
        } // } #122 (L3) - catch Exception
    } // } #90 (L2) - get /api/turso/checknewstaffaccount
} // } #1 (L1) - fun Route.configureSalaryRoutes

private fun fetchSalariesDirectHttp(ids: List<String>): List<SalaryDto> { // { #123 (L1) - fun fetchSalariesDirectHttp
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

    val argsJson = buildJsonArray { // { #124 (L2) - buildJsonArray
        ids.forEach { id -> // { #125 (L3) - forEach
            addJsonObject { // { #126 (L4) - addJsonObject
                put("type", "text")
                put("value", id.trim())
            } // } #126 (L4) - addJsonObject
        } // } #125 (L3) - forEach
    } // } #124 (L2) - buildJsonArray

    val requestBody = buildJsonObject { // { #127 (L2) - buildJsonObject
        putJsonArray("requests") { // { #128 (L3) - putJsonArray
            addJsonObject { // { #129 (L4) - addJsonObject
                put("type", "execute")
                putJsonObject("stmt") { // { #130 (L5) - putJsonObject
                    put("sql", sql)
                    put("args", argsJson)
                } // } #130 (L5) - putJsonObject
            } // } #129 (L4) - addJsonObject
            addJsonObject { // { #131 (L4) - addJsonObject
                put("type", "close")
            } // } #131 (L4) - addJsonObject
        } // } #128 (L3) - putJsonArray
    }.toString()

    val url = java.net.URI(tursoUrl).toURL()
    val conn = (url.openConnection() as HttpURLConnection).apply { // { #132 (L2) - apply
        requestMethod = "POST"
        connectTimeout = 15000
        readTimeout = 15000
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Authorization", "Bearer $authToken")
        doOutput = true
        outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
    } // } #132 (L2) - apply

    val responseText = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    val root = Json.parseToJsonElement(responseText).jsonObject
    val resultObj = root["results"]?.jsonArray?.get(0)?.jsonObject
        ?.get("response")?.jsonObject
        ?.get("result")?.jsonObject ?: return emptyList()

    val cols = resultObj["cols"]?.jsonArray?.map { it.jsonObject["name"]?.jsonPrimitive?.content.orEmpty() } ?: emptyList()
    val rows = resultObj["rows"]?.jsonArray ?: return emptyList()

    val dtoList = mutableListOf<SalaryDto>()

    for (rowElement in rows) { // { #133 (L2) - for rowElement in rows
        val rowArr = rowElement.jsonArray
        val map = mutableMapOf<String, String>()
        for (i in cols.indices) { // { #134 (L3) - for i in cols.indices
            val cellObj = rowArr[i].jsonObject
            val value = cellObj["value"]?.jsonPrimitive?.contentOrNull ?: ""
            map[cols[i].lowercase()] = value
        } // } #134 (L3) - for i in cols.indices

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
    } // } #133 (L2) - for rowElement in rows

    val byId = dtoList.associateBy { it.id }
    return ids.mapNotNull { byId[it] }
} // } #123 (L1) - fun fetchSalariesDirectHttp

private fun escapeCsvVal(value: String): String { // { #135 (L1) - fun escapeCsvVal
    val clean = value.trim()
    return if (clean.contains(",") || clean.contains("\"") || clean.contains("\n") || clean.contains("\r")) { // { #136 (L2) - if clean.contains
        "\"${clean.replace("\"", "\"\"")}\""
    } else { // } #136 (L2) - if clean.contains / { #137 (L2) - else
        clean
    } // } #137 (L2) - else
} // } #135 (L1) - fun escapeCsvVal