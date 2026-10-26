package com.example

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

// Schema representing MasterData table in Turso
object MasterDataTable : Table("MasterData") {
    val id = integer("id").autoIncrement()
    val empId = varchar("emp_id", 100)
    val employeeName = varchar("employee_name", 255)
    val designation = varchar("designation", 255)

    override val primaryKey = PrimaryKey(id)
}

object TursoDatabaseService {
    private val logger = LoggerFactory.getLogger(TursoDatabaseService::class.java)

    fun init() {
        val rawUrl = System.getenv("TURSO_DATABASE_URL") ?: ""
        val token = System.getenv("TURSO_AUTH_TOKEN") ?: ""

        if (rawUrl.isBlank() || token.isBlank()) {
            logger.warn("TURSO_DATABASE_URL or TURSO_AUTH_TOKEN missing in environment variables!")
            return
        }

        // Standardize URL format for libSQL JDBC driver
        val jdbcUrl = when {
            rawUrl.startsWith("libsql://") -> rawUrl.replace("libsql://", "jdbc:sqlite:https://")
            !rawUrl.startsWith("jdbc:sqlite:") -> "jdbc:sqlite:$rawUrl"
            else -> rawUrl
        }

        try {
            val hikariConfig = HikariConfig().apply {
                this.jdbcUrl = "$jdbcUrl?authToken=$token"
                this.driverClassName = "io.turso.libsql.Driver"
                this.maximumPoolSize = 5
                this.isAutoCommit = true
                this.transactionIsolation = "TRANSACTION_SERIALIZABLE"
            }

            val dataSource = HikariDataSource(hikariConfig)
            Database.connect(dataSource)
            logger.info("Turso database initialized successfully.")
        } catch (e: Exception) {
            logger.error("Failed to connect to Turso: ${e.message}", e)
        }
    }

    // Retrieve a single record from MasterData by Employee ID
    fun getRecordByEmpId(targetEmpId: String): MasterDataDto? {
        return transaction {
            MasterDataTable.select { MasterDataTable.empId eq targetEmpId }
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
    }
}