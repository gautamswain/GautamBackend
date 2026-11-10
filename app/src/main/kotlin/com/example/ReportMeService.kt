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
    val masterActiveCodes: List<String> = emptyList()
)

object ReportMeService {

    fun last_two_month_all_code(conn: Connection): List<String> {
        val uniqueCodes = mutableSetOf<String>()
        val query = "SELECT month, emp_id_2 FROM SalaryHistory ORDER BY rowid DESC"
        
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery(query)
            val allRows = mutableListOf<Pair<String, String>>()
            
            while (rs.next()) {
                // Column name badalare Column Index (1 au 2) use karajigala
                val month = rs.getString(1) ?: ""
                val empId = rs.getString(2) ?: ""
                allRows.add(Pair(month, empId))
            }
            rs.close()
            
            // Sabuthu recent last duiti distinct month khoji bar kariba
            val distinctMonths = allRows.map { it.first }.filter { it.isNotBlank() }.distinct().take(2)
            
            // Sehi duiti month bhitara asuthiba emp_id_2 gudoiku collect kariba
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

    // MasterData ru emp_status_35 = 1 thiba employee code aniba function
    fun Master_Active(conn: Connection): List<String> {
        val activeCodes = mutableSetOf<String>()
        
        conn.createStatement().use { stmt ->
            // Prathame emp_id_2 column check kariba, na thile fallback re dynamic column khojiba
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

    // Kounasi bi निर्दिष्ट field ('a' ba 'b') re save kariba pain
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
        }

        return ReportResult(
            status = "success",
            idRecord = "1",
            totalUniqueCodes = codesList.size,
            codes = codesList,
            totalMasterActive = activeList.size,
            masterActiveCodes = activeList
        )
    }
}