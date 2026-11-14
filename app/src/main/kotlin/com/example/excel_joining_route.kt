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

data class DesignationEntity(
    val slno: String,
    val designation: String,
    val tier: String,
    val degcode: String,
    val department: String,
    val shtcode: String
)

object MasterDataRepository {
    private var cachedBranches: List<String> = emptyList()
    private var cachedDesignations: List<DesignationEntity> = emptyList()
    private var lastFetchTime: Long = 0L
    private const val TTL_MS = 10 * 60 * 1000L // 10 minutes

    // Clear/refresh cache helper method
    @Synchronized
    fun clearCache() {
        cachedBranches = emptyList()
        cachedDesignations = emptyList()
        lastFetchTime = 0L
    }

    // Fetch official branch names dynamically with 10-minute TTL cache
    @Synchronized
    fun getOfficialBranches(): List<String> {
        val now = System.currentTimeMillis()
        if (cachedBranches.isNotEmpty() && (now - lastFetchTime < TTL_MS)) {
            return cachedBranches
        }

        val branchList = mutableListOf<String>()
        val conn = TursoDatabaseService.getConnection()
        if (conn != null) {
            try {
                val pstmt = conn.prepareStatement("SELECT branchname FROM BranchDetails")
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    val bName = rs.getString("branchname") ?: rs.getString(1)
                    if (!bName.isNullOrBlank()) {
                        branchList.add(bName.trim())
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
        
        // Fallback list if DB connection fails or table is empty
        if (branchList.isEmpty()) {
            cachedBranches = listOf(
                "Barpali", "Sohela", "Padampur", "Kesinga", "Tarbha", "Th.Rampur", "Koraput", "Kotpad",
                "Semeliguda", "Mathili", "Ambadola", "Rayagada", "Karanjia", "Keonjhar", "Ghatagaon",
                "Anandpur", "Raikia", "Baliguda", "Phulbani", "Kamarda", "Agarapada", "Bhadrak",
                "Chandikhol", "Chhatia", "Haripur", "Jajpur Town", "Salepur", "Adaspur", "Banki",
                "Baranga", "Kandarpur", "Phulanakhara", "Balakati", "Puri", "Sakhigopal", "Balugaon",
                "Malkangiri", "Kalampur"
            )
        } else {
            cachedBranches = branchList
        }
        lastFetchTime = now
        return cachedBranches
    }

    // Fetch official designations dynamically with 10-minute TTL cache
    @Synchronized
    fun getOfficialDesignations(): List<DesignationEntity> {
        val now = System.currentTimeMillis()
        if (cachedDesignations.isNotEmpty() && (now - lastFetchTime < TTL_MS)) {
            return cachedDesignations
        }

        val list = mutableListOf<DesignationEntity>()
        val conn = TursoDatabaseService.getConnection()
        if (conn != null) {
            try {
                val pstmt = conn.prepareStatement("SELECT Designationslno, designation, tier, degcode, department, shtcode FROM Designation")
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    list.add(
                        DesignationEntity(
                            slno = rs.getString("Designationslno") ?: rs.getString(1) ?: "",
                            designation = rs.getString("designation") ?: rs.getString(2) ?: "",
                            tier = rs.getString("tier") ?: rs.getString(3) ?: "",
                            degcode = rs.getString("degcode") ?: rs.getString(4) ?: "",
                            department = rs.getString("department") ?: rs.getString(5) ?: "",
                            shtcode = rs.getString("shtcode") ?: rs.getString(6) ?: ""
                        )
                    )
                }
                rs.close()
                pstmt.close()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { conn.close() } catch (_: Exception) {}
            }
        }
        cachedDesignations = list
        lastFetchTime = now
        return cachedDesignations
    }
}

fun Route.newJoiningRouting() {
    post("/api/turso/upload-new-joining") {
        val multipart = call.receiveMultipart()
        val employeeList = mutableListOf<NewJMasterDto>()
        val formatter = DataFormatter()

        // Load official branches dynamically and build normalized lookup map for typo rectification
        val officialBranches = MasterDataRepository.getOfficialBranches()
        val branchLookup = officialBranches.associateBy { it.lowercase().replace(Regex("[^a-z0-9]"), "") }

        // Load official designations dynamically and build normalized lookup map
        val officialDesignations = MasterDataRepository.getOfficialDesignations()
        val designationLookup = officialDesignations.associateBy { it.designation.lowercase().replace(Regex("[^a-z0-9]"), "") }

        // Helper function to rectify and standardize branch name
        fun rectifyBranchName(rawBranch: String): String {
            val clean = rawBranch.trim()
            if (clean.isBlank()) return ""
            val key = clean.lowercase().replace(Regex("[^a-z0-9]"), "")
            return branchLookup[key] ?: officialBranches.find { it.equals(clean, ignoreCase = true) } 
                ?: clean.lowercase().split(Regex("\\s+")).joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
                }
        }

        // Helper function to rectify and standardize designation against Designation table
        fun rectifyDesignation(rawDesignation: String): String {
            val clean = rawDesignation.trim()
            if (clean.isBlank()) return ""
            val key = clean.lowercase().replace(Regex("[^a-z0-9]"), "")
            return designationLookup[key]?.designation 
                ?: officialDesignations.find { it.designation.equals(clean, ignoreCase = true) }?.designation
                ?: clean.lowercase().split(Regex("\\s+")).joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
                }
        }

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

                    // Skip header row and process up to 100 rows
                    for (row in sheet.drop(1).take(100)) {
                        val slNo = getCellString(row, 0)
                        val rawEmpName = getCellString(row, 1)

                        println("Row Check -> Sl.No: '$slNo', Emp Name: '$rawEmpName'")

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
                                designation = rectifyDesignation(getCellString(row, 4)), // Rectified & validated against Designation table
                                branchName = rectifyBranchName(getCellString(row, 5)), // Rectified & validated against BranchDetails table
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