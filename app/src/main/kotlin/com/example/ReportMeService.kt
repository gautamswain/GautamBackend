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

  // 1f & 1g: Branchwisedata (Exact BranchDetails 'slno' & 'branchname' Sorting + Head Office Separation)
    fun Branchwisedata(conn: Connection, activeCodes: List<String>): Pair<List<String>, List<String>> {
        val rawBranchEmployees = mutableListOf<Triple<String, String, String>>() // empId, branch, designation
        val headOfficeList = mutableListOf<String>()
        val activeSet = activeCodes.map { it.trim().uppercase() }.toSet()

        // ବ୍ରାଞ୍ଚ୍ ନାମର extra space ଓ ବଡ଼/ସାନ ଅକ୍ଷର ସଫା କରିବା ପାଇଁ
        fun normalizeName(name: String): String {
            return name.trim().lowercase().replace(Regex("\\s+"), " ")
        }

        conn.createStatement().use { stmt ->
            // 1. BranchDetails ରୁ 'slno' କ୍ରମ ଅନୁସାରେ ସିଧାସଳଖ 'branchname' ଆଣିବା
            val branchSequence = mutableListOf<String>()
            try {
                val branchRs = stmt.executeQuery("SELECT branchname FROM BranchDetails ORDER BY CAST(slno AS INTEGER) ASC")
                while (branchRs.next()) {
                    val bName = (branchRs.getString("branchname") ?: "").trim()
                    if (bName.isNotBlank()) {
                        branchSequence.add(normalizeName(bName))
                    }
                }
                branchRs.close()
            } catch (e: Exception) {
                // ଯଦି slno ଟେକ୍ସଟ୍ ଥାଏ ବା କିଛି ଏରର୍ ଆସେ, ତେବେ rowid ଅନୁସାରେ ଆଣିବ
                try {
                    val rsFallback = stmt.executeQuery("SELECT branchname FROM BranchDetails ORDER BY rowid ASC")
                    while (rsFallback.next()) {
                        val bName = (rsFallback.getString("branchname") ?: "").trim()
                        if (bName.isNotBlank()) {
                            branchSequence.add(normalizeName(bName))
                        }
                    }
                    rsFallback.close()
                } catch (_: Exception) {}
            }

            // 2. MasterData ରୁ ଆକ୍ଟିଭ୍ ଷ୍ଟାଫ୍ ଫେଚ୍ କରିବା
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
                            headOfficeList.add("$empId, $branch, $designation") // 1g ପାଇଁ
                        } else {
                            rawBranchEmployees.add(Triple(empId, branch, designation)) // 1f ପାଇଁ
                        }
                    }
                }
                empRs.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 3. BranchDetails କ୍ରମ ଅନୁସାରେ 1f କୁ ସଜାଇବା (Sorting)
            val branchIndexMap = branchSequence.distinct().mapIndexed { index, name -> name to index }.toMap()
            
            val sortedBranchList = rawBranchEmployees
                .sortedBy { emp -> 
                    val cleanBranch = normalizeName(emp.second)
                    branchIndexMap[cleanBranch] ?: 999 
                }
                .map { emp -> "${emp.first}, ${emp.second}, ${emp.third}" }

            return Pair(sortedBranchList, headOfficeList)
        }
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