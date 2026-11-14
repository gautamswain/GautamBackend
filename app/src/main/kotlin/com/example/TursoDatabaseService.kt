package com.example

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

object TursoDatabaseService {
    private val logger = LoggerFactory.getLogger(TursoDatabaseService::class.java)
    var initErrorLog: String = "NOT_STARTED"
        private set

    private var jdbcUrl: String = ""
    private var authToken: String = ""

    fun init() {
        val envUrl = System.getenv("TURSO_DATABASE_URL")?.trim() ?: ""
        val envToken = System.getenv("TURSO_AUTH_TOKEN")?.trim() ?: ""

        val rawUrl = if (envUrl.isNotBlank()) envUrl else "https://adhikarhr-gautamswain.aws-ap-south-1.turso.io"
        authToken = envToken
        if (authToken.isBlank()) {
            initErrorLog = "TURSO_AUTH_TOKEN missing"
        }

        val cleanUrl = rawUrl
            .removePrefix("jdbc:sqlite:")
            .removePrefix("jdbc:dbeaver:libsql:")
            .removePrefix("libsql://")
            .removePrefix("https://")
            .split("]")
            .first()
            .trim()

        jdbcUrl = "jdbc:dbeaver:libsql:https://$cleanUrl"

        try {
            Class.forName("com.dbeaver.jdbc.driver.libsql.LibSqlDriver")
            if (authToken.isBlank()) {
                logger.warn("Turso driver initialized, but TURSO_AUTH_TOKEN is missing!")
            } else {
                initErrorLog = "INITIALIZED"
                logger.info("Turso driver initialized with host: $cleanUrl")
            }
        } catch (e: Exception) {
            initErrorLog = "Driver Load Error: ${e.message}"
            logger.error("Failed to load LibSqlDriver: ${e.message}", e)
        }
    }

    fun getConnection(): Connection? {
        return try {
            val props = Properties().apply {
                setProperty("password", authToken)
                setProperty("user", "")
            }
            DriverManager.getConnection(jdbcUrl, props)
        } catch (e: Exception) {
            initErrorLog = "Connection Exec Error: ${e.message}"
            logger.error("Error creating direct Turso connection: ${e.message}", e)
            null
        }
    }

    fun getColumns(): List<String> {
        val conn = getConnection() ?: return listOf("ERROR: Direct Connection is NULL. Log: $initErrorLog")
        val result = mutableListOf<String>()
        try {
            val stmt = conn.createStatement()
            val rsTables = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table';")
            while (rsTables.next()) {
                result.add("TABLE: " + rsTables.getString("name"))
            }
            rsTables.close()

            val rsCols = stmt.executeQuery("SELECT * FROM MasterData LIMIT 1;")
            if (rsCols.next()) {
                val metaData = rsCols.metaData
                for (i in 1..metaData.columnCount) {
                    result.add("COL: " + metaData.getColumnName(i))
                }
            }
            rsCols.close()
            stmt.close()
        } catch (e: Exception) {
            logger.error("Error fetching columns: ${e.message}", e)
            result.add("EXCEPTION: ${e.message}")
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
        return result
    }

    fun getRecordByEmpId(targetEmpId: String): MasterDataDto? {
        val results = searchRecords(targetEmpId)
        return results.firstOrNull()
    }

    fun calculateStatusScore(dto: MasterDataDto): Int {
        return StaffRepository.calculateStatusScore(dto)
    }

    fun searchRecords(query: String): List<MasterDataDto> {
        val conn = getConnection() ?: return emptyList()

        val searchTerms = query.trim().lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        val resultList = mutableListOf<MasterDataDto>()

        return try {
            val sql = if (searchTerms.isEmpty()) {
                "SELECT * FROM MasterData ORDER BY ROWID DESC LIMIT 100;"
            } else {
                val conditions = searchTerms.joinToString(" AND ") {
                    "(LOWER(Code_2) LIKE ? OR LOWER(employee_name_3) LIKE ? OR personal_contact_number_14 LIKE ? OR contact_no_2_15 LIKE ? OR LOWER(branch_name_9) LIKE ? OR LOWER(designation_7) LIKE ?)"
                }
                "SELECT * FROM MasterData WHERE $conditions ORDER BY ROWID DESC LIMIT 100;"
            }

            val pstmt = conn.prepareStatement(sql)
            var paramIndex = 1
            if (searchTerms.isNotEmpty()) {
                for (term in searchTerms) {
                    val pattern = "%$term%"
                    repeat(6) {
                        pstmt.setString(paramIndex++, pattern)
                    }
                }
            }

            val rs = pstmt.executeQuery()
            while (rs.next()) {
                resultList.add(StaffRepository.parseMasterDataDto(rs))
            }
            rs.close()
            pstmt.close()
            resultList
        } catch (e: Exception) {
            logger.error("Error searching records for query '$query': ${e.message}", e)
            emptyList()
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    fun getActiveStaffByBranch(branchQuery: String): List<MasterDataDto> {
        if (branchQuery.isBlank()) return emptyList()
        val conn = getConnection() ?: return emptyList()
        val resultList = mutableListOf<MasterDataDto>()
        try {
            val sql = "SELECT * FROM MasterData WHERE LOWER(branch_name_9) LIKE ? LIMIT 200;"
            val pstmt = conn.prepareStatement(sql)
            pstmt.setString(1, "%${branchQuery.trim().lowercase()}%")
            val rs = pstmt.executeQuery()
            while (rs.next()) {
                val dto = StaffRepository.parseMasterDataDto(rs)
                if (calculateStatusScore(dto) > 1) {
                    resultList.add(dto)
                }
            }
            rs.close()
            pstmt.close()
        } catch (e: Exception) {
            logger.error("Error fetching active staff by branch: ${e.message}", e)
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
        return resultList
    }

    fun getActiveStaffByDesignation(designationQuery: String): List<MasterDataDto> {
        if (designationQuery.isBlank()) return emptyList()
        val conn = getConnection() ?: return emptyList()
        val resultList = mutableListOf<MasterDataDto>()
        try {
            val sql = "SELECT * FROM MasterData WHERE LOWER(designation_7) LIKE ? LIMIT 200;"
            val pstmt = conn.prepareStatement(sql)
            pstmt.setString(1, "%${designationQuery.trim().lowercase()}%")
            val rs = pstmt.executeQuery()
            while (rs.next()) {
                val dto = StaffRepository.parseMasterDataDto(rs)
                if (calculateStatusScore(dto) > 1) {
                    resultList.add(dto)
                }
            }
            rs.close()
            pstmt.close()
        } catch (e: Exception) {
            logger.error("Error fetching active staff by designation: ${e.message}", e)
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
        return resultList
    }

    fun getLast20Records(): List<MasterDataDto> {
        val conn = getConnection() ?: return emptyList()
        val resultList = mutableListOf<MasterDataDto>()

        return try {
            val stmt = conn.createStatement()
            val rs = stmt.executeQuery("SELECT * FROM MasterData ORDER BY ROWID DESC LIMIT 20;")

            while (rs.next()) {
                val dto = StaffRepository.parseMasterDataDto(rs)
                resultList.add(dto)
            }
            rs.close()
            stmt.close()
            resultList
        } catch (e: Exception) {
            logger.error("Error fetching last 20 records: ${e.message}", e)
            emptyList()
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    fun getSalaryHistoryByEmpIdLast4(rawEmpId: String): List<SalaryDto> {
        return getSalaryHistoryLast20(rawEmpId)
    }

    fun getSalaryHistoryLast20(rawEmpId: String): List<SalaryDto> {
        val conn = getConnection() ?: return emptyList()
        val cleanEmpId = rawEmpId.trim()
        if (cleanEmpId.length < 4) return emptyList()
        val last4Digits = cleanEmpId.takeLast(4)
        val searchPattern = "%$last4Digits"

        val resultList = mutableListOf<SalaryDto>()
        val sql = "SELECT * FROM SalaryHistory WHERE LOWER(emp_id_2) LIKE LOWER(?) ORDER BY rowid DESC LIMIT 20;"

        try {
            val preparedStatement = conn.prepareStatement(sql)
            preparedStatement.setString(1, searchPattern)
            val rs = preparedStatement.executeQuery()
            while (rs.next()) {
                resultList.add(parseSalaryDto(rs))
            }
            rs.close()
            preparedStatement.close()
            return resultList.reversed()
        } catch (e: Exception) {
            logger.error("Error fetching salary history: ${e.message}", e)
            return emptyList()
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    fun getSalaryTableColumns(): List<String> {
        val conn = getConnection() ?: return listOf("ERROR: Connection is NULL")
        val result = mutableListOf<String>()
        try {
            val stmt = conn.createStatement()
            val rsCols = stmt.executeQuery("SELECT * FROM SalaryHistory LIMIT 1;")
            if (rsCols.next()) {
                val metaData = rsCols.metaData
                for (i in 1..metaData.columnCount) {
                    result.add("COL: " + metaData.getColumnName(i))
                }
            } else {
                result.add("SalaryHistory table is empty")
            }
            rsCols.close()
            stmt.close()
        } catch (e: Exception) {
            result.add("EXCEPTION: ${e.message}")
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
        return result
    }
}