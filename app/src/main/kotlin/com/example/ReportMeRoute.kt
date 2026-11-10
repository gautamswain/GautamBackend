package com.example

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import java.sql.Connection

fun Route.reportMeRouting(getConnection: () -> Connection?) {
    get("/api/turso/reportme") {
        try {
            val empId = call.request.queryParameters["empId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "empId parameter is required"))
                return@get
            }

            val conn = getConnection() ?: run {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
                return@get
            }

            val resultJson = ReportMeService.generateAndSaveReport(conn, empId)

            call.respond(HttpStatusCode.OK, mapOf(
                "status" to "success",
                "message" to "Report generated and saved successfully.",
                "data" to resultJson
            ))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "status" to "error",
                "message" to (e.localizedMessage ?: "Unknown error")
            ))
        }
    }
}
