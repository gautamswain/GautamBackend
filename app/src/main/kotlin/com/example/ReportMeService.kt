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
        
        conn.prepareStatement(query).use { pstmt ->
            val rs = pstmt.executeQuery()
            val allRows = mutableListOf<Pair<String, String>>()
            
            while (rs.next()) {
                val month = rs.getString("month") ?: ""
                val empId = rs.getString("emp_id_2") ?: ""
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
                    uniqueCodes.add(empId.trim()) // Duplicate automatic remove hoijiba
                }
            }
        }
        return uniqueCodes.toList()
    }

    fun generateAndSaveReport(conn: Connection, x: String, y: String) {
        val insertQuery = """
            INSERT INTO reports (emp_id, a) 
            VALUES (?, ?)
            ON CONFLICT(emp_id) DO UPDATE SET 
            a = excluded.a
        """
        
        val fallbackInsert = "INSERT INTO reports (emp_id, a) VALUES (?, ?)"
        
        try {
            val insertStmt = conn.prepareStatement(insertQuery)
            insertStmt.setString(1, x)
            insertStmt.setString(2, y)
            insertStmt.executeUpdate()
            insertStmt.close()
        } catch (_: Exception) {
            val insertStmt = conn.prepareStatement(fallbackInsert)
            insertStmt.setString(1, x)
            insertStmt.setString(2, y)
            insertStmt.executeUpdate()
            insertStmt.close()
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