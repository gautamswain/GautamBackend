package com.example

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.random.Random

// ============================================================================
// 1. DATA MODELS (SERIALIZABLE)
// ============================================================================

@Serializable
data class LetterCodeRequest(
    val code: String,
    val date: String? = null // Supports "yyyy-MM-dd", "dd-MM-yyyy", "dd/MM/yyyy", or 5-digit Excel serial; defaults to today
)

@Serializable
data class LetterCodeResponse(
    val employeeCode: String,
    val dateSerial: Int,
    val issueDate: String,
    val firstCode: String,
    val secondCode: String,
    val combinedCode: String
)

@Serializable
data class VerifyLetterCodeRequest(
    val combinedCode: String
)

@Serializable
data class DecodedHalf(
    val raw13Code: String,
    val marker: Char,
    val last4Digits: String,
    val dateSerial: Int,
    val issueDate: String
)

@Serializable
data class EmployeeMatchInfo(
    val employeeCode: String,
    val employeeName: String,
    val designation: String? = null,
    val branch: String? = null,
    val letterSubject: String? = null,
    val letterStatus: String? = null
)

@Serializable
data class VerifyLetterCodeResponse(
    val isAuthentic: Boolean,
    val reason: String,
    val last4Digits: String? = null,
    val issueDate: String? = null,
    val dateSerial: Int? = null,
    val firstHalf: DecodedHalf? = null,
    val secondHalf: DecodedHalf? = null,
    val matchedEmployees: List<EmployeeMatchInfo> = emptyList()
)

// ============================================================================
// 2. SHARED CIPHER & PERMUTATION CONSTANTS
// ============================================================================

private object LetterCipherSpec {
    // Exact CHair lookup table (Index 1..26 -> VBA row 17..42 in Col 136)
    val cipherChars = listOf(
        'f', 'h', 'c', 'k', 'a', 'o', 'u', 'q', 'z', 'w',
        'n', 'd', 'v', 'y', 't', 'm', 'i', 'e', 'b', 'g',
        'j', 'l', 'r', 's', 'p', 'x'
    )

    // Exact 24x4 Permutation Matrix (T15 rows 2..25, cols 131..134)
    val permutations = listOf(
        intArrayOf(1, 2, 3, 4), intArrayOf(1, 2, 4, 3), intArrayOf(1, 3, 2, 4),
        intArrayOf(1, 3, 4, 2), intArrayOf(1, 4, 2, 3), intArrayOf(1, 4, 3, 2),
        intArrayOf(2, 1, 3, 4), intArrayOf(2, 1, 4, 3), intArrayOf(2, 3, 1, 4),
        intArrayOf(2, 3, 4, 1), intArrayOf(2, 4, 1, 3), intArrayOf(2, 4, 3, 1),
        intArrayOf(3, 1, 2, 4), intArrayOf(3, 1, 4, 2), intArrayOf(3, 2, 1, 4),
        intArrayOf(3, 2, 4, 1), intArrayOf(3, 4, 1, 2), intArrayOf(3, 4, 2, 1),
        intArrayOf(4, 1, 2, 3), intArrayOf(4, 1, 3, 2), intArrayOf(4, 2, 1, 3),
        intArrayOf(4, 2, 3, 1), intArrayOf(4, 3, 1, 2), intArrayOf(4, 3, 2, 1)
    )

    val group1Markers = listOf('m', 't', 'e', 'h')
    val group2Markers = listOf('a', 'r', 's', 'k')

    private val excelEpoch: LocalDate = LocalDate.of(1899, 12, 30)

    // VBA CHair(Z): Z is 17..42 -> maps to cipherChars[Z - 17]
    fun cHair(z: Int): Char {
        require(z in 17..42) { "CHair index out of bounds: $z" }
        return cipherChars[z - 17]
    }

    // VBA Ascix(Z): returns 17..42
    fun ascix(ch: Char): Int {
        val idx = cipherChars.indexOf(ch)
        require(idx != -1) { "Invalid cipher character '$ch'" }
        return idx + 17
    }

    fun toExcelDateSerial(dateInput: String?): Pair<Int, LocalDate> {
        val trimmed = dateInput?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            val today = LocalDate.now()
            val serial = ChronoUnit.DAYS.between(excelEpoch, today).toInt()
            return serial to today
        }

        trimmed.toIntOrNull()?.let { serial ->
            require(serial in 10000..99999) { "Date serial must be 5 digits (10000..99999)" }
            return serial to excelEpoch.plusDays(serial.toLong())
        }

        val formatters = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,          // yyyy-MM-dd
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd")
        )
        val parsedDate = formatters.firstNotNullOfOrNull { fmt ->
            runCatching { LocalDate.parse(trimmed, fmt) }.getOrNull()
        } ?: throw IllegalArgumentException(
            "Invalid date format: '$trimmed'. Use yyyy-MM-dd, dd-MM-yyyy, or 5-digit Excel serial."
        )

        val serial = ChronoUnit.DAYS.between(excelEpoch, parsedDate).toInt()
        require(serial in 10000..99999) { "Date serial out of 5-digit range: $serial" }
        return serial to parsedDate
    }

    fun fromExcelDateSerial(serial: Int): LocalDate = excelEpoch.plusDays(serial.toLong())
}

// ============================================================================
// 3. ENCODER (GENERATES DUAL 13-CHAR CODES & INTERLEAVES INTO 26-CHAR CODE)
// ============================================================================

object LetterCodeGenerator {

    private fun generateSingle13CharCode(
        last4Digits: List<Int>,
        dateDigits: List<Int>,
        markers: List<Char>
    ): String {
        // DC(5): Random 1..10
        val dc5 = Random.nextInt(1, 11)
        val dc6 = LetterCipherSpec.cHair(dc5 + 16)

        // DC(1)..DC(4): Encoded last 4 digits of employee code
        val dc1To4 = last4Digits.map { digit -> LetterCipherSpec.cHair(digit + 16 + dc5) }

        // DC(7): Random 2..25 (row index in T15)
        val dc7 = Random.nextInt(2, 26)
        val dc8 = LetterCipherSpec.cHair(dc7 + 16)

        // DC(21)..DC(24): Permuted employee digits
        val perm = LetterCipherSpec.permutations[dc7 - 2]
        val dc21 = dc1To4[perm[0] - 1]
        val dc22 = dc1To4[perm[1] - 1]
        val dc23 = dc1To4[perm[2] - 1]
        val dc24 = dc1To4[perm[3] - 1]

        // DC(25)..DC(29): Encoded 5 digits of Excel date serial
        val dc25 = LetterCipherSpec.cHair(dateDigits[0] + 17)
        val dc26 = LetterCipherSpec.cHair(dateDigits[1] + 17)
        val dc27 = LetterCipherSpec.cHair(dateDigits[2] + 17)
        val dc28 = LetterCipherSpec.cHair(dateDigits[3] + 17)
        val dc29 = LetterCipherSpec.cHair(dateDigits[4] + 17)

        // DC(16): Random 27..50 -> lowercase ASCII 'a'..'x'
        val dc16Num = Random.nextInt(27, 51)
        val dc16 = (dc16Num - 26 + 96).toChar()

        // DC(30): Random pattern marker from given group
        val dc30 = markers.random()

        return when (dc30) {
            // Group 1 patterns (m, t, e, h)
            'm' -> "$dc25$dc21$dc26$dc22$dc27$dc23$dc28$dc24$dc29$dc6$dc8${dc16}m"
            't' -> "$dc25$dc26$dc21$dc22$dc27$dc23$dc24$dc28$dc29$dc6$dc8${dc16}t"
            'e' -> "$dc25$dc21$dc27$dc26$dc22$dc23$dc28$dc24$dc29$dc6$dc8${dc16}e"
            'h' -> "$dc25$dc26$dc21$dc23$dc22$dc27$dc24$dc28$dc29$dc6$dc8${dc16}h"

            // Group 2 patterns (a, r, s, k)
            'a' -> "$dc25$dc26$dc27$dc21$dc22$dc28$dc23$dc24$dc29$dc6$dc8${dc16}a"
            'r' -> "$dc21$dc25$dc26$dc22$dc27$dc23$dc28$dc24$dc29$dc6$dc8${dc16}r"
            's' -> "$dc25$dc21$dc22$dc26$dc27$dc23$dc24$dc28$dc29$dc6$dc8${dc16}s"
            'k' -> "$dc21$dc22$dc25$dc26$dc27$dc23$dc28$dc29$dc24$dc6$dc8${dc16}k"
            else -> error("Unsupported marker: $dc30")
        }
    }

    // Interleaves 1st code (abcd...) and 2nd code (mnop...) into ambncodp...
    private fun interleaveCodes(first: String, second: String): String {
        require(first.length == second.length)
        return buildString(first.length * 2) {
            for (i in first.indices) {
                append(first[i])
                append(second[i])
            }
        }
    }

    fun generate(employeeCode: String, dateInput: String? = null): LetterCodeResponse {
        val cleanCode = employeeCode.trim()
        val digitsOnly = cleanCode.filter { it.isDigit() }
        require(digitsOnly.length >= 4) {
            "Employee code '$cleanCode' must contain at least 4 digits (e.g., ADKR5678)."
        }
        val last4Digits = digitsOnly.takeLast(4).map { it.digitToInt() }

        val (serial, localDate) = LetterCipherSpec.toExcelDateSerial(dateInput)
        val dateDigits = serial.toString().padStart(5, '0').map { it.digitToInt() }

        val firstCode = generateSingle13CharCode(last4Digits, dateDigits, LetterCipherSpec.group1Markers)
        val secondCode = generateSingle13CharCode(last4Digits, dateDigits, LetterCipherSpec.group2Markers)
        val combinedCode = interleaveCodes(firstCode, secondCode)

        return LetterCodeResponse(
            employeeCode = cleanCode,
            dateSerial = serial,
            issueDate = localDate.toString(),
            firstCode = firstCode,
            secondCode = secondCode,
            combinedCode = combinedCode
        )
    }
}

// ============================================================================
// 4. DECODER & DUAL-SIMILARITY VERIFIER
// ============================================================================

object LetterCodeDecoder {

    private fun decodeSingle13CharCode(code13: String, allowedMarkers: Set<Char>): DecodedHalf {
        require(code13.length == 13) { "Each split code must be 13 characters" }

        val marker = code13[12]
        require(marker in allowedMarkers) { "Invalid pattern marker '$marker' in half '$code13'" }

        // Position 10 (index 9) -> DC(6) -> DC(5) (shift 1..10)
        val dc5 = LetterCipherSpec.ascix(code13[9]) - 16
        require(dc5 in 1..10) { "Corrupted shift key in half '$code13'" }

        // Position 11 (index 10) -> DC(8) -> DC(7) (permutation row 2..25)
        val dc7 = LetterCipherSpec.ascix(code13[10]) - 16
        require(dc7 in 2..25) { "Corrupted permutation key in half '$code13'" }

        // Position 12 (index 11) -> DC(16) dummy char ('a'..'x')
        require(code13[11] in 'a'..'x') { "Corrupted check character in half '$code13'" }

        // Extract 5 Date chars (dc25..dc29) and 4 Permuted Employee chars (dc21..dc24)
        val (dateChars, permEmpChars) = when (marker) {
            'm' -> listOf(code13[0], code13[2], code13[4], code13[6], code13[8]) to listOf(code13[1], code13[3], code13[5], code13[7])
            't' -> listOf(code13[0], code13[1], code13[4], code13[7], code13[8]) to listOf(code13[2], code13[3], code13[5], code13[6])
            'e' -> listOf(code13[0], code13[3], code13[2], code13[6], code13[8]) to listOf(code13[1], code13[4], code13[5], code13[7])
            'h' -> listOf(code13[0], code13[1], code13[5], code13[7], code13[8]) to listOf(code13[2], code13[4], code13[3], code13[6])
            'a' -> listOf(code13[0], code13[1], code13[2], code13[5], code13[8]) to listOf(code13[3], code13[4], code13[6], code13[7])
            'r' -> listOf(code13[1], code13[2], code13[4], code13[6], code13[8]) to listOf(code13[0], code13[3], code13[5], code13[7])
            's' -> listOf(code13[0], code13[3], code13[4], code13[7], code13[8]) to listOf(code13[1], code13[2], code13[5], code13[6])
            'k' -> listOf(code13[2], code13[3], code13[4], code13[6], code13[7]) to listOf(code13[0], code13[1], code13[8], code13[5])
            else -> error("Unsupported marker: $marker")
        }

        // Decode 5 date digits: ascix(ch) - 17
        val dateDigits = dateChars.map { ch ->
            val d = LetterCipherSpec.ascix(ch) - 17
            require(d in 0..9) { "Invalid date digit decoded from '$ch'" }
            d
        }
        val dateSerial = dateDigits.joinToString("").toInt()
        val issueDate = LetterCipherSpec.fromExcelDateSerial(dateSerial).toString() // yyyy-MM-dd

        // Un-permute DC(21)..DC(24) back to original DC(1)..DC(4)
        val perm = LetterCipherSpec.permutations[dc7 - 2]
        val originalEmpChars = CharArray(4)
        for (i in 0..3) {
            originalEmpChars[perm[i] - 1] = permEmpChars[i]
        }

        // Decode 4 employee digits: ascix(ch) - 16 - dc5
        val last4Digits = originalEmpChars.map { ch ->
            val d = LetterCipherSpec.ascix(ch) - 16 - dc5
            require(d in 0..9) { "Invalid employee digit decoded from '$ch'" }
            d
        }.joinToString("")

        return DecodedHalf(
            raw13Code = code13,
            marker = marker,
            last4Digits = last4Digits,
            dateSerial = dateSerial,
            issueDate = issueDate
        )
    }

    fun decodeAndVerify(
        combinedCodeInput: String,
        lookupEmployee: ((last4Digits: String, issueDate: String) -> List<EmployeeMatchInfo>)? = null
    ): VerifyLetterCodeResponse {
        val clean = combinedCodeInput.trim().lowercase()
        if (clean.length != 26) {
            return VerifyLetterCodeResponse(
                isAuthentic = false,
                reason = "Invalid code length (${clean.length}). Verification code must be exactly 26 characters."
            )
        }

        // Split ambncodp... back into 1st code (even indices) and 2nd code (odd indices)
        val first13 = buildString(13) { for (i in 0 until 26 step 2) append(clean[i]) }
        val second13 = buildString(13) { for (i in 1 until 26 step 2) append(clean[i]) }

        return try {
            val firstDecoded = decodeSingle13CharCode(first13, LetterCipherSpec.group1Markers.toSet())
            val secondDecoded = decodeSingle13CharCode(second13, LetterCipherSpec.group2Markers.toSet())

            val digitsMatch = firstDecoded.last4Digits == secondDecoded.last4Digits
            val datesMatch = firstDecoded.dateSerial == secondDecoded.dateSerial

            if (digitsMatch && datesMatch) {
                val matches = runCatching {
                    lookupEmployee?.invoke(firstDecoded.last4Digits, firstDecoded.issueDate).orEmpty()
                }.getOrDefault(emptyList())

                VerifyLetterCodeResponse(
                    isAuthentic = true,
                    reason = "Verified: Both code halves match identically.",
                    last4Digits = firstDecoded.last4Digits,
                    issueDate = firstDecoded.issueDate,
                    dateSerial = firstDecoded.dateSerial,
                    firstHalf = firstDecoded,
                    secondHalf = secondDecoded,
                    matchedEmployees = matches
                )
            } else {
                VerifyLetterCodeResponse(
                    isAuthentic = false,
                    reason = "Tampered/Invalid: First half (${firstDecoded.last4Digits}, ${firstDecoded.issueDate}) does not match second half (${secondDecoded.last4Digits}, ${secondDecoded.issueDate}).",
                    firstHalf = firstDecoded,
                    secondHalf = secondDecoded
                )
            }
        } catch (e: Exception) {
            VerifyLetterCodeResponse(
                isAuthentic = false,
                reason = "Decoding failed: ${e.message ?: "Corrupted code"}"
            )
        }
    }
}

// ============================================================================
// 5. KTOR ROUTES (/api/letter-code & /api/letter-code/verify)
// ============================================================================

fun Route.letterCodeRoutes(
    lookupEmployee: ((last4Digits: String, issueDate: String) -> List<EmployeeMatchInfo>)? = null
) {
    route("/api/letter-code") {
        // Generate 26-char unique verification code
        // POST /api/letter-code  { "code": "ADKR5678", "date": "2026-11-18" }
        post {
            try {
                val req = call.receive<LetterCodeRequest>()
                val result = LetterCodeGenerator.generate(req.code, req.date)
                call.respond(HttpStatusCode.OK, result)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid input")))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Server error")))
            }
        }

        // GET /api/letter-code?code=ADKR5678&date=2026-11-18
        get {
            try {
                val code = call.request.queryParameters["code"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing 'code' query parameter"))
                val date = call.request.queryParameters["date"]
                val result = LetterCodeGenerator.generate(code, date)
                call.respond(HttpStatusCode.OK, result)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid input")))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Server error")))
            }
        }

        // Decode & Verify 26-char code + fetch employee/letter details
        // POST /api/letter-code/verify  { "combinedCode": "qvfcyeaobkvfyihoimtrxlppma" }
        post("/verify") {
            try {
                val req = call.receive<VerifyLetterCodeRequest>()
                val result = LetterCodeDecoder.decodeAndVerify(req.combinedCode, lookupEmployee)
                val status = if (result.isAuthentic) HttpStatusCode.OK else HttpStatusCode.UnprocessableEntity
                call.respond(status, result)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid verification request")))
            }
        }

        // GET /api/letter-code/verify?code=qvfcyeaobkvfyihoimtrxlppma
        get("/verify") {
            val code = call.request.queryParameters["code"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing 'code' query parameter"))
            val result = LetterCodeDecoder.decodeAndVerify(code, lookupEmployee)
            val status = if (result.isAuthentic) HttpStatusCode.OK else HttpStatusCode.UnprocessableEntity
            call.respond(status, result)
        }
    }
}