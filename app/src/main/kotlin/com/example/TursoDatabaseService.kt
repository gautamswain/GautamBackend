package com.example

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

// Explicitly mapping Google Sheet synced column names
object MasterDataTable : Table("MasterData") {
    val id = integer("id").autoIncrement()
    val empId = varchar("Emp. ID", 100) // 👈 Matches exact Google Sheet header
    val employeeName = varchar("EMPLOYEE NAME", 255) // 👈 Matches exact Google Sheet header
    val designation = varchar("DESIGNATION", 255) // 👈 Matches exact Google Sheet header
    val department = varchar("DEPARTMENT", 255)
    val branchName = varchar("BRANCH NAME", 255)
    val contactNo = varchar("Personal Contact Number", 100)
    val panNo = varchar("PAN Card No.", 100)
    val officeEmail = varchar("Mail ID - Office", 255)

    override val primaryKey = PrimaryKey(id)
}

object TursoDatabaseService {
    private val logger = LoggerFactory.getLogger(TursoDatabaseService::class.java)
    private var isConnected = false

    fun init() {
        val rawUrl = System.getenv("TURSO_DATABASE_URL") ?: ""
        val token = System.getenv("TURSO_AUTH_TOKEN") ?: ""

        if (rawUrl.isBlank() || token.isBlank()) {
            logger.warn("TURSO_DATABASE_URL or TURSO_AUTH_TOKEN missing in Render environment variables!")
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
            logger.info("Turso database successfully initialized.")
        } catch (e: Exception) {
            logger.error("Failed to connect to Turso: ${e.message}", e)
        }
    }

    fun getRecordByEmpId(targetEmpId: String): MasterDataDto? {
        if (!isConnected) return null

        val cleanSearchId = targetEmpId.trim()

        return try {
            transaction {
                MasterDataTable.select { 
                    MasterDataTable.empId.lowerCase() eq cleanSearchId.lowercase() 
                }
                .map { row ->
                    MasterDataDto(
                        id = row[MasterDataTable.id],
                        empId = row[MasterDataTable.empId],
                        employeeName = row[MasterDataTable.employeeName],
                        designation = row[MasterDataTable.designation]
                    )
                }
                .singleOrNull()
            }
        } catch (e: Exception) {
            logger.error("Error querying empId $targetEmpId: ${e.message}", e)
            null
        }
    }
}