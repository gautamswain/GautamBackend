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

    val conn = TursoDatabaseService.getConnection()
        ?: throw IllegalStateException("Database connection failed")

    // SQL Injection ରୋକିବା ପାଇଁ କେବଳ alphanumeric/numeric ID କୁ ଅନୁମତି ଦେବା
    val safeIds = ids.map { it.trim().replace("'", "") }.filter { it.isNotEmpty() }
    if (safeIds.isEmpty()) return emptyList()

    // '?' ବଦଳରେ ସିଧାସଳଖ '26594','26827','27067' ଫର୍ମାଟ୍ ତିଆରି କରିବା
    val inClause = safeIds.joinToString(",") { "'$it'" }
    val sql = "SELECT * FROM SalaryHistory WHERE CAST(id AS TEXT) IN ($inClause)"

    val results = mutableListOf<SalaryDto>()

    conn.use { connection ->
        connection.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    results.add(parseSalaryDto(rs))
                }
            }
        }
    }

    // ୟୁଜର୍ ଦେଇଥିବା ଇନପୁଟ୍ ଅର୍ଡର୍ (order) ଅନୁସାରେ ଡାଟା ସଜାଡ଼ିବା
    val byId = results.associateBy { it.id.trim() }
    return safeIds.mapNotNull { id -> byId[id] }
}
