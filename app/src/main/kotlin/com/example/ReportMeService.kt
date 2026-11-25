package com.example

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet

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
data class FieldChangeDetail(
    val field: String,
    val oldSalaryValue: String,
    val newMasterValue: String
)

@Serializable
data class SalaryMasterChangeItem(
    val empId: String,
    val empName: String,
    val sourceGroup: String, // "1f", "1g", or "1d"
    val lastSalaryId: String,
    val lastSalaryMonth: String,
    val changedFields: List<String>, // e.g. ["Designation", "Branch"]
    val changes: List<FieldChangeDetail>
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
    val headOfficeCodes: List<HeadOfficeDeptReportItem> = emptyList(),
    val totalSalaryMasterChangesCount: Int = 0,
    val salaryMasterChanges: List<SalaryMasterChangeItem> = emptyList()
)

object ReportMeService { // { #1 (L1) - object ReportMeService

    // Compact JSON: Khali ("") fields JSON re lekha heba nahi + a1..a67 short keys use haba
    private val compactJson = Json { // { #2 (L2) - compactJson config
        encodeDefaults = false
        ignoreUnknownKeys = true
    } // } #2 (L2) - compactJson config

    @Volatile
    private var isRunning = false

    @Volatile
    private var rerunRequested = false

    fun triggerAsync() { // { #3 (L1) - fun triggerAsync
        if (isRunning) { // { #4 (L2) - if isRunning
            rerunRequested = true
            return
        } // } #4 (L2) - if isRunning
        isRunning = true

        Thread { // { #5 (L2) - Thread
            try { // { #6 (L3) - try
                do { // { #7 (L4) - do-while
                    rerunRequested = false
                    var conn: Connection? = null
                    try { // { #8 (L5) - try
                        conn = TursoDatabaseService.getConnection()
                        if (conn != null) { // { #9 (L6) - if conn != null
                            processAndSave(conn)
                            println("REPORTME SERVICE: reports updated successfully")
                        } // } #9 (L6) - if conn != null
                    } catch (e: Exception) { // } #8 (L5) - try / { #10 (L5) - catch Exception
                        println("REPORTME SERVICE ERROR: ${e.message}")
                    } finally { // } #10 (L5) - catch Exception / { #11 (L5) - finally
                        try { conn?.close() } catch (_: Exception) {}
                    } // } #11 (L5) - finally
                } while (rerunRequested)
            } finally { // } #7 (L4) - do-while / { #12 (L3) - finally
                isRunning = false
            } // } #12 (L3) - finally
        }.start()
    } // } #3 (L1) - fun triggerAsync

    fun getTotalMasterRecords(conn: Connection): Int { // { #13 (L1) - fun getTotalMasterRecords
        var count = 0
        conn.createStatement().use { stmt -> // { #14 (L2) - stmt.use
            try { // { #15 (L3) - try
                val rs = stmt.executeQuery("SELECT COUNT(*) FROM MasterData")
                if (rs.next()) { // { #16 (L4) - if rs.next
                    count = rs.getInt(1)
                } // } #16 (L4) - if rs.next
                rs.close()
            } catch (e: Exception) { // } #15 (L3) - try / { #17 (L3) - catch Exception
                e.printStackTrace()
            } // } #17 (L3) - catch Exception
        } // } #14 (L2) - stmt.use
        return count
    } // } #13 (L1) - fun getTotalMasterRecords

    fun last_two_month_all_code(conn: Connection): List<String> { // { #18 (L1) - fun last_two_month_all_code
        val uniqueCodes = mutableSetOf<String>()
        val query = "SELECT month, emp_id_2 FROM SalaryHistory ORDER BY rowid DESC LIMIT 3000"

        conn.createStatement().use { stmt -> // { #19 (L2) - stmt.use
            val rs = stmt.executeQuery(query)
            val allRows = mutableListOf<Pair<String, String>>()

            while (rs.next()) { // { #20 (L3) - while rs.next
                val month = rs.getString(1) ?: ""
                val empId = rs.getString(2) ?: ""
                allRows.add(Pair(month, empId))
            } // } #20 (L3) - while rs.next
            rs.close()

            val distinctMonths = allRows.map { it.first }.filter { it.isNotBlank() }.distinct().take(2)

            for (row in allRows) { // { #21 (L3) - for row in allRows
                val month = row.first
                val empId = row.second
                if (month in distinctMonths && empId.isNotBlank() && empId != "0") { // { #22 (L4) - if month in distinctMonths
                    uniqueCodes.add(empId.trim())
                } // } #22 (L4) - if month in distinctMonths
            } // } #21 (L3) - for row in allRows
        } // } #19 (L2) - stmt.use
        return uniqueCodes.toList()
    } // } #18 (L1) - fun last_two_month_all_code

    fun Master_Active(conn: Connection): List<String> { // { #23 (L1) - fun Master_Active
        val activeCodes = mutableSetOf<String>()
        conn.createStatement().use { stmt -> // { #24 (L2) - stmt.use
            try { // { #25 (L3) - try
                val query = "SELECT Code_2 FROM MasterData WHERE emp_status_35 = '1' OR emp_status_35 = 1"
                val rs = stmt.executeQuery(query)
                while (rs.next()) { // { #26 (L4) - while rs.next
                    val code = rs.getString(1) ?: ""
                    if (code.isNotBlank() && code != "0") { // { #27 (L5) - if code.isNotBlank
                        activeCodes.add(code.trim())
                    } // } #27 (L5) - if code.isNotBlank
                } // } #26 (L4) - while rs.next
                rs.close()
            } catch (_: Exception) { // } #25 (L3) - try / { #28 (L3) - catch Exception
                try { // { #29 (L4) - try fallback
                    val rs = stmt.executeQuery("SELECT Code_2 FROM MasterData")
                    while (rs.next()) { // { #30 (L5) - while rs.next
                        val code = rs.getString(1) ?: ""
                        if (code.isNotBlank() && code != "0") { // { #31 (L6) - if code.isNotBlank
                            activeCodes.add(code.trim())
                        } // } #31 (L6) - if code.isNotBlank
                    } // } #30 (L5) - while rs.next
                    rs.close()
                } catch (_: Exception) {} // } #29 (L4) - try fallback
            } // } #28 (L3) - catch Exception
        } // } #24 (L2) - stmt.use
        return activeCodes.toList()
    } // } #23 (L1) - fun Master_Active

    fun Sal_Mast_Common(list1a: List<String>, list1b: List<String>): List<String> { // { #32 (L1) - fun Sal_Mast_Common
        val setB = list1b.toSet()
        return list1a.filter { it in setB }.distinct()
    } // } #32 (L1) - fun Sal_Mast_Common

    fun Left_Salary(list1a: List<String>, list1b: List<String>): List<String> { // { #33 (L1) - fun Left_Salary
        val setB = list1b.toSet()
        return list1a.filter { it !in setB }.distinct()
    } // } #33 (L1) - fun Left_Salary

    fun Recent_Joined(list1a: List<String>, list1b: List<String>): List<String> { // { #34 (L1) - fun Recent_Joined
        val setA = list1a.toSet()
        return list1b.filter { it !in setA }.distinct()
    } // } #34 (L1) - fun Recent_Joined

    // 1f (Branchwise by slno & Desig Rank) & 1g (Head Office by Department 1..10 & 99) — Last 24 Salary IDs
    fun Branchwisedata(conn: Connection, activeCodes: List<String>): Pair<List<BranchSummaryReportItem>, List<HeadOfficeDeptReportItem>> { // { #35 (L1) - fun Branchwisedata
        val activeSet = activeCodes.map { it.trim().uppercase() }.toSet()

        fun normalizeName(name: String): String { // { #36 (L2) - fun normalizeName
            return name.trim().lowercase().replace(Regex("\\s+"), " ")
        } // } #36 (L2) - fun normalizeName

        // Helper: Head Office Department ku serial Rank (1..10) au last re Others (99) deba
        fun getHeadOfficeDeptInfo(rawDept: String, rawDesig: String): Pair<Int, String> { // { #37 (L2) - fun getHeadOfficeDeptInfo
            val d = rawDept.trim().lowercase()
            val des = rawDesig.trim().lowercase()
            return when { // { #38 (L3) - when
                des.replace(" ", "").contains("telecaller", ignoreCase = true) || d.contains("tele") || d.contains("caller") -> Pair(9, "Telecaller")
                d.contains("manage") -> Pair(1, "Management")
                d.contains("operat") -> Pair(2, "Operations")
                d.contains("human") || d == "hr" || d.contains("hr ") -> Pair(3, "Human Resource")
                d.contains("account") || d.contains("finance") -> Pair(4, "Accounts")
                d == "mis" || d.contains("mis") -> Pair(5, "MIS")
                d.contains("audit") -> Pair(6, "Audit")
                d.contains("credit") -> Pair(7, "Credit")
                d.contains("train") -> Pair(8, "Training")
                d.contains("admin") -> Pair(10, "Administration")
                else -> Pair(99, rawDept.trim().ifBlank { "Others" })
            } // } #38 (L3) - when
        } // } #37 (L2) - fun getHeadOfficeDeptInfo

        // 1. BranchDetails ru (normalizedName -> Pair(slno, exactBranchName)) map banai neba
        val branchInfoMap = mutableMapOf<String, Pair<Int, String>>()
        conn.createStatement().use { branchStmt -> // { #39 (L2) - branchStmt.use
            try { // { #40 (L3) - try
                val branchRs = branchStmt.executeQuery("SELECT * FROM BranchDetails")
                while (branchRs.next()) { // { #41 (L4) - while branchRs.next
                    val slnoVal = (branchRs.getString(2) ?: "").trim().toIntOrNull() ?: 9999
                    val bName = (branchRs.getString(4) ?: "").trim()
                    if (bName.isNotBlank()) { // { #42 (L5) - if bName.isNotBlank
                        branchInfoMap[normalizeName(bName)] = Pair(slnoVal, bName)
                    } // } #42 (L5) - if bName.isNotBlank
                } // } #41 (L4) - while branchRs.next
                branchRs.close()
            } catch (e: Exception) { // } #40 (L3) - try / { #43 (L3) - catch Exception
                e.printStackTrace()
            } // } #43 (L3) - catch Exception
        } // } #39 (L2) - branchStmt.use

        val groupedByBranch = mutableMapOf<String, MutableList<Pair<Int, MasterDataDto>>>()
        val branchSlnoLookup = mutableMapOf<String, Int>()
        val groupedByHoDept = mutableMapOf<Pair<Int, String>, MutableList<MasterDataDto>>()

        // 2. MasterData ru active staff fetch kari Branch (1f) o Head Office Dept (1g) re bhaga kariba
        conn.createStatement().use { empStmt -> // { #44 (L2) - empStmt.use
            val seenIds = mutableSetOf<String>()
            try { // { #45 (L3) - try
                val empRs = empStmt.executeQuery("SELECT * FROM MasterData")
                while (empRs.next()) { // { #46 (L4) - while empRs.next
                    val rawDto = StaffRepository.parseMasterDataDto(empRs)
                    val empId = rawDto.empId.trim()
                    val empIdUpper = empId.uppercase()
                    val designation = rawDto.designation.trim()
                    val rawBranch = rawDto.branchName.trim()

                    if (activeSet.contains(empIdUpper) && empId.isNotBlank() && empId != "0" && seenIds.add(empIdUpper)) { // { #47 (L5) - if activeSet.contains
                        // Kevala Last 24 Salary IDs rakhiba (Ubhaya Branch Summary o Head Office pain)
                        val last24SalaryHistory = rawDto.salaryHistory
                            .split("~")
                            .filter { it.isNotBlank() }
                            .takeLast(24)
                            .joinToString("~")

                        val isTelecaller = designation.replace(" ", "").contains("telecaller", ignoreCase = true)

                        val effectiveDept = if (isTelecaller) "Telecaller" else rawDto.department
                        val dto = rawDto.copy(salaryHistory = last24SalaryHistory, department = effectiveDept)

                        val isHoOrTelecaller = rawBranch.contains("head office", ignoreCase = true) ||
                                                 rawBranch.equals("ho", ignoreCase = true) ||
                                                 isTelecaller

                        if (isHoOrTelecaller) { // { #48 (L6) - if isHoOrTelecaller
                            // 1g: Head Office staff ku Department anuyayi group kariba
                            val deptKey = getHeadOfficeDeptInfo(dto.department, dto.designation)
                            groupedByHoDept.getOrPut(deptKey) { mutableListOf() }.add(dto)
                        } else { // } #48 (L6) - if isHoOrTelecaller / { #49 (L6) - else
                            // 1f: Field Branch staff
                            val normBranch = normalizeName(rawBranch)
                            val branchMeta = branchInfoMap[normBranch]
                            val slno = branchMeta?.first ?: 9999
                            val officialBranchName = branchMeta?.second ?: rawBranch

                            branchSlnoLookup[officialBranchName] = slno

                            val d = designation.lowercase()
                            val desigRank = when { // { #50 (L7) - when desigRank
                                d == "co" || (d.contains("credit officer") && !d.contains("senior") && !d.contains("sr")) -> 1
                                d == "sco" || d.contains("senior credit officer") || d.contains("sr. credit officer") || d.contains("sr credit officer") -> 2
                                d == "abm" || d.contains("assistant branch manager") || d.contains("asst. branch manager") || d.contains("asst branch manager") -> 3
                                d == "bm" || d.contains("sr. bm") || d.contains("branch manager") -> 4
                                d == "am" || d == "sam" || d.contains("area manager") -> 5
                                d == "cm" || d.contains("cluster manager") -> 6
                                d.contains("executive") -> 7
                                else -> 99
                            } // } #50 (L7) - when desigRank

                            groupedByBranch.getOrPut(officialBranchName) { mutableListOf() }
                                .add(Pair(desigRank, dto))
                        } // } #49 (L6) - else
                    } // } #47 (L5) - if activeSet.contains
                } // } #46 (L4) - while empRs.next
                empRs.close()
            } catch (e: Exception) { // } #45 (L3) - try / { #51 (L3) - catch Exception
                e.printStackTrace()
            } // } #51 (L3) - catch Exception
        } // } #44 (L2) - empStmt.use

        // 3a. 1f Final Branch List (Sorted by slno & designation rank)
        val finalBranchSummaryList = groupedByBranch.entries
            .sortedBy { branchSlnoLookup[it.key] ?: 9999 }
            .map { (branchName, staffWithRanks) -> // { #52 (L2) - map
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
            } // } #52 (L2) - map

        // 3b. 1g Final Head Office Department List (Sorted by department rank, staff sorted by numeric salary_code_47 ascending)
        val finalHeadOfficeDeptList = groupedByHoDept.entries
            .sortedBy { it.key.first }
            .map { (deptPair, staffList) -> // { #53 (L2) - map
                val sortedStaffList = staffList.sortedBy { emp ->
                    emp.a48.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: Double.MAX_VALUE
                }

                HeadOfficeDeptReportItem(
                    departmentName = deptPair.second,
                    deptRank = deptPair.first,
                    totalStaffCount = sortedStaffList.size,
                    staffList = sortedStaffList
                )
            } // } #53 (L2) - map

        return Pair(finalBranchSummaryList, finalHeadOfficeDeptList)
    } // } #35 (L1) - fun Branchwisedata

    /**
     * 1h: Gets each employee's last Salary ID (from 1f, 1g, and 1d), fetches their last
     * salary details from SalaryHistory, compares Designation, Department, and Branch against
     * MasterData, and returns only records where at least one of those 3 fields changed.
     */
    fun compareLastSalaryWithMasterAndBuildChanges(
        conn: Connection,
        branchList1f: List<BranchSummaryReportItem>,
        hoList1g: List<HeadOfficeDeptReportItem>,
        leftSalaryCodes1d: List<String>
    ): List<SalaryMasterChangeItem> { // { #54 (L1) - fun compareLastSalaryWithMasterAndBuildChanges
        fun norm(v: String): String = v.trim().lowercase().replace(Regex("\\s+"), " ")

        // 1. Collect unique staff DTOs from 1f, 1g, and 1d (tagged with sourceGroup)
        val staffCandidates = linkedMapOf<String, Pair<String, MasterDataDto>>()

        for (branchItem in branchList1f) { // { #55 (L2) - for branchItem
            for (dto in branchItem.staffList) { // { #56 (L3) - for dto
                val code = dto.empId.trim().uppercase()
                if (code.isNotBlank() && code != "0" && !staffCandidates.containsKey(code)) { // { #57 (L4) - if !staffCandidates.containsKey
                    staffCandidates[code] = Pair("1f", dto)
                } // } #57 (L4) - if !staffCandidates.containsKey
            } // } #56 (L3) - for dto
        } // } #55 (L2) - for branchItem

        for (hoItem in hoList1g) { // { #58 (L2) - for hoItem
            for (dto in hoItem.staffList) { // { #59 (L3) - for dto
                val code = dto.empId.trim().uppercase()
                if (code.isNotBlank() && code != "0" && !staffCandidates.containsKey(code)) { // { #60 (L4) - if !staffCandidates.containsKey
                    staffCandidates[code] = Pair("1g", dto)
                } // } #60 (L4) - if !staffCandidates.containsKey
            } // } #59 (L3) - for dto
        } // } #58 (L2) - for hoItem

        val leftCodesSet = leftSalaryCodes1d
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() && it != "0" && !staffCandidates.containsKey(it) }
            .toSet()

        if (leftCodesSet.isNotEmpty()) { // { #61 (L2) - if leftCodesSet.isNotEmpty
            conn.createStatement().use { stmt -> // { #62 (L3) - stmt.use
                try { // { #63 (L4) - try
                    val rs = stmt.executeQuery("SELECT * FROM MasterData")
                    while (rs.next()) { // { #64 (L5) - while rs.next
                        val dto = StaffRepository.parseMasterDataDto(rs)
                        val code = dto.empId.trim().uppercase()
                        if (code in leftCodesSet && !staffCandidates.containsKey(code)) { // { #65 (L6) - if code in leftCodesSet
                            staffCandidates[code] = Pair("1d", dto)
                        } // } #65 (L6) - if code in leftCodesSet
                    } // } #64 (L5) - while rs.next
                    rs.close()
                } catch (e: Exception) { // } #63 (L4) - try / { #66 (L4) - catch Exception
                    e.printStackTrace()
                } // } #66 (L4) - catch Exception
            } // } #62 (L3) - stmt.use
        } // } #61 (L2) - if leftCodesSet.isNotEmpty

        if (staffCandidates.isEmpty()) return emptyList()

        // 2. Load SalaryHistory records indexed by Salary ID and latest row by emp_id_2
        data class SalarySnapshot( // { #67 (L2) - data class SalarySnapshot
            val salaryId: String,
            val empId: String,
            val month: String,
            val designation: String,
            val department: String,
            val branch: String
        )

        val salaryByIdMap = mutableMapOf<String, SalarySnapshot>()
        val latestSalaryByEmpMap = mutableMapOf<String, SalarySnapshot>()

        conn.createStatement().use { stmt -> // { #68 (L2) - stmt.use
            try { // { #69 (L3) - try
                val rs = stmt.executeQuery("SELECT rowid, * FROM SalaryHistory ORDER BY rowid ASC")
                val meta = rs.metaData
                val colCount = meta.columnCount

                fun findColIndex(vararg candidates: String): Int { // { #70 (L3) - fun findColIndex
                    for (c in candidates) { // { #71 (L4) - for candidates
                        val target = c.lowercase()
                        for (i in 1..colCount) { // { #72 (L5) - for colCount
                            val name = (meta.getColumnLabel(i) ?: meta.getColumnName(i) ?: "").lowercase()
                            if (name == target || name.contains(target)) return i
                        } // } #72 (L5) - for colCount
                    } // } #71 (L4) - for candidates
                    return -1
                } // } #70 (L3) - fun findColIndex

                val idxId     = findColIndex("id", "slno", "salary_id", "rowid")
                val idxEmpId  = findColIndex("emp_id_2", "emp_id", "code_2", "code")
                val idxMonth  = findColIndex("month")
                val idxDesig  = findColIndex("designation_7", "designation", "desig")
                val idxDept   = findColIndex("department_8", "department", "dept")
                val idxBranch = findColIndex("branch_name_9", "branch_name", "branch")

                fun safeCell(rs: ResultSet, idx: Int): String =
                    if (idx > 0) (rs.getString(idx) ?: "").trim() else ""

                while (rs.next()) { // { #73 (L3) - while rs.next
                    val salId = safeCell(rs, idxId)
                    val empId = safeCell(rs, idxEmpId).uppercase()
                    val snap = SalarySnapshot(
                        salaryId = salId,
                        empId = empId,
                        month = safeCell(rs, idxMonth),
                        designation = safeCell(rs, idxDesig),
                        department = safeCell(rs, idxDept),
                        branch = safeCell(rs, idxBranch)
                    )
                    if (salId.isNotBlank()) { // { #74 (L4) - if salId.isNotBlank
                        salaryByIdMap[salId] = snap
                    } // } #74 (L4) - if salId.isNotBlank
                    if (empId.isNotBlank() && empId != "0") { // { #75 (L4) - if empId.isNotBlank
                        latestSalaryByEmpMap[empId] = snap
                    } // } #75 (L4) - if empId.isNotBlank
                } // } #73 (L3) - while rs.next
                rs.close()
            } catch (e: Exception) { // } #69 (L3) - try / { #76 (L3) - catch Exception
                e.printStackTrace()
            } // } #76 (L3) - catch Exception
        } // } #68 (L2) - stmt.use

        // 3. Compare Designation, Department & Branch against the last Salary record
        val changeItems = mutableListOf<SalaryMasterChangeItem>()

        for ((empCode, sourceAndDto) in staffCandidates) { // { #77 (L2) - for staffCandidates
            val (sourceGroup, dto) = sourceAndDto
            val lastSalaryIdFromMaster = dto.salaryHistory
                .split("~")
                .map { it.trim() }
                .lastOrNull { it.isNotBlank() } ?: ""

            val lastSal = (if (lastSalaryIdFromMaster.isNotBlank()) salaryByIdMap[lastSalaryIdFromMaster] else null)
                ?: latestSalaryByEmpMap[empCode]
                ?: continue

            val effectiveSalaryId = lastSalaryIdFromMaster.ifBlank { lastSal.salaryId }

            val masterDesig = dto.designation.trim()
            val masterDept = dto.department.trim()
            val masterBranch = dto.branchName.trim()

            val fieldChanges = mutableListOf<FieldChangeDetail>()

            if (lastSal.designation.isNotBlank() && norm(lastSal.designation) != norm(masterDesig)) { // { #78 (L3) - if designation changed
                fieldChanges.add(
                    FieldChangeDetail(
                        field = "Designation",
                        oldSalaryValue = lastSal.designation,
                        newMasterValue = masterDesig
                    )
                )
            } // } #78 (L3) - if designation changed

            if (lastSal.department.isNotBlank() && norm(lastSal.department) != norm(masterDept)) { // { #79 (L3) - if department changed
                fieldChanges.add(
                    FieldChangeDetail(
                        field = "Department",
                        oldSalaryValue = lastSal.department,
                        newMasterValue = masterDept
                    )
                )
            } // } #79 (L3) - if department changed

            if (lastSal.branch.isNotBlank() && norm(lastSal.branch) != norm(masterBranch)) { // { #80 (L3) - if branch changed
                fieldChanges.add(
                    FieldChangeDetail(
                        field = "Branch",
                        oldSalaryValue = lastSal.branch,
                        newMasterValue = masterBranch
                    )
                )
            } // } #80 (L3) - if branch changed

            if (fieldChanges.isNotEmpty()) { // { #81 (L3) - if fieldChanges.isNotEmpty
                changeItems.add(
                    SalaryMasterChangeItem(
                        empId = empCode,
                        empName = dto.employeeName.trim(),
                        sourceGroup = sourceGroup,
                        lastSalaryId = effectiveSalaryId,
                        lastSalaryMonth = lastSal.month,
                        changedFields = fieldChanges.map { it.field },
                        changes = fieldChanges
                    )
                )
            } // } #81 (L3) - if fieldChanges.isNotEmpty
        } // } #77 (L2) - for staffCandidates

        return changeItems
    } // } #54 (L1) - fun compareLastSalaryWithMasterAndBuildChanges

    fun generateAndSaveReport(conn: Connection, x: String, y: String, fieldName: String = "a") {
    val allowedCols = setOf("a", "b", "c", "d", "e", "f", "g", "h", "i")
    if (fieldName !in allowedCols) return

    try {
        conn.createStatement().use { stmt ->
            try {
                stmt.execute("ALTER TABLE reports ADD COLUMN $fieldName TEXT")
            } catch (_: Exception) {
                // Column already exists
            }
        }

        var exists = false
        conn.prepareStatement("SELECT COUNT(*) FROM reports WHERE id = ?").use { checkStmt ->
            checkStmt.setString(1, x)
            checkStmt.executeQuery().use { rs ->
                if (rs.next()) exists = rs.getInt(1) > 0
            }
        }

        if (exists) {
            conn.prepareStatement("UPDATE reports SET $fieldName = ? WHERE id = ?").use { updateStmt ->
                updateStmt.setString(1, y)
                updateStmt.setString(2, x)
                updateStmt.executeUpdate()
            }
        } else {
            conn.prepareStatement("INSERT INTO reports (id, cr, $fieldName) VALUES (?, 'm', ?)").use { insertStmt ->
                insertStmt.setString(1, x)
                insertStmt.setString(2, y)
                insertStmt.executeUpdate()
            }
        }
        println("REPORTME SAVE OK: id=$x, col=$fieldName, len=${y.length}")
    } catch (e: Exception) {
        println("REPORTME SAVE ERROR (id=$x, col=$fieldName): ${e.message}")
        e.printStackTrace()
    }
}

    fun generateAndSaveReport(conn: Connection): ReportResult = processAndSave(conn)

    fun processAndSave(conn: Connection): ReportResult { // { #93 (L1) - fun processAndSave
        val x = "1" // 1 no record

        val totalMasterCount = getTotalMasterRecords(conn)

        // 1a
        val codesList = last_two_month_all_code(conn)
        generateAndSaveReport(conn, x, compactJson.encodeToString(codesList), "a")

        // 1b
        val activeList = Master_Active(conn)
        generateAndSaveReport(conn, x, compactJson.encodeToString(activeList), "b")

        // 1c
        val commonList = Sal_Mast_Common(codesList, activeList)
        generateAndSaveReport(conn, x, compactJson.encodeToString(commonList), "c")

        // 1d
        val leftSalaryList = Left_Salary(codesList, activeList)
        generateAndSaveReport(conn, x, compactJson.encodeToString(leftSalaryList), "d")

        // 1e
        val recentJoinedList = Recent_Joined(codesList, activeList)
        generateAndSaveReport(conn, x, compactJson.encodeToString(recentJoinedList), "e")

        // 1f (Branchwise compact JSON) & 1g (Head Office Department-wise compact JSON)
        val (bList, hList) = Branchwisedata(conn, activeList)
        generateAndSaveReport(conn, x, compactJson.encodeToString(bList), "f")
        generateAndSaveReport(conn, x, compactJson.encodeToString(hList), "g")

        // 1h: Salary vs MasterData Changes
        val salaryChangesList = compareLastSalaryWithMasterAndBuildChanges(conn, bList, hList, leftSalaryList)
        generateAndSaveReport(conn, x, compactJson.encodeToString(salaryChangesList), "h")

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
            headOfficeCodes = hList,
            totalSalaryMasterChangesCount = salaryChangesList.size,
            salaryMasterChanges = salaryChangesList
        )
    } // } #93 (L1) - fun processAndSave
} // } #1 (L1) - object ReportMeService