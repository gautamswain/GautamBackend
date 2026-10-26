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

                // 1. First, fetch table metadata to discover the exact emp_id column name
                var empColName: String? = null
                exec("PRAGMA table_info(MasterData);") { rs ->
                    while (rs.next()) {
                        val col = rs.getString("name")
                        val lowerCol = col.lowercase()
                        if (lowerCol == "emp_id_2" || lowerCol == "emp_id" || lowerCol.startsWith("emp_id") || lowerCol.contains("empid")) {
                            empColName = col
                            break
                        }
                    }
                }

                // Default fallback if PRAGMA doesn't find it
                val targetColumn = empColName ?: "emp_id_2"

                // 2. Query Turso using the dynamically resolved column name
                val query = "SELECT * FROM MasterData WHERE TRIM(LOWER(\"$targetColumn\")) = LOWER('$searchId') LIMIT 1;"

                exec(query) { rs ->
                    if (rs.next()) {
                        val metaData = rs.metaData
                        val count = metaData.columnCount
                        val map = mutableMapOf<String, String>()

                        for (i in 1..count) {
                            val colName = metaData.getColumnName(i).lowercase()
                            val colValue = rs.getString(i) ?: ""
                            map[colName] = colValue
                        }

                        dto = MasterDataDto(
                            id = map["id"] ?: "",
                            slNo = map["sl_no_1"] ?: map["sl_no"] ?: "",
                            empId = map["emp_id_2"] ?: map["emp_id"] ?: searchId,
                            employeeName = map["employee_name_3"] ?: map["employee_name"] ?: "",
                            fathersName = map["fathers_name_4"] ?: map["fathers_name"] ?: "",
                            gender = map["gender_5"] ?: map["gender"] ?: "",
                            dob = map["dob_6"] ?: map["dob"] ?: "",
                            designation = map["designation_7"] ?: map["designation"] ?: "",
                            department = map["department_8"] ?: map["department"] ?: "",
                            branchName = map["branch_name_9"] ?: map["branch_name"] ?: "",
                            dojInAdhikar = map["doj_in_adhikar_10"] ?: map["doj_in_adhikar"] ?: "",
                            presentAddress = map["present_address_11"] ?: map["present_address"] ?: "",
                            permanentAddress = map["permanent_address_12"] ?: map["permanent_address"] ?: "",
                            highestEducationalQualification = map["highest_educational_qualification_13"] ?: map["highest_educational_qualification"] ?: "",
                            personalContactNumber = map["personal_contact_number_14"] ?: map["personal_contact_number"] ?: "",
                            contactNo2 = map["contact_no_2_15"] ?: map["contact_no_2"] ?: "",
                            aadharCardNo = map["aadhar_card_no_16"] ?: map["aadhar_card_no"] ?: "",
                            panCardNo = map["pan_card_no_17"] ?: map["pan_card_no"] ?: "",
                            driving = map["driving_18"] ?: map["driving"] ?: "",
                            bankName = map["bank_name_19"] ?: map["bank_name"] ?: "",
                            bankAccountNumber = map["bank_account_number_20"] ?: map["bank_account_number"] ?: "",
                            ifscCodeBankBranchName = map["ifsc_code_bank_branch_name_21"] ?: map["ifsc_code_bank_branch_name"] ?: "",
                            esiNo = map["esi_no_22"] ?: map["esi_no"] ?: "",
                            epfUanNo = map["epf_uan_no_23"] ?: map["epf_uan_no"] ?: "",
                            uanName = map["uan_name_24"] ?: map["uan_name"] ?: "",
                            dol = map["dol_25"] ?: map["dol"] ?: "",
                            companyNameStartingFromCurrentToOld = map["company_name_starting_from_current_to_old_26"] ?: "",
                            designationbackcompany = map["designationbackcompany_27"] ?: "",
                            bloodGroup = map["blood_group_28"] ?: map["blood_group"] ?: "",
                            ff = map["ff_29"] ?: map["ff"] ?: "",
                            status = map["status_30"] ?: map["status"] ?: "",
                            interviewed = map["interviewed_31"] ?: map["interviewed"] ?: "",
                            dateOfResignation = map["date_of_resignation_32"] ?: map["date_of_resignation"] ?: "",
                            lastWorkingDate = map["last_working_date_33"] ?: map["last_working_date"] ?: "",
                            typeOfExit = map["type_of_exit_34"] ?: map["type_of_exit"] ?: "",
                            empStatus = map["emp_status_35"] ?: map["emp_status"] ?: "",
                            empStatusTemp = map["emp_status_temp_36"] ?: map["emp_status_temp"] ?: "",
                            slary = map["slary_37"] ?: map["slary"] ?: "",
                            mastRem = map["mast_rem_38"] ?: map["mast_rem"] ?: "",
                            hravariable = map["hravariable_39"] ?: map["hravariable"] ?: "",
                            daaosa = map["daaosa_40"] ?: map["daaosa"] ?: "",
                            arrearsIncrement = map["arrears_increment_41"] ?: map["arrears_increment"] ?: "",
                            tds = map["tds_42"] ?: map["tds"] ?: "",
                            staffLoan = map["staff_loan_43"] ?: map["staff_loan"] ?: "",
                            staffAdvance = map["staff_advance_44"] ?: map["staff_advance"] ?: "",
                            others = map["others_45"] ?: map["others"] ?: "",
                            otherEarnings = map["other_earnings_46"] ?: map["other_earnings"] ?: "",
                            salaryCode = map["salary_code_47"] ?: map["salary_code"] ?: "",
                            salH = map["sal_h_48"] ?: map["sal_h"] ?: "",
                            epf55 = map["epf55_49"] ?: map["epf55"] ?: "",
                            esi56 = map["esi56_50"] ?: map["esi56"] ?: "",
                            lender = map["lender_51"] ?: map["lender"] ?: "",
                            email61Personal = map["email61_personal_52"] ?: map["email61_personal"] ?: "",
                            offerApp = map["offer_app_53"] ?: map["offer_app"] ?: "",
                            insurancesbi = map["insurancesbi_54"] ?: map["insurancesbi"] ?: "",
                            equifax = map["equifax_55"] ?: map["equifax"] ?: "",
                            exitDate = map["exit_date_56"] ?: map["exit_date"] ?: "",
                            idphoto68 = map["idphoto68_57"] ?: map["idphoto68"] ?: "",
                            mailIdOffice = map["mail_id_office_58"] ?: map["mail_id_office"] ?: "",
                            coment77 = map["coment77_59"] ?: map["coment77"] ?: "",
                            documentSubmissionDate78 = map["documentsubmissiondate78_60"] ?: ""
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