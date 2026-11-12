package com.example

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

fun Route.commentsQueueRoute() {
    post("/api/turso/add-to-queue") {
        try {
            val bodyText = call.receiveText()
            val jsonElement = try {
                Json.parseToJsonElement(bodyText).jsonObject
            } catch (e: Exception) {
                null
            }

            val separater = jsonElement?.get("separater")?.jsonPrimitive?.content
                ?: jsonElement?.get("separator")?.jsonPrimitive?.content
                ?: "NewJ"
            val jsonDetails = jsonElement?.get("jsondetails")?.jsonPrimitive?.content
                ?: jsonElement?.get("jsonDetails")?.jsonPrimitive?.content
                ?: bodyText
            val wef = jsonElement?.get("wef")?.jsonPrimitive?.content
                ?: java.time.LocalDate.now().toString()

            val conn = TursoDatabaseService.getConnection()
            if (conn == null) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
                return@post
            }

            try {
                val passedSlNo = jsonElement?.get("slno")?.jsonPrimitive?.content
                val finalSlNo = if (!passedSlNo.isNullOrBlank()) {
                    passedSlNo
                } else {
                    conn.createStatement().use { stmt ->
                        val rs = stmt.executeQuery("SELECT COALESCE(MAX(CAST(slno AS INTEGER)), 0) + 1 AS next_sl FROM Comments")
                        if (rs.next()) rs.getInt("next_sl").toString() else "1"
                    }
                }

                val sql = "INSERT INTO Comments (slno, separater, jsondetails, wef) VALUES (?, ?, ?, ?);"
                val pstmt = conn.prepareStatement(sql)
                pstmt.setString(1, finalSlNo)
                pstmt.setString(2, separater)
                pstmt.setString(3, jsonDetails)
                pstmt.setString(4, wef)

                pstmt.executeUpdate()
                pstmt.close()
                conn.close()

                call.respond(HttpStatusCode.OK, mapOf("status" to "success", "message" to "Record successfully added to queue"))
            } catch (dbEx: Exception) {
                try { conn.close() } catch (_: Exception) {}
                dbEx.printStackTrace()
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database Error: ${dbEx.localizedMessage}"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf(
                    "error" to (e.localizedMessage ?: "Failed to process queue request"),
                    "exception" to e.javaClass.simpleName
                )
            )
        }
    }

    get("/api/turso/comments/{separater}") {
        val separater = call.parameters["separater"] ?: "PENDING"
        val conn = TursoDatabaseService.getConnection()
        if (conn == null) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))
            return@get
        }

        try {
            val pstmt = if (separater.equals("PENDING", ignoreCase = true) || separater.equals("ALL", ignoreCase = true)) {
                conn.prepareStatement("SELECT * FROM Comments WHERE separater NOT IN ('CONF', 'REJ') ORDER BY id DESC")
            } else {
                conn.prepareStatement("SELECT * FROM Comments WHERE separater = ? ORDER BY id DESC").apply {
                    setString(1, separater)
                }
            }
            val rs = pstmt.executeQuery()

            val records = mutableListOf<Map<String, String>>()
            while (rs.next()) {
                records.add(
                    mapOf(
                        "id" to (rs.getString("id") ?: ""),
                        "slno" to (rs.getString("slno") ?: ""),
                        "separater" to (rs.getString("separater") ?: ""),
                        "jsondetails" to (rs.getString("jsondetails") ?: ""),
                        "wef" to (rs.getString("wef") ?: "")
                    )
                )
            }
            rs.close()
            pstmt.close()
            conn.close()

            call.respond(records)
        } catch (e: Exception) {
            try { conn.close() } catch (_: Exception) {}
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Unknown error")))
        }
    }


    // 1. CONFIRM MUP -> MasterData update kariba & separater ku MUP ru CONF kariba
    post("/api/turso/confirm-mup/{id}") {
        val commentId = call.parameters["id"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id parameter"))

        val conn = TursoDatabaseService.getConnection()
            ?: return@post call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))

        try {
            var separater = ""
            var jsonDetails = ""

            conn.prepareStatement("SELECT separater, jsondetails FROM Comments WHERE id = ?").use { ps ->
                ps.setString(1, commentId)
                val rs = ps.executeQuery()
                if (rs.next()) {
                    separater = rs.getString("separater") ?: ""
                    jsonDetails = rs.getString("jsondetails") ?: ""
                }
            }

            if (jsonDetails.isBlank()) {
                conn.close()
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Comment record not found"))
                return@post
            }

            if (separater == "MUP") {
                val obj = Json.parseToJsonElement(jsonDetails).jsonObject
                val empCode = obj["Code_2"]?.jsonPrimitive?.content?.trim() ?: ""

                val allowedColumns = setOf(
                    "fathers_name_4", "gender_5", "dob_6", "designation_7", "department_8",
                    "branch_name_9", "doj_in_adhikar_10", "present_address_11", "permanent_address_12",
                    "highest_educational_qualification_13", "personal_contact_number_14", "contact_no_2_15",
                    "aadhar_card_no_16", "pan_card_no_17", "driving_18", "bank_name_19",
                    "bank_account_number_20", "ifsc_code_bank_branch_name_21", "esi_no_22",
                    "epf_uan_no_23", "uan_name_24", "dol_25", "company_name_starting_from_current_to_old_26",
                    "designationbackcompany_27", "blood_group_28", "ff_29", "status_30", "interviewed_31",
                    "date_of_resignation_32", "last_working_date_33", "type_of_exit_34", "emp_status_35",
                    "emp_status_temp_36", "slary_37", "mast_rem_38", "hravariable_39", "daaosa_40",
                    "arrears_increment_41", "tds_42", "staff_loan_43", "staff_advance_44", "others_45",
                    "other_earnings_46", "salary_code_47", "sal_h_48", "epf55_49", "esi56_50",
                    "lender_51", "email61_personal_52", "offer_app_53", "insurancesbi_54", "equifax_55",
                    "exit_date_56", "idphoto68_57", "mail_id_office_58", "coment77_59", "documentsubmissiondate78_60"
                )

                val updates = obj.filterKeys { it in allowedColumns }
                if (empCode.isNotBlank() && updates.isNotEmpty()) {
                    val setClause = updates.keys.joinToString(", ") { "$it = ?" }
                    conn.prepareStatement("UPDATE MasterData SET $setClause WHERE LOWER(TRIM(Code_2)) = LOWER(TRIM(?))").use { ps ->
                        var idx = 1
                        updates.values.forEach { v -> ps.setString(idx++, v.jsonPrimitive.content) }
                        ps.setString(idx, empCode)
                        ps.executeUpdate()
                    }
                }
            }

            // separater ku MUP ru CONF karideba
            conn.prepareStatement("UPDATE Comments SET separater = 'CONF' WHERE id = ?").use { ps ->
                ps.setString(1, commentId)
                ps.executeUpdate()
            }

            conn.close()
            call.respond(HttpStatusCode.OK, mapOf("status" to "confirmed", "message" to "Confirmed & MasterData updated"))
        } catch (e: Exception) {
            try { conn.close() } catch (_: Exception) {}
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Confirm failed")))
        }
    }

    // 2. REJECT MUP -> MasterData change na kari separater ku REJ kariba
    post("/api/turso/reject-mup/{id}") {
        val commentId = call.parameters["id"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id parameter"))

        val conn = TursoDatabaseService.getConnection()
            ?: return@post call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database connection failed"))

        try {
            conn.prepareStatement("UPDATE Comments SET separater = 'REJ' WHERE id = ?").use { ps ->
                ps.setString(1, commentId)
                ps.executeUpdate()
            }
            conn.close()
            call.respond(HttpStatusCode.OK, mapOf("status" to "rejected", "message" to "Rejected"))
        } catch (e: Exception) {
            try { conn.close() } catch (_: Exception) {}
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Reject failed")))
        }
    }
}