package com.example

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

object TursoDatabaseService {
    private val logger = LoggerFactory.getLogger(TursoDatabaseService::class.java)
    private var isConnected = false

    fun init() {
        val rawUrl = System.getenv("TURSO_DATABASE_URL") ?: ""
        val token = System.getenv("TURSO_AUTH_TOKEN") ?: ""

        if (rawUrl.isBlank() || token.isBlank()) {
            logger.warn("TURSO_DATABASE_URL or TURSO_AUTH_TOKEN is missing in environment variables!")
            return
        }

        val cleanUrl = rawUrl
            .removePrefix("jdbc:sqlite:")
            .removePrefix("jdbc:dbeaver:libsql:")
            .removePrefix("libsql://")
            .removePrefix("https://")

        try {
            val hikariConfig = HikariConfig().apply {
                this.jdbcUrl = "jdbc:dbeaver:libsql:https://$cleanUrl"
                this.password = token
                this.driverClassName = "com.dbeaver.jdbc.driver.libsql.LibSqlDriver"
                this.maximumPoolSize = 5
                this.isAutoCommit = true
                this.connectionTimeout = 10000
            }

            val dataSource = HikariDataSource(hikariConfig)
            Database.connect(dataSource)
            isConnected = true
            logger.info("Turso database initialized successfully.")
        } catch (e: Exception) {
            logger.error("Failed to initialize Turso database connection: ${e.message}", e)
        }
    }

    fun getRecordByEmpId(targetEmpId: String): MasterDataDto? {
        if (!isConnected) return null

        val searchId = targetEmpId.trim()

        return try {
            transaction {
                // Using raw SQL handles column names with spaces/dots smoothly ("Emp. ID", "EMPLOYEE NAME", "DESIGNATION")
                val query = """
                    SELECT * FROM MasterData 
                    WHERE TRIM(LOWER("Emp. ID")) = LOWER('$searchId') 
                       OR TRIM(LOWER("emp_id")) = LOWER('$searchId')
                    LIMIT 1
                """.trimIndent()

                var resultDto: MasterDataDto? = null

                exec(query) { rs ->
                    if (rs.next()) {
                        // Extract with column name fallbacks
                        val idVal = try { rs.getInt("id") } catch (_: Exception) { 1 }
                        
                        val empIdVal = try { rs.getString("Emp. ID") } 
                                       catch (_: Exception) { rs.getString("emp_id") } ?: searchId

                        val nameVal = try { rs.getString("EMPLOYEE NAME") } 
                                      catch (_: Exception) { rs.getString("employee_name") } ?: ""

                        val designationVal = try { rs.getString("DESIGNATION") } 
                                             catch (_: Exception) { rs.getString("designation") } ?: ""

                        resultDto = MasterDataDto(
                            id = idVal,
                            empId = empIdVal.trim(),
                            employeeName = nameVal.trim(),
                            designation = designationVal.trim()
                        )
                    }
                }
                resultDto
            }
        } catch (e: Exception) {
            logger.error("Error executing query for empId $targetEmpId: ${e.message}", e)
            null
        }
    }
}