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
                if (searchTerms.isNotEmpty()) {
                    for (term in searchTerms) {
                        if (!searchableText.contains(term)) {
                            isMatch = false
                            break
                        }
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

    // 1. Branch Name ରେ Active Staff (Score 3) Filter
    fun getActiveStaffByBranch(branchQuery: String): List<MasterDataDto> {
        if (branchQuery.isBlank()) return emptyList()
        val allRecords = searchRecords("") 
        return allRecords.filter { dto ->
            val isActive = calculateStatusScore(dto) == 3
            val isBranchMatch = dto.branchName.trim().lowercase().contains(branchQuery.lowercase())
            isActive && isBranchMatch
        }
    }

    // 2. Designation ରେ Active Staff (Score 3) Filter
    fun getActiveStaffByDesignation(desgQuery: String): List<MasterDataDto> {
        if (desgQuery.isBlank()) return emptyList()
        val allRecords = searchRecords("") 
        return allRecords.filter { dto ->
            val isActive = calculateStatusScore(dto) == 3
            val isDesgMatch = dto.designation.trim().lowercase().contains(desgQuery.lowercase())
            isActive && isDesgMatch
        }
    }

    // 3. Last 20 Records
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

    // 4. Emp ID last 4 digits (ADKR1234 / AMPL1234 -> 1234) ଆଧାରରେ SalaryHistory ରୁ ୨୪ ଟି ରେକର୍ଡ
    fun getSalaryHistoryByEmpIdLast4(rawEmpId: String): List<Map<String, String>> {
        val conn = getConnection() ?: return emptyList()
        val cleanEmpId = rawEmpId.trim()
        
        if (cleanEmpId.length < 4) return emptyList()
        val last4Digits = cleanEmpId.takeLast(4)

        val resultList = mutableListOf<Map<String, String>>()

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

                val empIdValue = map["emp_id_2"] ?: map["emp_id"] ?: map["code"] ?: ""

                if (empIdValue.trim().endsWith(last4Digits)) {
                    val dtoMap = mapOf(
                        "month" to (map["month"] ?: map["salary_month"] ?: ""),
                        "sl_no_1" to (map["sl_no_1"] ?: map["sl_no"] ?: ""),
                        "emp_id_2" to empIdValue,
                        "employee_name_3" to (map["employee_name_3"] ?: map["employee_name"] ?: ""),
                        "designation_7" to (map["designation_7"] ?: map["designation"] ?: ""),
                        "department_8" to (map["department_8"] ?: map["department"] ?: ""),
                        "branch_name_9" to (map["branch_name_9"] ?: map["branch"] ?: ""),
                        "portfolio_7" to (map["portfolio_7"] ?: map["portfolio"] ?: ""),
                        "paid_days_8" to (map["paid_days_8"] ?: map["paid_days"] ?: ""),
                        "basic_da_9" to (map["basic_da_9"] ?: map["basic_da"] ?: ""),
                        "hra_10" to (map["hra_10"] ?: map["hra"] ?: ""),
                        "conveyance_11" to (map["conveyance_11"] ?: map["conveyance"] ?: ""),
                        "medical_allowance_12" to (map["medical_allowance_12"] ?: map["medical_allowance"] ?: ""),
                        "education_allowance_13" to (map["education_allowance_13"] ?: map["education_allowance"] ?: ""),
                        "lunch_canteen_allownance_14" to (map["lunch_canteen_allownance_14"] ?: map["lunch_canteen_allowance"] ?: ""),
                        "special_allowance_15" to (map["special_allowance_15"] ?: map["special_allowance"] ?: ""),
                        "hravariable_39" to (map["hravariable_39"] ?: map["hra_variable_16"] ?: ""),
                        "other_allowance_17" to (map["other_allowance_17"] ?: map["other_allowance"] ?: ""),
                        "daaosa_40" to (map["daaosa_40"] ?: map["daa_osa_18"] ?: ""),
                        "arrears_increment_41" to (map["arrears_increment_41"] ?: map["arrears_increment_19"] ?: ""),
                        "epf55_49" to (map["epf55_49"] ?: map["epf_20"] ?: ""),
                        "esi56_50" to (map["esi56_50"] ?: map["esic_21"] ?: ""),
                        "professional_tax_22" to (map["professional_tax_22"] ?: map["pt"] ?: ""),
                        "tds_42" to (map["tds_42"] ?: map["tds_23"] ?: ""),
                        "staff_loan_43" to (map["staff_loan_43"] ?: map["staff_loan_24"] ?: ""),
                        "staff_advance_44" to (map["staff_advance_44"] ?: map["staff_advance_25"] ?: ""),
                        "others_45" to (map["others_45"] ?: map["others_26"] ?: ""),
                        "other_earnings_46" to (map["other_earnings_46"] ?: map["other_earnings_27"] ?: ""),
                        "gross_earnings_28" to (map["gross_earnings_28"] ?: map["gross_earnings"] ?: ""),
                        "gross_deductions_29" to (map["gross_deductions_29"] ?: map["gross_deductions"] ?: ""),
                        "net_amount_30" to (map["net_amount_30"] ?: map["net_amount"] ?: ""),
                        "Status_00" to (map["status_00"] ?: map["status_31"] ?: map["status"] ?: ""),
                        "bank_account_number_20" to (map["bank_account_number_20"] ?: map["ac_no_32"] ?: ""),
                        "ifsc_code_bank_branch_name_21" to (map["ifsc_code_bank_branch_name_21"] ?: map["ifsc_33"] ?: ""),
                        "bank_name_19" to (map["bank_name_19"] ?: map["bank_34"] ?: ""),
                        "backSalarygive" to (map["backsalarygive"] ?: map["sal_h_48"] ?: map["back_35"] ?: "")
                    )
                    resultList.add(dtoMap)
                }
            }
            rs.close()
            stmt.close()
            
            resultList.takeLast(24).reversed()
        } catch (e: Exception) {
            logger.error("Error fetching salary history: ${e.message}", e)
            emptyList()
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }
}