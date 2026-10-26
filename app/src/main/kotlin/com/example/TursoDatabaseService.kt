package com.example

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.sql.Connection

object TursoDatabaseService {
    private val logger = LoggerFactory.getLogger(TursoDatabaseService::class.java)
    private var dataSource: HikariDataSource? = null
    var initErrorLog: String = "NOT_STARTED"
        private set

    fun init() {
        // Fallback credentials if Render environment variables are empty
        val envUrl = System.getenv("TURSO_DATABASE_URL")?.trim() ?: ""
        val envToken = System.getenv("TURSO_AUTH_TOKEN")?.trim() ?: ""

        val rawUrl = if (envUrl.isNotBlank()) envUrl else "https://adhikarhr-gautamswain.aws-ap-south-1.turso.io"
        val token = if (envToken.isNotBlank()) envToken else "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJpYXQiOjE3OTI5MzM2NTIsImlkIjoiMDFhMTczMGItZGYwMS03ODc3LWEwZTctZDI4MGY3ODczYmMzIiwia2lkIjoiSFNDdE1aWHdidGJ4allfeERJMWV2ZjBERHJ3WU1odlVyb0k4dnBHX25EayIsInJpZCI6ImUzNzM5ODAzLWE2NjEtNDlhNS1hMmFkLTg4YzMzNDk2Y2FmNiJ9.HXc7gy5g_F_qilsgjsSC1aG_GrV1-TvX-3i6Py1EwAM77R6EuUpB4_0pmnE5215WIIbk3sD7lqI72lTF3mo8Cg"

        val cleanUrl = rawUrl
            .removePrefix("jdbc:sqlite:")
            .removePrefix("jdbc:dbeaver:libsql:")
            .removePrefix("libsql://")
            .removePrefix("https://")
            .split("]")
            .first()
            .trim()

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
            initErrorLog = "CONNECTED"
            logger.info("Turso database initialized successfully with host: $cleanUrl")
        } catch (e: Exception) {
            initErrorLog = "Hikari Init Error: ${e.message}"
            logger.error("Failed to initialize Turso connection: ${e.message}", e)
        }
    }

    fun getColumns(): List<String> {
        val ds = dataSource ?: return listOf("ERROR: dataSource is NULL. Log: $initErrorLog")
        val result = mutableListOf<String>()
        var conn: Connection? = null
        try {
            conn = ds.connection
            val stmt = conn.createStatement()
            
            val rsTables = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table';")
            while (rsTables.next()) {
                result.add("TABLE: " + rsTables.getString("name"))
            }
            rsTables.close()

            val rsCols = stmt.executeQuery("PRAGMA table_info('MasterData');")
            while (rsCols.next()) {
                result.add("COL: " + rsCols.getString("name"))
            }
            rsCols.close()
            stmt.close()
        } catch (e: Exception) {
            logger.error("Error fetching columns: ${e.message}", e)
            result.add("EXCEPTION: ${e.message}")
        } finally {
            conn?.close()
        }
        return result
    }

    fun getRecordByEmpId(targetEmpId: String): MasterDataDto? {
        val ds = dataSource ?: return null
        val searchId = targetEmpId.trim()

        var conn: Connection? = null
        return try {
            conn = ds.connection

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