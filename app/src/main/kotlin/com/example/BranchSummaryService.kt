package com.example

import kotlinx.serialization.Serializable
import java.sql.Connection

@Serializable
data class StaffDetailDto(
    val empId: String,
    val employeeName: String,
    val designation: String,
    val phoneNo1: String,
    val phoneNo2: String
)

@Serializable
data class BranchStaffSummary(
    val branchName: String,
    val clusterManagerCount: Int,
    val areaManagerCount: Int,
    val branchManagerCount: Int,
    val assistantBranchManagerCount: Int,
    val seniorCreditOfficerCount: Int,
    val creditOfficerCount: Int,
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
                // Using centralized StaffRepository parser
                val dto = StaffRepository.parseMasterDataDto(rs)
                val statusScore = StaffRepository.calculateStatusScore(dto)

                if (statusScore > 1) {
                    val branch = dto.branchName.ifBlank { "Other Branch" }
                    val designationLower = dto.designation.lowercase().trim()

                    branchStaffMap.getOrPut(branch) { mutableListOf() }.add(
                        StaffDetailDto(
                            empId = dto.empId, 
                            employeeName = dto.employeeName, 
                            designation = dto.designation,
                            phoneNo1 = dto.personalContactNumber,
                            phoneNo2 = dto.contactNo2
                        )
                    )

                    val counts = branchCountsMap.getOrPut(branch) {
                        mutableMapOf("cm" to 0, "am" to 0, "bm" to 0, "abm" to 0, "sco" to 0, "co" to 0)
                    }

                    when {
                        designationLower.contains("cluster manager") || designationLower == "cm" -> counts["cm"] = counts["cm"]!! + 1
                        designationLower.contains("area manager") || designationLower == "am" -> counts["am"] = counts["am"]!! + 1
                        designationLower.contains("assistant branch manager") || designationLower.contains("abm") -> counts["abm"] = counts["abm"]!! + 1
                        designationLower.contains("branch manager") || designationLower == "bm" -> counts["bm"] = counts["bm"]!! + 1
                        designationLower.contains("senior credit officer") || designationLower.contains("senior credit manager") || designationLower.contains("sco") -> counts["sco"] = counts["sco"]!! + 1
                        designationLower.contains("credit officer") || designationLower.contains("credit manager") || designationLower == "co" -> counts["co"] = counts["co"]!! + 1
                    }
                }
            }
            rs.close()
            stmt.close()

            val allBranches = (branchCountsMap.keys + branchStaffMap.keys).distinct()

            return allBranches.map { branch ->
                val counts = branchCountsMap[branch] ?: mapOf("cm" to 0, "am" to 0, "bm" to 0, "abm" to 0, "sco" to 0, "co" to 0)
                BranchStaffSummary(
                    branchName = branch,
                    clusterManagerCount = counts["cm"] ?: 0,
                    areaManagerCount = counts["am"] ?: 0,
                    branchManagerCount = counts["bm"] ?: 0,
                    assistantBranchManagerCount = counts["abm"] ?: 0,
                    seniorCreditOfficerCount = counts["sco"] ?: 0,
                    creditOfficerCount = counts["co"] ?: 0,
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