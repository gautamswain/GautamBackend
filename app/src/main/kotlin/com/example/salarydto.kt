package com.example

import kotlinx.serialization.Serializable
import java.sql.ResultSet

@Serializable
data class SalaryDto(
    val id: String,
    val month: String,
    val slNo1: String,
    val empId2: String,
    val employeeName3: String,
    val designation7: String,
    val department8: String,
    val branchName9: String,
    val portfolio7: String,
    val paidDays8: String,
    val basicDa9: String,
    val hra10: String,
    val conveyance11: String,
    val medicalAllowance12: String,
    val educationAllowance13: String,
    val lunchCanteenAllowance14: String,
    val specialAllowance15: String,
    val hraVariable39: String,
    val otherAllowance17: String,
    val daAosa40: String,
    val arrearsIncrement41: String,
    val epf5549: String,
    val esi5650: String,
    val professionalTax22: String,
    val tds42: String,
    val staffLoan43: String,
    val staffAdvance44: String,
    val others45: String,
    val otherEarnings46: String,
    val grossEarnings28: String,
    val grossDeductions29: String,
    val netAmount30: String,
    val status00: String,
    val bankAccountNumber20: String,
    val ifscCodeBankBranchName21: String,
    val bankName19: String,
    val backSalaryGive: String
)

fun parseSalaryDto(rs: ResultSet): SalaryDto {
    val meta = rs.metaData
    val colMap = mutableMapOf<String, String>()
    for (i in 1..meta.columnCount) {
        val colName = meta.getColumnName(i).trim().lowercase().replace("_", "")
        val value = rs.getString(i)?.trim().orEmpty()
        if (value.isNotBlank()) {
            colMap[colName] = value
        }
    }

    fun getCol(vararg candidates: String): String {
        for (cand in candidates) {
            val key = cand.trim().lowercase().replace("_", "")
            val v = colMap[key]
            if (!v.isNullOrBlank()) return v
        }
        return ""
    }

    return SalaryDto(
        id = getCol("id", "salaryhistoryslno", "slno"),
        month = getCol("month"),
        slNo1 = getCol("slno1", "slno", "sl_no_1"),
        empId2 = getCol("empid2", "empid", "code2", "code", "empid_2"),
        employeeName3 = getCol("employeename3", "employeename", "name", "employee_name_3"),
        designation7 = getCol("designation7", "designation", "designation_7"),
        department8 = getCol("department8", "department", "department_8"),
        branchName9 = getCol("branchname9", "branchname", "branch", "branch_name_9"),
        portfolio7 = getCol("portfolio7", "portfolio", "portfolio_7"),
        paidDays8 = getCol("paiddays8", "paiddays", "paid_days_8"),
        basicDa9 = getCol("basicda9", "basicda", "basic", "basic_da_9"),
        hra10 = getCol("hra10", "hra", "hra_10"),
        conveyance11 = getCol("conveyance11", "conveyance", "conveyance_11"),
        medicalAllowance12 = getCol("medicalallowance12", "medicalallowance", "medical", "medical_allowance_12"),
        educationAllowance13 = getCol("educationallowance13", "educationallowance", "education", "education_allowance_13"),
        lunchCanteenAllowance14 = getCol("lunchcanteenallowance14", "lunchcanteenallowance", "lunch", "canteen", "lunch_canteen_allownance_14"),
        specialAllowance15 = getCol("specialallowance15", "specialallowance", "special", "special_allowance_15"),
        hraVariable39 = getCol("hravariable39", "hravariable", "hravariable_39"),
        otherAllowance17 = getCol("otherallowance17", "otherallowance", "other_allowance_17"),
        daAosa40 = getCol("daaosa40", "daaosa", "da_aosa_40"),
        arrearsIncrement41 = getCol("arrearsincrement41", "arrearsincrement", "arrears", "arrears_increment_41"),
        epf5549 = getCol("epf5549", "epf55", "epf", "epf55_49"),
        esi5650 = getCol("esi5650", "esi56", "esi", "esi56_50"),
        professionalTax22 = getCol("professionaltax22", "professionaltax", "pt", "professional_tax_22"),
        tds42 = getCol("tds42", "tds", "tds_42"),
        staffLoan43 = getCol("staffloan43", "staffloan", "loan", "staff_loan_43"),
        staffAdvance44 = getCol("staffadvance44", "staffadvance", "advance", "staff_advance_44"),
        others45 = getCol("others45", "others", "others_45"),
        otherEarnings46 = getCol("otherearnings46", "otherearnings", "other_earnings_46"),
        grossEarnings28 = getCol("grossearnings28", "grossearnings", "gross", "gross_earnings_28"),
        grossDeductions29 = getCol("grossdeductions29", "grossdeductions", "deductions", "gross_deductions_29"),
        netAmount30 = getCol("netamount30", "netamount", "netsalary", "net", "net_amount_30"),
        status00 = getCol("status00", "status", "status_00"),
        bankAccountNumber20 = getCol("bankaccountnumber20", "bankaccountnumber", "accountno", "acno", "bank_account_number_20"),
        ifscCodeBankBranchName21 = getCol("ifsccodebankbranchname21", "ifsccodebankbranchname", "ifsc", "ifsc_code_bank_branch_name_21"),
        bankName19 = getCol("bankname19", "bankname", "bank", "bank_name_19"),
        backSalaryGive = getCol("backsalarygive", "backsalary")
    )
}