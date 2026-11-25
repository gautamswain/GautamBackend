package com.example

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection

fun Route.reportMeRouting(getConnection: () -> Connection?) {
    val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    get("/api/turso/reportme") {
        val conn = getConnection() ?: run {
            call.respond(HttpStatusCode.InternalServerError, mapOf("status" to "error", "message" to "Database connection failed"))
            return@get
        }

        try {
            conn.use { c ->
                c.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT a, b, c, d, e, f, g FROM reports WHERE TRIM(cr) = '1' OR id = '1' LIMIT 1").use { rs ->
                        if (rs.next()) {
                            val jsonA = rs.getString("a") ?: "[]"
                            val jsonB = rs.getString("b") ?: "[]"
                            val jsonC = rs.getString("c") ?: "[]"
                            val jsonD = rs.getString("d") ?: "[]"
                            val jsonE = rs.getString("e") ?: "[]"
                            val jsonF = rs.getString("f") ?: "[]"
                            val jsonG = rs.getString("g") ?: "[]"

                            val codes = runCatching { jsonParser.decodeFromString<List<String>>(jsonA) }.getOrDefault(emptyList())
                            val active = runCatching { jsonParser.decodeFromString<List<String>>(jsonB) }.getOrDefault(emptyList())
                            val common = runCatching { jsonParser.decodeFromString<List<String>>(jsonC) }.getOrDefault(emptyList())
                            val left = runCatching { jsonParser.decodeFromString<List<String>>(jsonD) }.getOrDefault(emptyList())
                            val recent = runCatching { jsonParser.decodeFromString<List<String>>(jsonE) }.getOrDefault(emptyList())
                            val branchItems = runCatching { jsonParser.decodeFromString<List<BranchSummaryReportItem>>(jsonF) }.getOrDefault(emptyList())
                            val hoItems = runCatching { jsonParser.decodeFromString<List<HeadOfficeDeptReportItem>>(jsonG) }.getOrDefault(emptyList())

                            var totalMaster = 0
                            try {
                                c.createStatement().use { st ->
                                    st.executeQuery("SELECT COUNT(*) FROM MasterData").use { mRs ->
                                        if (mRs.next()) totalMaster = mRs.getInt(1)
                                    }
                                }
                            } catch (_: Exception) {}

                            val result = ReportResult(
                                status = "success",
                                idRecord = "1",
                                totalMasterRecords = totalMaster,
                                totalUniqueCodes = codes.size,
                                codes = codes,
                                totalMasterActive = active.size,
                                masterActiveCodes = active,
                                totalCommonCodes = common.size,
                                commonCodes = common,
                                totalLeftSalaryCodes = left.size,
                                leftSalaryCodes = left,
                                totalRecentJoinedCodes = recent.size,
                                recentJoinedCodes = recent,
                                totalBranchwiseCount = branchItems.size,
                                branchwiseCodes = branchItems,
                                totalHeadOfficeDeptCount = hoItems.size,
                                headOfficeCodes = hoItems
                            )

                            call.respond(HttpStatusCode.OK, result)
                        } else {
                            call.respond(HttpStatusCode.NotFound, mapOf("status" to "error", "message" to "Report not generated yet. Please call POST /api/turso/reportme/refresh"))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "status" to "error",
                "message" to (e.message ?: e.toString())
            ))
        }
    }

    // GET /api/turso/salary-master-changes
    get("/api/turso/salary-master-changes") {
        val conn = getConnection() ?: run {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
            return@get
        }

        try {
            conn.use { c ->
                try {
                    c.createStatement().use { stmt ->
                        stmt.execute("ALTER TABLE reports ADD COLUMN h TEXT DEFAULT '[]'")
                    }
                } catch (_: Exception) {}

                var jsonH = "[]"
                c.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT h FROM reports WHERE id = '1' OR slno = '1' LIMIT 1").use { rs ->
                        if (rs.next()) {
                            jsonH = rs.getString("h")?.trim().orEmpty().ifBlank { "[]" }
                        }
                    }
                }
                call.respondText(jsonH, ContentType.Application.Json, HttpStatusCode.OK)
            }
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (e.localizedMessage ?: e.message ?: "Failed to fetch salary-master-changes"))
            )
        }
    }

    // GET /api/turso/leftstaff
    get("/api/turso/leftstaff") {
        val conn = getConnection() ?: run {
            call.respond(HttpStatusCode.InternalServerError, mapOf("status" to "error", "message" to "Database connection failed"))
            return@get
        }

        try {
            conn.use { c ->
                val leftCodes = mutableSetOf<String>()
                val orderedCodes = mutableListOf<String>()

                c.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT d FROM reports WHERE TRIM(cr) = '1' OR id = '1' LIMIT 1").use { rs ->
                        if (rs.next()) {
                            val rawD = rs.getString("d") ?: "[]"
                            if (rawD.startsWith("[")) {
                                runCatching {
                                    Json.parseToJsonElement(rawD).jsonArray.forEach { el ->
                                        val code = el.jsonPrimitive.content.trim().uppercase()
                                        if (code.isNotBlank() && leftCodes.add(code)) {
                                            orderedCodes.add(code)
                                        }
                                    }
                                }
                            } else if (rawD.isNotBlank()) {
                                rawD.split(',', '\n', '~', ';', '\"', '[', ']', ' ')
                                    .map { it.trim().uppercase() }
                                    .filter { it.isNotBlank() && it.startsWith("ADKR") }
                                    .forEach { code ->
                                        if (leftCodes.add(code)) {
                                            orderedCodes.add(code)
                                        }
                                    }
                            }
                        }
                    }
                }

                if (orderedCodes.isEmpty()) {
                    call.respond(HttpStatusCode.OK, emptyList<MasterDataDto>())
                    return@get
                }

                val placeholders = orderedCodes.joinToString(",") { "?" }
                val sql = """
                    SELECT * FROM MasterData 
                    WHERE UPPER(TRIM(Code_2)) IN ($placeholders)
                """.trimIndent()

                val masterMap = mutableMapOf<String, MasterDataDto>()
                c.prepareStatement(sql).use { ps ->
                    orderedCodes.forEachIndexed { idx, code ->
                        ps.setString(idx + 1, code)
                    }
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            val dto = StaffRepository.parseMasterDataDto(rs)
                            val code = dto.empId.ifBlank { dto.id }.trim().uppercase()
                            if (code.isNotBlank()) {
                                masterMap[code] = dto
                            }
                        }
                    }
                }

                val resultList = orderedCodes.mapNotNull { masterMap[it] }
                call.respond(HttpStatusCode.OK, resultList)
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "status" to "error",
                "message" to (e.message ?: e.toString())
            ))
        }
    }

    // GET /api/turso/reports/column/{col} (e.g. /api/turso/reports/column/d or /api/turso/reports/column/e)
    get("/api/turso/reports/column/{col}") {
        val colParam = call.parameters["col"]?.trim()?.lowercase().orEmpty()
        val validCols = setOf("a", "b", "c", "d", "e", "f", "g", "h", "cr")
        if (colParam !in validCols) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid column. Valid columns are a, b, c, d, e, f, g, h"))
            return@get
        }

        val conn = getConnection() ?: run {
            call.respond(HttpStatusCode.InternalServerError, mapOf("status" to "error", "message" to "Database connection failed"))
            return@get
        }

        try {
            conn.use { c ->
                c.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT $colParam FROM reports WHERE TRIM(cr) = '1' OR id = '1' LIMIT 1").use { rs ->
                        if (rs.next()) {
                            val rawJson = rs.getString(1) ?: "[]"
                            val jsonElement = runCatching { Json.parseToJsonElement(rawJson) }.getOrDefault(JsonArray(emptyList()))
                            call.respond(HttpStatusCode.OK, jsonElement)
                        } else {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Report record not found for cr=1"))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("status" to "error", "message" to (e.message ?: "Refetch failed")))
        }
    }

    // GET /api/turso/reports/d (specifically for Left Salary codes from column d where cr=1)
    get("/api/turso/reports/d") {
        val conn = getConnection() ?: run {
            call.respond(HttpStatusCode.InternalServerError, mapOf("status" to "error", "message" to "Database connection failed"))
            return@get
        }

        try {
            conn.use { c ->
                c.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT d FROM reports WHERE TRIM(cr) = '1' OR id = '1' LIMIT 1").use { rs ->
                        if (rs.next()) {
                            val rawJson = rs.getString("d") ?: "[]"
                            val list = runCatching { jsonParser.decodeFromString<List<String>>(rawJson) }.getOrDefault(emptyList())
                            call.respond(HttpStatusCode.OK, mapOf("status" to "success", "column" to "d", "count" to list.size, "codes" to list))
                        } else {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Report column d not found"))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("status" to "error", "message" to (e.message ?: "Failed to fetch column d")))
        }
    }

    post("/api/turso/reportme/refresh") {
        try {
            ReportMeService.triggerAsync()
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "success", "message" to "Report recalculation triggered in background"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("status" to "error", "message" to (e.message ?: e.toString())))
        }
    }
}