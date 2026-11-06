package com.example

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

@kotlinx.serialization.Serializable
data class ComprehensiveVerificationReport(
    val employeeName: String,
    // Format Validation Results
    val isPanValid: Boolean,
    val panMessage: String,
    val isAadharValid: Boolean,
    val aadharMessage: String,
    val isDobValid: Boolean,
    val dobMessage: String,
    val isDojValid: Boolean,
    val dojMessage: String,
    // Turso Duplicate Check Results
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
            val newEmp = call.receive<NewJMasterDto>()

            // 1. PAN Validation (Length 10, Format: 5 Letters, 4 Numbers, 1 Letter)
            val panRegex = Regex("^[A-Z]{5}[0-9]{4}[A-Z]{1}$")
            val cleanPan = (newEmp.panNo ?: "").trim().uppercase()
            val isPanValid = cleanPan.length == 10 && panRegex.matches(cleanPan)
            val panMessage = if (isPanValid) "PAN format and length are valid." else "Invalid PAN format or length (Must be 10 characters: AAAAA9999A)."

            // 2. Aadhaar Validation (Length 12 digits numeric)
            val cleanAadhar = (newEmp.aadharNo ?: "").replace(Regex("\\s+"), "").trim()
            val aadharRegex = Regex("^[0-9]{12}$")
            val isAadharValid = cleanAadhar.length == 12 && aadharRegex.matches(cleanAadhar)
            val aadharMessage = if (isAadharValid) "Aadhaar format and length are valid." else "Invalid Aadhaar format or length (Must be 12 digits)."

            // 3. DOB Validation Format Check (Accepts YYYY-MM-DD or DD-MM-YYYY)
            var isDobValid = false
            val dobString = (newEmp.dob ?: "").trim()
            for (formatter in listOf(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("dd-MM-yyyy"), DateTimeFormatter.ofPattern("dd/MM/yyyy"))) {
                try {
                    LocalDate.parse(dobString, formatter)
                    isDobValid = true
                    break
                } catch (_: DateTimeParseException) {}
            }
            val dobMessage = if (isDobValid) "Date of Birth format is valid." else "Invalid Date of Birth format."

            // 4. DOJ Validation Format Check
            var isDojValid = false
            val dojString = (newEmp.dojInAdhikar ?: "").trim()
            for (formatter in listOf(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("dd-MM-yyyy"), DateTimeFormatter.ofPattern("dd/MM/yyyy"))) {
                try {
                    LocalDate.parse(dojString, formatter)
                    isDojValid = true
                    break
                } catch (_: DateTimeParseException) {}
            }
            val dojMessage = if (isDojValid) "Date of Joining format is valid." else "Invalid Date of Joining format."

            // 5. Turso MasterData Duplicate Check (Using correct column names: pan_no, aadhar_no, account_no, contact_no)
            val conn = TursoDatabaseService.getConnection()
            val matchedRecords = mutableListOf<MatchedDatabaseRecord>()

            if (conn != null) {
                try {
                    val query = """
                        SELECT * FROM MasterData 
                        WHERE (pan_no IS NOT NULL AND pan_no != '' AND pan_no = ?) 
                           OR (aadhar_no IS NOT NULL AND aadhar_no != '' AND aadhar_no = ?) 
                           OR (account_no IS NOT NULL AND account_no != '' AND account_no = ?) 
                           OR (contact_no IS NOT NULL AND contact_no != '' AND (contact_no = ? OR contact_no = ?))
                           OR (contactno2 IS NOT NULL AND contactno2 != '' AND (contactno2 = ? OR contactno2 = ?))
                    """
                    
                    val pstmt = conn.prepareStatement(query)
                    pstmt.setString(1, cleanPan)
                    pstmt.setString(2, cleanAadhar)
                    pstmt.setString(3, (newEmp.accountNo ?: "").trim())
                    pstmt.setString(4, (newEmp.contactNo ?: "").trim())
                    pstmt.setString(5, (newEmp.contactno2 ?: "").trim())
                    pstmt.setString(6, (newEmp.contactNo ?: "").trim())
                    pstmt.setString(7, (newEmp.contactno2 ?: "").trim())

                    val rs = pstmt.executeQuery()
                    while (rs.next()) {
                        val dbEmpId = rs.getString("emp_id") ?: rs.getString("id") ?: ""
                        val dbEmpName = rs.getString("emp_name") ?: rs.getString("employee_name") ?: ""
                        val dbFatherName = rs.getString("fathers_name") ?: ""
                        val dbBranch = rs.getString("branch_name") ?: ""
                        val dbDob = rs.getString("dob") ?: ""
                        val dbPan = rs.getString("pan_no") ?: ""
                        val dbAadhar = rs.getString("aadhar_no") ?: ""
                        val dbAccount = rs.getString("account_no") ?: ""
                        val dbPhone1 = rs.getString("contact_no") ?: ""
                        val dbPhone2 = rs.getString("contactno2") ?: ""

                        val matchedFields = mutableListOf<String>()

                        if (cleanPan.isNotBlank() && cleanPan.equals(dbPan, ignoreCase = true)) {
                            matchedFields.add("PAN Number")
                        }
                        if (cleanAadhar.isNotBlank() && cleanAadhar == dbAadhar.replace(Regex("\\s+"), "")) {
                            matchedFields.add("Aadhaar Number")
                        }
                        if (!newEmp.accountNo.isNullOrBlank() && newEmp.accountNo.trim() == dbAccount.trim()) {
                            matchedFields.add("Bank Account Number")
                        }
                        if (!newEmp.contactNo.isNullOrBlank() && (newEmp.contactNo.trim() == dbPhone1.trim() || newEmp.contactNo.trim() == dbPhone2.trim())) {
                            matchedFields.add("Phone Number 1")
                        }
                        if (!newEmp.contactno2.isNullOrBlank() && (newEmp.contactno2.trim() == dbPhone1.trim() || newEmp.contactno2.trim() == dbPhone2.trim())) {
                            matchedFields.add("Phone Number 2")
                        }
                        if (!newEmp.fathersName.isNullOrBlank() && newEmp.fathersName.trim().equals(dbFatherName.trim(), ignoreCase = true)) {
                            matchedFields.add("Father's Name")
                        }
                        if (!newEmp.dob.isNullOrBlank() && newEmp.dob.trim() == dbDob.trim()) {
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
                employeeName = newEmp.empName ?: "Unknown",
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