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
    val codes: List<String>
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

    fun generateAndSaveReport(conn: Connection, x: String, y: String) {
        val safeX = x.replace("'", "''")
        val safeY = y.replace("'", "''")
        
        // Table schema anujayi 'id' ba 'emp_id' re update/insert heba
        conn.createStatement().use { stmt ->
            try {
                // Prathame 'id' column (screenshot anujayi id = 1) re update chesta kariba
                val updated = stmt.executeUpdate("UPDATE reports SET a = '$safeY' WHERE id = '$safeX'")
                if (updated == 0) {
                    stmt.executeUpdate("INSERT INTO reports (id, cr, a) VALUES ('$safeX', 'm', '$safeY')")
                }
            } catch (_: Exception) {
                // Yadi column nama 'emp_id' thaye, tahale ethire save heba
                val updated = stmt.executeUpdate("UPDATE reports SET a = '$safeY' WHERE emp_id = '$safeX'")
                if (updated == 0) {
                    stmt.executeUpdate("INSERT INTO reports (emp_id, a) VALUES ('$safeX', '$safeY')")
                }
            }
        }
    }

    fun generateAndSaveReport(conn: Connection): ReportResult = processAndSave(conn)

    fun processAndSave(conn: Connection): ReportResult {
        var codesList = listOf<String>()
        
        conn.use { connection ->
            codesList = last_two_month_all_code(connection)
            
            val x = "1" // 1 no record
            val y = Json.encodeToString(codesList) // codes list as JSON for 'a' field
            
            generateAndSaveReport(connection, x, y)
        }

        return ReportResult(
            status = "success",
            idRecord = "1",
            totalUniqueCodes = codesList.size,
            codes = codesList
        )
    }
}