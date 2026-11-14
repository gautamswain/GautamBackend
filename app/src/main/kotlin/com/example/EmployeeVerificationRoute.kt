package com.example

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.util.Locale
import java.sql.Connection
import kotlinx.serialization.json.*

@kotlinx.serialization.Serializable
data class ComprehensiveVerificationReport(
    val employeeName: String,
    val isPanValid: Boolean,
    val panMessage: String,
    val panStatusIcon: String,
    val isAadharValid: Boolean,
    val aadharMessage: String,
    val aadharStatusIcon: String,
    val isAccountValid: Boolean,
    val accountMessage: String,
    val accountStatusIcon: String,
    val isIfscValid: Boolean,
    val ifscMessage: String,
    val ifscStatusIcon: String,
    val isDobValid: Boolean,
    val dobMessage: String,
    val dobStatusIcon: String,
    val isDojValid: Boolean,
    val dojMessage: String,
    val dojStatusIcon: String,
    val isBranchValid: Boolean,
    val branchMessage: String,
    val branchStatusIcon: String,
    val isDesignationValid: Boolean,
    val designationMessage: String,
    val designationStatusIcon: String,
    val hasDuplicates: Boolean,
    val duplicateStatusIcon: String,
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

private object MasterCache {
    private var cachedBranches: Set<String> = emptySet()
    private var cachedDesignations: Set<String> = emptySet()
    private var lastFetchTime: Long = 0L
    private const val TTL_MS = 10 * 60 * 1000L // 10 minutes

    @Synchronized
    fun getBranchesAndDesignations(conn: Connection): Pair<Set<String>, Set<String>> {
        val now = System.currentTimeMillis()
        if (cachedBranches.isNotEmpty() && cachedDesignations.isNotEmpty() && (now - lastFetchTime < TTL_MS)) {
            return Pair(cachedBranches, cachedDesignations)
        }

        val branches = mutableSetOf<String>()
        val designations = mutableSetOf<String>()

        try {
            val stmt = conn.createStatement()
            try {
                val branchRs = stmt.executeQuery("SELECT branchname FROM BranchDetails")
                while (branchRs.next()) {
                    val b = branchRs.getString(1)
                    if (!b.isNullOrBlank()) branches.add(b.trim().lowercase())
                }
                branchRs.close()
            } catch (_: Exception) {}

            try {
                val desigRs = stmt.executeQuery("SELECT designation FROM Designation")
                while (desigRs.next()) {
                    val d = desigRs.getString(1)
                    if (!d.isNullOrBlank()) designations.add(d.trim().lowercase())
                }
                desigRs.close()
            } catch (_: Exception) {}

            stmt.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (branches.isNotEmpty()) cachedBranches = branches
        if (designations.isNotEmpty()) cachedDesignations = designations
        lastFetchTime = now

        return Pair(cachedBranches, cachedDesignations)
    }
}

fun Route.employeeVerificationRoute() {
    post("/api/turso/comprehensive-verify") {
        try {
            val bodyText = call.receiveText()
            val jsonElement = Json.parseToJsonElement(bodyText).jsonObject

            val empName = jsonElement["emp_name"]?.jsonPrimitive?.content ?: jsonElement["empName"]?.jsonPrimitive?.content ?: ""
            val fathersName = jsonElement["fathers_name"]?.jsonPrimitive?.content ?: jsonElement["fathersName"]?.jsonPrimitive?.content ?: ""
            val rawDob = jsonElement["dob"]?.jsonPrimitive?.content ?: ""
            val rawDoj = jsonElement["doj_in_adhikar"]?.jsonPrimitive?.content ?: jsonElement["dojInAdhikar"]?.jsonPrimitive?.content ?: jsonElement["doj"]?.jsonPrimitive?.content ?: ""
            val rawPan = jsonElement["pan_no"]?.jsonPrimitive?.content ?: jsonElement["panNo"]?.jsonPrimitive?.content ?: ""
            val rawAadhar = jsonElement["aadhar_no"]?.jsonPrimitive?.content ?: jsonElement["aadharNo"]?.jsonPrimitive?.content ?: ""
            val rawAccount = jsonElement["account_no"]?.jsonPrimitive?.content ?: jsonElement["accountNo"]?.jsonPrimitive?.content ?: ""
            val rawIfsc = jsonElement["ifsc_code"]?.jsonPrimitive?.content ?: jsonElement["ifscCode"]?.jsonPrimitive?.content ?: jsonElement["ifsc"]?.jsonPrimitive?.content ?: ""
            val rawBranch = jsonElement["branch_name"]?.jsonPrimitive?.content ?: jsonElement["branchName"]?.jsonPrimitive?.content ?: ""
            val rawDesignation = jsonElement["designation"]?.jsonPrimitive?.content ?: ""
            val contactNo = jsonElement["contact_no"]?.jsonPrimitive?.content ?: jsonElement["contactNo"]?.jsonPrimitive?.content ?: ""
            val contactNo2 = jsonElement["contactno2"]?.jsonPrimitive?.content ?: ""

            // Clean inputs
            val cleanPan = rawPan.replace(Regex("[^A-Za-z0-9]"), "").trim().uppercase()
            val cleanAadhar = rawAadhar.replace(Regex("[^0-9]"), "").trim()
            val cleanAccount = rawAccount.replace(Regex("[^0-9]"), "").trim()
            val cleanIfsc = rawIfsc.replace(Regex("[^A-Za-z0-9]"), "").trim().uppercase()
            val cleanDob = rawDob.trim()
            val cleanDoj = rawDoj.trim()
            val cleanBranch = rawBranch.trim()
            val cleanDesignation = rawDesignation.trim()

            // 1. PAN Validation
            val panRegex = Regex("^[A-Z]{5}[0-9]{4}[A-Z]{1}$")
            val isPanValid = cleanPan.length == 10 && panRegex.matches(cleanPan)
            val panMessage = if (isPanValid) "PAN format and length are valid." else "Invalid PAN format or length."

            // 2. Aadhaar Validation
            val isAadharValid = VerhoeffValidator.isValid(cleanAadhar)
            val aadharMessage = if (isAadharValid) "Aadhaar number and checksum are valid." else "Invalid Aadhaar number or checksum."

            // 3. Bank Account Validation
            val accountRegex = Regex("^[0-9]{9,18}$")
            val isAccountValid = accountRegex.matches(cleanAccount)
            val accountMessage = if (isAccountValid) "Bank account number is valid." else "Invalid bank account number."

            // 4. IFSC Code Validation
            val ifscRegex = Regex("^[A-Z]{4}0[A-Z0-9]{6}$")
            val isIfscValid = ifscRegex.matches(cleanIfsc)
            val ifscMessage = if (isIfscValid) "IFSC code format is valid." else "Invalid IFSC code format."

            // Date parsing helper
            fun isValidDate(dateStr: String): Boolean {
                if (dateStr.isBlank()) return false
                val customFormatterD = DateTimeFormatterBuilder()
                    .appendPattern("d-MMM-")
                    .appendValueReduced(ChronoField.YEAR, 2, 2, 1950)
                    .toFormatter(Locale.ENGLISH)

                val customFormatterDd = DateTimeFormatterBuilder()
                    .appendPattern("dd-MMM-")
                    .appendValueReduced(ChronoField.YEAR, 2, 2, 1950)
                    .toFormatter(Locale.ENGLISH)

                val formatters = listOf(
                    customFormatterD,
                    customFormatterDd,
                    DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH),
                    DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
                    DateTimeFormatter.ISO_LOCAL_DATE,
                    DateTimeFormatter.ofPattern("dd-MM-yyyy"),
                    DateTimeFormatter.ofPattern("dd/MM/yyyy"),
                    DateTimeFormatter.ofPattern("dd.MM.yyyy")
                )
                for (fmt in formatters) {
                    try {
                        LocalDate.parse(dateStr, fmt)
                        return true
                    } catch (_: DateTimeParseException) {}
                }
                return false
            }

            val isDobValid = isValidDate(cleanDob)
            val dobMessage = if (isDobValid) "DOB format is valid." else "Invalid DOB format."

            val isDojValid = isValidDate(cleanDoj)
            val dojMessage = if (isDojValid) "DOJ format is valid." else "Invalid DOJ format."

            // Fetch DB Master lists for Branch and Designation verification using MasterCache (10-min TTL)
            val conn = TursoDatabaseService.getConnection()
            val (dbBranches, dbDesignations) = if (conn != null) {
                MasterCache.getBranchesAndDesignations(conn)
            } else {
                Pair(emptySet(), emptySet())
            }

            val normalizedBranch = cleanBranch.lowercase()
            val isBranchValid = cleanBranch.isNotBlank() && (dbBranches.isEmpty() || dbBranches.contains(normalizedBranch))
            val branchMessage = if (isBranchValid) "Branch name is valid." else "Branch name not found in BranchDetails master data."

            val normalizedDesignation = cleanDesignation.lowercase()
            val isDesignationValid = cleanDesignation.isNotBlank() && (dbDesignations.isEmpty() || dbDesignations.contains(normalizedDesignation))
            val designationMessage = if (isDesignationValid) "Designation is valid." else "Designation not found in master records."

            // Turso MasterData Duplicate Check (Optimized dynamic SQL WHERE query)
            val matchedRecords = mutableListOf<MatchedDatabaseRecord>()

            if (conn != null) {
                try {
                    val conditions = mutableListOf<String>()
                    val params = mutableListOf<Any>()

                    if (cleanPan.isNotBlank()) {
                        conditions.add("UPPER(TRIM(pan_card_no_17)) = ?")
                        params.add(cleanPan)
                    }
                    if (cleanAadhar.isNotBlank()) {
                        conditions.add("REPLACE(TRIM(aadhar_card_no_16), ' ', '') = ?")
                        params.add(cleanAadhar)
                    }
                    if (cleanAccount.isNotBlank()) {
                        conditions.add("TRIM(bank_account_number_20) = ?")
                        params.add(cleanAccount)
                    }
                    if (contactNo.isNotBlank()) {
                        conditions.add("(TRIM(personal_contact_number_14) = ? OR TRIM(contact_no_2_15) = ?)")
                        params.add(contactNo.trim())
                        params.add(contactNo.trim())
                    }
                    if (contactNo2.isNotBlank()) {
                        conditions.add("(TRIM(personal_contact_number_14) = ? OR TRIM(contact_no_2_15) = ?)")
                        params.add(contactNo2.trim())
                        params.add(contactNo2.trim())
                    }
                    if (fathersName.isNotBlank()) {
                        conditions.add("LOWER(TRIM(fathers_name_4)) = ?")
                        params.add(fathersName.trim().lowercase())
                    }

                    if (conditions.isNotEmpty()) {
                        val query = """
                            SELECT Code_2, employee_name_3, fathers_name_4, branch_name_9, pan_card_no_17, aadhar_card_no_16, bank_account_number_20, personal_contact_number_14, contact_no_2_15 
                            FROM MasterData 
                            WHERE ${conditions.joinToString(" OR ")} 
                            LIMIT 50
                        """.trimIndent()

                        val pstmt = conn.prepareStatement(query)
                        params.forEachIndexed { idx, p ->
                            pstmt.setString(idx + 1, p.toString())
                        }

                        val rs = pstmt.executeQuery()
                        while (rs.next()) {
                            val dbEmpId = rs.getString("Code_2") ?: ""
                            val dbEmpName = rs.getString("employee_name_3") ?: ""
                            val dbFatherName = rs.getString("fathers_name_4") ?: ""
                            val dbBranch = rs.getString("branch_name_9") ?: ""
                            val dbPan = rs.getString("pan_card_no_17") ?: ""
                            val dbAadhar = rs.getString("aadhar_card_no_16") ?: ""
                            val dbAccount = rs.getString("bank_account_number_20") ?: ""
                            val dbPhone1 = rs.getString("personal_contact_number_14") ?: ""
                            val dbPhone2 = rs.getString("contact_no_2_15") ?: ""

                            val matchedFields = mutableListOf<String>()
                            if (cleanPan.isNotBlank() && cleanPan.equals(dbPan.trim(), ignoreCase = true)) matchedFields.add("PAN Number")
                            if (cleanAadhar.isNotBlank() && cleanAadhar == dbAadhar.replace(Regex("[^0-9]"), "")) matchedFields.add("Aadhaar Number")
                            if (cleanAccount.isNotBlank() && cleanAccount == dbAccount.trim()) matchedFields.add("Bank Account Number")
                            if (contactNo.isNotBlank() && (contactNo.trim() == dbPhone1.trim() || contactNo.trim() == dbPhone2.trim())) matchedFields.add("Phone Number 1")
                            if (contactNo2.isNotBlank() && (contactNo2.trim() == dbPhone1.trim() || contactNo2.trim() == dbPhone2.trim())) matchedFields.add("Phone Number 2")
                            if (fathersName.isNotBlank() && fathersName.trim().equals(dbFatherName.trim(), ignoreCase = true)) matchedFields.add("Father's Name")

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
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    try { conn.close() } catch (_: Exception) {}
                }
            }

            val hasDuplicates = matchedRecords.isNotEmpty()
            val allValid = isPanValid && isAadharValid && isAccountValid && isIfscValid && isDobValid && isDojValid && isBranchValid && isDesignationValid && !hasDuplicates

            val summary = buildString {
                append(if (hasDuplicates) "Duplicate Warning: Found ${matchedRecords.size} matching record(s). " else "No duplicate records found. ")
                append(if (allValid) "All formats and entity checks are valid." else "Validation failures or mismatches detected.")
            }

            val report = ComprehensiveVerificationReport(
                employeeName = empName.ifBlank { "Unknown" },
                isPanValid = isPanValid,
                panMessage = panMessage,
                panStatusIcon = if (isPanValid) "👍" else "👎",
                isAadharValid = isAadharValid,
                aadharMessage = aadharMessage,
                aadharStatusIcon = if (isAadharValid) "👍" else "👎",
                isAccountValid = isAccountValid,
                accountMessage = accountMessage,
                accountStatusIcon = if (isAccountValid) "👍" else "👎",
                isIfscValid = isIfscValid,
                ifscMessage = ifscMessage,
                ifscStatusIcon = if (isIfscValid) "👍" else "👎",
                isDobValid = isDobValid,
                dobMessage = dobMessage,
                dobStatusIcon = if (isDobValid) "👍" else "👎",
                isDojValid = isDojValid,
                dojMessage = dojMessage,
                dojStatusIcon = if (isDojValid) "👍" else "👎",
                isBranchValid = isBranchValid,
                branchMessage = branchMessage,
                branchStatusIcon = if (isBranchValid) "👍" else "👎",
                isDesignationValid = isDesignationValid,
                designationMessage = designationMessage,
                designationStatusIcon = if (isDesignationValid) "👍" else "👎",
                hasDuplicates = hasDuplicates,
                duplicateStatusIcon = if (!hasDuplicates) "👍" else "👎",
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