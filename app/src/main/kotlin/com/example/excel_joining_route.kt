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

fun Route.newJoiningRouting() {
    post("/api/turso/upload-new-joining") {
        val multipart = call.receiveMultipart()
        val employeeList = mutableListOf<NewJMasterDto>()
        val formatter = DataFormatter()

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
                            val dto = NewJMasterDto(
                                slNo = slNo,
                                empName = empName,
                                fathersName = getCellString(row, 2),
                                dob = getCellString(row, 3),
                                designation = getCellString(row, 4),
                                branchName = getCellString(row, 5),
                                areaName = getCellString(row, 6),
                                dojInAdhikar = getCellString(row, 7),
                                dojInBranch = getCellString(row, 8),
                                presentAddress = getCellString(row, 9),
                                permanentAddress = getCellString(row, 10),
                                education = getCellString(row, 11),
                                contactNo = getCellString(row, 12),
                                aadharNo = getCellString(row, 13),
                                panNo = getCellString(row, 14),
                                bankName = getCellString(row, 15),
                                accountNo = getCellString(row, 16),
                                ifscCode = getCellString(row, 17),
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