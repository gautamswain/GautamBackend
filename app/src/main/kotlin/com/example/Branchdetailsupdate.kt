package com.example

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.content.*
import io.ktor.http.*
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.InputStream
import kotlinx.serialization.Serializable

@Serializable
data class BranchUpdateResult(
    val row: String,
    val columnA: String,
    val columnB: String,
    val columnC: String,
    val matchedBranch: String,
    val updatedKField: String,
    val dbUpdateStatus: String
)

@Serializable
data class BranchUpdateResponse(
    val status: String,
    val data: List<BranchUpdateResult>
)

fun Route.branchDetailsUpdateRouting() {
    post("/api/turso/Branchdetailschange") {
        val multipart = call.receiveMultipart()
        val formatter = DataFormatter()
        val processedResults = mutableListOf<BranchUpdateResult>()

        multipart.forEachPart { part ->
            when (part) {
                is PartData.FileItem -> {
                    val fileBytes = part.streamProvider().readBytes()
                    val inputStream: InputStream = fileBytes.inputStream()
                    val workbook = WorkbookFactory.create(inputStream)
                    val sheet = workbook.getSheetAt(0)
                    
                    val startRow = 3
                    val endRow = minOf(23, sheet.lastRowNum)

                    val oToNMap = mutableMapOf<String, String>()
                    val lToKMap = mutableMapOf<String, String>()

                    for (r in 0..sheet.lastRowNum) {
                        val row = sheet.getRow(r) ?: continue
                        val colN = formatter.formatCellValue(row.getCell(13)).trim()
                        val colO = formatter.formatCellValue(row.getCell(14)).trim()
                        val colL = formatter.formatCellValue(row.getCell(11)).trim()
                        val colK = formatter.formatCellValue(row.getCell(10)).trim()

                        if (colO.isNotBlank() && colN.isNotBlank()) {
                            oToNMap[colO] = colN
                        }
                        if (colL.isNotBlank() && colK.isNotBlank()) {
                            lToKMap[colL] = colK
                        }
                    }

                    // Turso Database Connection 
                    val conn = TursoDatabaseService.getConnection()

                    // ଡାଟାବେସ୍‌ରୁ ସମସ୍ତ ବ୍ରାଞ୍ଚ ନାମ ଫେଚ୍ କରି ମ୍ୟାପ୍ ପ୍ରସ୍ତୁତ କରିବା
                    val dbBranchMap = mutableMapOf<String, String>()
                    if (conn != null) {
                        try {
                            val stmt = conn.createStatement()
                            val rs = stmt.executeQuery("SELECT branchname FROM BranchDetails")
                            while (rs.next()) {
                                val bName = rs.getString("branchname")?.trim() ?: ""
                                if (bName.isNotBlank()) {
                                    val normalizedKey = bName.lowercase().replace(Regex("[^a-z0-9]"), "")
                                    dbBranchMap[normalizedKey] = bName
                                }
                            }
                            rs.close()
                            stmt.close()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    for (rowIndex in startRow..endRow) {
                        val row = sheet.getRow(rowIndex) ?: continue
                        val colA = formatter.formatCellValue(row.getCell(0)).trim()
                        val colB = formatter.formatCellValue(row.getCell(1)).trim()
                        val colC = formatter.formatCellValue(row.getCell(2)).trim()

                        if (colA.isBlank() && colB.isBlank() && colC.isBlank()) {
                            continue
                        }

                        val updatedKField = lToKMap[colC] ?: ""
                        var updateStatus = "Skipped"

                        if (colB.isBlank()) {
                            updateStatus = "MatchedBranch is Blank (Col B: $colB)"
                        } else if (updatedKField.isBlank()) {
                            updateStatus = "UpdatedKField is Blank (Col C: $colC)"
                        } else if (conn == null) {
                            updateStatus = "DB Connection Failed"
                        } else {
                            // କମା ଥିଲେ ଏକାଧିକ କି' (keys) କୁ ଅଲଗା କରିବା (ଯେପରିକି "6,7")
                            val branchKeys = colB.split(",").map { it.trim() }.filter { it.isNotBlank() }
                            val statuses = mutableListOf<String>()

                            for (bKey in branchKeys) {
                                val rawMatchedBranch = oToNMap[bKey] ?: bKey // ଯଦି ମ୍ୟାପ୍ ନଥାଏ ସିଧାସଳଖ ବ୍ୟବହାର କରିବ
                                val lookupKey = rawMatchedBranch.lowercase().replace(Regex("[^a-z0-9]"), "")
                                val exactDbBranch = dbBranchMap[lookupKey] ?: rawMatchedBranch

                                try {
                                    val safeColumn = updatedKField.lowercase().replace(Regex("[^a-z0-9_]"), "")
                                    val escapedVal = colA.replace("'", "''")
                                    val escapedBranch = exactDbBranch.replace("'", "''")
                                    
                                    val sql = "UPDATE BranchDetails SET $safeColumn = '$escapedVal' WHERE branchname = '$escapedBranch'"
                                    
                                    val stmt = conn.createStatement()
                                    val rowsAffected = stmt.executeUpdate(sql)
                                    stmt.close()

                                    if (rowsAffected > 0) {
                                        statuses.add("Updated ($exactDbBranch)")
                                    } else {
                                        statuses.add("Not Found ($exactDbBranch)")
                                    }
                                } catch (e: Exception) {
                                    statuses.add("Error: ${e.javaClass.simpleName}")
                                }
                            }
                            updateStatus = statuses.joinToString("; ")
                        }

                        processedResults.add(
                            BranchUpdateResult(
                                row = (rowIndex + 1).toString(),
                                columnA = colA,
                                columnB = colB,
                                columnC = colC,
                                matchedBranch = colB,
                                updatedKField = updatedKField,
                                dbUpdateStatus = updateStatus
                            )
                        )
                    }
                    try { conn?.close() } catch (_: Exception) {}
                    workbook.close()
                }
                else -> {}
            }
            part.dispose()
        }

        call.respond(HttpStatusCode.OK, BranchUpdateResponse(status = "success", data = processedResults))
    }
}
