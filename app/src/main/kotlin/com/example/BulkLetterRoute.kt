package com.example

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.sql.Connection
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Serializable
data class BulkLetterRow(
    val code: String,
    val letter: String,
    val promoTo: String? = null,
    @SerialName("dBranch") val dBranch: String? = null,
    val wef: String? = null,
    val address: String? = null
)

@Serializable
data class GenerateLetterRequest(
    val rows: List<BulkLetterRow>
)

data class MasterEmployeeData(
    val name: String,           // {1}
    val code: String,           // {2}
    val department: String,     // {3}
    val designation: String,    // {4}
    val branch: String,         // {5}
    val gender: String,         // For {17} & {18}
    val doj: String,            // {8}
    val dol: String,            // {9}
    val salary: String,         // {11}
    val fatherName: String,     // {14}
    val presentAddress: String, // {15}
    val mobileNo: String,       // {16}
    val email: String           // {emp_mail}
) {
    val heShe: String
        get() = if (gender.trim().equals("Female", ignoreCase = true) ||
            gender.trim().equals("F", ignoreCase = true)
        ) "She" else "He"

    val hisHer: String
        get() = if (gender.trim().equals("Female", ignoreCase = true) ||
            gender.trim().equals("F", ignoreCase = true)
        ) "Her" else "His"
}

data class BulkLetterTemplateConfig(
    val lettercode: String,
    val templete: String,
    val subject: String,
    val body: String,
    val tomail: String,
    val ccmail: String,
    val wef: String
)

object BulkLetterService {

    private val istZone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val displayDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    fun fetchEmployeeFromMasterData(conn: Connection, empCode: String): MasterEmployeeData? {
        val sql = """
            SELECT * FROM MasterData 
            WHERE UPPER(TRIM(Code_2)) = UPPER(TRIM(?)) 
            LIMIT 1
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, empCode)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return null
                val dto = StaffRepository.parseMasterDataDto(rs)
                return MasterEmployeeData(
                    name = dto.employeeName.ifBlank { dto.id },
                    code = dto.empId.ifBlank { empCode },
                    department = dto.department,
                    designation = dto.designation,
                    branch = dto.branchName,
                    gender = dto.gender,
                    doj = dto.dojInAdhikar,
                    dol = dto.dol,
                    salary = dto.salaryCode.ifBlank { dto.slary },
                    fatherName = dto.fathersName,
                    presentAddress = dto.presentAddress,
                    mobileNo = dto.personalContactNumber,
                    email = dto.email61Personal.ifBlank { dto.mailIdOffice }
                )
            }
        }
    }

    fun fetchLetterConfigFromDb(conn: Connection, letterCode: String): BulkLetterTemplateConfig? {
        val sql = """
            SELECT lettercode, templete, subject, body, tomail, ccmail, wef 
            FROM bulkletter 
            WHERE TRIM(lettercode) = TRIM(?) 
            LIMIT 1
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, letterCode)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return BulkLetterTemplateConfig(
                        lettercode = rs.getString("lettercode")?.trim().orEmpty(),
                        templete = rs.getString("templete")?.trim().orEmpty(),
                        subject = rs.getString("subject")?.trim().orEmpty(),
                        body = rs.getString("body")?.trim().orEmpty(),
                        tomail = rs.getString("tomail")?.trim().orEmpty(),
                        ccmail = rs.getString("ccmail")?.trim().orEmpty(),
                        wef = rs.getString("wef")?.trim().orEmpty()
                    )
                }
            }
        }
        return null
    }

    fun fetchResponsibleBranches(conn: Connection, empCode: String): String {
        val sql = "SELECT * FROM branchdetails"
        val matchedBranches = mutableListOf<String>()

        conn.prepareStatement(sql).use { stmt ->
            stmt.executeQuery().use { rs ->
                val meta = rs.metaData
                val colCount = meta.columnCount

                while (rs.next()) {
                    var branchName = ""
                    var isResponsible = false

                    for (i in 1..colCount) {
                        val colName = meta.getColumnName(i).lowercase()
                        val value = rs.getString(i)?.trim().orEmpty()

                        if (branchName.isBlank() &&
                            colName in setOf("branch", "branch_name", "branchname", "name")
                        ) {
                            branchName = value
                        }

                        if (value.equals(empCode.trim(), ignoreCase = true) &&
                            colName !in setOf("branch", "branch_name", "branchname", "id")
                        ) {
                            isResponsible = true
                        }
                    }

                    if (isResponsible && branchName.isNotBlank() && !matchedBranches.contains(branchName)) {
                        matchedBranches.add(branchName)
                    }
                }
            }
        }
        return matchedBranches.joinToString(", ")
    }

    fun fetchProbationDays(conn: Connection, designationName: String): String {
        val cleanDesignation = designationName.trim()
        if (cleanDesignation.isBlank()) return ""

        val sql = """
            SELECT probday FROM designation 
            WHERE UPPER(TRIM(designation)) = UPPER(TRIM(?)) 
               OR UPPER(TRIM(degcode)) = UPPER(TRIM(?))
               OR UPPER(TRIM(shtcode)) = UPPER(TRIM(?))
            LIMIT 1
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, cleanDesignation)
            stmt.setString(2, cleanDesignation)
            stmt.setString(3, cleanDesignation)

            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return rs.getString("probday")?.trim().orEmpty()
                }
            }
        }
        return ""
    }

    fun calculateInterviewDate(dojStr: String): String {
        val cleanDoj = dojStr.trim()
        if (cleanDoj.isBlank()) return ""

        val formats = listOf(
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d-M-yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy")
        )

        for (fmt in formats) {
            try {
                return LocalDate.parse(cleanDoj, fmt)
                    .minusDays(1)
                    .format(displayDateFormatter)
            } catch (_: Exception) { }
        }
        return ""
    }

    private fun xmlEscape(str: String): String {
        return str.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
    }

    fun applyPlaceholders(
        rawTemplate: String,
        emp: MasterEmployeeData,
        row: BulkLetterRow,
        responsibleBranches: String,
        probationPeriod: String,
        isEmailField: Boolean = false
    ): String {
        val todayDate = LocalDate.now(istZone).format(displayDateFormatter)
        val interviewDate = calculateInterviewDate(emp.doj)

        val effectiveBranch = row.dBranch?.takeIf { it.isNotBlank() } ?: emp.branch
        val effectiveAddress = row.address?.takeIf { it.isNotBlank() } ?: emp.presentAddress
        val effectiveDesignation = row.promoTo?.takeIf { it.isNotBlank() } ?: emp.designation
        val effectiveDolOrWef = row.wef?.takeIf { it.isNotBlank() } ?: emp.dol

        val mapper: (String) -> String = if (isEmailField) { { it } } else { { xmlEscape(it) } }

        return rawTemplate
            .replace("{1}", mapper(emp.name))
            .replace("{2}", mapper(emp.code))
            .replace("{3}", mapper(emp.department))
            .replace("{4}", mapper(effectiveDesignation))
            .replace("{5}", mapper(effectiveBranch))
            .replace("{8}", mapper(emp.doj))
            .replace("{9}", mapper(effectiveDolOrWef))
            .replace("{11}", mapper(emp.salary))
            .replace("{13}", mapper(probationPeriod))
            .replace("{14}", mapper(emp.fatherName))
            .replace("{15}", mapper(effectiveAddress))
            .replace("{16}", mapper(emp.mobileNo))
            .replace("{17}", mapper(emp.heShe))
            .replace("{18}", mapper(emp.hisHer))
            .replace("{17_lower}", mapper(emp.heShe.lowercase()))
            .replace("{18_lower}", mapper(emp.hisHer.lowercase()))
            .replace("{19}", mapper(todayDate))
            .replace("{20}", mapper(responsibleBranches))
            .replace("{21}", mapper(interviewDate))
            .replace("{emp_mail}", mapper(emp.email))
    }

    fun resolveEmailField(conn: Connection, rawToken: String, emp: MasterEmployeeData): String {
        val tokens = rawToken.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val resolved = mutableListOf<String>()
        for (token in tokens) {
            if (token.equals("own", ignoreCase = true)) {
                if (emp.email.isNotBlank()) resolved.add(emp.email)
            } else if (token.contains("@")) {
                resolved.add(token)
            } else {
                val otherEmp = fetchEmployeeFromMasterData(conn, token)
                if (otherEmp != null && otherEmp.email.isNotBlank()) {
                    resolved.add(otherEmp.email)
                } else {
                    val emailSql = "SELECT mail_id_office, email61_personal FROM MasterData WHERE UPPER(TRIM(Code_2)) = UPPER(TRIM(?)) LIMIT 1"
                    try {
                        conn.prepareStatement(emailSql).use { ps ->
                            ps.setString(1, token)
                            ps.executeQuery().use { rs ->
                                if (rs.next()) {
                                    val mail = (rs.getString("mail_id_office") ?: rs.getString("email61_personal") ?: "").trim()
                                    if (mail.isNotBlank()) resolved.add(mail)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        return resolved.joinToString(", ")
    }

    fun insertLetterEmailRecord(
        conn: Connection,
        code: String,
        letterNo: String,
        toMail: String,
        ccMail: String,
        subject: String,
        body: String,
        attachmentLink: String
    ) {
        val sql = """
            INSERT INTO letteremail (code, letterno, tomail, ccmail, subject, body, attachmentlink)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, code)
            stmt.setString(2, letterNo)
            stmt.setString(3, toMail)
            stmt.setString(4, ccMail)
            stmt.setString(5, subject)
            stmt.setString(6, body)
            stmt.setString(7, attachmentLink)
            stmt.executeUpdate()
        }
    }
}

fun Route.bulkLetterRoutes(dbConnectionProvider: () -> Connection) {
    post("/api/turso/generateletter") {
        try {
            val text = call.receiveText()
            val rows = try {
                Json.decodeFromString<List<BulkLetterRow>>(text)
            } catch (_: Exception) {
                try {
                    Json.decodeFromString<GenerateLetterRequest>(text).rows
                } catch (e: Exception) {
                    emptyList()
                }
            }

            if (rows.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No rows provided"))
                return@post
            }

            val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))
                .format(DateTimeFormatter.ofPattern("dd-MM-yyyy"))
            val zipStream = ByteArrayOutputStream()

            dbConnectionProvider().use { conn ->
                ZipOutputStream(zipStream).use { zip ->
                    rows.forEachIndexed { index, row ->
                        val empData = BulkLetterService.fetchEmployeeFromMasterData(conn, row.code)
                            ?: MasterEmployeeData(
                                name = row.code,
                                code = row.code,
                                department = "",
                                designation = row.promoTo ?: "",
                                branch = row.dBranch ?: "",
                                gender = "Male",
                                doj = "",
                                dol = row.wef ?: "",
                                salary = "",
                                fatherName = "",
                                presentAddress = row.address ?: "",
                                mobileNo = "",
                                email = ""
                            )

                        val letterConfig = BulkLetterService.fetchLetterConfigFromDb(conn, row.letter)
                            ?: error("Letter template not found in bulkletter table for lettercode: ${row.letter}")

                        val effectiveRow = if (row.wef.isNullOrBlank() && letterConfig.wef.isNotBlank()) {
                            row.copy(wef = letterConfig.wef)
                        } else {
                            row
                        }

                        val targetDesignation = effectiveRow.promoTo?.takeIf { it.isNotBlank() } ?: empData.designation
                        val probationPeriod = BulkLetterService.fetchProbationDays(conn, targetDesignation)
                        val responsibleBranches = BulkLetterService.fetchResponsibleBranches(conn, row.code)

                        val finalHtmlBody = BulkLetterService.applyPlaceholders(
                            rawTemplate = letterConfig.templete,
                            emp = empData,
                            row = effectiveRow,
                            responsibleBranches = responsibleBranches,
                            probationPeriod = probationPeriod,
                            isEmailField = false
                        )

                        val refNo = "ADH/HR/2026/${index + 1}"
                        val pdfBytes = LetterPdfService.generateLetterPdf(
                            formattedHtmlBody = finalHtmlBody,
                            refNo = refNo,
                            date = today
                        )

                        val safeCode = row.code.ifBlank { "Emp_${index + 1}" }
                            .replace(Regex("[^A-Za-z0-9_-]"), "_")
                        val pdfFileName = "${safeCode}_Letter_${row.letter}.pdf"

                        // 1. Upload PDF to Google Drive (AdhikarHR/FILE<digits>/)
                        var attachmentLink = ""
                        try {
                            val driveResult = GoogleDriveReportService.uploadStaffFile(
                                empCode = row.code,
                                fileName = pdfFileName,
                                mimeType = "application/pdf",
                                bytes = pdfBytes
                            )
                            val driveFileId = driveResult["id"].orEmpty()
                            if (driveFileId.isNotBlank()) {
                                attachmentLink = "https://drive.google.com/file/d/$driveFileId/view"
                            }
                        } catch (_: Exception) { }

                        // 2. Resolve Email fields & save into letteremail table
                        val resolvedSubject = BulkLetterService.applyPlaceholders(
                            letterConfig.subject, empData, effectiveRow, responsibleBranches, probationPeriod, isEmailField = true
                        )
                        val resolvedBody = BulkLetterService.applyPlaceholders(
                            letterConfig.body, empData, effectiveRow, responsibleBranches, probationPeriod, isEmailField = true
                        )
                        val resolvedToMail = BulkLetterService.resolveEmailField(conn, letterConfig.tomail, empData)
                        val resolvedCcMail = BulkLetterService.resolveEmailField(conn, letterConfig.ccmail, empData)

                        BulkLetterService.insertLetterEmailRecord(
                            conn = conn,
                            code = row.code,
                            letterNo = refNo,
                            toMail = resolvedToMail,
                            ccMail = resolvedCcMail,
                            subject = resolvedSubject,
                            body = resolvedBody,
                            attachmentLink = attachmentLink
                        )

                        // 3. Add PDF to ZIP response
                        zip.putNextEntry(ZipEntry(pdfFileName))
                        zip.write(pdfBytes)
                        zip.closeEntry()
                    }
                }
            }

            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName,
                    "Adhikar_Bulk_Letters.zip"
                ).toString()
            )
            call.respondBytes(zipStream.toByteArray(), ContentType.Application.Zip)
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf(
                    "error" to (e.localizedMessage ?: e.message ?: "Unknown error"),
                    "exception" to e.javaClass.simpleName
                )
            )
        }
    }
}