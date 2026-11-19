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

    private fun xmlEscape(str: String): String {
        return str.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
    }

    private fun cleanField(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isBlank() || s.equals("null", ignoreCase = true) || s.equals("n/a", ignoreCase = true) || s.equals("na", ignoreCase = true) || s == "-" || s == "0") {
            return ""
        }
        return s
    }

    fun formatMonthName(rawMonth: String): String {
        val clean = rawMonth.trim()
        if (clean.isBlank()) return "Current Month"

        if (clean.contains("GMT") || clean.contains("UTC") || clean.length > 20) {
            try {
                val parts = clean.split(" ")
                val monthMap = mapOf(
                    "Jan" to "January", "Feb" to "February", "Mar" to "March", "Apr" to "April",
                    "May" to "May", "Jun" to "June", "Jul" to "July", "Aug" to "August",
                    "Sep" to "September", "Oct" to "October", "Nov" to "November", "Dec" to "December"
                )
                val monStr = parts.getOrNull(1) ?: ""
                val yearStr = parts.getOrNull(3) ?: LocalDate.now().year.toString()
                val fullMonth = monthMap[monStr] ?: monStr
                return "$fullMonth-$yearStr"
            } catch (_: Exception) {}
        }

        val serial = clean.toDoubleOrNull()?.toLong()
        if (serial != null && serial in 10000L..90000L) {
            try {
                val date = LocalDate.of(1899, 12, 30).plusDays(serial)
                return date.format(java.time.format.DateTimeFormatter.ofPattern("MMMM-yyyy", java.util.Locale.ENGLISH))
            } catch (_: Exception) {}
        }

        val formats = listOf(
            java.time.format.DateTimeFormatter.ofPattern("MMMM-yyyy", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("MMM-yyyy", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"),
            java.time.format.DateTimeFormatter.ofPattern("MM-yyyy"),
            java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")
        )
        for (fmt in formats) {
            try {
                val parsed = LocalDate.parse(clean, fmt)
                return parsed.format(java.time.format.DateTimeFormatter.ofPattern("MMMM-yyyy", java.util.Locale.ENGLISH))
            } catch (_: Exception) {}
        }

        return clean
    }

    fun generateSecretCode(empCode: String, month: String, netPay: Double): String {
        try {
            val codeForGen = if (empCode.any { it.isDigit() }) empCode else "ADKR0001"
            return LetterCodeGenerator.generate(employeeCode = codeForGen, dateInput = LocalDate.now().toString()).combinedCode
        } catch (_: Exception) {
            try {
                val input = "$empCode-$month-$netPay-ADHIKAR-2026"
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
                val sb = StringBuilder()
                for (b in hashBytes) {
                    val charCode = 'a'.code + (Math.abs(b.toInt()) % 26)
                    sb.append(charCode.toChar())
                }
                return sb.toString().take(13)
            } catch (_: Exception) {
                return "aukuknzachkut"
            }
        }
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
                tens[(num % 100) / 10] + if (num % 10 > 0) " " + units[num % 10] else ""
            }

            return if (num > 99) {
                units[num / 100] + " Hundred" + if (num % 100 > 0) " and " + current else ""
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
        val formattedMonth = formatMonthName(sal.month)
        val empName = xmlEscape(sal.employeeName3.ifBlank { master?.employeeName ?: sal.empId2 })
        val rawEmpCode = sal.empId2.ifBlank { master?.empId ?: sal.id }
        val empCode = xmlEscape(rawEmpCode)
        val designation = xmlEscape(sal.designation7.ifBlank { master?.designation ?: "" })
        val department = xmlEscape(sal.department8.ifBlank { master?.department ?: "" })
        val branch = xmlEscape(sal.branchName9.ifBlank { master?.branchName ?: "" })
        val paidDays = xmlEscape(sal.paidDays8.ifBlank { "30" })
        val bankName = xmlEscape(sal.bankName19.ifBlank { master?.bankName ?: "" })
        val accountNo = xmlEscape(sal.bankAccountNumber20.ifBlank { master?.bankAccountNumber ?: "" })
        val ifsc = xmlEscape(sal.ifscCodeBankBranchName21.ifBlank { master?.ifscCodeBankBranchName ?: "" })
        val doj = xmlEscape(DateUtils.toDisplayDate(master?.dojInAdhikar.orEmpty()))
        val dob = xmlEscape(DateUtils.toDisplayDate(master?.dob.orEmpty()))
        val gender = xmlEscape(master?.gender.orEmpty())
        val pan = xmlEscape(master?.panCardNo.orEmpty())

        val uan = cleanField(master?.epfUanNo)
        val esi = cleanField(master?.esiNo)

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

        val secretCode = generateSecretCode(rawEmpCode, formattedMonth, netAmount)
        val documentHeaderHtml = BrandAssets.renderDocumentHeader("Salary Slip for the Month Of $formattedMonth", secretCode)

        val maxRows = maxOf(validEarnings.size, validDeductions.size)
        val rowsHtml = StringBuilder()

        for (i in 0 until maxRows) {
            val earn = validEarnings.getOrNull(i)
            val ded = validDeductions.getOrNull(i)

            val earnLabel = xmlEscape(earn?.label ?: "")
            val earnAmt = if (earn != null) formatMoney(earn.amount) else ""
            val dedLabel = xmlEscape(ded?.label ?: "")
            val dedAmt = if (ded != null) formatMoney(ded.amount) else ""

            rowsHtml.append("""
                <tr>
                    <td>$earnLabel</td>
                    <td class="text-right">$earnAmt</td>
                    <td>$dedLabel</td>
                    <td class="text-right">$dedAmt</td>
                </tr>
            """.trimIndent())
        }

        val netWords = numberToWords(netAmount)

        val uanEsiRow = when {
            uan.isNotBlank() && esi.isNotBlank() -> """
                <tr>
                    <td class="label">UAN No.</td>
                    <td>${xmlEscape(uan)}</td>
                    <td class="label">ESI No.</td>
                    <td>${xmlEscape(esi)}</td>
                </tr>
            """.trimIndent()
            uan.isNotBlank() -> """
                <tr>
                    <td class="label">UAN No.</td>
                    <td>${xmlEscape(uan)}</td>
                    <td class="label"></td>
                    <td></td>
                </tr>
            """.trimIndent()
            esi.isNotBlank() -> """
                <tr>
                    <td class="label">ESI No.</td>
                    <td>${xmlEscape(esi)}</td>
                    <td class="label"></td>
                    <td></td>
                </tr>
            """.trimIndent()
            else -> ""
        }

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8"/>
                <style>
                    body { font-family: 'Helvetica', 'Arial', sans-serif; font-size: 11px; color: #1F2937; margin: 0; padding: 15px; }
                    .grid { width: 100%; border-collapse: collapse; margin-bottom: 12px; }
                    .grid td { padding: 4px 6px; border: 1px solid #D1D5DB; font-size: 10px; width: 25%; }
                    .grid td.label { font-weight: bold; background: #FFF3E0; color: #1F2937; }
                    .table { width: 100%; border-collapse: collapse; margin-bottom: 10px; }
                    .table th, .table td { border: 1px solid #D1D5DB; padding: 5px 8px; font-size: 10px; }
                    .table th { background: #FFF3E0; color: #E65100; text-align: left; font-weight: bold; border-bottom: 2px solid #E65100; }
                    .text-right { text-align: right; }
                    .total-row { font-weight: bold; background: #FFF3E0; color: #1F2937; }
                    .net-box { border: 2px solid #E65100; padding: 8px; background: #FFF3E0; font-weight: bold; font-size: 11px; margin-top: 10px; color: #1F2937; }
                    .footer-container { margin-top: 25px; border-top: 1px solid #D1D5DB; padding-top: 6px; }
                    .footer { text-align: center; font-size: 9px; color: #4B5563; }
                    .bottom-secret { font-size: 9px; color: #6B7280; letter-spacing: 0.5px; float: left; }
                </style>
            </head>
            <body>
                $documentHeaderHtml

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
                    $uanEsiRow
                </table>

                <table class="table">
                    <thead>
                        <tr>
                            <th>Earnings</th>
                            <th class="text-right">Amount (Rs.)</th>
                            <th>Deductions</th>
                            <th class="text-right">Amount (Rs.)</th>
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
                    Net Salary :- Rs. ${formatMoney(netAmount)} &#160;&#160;&#160;(Rupees $netWords Only)
                </div>

                <div class="footer-container">
                    <span class="bottom-secret">$secretCode</span>
                    <div class="footer">This is a computer generated statement and does not require any signature or stamp.</div>
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
                val html = SalarySlipGenerator.generateHtml(sal, master).replace("&nbsp;", "&#160;")
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
                        ?: continue

                    val master = TursoDatabaseService.getRecordByEmpId(row.code)
                    val html = SalarySlipGenerator.generateHtml(sal, master).replace("&nbsp;", "&#160;")

                    val safeCode = row.code.replace(Regex("[^A-Za-z0-9_-]"), "_")
                    val formattedMonth = SalarySlipGenerator.formatMonthName(row.month.ifBlank { sal.month })
                    val monthStr = formattedMonth.replace(Regex("[^A-Za-z0-9_-]"), "_")
                    val fileName = "Salary_Slip_${safeCode}_${monthStr}.pdf"

                    val pdfOut = ByteArrayOutputStream()
                    com.openhtmltopdf.pdfboxout.PdfRendererBuilder()
                        .useFastMode()
                        .withHtmlContent(html, null)
                        .toStream(pdfOut)
                        .run()
                    val pdfBytes = pdfOut.toByteArray()

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
                                        subject = "Salary Slip for $formattedMonth - ${sal.employeeName3}",
                                        htmlBody = "<p>Dear ${sal.employeeName3},</p><p>Please find your salary slip for <b>$formattedMonth</b> attached herewith.</p>",
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
                call.respondBytes(zipStream.toByteArray(), ContentType.Application.Pdf)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Generation failed")))
        }
    }
}