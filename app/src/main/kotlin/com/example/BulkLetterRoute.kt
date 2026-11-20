package com.example

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
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
    val address: String? = null,
    val dol: String? = null
)

@Serializable
data class GenerateLetterRequest(
    val rows: List<BulkLetterRow>
)

@Serializable
data class LetterEmailItem(
    val id: Long = 0L,
    val code: String = "",
    val lettercode: String = "",
    val tomail: String = "",
    val ccmail: String = "",
    val subject: String = "",
    val body: String = "",
    val attachmentlink: String = "",
    val wef: String = "",
    val status: String = "PENDING",
    val sentdate: String? = null
)

@Serializable
data class SendEmailsResponse(
    val sent: Int,
    val failed: Int,
    val errors: List<String> = emptyList()
)

@Serializable
data class SkippedLetterError(
    val code: String,
    val letter: String,
    val reason: String
)

@Serializable
data class GenerateLetterErrorResponse(
    val error: String,
    val skipped: List<SkippedLetterError> = emptyList(),
    val exception: String? = null
)

@Serializable
data class ResolveMailRequest(
    val code: String,
    val rule: String // e.g., "ownp, owno, pipe, branch, ADKR1001o, ADKR1002p"
)

@Serializable
data class ResolveMailResponse(
    val employeeCode: String,
    val branch: String,
    val rule: String,
    val emails: List<String>,
    val joinedEmails: String
)

@Serializable
data class LetterTemplateDto(
    val lettercode: String,
    val templete: String,
    val filenameformat: String,
    val subject: String,
    val body: String,
    val tomail: String,
    val ccmail: String,
    val wef: String,
    val recupdate: String
)

@Serializable
data class SaveLetterTemplateRequest(
    val mode: String = "ADD", // "ADD" or "UPDATE"
    val lettercode: String,
    val templete: String,
    val filenameformat: String = "",
    val subject: String = "",
    val body: String = "",
    val tomail: String = "",
    val ccmail: String = "",
    val wef: String = "",
    val recupdate: String = ""
)

@Serializable
data class BrevoAddress(val email: String, val name: String? = null)

@Serializable
data class BrevoAttachment(val name: String, val content: String)

@Serializable
data class BrevoEmailRequest(
    val sender: BrevoAddress,
    val to: List<BrevoAddress>,
    val cc: List<BrevoAddress>? = null,
    val subject: String,
    val htmlContent: String,
    val attachment: List<BrevoAttachment>? = null
)

private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

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
    val filenameformat: String,
    val subject: String,
    val body: String,
    val tomail: String,
    val ccmail: String,
    val wef: String,
    val recupdate: String = ""
)

object BulkLetterService {

    private val istZone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val displayDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    private val placeholderLabels = mapOf(
        "1" to "Name Missing",
        "2" to "Code Missing",
        "3" to "Department Missing",
        "4" to "Designation Missing",
        "5" to "Branch Missing",
        "8" to "DOJ Missing",
        "9" to "DOL/WEF Missing",
        "11" to "Salary Missing",
        "12" to "Salary Words Missing",
        "13" to "Probation Period Missing",
        "14" to "Father's Name Missing",
        "15" to "Address Missing",
        "16" to "Mobile No Missing",
        "19" to "Date Missing",
        "20" to "Responsible Branches Missing",
        "21" to "Interview Date Missing"
    )

    fun formatIndianNumber(amount: Long): String {
        val s = amount.toString()
        if (s.length <= 3) return s
        val lastThree = s.takeLast(3)
        val rest = s.dropLast(3)
        val sb = StringBuilder()
        var i = rest.length
        while (i > 0) {
            val start = maxOf(0, i - 2)
            sb.insert(0, rest.substring(start, i))
            if (start > 0) sb.insert(0, ",")
            i = start
        }
        return if (sb.isNotEmpty()) "$sb,$lastThree" else lastThree
    }

    fun convertToIndianRupeesWords(amount: Long): String {
        val n = amount
        if (n == 0L) return "Rupees Zero Only"

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

        return "Rupees " + result.trim() + " Only"
    }

    fun formatNameWithSalutation(rawName: String, gender: String): String {
        val trimmed = rawName.trim()
        if (trimmed.isBlank()) return ""

        val prefixes = listOf("Mr.", "Mr", "Ms.", "Ms", "Mrs.", "Mrs", "Miss", "Dr.", "Shri", "Smt.")
        val hasSalutation = prefixes.any { prefix ->
            trimmed.equals(prefix, ignoreCase = true) ||
            trimmed.startsWith("$prefix ", ignoreCase = true) ||
            trimmed.startsWith("$prefix.", ignoreCase = true)
        }

        if (hasSalutation) return trimmed

        val g = gender.trim().lowercase()
        return when {
            g == "male" || g == "m" -> "Mr. $trimmed"
            g == "female" || g == "f" -> "Ms. $trimmed"
            else -> trimmed
        }
    }

    fun extractCleanEmail(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val match = Regex("<([^>]+)>").find(trimmed)
        val extracted = match?.groupValues?.getOrNull(1)?.trim() ?: trimmed
        return if (extracted.contains("@")) extracted else ""
    }

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
                val rawName = dto.employeeName.ifBlank { dto.id }
                val formattedName = formatNameWithSalutation(rawName, dto.gender)
                return MasterEmployeeData(
                    name = formattedName,
                    code = dto.empId.ifBlank { empCode },
                    department = dto.department,
                    designation = dto.designation,
                    branch = dto.branchName,
                    gender = dto.gender,
                    doj = DateUtils.toDisplayDate(dto.dojInAdhikar),
                    dol = DateUtils.toDisplayDate(dto.dol),
                    salary = dto.salaryCode.ifBlank { dto.slary },
                    fatherName = dto.fathersName,
                    presentAddress = dto.presentAddress,
                    mobileNo = dto.personalContactNumber,
                    email = extractCleanEmail(dto.email61Personal).ifBlank { extractCleanEmail(dto.mailIdOffice) }
                )
            }
        }
    }

    fun fetchLetterConfigFromDb(conn: Connection, letterCode: String): BulkLetterTemplateConfig? {
        val sql = """
            SELECT * FROM bulkletter 
            WHERE TRIM(lettercode) = TRIM(?) 
            LIMIT 1
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, letterCode)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    val meta = rs.metaData
                    val colMap = (1..meta.columnCount).associate {
                        meta.getColumnName(it).trim().lowercase() to (rs.getString(it)?.trim().orEmpty())
                    }
                    return BulkLetterTemplateConfig(
                        lettercode = colMap["lettercode"].orEmpty(),
                        templete = colMap["templete"].orEmpty(),
                        filenameformat = colMap["filenameformat"].orEmpty(),
                        subject = colMap["subject"].orEmpty(),
                        body = colMap["body"].orEmpty(),
                        tomail = colMap["tomail"].orEmpty(),
                        ccmail = colMap["ccmail"].orEmpty(),
                        wef = DateUtils.toDisplayDate(colMap["wef"]),
                        recupdate = colMap["recupdate"].orEmpty()
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
                        val colName = meta.getColumnName(i).trim().lowercase()
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

        val formattedDoj = DateUtils.toDisplayDate(cleanDoj)

        val formats = listOf(
            displayDateFormatter,
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d-M-yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy")
        )

        for (fmt in formats) {
            try {
                return LocalDate.parse(formattedDoj, fmt)
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
        resolvedWef: String,
        isEmailField: Boolean = false,
        overrideTodayDate: String? = null
    ): String {
        val todayDate = overrideTodayDate?.takeIf { it.isNotBlank() } ?: DateUtils.toDisplayDate(LocalDate.now(istZone).toString())
        val ddmmyy = LocalDate.now(istZone).format(DateTimeFormatter.ofPattern("ddMMyy"))
        val interviewDate = calculateInterviewDate(emp.doj)

        val isOfferOrAppointment = rawTemplate.contains("offer", ignoreCase = true) || rawTemplate.contains("appointment", ignoreCase = true)
        val effectiveLetterDate = if (isOfferOrAppointment && emp.doj.isNotBlank()) emp.doj else todayDate
        val effectiveInterviewDate = if (isOfferOrAppointment && interviewDate.isBlank()) {
            emp.doj
        } else {
            interviewDate.ifBlank { emp.doj }
        }

        val effectiveBranch = row.dBranch?.takeIf { it.isNotBlank() } ?: emp.branch
        val effectiveAddress = row.address?.takeIf { it.isNotBlank() } ?: emp.presentAddress
        val effectiveDesignation = row.promoTo?.takeIf { it.isNotBlank() } ?: emp.designation
        val effectiveDolOrWef = row.dol?.takeIf { it.isNotBlank() }
            ?: row.wef?.takeIf { it.isNotBlank() }
            ?: resolvedWef.takeIf { it.isNotBlank() }
            ?: emp.dol

        val mapper: (String) -> String = if (isEmailField) { { it } } else { { xmlEscape(it) } }

        val nameVal = emp.name.ifBlank { "Name Missing" }
        val codeVal = emp.code.ifBlank { "Code Missing" }
        val deptVal = emp.department.ifBlank { "Department Missing" }
        val desigVal = effectiveDesignation.ifBlank { "Designation Missing" }
        val branchVal = effectiveBranch.ifBlank { "Branch Missing" }
        val dojVal = emp.doj.ifBlank { "DOJ Missing" }
        val dolVal = effectiveDolOrWef.ifBlank { "DOL/WEF Missing" }

        val cleanSalary = emp.salary.trim()
        val amountLong = cleanSalary
            .replace("Rs.", "", ignoreCase = true)
            .replace("rs", "", ignoreCase = true)
            .replace("/-", "")
            .replace(",", "")
            .replace(" ", "")
            .substringBefore(".")
            .toLongOrNull()

        val salaryReplacedTemplate = if (amountLong != null && amountLong > 0) {
            val formattedDigits = formatIndianNumber(amountLong)
            val wordsText = convertToIndianRupeesWords(amountLong)
            if (rawTemplate.contains("{12}")) {
                rawTemplate
                    .replace("{11}", mapper(formattedDigits))
                    .replace("{12}", mapper(wordsText))
            } else {
                val fullSalaryStr = "Rs. $formattedDigits/- ($wordsText)"
                rawTemplate
                    .replace("Rs.{11}/-", mapper(fullSalaryStr))
                    .replace("Rs. {11}/-", mapper(fullSalaryStr))
                    .replace("Rs.{11}", mapper(fullSalaryStr))
                    .replace("Rs. {11}", mapper(fullSalaryStr))
                    .replace("{11}", mapper(fullSalaryStr))
            }
        } else {
            val fallback = emp.salary.ifBlank { "Salary Missing" }
            rawTemplate
                .replace("Rs.{11}/-", mapper(fallback))
                .replace("Rs. {11}/-", mapper(fallback))
                .replace("{11}", mapper(fallback))
                .replace("{12}", "")
        }

        val probVal = probationPeriod.ifBlank { "Probation Period Missing" }
        val fatherVal = emp.fatherName.ifBlank { "Father's Name Missing" }
        val addrVal = effectiveAddress.ifBlank { "Address Missing" }
        val mobVal = emp.mobileNo.ifBlank { "Mobile No Missing" }
        val respBrVal = responsibleBranches.ifBlank { emp.branch.ifBlank { "Branch Missing" } }
        val intDateVal = effectiveInterviewDate.ifBlank { "Interview Date Missing" }

        val processedTemplate = salaryReplacedTemplate.replace(Regex("\\{X\\}", RegexOption.IGNORE_CASE), "<br/><br/>")

        val replaced = processedTemplate
            .replace("{1}", mapper(nameVal))
            .replace("{2}", mapper(codeVal))
            .replace("{3}", mapper(deptVal))
            .replace("{4}", mapper(desigVal))
            .replace("{5}", mapper(branchVal))
            .replace("{8}", mapper(dojVal))
            .replace("{9}", mapper(dolVal))
            .replace("{13}", mapper(probVal))
            .replace("{14}", mapper(fatherVal))
            .replace("{15}", mapper(addrVal))
            .replace("{16}", mapper(mobVal))
            .replace("{17}", mapper(emp.heShe))
            .replace("{18}", mapper(emp.hisHer))
            .replace("{17_lower}", mapper(emp.heShe.lowercase()))
            .replace("{18_lower}", mapper(emp.hisHer.lowercase()))
            .replace("{19}", mapper(effectiveLetterDate))
            .replace("{20}", mapper(respBrVal))
            .replace("{21}", mapper(intDateVal))
            .replace("{emp_mail}", mapper(emp.email))
            .replace("{ddmmyy}", mapper(ddmmyy))
            .replace("{wef}", mapper(dolVal))

        return highlightUnreplacedPlaceholders(replaced, mapper)
    }

    private fun highlightUnreplacedPlaceholders(template: String, mapper: (String) -> String): String {
        return template.replace(Regex("\\{(\\d+)\\}")) { matchResult ->
            val num = matchResult.groupValues[1]
            val label = placeholderLabels[num] ?: "Field {$num} Missing"
            mapper(label)
        }
    }

    fun resolveEmailField(conn: Connection, rawToken: String, emp: MasterEmployeeData): String {
        val tokens = rawToken.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val resolved = linkedSetOf<String>()

        fun addCleanEmail(raw: String) {
            val clean = extractCleanEmail(raw)
            if (clean.isNotBlank()) resolved.add(clean)
        }

        fun fetchMasterEmails(targetCode: String): Pair<String?, String?> {
            val sql = "SELECT * FROM MasterData WHERE UPPER(TRIM(Code_2)) = UPPER(TRIM(?)) LIMIT 1"
            try {
                conn.prepareStatement(sql).use { ps ->
                    ps.setString(1, targetCode)
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) return Pair(null, null)
                        val meta = rs.metaData
                        val colMap = (1..meta.columnCount).associate {
                            meta.getColumnName(it).trim().lowercase() to (rs.getString(it)?.trim().orEmpty())
                        }
                        val office = extractCleanEmail(
                            colMap["mail_id_office_58"].orEmpty().ifBlank { colMap["mail_id_office"].orEmpty() }
                        )
                        val personal = extractCleanEmail(
                            colMap["email61_personal_52"].orEmpty()
                                .ifBlank { colMap["email61_personal"].orEmpty() }
                                .ifBlank { colMap["personal_email"].orEmpty() }
                        )

                        return Pair(office.takeIf { it.contains("@") }, personal.takeIf { it.contains("@") })
                    }
                }
            } catch (_: Exception) {
                return Pair(null, null)
            }
        }

        fun fetchBranchEmails(branchName: String, pipeAll: Boolean): List<String> {
            if (branchName.isBlank()) return emptyList()
            val found = mutableListOf<String>()
            try {
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT * FROM BranchDetails").use { rs ->
                        val meta = rs.metaData
                        val colCount = meta.columnCount
                        while (rs.next()) {
                            var bName = ""
                            var mailBr = ""
                            val pipeList = mutableListOf<String>()

                            for (i in 1..colCount) {
                                val col = meta.getColumnName(i).trim().lowercase()
                                val valStr = rs.getString(i)?.trim().orEmpty()
                                if (bName.isBlank() && col in setOf("branch", "branch_name", "branchname", "name")) {
                                    bName = valStr
                                }
                                val cleanMail = extractCleanEmail(valStr)
                                if (col == "mailbr" && cleanMail.isNotBlank()) {
                                    mailBr = cleanMail
                                }
                                if (cleanMail.isNotBlank() && col in setOf("mailbr", "mail1am", "mail2cm", "mail3bh", "1am", "2cm", "3bh")) {
                                    pipeList.add(cleanMail)
                                }
                            }

                            if (bName.equals(branchName.trim(), ignoreCase = true)) {
                                if (pipeAll) {
                                    found.addAll(pipeList)
                                } else if (mailBr.isNotBlank()) {
                                    found.add(mailBr)
                                }
                                break
                            }
                        }
                    }
                }
            } catch (_: Exception) { }
            return found
        }

        for (token in tokens) {
            when {
                token.equals("ownp", ignoreCase = true) -> {
                    val (_, personal) = fetchMasterEmails(emp.code)
                    personal?.let { addCleanEmail(it) }
                }
                token.equals("owno", ignoreCase = true) -> {
                    val (office, _) = fetchMasterEmails(emp.code)
                    office?.let { addCleanEmail(it) }
                }
                token.equals("own", ignoreCase = true) -> {
                    val (office, personal) = fetchMasterEmails(emp.code)
                    office?.let { addCleanEmail(it) }
                    personal?.let { addCleanEmail(it) }
                    if (emp.email.contains("@")) addCleanEmail(emp.email)
                }
                token.equals("pipe", ignoreCase = true) -> {
                    fetchBranchEmails(emp.branch, pipeAll = true).forEach { addCleanEmail(it) }
                }
                token.equals("branch", ignoreCase = true) -> {
                    fetchBranchEmails(emp.branch, pipeAll = false).forEach { addCleanEmail(it) }
                }
                token.contains("@") -> {
                    addCleanEmail(token)
                }
                token.length > 4 && token.endsWith("o", ignoreCase = true) && token.dropLast(1).any { it.isDigit() } -> {
                    val staffCode = token.dropLast(1)
                    val (office, _) = fetchMasterEmails(staffCode)
                    office?.let { addCleanEmail(it) }
                }
                token.length > 4 && token.endsWith("p", ignoreCase = true) && token.dropLast(1).any { it.isDigit() } -> {
                    val staffCode = token.dropLast(1)
                    val (_, personal) = fetchMasterEmails(staffCode)
                    personal?.let { addCleanEmail(it) }
                }
                else -> {
                    val (office, personal) = fetchMasterEmails(token)
                    office?.let { addCleanEmail(it) } ?: personal?.let { addCleanEmail(it) }
                }
            }
        }

        return resolved.filter { it.isNotBlank() && it.contains("@") }.distinct().joinToString(",")
    }

    fun resolveMailForApi(conn: Connection, rawRule: String, empCode: String): ResolveMailResponse {
        val emp = fetchEmployeeFromMasterData(conn, empCode)
            ?: throw IllegalArgumentException("Employee not found for code: $empCode")
        val joined = resolveEmailField(conn, rawRule, emp)
        val list = if (joined.isBlank()) emptyList() else joined.split(",")
        return ResolveMailResponse(
            employeeCode = emp.code,
            branch = emp.branch,
            rule = rawRule,
            emails = list,
            joinedEmails = joined
        )
    }

    fun appendStaffHistoryComment(
        conn: Connection,
        empCode: String,
        resolvedRecUpdate: String,
        todayDate: String
    ) {
        val cleanNote = resolvedRecUpdate.trim()
        if (cleanNote.isBlank()) return

        val displayDate = DateUtils.toDisplayDate(todayDate).ifBlank { todayDate }
        val formattedBlock = if (cleanNote.startsWith("{") && cleanNote.endsWith("}")) {
            cleanNote
        } else {
            "{$displayDate: $cleanNote}"
        }

        try {
            val selectSql = "SELECT coment77_59 FROM MasterData WHERE UPPER(TRIM(Code_2)) = UPPER(TRIM(?)) LIMIT 1"
            var existingComment = ""
            conn.prepareStatement(selectSql).use { ps ->
                ps.setString(1, empCode)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        existingComment = rs.getString("coment77_59")?.trim().orEmpty()
                    }
                }
            }

            val updatedComment = if (existingComment.isBlank()) {
                formattedBlock
            } else {
                "$existingComment $formattedBlock"
            }

            val updateSql = "UPDATE MasterData SET coment77_59 = ? WHERE UPPER(TRIM(Code_2)) = UPPER(TRIM(?))"
            conn.prepareStatement(updateSql).use { ps ->
                ps.setString(1, updatedComment)
                ps.setString(2, empCode)
                ps.execute()
            }
        } catch (e: Exception) {
            println("Warning: Failed to update coment77_59 for $empCode: {e.message}")
        }
    }

    fun insertLetterEmailRecord(
        conn: Connection,
        code: String,
        letterCode: String,
        toMail: String,
        ccMail: String,
        subject: String,
        body: String,
        attachmentLink: String,
        wef: String
    ) {
        try {
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS letteremail (
                        code TEXT, 
                        lettercode TEXT, 
                        tomail TEXT, 
                        ccmail TEXT, 
                        subject TEXT, 
                        body TEXT, 
                        attachmentlink TEXT, 
                        wef TEXT, 
                        status TEXT DEFAULT 'PENDING', 
                        sentdate TEXT
                    );
                """.trimIndent())
            }
        } catch (_: Exception) {}

        val excelWef = DateUtils.toExcelSerial(wef)
        val sql = """
            INSERT INTO letteremail (code, lettercode, tomail, ccmail, subject, body, attachmentlink, wef, status, sentdate)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', NULL)
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, code)
            stmt.setString(2, letterCode)
            stmt.setString(3, toMail)
            stmt.setString(4, ccMail)
            stmt.setString(5, subject)
            stmt.setString(6, body)
            stmt.setString(7, attachmentLink)
            stmt.setString(8, excelWef)
            stmt.execute()
        }
    }

    fun sendEmailWithJakartaMail(
        to: String,
        cc: String,
        subject: String,
        htmlBody: String,
        pdfBytes: ByteArray?,
        pdfName: String
    ) {
        val brevoApiKey = System.getenv("BREVO_API_KEY")?.trim().takeIf { !it.isNullOrBlank() }
            ?: System.getenv("SMTP_PASS")?.trim().takeIf { !it.isNullOrBlank() && it.startsWith("xkeysib-", ignoreCase = true) }
            ?: System.getenv("SMTP_PASSWORD")?.trim().takeIf { !it.isNullOrBlank() && it.startsWith("xkeysib-", ignoreCase = true) }
            ?: throw IllegalStateException("Missing BREVO_API_KEY (xkeysib-...) in Render environment variables")

        val senderEmail = System.getenv("SMTP_FROM")?.trim().takeIf { !it.isNullOrBlank() }
            ?: System.getenv("SMTP_USER")?.trim().takeIf { !it.isNullOrBlank() }
            ?: System.getenv("SMTP_EMAIL")?.trim().takeIf { !it.isNullOrBlank() }
            ?: "hr@adhikarindia.org"

        fun parseAddresses(raw: String): List<BrevoAddress> {
            if (raw.isBlank()) return emptyList()
            return raw.split(Regex("[,;]"))
                .map { it.trim() }
                .filter { it.contains("@") }
                .map { BrevoAddress(email = it) }
        }

        val toList = parseAddresses(to)
        if (toList.isEmpty()) {
            throw IllegalArgumentException("No valid 'to' recipient email found")
        }
        val ccList = parseAddresses(cc).ifEmpty { null }

        val attachmentList = if (pdfBytes != null && pdfBytes.isNotEmpty()) {
            val base64Pdf = java.util.Base64.getEncoder().encodeToString(pdfBytes)
            listOf(BrevoAttachment(name = pdfName.ifBlank { "letter.pdf" }, content = base64Pdf))
        } else {
            null
        }

        val emailRequest = BrevoEmailRequest(
            sender = BrevoAddress(email = senderEmail, name = "Adhikar HR"),
            to = toList,
            cc = ccList,
            subject = subject,
            htmlContent = htmlBody,
            attachment = attachmentList
        )

        val jsonPayload = Json.encodeToString(emailRequest)
        val endpoint = "https://api.brevo.com/v3/smtp/email"

        val client = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .build()

        val request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(endpoint))
            .header("api-key", brevoApiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonPayload, Charsets.UTF_8))
            .build()

        val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        println("Brevo response: ${response.statusCode()} - ${response.body()}")

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("Brevo API error ${response.statusCode()}: ${response.body()}")
        }
    }
}

fun Route.bulkLetterRoutes(dbConnectionProvider: () -> Connection) {

    // GET /api/turso/generateletter/preview
    get("/api/turso/generateletter/preview") {
        val code = call.request.queryParameters["code"] ?: ""
        val letter = call.request.queryParameters["letter"] ?: "1"
        val promoTo = call.request.queryParameters["promoTo"]
        val dBranch = call.request.queryParameters["dBranch"]
        val wef = call.request.queryParameters["wef"]
        val address = call.request.queryParameters["address"]
        val dol = call.request.queryParameters["dol"]

        if (code.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing code parameter"))
            return@get
        }

        try {
            dbConnectionProvider().use { conn ->
                val empData = BulkLetterService.fetchEmployeeFromMasterData(conn, code)
                    ?: MasterEmployeeData(
                        name = BulkLetterService.formatNameWithSalutation(code, "Male"),
                        code = code,
                        department = "",
                        designation = promoTo ?: "",
                        branch = dBranch ?: "",
                        gender = "Male",
                        doj = "",
                        dol = dol ?: wef ?: "",
                        salary = "",
                        fatherName = "",
                        presentAddress = address ?: "",
                        mobileNo = "",
                        email = ""
                    )

                val letterConfig = BulkLetterService.fetchLetterConfigFromDb(conn, letter)
                    ?: error("Letter template not found for lettercode: $letter")

                val row = BulkLetterRow(code = code, letter = letter, promoTo = promoTo, dBranch = dBranch, wef = wef, address = address, dol = dol)
                val resolvedWef = wef?.takeIf { it.isNotBlank() } ?: letterConfig.wef.takeIf { it.isNotBlank() } ?: LocalDate.now(ZoneId.of("Asia/Kolkata")).format(DateTimeFormatter.ofPattern("dd-MM-yyyy"))
                val targetDesignation = row.promoTo?.takeIf { it.isNotBlank() } ?: empData.designation
                val probationPeriod = BulkLetterService.fetchProbationDays(conn, targetDesignation)
                val responsibleBranches = BulkLetterService.fetchResponsibleBranches(conn, code)

                val isOfferOrAppointment = letterConfig.lettercode.contains("offer", ignoreCase = true) ||
                    letterConfig.lettercode.contains("appointment", ignoreCase = true) ||
                    letterConfig.filenameformat.contains("offer", ignoreCase = true) ||
                    letterConfig.filenameformat.contains("appointment", ignoreCase = true) ||
                    letterConfig.subject.contains("offer", ignoreCase = true) ||
                    letterConfig.subject.contains("appointment", ignoreCase = true) ||
                    letterConfig.templete.contains("offer", ignoreCase = true) ||
                    letterConfig.templete.contains("appointment", ignoreCase = true)

                val todayDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).format(DateTimeFormatter.ofPattern("dd-MM-yyyy"))
                val effectiveLetterDate = if (isOfferOrAppointment && empData.doj.isNotBlank()) empData.doj else todayDate

                val finalHtmlBody = BulkLetterService.applyPlaceholders(
                    rawTemplate = letterConfig.templete,
                    emp = empData,
                    row = row,
                    responsibleBranches = responsibleBranches,
                    probationPeriod = probationPeriod,
                    resolvedWef = resolvedWef,
                    isEmailField = false,
                    overrideTodayDate = effectiveLetterDate
                )

                val secretCode = try {
                    LetterCodeGenerator.generate(code, effectiveLetterDate).combinedCode
                } catch (_: Exception) { "" }

                val previewHtml = """
                    <!DOCTYPE html>
                    <html>
                    <head><meta charset="UTF-8"/><title>Letter Preview</title></head>
                    <body style="font-family: Arial, sans-serif; padding: 20px;">
                        ${BrandAssets.renderDocumentHeader("Preview - Letter Code: $letter", secretCode)}
                        <div style="margin-top: 20px; line-height: 1.6;">
                            $finalHtmlBody
                        </div>
                    </body>
                    </html>
                """.trimIndent()

                call.respondText(previewHtml, ContentType.Text.Html, HttpStatusCode.OK)
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Preview failed")))
        }
    }

    // GET /api/turso/letter-templates
    get("/api/turso/letter-templates") {
        try {
            val list = mutableListOf<LetterTemplateDto>()
            dbConnectionProvider().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT * FROM bulkletter ORDER BY CAST(lettercode AS INTEGER) ASC").use { rs ->
                        val meta = rs.metaData
                        val cols = (1..meta.columnCount).map { meta.getColumnName(it).trim().lowercase() }
                        while (rs.next()) {
                            fun col(name: String) = if (cols.contains(name)) rs.getString(cols.indexOf(name) + 1)?.trim().orEmpty() else ""
                            list.add(
                                LetterTemplateDto(
                                    lettercode = col("lettercode"),
                                    templete = col("templete"),
                                    filenameformat = col("filenameformat"),
                                    subject = col("subject"),
                                    body = col("body"),
                                    tomail = col("tomail"),
                                    ccmail = col("ccmail"),
                                    wef = DateUtils.toDisplayDate(col("wef")),
                                    recupdate = col("recupdate")
                                )
                            )
                        }
                    }
                }
            }
            call.respond(HttpStatusCode.OK, list)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Failed to fetch templates")))
        }
    }

    // POST /api/turso/letter-templates/save
    post("/api/turso/letter-templates/save") {
        val log = call.application.environment.log
        try {
            val rawBody = call.receiveText()
            val req = lenientJson.decodeFromString<SaveLetterTemplateRequest>(rawBody)
            val cleanCode = req.lettercode.trim()
            if (cleanCode.isBlank()) {
                call.respondText(
                    """{"error":"Lettercode cannot be empty"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return@post
            }

            val cleanHtml = req.templete.replace(Regex("\\s*\\n\\s*"), "").trim()
            val excelWef = DateUtils.toExcelSerial(req.wef)

            dbConnectionProvider().use { conn ->
                // 1. Discover exact column names via ResultSetMetaData
                val actualCols = mutableMapOf<String, String>()
                try {
                    conn.createStatement().use { st ->
                        st.executeQuery("SELECT * FROM bulkletter LIMIT 1").use { rs ->
                            val meta = rs.metaData
                            for (i in 1..meta.columnCount) {
                                val exactName = meta.getColumnName(i) ?: continue
                                actualCols[exactName.trim().lowercase()] = exactName
                            }
                        }
                    }
                } catch (_: Exception) {
                    conn.createStatement().use { st ->
                        st.execute(
                            """
                            CREATE TABLE IF NOT EXISTS bulkletter (
                                lettercode TEXT,
                                templete TEXT,
                                filenameformat TEXT,
                                subject TEXT,
                                body TEXT,
                                tomail TEXT,
                                ccmail TEXT,
                                wef TEXT,
                                recupdate TEXT
                            )
                            """.trimIndent()
                        )
                    }
                    listOf("lettercode", "templete", "filenameformat", "subject", "body", "tomail", "ccmail", "wef", "recupdate")
                        .forEach { actualCols[it] = it }
                }

                fun qCol(key: String): String? = actualCols[key]?.let { "\"$it\"" }

                val codeCol = qCol("lettercode") ?: "\"lettercode\""
                val checkSql = "SELECT COUNT(*) FROM bulkletter WHERE TRIM($codeCol) = TRIM(?)"
                val exists = conn.prepareStatement(checkSql).use { ps ->
                    ps.setString(1, cleanCode)
                    ps.executeQuery().use { rs -> rs.next() && rs.getInt(1) > 0 }
                }

                val fieldValues = linkedMapOf<String, String>()
                qCol("templete")?.let { fieldValues[it] = cleanHtml }
                qCol("filenameformat")?.let { fieldValues[it] = req.filenameformat }
                qCol("subject")?.let { fieldValues[it] = req.subject }
                qCol("body")?.let { fieldValues[it] = req.body }
                qCol("tomail")?.let { fieldValues[it] = req.tomail }
                qCol("ccmail")?.let { fieldValues[it] = req.ccmail }
                qCol("wef")?.let { fieldValues[it] = excelWef }
                qCol("recupdate")?.let { fieldValues[it] = req.recupdate }

                if (req.mode.equals("ADD", ignoreCase = true)) {
                    if (exists) {
                        call.respondText(
                            """{"error":"Duplicate Error: Lettercode '$cleanCode' already exists!"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Conflict
                        )
                        return@post
                    }
                    val allCols = listOf(codeCol) + fieldValues.keys
                    val placeholders = allCols.joinToString(", ") { "?" }
                    val insertSql = "INSERT INTO bulkletter (${allCols.joinToString(", ")}) VALUES ($placeholders)"

                    conn.prepareStatement(insertSql).use { ps ->
                        ps.setString(1, cleanCode)
                        fieldValues.values.forEachIndexed { idx, value ->
                            ps.setString(idx + 2, value)
                        }
                        ps.execute()
                    }
                } else {
                    if (!exists) {
                        call.respondText(
                            """{"error":"Lettercode '$cleanCode' not found! Use Add mode to create."}""",
                            ContentType.Application.Json,
                            HttpStatusCode.NotFound
                        )
                        return@post
                    }
                    val setClause = fieldValues.keys.joinToString(", ") { "$it = ?" }
                    val updateSql = "UPDATE bulkletter SET $setClause WHERE TRIM($codeCol) = TRIM(?)"

                    conn.prepareStatement(updateSql).use { ps ->
                        fieldValues.values.forEachIndexed { idx, value ->
                            ps.setString(idx + 1, value)
                        }
                        ps.setString(fieldValues.size + 1, cleanCode)
                        ps.execute()
                    }
                }
            }

            val okJson = lenientJson.encodeToString(mapOf("status" to "success", "lettercode" to cleanCode))
            call.respondText(okJson, ContentType.Application.Json, HttpStatusCode.OK)
        } catch (e: Exception) {
            log.error("FAILED POST /api/turso/letter-templates/save", e)
            val errJson = lenientJson.encodeToString(
                mapOf("error" to "${e.javaClass.simpleName}: ${e.localizedMessage ?: e.message ?: "Save failed"}")
            )
            call.respondText(errJson, ContentType.Application.Json, HttpStatusCode.InternalServerError)
        }
    }

    // GET /api/turso/letteremails
    get("/api/turso/letteremails") {
        val conn = dbConnectionProvider()
        try {
            val list = mutableListOf<LetterEmailItem>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT rowid AS id, code, lettercode, tomail, ccmail, subject, body, attachmentlink, wef, status, sentdate FROM letteremail ORDER BY rowid DESC;")
                while (rs.next()) {
                    list.add(
                        LetterEmailItem(
                            id = rs.getLong("id"),
                            code = rs.getString("code") ?: "",
                            lettercode = rs.getString("lettercode") ?: "",
                            tomail = rs.getString("tomail") ?: "",
                            ccmail = rs.getString("ccmail") ?: "",
                            subject = rs.getString("subject") ?: "",
                            body = rs.getString("body") ?: "",
                            attachmentlink = rs.getString("attachmentlink") ?: "",
                            wef = DateUtils.toDisplayDate(rs.getString("wef")),
                            status = rs.getString("status") ?: "PENDING",
                            sentdate = DateUtils.toDisplayDate(rs.getString("sentdate"))
                        )
                    )
                }
                rs.close()
            }
            conn.close()
            call.respond(HttpStatusCode.OK, list)
        } catch (e: Exception) {
            try { conn.close() } catch (_: Exception) {}
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Failed to fetch letteremails")))
        }
    }

    // POST /api/turso/sendletteremails
    post("/api/turso/sendletteremails") {
        val errorList = mutableListOf<String>()
        try {
            val text = call.receiveText()
            val items = try {
                lenientJson.decodeFromString<List<LetterEmailItem>>(text)
            } catch (_: Exception) {
                try {
                    val single = lenientJson.decodeFromString<LetterEmailItem>(text)
                    listOf(single)
                } catch (e: Exception) {
                    emptyList()
                }
            }

            var sentCount = 0
            var failedCount = 0

            dbConnectionProvider().use { conn ->
                for (item in items) {
                    try {
                        var pdfBytes: ByteArray? = null
                        var pdfName = "${item.code}_Letter_${item.lettercode}.pdf"

                        if (item.attachmentlink.isNotBlank()) {
                            val fileId = if (item.attachmentlink.contains("/file/d/")) {
                                item.attachmentlink.substringAfter("/file/d/").substringBefore("/")
                            } else {
                                item.attachmentlink.trim()
                            }
                            if (fileId.isNotBlank()) {
                                try {
                                    val (name, _, bytes) = GoogleDriveReportService.downloadStaffFile(fileId)
                                    pdfBytes = bytes
                                    if (name.isNotBlank()) pdfName = name
                                } catch (driveEx: Exception) {
                                    errorList.add("Drive download failed for ${item.code}: ${driveEx.message}")
                                }
                            }
                        }

                        BulkLetterService.sendEmailWithJakartaMail(
                            to = item.tomail,
                            cc = item.ccmail,
                            subject = item.subject,
                            htmlBody = item.body,
                            pdfBytes = pdfBytes,
                            pdfName = pdfName
                        )

                        val istZone = ZoneId.of("Asia/Kolkata")
                        val sentTimestamp = java.time.LocalDateTime.now(istZone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

                        val updateSql = "UPDATE letteremail SET status = 'SENT', sentdate = ? WHERE rowid = ?"
                        conn.prepareStatement(updateSql).use { ps ->
                            ps.setString(1, sentTimestamp)
                            ps.setLong(2, item.id)
                            ps.execute()
                        }
                        sentCount++
                    } catch (e: Exception) {
                        e.printStackTrace()
                        failedCount++
                        errorList.add("Email send failed for ${item.code}: ${e.localizedMessage ?: e.message}")
                    }
                }
            }

            val statusCode = if (sentCount == 0 && failedCount > 0) HttpStatusCode.InternalServerError else HttpStatusCode.OK
            call.respond(statusCode, SendEmailsResponse(sent = sentCount, failed = failedCount, errors = errorList))
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                SendEmailsResponse(sent = 0, failed = 1, errors = listOf(e.localizedMessage ?: e.message ?: "Unknown error"))
            )
        }
    }

    // POST /api/turso/generateletter
    post("/api/turso/generateletter") {
        val skippedErrors = mutableListOf<SkippedLetterError>()
        val generatedPdfs = mutableListOf<Pair<String, ByteArray>>()

        try {
            val text = call.receiveText()
            val rows = try {
                lenientJson.decodeFromString<List<BulkLetterRow>>(text)
            } catch (_: Exception) {
                try {
                    lenientJson.decodeFromString<GenerateLetterRequest>(text).rows
                } catch (e: Exception) {
                    emptyList()
                }
            }

            if (rows.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No rows provided"))
                return@post
            }

            // Expand composite letter code "27" -> "25" and "26"
            val expandedRows = mutableListOf<BulkLetterRow>()
            for (row in rows) {
                if (row.letter.trim() == "27") {
                    expandedRows.add(row.copy(letter = "25"))
                    expandedRows.add(row.copy(letter = "26"))
                } else {
                    expandedRows.add(row)
                }
            }

            val todayDate = LocalDate.now(ZoneId.of("Asia/Kolkata"))
                .format(DateTimeFormatter.ofPattern("dd-MM-yyyy"))

            dbConnectionProvider().use { conn ->
                for ((index, row) in expandedRows.withIndex()) {
                    try {
                        val empData = BulkLetterService.fetchEmployeeFromMasterData(conn, row.code)
                            ?: MasterEmployeeData(
                                name = BulkLetterService.formatNameWithSalutation(row.code, "Male"),
                                code = row.code,
                                department = "",
                                designation = row.promoTo ?: "",
                                branch = row.dBranch ?: "",
                                gender = "Male",
                                doj = "",
                                dol = row.dol ?: row.wef ?: "",
                                salary = "",
                                fatherName = "",
                                presentAddress = row.address ?: "",
                                mobileNo = "",
                                email = ""
                            )

                        val letterConfig = BulkLetterService.fetchLetterConfigFromDb(conn, row.letter)
                            ?: error("Letter template not found for lettercode: ${row.letter}")

                        val resolvedWef = row.wef?.takeIf { it.isNotBlank() }
                            ?: letterConfig.wef.takeIf { it.isNotBlank() }
                            ?: todayDate

                        val requiresDol = row.letter.trim() == "21" ||
                            letterConfig.templete.contains("{9}") ||
                            letterConfig.subject.contains("{9}") ||
                            letterConfig.body.contains("{9}")
                        if (requiresDol && empData.dol.isBlank()) {
                            skippedErrors.add(SkippedLetterError(code = row.code, letter = row.letter, reason = "Date of Leaving missing"))
                            continue
                        }

                        val targetDesignation = row.promoTo?.takeIf { it.isNotBlank() } ?: empData.designation
                        val probationPeriod = BulkLetterService.fetchProbationDays(conn, targetDesignation)
                        val responsibleBranches = BulkLetterService.fetchResponsibleBranches(conn, row.code)

                        val isOfferOrAppointment = letterConfig.lettercode.contains("offer", ignoreCase = true) ||
                            letterConfig.lettercode.contains("appointment", ignoreCase = true) ||
                            letterConfig.filenameformat.contains("offer", ignoreCase = true) ||
                            letterConfig.filenameformat.contains("appointment", ignoreCase = true) ||
                            letterConfig.subject.contains("offer", ignoreCase = true) ||
                            letterConfig.subject.contains("appointment", ignoreCase = true) ||
                            letterConfig.templete.contains("offer", ignoreCase = true) ||
                            letterConfig.templete.contains("appointment", ignoreCase = true)

                        val effectiveLetterDate = if (isOfferOrAppointment && empData.doj.isNotBlank()) empData.doj else todayDate

                        val finalHtmlBody = BulkLetterService.applyPlaceholders(
                            rawTemplate = letterConfig.templete,
                            emp = empData,
                            row = row,
                            responsibleBranches = responsibleBranches,
                            probationPeriod = probationPeriod,
                            resolvedWef = resolvedWef,
                            isEmailField = false,
                            overrideTodayDate = effectiveLetterDate
                        )

                        val secretCode = try {
                            LetterCodeGenerator.generate(empData.code, effectiveLetterDate).combinedCode
                        } catch (_: Exception) { "" }

                        val pdfBytes = LetterPdfService.generateLetterPdf(
                            formattedHtmlBody = finalHtmlBody,
                            refNo = "ADH/HR/${empData.code}/${index + 1}",
                            date = effectiveLetterDate,
                            secretCode = secretCode
                        )

                        val safeCode = row.code.ifBlank { "Emp_${index + 1}" }
                            .replace(Regex("[^A-Za-z0-9_-]"), "_")

                        val fileNameRaw = letterConfig.filenameformat.takeIf { it.isNotBlank() } ?: "${safeCode}_Letter_${row.letter}"
                        val resolvedFileName = BulkLetterService.applyPlaceholders(
                            fileNameRaw, empData, row, responsibleBranches, probationPeriod, resolvedWef, isEmailField = true, overrideTodayDate = effectiveLetterDate
                        ).let { if (it.endsWith(".pdf", ignoreCase = true)) it else "$it.pdf" }

                        var attachmentLink = ""
                        try {
                            val driveResult = GoogleDriveReportService.uploadStaffFile(
                                empCode = row.code,
                                fileName = resolvedFileName,
                                mimeType = "application/pdf",
                                bytes = pdfBytes
                            )
                            val driveFileId = driveResult["id"].orEmpty()
                            if (driveFileId.isNotBlank()) {
                                attachmentLink = "https://drive.google.com/file/d/$driveFileId/view"
                            }
                        } catch (_: Exception) {}

                        val resolvedSubject = BulkLetterService.applyPlaceholders(
                            letterConfig.subject, empData, row, responsibleBranches, probationPeriod, resolvedWef, isEmailField = true, overrideTodayDate = effectiveLetterDate
                        )
                        val resolvedBody = BulkLetterService.applyPlaceholders(
                            letterConfig.body, empData, row, responsibleBranches, probationPeriod, resolvedWef, isEmailField = true, overrideTodayDate = effectiveLetterDate
                        )
                        val resolvedToMail = BulkLetterService.resolveEmailField(conn, letterConfig.tomail, empData)
                        val resolvedCcMail = BulkLetterService.resolveEmailField(conn, letterConfig.ccmail, empData)

                        BulkLetterService.insertLetterEmailRecord(
                            conn = conn,
                            code = row.code,
                            letterCode = letterConfig.lettercode.ifBlank { row.letter },
                            toMail = resolvedToMail,
                            ccMail = resolvedCcMail,
                            subject = resolvedSubject,
                            body = resolvedBody,
                            attachmentLink = attachmentLink,
                            wef = resolvedWef
                        )

                        if (letterConfig.recupdate.isNotBlank()) {
                            val resolvedRecUpdate = BulkLetterService.applyPlaceholders(
                                letterConfig.recupdate, empData, row, responsibleBranches, probationPeriod, resolvedWef, isEmailField = true, overrideTodayDate = effectiveLetterDate
                            )
                            BulkLetterService.appendStaffHistoryComment(
                                conn = conn,
                                empCode = row.code,
                                resolvedRecUpdate = resolvedRecUpdate,
                                todayDate = effectiveLetterDate
                            )
                        }

                        generatedPdfs.add(resolvedFileName to pdfBytes)
                    } catch (rowEx:Exception) {
                        skippedErrors.add(SkippedLetterError(code = row.code, letter = row.letter, reason = rowEx.message ?: "Processing error"))
                    }
                }
            }

            if (generatedPdfs.isEmpty()) {
                call.respond(HttpStatusCode.NotFound, GenerateLetterErrorResponse(error = "No valid letters generated", skipped = skippedErrors))
                return@post
            }

            if (skippedErrors.isNotEmpty()) {
                call.response.header("X-Skipped-Rows", lenientJson.encodeToString(skippedErrors))
            }

            if (generatedPdfs.size == 1) {
                val (fileName, bytes) = generatedPdfs.first()
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(
                        ContentDisposition.Parameters.FileName,
                        fileName
                    ).toString()
                )
                call.respondBytes(bytes, ContentType.Application.Pdf)
            } else {
                val zipOut = ByteArrayOutputStream()
                ZipOutputStream(zipOut).use { zip ->
                    for ((fileName, bytes) in generatedPdfs) {
                        zip.putNextEntry(ZipEntry(fileName))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(
                        ContentDisposition.Parameters.FileName,
                        "Adhikar_Bulk_Letters.zip"
                    ).toString()
                )
                call.respondBytes(zipOut.toByteArray(), ContentType.Application.Zip)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                GenerateLetterErrorResponse(
                    error = e.localizedMessage ?: e.message ?: "Unknown error",
                    skipped = skippedErrors,
                    exception = e.javaClass.simpleName
                )
            )
        }
    }
}

private fun effectiveRowPromoTo(row: BulkLetterRow, empData: MasterEmployeeData): String {
    return row.promoTo?.takeIf { it.isNotBlank() } ?: empData.designation
}