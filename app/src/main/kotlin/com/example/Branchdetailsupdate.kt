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

                    for (rowIndex in startRow..endRow) {
                        val row = sheet.getRow(rowIndex) ?: continue
                        val colA = formatter.formatCellValue(row.getCell(0)).trim()
                        val colB = formatter.formatCellValue(row.getCell(1)).trim()
                        val colC = formatter.formatCellValue(row.getCell(2)).trim()

                        val matchedBranch = oToNMap[colB] ?: ""
                        val updatedKField = lToKMap[colC] ?: ""

                        var updateStatus = "Skipped"

                        // ଡାଟାବେସ୍ ଅପଡେଟ୍ ପ୍ରକ୍ରିୟା (BranchDetails Table)
                        if (matchedBranch.isNotBlank() && updatedKField.isNotBlank() && conn != null) {
                            try {
                                // କଲମ୍ ନାମକୁ ସୁରକ୍ଷିତ ଭାବରେ ସାନିଟାଇଜ୍ କରିବା
                                val safeColumn = updatedKField.lowercase().replace(Regex("[^a-z0-9_]"), "")
                                val sql = "UPDATE BranchDetails SET $safeColumn = ? WHERE branchnmae = ?"
                                
                                val pstmt = conn.prepareStatement(sql)
                                pstmt.setString(1, colA)
                                pstmt.setString(2, matchedBranch)
                                val rowsAffected = pstmt.executeUpdate()
                                pstmt.close()

                                updateStatus = if (rowsAffected > 0) "Updated Successfully" else "Branch Not Found in DB"
                            } catch (e: Exception) {
                                updateStatus = "Error: ${e.localizedMessage}"
                            }
                        }

                        processedResults.add(
                            BranchUpdateResult(
                                row = (rowIndex + 1).toString(),
                                columnA = colA,
                                columnB = colB,
                                columnC = colC,
                                matchedBranch = matchedBranch,
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
