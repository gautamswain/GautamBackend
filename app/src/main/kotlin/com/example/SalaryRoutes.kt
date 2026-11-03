package com.example // ba apanankara sothik package name

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*

fun Route.configureSalaryRoutes() {
    get("/api/turso/salaries-batch") {
        val historyParam = call.request.queryParameters["history"] ?: ""
        
        // 1. Split and extract last 20 IDs on backend
        val last20Ids = historyParam.split("~")
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "0" }
            .takeLast(20)
        
        val results = mutableListOf<SalaryDto>()
        
        // 2. Fetch records for these 20 IDs from database using TursoDatabaseService
        val conn = TursoDatabaseService.getConnection()
        if (conn == null) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
            return@get
        }

        try {
            val pstmt = conn.prepareStatement("SELECT * FROM SalaryHistory WHERE id = ? OR sl_no_1 = ?")
            for (id in last20Ids) {
                pstmt.setString(1, id)
                pstmt.setString(2, id)
                val rs = pstmt.executeQuery()
                if (rs.next()) {
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