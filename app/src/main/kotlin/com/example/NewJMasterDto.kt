package com.example

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NewJMasterDto(
    @SerialName("sl_no") val slNo: String = "",
    @SerialName("emp_name") val empName: String = "",
    @SerialName("fathers_name") val fathersName: String = "",
    @SerialName("dob") val dob: String = "",
    @SerialName("designation") val designation: String = "",
    @SerialName("branch_name") val branchName: String = "",
    @SerialName("area_name") val areaName: String = "",
    @SerialName("doj_in_adhikar") val dojInAdhikar: String = "",
    @SerialName("doj_in_branch") val dojInBranch: String = "",
    @SerialName("present_address") val presentAddress: String = "",
    @SerialName("permanent_address") val permanentAddress: String = "",
    @SerialName("education") val education: String = "",
    @SerialName("contact_no") val contactNo: String = "",
    @SerialName("aadhar_no") val aadharNo: String = "",
    @SerialName("pan_no") val panNo: String = "",
    @SerialName("bank_name") val bankName: String = "",
    @SerialName("account_no") val accountNo: String = "",
    @SerialName("ifsc_code") val ifscCode: String = "",
    @SerialName("esi") val esi: String = "",
    @SerialName("uan_no") val uanNo: String = "",
    @SerialName("gender") val gender: String = "",
    @SerialName("contactno_2") val contactno2: String = "",
    @SerialName("email") val email: String = "",
    @SerialName("company_experience") val companyExperience: String = "",
    @SerialName("refferenced_by") val refferencedBy: String = "",
    @SerialName("interviewd_by") val interviewdBy: String = ""
)