package com.example

import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        bulkLetterRoutes { TursoDatabaseService.getConnection() ?: throw IllegalStateException("Database connection failed") }
    }
}