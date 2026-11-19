package com.example

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.sql.Connection

@Serializable
data class LoginRequest(
    val entrycode: String = ""
)

@Serializable
data class LoginResponse(
    val allowed: Boolean,
    val verifycode: String? = null,
    val permissions: String? = null,
    val reason: String? = null,
    val name: String = ""
)

@Serializable
data class CheckAccessRequest(
    val verifycode: String = ""
)

@Serializable
data class CheckAccessResponse(
    val valid: Boolean,
    val permissions: String? = null,
    val reason: String? = null,
    val name: String = ""
)

@Serializable
data class LoginRecordDto(
    val entrycode: String = "",
    val verifycode: String = "",
    val permissions: String = "",
    val veryfynumber: Int = 0,
    val name: String = ""
)

@Serializable
data class UpdateAccessRequest(
    val entrycode: String = "",
    val verifycode: String = "",
    val permissions: String = "",
    val veryfynumber: Int = 0,
    val name: String = ""
)

@Serializable
data class UpdateAccessResponse(
    val updated: Boolean,
    val message: String
)

fun Route.authRoutes(dbConnectionProvider: () -> Connection) {
    post("/api/staff/login") {
        try {
            val req = call.receive<LoginRequest>()
            val code = req.entrycode.trim().lowercase()
            if (code.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Entry code cannot be empty"))
                return@post
            }

            dbConnectionProvider().use { conn ->
                try {
                    conn.createStatement().use { stmt ->
                        stmt.execute("""
                            CREATE TABLE IF NOT EXISTS login (
                                entrycode TEXT,
                                verifycode TEXT,
                                permissions TEXT,
                                veryfynumber INTEGER DEFAULT 0,
                                name TEXT DEFAULT ''
                            );
                        """.trimIndent())
                    }
                } catch (_: Exception) {}
                try {
                    conn.createStatement().use { stmt ->
                        stmt.execute("ALTER TABLE login ADD COLUMN name TEXT DEFAULT '';")
                    }
                } catch (_: Exception) {}

                val selectSql = "SELECT entrycode, verifycode, permissions, veryfynumber, name FROM login WHERE LOWER(TRIM(entrycode)) = ? LIMIT 1"
                var verifycode = ""
                var permissions = ""
                var veryfynumber = 0
                var name = ""
                var found = false

                conn.prepareStatement(selectSql).use { ps ->
                    ps.setString(1, code)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            found = true
                            verifycode = rs.getString("verifycode")?.trim()?.lowercase().orEmpty()
                            permissions = rs.getString("permissions")?.trim()?.lowercase().orEmpty()
                            veryfynumber = rs.getInt("veryfynumber")
                            name = rs.getString("name")?.trim()?.lowercase().orEmpty()
                        }
                    }
                }

                if (!found) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Invalid Entry Code"))
                    return@post
                }

                when (veryfynumber) {
                    0 -> {
                        conn.prepareStatement("UPDATE login SET veryfynumber = 1 WHERE LOWER(TRIM(entrycode)) = ?").use { ps ->
                            ps.setString(1, code)
                            ps.execute()
                        }
                        call.respond(HttpStatusCode.OK, LoginResponse(allowed = true, verifycode = verifycode, permissions = permissions, name = name))
                    }
                    1 -> {
                        conn.prepareStatement("UPDATE login SET veryfynumber = 2 WHERE LOWER(TRIM(entrycode)) = ?").use { ps ->
                            ps.setString(1, code)
                            ps.execute()
                        }
                        call.respond(HttpStatusCode.Forbidden, LoginResponse(allowed = false, reason = "Code already active on another device. Locked (Status 2)."))
                    }
                    else -> {
                        call.respond(HttpStatusCode.Forbidden, LoginResponse(allowed = false, reason = "Access blocked by Admin (Status 2)."))
                    }
                }
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Login failed")))
        }
    }

    post("/api/staff/check-access") {
        try {
            val req = call.receive<CheckAccessRequest>()
            val vcode = req.verifycode.trim().lowercase()
            if (vcode.isBlank()) {
                call.respond(HttpStatusCode.Forbidden, CheckAccessResponse(valid = false, reason = "Session revoked or inactive."))
                return@post
            }

            dbConnectionProvider().use { conn ->
                val selectSql = "SELECT permissions, veryfynumber, name FROM login WHERE LOWER(TRIM(verifycode)) = ? LIMIT 1"
                var permissions = ""
                var veryfynumber = -1
                var name = ""
                var found = false

                conn.prepareStatement(selectSql).use { ps ->
                    ps.setString(1, vcode)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            found = true
                            permissions = rs.getString("permissions")?.trim()?.lowercase().orEmpty()
                            veryfynumber = rs.getInt("veryfynumber")
                            name = rs.getString("name")?.trim()?.lowercase().orEmpty()
                        }
                    }
                }

                if (found && veryfynumber == 1) {
                    call.respond(HttpStatusCode.OK, CheckAccessResponse(valid = true, permissions = permissions, name = name))
                } else {
                    call.respond(HttpStatusCode.Forbidden, CheckAccessResponse(valid = false, reason = "Session revoked or inactive."))
                }
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, CheckAccessResponse(valid = false, reason = (e.localizedMessage ?: "Access check failed")))
        }
    }
}

fun Route.adminLoginControlRoutes(dbConnectionProvider: () -> Connection) {
    get("/api/staff/login-records") {
        try {
            dbConnectionProvider().use { conn ->
                try {
                    conn.createStatement().use { stmt ->
                        stmt.execute("""
                            CREATE TABLE IF NOT EXISTS login (
                                entrycode TEXT,
                                verifycode TEXT,
                                permissions TEXT,
                                veryfynumber INTEGER DEFAULT 0,
                                name TEXT DEFAULT ''
                            );
                        """.trimIndent())
                    }
                } catch (_: Exception) {}
                try {
                    conn.createStatement().use { stmt ->
                        stmt.execute("ALTER TABLE login ADD COLUMN name TEXT DEFAULT '';")
                    }
                } catch (_: Exception) {}

                val records = mutableListOf<LoginRecordDto>()
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT * FROM login").use { rs ->
                        while (rs.next()) {
                            val entrycode = rs.getString("entrycode")?.trim()?.lowercase().orEmpty()
                            val verifycode = rs.getString("verifycode")?.trim()?.lowercase().orEmpty()
                            val permissions = rs.getString("permissions")?.trim()?.lowercase().orEmpty()
                            val veryfynumber = rs.getInt("veryfynumber")
                            val name = rs.getString("name")?.trim()?.lowercase().orEmpty()

                            records.add(
                                LoginRecordDto(
                                    entrycode = entrycode,
                                    verifycode = verifycode,
                                    permissions = permissions,
                                    veryfynumber = veryfynumber,
                                    name = name
                                )
                            )
                        }
                    }
                }
                call.respond(HttpStatusCode.OK, records)
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Failed to fetch login records")))
        }
    }

    post("/api/staff/update-access") {
        try {
            val req = call.receive<UpdateAccessRequest>()
            val entrycode = req.entrycode.trim().lowercase()
            if (entrycode.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Entry code cannot be empty"))
                return@post
            }

            val name = req.name.trim().lowercase()
            val permissions = req.permissions.trim().lowercase()
            val verifycode = req.verifycode.trim().lowercase()

            dbConnectionProvider().use { conn ->
                val updateSql = "UPDATE login SET name = ?, permissions = ?, veryfynumber = ?, verifycode = ? WHERE LOWER(TRIM(entrycode)) = ?"
                conn.prepareStatement(updateSql).use { ps ->
                    ps.setString(1, name)
                    ps.setString(2, permissions)
                    ps.setInt(3, req.veryfynumber)
                    ps.setString(4, verifycode)
                    ps.setString(5, entrycode)
                    ps.execute()
                }
                call.respond(HttpStatusCode.OK, UpdateAccessResponse(updated = true, message = "Access updated successfully"))
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Failed to update access")))
        }
    }
}