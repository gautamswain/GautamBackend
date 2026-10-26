package com.example

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MasterDataDto(
    val id: String = "",
    val slNo: String = "",
    val empId: String = "",
    val employeeName: String = "",
    val fathersName: String = "",
    val gender: String = "",
    val dob: String = "",
    val designation: String = "",
    val department: String = "",
    val branchName: String = "",
    val dojInAdhikar: String = "",
    val personalContactNumber: String = "",
    val contactNo2: String = "",
    val aadharCardNo: String = "",
    val panCardNo: String = "",
    val bankName: String = "",
    val bankAccountNumber: String = "",
    val ifscCodeBankBranchName: String = "",
    val esiNo: String = "",
    val epfUanNo: String = "",
    val uanName: String = ""
)

// Search Response ପାଇଁ strictly ୪ଟି Selected Fields DTO
@Serializable
data class SearchResultDto(
    @SerialName("emp_id_2") val empId: String = "",
    @SerialName("employee_name_3") val employeeName: String = "",
    @SerialName("emp_status_35") val empStatus: String = "",
    @SerialName("emp_status_temp_36") val empStatusTemp: String = ""
)