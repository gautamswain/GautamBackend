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

    private fun getConnection(): Connection? {
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

    // Status Score Formula (0 to 3) Calculate କରିବା Helper
    fun calculateStatusScore(dto: MasterDataDto): Int {
        val s1 = if (dto.empStatus.trim() == "1" || dto.empStatus.trim().equals("active", ignoreCase = true)) 1 else 0
        val s2 = if (dto.empStatusTemp.trim() == "1" || dto.empStatusTemp.trim().equals("active", ignoreCase = true)) 1 else 0
        val s3 = if (dto.salaryStatus.trim() == "1" || dto.salaryStatus.trim().equals("active", ignoreCase = true)) 1 else 0
        return s1 + s2 + s3
    }

    // Standard Multi-Term Search
    fun searchRecords(query: String): List<MasterDataDto> {
        val conn = getConnection() ?: return emptyList()

        val searchTerms = query.trim().lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        if (searchTerms.isEmpty()) return emptyList()

        val resultList = mutableListOf<MasterDataDto>()

        return try {
            val stmt = conn.createStatement()
            val rs = stmt.executeQuery("SELECT * FROM MasterData;")

            while (rs.next()) {
                val metaData = rs.metaData
                val count = metaData.columnCount
                val map = mutableMapOf<String, String>()

                for (i in 1..count) {
                    val colName = metaData.getColumnName(i).lowercase().trim()
                    val colValue = rs.getString(i) ?: ""
                    map[colName] = colValue
                }

                fun getVal(vararg keys: String): String {
                    for (key in keys) {
                        val value = map[key.lowercase()]
                        if (!value.isNullOrBlank()) return value
                    }
                    return ""
                }

                val empId = getVal("emp_id_2", "emp_id", "code").lowercase()
                val empName = getVal("employee_name_3", "employee_name").lowercase()
                val phone1 = getVal("personal_contact_number_14", "personal_contact_number").lowercase()
                val phone2 = getVal("contact_no_2_15", "contact_no_2").lowercase()
                val branch = getVal("branch_name_9", "branch_name").lowercase()
                val designation = getVal("designation_7", "designation").lowercase()

                val searchableText = "$empId $empName $phone1 $phone2 $branch $designation"

                var isMatch = true
                for (term in searchTerms) {
                    if (!searchableText.contains(term)) {
                        isMatch = false
                        break
                    }
                }

                if (isMatch) {
                    val dto = MasterDataDto(
                        id = getVal("id"),
                        slNo = getVal("sl_no_1", "sl_no"),
                        empId = getVal("emp_id_2", "emp_id", "code"),
                        employeeName = getVal("employee_name_3", "employee_name"),
                        fathersName = getVal("fathers_name_4", "fathers_name"),
                        gender = getVal("gender_5", "gender"),
                        dob = getVal("dob_6", "dob"),
                        designation = getVal("designation_7", "designation"),
                        department = getVal("department_8", "department"),
                        branchName = getVal("branch_name_9", "branch_name"),
                        dojInAdhikar = getVal("doj_in_adhikar_10", "doj_in_adhikar"),
                        empStatus = getVal("emp_status_35", "emp_status"),
                        empStatusTemp = getVal("emp_status_temp_36", "emp_status_temp"),
                        salaryStatus = getVal("salary_status"),
                        personalContactNumber = getVal("personal_contact_number_14", "personal_contact_number"),
                        contactNo2 = getVal("contact_no_2_15", "contact_no_2"),
                        aadharCardNo = getVal("aadhar_card_no_16", "aadhar_card_no"),
                        panCardNo = getVal("pan_card_no_17", "pan_card_no"),
                        bankName = getVal("bank_name_19", "bank_name"),
                        bankAccountNumber = getVal("bank_account_number_20", "bank_account_number"),
                        ifscCodeBankBranchName = getVal("ifsc_code_bank_branch_name_21", "ifsc_code_bank_branch_name"),
                        esiNo = getVal("esi_no_22", "esi_no"),
                        epfUanNo = getVal("epf_uan_no_23", "epf_uan_no"),
                        uanName = getVal("uan_name_24", "uan_name")
                    )
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

    // 1. Branch Name ରେ କେବଳ Active Staff (Score 3) Filter
    fun getActiveStaffByBranch(branchQuery: String): List<MasterDataDto> {
        val allRecords = searchRecords(branchQuery)
        return allRecords.filter { dto ->
            calculateStatusScore(dto) == 3 && dto.branchName.lowercase().contains(branchQuery.lowercase())
        }
    }

    // 2. Designation ରେ କେବଳ Active Staff (Score 3) Filter
    fun getActiveStaffByDesignation(desgQuery: String): List<MasterDataDto> {
        val allRecords = searchRecords(desgQuery)
        return allRecords.filter { dto ->
            calculateStatusScore(dto) == 3 && dto.designation.lowercase().contains(desgQuery.lowercase())
        }
    }

    // 3. Database ରୁ Last 20 Records
    fun getLast20Records(): List<MasterDataDto> {
        val conn = getConnection() ?: return emptyList()
        val resultList = mutableListOf<MasterDataDto>()

        return try {
            val stmt = conn.createStatement()
            val rs = stmt.executeQuery("SELECT * FROM MasterData ORDER BY ROWID DESC LIMIT 20;")

            while (rs.next()) {
                val map = mutableMapOf<String, String>()
                val metaData = rs.metaData
                for (i in 1..metaData.columnCount) {
                    val colName = metaData.getColumnName(i).lowercase().trim()
                    map[colName] = rs.getString(i) ?: ""
                }

                fun getVal(vararg keys: String): String {
                    for (key in keys) {
                        val value = map[key.lowercase()]
                        if (!value.isNullOrBlank()) return value
                    }
                    return ""
                }

                val dto = MasterDataDto(
                    id = getVal("id"),
                    slNo = getVal("sl_no_1", "sl_no"),
                    empId = getVal("emp_id_2", "emp_id", "code"),
                    employeeName = getVal("employee_name_3", "employee_name"),
                    fathersName = getVal("fathers_name_4", "fathers_name"),
                    gender = getVal("gender_5", "gender"),
                    dob = getVal("dob_6", "dob"),
                    designation = getVal("designation_7", "designation"),
                    department = getVal("department_8", "department"),
                    branchName = getVal("branch_name_9", "branch_name"),
                    dojInAdhikar = getVal("doj_in_adhikar_10", "doj_in_adhikar"),
                    empStatus = getVal("emp_status_35", "emp_status"),
                    empStatusTemp = getVal("emp_status_temp_36", "emp_status_temp"),
                    salaryStatus = getVal("salary_status"),
                    personalContactNumber = getVal("personal_contact_number_14", "personal_contact_number"),
                    contactNo2 = getVal("contact_no_2_15", "contact_no_2"),
                    aadharCardNo = getVal("aadhar_card_no_16", "aadhar_card_no"),
                    panCardNo = getVal("pan_card_no_17", "pan_card_no"),
                    bankName = getVal("bank_name_19", "bank_name"),
                    bankAccountNumber = getVal("bank_account_number_20", "bank_account_number"),
                    ifscCodeBankBranchName = getVal("ifsc_code_bank_branch_name_21", "ifsc_code_bank_branch_name"),
                    esiNo = getVal("esi_no_22", "esi_no"),
                    epfUanNo = getVal("epf_uan_no_23", "epf_uan_no"),
                    uanName = getVal("uan_name_24", "uan_name")
                )
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
}