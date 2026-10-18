package com.example

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class UserResponse(val status: String, val message: String)

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    // Install JSON serialization
    install(ContentNegotiation) {
        json()
    }

    // Configure routes
    routing {
        get("/") {
            call.respondText("Welcome to Ktor Backend!")
        }
        
        get("/api/user") {
            call.respond(UserResponse(status = "success", message = "Hello from Ktor Server!"))
        }
    }
}