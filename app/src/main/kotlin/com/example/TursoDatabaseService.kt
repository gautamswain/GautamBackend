fun getSalaryHistoryLast20(targetEmpId: String): List<Map<String, String>> {
        val conn = getConnection() ?: return emptyList()
        val resultList = mutableListOf<Map<String, String>>()
        
        val sql = """
            SELECT * FROM SalaryHistory 
            WHERE LOWER(emp_id_2) = LOWER(?) 
            ORDER BY rowid DESC 
            LIMIT 20;
        """.trimIndent()

        return try {
            val pstmt = conn.prepareStatement(sql)
            pstmt.setString(1, targetEmpId.trim())
            val rs = pstmt.executeQuery()

            val metaData = rs.metaData
            val count = metaData.columnCount

            while (rs.next()) {
                val map = mutableMapOf<String, String>()
                for (i in 1..count) {
                    val colName = metaData.getColumnName(i).lowercase().trim()
                    map[colName] = rs.getString(i) ?: ""
                }

                val dtoMap = mapOf(
                    "id" to getVal(map, "id"),
                    "month" to getVal(map, "month"),
                    "sl_no_1" to getVal(map, "sl_no_1", "sl_no", "slno"),
                    "emp_id_2" to getVal(map, "emp_id_2", "emp_id", "empolyee_code", "code"),
                    "employee_name_3" to getVal(map, "employee_name_3", "employee_name"),
                    "designation_7" to getVal(map, "designation_7", "designation"),
                    "department_8" to getVal(map, "department_8", "department"),
                    "branch_name_9" to getVal(map, "branch_name_9", "branch", "branch_name"),
                    "portfolio_7" to getVal(map, "portfolio_7", "portfolio"),
                    "paid_days_8" to getVal(map, "paid_days_8", "paid_days"),
                    "basic_da_9" to getVal(map, "basic_da_9", "basic__da"),
                    "hra_10" to getVal(map, "hra_10", "hra"),
                    "conveyance_11" to getVal(map, "conveyance_11", "conveyance"),
                    "medical_allowance_12" to getVal(map, "medical_allowance_12", "medical_allowance"),
                    "education_allowance_13" to getVal(map, "education_allowance_13", "education_allowance"),
                    "lunch_canteen_allownance_14" to getVal(map, "lunch_canteen_allownance_14", "lunchcanteen_allownance"),
                    "special_allowance_15" to getVal(map, "special_allowance_15", "special_allowance"),
                    "hravariable_39" to getVal(map, "hravariable_39", "hravariable"),
                    "other_allowance_17" to getVal(map, "other_allowance_17", "other_allowance"),
                    "daaosa_40" to getVal(map, "daaosa_40", "daaosa"),
                    "arrears_increment_41" to getVal(map, "arrears_increment_41", "arrears_increment"),
                    "epf55_49" to getVal(map, "epf55_49", "epf"),
                    "esi56_50" to getVal(map, "esi56_50", "esic"),
                    "professional_tax_22" to getVal(map, "professional_tax_22", "professional_tax"),
                    "tds_42" to getVal(map, "tds_42", "tds"),
                    "staff_loan_43" to getVal(map, "staff_loan_43", "staff_loan"),
                    "staff_advance_44" to getVal(map, "staff_advance_44", "staff_advance"),
                    "others_45" to getVal(map, "others_45", "others"),
                    "other_earnings_46" to getVal(map, "other_earnings_46", "other_earnings"),
                    "gross_earnings_28" to getVal(map, "gross_earnings_28", "gross_earnings"),
                    "gross_deductions_29" to getVal(map, "gross_deductions_29", "gross_deductions"),
                    "net_amount_30" to getVal(map, "net_amount_30", "net_amount"),
                    "status_00" to getVal(map, "status_00", "status"),
                    "bank_account_number_20" to getVal(map, "bank_account_number_20", "ac_no"),
                    "ifsc_code_bank_branch_name_21" to getVal(map, "ifsc_code_bank_branch_name_21", "ifsc"),
                    "bank_name_19" to getVal(map, "bank_name_19", "bank"),
                    "backsalarygive" to getVal(map, "backsalarygive", "back")
                )
                resultList.add(dtoMap)
            }
            rs.close()
            pstmt.close()
            resultList.reversed()
        } catch (e: Exception) {
            logger.error("Error fetching salary history for empId $targetEmpId: ${e.message}", e)
            emptyList()
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }