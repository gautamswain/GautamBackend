package com.example

import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object CurrentStaffReportService {

    fun generateAndSaveCurrentStaffReport(inputStream: InputStream, localOutputDir: String): String {
        val workbook = WorkbookFactory.create(inputStream)
        val formatter = DataFormatter()

        val activeSheets = listOf("CO", "BM", "AM", "DM-RM", "Audit", "HO", "MSME")
        val allRecords = mutableListOf<Map<String, String>>()

        // ୧. Active sheets ରୁ ଡାଟା ପଢ଼ିବା
        for (sheetName in activeSheets) {
            val sheet = workbook.getSheet(sheetName) ?: continue
            for (row in sheet) {
                if (row.rowNum == 0) continue // Header ଛାଡ଼ିବା
                val slNo = formatter.formatCellValue(row.getCell(0)).trim()
                if (slNo.isBlank()) continue

                val record = mapOf(
                    "SL No" to slNo,
                    "Emp. ID" to formatter.formatCellValue(row.getCell(1)).trim(),
                    "EMPLOYEE NAME" to formatter.formatCellValue(row.getCell(2)).trim(),
                    "DESIGNATION" to formatter.formatCellValue(row.getCell(3)).trim(),
                    "DEPARTMENT" to formatter.formatCellValue(row.getCell(4)).trim(),
                    "BRANCH NAME" to formatter.formatCellValue(row.getCell(5)).trim(),
                    "DOJ IN ADHIKAR" to formatDate(formatter.formatCellValue(row.getCell(6))),
                    "ADHAR" to formatter.formatCellValue(row.getCell(7)).trim(),
                    "DOJ" to formatDate(formatter.formatCellValue(row.getCell(8))),
                    "Cont-1" to formatter.formatCellValue(row.getCell(9)).trim(),
                    "Cont-2" to formatter.formatCellValue(row.getCell(10)).trim(),
                    "DOB" to formatDate(formatter.formatCellValue(row.getCell(11))),
                    "Age" to formatter.formatCellValue(row.getCell(12)).trim(),
                    "Source_Sheet" to sheetName
                )
                allRecords.add(record)
            }
        }

        // ୨. Left (Inactive) sheet ରୁ ଡାଟା ପଢ଼ିବା
        val leftSheet = workbook.getSheet("Left")
        if (leftSheet != null) {
            for (row in leftSheet) {
                if (row.rowNum == 0) continue
                val slNo = formatter.formatCellValue(row.getCell(0)).trim()
                if (slNo.isBlank()) continue

                val record = mapOf(
                    "SL No" to slNo,
                    "Emp. ID" to formatter.formatCellValue(row.getCell(1)).trim(),
                    "EMPLOYEE NAME" to formatter.formatCellValue(row.getCell(2)).trim(),
                    "DESIGNATION" to formatter.formatCellValue(row.getCell(3)).trim(),
                    "DEPARTMENT" to formatter.formatCellValue(row.getCell(4)).trim(),
                    "BRANCH NAME" to formatter.formatCellValue(row.getCell(5)).trim(),
                    "DOJ IN ADHIKAR" to formatDate(formatter.formatCellValue(row.getCell(6))),
                    "ADHAR" to formatter.formatCellValue(row.getCell(7)).trim(),
                    "DOJ" to formatDate(formatter.formatCellValue(row.getCell(8))),
                    "Cont-1" to formatter.formatCellValue(row.getCell(9)).trim(),
                    "Cont-2" to formatter.formatCellValue(row.getCell(10)).trim(),
                    "DOB" to formatDate(formatter.formatCellValue(row.getCell(11))),
                    "Age" to formatter.formatCellValue(row.getCell(12)).trim(),
                    "Source_Sheet" to "Left"
                )
                allRecords.add(record)
            }
        }
        workbook.close()

        // ୩. ୧୧ଟି ନିର୍ଦ୍ଦିଷ୍ଟ ଶିଟ୍ କ୍ରମରେ କାଟାଗୋରାଇଜ୍ କରିବା
        val sheetOrder = listOf(
            "CO", "BM", "AM", "CM", "Auditors", "Telecallers", "HO", "MSME", "NPA", "Undefine", "Left"
        )

        val categorizedMap = mutableMapOf<String, MutableList<Map<String, String>>>()
        for (s in sheetOrder) {
            categorizedMap[s] = mutableListOf()
        }

        for (rec in allRecords) {
            val sourceSheet = rec["Source_Sheet"] ?: ""
            val desig = rec["DESIGNATION"]?.lowercase() ?: ""
            val dept = rec["DEPARTMENT"]?.lowercase() ?: ""

            val assigned = when {
                sourceSheet.equals("Left", ignoreCase = true) -> "Left"
                dept.contains("npa") -> "NPA"
                desig.contains("tele") || dept.contains("tele") -> "Telecallers"
                desig.contains("credit officer") -> "CO"
                desig.contains("branch manager") || desig.contains("branch incharge") || desig.contains("assistant branch manager") -> "BM"
                desig.contains("area manager") -> "AM"
                desig.contains("cluster manager") -> "CM"
                desig.contains("audit") || desig.contains("ia") || desig.contains("auditor") -> "Auditors"
                desig.contains("msme") || dept.contains("msme") -> "MSME"
                sourceSheet.equals("HO", ignoreCase = true) -> "HO"
                else -> "Undefine"
            }
            categorizedMap[assigned]?.add(rec)
        }

        // ୪. ନୂଆ ଏକ୍ସେଲ୍ ଫାଇଲ୍ ତିଆରି କରିବା (Stafflist + DDMMYYYY.xlsx)
        val newWb = XSSFWorkbook()
        val headers = listOf("SL No", "Emp ID", "Employee Name", "Designation", "Department", "Branch Name", "DOJ in Adhikar", "Aadhar", "DOJ", "Contact 1", "Contact 2", "DOB", "Age")
        val keys = listOf("SL No", "Emp. ID", "EMPLOYEE NAME", "DESIGNATION", "DEPARTMENT", "BRANCH NAME", "DOJ IN ADHIKAR", "ADHAR", "DOJ", "Cont-1", "Cont-2", "DOB", "Age")

        for (sName in sheetOrder) {
            val ws = newWb.createSheet(sName)
            val rowsList = categorizedMap[sName] ?: emptyList()

            // Header Row
            val headerRow = ws.createRow(0)
            for (i in headers.indices) {
                headerRow.createCell(i).setCellValue(headers[i])
            }

            // Data Rows
            var rIdx = 1
            for (rowMap in rowsList) {
                val dataRow = ws.createRow(rIdx++)
                for (cIdx in keys.indices) {
                    val valStr = rowMap[keys[cIdx]] ?: ""
                    dataRow.createCell(cIdx).setCellValue(valStr)
                }
            }
        }

        // ୫. ଫାଇଲ୍ ନାମ ଏବଂ ସେଭ୍ କରିବା (Local + Google Drive path)
        val dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMyyyy"))
        val fileName = "Stafflist$dateStr.xlsx"

        // ଲୋକାଲ୍ ଡାଇରେକ୍ଟରି
        val targetFile = File(localOutputDir, fileName)
        targetFile.parentFile?.mkdirs()
        
        // ଗୁଗଲ୍ ଡ୍ରାଇଭ୍ ସିଙ୍କ୍ ଡାଇରେକ୍ଟରି (ଯଦି Google Drive desktop app ଲୋକାଲ୍ କମ୍ପ୍ୟୁଟରରେ ସିଙ୍କ୍ ହେଉଥାଏ)
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