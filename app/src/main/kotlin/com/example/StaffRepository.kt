package com.example

import java.sql.ResultSet

object StaffRepository {

    // ResultSet ରୁ ଷ୍ଟାଫ୍ ଡାଟା ପାର୍ସ କରିବା ପାଇଁ
    fun parseMasterDataDto(rs: ResultSet): MasterDataDto {
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

        return MasterDataDto(
            id = getVal("id"),
            slNo = getVal("sl_no_1", "sl_no", "slno"),
            empId = getVal("emp_id_2", "emp_id", "empolyee_code", "code"),
            employeeName = getVal("employee_name_3", "employee_name"),
            fathersName = getVal("fathers_name_4", "fathers_name"),
            gender = getVal("gender_5", "gender"),
            dob = getVal("dob_6", "dob"),
            designation = getVal("designation_7", "designation"),
            department = getVal("department_8", "department"),
            branchName = getVal("branch_name_9", "branch_name", "branch"),
            dojInAdhikar = getVal("doj_in_adhikar_10", "doj_in_adhikar"),
            presentAddress = getVal("present_address_11", "present_address"),
            permanentAddress = getVal("permanent_address_12", "permanent_address"),
            highestEducationalQualification = getVal("highest_educational_qualification_13", "highest_educational_qualification"),
            personalContactNumber = getVal("personal_contact_number_14", "personal_contact_number"),
            contactNo2 = getVal("contact_no_2_15", "contact_no_2"),
            aadharCardNo = getVal("aadhar_card_no_16", "aadhar_card_no"),
            panCardNo = getVal("pan_card_no_17", "pan_card_no"),
            driving = getVal("driving_18", "driving"),
            bankName = getVal("bank_name_19", "bank_name", "bank"),
            bankAccountNumber = getVal("bank_account_number_20", "bank_account_number", "ac_no"),
            ifscCodeBankBranchName = getVal("ifsc_code_bank_branch_name_21", "ifsc_code_bank_branch_name", "ifsc"),
            esiNo = getVal("esi_no_22", "esi_no", "esic"),
            epfUanNo = getVal("epf_uan_no_23", "epf_uan_no", "epf"),
            uanName = getVal("uan_name_24", "uan_name"),
            dol = getVal("dol_25", "dol"),
            companyNameStartingFromCurrentToOld = getVal("company_name_starting_from_current_to_old_26", "company_name_starting_from_current_to_old"),
            designationbackcompany = getVal("designationbackcompany_27", "designationbackcompany"),
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
            documentSubmissionDate = getVal("documentsubmissiondate78_60", "document_submission_date", "documentsubmissiondate78_60"),
            idPrepare = getVal("id_prepare", "idprepare"),
            officeWellbeings = getVal("officewelbeings"),
            salaryHistory = getVal("salaryhistory63", "salary_history"),
            totalMonthOfWork = getVal("totalmonthofwork64", "total_month_of_work"),
            sameBranch = getVal("samebranch65", "same_branch"),
            sameDesignation = getVal("samedesignation66", "same_designation")
        )
    }

    // ଷ୍ଟାଟସ୍ ସ୍କୋର୍ କାଲ୍‌କୁଲେଟ୍ କରିବା ପାଇଁ
    fun calculateStatusScore(dto: MasterDataDto): Int {
        val s1 = if (dto.empStatus.trim() == "1" || dto.empStatus.trim().equals("active", ignoreCase = true)) 1 else 0
        val s2 = if (dto.empStatusTemp.trim() == "1" || dto.empStatusTemp.trim().equals("active", ignoreCase = true)) 1 else 0
        val s3 = if (dto.slary.trim().isNotEmpty() && dto.slary.trim() != "0") 1 else 0
        return s1 + s2 + s3
    }
}