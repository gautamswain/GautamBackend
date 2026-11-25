package com.example.services // replace with your actual package name

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class AttendanceVerifyRequestItem(
    val code: String = "",
    val name: String = "",
    val designation: String = "",
    val department: String = "",
    val branch: String = "",
    val lender: String = "",
    val doj: String = ""
)

@Serializable
data class AttendanceVerifyResultItem(
    val code: String,
    val salary: String = "",
    val mismatches: List<String> = emptyList(),
    val salaryHistory: String = "",
    val saltier: String = ""
)

@Serializable
data class AttendanceVerifyResponse(
    val results: List<AttendanceVerifyResultItem>
)

class AttendanceVerifyService(
    private val httpClient: HttpClient,
    private val tursoUrl: String,      // e.g. System.getenv("TURSO_DATABASE_URL")?.replace("libsql://", "https://")
    private val tursoAuthToken: String // e.g. System.getenv("TURSO_AUTH_TOKEN")
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun verifyRecords(records: List<AttendanceVerifyRequestItem>): AttendanceVerifyResponse {
        if (records.isEmpty()) return AttendanceVerifyResponse(emptyList())

        val codes = records.mapNotNull { it.code.trim().uppercase().takeIf { c -> c.isNotEmpty() } }.distinct()
        if (codes.isEmpty()) return AttendanceVerifyResponse(emptyList())

        // Fetch matching rows from MasterData joined with Designation for Saltier
        val inClause = codes.joinToString(",") { "'${it.replace("'", "''")}'" }
        val sql = """
            SELECT 
                UPPER(TRIM(m.a3)) AS code,
                TRIM(COALESCE(m.a4, '')) AS name,
                TRIM(COALESCE(m.a8, '')) AS designation,
                TRIM(COALESCE(m.a9, '')) AS department,
                TRIM(COALESCE(m.a10, '')) AS branch,
                TRIM(COALESCE(m.a52, '')) AS lender,
                TRIM(COALESCE(m.a11, '')) AS doj,
                TRIM(COALESCE(m.a20, '')) AS salary,
                TRIM(COALESCE(m.salary_history, '')) AS salary_history,
                TRIM(COALESCE(d.saltier, '')) AS saltier
            FROM MasterData m
            LEFT JOIN Designation d ON LOWER(TRIM(m.a8)) = LOWER(TRIM(d.designation))
            WHERE UPPER(TRIM(m.a3)) IN ($inClause)
        """.trimIndent()

        val dbMap = queryTursoMasterData(sql)

        val results = records.map { req ->
            val codeKey = req.code.trim().uppercase()
            val dbRow = dbMap[codeKey]

            if (dbRow == null) {
                AttendanceVerifyResultItem(
                    code = codeKey,
                    mismatches = listOf("Code not found in MasterData")
                )
            } else {
                val mismatches = mutableListOf<String>()
                if (req.name.isNotBlank() && !req.name.trim().equals(dbRow.name, ignoreCase = true)) {
                    mismatches.add("Name mismatch (${dbRow.name})")
                }
                if (req.designation.isNotBlank() && !req.designation.trim().equals(dbRow.designation, ignoreCase = true)) {
                    mismatches.add("Designation mismatch (${dbRow.designation})")
                }
                if (req.department.isNotBlank() && !req.department.trim().equals(dbRow.department, ignoreCase = true)) {
                    mismatches.add("Department mismatch (${dbRow.department})")
                }
                if (req.branch.isNotBlank() && !req.branch.trim().equals(dbRow.branch, ignoreCase = true)) {
                    mismatches.add("Branch mismatch (${dbRow.branch})")
                }
                if (req.lender.isNotBlank() && !req.lender.trim().equals(dbRow.lender, ignoreCase = true)) {
                    mismatches.add("Portfolio mismatch (${dbRow.lender})")
                }
                if (req.doj.isNotBlank() && dbRow.doj.isNotBlank() && req.doj.trim() != dbRow.doj) {
                    mismatches.add("DOJ mismatch (${dbRow.doj})")
                }

                AttendanceVerifyResultItem(
                    code = codeKey,
                    salary = dbRow.salary,
                    mismatches = mismatches,
                    salaryHistory = dbRow.salaryHistory,
                    saltier = dbRow.saltier
                )
            }
        }

        return AttendanceVerifyResponse(results)
    }

    private data class MasterDbRow(
        val name: String,
        val designation: String,
        val department: String,
        val branch: String,
        val lender: String,
        val doj: String,
        val salary: String,
        val salaryHistory: String,
        val saltier: String
    )

    private suspend fun queryTursoMasterData(sql: String): Map<String, MasterDbRow> {
        val pipelineUrl = "${tursoUrl.trimEnd('/')}/v2/pipeline"
        val bodyJson = buildJsonObject {
            putJsonArray("requests") {
                addJsonObject {
                    put("type", "execute")
                    putJsonObject("stmt") {
                        put("sql", sql)
                    }
                }
                addJsonObject {
                    put("type", "close")
                }
            }
        }

        val response = httpClient.post(pipelineUrl) {
            header(HttpHeaders.Authorization, "Bearer $tursoAuthToken")
            contentType(ContentType.Application.Json)
            setBody(bodyJson.toString())
        }

        val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val resultObj = root["results"]?.jsonArray?.getOrNull(0)?.jsonObject
            ?.get("response")?.jsonObject
            ?.get("result")?.jsonObject ?: return emptyMap()

        val cols = resultObj["cols"]?.jsonArray?.map { it.jsonObject["name"]?.jsonPrimitive?.content ?: "" } ?: emptyList()
        val rows = resultObj["rows"]?.jsonArray ?: return emptyMap()

        val map = mutableMapOf<String, MasterDbRow>()
        for (rowEl in rows) {
            val cells = rowEl.jsonArray
            fun cell(colName: String): String {
                val idx = cols.indexOf(colName)
                if (idx == -1 || idx >= cells.size) return ""
                val obj = cells[idx].jsonObject
                return obj["value"]?.jsonPrimitive?.contentOrNull ?: ""
            }

            val code = cell("code").uppercase()
            if (code.isNotEmpty()) {
                map[code] = MasterDbRow(
                    name = cell("name"),
                    designation = cell("designation"),
                    department = cell("department"),
                    branch = cell("branch"),
                    lender = cell("lender"),
                    doj = cell("doj"),
                    salary = cell("salary"),
                    salaryHistory = cell("salary_history"),
                    saltier = cell("saltier")
                )
            }
        }
        return map
    }
}