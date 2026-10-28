// Emp ID last 4 digits (ADKR1146 -> 1146) ଆଧାରରେ SalaryHistory ରୁ ଶେଷ 24 ଟି Record
fun getSalaryHistoryByEmpIdLast4(rawEmpId: String): List<Map<String, String>> {
    val conn = getConnection() ?: return emptyList()
    val cleanEmpId = rawEmpId.trim()
    
    if (cleanEmpId.length < 4) return emptyList()
    val last4Digits = cleanEmpId.takeLast(4)

    val resultList = mutableListOf<Map<String, String>>()

    return try {
        val stmt = conn.createStatement()
        
        // Exact Column Query (Database Structure Matching)
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

            // Database exact field 'emp_id_2' ରୁ Check କରାଗଲା
            val empIdValue = map["emp_id_2"] ?: map["emp_id"] ?: map["code"] ?: ""

            if (empIdValue.trim().endsWith(last4Digits)) {
                val dtoMap = mapOf(
                    "month" to (map["month"] ?: ""),
                    "sl_no_1" to (map["sl_no_1"] ?: ""),
                    "emp_id_2" to empIdValue,
                    "employee_name_3" to (map["employee_name_3"] ?: ""),
                    "designation_7" to (map["designation_7"] ?: ""),
                    "department_8" to (map["department_8"] ?: ""),
                    "branch_name_9" to (map["branch_name_9"] ?: ""),
                    "portfolio_7" to (map["portfolio_7"] ?: ""),
                    "paid_days_8" to (map["paid_days_8"] ?: ""),
                    "basic_da_9" to (map["basic_da_9"] ?: ""),
                    "hra_10" to (map["hra_10"] ?: ""),
                    "conveyance_11" to (map["conveyance_11"] ?: ""),
                    "medical_allowance_12" to (map["medical_allowance_12"] ?: ""),
                    "education_allowance_13" to (map["education_allowance_13"] ?: ""),
                    "lunch_canteen_allownance_14" to (map["lunch_canteen_allownance_14"] ?: ""),
                    "special_allowance_15" to (map["special_allowance_15"] ?: ""),
                    "hravariable_39" to (map["hravariable_39"] ?: ""),
                    "other_allowance_17" to (map["other_allowance_17"] ?: ""),
                    "daaosa_40" to (map["daaosa_40"] ?: ""),
                    "arrears_increment_41" to (map["arrears_increment_41"] ?: ""),
                    "epf55_49" to (map["epf55_49"] ?: ""),
                    "esi56_50" to (map["esi56_50"] ?: ""),
                    "professional_tax_22" to (map["professional_tax_22"] ?: ""),
                    "tds_42" to (map["tds_42"] ?: ""),
                    "staff_loan_43" to (map["staff_loan_43"] ?: ""),
                    "staff_advance_44" to (map["staff_advance_44"] ?: ""),
                    "others_45" to (map["others_45"] ?: ""),
                    "other_earnings_46" to (map["other_earnings_46"] ?: ""),
                    "gross_earnings_28" to (map["gross_earnings_28"] ?: ""),
                    "gross_deductions_29" to (map["gross_deductions_29"] ?: ""),
                    "net_amount_30" to (map["net_amount_30"] ?: ""),
                    "Status_00" to (map["status_00"] ?: map["status"] ?: ""),
                    "bank_account_number_20" to (map["bank_account_number_20"] ?: ""),
                    "ifsc_code_bank_branch_name_21" to (map["ifsc_code_bank_branch_name_21"] ?: ""),
                    "bank_name_19" to (map["bank_name_19"] ?: ""),
                    "backSalarygive" to (map["backsalarygive"] ?: "")
                )
                resultList.add(dtoMap)
            }
        }
        rs.close()
        stmt.close()
        
        // Latest 24 Records
        resultList.takeLast(24).reversed()
    } catch (e: Exception) {
        logger.error("Error fetching salary history: ${e.message}", e)
        emptyList()
    } finally {
        try { conn.close() } catch (_: Exception) {}
    }
}