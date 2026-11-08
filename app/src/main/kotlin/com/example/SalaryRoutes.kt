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

    val conn = TursoDatabaseService.getConnection()
        ?: throw IllegalStateException("Database connection failed")

    val results = mutableListOf<SalaryDto>()
    
    // Dynamic placeholders (e.g., ?, ?, ?) ତିଆରି କରିବା
    val placeholders = ids.joinToString(",") { "?" }
    val sql = "SELECT * FROM SalaryHistory WHERE id IN ($placeholders) OR sl_no_1 IN ($placeholders)"

    conn.use {
        it.prepareStatement(sql).use { pstmt ->
            // id ଏବଂ sl_no_1 ଦୁଇଟିଯାକ ପାଇଁ ପାରାମିଟରଗୁଡ଼ିକ ସେଟ୍ କରିବା
            ids.forEachIndexed { index, id ->
                pstmt.setString(index + 1, id.trim())
            }
            ids.forEachIndexed { index, id ->
                pstmt.setString(ids.size + index + 1, id.trim())
            }

            pstmt.executeQuery().use { rs ->
                while (rs.next()) {
                    results.add(parseSalaryDto(rs))
                }
            }
        }
    }
    
    // ଇନପୁଟ୍ ଅର୍ଡର୍ (order) ଅନୁସାରେ ଡାଟା ସଜାଡ଼ିବା
    val byId = results.associateBy { it.id }
    val bySlNo = results.associateBy { it.slNo1 }
    
    return ids.mapNotNull { id ->
        byId[id] ?: bySlNo[id]
    }
}