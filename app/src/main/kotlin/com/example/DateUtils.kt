package com.example

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

object DateUtils {
    private val excelEpoch: LocalDate = LocalDate.of(1899, 12, 30)
    private val displayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    private val inputFormats = listOf(
        displayFmt,
        DateTimeFormatter.ofPattern("d-M-yyyy"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("d/M/yyyy"),
        DateTimeFormatter.ofPattern("dd.MM.yyyy"),
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d-MMM-yy", Locale.ENGLISH)
    )

    fun toExcelSerial(raw: String?): String {
        val clean = raw?.trim().orEmpty()
        if (clean.isBlank() || clean.equals("null", ignoreCase = true)) return ""

        val longVal = clean.toDoubleOrNull()?.toLong()
        if (longVal != null && longVal in 10000L..90000L) {
            return longVal.toString()
        }

        for (fmt in inputFormats) {
            try {
                val parsed = LocalDate.parse(clean, fmt)
                val serial = ChronoUnit.DAYS.between(excelEpoch, parsed)
                return serial.toString()
            } catch (_: Exception) {}
        }

        return clean
    }

    fun toDisplayDate(raw: String?): String {
        val clean = raw?.trim().orEmpty()
        if (clean.isBlank() || clean.equals("null", ignoreCase = true)) return ""

        val longVal = clean.toDoubleOrNull()?.toLong()
        if (longVal != null && longVal in 10000L..90000L) {
            try {
                return excelEpoch.plusDays(longVal).format(displayFmt)
            } catch (_: Exception) {}
        }

        for (fmt in inputFormats) {
            try {
                val parsed = LocalDate.parse(clean, fmt)
                return parsed.format(displayFmt)
            } catch (_: Exception) {}
        }

        return clean
    }
}