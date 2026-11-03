package com.example // ba apanankara sothik package name

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*

fun Route.configureSalaryRoutes() {
    get("/api/turso/salaries-batch") {
        val historyParam = call.request.queryParameters["history"] ?: ""
        
        val last20Ids = historyParam.split("~")
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "0" }
            .takeLast(20)
        
        if (last20Ids.isEmpty()) {
            call.respond(emptyList<SalaryDto>())
            return@get
        }

        val results = mutableListOf<SalaryDto>()
        
        val conn = TursoDatabaseService.getConnection()
        if (conn == null) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
            return@get
        }

        try {
            // Dynamic IN clause banaba taaki sabu IDs gudaika gotei query re execute heibe
            val placeholders = last20Ids.joinToString(",") { "?" }
            val query = "SELECT * FROM SalaryHistory WHERE id IN ($placeholders)"
            
            val pstmt = conn.prepareStatement(query)
            for (i in last20Ids.indices) {
                pstmt.setString(i + 1, last20Ids[i])
            }
            
            val rs = pstmt.executeQuery()
            while (rs.next()) {
                results.add(parseSalaryDto(rs))
            }
            
            rs.close()
            pstmt.close()
            conn.close()

            call.respond(results)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Unknown error")))
        }
    }
}