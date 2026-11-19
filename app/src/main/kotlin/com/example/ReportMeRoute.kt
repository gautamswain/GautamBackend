package com.example

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
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
                    stmt.executeQuery("SELECT a, b, c, d, e, f, g FROM reports WHERE id = '1' LIMIT 1").use { rs ->
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

    post("/api/turso/reportme/refresh") {
        try {
            ReportMeService.triggerAsync()
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "success", "message" to "Report recalculation triggered in background"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("status" to "error", "message" to (e.message ?: e.toString())))
        }
    }
}