package com.example.plugins // Aapankara package name anukul rakhibe

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.configureSalaryRoutes() {
    get("/api/turso/salaries-batch") {
        val historyParam = call.request.queryParameters["history"] ?: ""
        
        // 1. Split and extract last 20 IDs on backend
        val last20Ids = historyParam.split("~")
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "0" }
            .takeLast(20)
        
        val results = mutableListOf>()
        
        // 2. Fetch records for these 20 IDs from database (Turso)
        for (id in last20Ids) {
            val record = TursoDatabase.getSalaryById(id) 
            if (record != null) {
                results.add(record)
            }
        }
        
        // 3. Send final JSON list back to Android frontend
        call.respond(results)
    }
}