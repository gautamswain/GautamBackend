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

object VerhoeffValidator {
    private val d = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
        intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
        intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6),
        intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
        intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8),
        intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
        intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2),
        intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
        intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4),
        intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0)
    )

    private val p = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
        intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
        intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2),
        intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
        intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0),
        intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
        intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5),
        intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8)
    )

    fun isValid(num: String): Boolean {
        if (num.length != 12 || !num.all { it.isDigit() }) return false
        var c = 0
        val myArray = num.reversed().mapNotNull { it.digitToIntOrNull() }
        if (myArray.size != 12) return false
        for (i in myArray.indices) {
            c = d[c][p[i % 8][myArray[i]]]
        }
        return c == 0
    }
}

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

            // 2. Aadhaar Validation with Verhoeff Checksum ([Aadhaar Redacted])
            val cleanAadhar = aadharNo.replace(Regex("\\s+"), "").trim()
            val isAadharValid = VerhoeffValidator.isValid(cleanAadhar)
            val aadharMessage = if (isAadharValid) {
                "Aadhaar number and checksum combination are valid."
            } else if (cleanAadhar.length != 12) {
                "Invalid Aadhaar length (Must be 12 digits)."
            } else {
                "Invalid Aadhaar number combination (checksum failed)."
            }

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