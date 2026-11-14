package com.example

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL

private const val MAX_BATCH = 20

fun Route.configureSalaryRoutes() {
    get("/api/turso/salaries-batch") {
        val lastIds = call.request.queryParameters["history"].orEmpty()
            .split('~')
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "0" }
            .distinct()
            .takeLast(MAX_BATCH)

        if (lastIds.isEmpty()) {
            call.respond(HttpStatusCode.OK, emptyList<SalaryDto>())
            return@get
        }

        val payload = withContext(Dispatchers.IO) {
            try {
                fetchSalariesDirectHttp(lastIds)
            } catch (e: Exception) {
                application.log.error("salaries-batch failed for ${lastIds.size} ids", e)
                null
            }
        }

        if (payload == null) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "query_failed"))
            return@get
        }
        call.respond(HttpStatusCode.OK, payload)
    }
}

private fun fetchSalariesDirectHttp(ids: List<String>): List<SalaryDto> {
    if (ids.isEmpty()) return emptyList()

    val rawUrl = System.getenv("TURSO_DATABASE_URL")?.trim().orEmpty()
        .ifBlank { "https://adhikarhr-gautamswain.aws-ap-south-1.turso.io" }
    val authToken = System.getenv("TURSO_AUTH_TOKEN")?.trim().orEmpty()
        .ifBlank { throw IllegalStateException("TURSO_AUTH_TOKEN missing") }

    val host = rawUrl
        .removePrefix("jdbc:sqlite:")
        .removePrefix("jdbc:dbeaver:libsql:")
        .removePrefix("libsql://")
        .removePrefix("https://")
        .split("]").first().trim().trimEnd('/')

    val tursoUrl = "https://$host/v2/pipeline"

    val placeholders = ids.joinToString(",") { "?" }
    val sql = "SELECT * FROM SalaryHistory WHERE id IN ($placeholders)"

    val argsJson = buildJsonArray {
        ids.forEach { id ->
            addJsonObject {
                put("type", "text")
                put("value", id.trim())
            }
        }
    }

    val requestBody = buildJsonObject {
        putJsonArray("requests") {
            addJsonObject {
                put("type", "execute")
                putJsonObject("stmt") {
                    put("sql", sql)
                    put("args", argsJson)
                }
            }
            addJsonObject {
                put("type", "close")
            }
        }
    }.toString()

    val url = URL(tursoUrl)
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 15000
        readTimeout = 15000
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Authorization", "Bearer $authToken")
        doOutput = true
        outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
    }

    val responseText = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    val root = Json.parseToJsonElement(responseText).jsonObject
    val resultObj = root["results"]?.jsonArray?.get(0)?.jsonObject
        ?.get("response")?.jsonObject
        ?.get("result")?.jsonObject ?: return emptyList()

    val cols = resultObj["cols"]?.jsonArray?.map { it.jsonObject["name"]?.jsonPrimitive?.content.orEmpty() } ?: emptyList()
    val rows = resultObj["rows"]?.jsonArray ?: return emptyList()

    val dtoList = mutableListOf<SalaryDto>()

    for (rowElement in rows) {
        val rowArr = rowElement.jsonArray
        val map = mutableMapOf<String, String>()
        for (i in cols.indices) {
            val cellObj = rowArr[i].jsonObject
            val value = cellObj["value"]?.jsonPrimitive?.contentOrNull ?: ""
            map[cols[i].lowercase()] = value
        }

        dtoList.add(
            SalaryDto(
                id = map["id"] ?: map["salaryhistory-slno"] ?: "",
                month = map["month"] ?: "",
                slNo1 = map["sl_no_1"] ?: "",
                empId2 = map["emp_id_2"] ?: "",
                employeeName3 = map["employee_name_3"] ?: "",
                designation7 = map["designation_7"] ?: "",
                department8 = map["department_8"] ?: "",
                branchName9 = map["branch_name_9"] ?: "",
                portfolio7 = map["portfolio_7"] ?: "",
                paidDays8 = map["paid_days_8"] ?: "",
                basicDa9 = map["basic_da_9"] ?: "",
                hra10 = map["hra_10"] ?: "",
                conveyance11 = map["conveyance_11"] ?: "",
                medicalAllowance12 = map["medical_allowance_12"] ?: "",
                educationAllowance13 = map["education_allowance_13"] ?: "",
                lunchCanteenAllowance14 = map["lunch_canteen_allownance_14"] ?: "",
                specialAllowance15 = map["special_allowance_15"] ?: "",
                hraVariable39 = map["hravariable_39"] ?: "",
                otherAllowance17 = map["other_allowance_17"] ?: "",
                daAosa40 = map["daaosa_40"] ?: "",
                arrearsIncrement41 = map["arrears_increment_41"] ?: "",
                epf5549 = map["epf55_49"] ?: "",
                esi5650 = map["esi56_50"] ?: "",
                professionalTax22 = map["professional_tax_22"] ?: "",
                tds42 = map["tds_42"] ?: "",
                staffLoan43 = map["staff_loan_43"] ?: "",
                staffAdvance44 = map["staff_advance_44"] ?: "",
                others45 = map["others_45"] ?: "",
                otherEarnings46 = map["other_earnings_46"] ?: "",
                grossEarnings28 = map["gross_earnings_28"] ?: "",
                grossDeductions29 = map["gross_deductions_29"] ?: "",
                netAmount30 = map["net_amount_30"] ?: "",
                status00 = map["status_00"] ?: "",
                bankAccountNumber20 = map["bank_account_number_20"] ?: "",
                ifscCodeBankBranchName21 = map["ifsc_code_bank_branch_name_21"] ?: "",
                bankName19 = map["bank_name_19"] ?: "",
                backSalaryGive = map["backsalarygive"] ?: ""
            )
        )
    }

    val byId = dtoList.associateBy { it.id }
    return ids.mapNotNull { byId[it] }
}