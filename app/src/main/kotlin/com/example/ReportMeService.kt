package com.example

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection

object ReportMeService {

    fun generateAndSaveReport(conn: Connection, empId: String): Map<String, Any> {
        var uniqueCode = ""
        val monthsList = mutableListOf<Map<String, String>>()
        val codesList = mutableListOf<String>()

        conn.use { connection ->
            // 1. Salary history table ru last row ebong tar purbar row aniwa
            val query = "SELECT * FROM SalaryHistory WHERE emp_id_2 = ? OR Code_2 = ? ORDER BY rowid DESC LIMIT 2"
            val pstmt = connection.prepareStatement(query)
            pstmt.setString(1, empId)
            pstmt.setString(2, empId)
            val rs = pstmt.executeQuery()

            val rows = mutableListOf<Map<String, String>>()
            while (rs.next()) {
                val map = mutableMapOf<String, String>()
                val meta = rs.metaData
                for (i in 1..meta.columnCount) {
                    map[meta.getColumnName(i).lowercase()] = rs.getString(i) ?: ""
                }
                rows.add(map)
            }
            rs.close()
            pstmt.close()

            if (rows.isNotEmpty()) {
                val lastRow = rows[0]
                val lastMonth = lastRow["month"] ?: ""
                val lastCode = lastRow["salary_code"] ?: lastRow["id"] ?: lastRow["sl_no_1"] ?: ""

                monthsList.add(mapOf("month" to lastMonth, "code" to lastCode))
                codesList.add(lastCode)

                if (rows.size > 1) {
                    val prevRow = rows[1]
                    val prevMonth = prevRow["month"] ?: ""
                    val prevCode = prevRow["salary_code"] ?: prevRow["id"] ?: prevRow["sl_no_1"] ?: ""
                    
                    monthsList.add(mapOf("month" to prevMonth, "code" to prevCode))
                    codesList.add(prevCode)
                    
                    uniqueCode = "UNIQ_${lastCode}_${prevCode}"
                } else {
                    uniqueCode = "UNIQ_${lastCode}_SINGLE"
                }
            }

            // 2. 'a' record JSON format (Full details)
            val aRecordMap = mapOf(
                "empId" to empId,
                "uniqueCode" to uniqueCode,
                "monthsData" to monthsList
            )
            val aRecordJson = Json.encodeToString(aRecordMap)

            // 3. 'm' record JSON format (Only codes)
            val mRecordMap = mapOf(
                "codes" to codesList
            )
            val mRecordJson = Json.encodeToString(mRecordMap)

            // 4. Insert or Update into 'reports' table (cr field = 'a' record, m field = only codes json)
            val insertQuery = """
                INSERT INTO reports (emp_id, cr, m, unique_code) 
                VALUES (?, ?, ?, ?)
                ON CONFLICT(emp_id) DO UPDATE SET 
                cr = excluded.cr, 
                m = excluded.m,
                unique_code = excluded.unique_code
            """
            
            val fallbackInsert = "INSERT INTO reports (emp_id, cr, m, unique_code) VALUES (?, ?, ?, ?)"
            
            try {
                val insertStmt = connection.prepareStatement(insertQuery)
                insertStmt.setString(1, empId)
                insertStmt.setString(2, aRecordJson) // cr field gets 'a' record
                insertStmt.setString(3, mRecordJson) // m field gets only codes json
                insertStmt.setString(4, uniqueCode)
                insertStmt.executeUpdate()
                insertStmt.close()
            } catch (_: Exception) {
                val insertStmt = connection.prepareStatement(fallbackInsert)
                insertStmt.setString(1, empId)
                insertStmt.setString(2, aRecordJson)
                insertStmt.setString(3, mRecordJson)
                insertStmt.setString(4, uniqueCode)
                insertStmt.executeUpdate()
                insertStmt.close()
            }
        }

        return mapOf(
            "empId" to empId,
            "uniqueCode" to uniqueCode,
            "codes" to codesList
        )
    }
}