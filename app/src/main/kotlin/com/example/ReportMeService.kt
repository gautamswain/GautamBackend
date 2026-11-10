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
        val query = "SELECT * FROM SalaryHistory ORDER BY rowid DESC"
        
        conn.prepareStatement(query).use { pstmt ->
            val rs = pstmt.executeQuery()
            val allRows = mutableListOf<Map<String, String>>()
            
            while (rs.next()) {
                val map = mutableMapOf<String, String>()
                val meta = rs.metaData
                for (i in 1..meta.columnCount) {
                    map[meta.getColumnName(i).lowercase()] = rs.getString(i) ?: ""
                }
                allRows.add(map)
            }
            rs.close()
            
            val distinctMonths = allRows.mapNotNull { it["month"] }.distinct().take(2)
            
            for (row in allRows) {
                val month = row["month"] ?: ""
                if (month in distinctMonths) {
                    val code = row["salary_code"] ?: row["emp_id_2"] ?: row["id"] ?: ""
                    if (code.isNotBlank()) {
                        uniqueCodes.add(code.trim())
                    }
                }
            }
        }
        return uniqueCodes.toList()
    }

    // x = "1" (1 no record), y = codes list JSON for "a" field
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