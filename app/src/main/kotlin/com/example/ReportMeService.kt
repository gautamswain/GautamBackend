package com.example

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection

@Serializable
data class BranchSummaryReportItem(
    val branchName: String,
    val clusterManagerCount: Int,
    val areaManagerCount: Int,
    val branchManagerCount: Int,
    val assistantBranchManagerCount: Int,
    val seniorCreditOfficerCount: Int,
    val creditOfficerCount: Int,
    val staffList: List<MasterDataDto>
)

@Serializable
data class HeadOfficeDeptReportItem(
    val departmentName: String,
    val deptRank: Int,
    val totalStaffCount: Int,
    val staffList: List<MasterDataDto>
)

@Serializable
data class ReportResult(
    val status: String,
    val idRecord: String,
    val totalMasterRecords: Int = 0,
    val totalUniqueCodes: Int,
    val codes: List<String>,
    val totalMasterActive: Int = 0,
    val masterActiveCodes: List<String> = emptyList(),
    val totalCommonCodes: Int = 0,
    val commonCodes: List<String> = emptyList(),
    val totalLeftSalaryCodes: Int = 0,
    val leftSalaryCodes: List<String> = emptyList(),
    val totalRecentJoinedCodes: Int = 0,
    val recentJoinedCodes: List<String> = emptyList(),
    val totalBranchwiseCount: Int = 0,
    val branchwiseCodes: List<BranchSummaryReportItem> = emptyList(),
    val totalHeadOfficeDeptCount: Int = 0,
    val headOfficeCodes: List<HeadOfficeDeptReportItem> = emptyList()
)

object ReportMeService {

    // Compact JSON: Khali ("") fields JSON re lekha heba nahi + a1..a67 short keys use haba
    private val compactJson = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    fun getTotalMasterRecords(conn: Connection): Int {
        var count = 0
        conn.createStatement().use { stmt ->
            try {
                val rs = stmt.executeQuery("SELECT COUNT(*) FROM MasterData")
                if (rs.next()) {
                    count = rs.getInt(1)
                }
                rs.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return count
    }

    fun last_two_month_all_code(conn: Connection): List<String> {
        val uniqueCodes = mutableSetOf<String>()
        val query = "SELECT month, emp_id_2 FROM SalaryHistory ORDER BY rowid DESC"

        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery(query)
            val allRows = mutableListOf<Pair<String, String>>()

            while (rs.next()) {
                val month = rs.getString(1) ?: ""
                val empId = rs.getString(2) ?: ""
                allRows.add(Pair(month, empId))
            }
            rs.close()

            val distinctMonths = allRows.map { it.first }.filter { it.isNotBlank() }.distinct().take(2)

            for (row in allRows) {
                val month = row.first
                val empId = row.second
                if (month in distinctMonths && empId.isNotBlank() && empId != "0") {
                    uniqueCodes.add(empId.trim())
                }
            }
        }
        return uniqueCodes.toList()
    }

    fun Master_Active(conn: Connection): List<String> {
        val activeCodes = mutableSetOf<String>()
        conn.createStatement().use { stmt ->
            try {
                val query = "SELECT Code_2 FROM MasterData WHERE emp_status_35 = '1' OR emp_status_35 = 1"
                val rs = stmt.executeQuery(query)
                while (rs.next()) {
                    val code = rs.getString(1) ?: ""
                    if (code.isNotBlank() && code != "0") {
                        activeCodes.add(code.trim())
                    }
                }
                rs.close()
            } catch (_: Exception) {
                try {
                    val rs = stmt.executeQuery("SELECT Code_2 FROM MasterData")
                    while (rs.next()) {
                        val code = rs.getString(1) ?: ""
                        if (code.isNotBlank() && code != "0") {
                            activeCodes.add(code.trim())
                        }
                    }
                    rs.close()
                } catch (_: Exception) {}
            }
        }
        return activeCodes.toList()
    }

    fun Sal_Mast_Common(list1a: List<String>, list1b: List<String>): List<String> {
        val setB = list1b.toSet()
        return list1a.filter { it in setB }.distinct()
    }

    fun Left_Salary(list1a: List<String>, list1b: List<String>): List<String> {
        val setB = list1b.toSet()
        return list1a.filter { it !in setB }.distinct()
    }

    fun Recent_Joined(list1a: List<String>, list1b: List<String>): List<String> {
        val setA = list1a.toSet()
        return list1b.filter { it !in setA }.distinct()
    }

    // 1f (Branchwise by slno & Desig Rank) & 1g (Head Office by Department 1..10 & 99) — Last 24 Salary IDs
    fun Branchwisedata(conn: Connection, activeCodes: List<String>): Pair<List<BranchSummaryReportItem>, List<HeadOfficeDeptReportItem>> {
        val activeSet = activeCodes.map { it.trim().uppercase() }.toSet()

        fun normalizeName(name: String): String {
            return name.trim().lowercase().replace(Regex("\\s+"), " ")
        }

        // Helper: Head Office Department ku serial Rank (1..10) au last re Others (99) deba
        fun getHeadOfficeDeptInfo(rawDept: String): Pair<Int, String> {
            val d = rawDept.trim().lowercase()
            return when {
                d.contains("manage") -> Pair(1, "Management")
                d.contains("operat") -> Pair(2, "Operations")
                d.contains("human") || d == "hr" || d.contains("hr ") -> Pair(3, "Human Resource")
                d.contains("account") || d.contains("finance") -> Pair(4, "Accounts")
                d == "mis" || d.contains("mis") -> Pair(5, "MIS")
                d.contains("audit") -> Pair(6, "Audit")
                d.contains("credit") -> Pair(7, "Credit")
                d.contains("train") -> Pair(8, "Training")
                d.contains("tele") || d.contains("caller") -> Pair(9, "Telecaller")
                d.contains("admin") -> Pair(10, "Administration")
                else -> Pair(99, rawDept.trim().ifBlank { "Others" })
            }
        }

        // 1. BranchDetails ru (normalizedName -> Pair(slno, exactBranchName)) map banai neba
        val branchInfoMap = mutableMapOf<String, Pair<Int, String>>()
        conn.createStatement().use { branchStmt ->
            try {
                val branchRs = branchStmt.executeQuery("SELECT * FROM BranchDetails")
                while (branchRs.next()) {
                    val slnoVal = (branchRs.getString(2) ?: "").trim().toIntOrNull() ?: 9999
                    val bName = (branchRs.getString(4) ?: "").trim()
                    if (bName.isNotBlank()) {
                        branchInfoMap[normalizeName(bName)] = Pair(slnoVal, bName)
                    }
                }
                branchRs.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val groupedByBranch = mutableMapOf<String, MutableList<Pair<Int, MasterDataDto>>>()
        val branchSlnoLookup = mutableMapOf<String, Int>()
        val groupedByHoDept = mutableMapOf<Pair<Int, String>, MutableList<MasterDataDto>>()

        // 2. MasterData ru active staff fetch kari Branch (1f) o Head Office Dept (1g) re bhaga kariba
        conn.createStatement().use { empStmt ->
            val seenIds = mutableSetOf<String>()
            try {
                val empRs = empStmt.executeQuery("SELECT * FROM MasterData")
                while (empRs.next()) {
                    val rawDto = StaffRepository.parseMasterDataDto(empRs)
                    val empId = rawDto.empId.trim()
                    val empIdUpper = empId.uppercase()
                    val designation = rawDto.designation.trim()
                    val rawBranch = rawDto.branchName.trim()

                    if (activeSet.contains(empIdUpper) && empId.isNotBlank() && empId != "0" && seenIds.add(empIdUpper)) {
                        // Kevala Last 24 Salary IDs rakhiba (Ubhaya Branch Summary o Head Office pain)
                        val last24SalaryHistory = rawDto.salaryHistory
                            .split("~")
                            .filter { it.isNotBlank() }
                            .takeLast(24)
                            .joinToString("~")

                        val dto = rawDto.copy(salaryHistory = last24SalaryHistory)

                        if (rawBranch.contains("head office", ignoreCase = true) || rawBranch.equals("ho", ignoreCase = true)) {
                            // 1g: Head Office staff ku Department anuyayi group kariba
                            val deptKey = getHeadOfficeDeptInfo(dto.department)
                            groupedByHoDept.getOrPut(deptKey) { mutableListOf() }.add(dto)
                        } else {
                            // 1f: Field Branch staff
                            val normBranch = normalizeName(rawBranch)
                            val branchMeta = branchInfoMap[normBranch]
                            val slno = branchMeta?.first ?: 9999
                            val officialBranchName = branchMeta?.second ?: rawBranch

                            branchSlnoLookup[officialBranchName] = slno

                            val d = designation.lowercase()
                            val desigRank = when {
                                d == "co" || (d.contains("credit officer") && !d.contains("senior") && !d.contains("sr")) -> 1
                                d == "sco" || d.contains("senior credit officer") || d.contains("sr. credit officer") || d.contains("sr credit officer") -> 2
                                d == "abm" || d.contains("assistant branch manager") || d.contains("asst. branch manager") || d.contains("asst branch manager") -> 3
                                d == "bm" || d == "sr. bm" || d.contains("branch manager") -> 4
                                d == "am" || d == "sam" || d.contains("area manager") -> 5
                                d == "cm" || d.contains("cluster manager") -> 6
                                d.contains("executive") -> 7
                                else -> 99
                            }

                            groupedByBranch.getOrPut(officialBranchName) { mutableListOf() }
                                .add(Pair(desigRank, dto))
                        }
                    }
                }
                empRs.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 3a. 1f Final Branch List (Sorted by slno & designation rank)
        val finalBranchSummaryList = groupedByBranch.entries
            .sortedBy { branchSlnoLookup[it.key] ?: 9999 }
            .map { (branchName, staffWithRanks) ->
                val sortedStaffPairs = staffWithRanks.sortedBy { it.first }
                val sortedStaffList = sortedStaffPairs.map { it.second }

                BranchSummaryReportItem(
                    branchName = branchName,
                    clusterManagerCount = sortedStaffPairs.count { it.first == 6 },
                    areaManagerCount = sortedStaffPairs.count { it.first == 5 },
                    branchManagerCount = sortedStaffPairs.count { it.first == 4 },
                    assistantBranchManagerCount = sortedStaffPairs.count { it.first == 3 },
                    seniorCreditOfficerCount = sortedStaffPairs.count { it.first == 2 },
                    creditOfficerCount = sortedStaffPairs.count { it.first == 1 },
                    staffList = sortedStaffList
                )
            }

        // 3b. 1g Final Head Office Department List (Sorted 1..10 & 99)
        val finalHeadOfficeDeptList = groupedByHoDept.entries
            .sortedBy { it.key.first }
            .map { (deptPair, staffList) ->
                HeadOfficeDeptReportItem(
                    departmentName = deptPair.second,
                    deptRank = deptPair.first,
                    totalStaffCount = staffList.size,
                    staffList = staffList
                )
            }

        return Pair(finalBranchSummaryList, finalHeadOfficeDeptList)
    }

    fun generateAndSaveReport(conn: Connection, x: String, y: String, fieldName: String = "a") {
        val safeX = x.replace("'", "''")
        val safeY = y.replace("'", "''")

        conn.createStatement().use { stmt ->
            try {
                val updated = stmt.executeUpdate("UPDATE reports SET $fieldName = '$safeY' WHERE id = '$safeX'")
                if (updated == 0) {
                    stmt.executeUpdate("INSERT INTO reports (id, cr, $fieldName) VALUES ('$safeX', 'm', '$safeY')")
                }
            } catch (_: Exception) {
                val updated = stmt.executeUpdate("UPDATE reports SET $fieldName = '$safeY' WHERE slno = '$safeX'")
                if (updated == 0) {
                    stmt.executeUpdate("INSERT INTO reports (emp_id, $fieldName) VALUES ('$safeX', '$safeY')")
                }
            }
        }
    }

    fun generateAndSaveReport(conn: Connection): ReportResult = processAndSave(conn)

    fun processAndSave(conn: Connection): ReportResult {
        val x = "1" // 1 no record

        val totalMasterCount = getTotalMasterRecords(conn)

        val codesList = last_two_month_all_code(conn)
        generateAndSaveReport(conn, x, compactJson.encodeToString(codesList), "a")

        val activeList = Master_Active(conn)
        generateAndSaveReport(conn, x, compactJson.encodeToString(activeList), "b")

        val commonList = Sal_Mast_Common(codesList, activeList)
        generateAndSaveReport(conn, x, compactJson.encodeToString(commonList), "c")

        val leftSalaryList = Left_Salary(codesList, activeList)
        generateAndSaveReport(conn, x, compactJson.encodeToString(leftSalaryList), "d")

        val recentJoinedList = Recent_Joined(codesList, activeList)
        generateAndSaveReport(conn, x, compactJson.encodeToString(recentJoinedList), "e")

        // 1f (Branchwise compact JSON) & 1g (Head Office Department-wise compact JSON)
        val (bList, hList) = Branchwisedata(conn, activeList)
        generateAndSaveReport(conn, x, compactJson.encodeToString(bList), "f")
        generateAndSaveReport(conn, x, compactJson.encodeToString(hList), "g")

        return ReportResult(
            status = "success",
            idRecord = x,
            totalMasterRecords = totalMasterCount,
            totalUniqueCodes = codesList.size,
            codes = codesList,
            totalMasterActive = activeList.size,
            masterActiveCodes = activeList,
            totalCommonCodes = commonList.size,
            commonCodes = commonList,
            totalLeftSalaryCodes = leftSalaryList.size,
            leftSalaryCodes = leftSalaryList,
            totalRecentJoinedCodes = recentJoinedList.size,
            recentJoinedCodes = recentJoinedList,
            totalBranchwiseCount = bList.size,
            branchwiseCodes = bList,
            totalHeadOfficeDeptCount = hList.size,
            headOfficeCodes = hList
        )
    }

    @Volatile
    private var isRunning = false

    @Volatile
    private var rerunRequested = false

    // Jekounasi route/service ru 1-line re call kariba pain: ReportMeService.triggerAsync()
    fun triggerAsync() {
        if (isRunning) {
            rerunRequested = true
            return
        }
        isRunning = true

        Thread {
            try {
                do {
                    rerunRequested = false
                    var conn: Connection? = null
                    try {
                        conn = TursoDatabaseService.getConnection()
                        if (conn != null) {
                            processAndSave(conn)
                            println("REPORTME SERVICE: reports updated successfully")
                        }
                    } catch (e: Exception) {
                        println("REPORTME SERVICE ERROR: ${e.message}")
                    } finally {
                        try { conn?.close() } catch (_: Exception) {}
                    }
                } while (rerunRequested)
            } finally {
                isRunning = false
            }
        }.start()
    }
}