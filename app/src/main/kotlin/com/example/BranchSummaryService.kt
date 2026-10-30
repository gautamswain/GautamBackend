package com.example

import kotlinx.serialization.Serializable
import java.sql.Connection

@Serializable
data class BranchStaffSummary(
    val branchName: String,
    val branchManagerCount: Int,
    val assistantBranchManagerCount: Int,
    val creditOfficerTotalCount: Int
)

object BranchSummaryService {
    
    // Active staff nkara branch-wise summary baher kariba pain function
    fun getActiveBranchSummary(getConnection: () -> Connection?): List<BranchStaffSummary> {
        val conn = getConnection() ?: return emptyList()
        val summaryMap = mutableMapOf<String, MutableMap<String, Int>>()

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

                // Check Active Status (score == 3)
                val empStatus = getVal("emp_status_35", "emp_status")
                val empStatusTemp = getVal("emp_status_temp_36", "emp_status_temp")
                val salaryStatus = getVal("salary_status")

                val s1 = if (empStatus.trim() == "1" || empStatus.trim().equals("active", ignoreCase = true)) 1 else 0
                val s2 = if (empStatusTemp.trim() == "1" || empStatusTemp.trim().equals("active", ignoreCase = true)) 1 else 0
                val s3 = if (salaryStatus.trim() == "1" || salaryStatus.trim().equals("active", ignoreCase = true)) 1 else 0

                // Yadi active staff hie thebe
                if ((s1 + s2 + s3) > 1) {
                    val branch = getVal("branch_name_9", "branch_name", "branch").ifBlank { "Other Branch" }
                    val designation = getVal("designation_7", "designation").lowercase().trim()

                    val branchData = summaryMap.getOrPut(branch) {
                        mutableMapOf("bm" to 0, "abm" to 0, "co" to 0)
                    }

                    when {
                        designation.contains("branch manager") || designation == "bm" -> {
                            branchData["bm"] = branchData["bm"]!! + 1
                        }
                        designation.contains("assistant branch manager") || designation.contains("abm") -> {
                            branchData["abm"] = branchData["abm"]!! + 1
                        }
                        designation.contains("credit officer") || designation.contains("senior credit officer") || designation.contains("co") || designation.contains("sco") -> {
                            branchData["co"] = branchData["co"]!! + 1
                        }
                    }
                }
            }
            rs.close()
            stmt.close()

            return summaryMap.map { (branch, counts) ->
                BranchStaffSummary(
                    branchName = branch,
                    branchManagerCount = counts["bm"] ?: 0,
                    assistantBranchManagerCount = counts["abm"] ?: 0,
                    creditOfficerTotalCount = counts["co"] ?: 0
                )
            }.sortedBy { it.branchName }

        } catch (e: Exception) {
            return emptyList()
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }
}