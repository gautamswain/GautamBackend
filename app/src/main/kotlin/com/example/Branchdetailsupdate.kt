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

fun Route.branchDetailsUpdateRouting() {
    post("/api/turso/Branchdetailschange") {
        val multipart = call.receiveMultipart()
        val formatter = DataFormatter()
        val processedResults = mutableListOf<Map<String, String>>()

        multipart.forEachPart { part ->
            when (part) {
                is PartData.FileItem -> {
                    val fileBytes = part.streamProvider().readBytes()
                    val inputStream: InputStream = fileBytes.inputStream()
                    val workbook = WorkbookFactory.create(inputStream)
                    val sheet = workbook.getSheetAt(0)
                    
                    // Row 4 to 24 (0-indexed ରେ row 3 ରୁ 23)
                    val startRow = 3
                    val endRow = minOf(23, sheet.lastRowNum)

                    val oToNMap = mutableMapOf<String, String>()
                    val lToKMap = mutableMapOf<String, String>()

                    // ସମସ୍ତ ଧାଡ଼ିରୁ ମ୍ୟାପିଂ ତଥ୍ୟ ପଢ଼ିବା
                    for (r in 0..sheet.lastRowNum) {
                        val row = sheet.getRow(r) ?: continue
                        val colN = formatter.formatCellValue(row.getCell(13)).trim() // Column N (Branch)
                        val colO = formatter.formatCellValue(row.getCell(14)).trim() // Column O (Number)
                        val colL = formatter.formatCellValue(row.getCell(11)).trim() // Column L (Number)
                        val colK = formatter.formatCellValue(row.getCell(10)).trim() // Column K (Field value)

                        if (colO.isNotBlank() && colN.isNotBlank()) {
                            oToNMap[colO] = colN
                        }
                        if (colL.isNotBlank() && colK.isNotBlank()) {
                            lToKMap[colL] = colK
                        }
                    }

                    // 4 to 24 row ପାଇଁ A, B, C କଲମ୍ ପ୍ରୋସେସ୍ କରିବା
                    for (rowIndex in startRow..endRow) {
                        val row = sheet.getRow(rowIndex) ?: continue
                        val colA = formatter.formatCellValue(row.getCell(0)).trim() // Column A
                        val colB = formatter.formatCellValue(row.getCell(1)).trim() // Column B (xx, yy, zz format numbers)
                        val colC = formatter.formatCellValue(row.getCell(2)).trim() // Column C (Number to match L)

                        // Col B ର ନମ୍ବରକୁ O କଲମ୍ ସହିତ ମ୍ୟାଚ୍ କରି N ରୁ ବ୍ରାଞ୍ଚ ନେବା
                        val matchedBranch = oToNMap[colB] ?: ""

                        // Col C ର ନମ୍ବରକୁ L କଲମ୍ ସହିତ ମ୍ୟାଚ୍ କରି K ଫିଲ୍ଡ ମୂଲ୍ୟ ନେବା
                        val matchedKValue = lToKMap[colC] ?: ""

                        processedResults.add(
                            mapOf(
                                "row" to (rowIndex + 1).toString(),
                                "columnA" to colA,
                                "columnB" to colB,
                                "columnC" to colC,
                                "matchedBranch" to matchedBranch,
                                "updatedKField" to matchedKValue
                            )
                        )
                    }
                    workbook.close()
                }
                else -> {}
            }
            part.dispose()
        }

        call.respond(HttpStatusCode.OK, mapOf("status" to "success", "data" to processedResults))
    }
}