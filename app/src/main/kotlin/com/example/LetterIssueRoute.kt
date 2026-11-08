package com.example

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*

fun Route.letterIssueRouting() {
    get("/api/turso/letter-issue/{empId}") {
        try {
            val empId = call.parameters["empId"] ?: ""
            if (empId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing empId parameter"))
                return@get
            }

            val letterData = LetterIssueService.getLetterIssueData(empId) { 
                TursoDatabaseService.getConnection() 
            }

            if (letterData != null) {
                call.respond(HttpStatusCode.OK, letterData)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Staff record not found for code: $empId"))
            }
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.InternalServerError, 
                mapOf("error" to (e.localizedMessage ?: "Unknown error"))
            )
        }
    }
}