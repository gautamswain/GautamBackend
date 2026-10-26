package com.example

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.sql.Connection

object TursoDatabaseService {
    private val logger = LoggerFactory.getLogger(TursoDatabaseService::class.java)
    private var dataSource: HikariDataSource? = null
    var isConnected = false
        private set

    fun init() {
        val rawUrl = System.getenv("TURSO_DATABASE_URL") ?: ""
        val token = System.getenv("TURSO_AUTH_TOKEN") ?: ""

        if (rawUrl.isBlank() || token.isBlank()) {
            logger.warn("TURSO_DATABASE_URL or TURSO_AUTH_TOKEN missing!")
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

            dataSource = HikariDataSource(hikariConfig)
            isConnected = true
            logger.info("Turso database initialized successfully.")
        } catch (e: Exception) {
            logger.error("Failed to initialize Turso connection: ${e.message}", e)
        }
    }

    fun getRecordByEmpId(targetEmpId: String): MasterDataDto? {
        val ds = dataSource ?: return null
        val searchId = targetEmpId.trim()

        var conn: Connection? = null
        return try {
            conn = ds.connection

            // Dynamic query to match emp_id_2, emp_id or any column containing searchId
            val query = """
                SELECT * FROM MasterData 
                WHERE LOWER(TRIM(coalesce(NULLIF(emp_id_2, ''), NULLIF(emp_id, ''), ''))) = LOWER(?) 
                   OR LOWER(TRIM(coalesce(NULLIF(emp_id_2, ''), NULLIF(emp_id, ''), ''))) LIKE LOWER(?)
                LIMIT 1
            """.trimIndent()

            val stmt = conn.prepareStatement(query)
            stmt.setString(1, searchId)
            stmt.setString(2, "%$searchId%")

            val rs = stmt.executeQuery()
            var dto: MasterDataDto? = null

            if (rs.next()) {
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

                dto = MasterDataDto(
                    id = getVal("id"),
                    slNo = getVal("sl_no_1", "sl_no"),
                    empId = getVal("emp_id_2", "emp_id", "code").ifEmpty { searchId },
                    employeeName = getVal("employee_name_3", "employee_name"),
                    fathersName = getVal("fathers_name_4", "fathers_name"),
                    gender = getVal("gender_5", "gender"),
                    dob = getVal("dob_6", "dob"),
                    designation = getVal("designation_7", "designation"),
                    department = getVal("department_8", "department"),
                    branchName = getVal("branch_name_9", "branch_name"),
                    dojInAdhikar = getVal("doj_in_adhikar_10", "doj_in_adhikar"),
                    presentAddress = getVal("present_address_11", "present_address"),
                    permanentAddress = getVal("permanent_address_12", "permanent_address"),
                    highestEducationalQualification = getVal("highest_educational_qualification_13", "highest_educational_qualification"),
                    personalContactNumber = getVal("personal_contact_number_14", "personal_contact_number"),
                    contactNo2 = getVal("contact_no_2_15", "contact_no_2"),
                    aadharCardNo = getVal("aadhar_card_no_16", "aadhar_card_no"),
                    panCardNo = getVal("pan_card_no_17", "pan_card_no"),
                    driving = getVal("driving_18", "driving"),
                    bankName = getVal("bank_name_19", "bank_name"),
                    bankAccountNumber = getVal("bank_account_number_20", "bank_account_number"),
                    ifscCodeBankBranchName = getVal("ifsc_code_bank_branch_name_21", "ifsc_code_bank_branch_name"),
                    esiNo = getVal("esi_no_22", "esi_no"),
                    epfUanNo = getVal("epf_uan_no_23", "epf_uan_no"),
                    uanName = getVal("uan_name_24", "uan_name"),
                    companyNameStartingFromCurrentToOld = getVal("company_name_starting_from_current_to_old_26"),
                    status = getVal("status_30", "status"),
                    empStatus = getVal("emp_status_35", "emp_status"),
                    salaryCode = getVal("salary_code_47", "salary_code"),
                    lender = getVal("lender_51", "lender"),
                    email61Personal = getVal("email61_personal_52", "email61_personal"),
                    offerApp = getVal("offer_app_53", "offer_app"),
                    insurancesbi = getVal("insurancesbi_54", "insurancesbi"),
                    equifax = getVal("equifax_55", "equifax"),
                    mailIdOffice = getVal("mail_id_office_58", "mail_id_office")
                )
            }
            rs.close()
            stmt.close()
            dto
        } catch (e: Exception) {
            logger.error("Error executing SQL query for empId $targetEmpId: ${e.message}", e)
            null
        } finally {
            conn?.close()
        }
    }
}