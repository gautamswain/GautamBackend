package com.example

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
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
        authToken = if (envToken.isNotBlank()) envToken else "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJpYXQiOjE3OTI5MzM2NTIsImlkIjoiMDFhMTczMGItZGYwMS03ODc3LWEwZTctZDI4MGY3ODczYmMzIiwia2lkIjoiSFNDdE1aWHdidGJ4allfeERJMWV2ZjBERHJ3WU1odlVyb0k4dnBHX25EayIsInJpZCI6ImUzNzM5ODAzLWE2NjEtNDlhNS1hMmFkLTg4YzMzNDk2Y2FmNiJ9.HXc7gy5g_F_qilsgjsSC1aG_GrV1-TvX-3i6Py1EwAM77R6EuUpB4_0pmnE5215WIIbk3sD7lqI72lTF3mo8Cg"

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
            initErrorLog = "INITIALIZED"
            logger.info("Turso driver initialized with host: $cleanUrl")
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
            val stmt = conn.createStatement()
            val rs = stmt.executeQuery("SELECT * FROM MasterData;")

            while (rs.next()) {
                val dto = StaffRepository.parseMasterDataDto(rs)
                val searchableText = "${dto.empId} ${dto.employeeName} ${dto.personalContactNumber} ${dto.contactNo2} ${dto.branchName} ${dto.designation}".lowercase()

                var isMatch = true
                if (searchTerms.isNotEmpty()) {
                    for (term in searchTerms) {
                        if (!searchableText.contains(term)) {
                            isMatch = false
                            break
                        }
                    }
                }

                if (isMatch) {
                    resultList.add(dto)
                }
            }
            rs.close()
            stmt.close()
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
        val allRecords = searchRecords("") 
        return allRecords.filter { dto ->
            val isActive = calculateStatusScore(dto) > 1
            val isBranchMatch = dto.branchName.trim().lowercase().contains(branchQuery.lowercase())
            isActive && isBranchMatch
        }
    }

    fun getActiveStaffByDesignation(designationQuery: String): List<MasterDataDto> {
        if (designationQuery.isBlank()) return emptyList()
        val allRecords = searchRecords("") 
        return allRecords.filter { dto ->
            val isActive = calculateStatusScore(dto) > 1
            val isDesignationMatch = dto.designation.trim().lowercase().contains(designationQuery.lowercase())
            isActive && isDesignationMatch
        }
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

    // ଛୋଟ ଏବଂ ସରଳ କରାଯାଇଥିବା Salary History Fetching
    fun getSalaryHistoryByEmpIdLast4(rawEmpId: String): List<SalaryDto> {
        val conn = getConnection() ?: return emptyList()
        val cleanEmpId = rawEmpId.trim()
        if (cleanEmpId.length < 4) return emptyList()
        val last4Digits = cleanEmpId.takeLast(4)

        val resultList = mutableListOf<SalaryDto>()
        try {
            val stmt = conn.createStatement()
            val rs = stmt.executeQuery("SELECT * FROM SalaryHistory;")
            while (rs.next()) {
                val dto = parseSalaryDto(rs)
                val rowEmpCode = dto.empId2.trim()
                if (rowEmpCode.isNotBlank() && rowEmpCode.endsWith(last4Digits)) {
                    resultList.add(dto)
                }
            }
            rs.close()
            stmt.close()
            return resultList.takeLast(20).reversed()
        } catch (e: Exception) {
            logger.error("Error fetching salary history: ${e.message}", e)
            return emptyList()
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
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