package com.example.utils

import com.example.models.AttendanceRecordDto
import com.example.models.BranchInfoDto
import com.example.models.EmployeeMasterDto
import com.example.models.SalaryRecordDto

object AdhikarDataMapper {

    // ୧. Master Data Mapper
    fun mapToEmployeeMaster(raw: Map<String, String>): EmployeeMasterDto {
        return EmployeeMasterDto(
            empCode = raw["Code_2"] ?: raw["emp_id_2"] ?: raw["Code"] ?: raw["mycode"] ?: "",
            empName = raw["employee_name_3"] ?: raw["Name"] ?: raw["emp_name"] ?: "",
            fathersName = raw["fathers_name_4"] ?: raw["fathers_name"] ?: "",
            gender = raw["gender_5"] ?: raw["gender"] ?: "",
            dob = raw["dob_6"] ?: raw["dob"] ?: "",
            doj = raw["doj_in_adhikar_10"] ?: raw["DOJ"] ?: raw["doj"] ?: "",
            dol = raw["dol_25"] ?: raw["dol"] ?: "",

            // Professional Details
            designation = raw["designation_7"] ?: raw["Designation"] ?: raw["designation"] ?: "",
            department = raw["department_8"] ?: raw["Department"] ?: raw["department"] ?: "",
            branchName = raw["branch_name_9"] ?: raw["Branch"] ?: raw["branchname"] ?: "",
            empStatus = raw["status_30"] ?: raw["emp_status_35"] ?: raw["status"] ?: "",

            // Contact & Emails
            phone1 = raw["personal_contact_number_14"] ?: raw["contact_no"] ?: raw["contact_no_1"] ?: "",
            phone2 = raw["contact_no_2_15"] ?: raw["contactno_2"] ?: "",
            personalEmail = raw["email61_personal_52"] ?: raw["email"] ?: "",
            officeEmail = raw["mail_id_office_58"] ?: "",
            presentAddress = raw["present_address_11"] ?: "",
            permanentAddress = raw["permanent_address_12"] ?: "",

            // Bank & Statutory
            bankName = raw["bank_name_19"] ?: "",
            bankAccountNo = raw["bank_account_number_20"] ?: raw["account_no"] ?: "",
            ifscCode = raw["ifsc_code_bank_branch_name_21"] ?: raw["ifsc_code"] ?: "",
            aadharNo = raw["aadhar_card_no_16"] ?: raw["aadhar_no"] ?: "",
            panNo = raw["pan_card_no_17"] ?: raw["pan_no"] ?: "",
            uanNo = raw["epf_uan_no_23"] ?: raw["uan_no"] ?: "",
            esiNo = raw["esi_no_22"] ?: raw["esi"] ?: "",
            salaryCode = raw["salary_code_47"] ?: ""
        )
    }

    // ୨. Salary History Mapper
    fun mapToSalaryRecord(raw: Map<String, String>): SalaryRecordDto {
        return SalaryRecordDto(
            month = raw["month"] ?: "",
            basicDa = raw["basic_da_9"] ?: "",
            hra = raw["hra_10"] ?: "",
            grossEarnings = raw["gross_earnings_28"] ?: "",
            grossDeductions = raw["gross_deductions_29"] ?: "",
            netAmount = raw["net_amount_30"] ?: "",
            salaryStatus = raw["Status_00"] ?: "",
            backSalaryGive = raw["backSalarygive"] ?: ""
        )
    }

    // ୩. Attendance History Mapper
    fun mapToAttendanceRecord(raw: Map<String, String>): AttendanceRecordDto {
        return AttendanceRecordDto(
            presentDays = raw["Present Days"] ?: "",
            casualLeave = raw["Casual Leave"] ?: "",
            sickLeave = raw["Sick Leave"] ?: "",
            totalPaidDays = raw["Total Paid Days"] ?: "",
            remarks = raw["REMARKS"] ?: raw["Remark"] ?: ""
        )
    }

    // ୪. Branch Info Mapper
    fun mapToBranchInfo(raw: Map<String, String>): BranchInfoDto {
        return BranchInfoDto(
            branchName = raw["branchname"] ?: "",
            areaName = raw["areaname"] ?: "",
            zoneName = raw["zonename"] ?: "",
            branchAddress = raw["address"] ?: ""
        )
    }
}