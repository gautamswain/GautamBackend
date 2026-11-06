package com.example

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.content.*
import io.ktor.http.*
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.InputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

fun Route.newJoiningRouting() {
    post("/api/turso/upload-new-joining") {
        val multipart = call.receiveMultipart()
        val employeeList = mutableListOf<NewJMasterDto>()
        val formatter = DataFormatter()

        // Helper function to clean, validate, and standardize date format to yyyy-MM-dd
        // Supports both single-digit ('7-May-91') and double-digit ('07-May-91') day formats
        fun cleanAndFormatDate(rawDate: String): String {
            val cleanStr = rawDate.trim()
            if (cleanStr.isBlank()) return ""
            
            val formatters = listOf(
                DateTimeFormatter.ofPattern("d-MMM-yy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("d/MMM/yy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("dd/MMM/yy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("d/MMM/yyyy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("dd/MMM/yyyy", Locale.ENGLISH),
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("d-M-yyyy"),
                DateTimeFormatter.ofPattern("dd-MM-yyyy"),
                DateTimeFormatter.ofPattern("d/M/yyyy"),
                DateTimeFormatter.ofPattern("dd/MM/yyyy"),
                DateTimeFormatter.ofPattern("d.M.yyyy"),
                DateTimeFormatter.ofPattern("dd.MM.yyyy")
            )
            
            for (fmt in formatters) {
                try {
                    val parsedDate = LocalDate.parse(cleanStr, fmt)
                    return parsedDate.format(DateTimeFormatter.ISO_LOCAL_DATE) // Returns YYYY-MM-DD
                } catch (_: DateTimeParseException) {}
            }
            return rawDate.trim() // Fallback if unrecognized
        }

        multipart.forEachPart { part ->
            when (part) {
                is PartData.FileItem -> {
                    val fileBytes = part.streamProvider().readBytes()
                    val inputStream: InputStream = fileBytes.inputStream()
                    val workbook = WorkbookFactory.create(inputStream)
                    val sheet = workbook.getSheetAt(0)
                    
                    fun getCellString(row: org.apache.poi.ss.usermodel.Row, index: Int): String {
                        val cell = row.getCell(index) ?: return ""
                        return formatter.formatCellValue(cell).trim()
                    }

                    // Row 1 ru row 10 parjanta read karibapain (index 0 ru take(10) rows)
                    for (row in sheet.take(10)) {
                        val slNo = getCellString(row, 0)
                        val empName = getCellString(row, 1)
                        
                        // Debug log to check row values in Render logs
                        println("Row Check -> Sl.No: '$slNo', Emp Name: '$empName'")

                        // Check if Sl.No is a valid number/present and Emp Name is not blank
                        if (slNo.isNotBlank() && empName.isNotBlank() && slNo.lowercase() != "sl.no") {
                            val rawAadhar = getCellString(row, 13)
                            val rawAccount = getCellString(row, 16)
                            val rawDob = getCellString(row, 3)
                            val rawDojAdhikar = getCellString(row, 7)
                            val rawDojBranch = getCellString(row, 8)

                            // Remove special characters and spaces from Aadhaar and Bank Account Number
                            val cleanAadhar = rawAadhar.replace(Regex("[^0-9]"), "").trim()
                            val cleanAccount = rawAccount.replace(Regex("[^0-9]"), "").trim()

                            val dto = NewJMasterDto(
                                slNo = slNo,
                                empName = empName,
                                fathersName = getCellString(row, 2),
                                dob = cleanAndFormatDate(rawDob),
                                designation = getCellString(row, 4),
                                branchName = getCellString(row, 5),
                                areaName = getCellString(row, 6),
                                dojInAdhikar = cleanAndFormatDate(rawDojAdhikar),
                                dojInBranch = cleanAndFormatDate(rawDojBranch),
                                presentAddress = getCellString(row, 9),
                                permanentAddress = getCellString(row, 10),
                                education = getCellString(row, 11),
                                contactNo = getCellString(row, 12),
                                aadharNo = cleanAadhar,
                                panNo = getCellString(row, 14).replace(Regex("[^A-Za-z0-9]"), "").trim().uppercase(),
                                bankName = getCellString(row, 15),
                                accountNo = cleanAccount,
                                ifscCode = getCellString(row, 17).replace(Regex("[^A-Za-z0-9]"), "").trim().uppercase(),
                                esi = getCellString(row, 18),
                                uanNo = getCellString(row, 19),
                                gender = getCellString(row, 20),
                                contactno2 = getCellString(row, 21),
                                email = getCellString(row, 22),
                                companyExperience = getCellString(row, 23),
                                refferencedBy = getCellString(row, 24),
                                interviewdBy = getCellString(row, 25)
                            )
                            employeeList.add(dto)
                        }
                    }
                    workbook.close()
                }
                else -> {}
            }
            part.dispose()
        }

        call.respond(HttpStatusCode.OK, employeeList)
    }
}