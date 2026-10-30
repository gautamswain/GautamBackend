package com.example

import kotlinx.serialization.Serializable
import java.sql.Connection

@Serializable
data class StaffDetailDto(
    val empId: String,
    val employeeName: String,
    val designation: String
)

@Serializable
data class BranchStaffSummary(
    val branchName: String,
    val branchManagerCount: Int,
    val assistantBranchManagerCount: Int,
    val creditOfficerTotalCount: Int,
    val staffList: List<StaffDetailDto>
)

object BranchSummaryService {
    
    fun getActiveBranchSummary(getConnection: () -> Connection?): List<BranchStaffSummary> {
        val conn = getConnection() ?: return emptyList()
        
        val branchStaffMap = mutableMapOf<String, MutableList<StaffDetailDto>>()
        val branchCountsMap = mutableMapOf<String, MutableMap<String, Int>>()

        try {
            val stmt = conn.createStatement()
            val rs = stmt.executeQuery("SELECT * FROM MasterData;")

            while (rs.next()) {
                val metaData = rs.metaData
                val count = metaData.columnCount
                val map = mutableMapOf<String, String>()
                for (i in 1..count) {
                    val colName = metaData.getColumnName(i).lowercase().trim()
                    map[colName] = rs.getString(i) ?: ""
                }

                fun getVal(vararg keys: String): String {
                    for (key in keys) {
                        val value = map[key.lowercase()]
                        if (!value.isNullOrBlank()) return value
                    }
                    return ""
                }

                val empStatus = getVal("emp_status_35", "emp_status")
                val empStatusTemp = getVal("emp_status_temp_36", "emp_status_temp")
                val salaryStatus = getVal("salary_status")

                val s1 = if (empStatus.trim() == "1" || empStatus.trim().equals("active", ignoreCase = true)) 1 else 0
                val s2 = if (empStatusTemp.trim() == "1" || empStatusTemp.trim().equals("active", ignoreCase = true)) 1 else 0
                val s3 = if (salaryStatus.trim() == "1" || salaryStatus.trim().equals("active", ignoreCase = true)) 1 else 0

                if ((s1 + s2 + s3) > 1) {
                    val branch = getVal("branch_name_9", "branch_name", "branch").ifBlank { "Other Branch" }
                    val rawDesignation = getVal("designation_7", "designation")
                    val designationLower = rawDesignation.lowercase().trim()
                    
                    val empId = getVal("emp_id_2", "emp_id", "empolyee_code", "code")
                    val empName = getVal("employee_name_3", "employee_name")

                    branchStaffMap.getOrPut(branch) { mutableListOf() }.add(
                        StaffDetailDto(empId = empId, employeeName = empName, designation = rawDesignation)
                    )

                    val counts = branchCountsMap.getOrPut(branch) {
                        mutableMapOf("bm" to 0, "abm" to 0, "co" to 0)
                    }

                    // Sothik Krama: Prathame Assistant Branch Manager ku check karibe
                    when {
                        designationLower.contains("assistant branch manager") || designationLower.contains("abm") -> {
                            counts["abm"] = counts["abm"]!! + 1
                        }
                        designationLower.contains("branch manager") || designationLower == "bm" -> {                        
                            counts["bm"] = counts["bm"]!! + 1
                        }
                        designationLower.contains("credit officer") || designationLower.contains("senior credit officer") || designationLower.contains("co") || designationLower.contains("sco") -> {
                            counts["co"] = counts["co"]!! + 1
                        }
                    }
                }
            }
            rs.close()
            stmt.close()

            val allBranches = (branchCountsMap.keys + branchStaffMap.keys).distinct()

            return allBranches.map { branch ->
                val counts = branchCountsMap[branch] ?: mapOf("bm" to 0, "abm" to 0, "co" to 0)
                BranchStaffSummary(
                    branchName = branch,
                    branchManagerCount = counts["bm"] ?: 0,
                    assistantBranchManagerCount = counts["abm"] ?: 0,
                    creditOfficerTotalCount = counts["co"] ?: 0,
                    staffList = branchStaffMap[branch] ?: emptyList()
                )
            }.sortedBy { it.branchName }

        } catch (e: Exception) {
            return emptyList()
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }
}