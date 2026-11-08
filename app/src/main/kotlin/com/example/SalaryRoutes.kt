package com.example

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_BATCH = 20

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
                fetchSalaries(lastIds)
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
}

private fun fetchSalaries(ids: List<String>): List<SalaryDto> {
    if (ids.isEmpty()) return emptyList()

    val results = mutableListOf<SalaryDto>()

    for (rawId in ids) {
        val cleanId = rawId.trim().replace("'", "")
        if (cleanId.isEmpty()) continue

        val conn = TursoDatabaseService.getConnection()
            ?: throw IllegalStateException("Database connection failed")

        conn.use { connection ->
            val sql = "SELECT * FROM SalaryHistory WHERE CAST(id AS TEXT) = '$cleanId'"
            connection.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    if (rs.next()) {
                        results.add(parseSalaryDto(rs))
                    }
                }
            }
        }
    }

    return results
}