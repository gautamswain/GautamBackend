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
            empStatus = getVal("emp_status_35", "emp_status"),
            empStatusTemp = getVal("emp_status_temp_36", "emp_status_temp"),
            salaryStatus = getVal("salary_status", "slary_37"),
            personalContactNumber = getVal("personal_contact_number_14", "personal_contact_number"),
            contactNo2 = getVal("contact_no_2_15", "contact_no_2"),
            aadharCardNo = getVal("aadhar_card_no_16", "aadhar_card_no"),
            panCardNo = getVal("pan_card_no_17", "pan_card_no"),
            bankName = getVal("bank_name_19", "bank_name", "bank"),
            bankAccountNumber = getVal("bank_account_number_20", "bank_account_number", "ac_no"),
            ifscCodeBankBranchName = getVal("ifsc_code_bank_branch_name_21", "ifsc_code_bank_branch_name", "ifsc"),
            esiNo = getVal("esi_no_22", "esi_no", "esic"),
            epfUanNo = getVal("epf_uan_no_23", "epf_uan_no", "epf"),
            uanName = getVal("uan_name_24", "uan_name")
        )
    }

    // ଷ୍ଟାଟସ୍ ସ୍କୋର୍ କାଲ୍‌କୁଲେଟ୍ କରିବା ପାଇଁ
    fun calculateStatusScore(dto: MasterDataDto): Int {
        val s1 = if (dto.empStatus.trim() == "1" || dto.empStatus.trim().equals("active", ignoreCase = true)) 1 else 0
        val s2 = if (dto.empStatusTemp.trim() == "1" || dto.empStatusTemp.trim().equals("active", ignoreCase = true)) 1 else 0
        val s3 = if (dto.salaryStatus.trim() == "1" || dto.salaryStatus.trim().equals("active", ignoreCase = true)) 1 else 0
        return s1 + s2 + s3
    }
}