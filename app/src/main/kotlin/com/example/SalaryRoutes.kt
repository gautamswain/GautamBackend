package com.example // ba apanankara sothik package name

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*

fun Route.configureSalaryRoutes() {
    get("/api/turso/salaries-batch") {
        val historyParam = call.request.queryParameters["history"] ?: ""
        
        // 1. Split and extract last 20 IDs
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
            // 2. Pratyeka ID pain independent query execute kariba (JDBC IN clause parameter binding issues avoid kariba pain eha sabutharu safe o reliable aTE)
            val pstmt = conn.prepareStatement("SELECT * FROM SalaryHistory WHERE id = ?")
            
            for (id in last20Ids) {
                pstmt.setString(1, id)
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    results.add(parseSalaryDto(rs))
                }
                rs.close()
            }
            pstmt.close()
            conn.close()

            call.respond(results)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Unknown error")))
        }
    }
}