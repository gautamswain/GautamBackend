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
            logger.warn("TURSO_DATABASE_URL or TURSO_AUTH_TOKEN is missing!")
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
                val query = """
                    SELECT * FROM MasterData 
                    WHERE LOWER(REPLACE(REPLACE("emp_id", ' ', ''), '.', '')) = LOWER('$searchId')
                       OR LOWER(REPLACE(REPLACE("Emp. ID", ' ', ''), '.', '')) = LOWER('$searchId')
                    LIMIT 1
                """.trimIndent()

                var dto: MasterDataDto? = null

                exec(query) { rs ->
                    if (rs.next()) {
                        val metaData = rs.metaData
                        val count = metaData.columnCount
                        val map = mutableMapOf<String, String>()

                        for (i in 1..count) {
                            val colName = metaData.getColumnName(i).lowercase().replace("-", "_").replace(" ", "_").replace(".", "")
                            val colValue = rs.getString(i) ?: ""
                            map[colName] = colValue
                        }

                        dto = MasterDataDto(
                            id = map["id"] ?: "",
                            slNo = map["sl_no"] ?: map["slno"] ?: "",
                            empId = map["emp_id"] ?: map["empid"] ?: searchId,
                            employeeName = map["employee_name"] ?: "",
                            fathersName = map["fathers_name"] ?: map["father_name"] ?: "",
                            gender = map["gender"] ?: "",
                            dob = map["dob"] ?: "",
                            designation = map["designation"] ?: "",
                            department = map["department"] ?: "",
                            branchName = map["branch_name"] ?: "",
                            dojInAdhikar = map["doj_in_adhikar"] ?: "",
                            presentAddress = map["present_address"] ?: "",
                            permanentAddress = map["permanent_address"] ?: "",
                            highestEducationalQualification = map["highest_educational_qualification"] ?: "",
                            personalContactNumber = map["personal_contact_number"] ?: "",
                            contactNo2 = map["contact_no_2"] ?: "",
                            aadharCardNo = map["aadhar_card_no"] ?: "",
                            panCardNo = map["pan_card_no"] ?: "",
                            driving = map["driving"] ?: "",
                            bankName = map["bank_name"] ?: "",
                            bankAccountNumber = map["bank_account_number"] ?: "",
                            ifscCodeBankBranchName = map["ifsc_code_bank_branch_name"] ?: "",
                            esiNo = map["esi_no"] ?: "",
                            epfUanNo = map["epf_uan_no"] ?: "",
                            uanName = map["uan_name"] ?: "",
                            dol = map["dol"] ?: "",
                            companyNameStartingFromCurrentToOld = map["company_name_starting_from_current_to_old"] ?: "",
                            designationbackcompany = map["designationbackcompany"] ?: "",
                            bloodGroup = map["blood_group"] ?: "",
                            ff = map["ff"] ?: "",
                            status = map["status"] ?: "",
                            interviewed = map["interviewed"] ?: "",
                            dateOfResignation = map["date_of_resignation"] ?: "",
                            lastWorkingDate = map["last_working_date"] ?: "",
                            typeOfExit = map["type_of_exit"] ?: "",
                            empStatus = map["emp_status"] ?: "",
                            empStatusTemp = map["emp_status_temp"] ?: "",
                            slary = map["slary"] ?: "",
                            mastRem = map["mast_rem"] ?: "",
                            hravariable = map["hravariable"] ?: "",
                            daaosa = map["daaosa"] ?: "",
                            arrearsIncrement = map["arrears_increment"] ?: "",
                            tds = map["tds"] ?: "",
                            staffLoan = map["staff_loan"] ?: "",
                            staffAdvance = map["staff_advance"] ?: "",
                            others = map["others"] ?: "",
                            otherEarnings = map["other_earnings"] ?: "",
                            salaryCode = map["salary_code"] ?: "",
                            salH = map["sal_h"] ?: "",
                            epf55 = map["epf55"] ?: "",
                            esi56 = map["esi56"] ?: "",
                            lender = map["lender"] ?: "",
                            email61Personal = map["email61_personal"] ?: "",
                            offerApp = map["offer_app"] ?: "",
                            insurancesbi = map["insurancesbi"] ?: "",
                            equifax = map["equifax"] ?: "",
                            exitDate = map["exit_date"] ?: "",
                            idphoto68 = map["idphoto68"] ?: "",
                            mailIdOffice = map["mail_id_office"] ?: "",
                            coment77 = map["coment77"] ?: "",
                            documentSubmissionDate78 = map["documentsubmissiondate78"] ?: "",
                            idPrepare = map["id_prepare"] ?: "",
                            officewelbeings = map["officewelbeings"] ?: ""
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