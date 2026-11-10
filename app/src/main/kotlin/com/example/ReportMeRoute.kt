package com.example

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import java.sql.Connection

fun Route.reportMeRouting(getConnection: () -> Connection?) {
    get("/api/turso/reportme") {
        try {
            val conn = getConnection() ?: run {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
                return@get
            }

            // Kevala function call karajigala
            val reportResult = ReportMeService.generateAndSaveReport(conn)

            call.respond(HttpStatusCode.OK, mapOf(
                "status" to "success",
                "message" to "Last two months unique staff report generated and saved successfully.",
                "data" to reportResult
            ))
        } catch (e: Exception) {
    call.respond(HttpStatusCode.InternalServerError, mapOf(
        "status" to "error",
        "message" to (e.message ?: e.toString()) // Exact error message dekhaibapain
    ))
}
    }
}