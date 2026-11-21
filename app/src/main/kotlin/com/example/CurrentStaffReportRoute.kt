package com.example

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import java.io.File

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

    get("/api/turso/download-staff-csv") {
        try {
            val csvBuilder = StringBuilder()
            // CSV Header
            csvBuilder.appendLine("Sl No,Staff Code (ID),Name,Designation,Branch,DOJ,DOB,Age (Years),Aadhaar Number")

            val today = java.time.LocalDate.now()
            val formatters = listOf(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"),
                java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"),
                java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")
            )

            fun parseDate(raw: String?): java.time.LocalDate? {
                val clean = raw?.trim().orEmpty()
                if (clean.isBlank()) return null
                for (fmt in formatters) {
                    try {
                        return java.time.LocalDate.parse(clean.take(10), fmt)
                    } catch (_: Exception) {}
                }
                return null
            }

            fun escapeCsv(value: String?): String {
                val v = value?.trim().orEmpty()
                return if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
                    "\"${v.replace("\"", "\"\"")}\""
                } else {
                    v
                }
            }

            // Fetch active staff (emp_status_35 = '1' or slary_37 = '1' representing 1a & 1b active staff)
            val sql = """
                SELECT Code_2, employee_name_3, designation_7, branch_name_9,
                       doj_in_adhikar_10, dob_6, aadhar_card_no_16
                FROM MasterData
                WHERE TRIM(emp_status_35) = '1' OR TRIM(slary_37) = '1'
                ORDER BY CAST(sl_no_1 AS INTEGER) ASC
            """.trimIndent()

            var slNo = 1
            val conn = TursoDatabaseService.getConnection()
            if (conn == null) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
                return@get
            }

            conn.use { c ->
                c.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        while (rs.next()) {
                            val code = rs.getString("Code_2").orEmpty()
                            val name = rs.getString("employee_name_3").orEmpty()
                            val designation = rs.getString("designation_7").orEmpty()
                            val branch = rs.getString("branch_name_9").orEmpty()
                            val doj = rs.getString("doj_in_adhikar_10").orEmpty()
                            val dobRaw = rs.getString("dob_6").orEmpty()
                            val aadhaar = rs.getString("aadhar_card_no_16").orEmpty()

                            val dobDate = parseDate(dobRaw)
                            val ageYears = if (dobDate != null) {
                                java.time.Period.between(dobDate, today).years.toString()
                            } else {
                                ""
                            }

                            // Prefix Aadhaar with tab or quotes so Excel doesn't convert 12 digits to scientific notation
                            val safeAadhaar = if (aadhaar.isNotBlank()) "\"\t$aadhaar\"" else ""

                            csvBuilder.appendLine(
                                listOf(
                                    slNo.toString(),
                                    escapeCsv(code),
                                    escapeCsv(name),
                                    escapeCsv(designation),
                                    escapeCsv(branch),
                                    escapeCsv(doj),
                                    escapeCsv(dobRaw),
                                    ageYears,
                                    safeAadhaar
                                ).joinToString(",")
                            )
                            slNo++
                        }
                    }
                }
            }

            val fileName = "Active_Staff_Report_${today}.csv"
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, fileName).toString()
            )
            call.respondText(csvBuilder.toString(), ContentType.Text.CSV)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Failed to generate CSV")))
        }
    }
}