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
                call.respond(HttpStatusCode.InternalServerError, mapOf("status" to "error", "message" to "Database connection failed"))
                return@get
            }

            val result = ReportMeService.processAndSave(conn)

            // mapOf badalare sidha @Serializable ReportResult object respond karajigala
            call.respond(HttpStatusCode.OK, result)
            
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "status" to "error",
                "message" to (e.message ?: e.toString())
            ))
        }
    }
}