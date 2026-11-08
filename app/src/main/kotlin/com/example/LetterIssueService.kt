package com.example

import java.sql.Connection
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object LetterIssueService {

    fun getLetterIssueData(empId: String, getConnection: () -> Connection?): Map<String, Any?>? {
        val conn = getConnection() ?: return null
        var resultData: Map<String, Any?>? = null

        try {
            // ୧. MasterData ରୁ ଷ୍ଟାଫ୍ ଡାଟା ଆଣିବା
            val pstmt = conn.prepareStatement("SELECT * FROM MasterData WHERE Code_2 = ?")
            pstmt.setString(1, empId.trim())
            val rs = pstmt.executeQuery()

            if (rs.next()) {
                val name = rs.getString("employee_name_3") ?: ""
                val code = rs.getString("Code_2") ?: ""
                val department = rs.getString("department_8") ?: ""
                val designation = rs.getString("designation_7") ?: ""
                val sBranch = rs.getString("branch_name_9") ?: ""
                val dBranch = sBranch // କରେଣ୍ଟ୍ ବ୍ରାଞ୍ଚ
                val promotedTo = rs.getString("designationbackcompany_27") ?: ""
                val dojStr = rs.getString("doj_in_adhikar_10") ?: ""
                val dol = rs.getString("dol_25") ?: ""
                val wef = rs.getString("emp_status_temp_36") ?: ""
                val salary = rs.getString("salary_code_47") ?: ""
                
                // Reporting: DOJ - 1 day calculate କରିବା
                val reportingDate = try {
                    val parsedDoj = LocalDate.parse(dojStr.take(10))
                    parsedDoj.minusDays(1).format(DateTimeFormatter.ofPattern("dd-MM-yyyy"))
                } catch (_: Exception) {
                    dojStr
                }

                // Probation Period: Designation ଟେବୁଲ୍‌ରୁ probday ଆଣିବା (Default 45 days)
                var probationDays = "45"
                try {
                    val probStmt = conn.prepareStatement("SELECT probday FROM DesignationTable WHERE designation_name = ?")
                    probStmt.setString(1, designation)
                    val probRs = probStmt.executeQuery()
                    if (probRs.next()) {
                        probationDays = probRs.getString("probday") ?: "45"
                    }
                    probRs.close()
                    probStmt.close()
                } catch (_: Exception) {}

                val fname = rs.getString("fathers_name_4") ?: ""
                val address = rs.getString("present_address_11") ?: ""
                val mobile = rs.getString("personal_contact_number_14") ?: ""
                val gender = (rs.getString("gender_5") ?: "").lowercase()
                
                // He-She / His-Her ଡିଜାଇନ୍
                val heShe = if (gender.contains("female") || gender.contains("f")) "She" else "He"
                val hisHer = if (gender.contains("female") || gender.contains("f")) "Her" else "His"

                val todayDate = LocalDate.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy"))
                val interviewDate = rs.getString("interviewed_31") ?: ""

                // Branch responsible: BranchDetails ଟେବୁଲ୍‌ରୁ bis, area, zone, bh ଆଣିବା
                var bis = ""
                var area = ""
                var zone = ""
                var bh = ""
                try {
                    val branchStmt = conn.prepareStatement("SELECT bis, area, zone, bh FROM BranchDetails WHERE branch_code = ? OR branch_name = ?")
                    branchStmt.setString(1, sBranch)
                    branchStmt.setString(2, sBranch)
                    val branchRs = branchStmt.executeQuery()
                    if (branchRs.next()) {
                        bis = branchRs.getString("bis") ?: ""
                        area = branchRs.getString("area") ?: ""
                        zone = branchRs.getString("zone") ?: ""
                        bh = branchRs.getString("bh") ?: ""
                    }
                    branchRs.close()
                    branchStmt.close()
                } catch (_: Exception) {}

                // Map ତିଆରି କରିବା
                resultData = mapOf(
                    "\$1\$" to name,
                    "\$2\$" to code,
                    "\$3\$" to department,
                    "\$4\$" to designation,
                    "\$5\$" to sBranch,
                    "\$6\$" to dBranch,
                    "\$7\$" to promotedTo,
                    "\$8\$" to dojStr,
                    "\$9\$" to dol,
                    "\$10\$" to wef,
                    "\$11\$" to salary,
                    "\$12\$" to reportingDate,
                    "\$13\$" to probationDays,
                    "\$14\$" to fname,
                    "\$15\$" to address,
                    "\$16\$" to mobile,
                    "\$17\$" to heShe,
                    "\$18\$" to hisHer,
                    "\$19\$" to todayDate,
                    "\$20\$" to mapOf("bis" to bis, "area" to area, "zone" to zone, "bh" to bh),
                    "\$21\$" to interviewDate
                )
            }
            rs.close()
            pstmt.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }

        return resultData
    }
}