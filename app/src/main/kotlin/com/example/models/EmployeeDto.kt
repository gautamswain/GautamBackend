package com.example.models

import kotlinx.serialization.Serializable

@Serializable
data class EmployeeMasterDto(
    val empCode: String = "",
    val empName: String = "",
    val fathersName: String = "",
    val gender: String = "",
    val dob: String = "",
    val doj: String = "",
    val dol: String = "",
    val designation: String = "",
    val department: String = "",
    val branchName: String = "",
    val phone1: String = "",
    val phone2: String = "",
    val personalEmail: String = "",
    val officeEmail: String = "",
    val presentAddress: String = "",
    val permanentAddress: String = "",
    val aadharNo: String = "",
    val panNo: String = "",
    val bankName: String = "",
    val bankAccountNo: String = "",
    val ifscCode: String = "",
    val uanNo: String = "",
    val esiNo: String = "",
    val salaryCode: String = "",
    val empStatus: String = ""
)

@Serializable
data class SalaryRecordDto(
    val month: String = "",
    val basicDa: String = "",
    val hra: String = "",
    val grossEarnings: String = "",
    val grossDeductions: String = "",
    val netAmount: String = "",
    val salaryStatus: String = "",
    val backSalaryGive: String = ""
)

@Serializable
data class AttendanceRecordDto(
    val month: String = "",
    val presentDays: String = "",
    val casualLeave: String = "",
    val sickLeave: String = "",
    val totalPaidDays: String = "",
    val remarks: String = ""
)

@Serializable
data class BranchInfoDto(
    val branchName: String = "",
    val areaName: String = "",
    val zoneName: String = "",
    val branchAddress: String = ""
)

@Serializable
data class EmployeeProfileResponse(
    val masterData: EmployeeMasterDto,
    val recentSalaries: List<SalaryRecordDto> = emptyList(),
    val attendanceInfo: List<AttendanceRecordDto> = emptyList(),
    val branchInfo: BranchInfoDto? = null
)