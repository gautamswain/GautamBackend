import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.content.*
import io.ktor.http.*
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.InputStream

fun Route.newJoiningRouting() {
    post("/api/turso/upload-new-joining") {
        val multipart = call.receiveMultipart()
        var employeeList = mutableListOf<SearchResultDto>()

        multipart.forEachPart { part ->
            when (part) {
                is PartData.FileItem -> {
                    val fileBytes = part.streamProvider().readBytes()
                    val inputStream: InputStream = fileBytes.inputStream()
                    val workbook = WorkbookFactory.create(inputStream)
                    val sheet = workbook.getSheetAt(0)
                    
                    // Row 0 headers chadiki, row 1 ru read kariba
                    for (row in sheet.drop(1)) {
                        val empName = row.getCell(1)?.stringCellValue ?: ""
                        val designation = row.getCell(2)?.stringCellValue ?: ""
                        val branch = row.getCell(3)?.stringCellValue ?: ""
                        val phone = row.getCell(4)?.toString() ?: ""
                        
                        if (empName.isNotBlank()) {
                            val dto = SearchResultDto(
                                id = row.getCell(0)?.numericCellValue?.toInt() ?: 0,
                                employeeName = empName,
                                designation = designation,
                                branchName = branch,
                                contactNo = phone
                            )
                            employeeList.add(dto)
                        }
                    }
                    workbook.close()
                }
                else -> {}
            }
            part.dispose()
        }

        // Database re save na kari direct frontend ku list pathaiaejauchi rectification pain
        call.respond(HttpStatusCode.OK, employeeList)
    }
}