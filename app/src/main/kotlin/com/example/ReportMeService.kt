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
    val branchwiseCodes: List<String> = emptyList()
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
                val rs = stmt.executeQuery("SELECT Code_2 FROM MasterData")
                while (rs.next()) {
                    val code = rs.getString(1) ?: ""
                    if (code.isNotBlank() && code != "0") {
                        activeCodes.add(code.trim())
                    }
                }
                rs.close()
            } catch (_: Exception) {
                try {
                    val rs = stmt.executeQuery("SELECT * FROM MasterData")
                    val meta = rs.metaData
                    var codeIdx = 1
                    for (i in 1..meta.columnCount) {
                        if (meta.getColumnName(i).lowercase().contains("code") || meta.getColumnName(i).lowercase().contains("emp_id")) {
                            codeIdx = i
                            break
                        }
                    }
                    while (rs.next()) {
                        val code = rs.getString(codeIdx) ?: ""
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

    // 1f: Branchwisedata using 1b codes as base, BranchDetails sequence, and exact designation priority
    fun Branchwisedata(conn: Connection, activeCodes: List<String>): List<String> {
        val sortedCodes = mutableListOf<String>()
        if (activeCodes.isEmpty()) return sortedCodes
        
        conn.createStatement().use { stmt ->
            // 1. Fetch exact branch sequence from BranchDetails
            val branchSequence = mutableListOf<String>()
            try {
                val branchRs = stmt.executeQuery("SELECT branchname FROM BranchDetails")
                while (branchRs.next()) {
                    val bName = (branchRs.getString(1) ?: "").trim()
                    if (bName.isNotBlank() && !bName.contains("Head Office", true) && !bName.contains("HO", true)) {
                        branchSequence.add(bName)
                    }
                }
                branchRs.close()
            } catch (_: Exception) {}

            // 2. Fetch employee details from MasterData for codes present in 1b (activeCodes)
            val employeeList = mutableListOf<Triple<String, String, String>>() // empId, designation, branch
            try {
                val empRs = stmt.executeQuery("SELECT Code_2, designation_7, branch_name_9 FROM MasterData")
                while (empRs.next()) {
                    val empId = (empRs.getString(1) ?: "").trim()
                    val designation = (empRs.getString(2) ?: "").trim().lowercase()
                    val branch = (empRs.getString(3) ?: "").trim()
                    
                    // Only process if code exists in 1b and not Head Office
                    if (empId in activeCodes && empId != "0" && !branch.contains("Head Office", true) && !branch.contains("HO", true)) {
                        employeeList.add(Triple(empId, designation, branch))
                    }
                }
                empRs.close()
            } catch (_: Exception) {
                try {
                    val empRs = stmt.executeQuery("SELECT * FROM MasterData")
                    val meta = empRs.metaData
                    var idIdx = 1; var desIdx = 2; var branchIdx = 3
                    for (i in 1..meta.columnCount) {
                        val name = meta.getColumnName(i).lowercase()
                        if (name.contains("code_2") || name.contains("code") || name.contains("emp_id")) idIdx = i
                        if (name.contains("designation")) desIdx = i
                        if (name.contains("branch")) branchIdx = i
                    }
                    while (empRs.next()) {
                        val empId = (empRs.getString(idIdx) ?: "").trim()
                        val designation = (empRs.getString(desIdx) ?: "").trim().lowercase()
                        val branch = (empRs.getString(branchIdx) ?: "").trim()
                        
                        if (empId in activeCodes && empId != "0" && !branch.contains("Head Office", true) && !branch.contains("HO", true)) {
                            employeeList.add(Triple(empId, designation, branch))
                        }
                    }
                    empRs.close()
                } catch (_: Exception) {}
            }

            val branchIndexMap = branchSequence.mapIndexed { index, name -> name.lowercase() to index }.toMap()

            // 3. Sorting: BranchDetails sequence index first, then designation priority (CO -> SCO -> ABM -> BM -> SBM -> AM -> CM)
            val sortedEmployees = employeeList.sortedWith(compareBy(
                { emp -> branchIndexMap[emp.third.lowercase()] ?: 999 },
                { emp -> 
                    val des = emp.second
                    when {
                        des.contains("co") && !des.contains("sco") -> 1
                        des.contains("sco") || des.contains("senior credit officer") -> 2
                        des.contains("abm") || des.contains("assistant branch manager") -> 3
                        des.contains("bm") && !des.contains("sbm") -> 4
                        des.contains("sbm") || des.contains("senior branch manager") -> 5
                        des.contains("am") || des.contains("area manager") -> 6
                        des.contains("cm") || des.contains("cluster manager") -> 7
                        else -> 99
                    }
                }
            ))

            sortedCodes.addAll(sortedEmployees.map { it.first }.distinct())
        }
        return sortedCodes
    }

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
        
        conn.use { connection ->
            val x = "1" // 1 no record
            
            codesList = last_two_month_all_code(connection)
            generateAndSaveReport(connection, x, Json.encodeToString(codesList), "a")
            
            activeList = Master_Active(connection)
            generateAndSaveReport(connection, x, Json.encodeToString(activeList), "b")
            
            commonList = Sal_Mast_Common(codesList, activeList)
            generateAndSaveReport(connection, x, Json.encodeToString(commonList), "c")

            leftSalaryList = Left_Salary(codesList, activeList)
            generateAndSaveReport(connection, x, Json.encodeToString(leftSalaryList), "d")

            recentJoinedList = Recent_Joined(codesList, activeList)
            generateAndSaveReport(connection, x, Json.encodeToString(recentJoinedList), "e")

            branchwiseList = Branchwisedata(connection, activeList)
            generateAndSaveReport(connection, x, Json.encodeToString(branchwiseList), "f")
        }

        return ReportResult(
            status = "success",
            idRecord = "1",
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
            branchwiseCodes = branchwiseList
        )
    }
}