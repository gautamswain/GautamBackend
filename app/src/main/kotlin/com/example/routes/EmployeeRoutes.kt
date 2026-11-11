package com.example.routes

import com.example.TursoDatabaseService
import com.example.models.EmployeeProfileResponse
import com.example.utils.AdhikarDataMapper
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.employeeRoutes() {
    
    get("/api/turso/staff-detail/{empId}") {
        val empId = call.parameters["empId"]
        if (empId.isNullOrBlank()) {
            call.respondText("Missing ID", status = HttpStatusCode.BadRequest)
            return@get
        }
        
        // ୧. Fetch Salary History from TursoDatabaseService
        val rawSalaryData = TursoDatabaseService.getSalaryHistoryByEmpIdLast4(empId)

        // ୨. Fetch MasterData Map from Turso DB
        val rawMasterData = mutableMapOf<String, String>()
        val conn = TursoDatabaseService.getConnection()
        if (conn != null) {
            try {
                val query = "SELECT * FROM MasterData WHERE Code_2 = ? OR emp_id_2 = ? OR id = ?"
                val pstmt = conn.prepareStatement(query)
                pstmt.setString(1, empId.trim())
                pstmt.setString(2, empId.trim())
                pstmt.setString(3, empId.trim())
                val rs = pstmt.executeQuery()
                if (rs.next()) {
                    val meta = rs.metaData
                    for (i in 1..meta.columnCount) {
                        rawMasterData[meta.getColumnName(i)] = rs.getString(i) ?: ""
                    }
                }
                rs.close()
                pstmt.close()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { conn.close() } catch (_: Exception) {}
            }
        }

        // ୩. Normalize data using AdhikarDataMapper
        val cleanMaster = AdhikarDataMapper.mapToEmployeeMaster(rawMasterData)
        val cleanSalaries = rawSalaryData.map { dto ->
            AdhikarDataMapper.mapToSalaryRecord(mapOf(
                "month" to dto.month,
                "basic_da_9" to dto.basicDa9,
                "hra_10" to dto.hra10,
                "gross_earnings_28" to dto.grossEarnings28,
                "gross_deductions_29" to dto.grossDeductions29,
                "net_amount_30" to dto.netAmount30,
                "Status_00" to dto.status00,
                "backSalarygive" to dto.backSalaryGive
            ))
        }

        // ୪. Construct Clean Response
        val finalResponse = EmployeeProfileResponse(
            masterData = cleanMaster,
            recentSalaries = cleanSalaries
        )
        
        call.respond(HttpStatusCode.OK, finalResponse)
    }
}