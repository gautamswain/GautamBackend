// SalaryHistory ଫେଚ୍ କରିବା ପାଇଁ Universal Flexible Matching ଲଜିକ୍
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

            // Universal check: Row ର ଯେକୌଣସି Column ରେ last4Digits (1146) ଥିଲେ Match କରିବ
            var isMatch = false
            for ((_, v) in map) {
                val trimmedVal = v.trim()
                if (trimmedVal.endsWith(last4Digits) || trimmedVal.contains(last4Digits)) {
                    isMatch = true
                    break
                }
            }

            if (isMatch) {
                val dtoMap = mapOf(
                    "month" to getVal(map, "month", "salary_month"),
                    "sl_no_1" to getVal(map, "sl_no_1", "sl_no"),
                    "emp_id_2" to getVal(map, "emp_id_2", "emp_id", "empolyee_code_2", "code"),
                    "employee_name_3" to getVal(map, "employee_name_3", "employee_name"),
                    "designation_7" to getVal(map, "designation_7", "designation"),
                    "department_8" to getVal(map, "department_8", "department"),
                    "branch_name_9" to getVal(map, "branch_name_9", "branch_name", "branch"),
                    "portfolio_7" to getVal(map, "portfolio_7", "portfolio"),
                    "paid_days_8" to getVal(map, "paid_days_8", "paid_days"),
                    "basic_da_9" to getVal(map, "basic_da_9", "basic_da"),
                    "hra_10" to getVal(map, "hra_10", "hra"),
                    "conveyance_11" to getVal(map, "conveyance_11", "conveyance"),
                    "medical_allowance_12" to getVal(map, "medical_allowance_12", "medical_allowance"),
                    "education_allowance_13" to getVal(map, "education_allowance_13", "education_allowance"),
                    "lunch_canteen_allownance_14" to getVal(map, "lunch_canteen_allownance_14", "lunch_canteen_allowance"),
                    "special_allowance_15" to getVal(map, "special_allowance_15", "special_allowance"),
                    "hravariable_39" to getVal(map, "hravariable_39", "hra_variable_16"),
                    "other_allowance_17" to getVal(map, "other_allowance_17", "other_allowance"),
                    "daaosa_40" to getVal(map, "daaosa_40", "daa_osa_18"),
                    "arrears_increment_41" to getVal(map, "arrears_increment_41", "arrears_increment_19"),
                    "epf55_49" to getVal(map, "epf55_49", "epf_20", "epf"),
                    "esi56_50" to getVal(map, "esi56_50", "esic_21", "esi"),
                    "professional_tax_22" to getVal(map, "professional_tax_22", "pt"),
                    "tds_42" to getVal(map, "tds_42", "tds_23"),
                    "staff_loan_43" to getVal(map, "staff_loan_43", "staff_loan_24"),
                    "staff_advance_44" to getVal(map, "staff_advance_44", "staff_advance_25"),
                    "others_45" to getVal(map, "others_45", "others_26"),
                    "other_earnings_46" to getVal(map, "other_earnings_46", "other_earnings_27"),
                    "gross_earnings_28" to getVal(map, "gross_earnings_28", "gross_earnings"),
                    "gross_deductions_29" to getVal(map, "gross_deductions_29", "gross_deductions"),
                    "net_amount_30" to getVal(map, "net_amount_30", "net_amount"),
                    "Status_00" to getVal(map, "status_00", "status_31", "status"),
                    "bank_account_number_20" to getVal(map, "bank_account_number_20", "ac_no_32", "bank_account_number"),
                    "ifsc_code_bank_branch_name_21" to getVal(map, "ifsc_code_bank_branch_name_21", "ifsc_33", "ifsc"),
                    "bank_name_19" to getVal(map, "bank_name_19", "bank_34", "bank"),
                    "backSalarygive" to getVal(map, "backsalarygive", "sal_h_48", "back_35", "backsalary")
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

private fun getVal(map: Map<String, String>, vararg keys: String): String {
    for (key in keys) {
        val value = map[key.lowercase()]
        if (!value.isNullOrBlank()) return value
    }
    return ""
}

// Debugging function to check SalaryHistory columns
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