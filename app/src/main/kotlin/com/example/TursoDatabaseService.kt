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
                // Using central StaffRepository parser
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

    fun getSalaryHistoryByEmpIdLast4(rawEmpId: String): List> {
        val conn = getConnection() ?: return emptyList()
        val cleanEmpId = rawEmpId.trim()
        
        if (cleanEmpId.length < 4) return emptyList()
        val last4Digits = cleanEmpId.takeLast(4)

        var matchedEmpId = cleanEmpId
        var matchedEmpName = ""
        
        try {
            val stmtMaster = conn.createStatement()
            val rsMaster = stmtMaster.executeQuery("SELECT * FROM MasterData;")
            while (rsMaster.next()) {
                val dto = StaffRepository.parseMasterDataDto(rsMaster)
                if (dto.empId.trim().endsWith(last4Digits)) {
                    matchedEmpId = dto.empId
                    matchedEmpName = dto.employeeName
                    break
                }
            }
            rsMaster.close()
            stmtMaster.close()
        } catch (_: Exception) {}

        val resultList = mutableListOf>()

        return try {
            val stmt = conn.createStatement()
            val rs = stmt.executeQuery("SELECT * FROM SalaryHistory;")

            while (rs.next()) {
                val metaData = rs.metaData
                val count = metaData.columnCount
                val map = mutableMapOf<String, String>()

                for (i in 1..count) {
                    val colName = metaData.getColumnName(i).lowercase().trim()
                    val colValue = rs.getString(i) ?: ""
                    map[colName] = colValue
                }

                fun getValLocal(vararg keys: String): String {
                    for (key in keys) {
                        val value = map[key.lowercase()]
                        if (!value.isNullOrBlank()) return value
                    }
                    return ""
                }

                val rowEmpCode = getValLocal("emp_id_2", "empolyee_code", "emp_id", "code")
                val rowEmpName = getValLocal("employee_name_3", "employee_name")

                val isMatch = if (rowEmpCode.isNotBlank()) {
                    rowEmpCode.trim().endsWith(last4Digits)
                } else {
                    false
                }

                if (isMatch) {
                    val finalEmpId = if (rowEmpCode.isNotBlank()) rowEmpCode else matchedEmpId
                    val finalEmpName = if (rowEmpName.isNotBlank()) rowEmpName else matchedEmpName

                    val dtoMap = mapOf(
                        "id" to getValLocal("id"),
                        "month" to getValLocal("month"),
                        "sl_no_1" to getValLocal("sl_no_1", "slno", "sl_no"),
                        "emp_id_2" to finalEmpId,
                        "employee_name_3" to finalEmpName,
                        "designation_7" to getValLocal("designation_7", "designation"),
                        "department_8" to getValLocal("department_8", "department"),
                        "branch_name_9" to getValLocal("branch_name_9", "branch", "branch_name"),
                        "portfolio_7" to getValLocal("portfolio_7", "portfolio"),
                        "paid_days_8" to getValLocal("paid_days_8", "paid_days"),
                        "basic_da_9" to getValLocal("basic_da_9", "basic__da"),
                        "hra_10" to getValLocal("hra_10", "hra"),
                        "conveyance_11" to getValLocal("conveyance_11", "conveyance"),
                        "medical_allowance_12" to getValLocal("medical_allowance_12", "medical_allowance"),
                        "education_allowance_13" to getValLocal("education_allowance_13", "education_allowance"),
                        "lunch_canteen_allownance_14" to getValLocal("lunch_canteen_allownance_14", "lunchcanteen_allownance"),
                        "special_allowance_15" to getValLocal("special_allowance_15", "special_allowance"),
                        "hravariable_39" to getValLocal("hravariable_39", "hravariable"),
                        "other_allowance_17" to getValLocal("other_allowance_17", "other_allowance"),
                        "daaosa_40" to getValLocal("daaosa_40", "daaosa"),
                        "arrears_increment_41" to getValLocal("arrears_increment_41", "arrears_increment"),
                        "epf55_49" to getValLocal("epf55_49", "epf"),
                        "esi56_50" to getValLocal("esi56_50", "esic"),
                        "professional_tax_22" to getValLocal("professional_tax_22", "professional_tax"),
                        "tds_42" to getValLocal("tds_42", "tds"),
                        "staff_loan_43" to getValLocal("staff_loan_43", "staff_loan"),
                        "staff_advance_44" to getValLocal("staff_advance_44", "staff_advance"),
                        "others_45" to getValLocal("others_45", "others"),
                        "other_earnings_46" to getValLocal("other_earnings_46", "other_earnings"),
                        "gross_earnings_28" to getValLocal("gross_earnings_28", "gross_earnings"),
                        "gross_deductions_29" to getValLocal("gross_deductions_29", "gross_deductions"),
                        "net_amount_30" to getValLocal("net_amount_30", "net_amount"),
                        "status_00" to getValLocal("status_00", "status"),
                        "bank_account_number_20" to getValLocal("bank_account_number_20", "ac_no"),
                        "ifsc_code_bank_branch_name_21" to getValLocal("ifsc_code_bank_branch_name_21", "ifsc"),
                        "bank_name_19" to getValLocal("bank_name_19", "bank"),
                        "backsalarygive" to getValLocal("backsalarygive", "back")
                    )
                    resultList.add(dtoMap)
                }
            }
            rs.close()
            stmt.close()
            
            resultList.takeLast(20).reversed()
        } catch (e: Exception) {
            logger.error("Error fetching salary history: ${e.message}", e)
            emptyList()
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    fun getSalaryHistoryLast20(rawEmpId: String): List> {
        val conn = getConnection() ?: return emptyList()
        val cleanEmpId = rawEmpId.trim()
        
        if (cleanEmpId.length < 4) return emptyList()
        val last4Digits = cleanEmpId.takeLast(4)
        val searchPattern = "%$last4Digits"

        val resultList = mutableListOf>()
        
        val sql = """
            SELECT * FROM SalaryHistory 
            WHERE LOWER(emp_id_2) LIKE LOWER(?) 
            ORDER BY rowid DESC 
            LIMIT 20;
        """.trimIndent()

        return try {
            val pstmt = conn.prepareStatement(sql)
            pstmt.setString(1, searchPattern)
            val rs = pstmt.executeQuery()

            val metaData = rs.metaData
            val count = metaData.columnCount

            while (rs.next()) {
                val map = mutableMapOf<String, String>()
                for (i in 1..count) {
                    val colName = metaData.getColumnName(i).lowercase().trim()
                    map[colName] = rs.getString(i) ?: ""
                }

                fun getValLocal(vararg keys: String): String {
                    for (key in keys) {
                        val value = map[key.lowercase()]
                        if (!value.isNullOrBlank()) return value
                    }
                    return ""
                }

                val dtoMap = mapOf(
                    "id" to getValLocal("id"),
                    "month" to getValLocal("month"),
                    "sl_no_1" to getValLocal("sl_no_1", "slno", "sl_no"),
                    "emp_id_2" to getValLocal("emp_id_2", "emp_id", "empolyee_code", "code"),
                    "employee_name_3" to getValLocal("employee_name_3", "employee_name"),
                    "designation_7" to getValLocal("designation_7", "designation"),
                    "department_8" to getValLocal("department_8", "department"),
                    "branch_name_9" to getValLocal("branch_name_9", "branch", "branch_name"),
                    "portfolio_7" to getValLocal("portfolio_7", "portfolio"),
                    "paid_days_8" to getValLocal("paid_days_8", "paid_days"),
                    "basic_da_9" to getValLocal("basic_da_9", "basic__da"),
                    "hra_10" to getValLocal("hra_10", "hra"),
                    "conveyance_11" to getValLocal("conveyance_11", "conveyance"),
                    "medical_allowance_12" to getValLocal("medical_allowance_12", "medical_allowance"),
                    "education_allowance_13" to getValLocal("education_allowance_13", "education_allowance"),
                    "lunch_canteen_allownance_14" to getValLocal("lunch_canteen_allownance_14", "lunchcanteen_allownance"),
                    "special_allowance_15" to getValLocal("special_allowance_15", "special_allowance"),
                    "hravariable_39" to getValLocal("hravariable_39", "hravariable"),
                    "other_allowance_17" to getValLocal("other_allowance_17", "other_allowance"),
                    "daaosa_40" to getValLocal("daaosa_40", "daaosa"),
                    "arrears_increment_41" to getValLocal("arrears_increment_41", "arrears_increment"),
                    "epf55_49" to getValLocal("epf55_49", "epf"),
                    "esi56_50" to getValLocal("esi56_50", "esic"),
                    "professional_tax_22" to getValLocal("professional_tax_22", "professional_tax"),
                    "tds_42" to getValLocal("tds_42", "tds"),
                    "staff_loan_43" to getValLocal("staff_loan_43", "staff_loan"),
                    "staff_advance_44" to getValLocal("staff_advance_44", "staff_advance"),
                    "others_45" to getValLocal("others_45", "others"),
                    "other_earnings_46" to getValLocal("other_earnings_46", "other_earnings"),
                    "gross_earnings_28" to getValLocal("gross_earnings_28", "gross_earnings"),
                    "gross_deductions_29" to getValLocal("gross_deductions_29", "gross_deductions"),
                    "net_amount_30" to getValLocal("net_amount_30", "net_amount"),
                    "status_00" to getValLocal("status_00", "status"),
                    "bank_account_number_20" to getValLocal("bank_account_number_20", "ac_no"),
                    "ifsc_code_bank_branch_name_21" to getValLocal("ifsc_code_bank_branch_name_21", "ifsc"),
                    "bank_name_19" to getValLocal("bank_name_19", "bank"),
                    "backsalarygive" to getValLocal("backsalarygive", "back")
                )
                resultList.add(dtoMap)
            }
            rs.close()
            pstmt.close()
            resultList.reversed()
        } catch (e: Exception) {
            logger.error("Error fetching salary history for empId pattern $last4Digits: ${e.message}", e)
            emptyList()
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