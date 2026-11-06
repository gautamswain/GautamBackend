package com.example

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.*

@kotlinx.serialization.Serializable
data class ComprehensiveVerificationReport(
    val employeeName: String,
    val isPanValid: Boolean,
    val panMessage: String,
    val isAadharValid: Boolean,
    val aadharMessage: String,
    val isDobValid: Boolean,
    val dobMessage: String,
    val isDojValid: Boolean,
    val dojMessage: String,
    val hasDuplicates: Boolean,
    val totalMatches: Int,
    val matchedRecords: List<MatchedDatabaseRecord>,
    val overallSummary: String
)

@kotlinx.serialization.Serializable
data class MatchedDatabaseRecord(
    val empId: String,
    val employeeName: String,
    val fathersName: String,
    val branchName: String,
    val matchedFields: List<String>
)

fun Route.employeeVerificationRoute() {
    post("/api/turso/comprehensive-verify") {
        try {
            val bodyText = call.receiveText()
            val jsonElement = Json.parseToJsonElement(bodyText).jsonObject

            val empName = jsonElement["emp_name"]?.jsonPrimitive?.content ?: ""
            val fathersName = jsonElement["fathers_name"]?.jsonPrimitive?.content ?: ""
            val dob = jsonElement["dob"]?.jsonPrimitive?.content ?: ""
            val doj = jsonElement["doj_in_adhikar"]?.jsonPrimitive?.content ?: jsonElement["doj"]?.jsonPrimitive?.content ?: ""
            val panNo = jsonElement["pan_no"]?.jsonPrimitive?.content ?: ""
            val aadharNo = jsonElement["aadhar_no"]?.jsonPrimitive?.content ?: ""
            val accountNo = jsonElement["account_no"]?.jsonPrimitive?.content ?: ""
            val contactNo = jsonElement["contact_no"]?.jsonPrimitive?.content ?: ""
            val contactNo2 = jsonElement["contactno2"]?.jsonPrimitive?.content ?: ""

            // 1. PAN Validation
            val panRegex = Regex("^[A-Z]{5}[0-9]{4}[A-Z]{1}$")
            val cleanPan = panNo.trim().uppercase()
            val isPanValid = cleanPan.length == 10 && panRegex.matches(cleanPan)
            val panMessage = if (isPanValid) "PAN format and length are valid." else "Invalid PAN format or length (Must be 10 characters: AAAAA9999A)."

            // 2. Aadhaar Validation ([Aadhaar Redacted])
            val cleanAadhar = aadharNo.replace(Regex("\\s+"), "").trim()
            val aadharRegex = Regex("^[0-9]{12}$")
            val isAadharValid = cleanAadhar.length == 12 && aadharRegex.matches(cleanAadhar)
            val aadharMessage = if (isAadharValid) "Aadhaar format and length are valid." else "Invalid Aadhaar format or length (Must be 12 digits)."

            // 3. DOB Validation
            var isDobValid = false
            val dobString = dob.trim()
            for (formatter in listOf(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("dd-MM-yyyy"), DateTimeFormatter.ofPattern("dd/MM/yyyy"))) {
                try {
                    LocalDate.parse(dobString, formatter)
                    isDobValid = true
                    break
                } catch (_: DateTimeParseException) {}
            }
            val dobMessage = if (isDobValid) "Date of Birth format is valid." else "Invalid Date of Birth format."

            // 4. DOJ Validation
            var isDojValid = false
            val dojString = doj.trim()
            for (formatter in listOf(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("dd-MM-yyyy"), DateTimeFormatter.ofPattern("dd/MM/yyyy"))) {
                try {
                    LocalDate.parse(dojString, formatter)
                    isDojValid = true
                    break
                } catch (_: DateTimeParseException) {}
            }
            val dojMessage = if (isDojValid) "Date of Joining format is valid." else "Invalid Date of Joining format."

            // 5. Turso MasterData Duplicate Check (Using exact DB schema column names with suffixes)
            val conn = TursoDatabaseService.getConnection()
            val matchedRecords = mutableListOf<MatchedDatabaseRecord>()

            if (conn != null) {
                try {
                    val query = """
                        SELECT * FROM MasterData 
                        WHERE (pan_card_no_17 IS NOT NULL AND pan_card_no_17 != '' AND pan_card_no_17 = ?) 
                           OR (aadhar_card_no_16 IS NOT NULL AND aadhar_card_no_16 != '' AND aadhar_card_no_16 = ?) 
                           OR (bank_account_number_20 IS NOT NULL AND bank_account_number_20 != '' AND bank_account_number_20 = ?) 
                           OR (personal_contact_number_14 IS NOT NULL AND personal_contact_number_14 != '' AND (personal_contact_number_14 = ? OR personal_contact_number_14 = ?))
                           OR (contact_no_2_15 IS NOT NULL AND contact_no_2_15 != '' AND (contact_no_2_15 = ? OR contact_no_2_15 = ?))
                    """
                    
                    val pstmt = conn.prepareStatement(query)
                    pstmt.setString(1, cleanPan)
                    pstmt.setString(2, cleanAadhar)
                    pstmt.setString(3, accountNo.trim())
                    pstmt.setString(4, contactNo.trim())
                    pstmt.setString(5, contactNo2.trim())
                    pstmt.setString(6, contactNo.trim())
                    pstmt.setString(7, contactNo2.trim())

                    val rs = pstmt.executeQuery()
                    while (rs.next()) {
                        val dbEmpId = rs.getString("Code_2") ?: rs.getString("emp_id") ?: ""
                        val dbEmpName = rs.getString("employee_name_3") ?: ""
                        val dbFatherName = rs.getString("fathers_name_4") ?: ""
                        val dbBranch = rs.getString("branch_name_9") ?: ""
                        val dbDob = rs.getString("dob_6") ?: ""
                        val dbPan = rs.getString("pan_card_no_17") ?: ""
                        val dbAadhar = rs.getString("aadhar_card_no_16") ?: ""
                        val dbAccount = rs.getString("bank_account_number_20") ?: ""
                        val dbPhone1 = rs.getString("personal_contact_number_14") ?: ""
                        val dbPhone2 = rs.getString("contact_no_2_15") ?: ""

                        val matchedFields = mutableListOf<String>()

                        if (cleanPan.isNotBlank() && cleanPan.equals(dbPan, ignoreCase = true)) {
                            matchedFields.add("PAN Number")
                        }
                        if (cleanAadhar.isNotBlank() && cleanAadhar == dbAadhar.replace(Regex("\\s+"), "")) {
                            matchedFields.add("Aadhaar Number ([Aadhaar Redacted])")
                        }
                        if (accountNo.isNotBlank() && accountNo.trim() == dbAccount.trim()) {
                            matchedFields.add("Bank Account Number")
                        }
                        if (contactNo.isNotBlank() && (contactNo.trim() == dbPhone1.trim() || contactNo.trim() == dbPhone2.trim())) {
                            matchedFields.add("Phone Number 1")
                        }
                        if (contactNo2.isNotBlank() && (contactNo2.trim() == dbPhone1.trim() || contactNo2.trim() == dbPhone2.trim())) {
                            matchedFields.add("Phone Number 2")
                        }
                        if (fathersName.isNotBlank() && fathersName.trim().equals(dbFatherName.trim(), ignoreCase = true)) {
                            matchedFields.add("Father's Name")
                        }
                        if (dob.isNotBlank() && dob.trim() == dbDob.trim()) {
                            matchedFields.add("Date of Birth")
                        }

                        if (matchedFields.isNotEmpty()) {
                            matchedRecords.add(
                                MatchedDatabaseRecord(
                                    empId = dbEmpId,
                                    employeeName = dbEmpName,
                                    fathersName = dbFatherName,
                                    branchName = dbBranch,
                                    matchedFields = matchedFields
                                )
                            )
                        }
                    }
                    rs.close()
                    pstmt.close()
                } finally {
                    conn.close()
                }
            }

            val summary = buildString {
                append(if (matchedRecords.isNotEmpty()) "Duplicate Warning: Found ${matchedRecords.size} matching record(s) in MasterData. " else "No duplicate records found in MasterData. ")
                append(if (isPanValid && isAadharValid && isDobValid && isDojValid) "All formats are valid." else "Some format validations failed.")
            }

            val report = ComprehensiveVerificationReport(
                employeeName = empName.ifBlank { "Unknown" },
                isPanValid = isPanValid,
                panMessage = panMessage,
                isAadharValid = isAadharValid,
                aadharMessage = aadharMessage,
                isDobValid = isDobValid,
                dobMessage = dobMessage,
                isDojValid = isDojValid,
                dojMessage = dojMessage,
                hasDuplicates = matchedRecords.isNotEmpty(),
                totalMatches = matchedRecords.size,
                matchedRecords = matchedRecords,
                overallSummary = summary
            )

            call.respond(HttpStatusCode.OK, report)
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf(
                    "error" to (e.localizedMessage ?: "Verification process failed"),
                    "exception" to e.javaClass.simpleName,
                    "details" to (e.stackTraceToString().take(500))
                )
            )
        }
    }
}