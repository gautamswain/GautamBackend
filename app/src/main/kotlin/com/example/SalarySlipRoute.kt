package com.example

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.sql.Connection
import java.text.DecimalFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Serializable
data class SalarySlipRowRequest(
    val code: String,
    val month: String = ""
)

@Serializable
data class SalarySlipRequest(
    val rows: List<SalarySlipRowRequest>,
    val sendEmail: Boolean = false,
    val saveToDrive: Boolean = true
)

data class SalaryHead(
    val label: String,
    val amount: Double
)

object SalarySlipGenerator {
    private val indianFormat = DecimalFormat("#,##,##0")

    fun formatMoney(amount: Double): String {
        return indianFormat.format(amount)
    }

    fun numberToWords(amount: Double): String {
        val n = amount.toLong()
        if (n == 0L) return "Zero"

        fun convertLessThanOneThousand(num: Int): String {
            val units = arrayOf("", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen")
            val tens = arrayOf("", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety")

            val current = if (num % 100 < 20) {
                units[num % 100]
            } else {
                tens[(num % 100) / 10] + (if (num % 10 > 0) " " + units[num % 10] else "")
            }

            return if (num > 99) {
                units[num / 100] + " Hundred" + (if (num % 100 > 0) " and " + current else "")
            } else {
                current
            }
        }

        var result = ""
        val crore = n / 10000000
        val lakh = (n / 100000) % 100
        val thousand = (n / 1000) % 100
        val hundred = n % 1000

        if (crore > 0) result += convertLessThanOneThousand(crore.toInt()) + " Crore "
        if (lakh > 0) result += convertLessThanOneThousand(lakh.toInt()) + " Lakh "
        if (thousand > 0) result += convertLessThanOneThousand(thousand.toInt()) + " Thousand "
        if (hundred > 0) result += convertLessThanOneThousand(hundred.toInt())

        return result.trim()
    }

    fun fetchSalaryRecord(conn: Connection, empCode: String, monthQuery: String): SalaryDto? {
        val cleanCode = empCode.trim()
        val sql = if (monthQuery.isNotBlank()) {
            "SELECT * FROM SalaryHistory WHERE (UPPER(TRIM(emp_id_2)) = UPPER(TRIM(?)) OR UPPER(TRIM(id)) = UPPER(TRIM(?))) AND UPPER(TRIM(month)) = UPPER(TRIM(?)) LIMIT 1"
        } else {
            "SELECT * FROM SalaryHistory WHERE UPPER(TRIM(emp_id_2)) = UPPER(TRIM(?)) OR UPPER(TRIM(id)) = UPPER(TRIM(?)) ORDER BY rowid DESC LIMIT 1"
        }

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, cleanCode)
            stmt.setString(2, cleanCode)
            if (monthQuery.isNotBlank()) {
                stmt.setString(3, monthQuery)
            }
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return parseSalaryDto(rs)
                }
            }
        }
        return null
    }

    fun generateHtml(sal: SalaryDto, master: MasterDataDto?): String {
        val monthName = sal.month.ifBlank { "Current Month" }
        val empName = sal.employeeName3.ifBlank { master?.employeeName ?: sal.empId2 }
        val empCode = sal.empId2.ifBlank { master?.empId ?: sal.id }
        val designation = sal.designation7.ifBlank { master?.designation ?: "" }
        val department = sal.department8.ifBlank { master?.department ?: "" }
        val branch = sal.branchName9.ifBlank { master?.branchName ?: "" }
        val paidDays = sal.paidDays8.ifBlank { "30" }
        val bankName = sal.bankName19.ifBlank { master?.bankName ?: "" }
        val accountNo = sal.bankAccountNumber20.ifBlank { master?.bankAccountNumber ?: "" }
        val ifsc = sal.ifscCodeBankBranchName21.ifBlank { master?.ifscCodeBankBranchName ?: "" }
        val doj = DateUtils.toDisplayDate(master?.dojInAdhikar.orEmpty())
        val dob = DateUtils.toDisplayDate(master?.dob.orEmpty())
        val gender = master?.gender.orEmpty()
        val pan = master?.panCardNo.orEmpty()

        // Earnings
        val rawEarnings = listOf(
            SalaryHead("Basic + DA", sal.basicDa9.toDoubleOrNull() ?: 0.0),
            SalaryHead("HRA", sal.hra10.toDoubleOrNull() ?: 0.0),
            SalaryHead("Conveyance", sal.conveyance11.toDoubleOrNull() ?: 0.0),
            SalaryHead("Medical Allowance", sal.medicalAllowance12.toDoubleOrNull() ?: 0.0),
            SalaryHead("Education Allowance", sal.educationAllowance13.toDoubleOrNull() ?: 0.0),
            SalaryHead("Lunch / Canteen Allowance", sal.lunchCanteenAllowance14.toDoubleOrNull() ?: 0.0),
            SalaryHead("Special Allowance", sal.specialAllowance15.toDoubleOrNull() ?: 0.0),
            SalaryHead("HRA Variable", sal.hraVariable39.toDoubleOrNull() ?: 0.0),
            SalaryHead("Other Allowance", sal.otherAllowance17.toDoubleOrNull() ?: 0.0),
            SalaryHead("DA / AOSA", sal.daAosa40.toDoubleOrNull() ?: 0.0),
            SalaryHead("Arrears / Increment", sal.arrearsIncrement41.toDoubleOrNull() ?: 0.0),
            SalaryHead("Other Earnings", sal.otherEarnings46.toDoubleOrNull() ?: 0.0),
            SalaryHead("Back Salary", sal.backSalaryGive.toDoubleOrNull() ?: 0.0)
        )

        // Deductions
        val rawDeductions = listOf(
            SalaryHead("EPF", sal.epf5549.toDoubleOrNull() ?: 0.0),
            SalaryHead("ESI", sal.esi5650.toDoubleOrNull() ?: 0.0),
            SalaryHead("Professional Tax", sal.professionalTax22.toDoubleOrNull() ?: 0.0),
            SalaryHead("TDS", sal.tds42.toDoubleOrNull() ?: 0.0),
            SalaryHead("Staff Loan", sal.staffLoan43.toDoubleOrNull() ?: 0.0),
            SalaryHead("Staff Advance", sal.staffAdvance44.toDoubleOrNull() ?: 0.0),
            SalaryHead("Others / Other Deductions", sal.others45.toDoubleOrNull() ?: 0.0)
        )

        val validEarnings = rawEarnings.filter { it.label.isNotBlank() && it.amount > 0.0 }
        val validDeductions = rawDeductions.filter { it.label.isNotBlank() && it.amount > 0.0 }

        val grossEarnings = sal.grossEarnings28.toDoubleOrNull() ?: validEarnings.sumOf { it.amount }
        val grossDeductions = sal.grossDeductions29.toDoubleOrNull() ?: validDeductions.sumOf { it.amount }
        val netAmount = sal.netAmount30.toDoubleOrNull() ?: (grossEarnings - grossDeductions)

        val maxRows = maxOf(validEarnings.size, validDeductions.size)
        val rowsHtml = StringBuilder()

        for (i in 0 until maxRows) {
            val earn = validEarnings.getOrNull(i)
            val ded = validDeductions.getOrNull(i)

            val earnLabel = earn?.label ?: ""
            val earnAmt = if (earn != null) formatMoney(earn.amount) else ""
            val dedLabel = ded?.label ?: ""
            val dedAmt = if (ded != null) formatMoney(ded.amount) else ""

            rowsHtml.append("""
                <tr>
                    <td>$earnLabel</td>
                    <td class="text-right">$earnAmt</td>
                    <td>$dedLabel</td>
                    <td class="text-right">$dedAmt</td>
                }
            """.trimIndent())
        }

        val netWords = numberToWords(netAmount)

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8"/>
                <style>
                    body { font-family: 'Helvetica', 'Arial', sans-serif; font-size: 11px; color: #222; margin: 0; padding: 15px; }
                    .header { text-align: center; border-bottom: 2px solid #003366; padding-bottom: 8px; margin-bottom: 12px; }
                    .company-name { font-size: 16px; font-weight: bold; color: #003366; margin: 0; }
                    .company-address { font-size: 9px; color: #555; margin: 3px 0; }
                    .slip-title { font-size: 13px; font-weight: bold; background: #003366; color: #fff; padding: 4px; margin-top: 8px; }
                    .grid { width: 100%; border-collapse: collapse; margin-bottom: 12px; }
                    .grid td { padding: 4px 6px; border: 1px solid #ccc; font-size: 10px; width: 25%; }
                    .grid td.label { font-weight: bold; background: #f0f4f8; color: #333; }
                    .table { width: 100%; border-collapse: collapse; margin-bottom: 10px; }
                    .table th, .table td { border: 1px solid #ccc; padding: 5px 8px; font-size: 10px; }
                    .table th { background: #003366; color: #fff; text-align: left; }
                    .text-right { text-align: right; }
                    .total-row { font-weight: bold; background: #eef2f7; }
                    .net-box { border: 2px solid #003366; padding: 6px; background: #f9fbff; font-weight: bold; font-size: 11px; margin-top: 10px; }
                    .footer { text-align: center; font-size: 9px; color: #666; margin-top: 25px; border-top: 1px solid #ddd; padding-top: 6px; }
                </style>
            </head>
            <body>
                <div class="header">
                    <div class="company-name">ADHIKAR MICROFINANCE PVT. LTD.</div>
                    <div class="company-address">Plot No. 77/180/970, Subudhipur, Tamando, Kalinga Vihar, Bhubaneswar-751028, Odisha</div>
                    <div class="slip-title">Salary Slip for the Month Of $monthName</div>
                </div>

                <table class="grid">
                    <tr>
                        <td class="label">Employee Name</td>
                        <td>$empName</td>
                        <td class="label">Employee Code</td>
                        <td>$empCode</td>
                    </tr>
                    <tr>
                        <td class="label">Designation</td>
                        <td>$designation</td>
                        <td class="label">Department</td>
                        <td>$department</td>
                    </tr>
                    <tr>
                        <td class="label">Branch / Location</td>
                        <td>$branch</td>
                        <td class="label">Paid Days</td>
                        <td>$paidDays</td>
                    </tr>
                    <tr>
                        <td class="label">Bank Name</td>
                        <td>$bankName</td>
                        <td class="label">Bank A/C Number</td>
                        <td>$accountNo</td>
                    </tr>
                    <tr>
                        <td class="label">IFSC Code</td>
                        <td>$ifsc</td>
                        <td class="label">PAN No.</td>
                        <td>$pan</td>
                    </tr>
                    <tr>
                        <td class="label">Date of Joining</td>
                        <td>$doj</td>
                        <td class="label">Date of Birth / Gender</td>
                        <td>$dob / $gender</td>
                    </tr>
                </table>

                <table class="table">
                    <thead>
                        <tr>
                            <th>Earnings</th>
                            <th class="text-right">Amount (₹)</th>
                            <th>Deductions</th>
                            <th class="text-right">Amount (₹)</th>
                        </tr>
                    </thead>
                    <tbody>
                        $rowsHtml
                        <tr class="total-row">
                            <td>Gross Earnings</td>
                            <td class="text-right">${formatMoney(grossEarnings)}</td>
                            <td>Gross Deductions</td>
                            <td class="text-right">${formatMoney(grossDeductions)}</td>
                        </tr>
                    </tbody>
                </table>

                <div class="net-box">
                    Net Salary :- ₹${formatMoney(netAmount)} &nbsp;&nbsp;&nbsp;(Rupees $netWords Only)
                </div>

                <div class="footer">
                    This is a computer generated statement and does not require any signature or stamp.
                </div>
            </body>
            </html>
        """.trimIndent()
    }
}

fun Route.salarySlipRoutes(dbConnectionProvider: () -> Connection) {
    get("/api/turso/salary-slip/preview") {
        val code = call.request.queryParameters["code"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing code parameter"))
        val month = call.request.queryParameters["month"].orEmpty()

        try {
            dbConnectionProvider().use { conn ->
                val sal = SalarySlipGenerator.fetchSalaryRecord(conn, code, month)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Salary record not found for code: $code"))
                val master = TursoDatabaseService.getRecordByEmpId(code)
                val html = SalarySlipGenerator.generateHtml(sal, master)
                call.respondText(html, ContentType.Text.Html, HttpStatusCode.OK)
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Preview failed")))
        }
    }

    post("/api/turso/salary-slip/generate") {
        try {
            val req = call.receive<SalarySlipRequest>()
            if (req.rows.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No rows provided"))
                return@post
            }

            val zipStream = ByteArrayOutputStream()
            val singlePdfs = mutableListOf<Pair<String, ByteArray>>()

            dbConnectionProvider().use { conn ->
                for (row in req.rows) {
                    val sal = SalarySlipGenerator.fetchSalaryRecord(conn, row.code, row.month)
                    if (sal == null) continue

                    val master = TursoDatabaseService.getRecordByEmpId(row.code)
                    val html = SalarySlipGenerator.generateHtml(sal, master)

                    val empName = sal.employeeName3.ifBlank { master?.employeeName ?: row.code }
                        .replace(Regex("[^A-Za-z0-9_-]"), "_")
                    val safeCode = row.code.replace(Regex("[^A-Za-z0-9_-]"), "_")
                    val monthStr = (row.month.ifBlank { sal.month }).replace(Regex("[^A-Za-z0-9_-]"), "_")
                    val fileName = "Salary Slip_${empName}_${safeCode}_${monthStr}.pdf"

                    val pdfBytes = LetterPdfService.generateLetterPdf(
                        formattedHtmlBody = html,
                        refNo = "SAL/${row.code}/${System.currentTimeMillis()}",
                        date = LocalDate.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy"))
                    )

                    if (req.saveToDrive) {
                        try {
                            GoogleDriveReportService.uploadStaffFile(
                                empCode = row.code,
                                fileName = fileName,
                                mimeType = "application/pdf",
                                bytes = pdfBytes
                            )
                        } catch (_: Exception) {}
                    }

                    if (req.sendEmail) {
                        try {
                            val empModel = BulkLetterService.fetchEmployeeFromMasterData(conn, row.code)
                            if (empModel != null) {
                                val toMail = BulkLetterService.resolveEmailField(conn, "ownp,owno", empModel)
                                if (toMail.isNotBlank()) {
                                    BulkLetterService.sendEmailWithJakartaMail(
                                        to = toMail,
                                        cc = "",
                                        subject = "Salary Slip for $monthStr - ${sal.employeeName3}",
                                        htmlBody = "<p>Dear ${sal.employeeName3},</p><p>Please find your salary slip for <b>$monthStr</b> attached herewith.</p>",
                                        pdfBytes = pdfBytes,
                                        pdfName = fileName
                                    )
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    singlePdfs.add(fileName to pdfBytes)
                }
            }

            if (singlePdfs.isEmpty()) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "No valid salary records found for generation"))
                return@post
            }

            if (singlePdfs.size == 1) {
                val (fileName, bytes) = singlePdfs.first()
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(
                        ContentDisposition.Parameters.FileName,
                        fileName
                    ).toString()
                )
                call.respondBytes(bytes, ContentType.Application.Pdf)
            } else {
                ZipOutputStream(zipStream).use { zip ->
                    for ((fileName, bytes) in singlePdfs) {
                        zip.putNextEntry(ZipEntry(fileName))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(
                        ContentDisposition.Parameters.FileName,
                        "Salary_Slips.zip"
                    ).toString()
                )
                call.respondBytes(zipStream.toByteArray(), ContentType.Application.Zip)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Generation failed")))
        }
    }
}
