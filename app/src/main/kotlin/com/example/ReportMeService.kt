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

    // 1a: SalaryHistory ru last duimasa ra unique codes
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

    // 1b: MasterData ru emp_status_35 = 1 thiba employee codes
    fun Master_Active(conn: Connection): List<String> {
        val activeCodes = mutableSetOf<String>()
        
        conn.createStatement().use { stmt ->
            try {
                val query = "SELECT emp_id_2 FROM MasterData WHERE emp_status_35 = '1' OR emp_status_35 = 1"
                val rs = stmt.executeQuery(query)
                while (rs.next()) {
                    val code = rs.getString(1) ?: ""
                    if (code.isNotBlank() && code != "0") {
                        activeCodes.add(code.trim())
                    }
                }
                rs.close()
            } catch (_: Exception) {
                val rs = stmt.executeQuery("SELECT * FROM MasterData WHERE emp_status_35 = '1' OR emp_status_35 = 1")
                val meta = rs.metaData
                var codeColIndex = 1
                for (i in 1..meta.columnCount) {
                    val colName = meta.getColumnName(i).lowercase()
                    if (colName.contains("emp_id") || colName.contains("code")) {
                        codeColIndex = i
                        break
                    }
                }
                while (rs.next()) {
                    val code = rs.getString(codeColIndex) ?: ""
                    if (code.isNotBlank() && code != "0") {
                        activeCodes.add(code.trim())
                    }
                }
                rs.close()
            }
        }
        return activeCodes.toList()
    }

    // 1c: Common between 1a and 1b
    fun Sal_Mast_Common(list1a: List<String>, list1b: List<String>): List<String> {
        val setB = list1b.toSet()
        return list1a.filter { it in setB }.distinct()
    }

    // 1d: Present in 1a but NOT in 1b
    fun Left_Salary(list1a: List<String>, list1b: List<String>): List<String> {
        val setB = list1b.toSet()
        return list1a.filter { it !in setB }.distinct()
    }

    // 1e: Present in 1b but NOT in 1a
    fun Recent_Joined(list1a: List<String>, list1b: List<String>): List<String> {
        val setA = list1a.toSet()
        return list1b.filter { it !in setA }.distinct()
    }

    // 1f: Branchwisedata - BranchDetails sequence + MasterData branch_name_9 + CO -> SCO -> ABM -> BM -> SBM -> AM -> CM
    fun Branchwisedata(conn: Connection): List<String> {
        val sortedCodes = mutableListOf<String>()
        
        conn.createStatement().use { stmt ->
            // 1. Fetch branchname sequence from BranchDetails
            val branchSequence = mutableListOf<String>()
            try {
                val branchRs = stmt.executeQuery("SELECT branchname FROM BranchDetails")
                while (branchRs.next()) {
                    val bName = (branchRs.getString(1) ?: "").trim().lowercase()
                    if (bName.isNotBlank() && !bName.contains("head office") && !bName.contains("ho")) {
                        branchSequence.add(bName)
                    }
                }
                branchRs.close()
            } catch (_: Exception) {
                val branchRs = stmt.executeQuery("SELECT * FROM BranchDetails")
                val meta = branchRs.metaData
                var bNameIdx = 1
                for (i in 1..meta.columnCount) {
                    if (meta.getColumnName(i).lowercase().contains("branch")) {
                        bNameIdx = i
                        break
                    }
                }
                while (branchRs.next()) {
                    val bName = (branchRs.getString(bNameIdx) ?: "").trim().lowercase()
                    if (bName.isNotBlank() && !bName.contains("head office") && !bName.contains("ho")) {
                        branchSequence.add(bName)
                    }
                }
                branchRs.close()
            }

            // 2. Fetch active employees along with branch_name_9 and designation_7 from MasterData
            val employeeList = mutableListOf<Triple<String, String, String>>() // empId, designation, branch
            try {
                val empRs = stmt.executeQuery("SELECT emp_id_2, designation_7, branch_name_9 FROM MasterData WHERE (emp_status_35 = '1' OR emp_status_35 = 1)")
                while (empRs.next()) {
                    val empId = empRs.getString(1) ?: ""
                    val designation = (empRs.getString(2) ?: "").trim().lowercase()
                    val branch = (empRs.getString(3) ?: "").trim().lowercase()
                    
                    if (empId.isNotBlank() && empId != "0" && !branch.contains("head office") && !branch.contains("ho")) {
                        employeeList.add(Triple(empId, designation, branch))
                    }
                }
                empRs.close()
            } catch (_: Exception) {
                val empRs = stmt.executeQuery("SELECT * FROM MasterData WHERE (emp_status_35 = '1' OR emp_status_35 = 1)")
                val meta = empRs.metaData
                var idIdx = 1; var desIdx = 2; var branchIdx = 3
                for (i in 1..meta.columnCount) {
                    val name = meta.getColumnName(i).lowercase()
                    if (name.contains("emp_id") || name.contains("code")) idIdx = i
                    if (name.contains("designation")) desIdx = i
                    if (name.contains("branch")) branchIdx = i
                }
                while (empRs.next()) {
                    val empId = empRs.getString(idIdx) ?: ""
                    val designation = (empRs.getString(desIdx) ?: "").trim().lowercase()
                    val branch = (empRs.getString(branchIdx) ?: "").trim().lowercase()
                    
                    if (empId.isNotBlank() && empId != "0" && !branch.contains("head office") && !branch.contains("ho")) {
                        employeeList.add(Triple(empId, designation, branch))
                    }
                }
                empRs.close()
            }

            val branchIndexMap = branchSequence.mapIndexed { index, name -> name to index }.toMap()

            // 3. Sorting: BranchDetails sequence index first, then designation priority (CO -> SCO -> ABM -> BM -> SBM -> AM -> CM)
            val sortedEmployees = employeeList.sortedWith(compareBy(
                { emp -> branchIndexMap[emp.third] ?: 999 },
                { emp -> 
                    val des = emp.second
                    when {
                        des.contains("co") && !des.contains("sco") -> 1
                        des.contains("sco") -> 2
                        des.contains("abm") -> 3
                        des.contains("bm") && !des.contains("sbm") -> 4
                        des.contains("sbm") -> 5
                        des.contains("am") -> 6
                        des.contains("cm") -> 7
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
            
            // 1. 1a: SalaryHistory last two months
            codesList = last_two_month_all_code(connection)
            generateAndSaveReport(connection, x, Json.encodeToString(codesList), "a")
            
            // 2. 1b: MasterData active employees
            activeList = Master_Active(connection)
            generateAndSaveReport(connection, x, Json.encodeToString(activeList), "b")
            
            // 3. 1c: Common between 1a and 1b
            commonList = Sal_Mast_Common(codesList, activeList)
            generateAndSaveReport(connection, x, Json.encodeToString(commonList), "c")

            // 4. 1d: Left Salary (1a - 1b)
            leftSalaryList = Left_Salary(codesList, activeList)
            generateAndSaveReport(connection, x, Json.encodeToString(leftSalaryList), "d")

            // 5. 1e: Recent Joined (1b - 1a)
            recentJoinedList = Recent_Joined(codesList, activeList)
            generateAndSaveReport(connection, x, Json.encodeToString(recentJoinedList), "e")

            // 6. 1f: Branchwisedata (BranchDetails branchname sequence + branch_name_9 + CO -> SCO -> ABM -> BM -> SBM -> AM -> CM)
            branchwiseList = Branchwisedata(connection)
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