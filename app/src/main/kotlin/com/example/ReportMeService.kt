package com.example

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection

object ReportMeService {

    fun generateAndSaveUniqueStaffReport(conn: Connection): Map<String, Any> {
        val uniqueEmpIds = mutableSetOf<String>()

        conn.use { connection ->
            // 1. SalaryHistory ru recent records ani unique emp_id gudoiku collect kariba
            val query = "SELECT emp_id_2, Code_2 FROM SalaryHistory ORDER BY rowid DESC LIMIT 2000"
            val pstmt = connection.prepareStatement(query)
            val rs = pstmt.executeQuery()

            while (rs.next()) {
                val empId = rs.getString("emp_id_2") ?: rs.getString("Code_2") ?: ""
                if (empId.isNotBlank() && empId != "0") {
                    uniqueEmpIds.add(empId.trim()) // Duplicate id gudoiku automatic remove kari gotie gotie (unique) karideba
                }
            }
            rs.close()
            pstmt.close()

            val empIdList = uniqueEmpIds.toList()

            // 2. 'm' field map banayiba jeuthi unique IDs thiba
            val mFieldMap = mapOf(
                "uniqueEmpIds" to empIdList,
                "count" to empIdList.size
            )

            // 3. 'a' record ("cr" field data) banayiba, jar bhitare 'm' field rahijiba
            val aRecordMap = mapOf(
                "reportName" to "Last Two Months Unique Staff Report",
                "m" to mFieldMap
            )
            val aRecordJson = Json.encodeToString(aRecordMap)

            // 4. Reports table ra 'cr' field re 'a' record insert ba update kariba
            val recordKey = "UNIQUE_STAFF_REPORT"
            val insertQuery = """
                INSERT INTO reports (emp_id, cr, unique_code) 
                VALUES (?, ?, ?)
                ON CONFLICT(emp_id) DO UPDATE SET 
                cr = excluded.cr, 
                unique_code = excluded.unique_code
            """
            
            val fallbackInsert = "INSERT INTO reports (emp_id, cr, unique_code) VALUES (?, ?, ?)"
            
            try {
                val insertStmt = connection.prepareStatement(insertQuery)
                insertStmt.setString(1, recordKey)
                insertStmt.setString(2, aRecordJson)   // 'cr' field re 'a' record (jar bhitare 'm' field achhi)
                insertStmt.setString(3, "UNIQ_STAFF_CODE")
                insertStmt.executeUpdate()
                insertStmt.close()
            } catch (_: Exception) {
                val insertStmt = connection.prepareStatement(fallbackInsert)
                insertStmt.setString(1, recordKey)
                insertStmt.setString(2, aRecordJson)
                insertStmt.setString(3, "UNIQ_STAFF_CODE")
                insertStmt.executeUpdate()
                insertStmt.close()
            }
        }

        return mapOf(
            "status" to "success",
            "uniqueIdsCount" to uniqueEmpIds.size
        )
    }
}
