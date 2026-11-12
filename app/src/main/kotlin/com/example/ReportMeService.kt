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

   // 1f & 1g: Branchwisedata (1f = BranchDetails କ୍ରମ ଅନୁସାରେ ସର୍ଟିଂ ହୋଇଥିବା Branches, 1g = Head Office)
    fun Branchwisedata(conn: Connection, activeCodes: List<String>): Pair<List<String>, List<String>> {
        val rawBranchEmployees = mutableListOf<Triple<String, String, String>>() // empId, branch, designation
        val headOfficeList = mutableListOf<String>()
        val activeSet = activeCodes.map { it.trim().uppercase() }.toSet()

        conn.createStatement().use { stmt ->
            // 1. BranchDetails ଟେବୁଲ୍ ରୁ ବ୍ରାଞ୍ଚ୍ ଗୁଡ଼ିକର ସଠିକ୍ କ୍ରମ (Sequence) ଆଣିବା
            val branchSequence = mutableListOf<String>()
            try {
                val branchRs = stmt.executeQuery("SELECT * FROM BranchDetails")
                val meta = branchRs.metaData
                var branchColName = ""
                for (i in 1..meta.columnCount) {
                    val cName = meta.getColumnName(i).lowercase()
                    if (cName.contains("branchname") || cName == "branch_name" || cName == "branch") {
                        branchColName = meta.getColumnName(i)
                        break
                    }
                }
                while (branchRs.next()) {
                    val bName = if (branchColName.isNotEmpty()) (branchRs.getString(branchColName) ?: "").trim() else ""
                    if (bName.isNotBlank()) {
                        branchSequence.add(bName)
                    }
                }
                branchRs.close()
            } catch (e: Exception) {
                e.printStackTrace()
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
                        if (branch.equals("Head Office", ignoreCase = true) || branch.equals("HO", ignoreCase = true)) {
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

            // 3. BranchDetails କ୍ରମ ଅନୁସାରେ 1f (Branch List) କୁ ସଜାଇବା (Sorting)
            val branchIndexMap = branchSequence.mapIndexed { index, name -> name.lowercase() to index }.toMap()
            
            val sortedBranchList = rawBranchEmployees
                .sortedBy { emp -> branchIndexMap[emp.second.lowercase()] ?: 999 }
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