import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.content.*
import io.ktor.http.*
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.InputStream

fun Route.newJoiningRouting() {
    post("/api/turso/upload-new-joining") {
        val multipart = call.receiveMultipart()
        val employeeList = mutableListOf<MasterDataDto>()

        multipart.forEachPart { part ->
            when (part) {
                is PartData.FileItem -> {
                    val fileBytes = part.streamProvider().readBytes()
                    val inputStream: InputStream = fileBytes.inputStream()
                    val workbook = WorkbookFactory.create(inputStream)
                    val sheet = workbook.getSheetAt(0)
                    
                    // Helper function to safely get cell string value
                    fun getCellString(row: org.apache.poi.ss.usermodel.Row, index: Int): String {
                        val cell = row.getCell(index) ?: return ""
                        return when (cell.cellType) {
                            CellType.STRING -> cell.stringCellValue.trim()
                            CellType.NUMERIC -> {
                                val num = cell.numericCellValue
                                if (num == num.toLong().toDouble()) {
                                    num.toLong().toString()
                                } else {
                                    num.toString()
                                }
                            }
                            CellType.BOOLEAN -> cell.booleanCellValue.toString()
                            else -> ""
                        }
                    }

                    // Row 0 headers chadiki, row 1 ru read kariba
                    for (row in sheet.drop(1)) {
                        val empName = getCellString(row, 3) // employee_name index anusaare adjust kariparibe
                        
                        if (empName.isNotBlank()) {
                            val dto = MasterDataDto(
                                id = getCellString(row, 0),
                                slNo = getCellString(row, 1),
                                empId = getCellString(row, 2),
                                employeeName = empName,
                                fathersName = getCellString(row, 4),
                                gender = getCellString(row, 5),
                                dob = getCellString(row, 6),
                                designation = getCellString(row, 7),
                                department = getCellString(row, 8),
                                branchName = getCellString(row, 9),
                                dojInAdhikar = getCellString(row, 10),
                                presentAddress = getCellString(row, 11),
                                permanentAddress = getCellString(row, 12),
                                highestEducationalQualification = getCellString(row, 13),
                                personalContactNumber = getCellString(row, 14),
                                contactNo2 = getCellString(row, 15),
                                aadharCardNo = getCellString(row, 16),
                                panCardNo = getCellString(row, 17),
                                driving = getCellString(row, 18),
                                bankName = getCellString(row, 19),
                                bankAccountNumber = getCellString(row, 20),
                                ifscCodeBankBranchName = getCellString(row, 21),
                                esiNo = getCellString(row, 22),
                                epfUanNo = getCellString(row, 23),
                                uanName = getCellString(row, 24),
                                dol = getCellString(row, 25),
                                companyNameStartingFromCurrentToOld = getCellString(row, 26),
                                designationbackcompany = getCellString(row, 27),
                                bloodGroup = getCellString(row, 28),
                                ff = getCellString(row, 29),
                                status = getCellString(row, 30),
                                interviewed = getCellString(row, 31),
                                dateOfResignation = getCellString(row, 32),
                                lastWorkingDate = getCellString(row, 33),
                                typeOfExit = getCellString(row, 34),
                                empStatus = getCellString(row, 35),
                                empStatusTemp = getCellString(row, 36),
                                slary = getCellString(row, 37),
                                mastRem = getCellString(row, 38),
                                hravariable = getCellString(row, 39),
                                daaosa = getCellString(row, 40),
                                arrearsIncrement = getCellString(row, 41),
                                tds = getCellString(row, 42),
                                staffLoan = getCellString(row, 43),
                                staffAdvance = getCellString(row, 44),
                                others = getCellString(row, 45),
                                otherEarnings = getCellString(row, 46),
                                salaryCode = getCellString(row, 47),
                                salH = getCellString(row, 48),
                                epf55 = getCellString(row, 49),
                                esi56 = getCellString(row, 50),
                                lender = getCellString(row, 51),
                                email61Personal = getCellString(row, 52),
                                offerApp = getCellString(row, 53),
                                insurancesbi = getCellString(row, 54),
                                equifax = getCellString(row, 55),
                                exitDate = getCellString(row, 56),
                                idphoto68 = getCellString(row, 57),
                                mailIdOffice = getCellString(row, 58),
                                coment77 = getCellString(row, 59),
                                documentSubmissionDate = getCellString(row, 60),
                                idPrepare = getCellString(row, 61),
                                officeWellbeings = getCellString(row, 62),
                                salaryHistory = getCellString(row, 63),
                                totalMonthOfWork = getCellString(row, 64),
                                sameBranch = getCellString(row, 65),
                                sameDesignation = getCellString(row, 66)
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
        call.respond<List<MasterDataDto>>(HttpStatusCode.OK, employeeList)
    }
}