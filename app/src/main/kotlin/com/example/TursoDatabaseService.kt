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

            val dataSource = HikariDataSource(hikariConfig)
            Database.connect(dataSource)
            isConnected = true
            logger.info("Turso database initialized successfully.")
        } catch (e: Exception) {
            logger.error("Failed to initialize Turso connection: ${e.message}", e)
        }
    }

    fun getRecordByEmpId(targetEmpId: String): MasterDataDto? {
        if (!isConnected) return null

        val searchId = targetEmpId.trim()

        return try {
            transaction {
                var dto: MasterDataDto? = null

                val query = """
                    SELECT * FROM MasterData 
                    WHERE LOWER(TRIM(coalesce(
                        NULLIF(emp_id_2, ''), 
                        NULLIF(emp_id, ''), 
                        ''
                    ))) = LOWER('$searchId') 
                    LIMIT 1;
                """.trimIndent()

                exec(query) { rs ->
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
                            slNo = getVal("sl_no_1", "sl_no", "slno"),
                            empId = getVal("emp_id_2", "emp_id", "empid", "code").ifEmpty { searchId },
                            employeeName = getVal("employee_name_3", "employee_name", "name"),
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
                            personalContactNumber = getVal("personal_contact_number_14", "personal_contact_number", "contact_no"),
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
                            dol = getVal("dol_25", "dol"),
                            companyNameStartingFromCurrentToOld = getVal("company_name_starting_from_current_to_old_26"),
                            designationbackcompany = getVal("designationbackcompany_27"),
                            bloodGroup = getVal("blood_group_28", "blood_group"),
                            ff = getVal("ff_29", "ff"),
                            status = getVal("status_30", "status"),
                            interviewed = getVal("interviewed_31", "interviewed"),
                            dateOfResignation = getVal("date_of_resignation_32", "date_of_resignation"),
                            lastWorkingDate = getVal("last_working_date_33", "last_working_date"),
                            typeOfExit = getVal("type_of_exit_34", "type_of_exit"),
                            empStatus = getVal("emp_status_35", "emp_status"),
                            empStatusTemp = getVal("emp_status_temp_36", "emp_status_temp"),
                            slary = getVal("slary_37", "slary"),
                            mastRem = getVal("mast_rem_38", "mast_rem"),
                            hravariable = getVal("hravariable_39", "hravariable"),
                            daaosa = getVal("daaosa_40", "daaosa"),
                            arrearsIncrement = getVal("arrears_increment_41", "arrears_increment"),
                            tds = getVal("tds_42", "tds"),
                            staffLoan = getVal("staff_loan_43", "staff_loan"),
                            staffAdvance = getVal("staff_advance_44", "staff_advance"),
                            others = getVal("others_45", "others"),
                            otherEarnings = getVal("other_earnings_46", "other_earnings"),
                            salaryCode = getVal("salary_code_47", "salary_code"),
                            salH = getVal("sal_h_48", "sal_h"),
                            epf55 = getVal("epf55_49", "epf55"),
                            esi56 = getVal("esi56_50", "esi56"),
                            lender = getVal("lender_51", "lender"),
                            email61Personal = getVal("email61_personal_52", "email61_personal"),
                            offerApp = getVal("offer_app_53", "offer_app"),
                            insurancesbi = getVal("insurancesbi_54", "insurancesbi"),
                            equifax = getVal("equifax_55", "equifax"),
                            exitDate = getVal("exit_date_56", "exit_date"),
                            idphoto68 = getVal("idphoto68_57", "idphoto68"),
                            mailIdOffice = getVal("mail_id_office_58", "mail_id_office"),
                            coment77 = getVal("coment77_59", "coment77"),
                            documentSubmissionDate78 = getVal("documentsubmissiondate78_60")
                        )
                    }
                }
                dto
            }
        } catch (e: Exception) {
            logger.error("Error querying empId $targetEmpId: ${e.message}", e)
            null
        }
    }
}