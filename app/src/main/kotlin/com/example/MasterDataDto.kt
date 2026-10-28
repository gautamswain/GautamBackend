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
    val empStatus: String = "",
    val empStatusTemp: String = "",
    val salaryStatus: String = "",
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