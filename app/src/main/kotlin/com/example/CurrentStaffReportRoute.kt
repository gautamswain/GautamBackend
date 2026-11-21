package com.example

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.util.pipeline.*
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.*

fun Route.currentStaffReportRouting() {
    get("/api/turso/generate-current-staff-report") {
        try {
            val tempDir = File(System.getProperty("java.io.tmpdir"), "AdhikarStaffReports")
            if (!tempDir.exists()) tempDir.mkdirs()

            val savedFilePath = CurrentStaffReportService.generateAndSaveReportFromDb(
                { TursoDatabaseService.getConnection() }, 
                tempDir.absolutePath
            )

            call.respond(HttpStatusCode.OK, mapOf(
                "status" to "success",
                "message" to "Report generated successfully.",
                "path" to savedFilePath
            ))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "status" to "error",
                "message" to (e.localizedMessage ?: "Unknown error")
            ))
        }
    }

    get("/api/turso/download-latest-staff-report") {
        try {
            val tempDir = File(System.getProperty("java.io.tmpdir"), "AdhikarStaffReports")
            if (!tempDir.exists()) {
                tempDir.mkdirs()
            }

            val savedFilePath = CurrentStaffReportService.generateAndSaveReportFromDb(
                { TursoDatabaseService.getConnection() }, 
                tempDir.absolutePath
            )

            val file = File(savedFilePath)
            if (file.exists()) {
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(
                        ContentDisposition.Parameters.FileName, 
                        file.name
                    ).toString()
                )
                call.respondFile(file)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Report file not found"))
            }
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.InternalServerError, 
                mapOf("error" to (e.localizedMessage ?: "Unknown error"))
            )
        }
    }

    suspend fun PipelineContext<Unit, ApplicationCall>.handleStaffCsv() {
        try {
            val excludeSet = mutableSetOf<String>()

            // 1. Check query parameters
            val excludeQuery = call.request.queryParameters["exclude"]
            if (!excludeQuery.isNullOrBlank()) {
                try {
                    val parsed = Json.parseToJsonElement(excludeQuery)
                    if (parsed is JsonArray) {
                        parsed.forEach { excludeSet.add(it.jsonPrimitive.content.trim().uppercase()) }
                    }
                } catch (_: Exception) {
                    excludeQuery.split(",").forEach { excludeSet.add(it.trim().uppercase()) }
                }
            }

            // 2. Check request body if POST
            if (call.request.httpMethod == HttpMethod.Post) {
                try {
                    val bodyText = call.receiveText()
                    if (bodyText.isNotBlank()) {
                        val parsed = Json.parseToJsonElement(bodyText)
                        if (parsed is JsonArray) {
                            parsed.forEach { excludeSet.add(it.jsonPrimitive.content.trim().uppercase()) }
                        } else if (parsed is JsonObject) {
                            parsed["exclude"]?.let { arr ->
                                if (arr is JsonArray) {
                                    arr.forEach { excludeSet.add(it.jsonPrimitive.content.trim().uppercase()) }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            val istZone = ZoneId.of("Asia/Kolkata")
            val today = LocalDate.now(istZone)
            val displayDateFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")

            fun parseDate(raw: String?): LocalDate? {
                val clean = raw?.trim().orEmpty()
                if (clean.isBlank()) return null

                val serial = clean.toDoubleOrNull()?.toLong()
                if (serial != null && serial in 10000L..90000L) {
                    try {
                        return LocalDate.of(1899, 12, 30).plusDays(serial)
                    } catch (_: Exception) {}
                }

                val formatters = listOf(
                    DateTimeFormatter.ISO_LOCAL_DATE,
                    DateTimeFormatter.ofPattern("dd-MM-yyyy"),
                    DateTimeFormatter.ofPattern("d-M-yyyy"),
                    DateTimeFormatter.ofPattern("dd/MM/yyyy"),
                    DateTimeFormatter.ofPattern("d/M/yyyy"),
                    DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
                    DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH),
                    DateTimeFormatter.ofPattern("dd.MM.yyyy")
                )
                for (fmt in formatters) {
                    try {
                        return LocalDate.parse(clean.take(10), fmt)
                    } catch (_: Exception) {}
                }
                return null
            }

            val newJoiningCodes = mutableListOf<String>()
            TursoDatabaseService.getConnection()?.use { c ->
                c.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT e FROM reports WHERE id = '1' LIMIT 1").use { rs ->
                        if (rs.next()) {
                            val jsonE = rs.getString("e") ?: "[]"
                            runCatching {
                                val arr = Json.parseToJsonElement(jsonE)
                                if (arr is JsonArray) {
                                    arr.forEach {
                                        val code = it.jsonPrimitive.content.trim()
                                        if (code.isNotBlank() && !excludeSet.contains(code.uppercase())) {
                                            newJoiningCodes.add(code)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val effectiveCodes = if (newJoiningCodes.isNotEmpty()) {
                newJoiningCodes
            } else {
                val list = mutableListOf<String>()
                TursoDatabaseService.getConnection()?.use { c ->
                    c.createStatement().use { stmt ->
                        stmt.executeQuery("SELECT Code_2 FROM MasterData ORDER BY rowid DESC LIMIT 100").use { rs ->
                            while (rs.next()) {
                                val code = rs.getString("Code_2")?.trim().orEmpty()
                                if (code.isNotBlank() && !excludeSet.contains(code.uppercase())) {
                                    list.add(code)
                                }
                            }
                        }
                    }
                }
                list
            }

            val csvBuilder = StringBuilder()
            csvBuilder.appendLine("Sl No,Code,Name,Branch,Designation,DOJ,DOB,Contact1,Contact2,Age (Years),Exact Age,Aadhaar No")

            if (effectiveCodes.isNotEmpty()) {
                val placeholders = effectiveCodes.joinToString(",") { "?" }
                val sql = """
                    SELECT Code_2, employee_name_3, designation_7, branch_name_9,
                           doj_in_adhikar_10, dob_6, personal_contact_number_14, contact_no_2_15, aadhar_card_no_16
                    FROM MasterData
                    WHERE UPPER(TRIM(Code_2)) IN ($placeholders)
                """.trimIndent()

                TursoDatabaseService.getConnection()?.use { c ->
                    c.prepareStatement(sql).use { ps ->
                        effectiveCodes.forEachIndexed { idx, code ->
                            ps.setString(idx + 1, code)
                        }
                        ps.executeQuery().use { rs ->
                            val rowsMap = mutableMapOf<String, Array<String>>()
                            while (rs.next()) {
                                val code = rs.getString("Code_2")?.trim().orEmpty()
                                val name = rs.getString("employee_name_3")?.trim().orEmpty()
                                val desig = rs.getString("designation_7")?.trim().orEmpty()
                                val branch = rs.getString("branch_name_9")?.trim().orEmpty()
                                val dojRaw = rs.getString("doj_in_adhikar_10")?.trim().orEmpty()
                                val dobRaw = rs.getString("dob_6")?.trim().orEmpty()
                                val contact1 = rs.getString("personal_contact_number_14")?.trim().orEmpty()
                                val contact2 = rs.getString("contact_no_2_15")?.trim().orEmpty()
                                val aadhaar = rs.getString("aadhar_card_no_16")?.trim().orEmpty()

                                val dobDate = parseDate(dobRaw)
                                val dojDate = parseDate(dojRaw)

                                val formattedDob = if (dobDate != null) dobDate.format(displayDateFormatter) else dobRaw
                                val formattedDoj = if (dojDate != null) dojDate.format(displayDateFormatter) else dojRaw

                                val (ageYears, exactAge) = if (dobDate != null) {
                                    val period = java.time.Period.between(dobDate, today)
                                    period.years.toString() to "${period.years}Y ${period.months}M ${period.days}D"
                                } else {
                                    "" to ""
                                }

                                val safeAadhaar = if (aadhaar.isNotBlank()) "\"\t$aadhaar\"" else ""

                                rowsMap[code.uppercase()] = arrayOf(
                                    code, name, branch, desig, formattedDoj, formattedDob, contact1, contact2, ageYears, exactAge, safeAadhaar
                                )
                            }

                            var slNo = 1
                            for (code in effectiveCodes) {
                                val data = rowsMap[code.uppercase()]
                                if (data != null) {
                                    fun escapeCsv(v: String) = if (v.contains(",") || v.contains("\"") || v.contains("\n")) "\"${v.replace("\"", "\"\"")}\"" else v
                                    csvBuilder.appendLine(
                                        listOf(
                                            slNo.toString(),
                                            escapeCsv(data[0]),
                                            escapeCsv(data[1]),
                                            escapeCsv(data[2]),
                                            escapeCsv(data[3]),
                                            escapeCsv(data[4]),
                                            escapeCsv(data[5]),
                                            escapeCsv(data[6]),
                                            escapeCsv(data[7]),
                                            escapeCsv(data[8]),
                                            escapeCsv(data[9]),
                                            data[10]
                                        ).joinToString(",")
                                    )
                                    slNo++
                                }
                            }
                        }
                    }
                }
            }

            val todayStr = today.format(displayDateFormatter)
            val fileName = "New_Joining_1e_Report_$todayStr.csv"
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, fileName).toString()
            )
            call.respondText(csvBuilder.toString(), ContentType.Text.CSV)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Failed to generate CSV")))
        }
    }

    get("/api/turso/download-staff-csv") { handleStaffCsv() }
    post("/api/turso/download-staff-csv") { handleStaffCsv() }
}