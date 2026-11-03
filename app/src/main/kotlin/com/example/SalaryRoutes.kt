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
        call.respond(HttpStatusCode.OK, payload)   // order matches history
    }
}

private fun fetchSalaries(ids: List<String>): List<SalaryDto> {
    if (ids.isEmpty()) return emptyList()

    val placeholders = ids.joinToString(",") { "?" }
    val sql = "SELECT * FROM SalaryHistory WHERE id IN ($placeholders)"

    val conn = TursoDatabaseService.getConnection()
        ?: throw IllegalStateException("Database connection failed")

    val byId = LinkedHashMap<String, SalaryDto>()
    conn.use {
        it.prepareStatement(sql).use { pstmt ->
            ids.forEachIndexed { i, id ->
                val longId = id.toLongOrNull()
                if (longId != null) {
                    pstmt.setLong(i + 1, longId)   // Handles integer IDs correctly in IN clauses
                } else {
                    pstmt.setString(i + 1, id)
                }
            }
            pstmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val dto = parseSalaryDto(rs)
                    byId[dto.id] = dto
                }
            }
        }
    }
    return ids.mapNotNull(byId::get)   // preserves original history order, drops missing rows
}