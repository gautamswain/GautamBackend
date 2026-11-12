package com.example

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection

@Serializable
data class ReportResult(
    val status: String,
    val idRecord: String,
    val totalUniqueCodes: Int,
    val codes: List<String>,
    val totalMasterActive: Int = 0,
    val masterActiveCodes: List<String> = emptyList(),
    val totalCommonCodes: Int = 0,
    val commonCodes: List<String> = emptyList(),
    val totalLeftSalaryCodes: Int = 0,
    val leftSalaryCodes: List<String> = emptyList(),
    val totalRecentJoinedCodes: Int = 0,
    val recentJoinedCodes: List<String> = emptyList(),
    val totalBranchwiseCodes: Int = 0,
    val branchwiseCodes: List<String> = emptyList(),
    val totalHeadOfficeCodes: Int = 0,
    val headOfficeCodes: List<String> = emptyList()
)

object ReportMeService {

    fun last_two_month_all_code(conn: Connection): List<String> {
        val uniqueCodes = mutableSetOf<String>()
        val query = "SELECT month, emp_id_2 FROM SalaryHistory ORDER BY rowid DESC"
        
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery(query)
            val allRows = mutableListOf<Pair<String, String>>()
            
            while (rs.next()) {
                val month = rs.getString(1) ?: ""
                val empId = rs.getString(2) ?: ""
                allRows.add(Pair(month, empId))
            }
            rs.close()
            
            val distinctMonths = allRows.map { it.first }.filter { it.isNotBlank() }.distinct().take(2)
            
            for (row in allRows) {
                val month = row.first
                val empId = row.second
                if (month in distinctMonths && empId.isNotBlank() && empId != "0") {
                    uniqueCodes.add(empId.trim())
                }
            }
        }
        return uniqueCodes.toList()
    }

    fun Master_Active(conn: Connection): List<String> {
        val activeCodes = mutableSetOf<String>()
        conn.createStatement().use { stmt ->
            try {
                val query = "SELECT Code_2 FROM MasterData WHERE emp_status_35 = '1' OR emp_status_35 = 1"
                val rs = stmt.executeQuery(query)
                while (rs.next()) {
                    val code = rs.getString(1) ?: ""
                    if (code.isNotBlank() && code != "0") {
                        activeCodes.add(code.trim())
                    }
                }
                rs.close()
            } catch (_: Exception) {
                try {
                    val rs = stmt.executeQuery("SELECT Code_2 FROM MasterData")
                    while (rs.next()) {
                        val code = rs.getString(1) ?: ""
                        if (code.isNotBlank() && code != "0") {
                            activeCodes.add(code.trim())
                        }
                    }
                    rs.close()
                } catch (_: Exception) {}
            }
        }
        return activeCodes.toList()
    }

    fun Sal_Mast_Common(list1a: List<String>, list1b: List<String>): List<String> {
        val setB = list1b.toSet()
        return list1a.filter { it in setB }.distinct()
    }

    fun Left_Salary(list1a: List<String>, list1b: List<String>): List<String> {
        val setB = list1b.toSet()
        return list1a.filter { it !in setB }.distinct()
    }

    fun Recent_Joined(list1a: List<String>, list1b: List<String>): List<String> {
        val setA = list1a.toSet()
        return list1b.filter { it !in setA }.distinct()
    }
//-------------------------------------------------------------------------------

  // 1f & 1g: Branchwisedata (BranchDetails ru gotie gotie kari padhi branchwise list ku update kariba)
    fun Branchwisedata(conn: Connection, activeCodes: List<String>): Pair<List<String>, List<String>> {
        val branchwiseList = mutableListOf<String>() // Prathame (code, branchname, designation) rahiba
        val headOfficeList = mutableListOf<String>()
        val activeSet = activeCodes.map { it.trim().uppercase() }.toSet()

        fun normalizeName(name: String): String {
            return name.trim().lowercase().replace(Regex("\\s+"), " ")
        }

        conn.createStatement().use { stmt ->
            // 1. MasterData ru active staff fetch kari branchwiseList o headOfficeList banai neba
            val seenIds = mutableSetOf<String>()
            try {
                val empRs = stmt.executeQuery("SELECT * FROM MasterData")
                while (empRs.next()) {
                    val dto = StaffRepository.parseMasterDataDto(empRs)
                    val empId = dto.empId.trim()
                    val empIdUpper = empId.uppercase()
                    val designation = dto.designation.trim()
                    val branch = dto.branchName.trim()

                    if (activeSet.contains(empIdUpper) && empId.isNotBlank() && empId != "0" && seenIds.add(empIdUpper)) {
                        if (branch.contains("head office", ignoreCase = true) || branch.equals("ho", ignoreCase = true)) {
                            headOfficeList.add("$empId, $branch, $designation") // 1g
                        } else {
                            branchwiseList.add("$empId, $branch, $designation") // 1f (initial format)
                        }
                    }
                }
                empRs.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 2. Ebe BranchDetails ru sabu record gotie gotie kari padhiba (slno, branchname)
            try {
                val branchRs = stmt.executeQuery("SELECT slno, branchname FROM BranchDetails")
                while (branchRs.next()) {
                    val recordNo = (branchRs.getString("slno") ?: "").trim()
                    val dbBranchName = (branchRs.getString("branchname") ?: "").trim()

                    if (dbBranchName.isNotBlank()) {
                        val cleanDbBranch = normalizeName(dbBranchName)

                        // branchwiseList re jeunthi branchname match kariba, taku change kari branchno jodi deba
                        for (i in branchwiseList.indices) {
                            val parts = branchwiseList[i].split(",").map { it.trim() }
                            // parts[0] = code, parts[1] = branchname, parts[2] = designation
                            if (parts.size == 3 && normalizeName(parts[1]) == cleanDbBranch) {
                                branchwiseList[i] = "${parts[0]}, ${parts[1]}, ${parts[2]}, $recordNo"
                            }
                        }
                    }
                }
                branchRs.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 3. Branchno (1, 2, 3...) krama anuyayi sajai deba (jadi sorting darkar na thae tebe ehi 4 line kati paribe)
        val sortedBranchwiseList = branchwiseList.sortedBy { record ->
            val parts = record.split(",").map { it.trim() }
            if (parts.size >= 4) parts[3].toIntOrNull() ?: 9999 else 9999
        }

        return Pair(sortedBranchwiseList, headOfficeList)
    }

//-----------------------------------------------------------------------------------------------
    fun generateAndSaveReport(conn: Connection, x: String, y: String, fieldName: String = "a") {
        val safeX = x.replace("'", "''")
        val safeY = y.replace("'", "''")
        
        conn.createStatement().use { stmt ->
            try {
                val updated = stmt.executeUpdate("UPDATE reports SET $fieldName = '$safeY' WHERE id = '$safeX'")
                if (updated == 0) {
                    stmt.executeUpdate("INSERT INTO reports (id, cr, $fieldName) VALUES ('$safeX', 'm', '$safeY')")
                }
            } catch (_: Exception) {
                val updated = stmt.executeUpdate("UPDATE reports SET $fieldName = '$safeY' WHERE emp_id = '$safeX'")
                if (updated == 0) {
                    stmt.executeUpdate("INSERT INTO reports (emp_id, $fieldName) VALUES ('$safeX', '$safeY')")
                }
            }
        }
    }

    fun generateAndSaveReport(conn: Connection): ReportResult = processAndSave(conn)

    fun processAndSave(conn: Connection): ReportResult {
        var codesList = listOf<String>()
        var activeList = listOf<String>()
        var commonList = listOf<String>()
        var leftSalaryList = listOf<String>()
        var recentJoinedList = listOf<String>()
        var branchwiseList = listOf<String>()
        var headOfficeList = listOf<String>()

        val x = "1" // 1 no record

        codesList = last_two_month_all_code(conn)
        generateAndSaveReport(conn, x, Json.encodeToString(codesList), "a")

        activeList = Master_Active(conn)
        generateAndSaveReport(conn, x, Json.encodeToString(activeList), "b")

        commonList = Sal_Mast_Common(codesList, activeList)
        generateAndSaveReport(conn, x, Json.encodeToString(commonList), "c")

        leftSalaryList = Left_Salary(codesList, activeList)
        generateAndSaveReport(conn, x, Json.encodeToString(leftSalaryList), "d")

        recentJoinedList = Recent_Joined(codesList, activeList)
        generateAndSaveReport(conn, x, Json.encodeToString(recentJoinedList), "e")

        // 1f & 1g: Branchwise (f) ଏବଂ Head Office (g) ଅଲଗା କରିବା
        val (bList, hList) = Branchwisedata(conn, activeList)
        branchwiseList = bList
        headOfficeList = hList
        generateAndSaveReport(conn, x, Json.encodeToString(branchwiseList), "f")
        generateAndSaveReport(conn, x, Json.encodeToString(headOfficeList), "g")

        return ReportResult(
            status = "success",
            idRecord = x,
            totalUniqueCodes = codesList.size,
            codes = codesList,
            totalMasterActive = activeList.size,
            masterActiveCodes = activeList,
            totalCommonCodes = commonList.size,
            commonCodes = commonList,
            totalLeftSalaryCodes = leftSalaryList.size,
            leftSalaryCodes = leftSalaryList,
            totalRecentJoinedCodes = recentJoinedList.size,
            recentJoinedCodes = recentJoinedList,
            totalBranchwiseCodes = branchwiseList.size,
            branchwiseCodes = branchwiseList,
            totalHeadOfficeCodes = headOfficeList.size,
            headOfficeCodes = headOfficeList
        )
    }
}