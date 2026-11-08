package com.example

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.sql.Connection
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object CurrentStaffReportService {

    fun generateAndSaveReportFromDb(getConnection: () -> Connection?, localOutputDir: String): String {
        val allRecords = mutableListOf<Map<String, String>>()
        val conn = getConnection()

        if (conn != null) {
            try {
                // MasterData ଟେବୁଲ୍‌ରୁ ସମସ୍ତ କର୍ମଚାରୀଙ୍କ ତଥ୍ୟ ଫେଚ୍ କରିବା
                val stmt = conn.createStatement()
                val rs = stmt.executeQuery("SELECT * FROM MasterData") // ଯଦି ଟେବୁଲ୍ ନାମ ଅଲଗା ଥାଏ, ଏଠାରେ ବଦଲାନ୍ତୁ
                
                while (rs.next()) {
                    val record = mapOf(
                        "SL No" to (rs.getString("sl_no") ?: ""),
                        "Emp. ID" to (rs.getString("emp_id") ?: ""),
                        "EMPLOYEE NAME" to (rs.getString("employee_name") ?: ""),
                        "DESIGNATION" to (rs.getString("designation") ?: ""),
                        "DEPARTMENT" to (rs.getString("department") ?: ""),
                        "BRANCH NAME" to (rs.getString("branch_name") ?: ""),
                        "DOJ IN ADHIKAR" to formatDate(rs.getString("doj_in_adhikar") ?: ""),
                        "ADHAR" to "[Redacted]", // ଗୋପନୀୟତା ନିୟମ ଅନੁଯାୟୀ
                        "DOJ" to formatDate(rs.getString("doj") ?: ""),
                        "Cont-1" to (rs.getString("cont_1") ?: ""),
                        "Cont-2" to (rs.getString("cont_2") ?: ""),
                        "DOB" to formatDate(rs.getString("dob") ?: ""),
                        "Age" to (rs.getString("age") ?: ""),
                        "Status" to (rs.getString("emp_status_35") ?: "1") // ଷ୍ଟାଟସ୍ ଯାଞ୍ଚ ପାଇଁ
                    )
                    allRecords.add(record)
                }
                rs.close()
                stmt.close()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { conn.close() } catch (_: Exception) {}
            }
        }

        // ୧୧ଟି ନିର୍ଦ୍ଦିଷ୍ଟ ଶିଟ୍ କ୍ରମରେ କାଟାଗୋରାଇଜ୍ କରିବା
        val sheetOrder = listOf(
            "CO", "BM", "AM", "CM", "Auditors", "Telecallers", "HO", "MSME", "NPA", "Undefine", "Left"
        )

        val categorizedMap = mutableMapOf<String, MutableList<Map<String, String>>>()
        for (s in sheetOrder) {
            categorizedMap[s] = mutableListOf()
        }

        for (rec in allRecords) {
            val status = rec["Status"] ?: "1"
            val desig = rec["DESIGNATION"]?.lowercase() ?: ""
            val dept = rec["DEPARTMENT"]?.lowercase() ?: ""

            // ଯଦି ଷ୍ଟାଟସ୍ 0 ଅଛି କିମ୍ବା Left ତାଲିକାରେ ଅଛି
            val assigned = if (status == "0") {
                "Left"
            } else {
                when {
                    dept.contains("npa") -> "NPA"
                    desig.contains("tele") || dept.contains("tele") -> "Telecallers"
                    desig.contains("credit officer") -> "CO"
                    desig.contains("branch manager") || desig.contains("branch incharge") || desig.contains("assistant branch manager") -> "BM"
                    desig.contains("area manager") -> "AM"
                    desig.contains("cluster manager") -> "CM"
                    desig.contains("audit") || desig.contains("ia") || desig.contains("auditor") -> "Auditors"
                    desig.contains("msme") || dept.contains("msme") -> "MSME"
                    dept.contains("ho") || dept.contains("head office") -> "HO"
                    else -> "Undefine"
                }
            }
            categorizedMap[assigned]?.add(rec)
        }

        // ନୂଆ ଏକ୍ସେଲ୍ ଫାଇଲ୍ ତିଆରି କରିବା
        val newWb = XSSFWorkbook()
        val headers = listOf("SL No", "Emp ID", "Employee Name", "Designation", "Department", "Branch Name", "DOJ in Adhikar", "Aadhar", "DOJ", "Contact 1", "Contact 2", "DOB", "Age")
        val keys = listOf("SL No", "Emp. ID", "EMPLOYEE NAME", "DESIGNATION", "DEPARTMENT", "BRANCH NAME", "DOJ IN ADHIKAR", "ADHAR", "DOJ", "Cont-1", "Cont-2", "DOB", "Age")

        for (sName in sheetOrder) {
            val ws = newWb.createSheet(sName)
            val rowsList = categorizedMap[sName] ?: emptyList()

            val headerRow = ws.createRow(0)
            for (i in headers.indices) {
                headerRow.createCell(i).setCellValue(headers[i])
            }

            var rIdx = 1
            for (rowMap in rowsList) {
                val dataRow = ws.createRow(rIdx++)
                for (cIdx in keys.indices) {
                    val valStr = rowMap[keys[cIdx]] ?: ""
                    dataRow.createCell(cIdx).setCellValue(valStr)
                }
            }
        }

        // ଫାଇଲ୍ ନାମ ଏବଂ ସେଭ୍ କରିବା (Stafflist + DDMMYYYY.xlsx)
        val dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMyyyy"))
        val fileName = "Stafflist$dateStr.xlsx"

        val targetFile = File(localOutputDir, fileName)
        targetFile.parentFile?.mkdirs()

        val googleDriveDir = File(System.getProperty("user.home") + File.separator + "Google Drive" + File.separator + "StaffReports")
        googleDriveDir.mkdirs()
        val driveFile = File(googleDriveDir, fileName)

        newWb.use { workbookInstance ->
            targetFile.outputStream().use { workbookInstance.write(it) }
            driveFile.outputStream().use { workbookInstance.write(it) }
        }

        return targetFile.absolutePath
    }

    private fun formatDate(dateStr: String): String {
        if (dateStr.isBlank() || dateStr.equals("nan", ignoreCase = true)) return ""
        try {
            val parsedDate = LocalDate.parse(dateStr.take(10))
            return parsedDate.format(DateTimeFormatter.ofPattern("dd-MM-YYYY"))
        } catch (_: Exception) {
            return dateStr
        }
    }
}