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
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.util.Locale

fun Route.newJoiningRouting() {
    post("/api/turso/upload-new-joining") {
        val multipart = call.receiveMultipart()
        val employeeList = mutableListOf<NewJMasterDto>()
        val formatter = DataFormatter()

        // Helper function to clean, validate, and standardize date format to yyyy-MM-dd with 1950 year pivot
        fun cleanAndFormatDate(rawDate: String): String {
            val cleanStr = rawDate.trim()
            if (cleanStr.isBlank()) return ""
            
            val customFormatterD = DateTimeFormatterBuilder()
                .appendPattern("d-MMM-")
                .appendValueReduced(ChronoField.YEAR, 2, 2, 1950)
                .toFormatter(Locale.ENGLISH)

            val customFormatterDd = DateTimeFormatterBuilder()
                .appendPattern("dd-MMM-")
                .appendValueReduced(ChronoField.YEAR, 2, 2, 1950)
                .toFormatter(Locale.ENGLISH)

            val formatters = listOf(
                customFormatterD,
                customFormatterDd,
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
                    return parsedDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
                } catch (_: DateTimeParseException) {}
            }
            return rawDate.trim()
        }

        // Helper function to convert uppercase/raw strings into Proper Case (Title Case)
        fun toProperCase(text: String): String {
            if (text.isBlank()) return ""
            return text.trim().lowercase().split(Regex("\\s+"))
                .joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
                }
        }

        // Helper to clean and format address strings into Proper Case while preserving punctuation and abbreviations
        fun cleanAddress(rawAddress: String): String {
            if (rawAddress.isBlank()) return ""
            val normalized = rawAddress.replace("\r\n", ", ")
                .replace("\n", ", ")
                .replace("\r", ", ")
                .replace(Regex("\\s+"), " ")
                .trim()

            return normalized.split(", ").map { part ->
                part.split("/").map { subPart ->
                    subPart.split(" ").map { word ->
                        if (word.isNotEmpty()) {
                            // Capitalize first letter of each sub-word, keeping acronyms or abbreviations readable
                            word.lowercase().replaceFirstChar { it.titlecase(Locale.ENGLISH) }
                        } else ""
                    }.joinToString(" ")
                }.joinToString("/")
            }.joinToString(", ")
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

                    // Read rows
                    for (row in sheet.take(10)) {
                        val slNo = getCellString(row, 0)
                        val rawEmpName = getCellString(row, 1)

                        if (slNo.isNotBlank() && rawEmpName.isNotBlank() && slNo.lowercase() != "sl.no") {
                            val rawAadhar = getCellString(row, 13)
                            val rawAccount = getCellString(row, 16)
                            val rawDob = getCellString(row, 3)
                            val rawDojAdhikar = getCellString(row, 7)
                            val rawDojBranch = getCellString(row, 8)

                            val cleanAadhar = rawAadhar.replace(Regex("[^0-9]"), "").trim()
                            val cleanAccount = rawAccount.replace(Regex("[^0-9]"), "").trim()

                            val dto = NewJMasterDto(
                                slNo = slNo,
                                empName = toProperCase(rawEmpName),
                                fathersName = toProperCase(getCellString(row, 2)),
                                dob = cleanAndFormatDate(rawDob),
                                designation = toProperCase(getCellString(row, 4)),
                                branchName = toProperCase(getCellString(row, 5)),
                                areaName = toProperCase(getCellString(row, 6)),
                                dojInAdhikar = cleanAndFormatDate(rawDojAdhikar),
                                dojInBranch = cleanAndFormatDate(rawDojBranch),
                                presentAddress = cleanAddress(getCellString(row, 9)),
                                permanentAddress = cleanAddress(getCellString(row, 10)),
                                education = getCellString(row, 11).uppercase(),
                                contactNo = getCellString(row, 12),
                                aadharNo = cleanAadhar,
                                panNo = getCellString(row, 14).replace(Regex("[^A-Za-z0-9]"), "").trim().uppercase(),
                                bankName = getCellString(row, 15).uppercase(),
                                accountNo = cleanAccount,
                                ifscCode = getCellString(row, 17).replace(Regex("[^A-Za-z0-9]"), "").trim().uppercase(),
                                esi = getCellString(row, 18),
                                uanNo = getCellString(row, 19),
                                gender = toProperCase(getCellString(row, 20)),
                                contactno2 = getCellString(row, 21),
                                email = getCellString(row, 22).lowercase(),
                                companyExperience = getCellString(row, 23),
                                refferencedBy = toProperCase(getCellString(row, 24)),
                                interviewdBy = toProperCase(getCellString(row, 25))
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