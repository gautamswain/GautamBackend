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
    val recentJoinedCodes: List<String> = emptyList()
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

    // 1c: 1a (SalaryHistory) au 1b (MasterData Active) bhitare common codes
    fun Sal_Mast_Common(list1a: List<String>, list1b: List<String>): List<String> {
        val setB = list1b.toSet()
        return list1a.filter { it in setB }.distinct()
    }

    // 1d: Jeunmane 1a re achhanti kintu 1b re nahanti (Left employees getting salary)
    fun Left_Salary(list1a: List<String>, list1b: List<String>): List<String> {
        val setB = list1b.toSet()
        return list1a.filter { it !in setB }.distinct()
    }

    // 1e: Jeunmane 1b re achhanti kintu 1a re nahanti (Active in MasterData, not in last 2 months salary)
    fun Recent_Joined(list1a: List<String>, list1b: List<String>): List<String> {
        val setA = list1a.toSet()
        return list1b.filter { it !in setA }.distinct()
    }

    // Kounasi bi field ('a', 'b', 'c', 'd', 'e') re save kariba pain
    fun generateAndSaveReport(conn: Connection, x: String, y: String, fieldName: String = "a") {
        val safeX = x.replace("'", "''")
        val safeY = y.replace("'", "''")
        
        conn.createStatement().use { stmt ->
            try {
                // Prathame 'id' column (id = 1) re update chesta kariba
                val updated = stmt.executeUpdate("UPDATE reports SET $fieldName = '$safeY' WHERE id = '$safeX'")
                if (updated == 0) {
                    stmt.executeUpdate("INSERT INTO reports (id, cr, $fieldName) VALUES ('$safeX', 'm', '$safeY')")
                }
            } catch (_: Exception) {
                // Yadi column nama 'emp_id' thaye, tahale ethire save heba
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
        
        conn.use { connection ->
            val x = "1" // 1 no record
            
            // 1. SalaryHistory last two months -> 1a ('a' field of record 1)
            codesList = last_two_month_all_code(connection)
            val yA = Json.encodeToString(codesList)
            generateAndSaveReport(connection, x, yA, "a")
            
            // 2. MasterData emp_status_35 = 1 -> 1b ('b' field of record 1)
            activeList = Master_Active(connection)
            val yB = Json.encodeToString(activeList)
            generateAndSaveReport(connection, x, yB, "b")
            
            // 3. Common between 1a and 1b -> 1c ('c' field of record 1)
            commonList = Sal_Mast_Common(codesList, activeList)
            val yC = Json.encodeToString(commonList)
            generateAndSaveReport(connection, x, yC, "c")

            // 4. Present in 1a but NOT in 1b -> 1d ('d' field of record 1)
            leftSalaryList = Left_Salary(codesList, activeList)
            val yD = Json.encodeToString(leftSalaryList)
            generateAndSaveReport(connection, x, yD, "d")

            // 5. Present in 1b but NOT in 1a -> 1e ('e' field of record 1)
            recentJoinedList = Recent_Joined(codesList, activeList)
            val yE = Json.encodeToString(recentJoinedList)
            generateAndSaveReport(connection, x, yE, "e")
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
            recentJoinedCodes = recentJoinedList
        )
    }
}