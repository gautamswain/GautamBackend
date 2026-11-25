package com.example

import io.ktor.http.*
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet

@Serializable
data class AttendanceVerifyInput(
    val code: String = "",
    val name: String = "",
    val designation: String = "",
    val department: String = "",
    val branch: String = "",
    val lender: String = "",
    val doj: String = ""
)

@Serializable
data class AttendanceVerifySingleResult(
    val code: String,
    val salary: String = "",
    val mismatches: List<String> = emptyList(),
    val salaryHistory: String = "",
    val saltier: String = ""
)

@Serializable
data class AttendanceVerifyResponse(
    val results: List<AttendanceVerifySingleResult>
)

object AttendanceVerifyService {
    private fun normalizeDate(raw: String?): String {
        val clean = raw?.trim().orEmpty()
        if (clean.isBlank()) return ""
        val d = DateUtils.toDisplayDate(clean)
        return d.ifBlank { clean }
    }

    private fun fetchSaltier(conn: Connection, designation: String, department: String = ""): String {
        val clean = designation.trim()
        if (clean.isBlank()) return ""
        try {
            val sql = """
                SELECT saltier FROM Designation 
                WHERE UPPER(TRIM(designation)) = UPPER(TRIM(?)) OR UPPER(TRIM(shtcode)) = UPPER(TRIM(?))
                ORDER BY CASE WHEN UPPER(TRIM(department)) = UPPER(TRIM(?)) THEN 0 ELSE 1 END
                LIMIT 1
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, clean)
                ps.setString(2, clean)
                ps.setString(3, department.trim())
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        return rs.getString(1)?.trim().orEmpty()
                    }
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun fetchLastThreeSalaryHistory(conn: Connection, codes: List<String>): Map<String, String> {
        if (codes.isEmpty()) return emptyMap()
        val inClause = codes.joinToString(",") { "?" }
        val sql = """
            SELECT slno, month, emp_id_2, designation_7, branch_name_9, gross_earnings_28, gross_deductions_29, net_amount_30, status_00
            FROM SalaryHistory
            WHERE UPPER(TRIM(emp_id_2)) IN ($inClause)
            ORDER BY CAST(slno AS INTEGER) DESC, rowid DESC
        """.trimIndent()

        val recordsByEmp = mutableMapOf<String, MutableList<String>>()

        try {
            conn.prepareStatement(sql).use { ps ->
                codes.forEachIndexed { idx, code ->
                    ps.setString(idx + 1, code.trim().uppercase())
                }
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val empId = (rs.getString("emp_id_2") ?: "").trim().uppercase()
                        if (empId.isNotBlank()) {
                            val month = (rs.getString("month") ?: "").trim()
                            val desig = (rs.getString("designation_7") ?: "").trim()
                            val branch = (rs.getString("branch_name_9") ?: "").trim()
                            val grossEarn = (rs.getString("gross_earnings_28") ?: "").trim()
                            val grossDed = (rs.getString("gross_deductions_29") ?: "").trim()
                            val netAmt = (rs.getString("net_amount_30") ?: "").trim()
                            val status = (rs.getString("status_00") ?: "").trim()

                            val formatted = "$month-$desig-$branch-$grossEarn,$grossDed,$netAmt,$status"
                            recordsByEmp.getOrPut(empId) { mutableListOf() }.add(formatted)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val historyMap = mutableMapOf<String, String>()
        for ((empId, list) in recordsByEmp) {
            val lastThree = list.take(3)
            historyMap[empId] = lastThree.joinToString(", ")
        }
        return historyMap
    }

    fun verify(conn: Connection, inputs: List<AttendanceVerifyInput>): AttendanceVerifyResponse {
        val results = mutableListOf<AttendanceVerifySingleResult>()
        val codes = inputs.map { it.code.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
        val historyMap = fetchLastThreeSalaryHistory(conn, codes)

        for (input in inputs) {
            val cleanCode = input.code.trim()
            if (cleanCode.isBlank()) {
                results.add(
                    AttendanceVerifySingleResult(
                        code = input.code,
                        salary = "",
                        mismatches = listOf("Code is blank"),
                        salaryHistory = "",
                        saltier = ""
                    )
                )
                continue
            }

            var dto: MasterDataDto? = null
            try {
                val sql = "SELECT * FROM MasterData WHERE UPPER(TRIM(Code_2)) = UPPER(TRIM(?)) OR UPPER(TRIM(id)) = UPPER(TRIM(?)) LIMIT 1"
                conn.prepareStatement(sql).use { ps ->
                    ps.setString(1, cleanCode)
                    ps.setString(2, cleanCode)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            dto = StaffRepository.parseMasterDataDto(rs)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (dto == null) {
                results.add(
                    AttendanceVerifySingleResult(
                        code = cleanCode,
                        salary = "",
                        mismatches = listOf("Code not found in MasterData"),
                        salaryHistory = historyMap[cleanCode.uppercase()].orEmpty(),
                        saltier = ""
                    )
                )
                continue
            }

            val d = dto
            val mismatches = mutableListOf<String>()

            if (input.name.isNotBlank() && !input.name.trim().equals(d.employeeName.trim(), ignoreCase = true)) {
                mismatches.add("Name mismatch: input '${input.name}' vs db '${d.employeeName}'")
            }
            if (input.designation.isNotBlank() && !input.designation.trim().equals(d.designation.trim(), ignoreCase = true)) {
                mismatches.add("Designation mismatch: input '${input.designation}' vs db '${d.designation}'")
            }
            if (input.department.isNotBlank() && !input.department.trim().equals(d.department.trim(), ignoreCase = true)) {
                mismatches.add("Department mismatch: input '${input.department}' vs db '${d.department}'")
            }
            if (input.branch.isNotBlank() && !input.branch.trim().equals(d.branchName.trim(), ignoreCase = true)) {
                mismatches.add("Branch mismatch: input '${input.branch}' vs db '${d.branchName}'")
            }
            if (input.lender.isNotBlank() && !input.lender.trim().equals(d.lender.trim(), ignoreCase = true)) {
                mismatches.add("Lender mismatch: input '${input.lender}' vs db '${d.lender}'")
            }
            val inputDojNorm = normalizeDate(input.doj)
            val dbDojNorm = normalizeDate(d.dojInAdhikar)
            if (inputDojNorm.isNotBlank() && dbDojNorm.isNotBlank() && inputDojNorm != dbDojNorm) {
                mismatches.add("DOJ mismatch: input '${input.doj}' vs db '${d.dojInAdhikar}'")
            }

            val salary = d.salaryCode.ifBlank { d.slary }
            val salaryHistory = historyMap[cleanCode.uppercase()].orEmpty()
            val saltier = fetchSaltier(conn, d.designation, d.department)

            results.add(
                AttendanceVerifySingleResult(
                    code = cleanCode,
                    salary = salary,
                    mismatches = mismatches,
                    salaryHistory = salaryHistory,
                    saltier = saltier
                )
            )
        }

        return AttendanceVerifyResponse(results = results)
    }
}