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
    val entryCode: String = ""
)

@Serializable
data class LoginResponse(
    val allowed: Boolean,
    val verifyCode: String? = null,
    val permissions: String? = null,
    val reason: String? = null
)

@Serializable
data class CheckAccessRequest(
    val verifyCode: String = ""
)

@Serializable
data class CheckAccessResponse(
    val valid: Boolean,
    val permissions: String? = null,
    val reason: String? = null
)

@Serializable
data class LoginRecordDto(
    val entryCode: String,
    val employeeName: String,
    val maskedVerifyCode: String,
    val permissions: String,
    val veryfynumber: Int
)

@Serializable
data class UpdateAccessRequest(
    val entryCode: String,
    val permissions: String,
    val veryfynumber: Int,
    val newVerifyCode: String? = null
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
            val code = req.entryCode.trim()
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
                                veryfynumber INTEGER DEFAULT 0
                            );
                        """.trimIndent())
                    }
                } catch (_: Exception) {}

                val selectSql = "SELECT entrycode, verifycode, permissions, veryfynumber FROM login WHERE UPPER(TRIM(entrycode)) = UPPER(TRIM(?)) LIMIT 1"
                var verifyCode = ""
                var permissions = ""
                var veryfynumber = 0
                var found = false

                conn.prepareStatement(selectSql).use { ps ->
                    ps.setString(1, code)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            found = true
                            verifyCode = rs.getString("verifycode")?.trim().orEmpty()
                            permissions = rs.getString("permissions")?.trim().orEmpty()
                            veryfynumber = rs.getInt("veryfynumber")
                        }
                    }
                }

                if (!found) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Invalid Entry Code"))
                    return@post
                }

                when (veryfynumber) {
                    0 -> {
                        val updateSql = "UPDATE login SET veryfynumber = 1 WHERE UPPER(TRIM(entrycode)) = UPPER(TRIM(?))"
                        conn.prepareStatement(updateSql).use { ps ->
                            ps.setString(1, code)
                            ps.execute()
                        }
                        call.respond(HttpStatusCode.OK, LoginResponse(allowed = true, verifyCode = verifyCode, permissions = permissions))
                    }
                    1 -> {
                        val updateSql = "UPDATE login SET veryfynumber = 2 WHERE UPPER(TRIM(entrycode)) = UPPER(TRIM(?))"
                        conn.prepareStatement(updateSql).use { ps ->
                            ps.setString(1, code)
                            ps.execute()
                        }
                        call.respond(
                            HttpStatusCode.Forbidden,
                            LoginResponse(allowed = false, reason = "Code already active on another device. Locked (Status 2).")
                        )
                    }
                    else -> {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            LoginResponse(allowed = false, reason = "Access blocked by Admin (Status 2).")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Login failed")))
        }
    }

    post("/api/staff/check-access") {
        try {
            val req = call.receive<CheckAccessRequest>()
            val vCode = req.verifyCode.trim()
            if (vCode.isBlank()) {
                call.respond(HttpStatusCode.Forbidden, CheckAccessResponse(valid = false, reason = "Session revoked or inactive."))
                return@post
            }

            dbConnectionProvider().use { conn ->
                val selectSql = "SELECT verifycode, permissions, veryfynumber FROM login WHERE UPPER(TRIM(verifycode)) = UPPER(TRIM(?)) LIMIT 1"
                var permissions = ""
                var veryfynumber = -1
                var found = false

                conn.prepareStatement(selectSql).use { ps ->
                    ps.setString(1, vCode)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            found = true
                            permissions = rs.getString("permissions")?.trim().orEmpty()
                            veryfynumber = rs.getInt("veryfynumber")
                        }
                    }
                }

                if (found && veryfynumber == 1) {
                    call.respond(HttpStatusCode.OK, CheckAccessResponse(valid = true, permissions = permissions))
                } else {
                    call.respond(HttpStatusCode.Forbidden, CheckAccessResponse(valid = false, reason = "Session revoked or inactive."))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, CheckAccessResponse(valid = false, reason = (e.localizedMessage ?: "Access check failed")))
        }
    }
}

fun Route.adminLoginControlRoutes(dbConnectionProvider: () -> Connection) {
    get("/api/staff/login-records") {
        try {
            dbConnectionProvider().use { conn ->
                val records = mutableListOf<LoginRecordDto>()
                val sql = "SELECT * FROM login"
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        while (rs.next()) {
                            val entryCode = rs.getString("entrycode")?.trim().orEmpty()
                            val verifyCode = rs.getString("verifycode")?.trim().orEmpty()
                            val permissions = rs.getString("permissions")?.trim().orEmpty()
                            val veryfynumber = rs.getInt("veryfynumber")

                            var empName = entryCode
                            try {
                                conn.prepareStatement("SELECT employee_name_3 FROM MasterData WHERE UPPER(TRIM(Code_2)) = UPPER(TRIM(?)) LIMIT 1").use { ps ->
                                    ps.setString(1, entryCode)
                                    ps.executeQuery().use { empRs ->
                                        if (empRs.next()) {
                                            empName = empRs.getString("employee_name_3")?.trim().orEmpty().ifBlank { entryCode }
                                        }
                                    }
                                }
                            } catch (_: Exception) {}

                            val masked = when {
                                verifyCode.isBlank() -> "Not Set"
                                verifyCode.length <= 4 -> "****"
                                else -> "****" + verifyCode.takeLast(4)
                            }

                            records.add(
                                LoginRecordDto(
                                    entryCode = entryCode,
                                    employeeName = empName,
                                    maskedVerifyCode = masked,
                                    permissions = permissions,
                                    veryfynumber = veryfynumber
                                )
                            )
                        }
                    }
                }
                call.respond(HttpStatusCode.OK, records)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Failed to fetch login records")))
        }
    }

    post("/api/staff/update-access") {
        try {
            val req = call.receive<UpdateAccessRequest>()
            val cleanCode = req.entryCode.trim()
            if (cleanCode.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Entry code cannot be empty"))
                return@post
            }
            if (req.veryfynumber !in 0..2) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid veryfynumber (must be 0, 1, or 2)"))
                return@post
            }

            val normalizedPerms = req.permissions.uppercase().toCharArray().filter { it in 'A'..'Z' }.toSet().sorted().joinToString("")
            val newCode = req.newVerifyCode?.trim()

            dbConnectionProvider().use { conn ->
                val checkSql = "SELECT COUNT(*) FROM login WHERE UPPER(TRIM(entrycode)) = UPPER(TRIM(?))"
                val exists = conn.prepareStatement(checkSql).use { ps ->
                    ps.setString(1, cleanCode)
                    ps.executeQuery().use { rs -> rs.next() && rs.getInt(1) > 0 }
                }

                if (!exists) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Login record not found for entryCode: $cleanCode"))
                    return@post
                }

                val updateSql = if (!newCode.isNullOrBlank()) {
                    "UPDATE login SET permissions = ?, veryfynumber = ?, verifycode = ? WHERE UPPER(TRIM(entrycode)) = UPPER(TRIM(?))"
                } else {
                    "UPDATE login SET permissions = ?, veryfynumber = ? WHERE UPPER(TRIM(entrycode)) = UPPER(TRIM(?))"
                }

                conn.prepareStatement(updateSql).use { ps ->
                    ps.setString(1, normalizedPerms)
                    ps.setInt(2, req.veryfynumber)
                    if (!newCode.isNullOrBlank()) {
                        ps.setString(3, newCode)
                        ps.setString(4, cleanCode)
                    } else {
                        ps.setString(3, cleanCode)
                    }
                    ps.execute()
                }

                call.respond(HttpStatusCode.OK, UpdateAccessResponse(updated = true, message = "Access updated successfully"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Failed to update access")))
        }
    }
}