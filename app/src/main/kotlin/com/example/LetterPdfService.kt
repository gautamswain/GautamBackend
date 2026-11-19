package com.example

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import java.io.ByteArrayOutputStream
import java.util.Base64

object LetterPdfService {

    private val bgBase64: String by lazy {
        val bgBytes = this::class.java.classLoader
            .getResourceAsStream("static/LHAdhikarS_page-0001.jpg")
            ?.use { it.readBytes() }
            ?: error("LHAdhikarS_page-0001.jpg not found in src/main/resources/static/!")
        Base64.getEncoder().encodeToString(bgBytes)
    }

    private val signBase64: String by lazy {
        val signBytes = this::class.java.classLoader
            .getResourceAsStream("static/hr_sign.png")
            ?.use { it.readBytes() }
        if (signBytes != null && signBytes.isNotEmpty()) {
            Base64.getEncoder().encodeToString(signBytes)
        } else {
            ""
        }
    }

    fun generateLetterPdf(
        formattedHtmlBody: String,
        refNo: String,
        date: String
    ): ByteArray {
        var html = this::class.java.classLoader
            .getResource("templates/adhikar_letterhead.html")
            ?.readText(Charsets.UTF_8)
            ?: error("adhikar_letterhead.html not found in src/main/resources/templates/!")

        val bodyWithSign = formattedHtmlBody.replace("{{signBase64}}", signBase64)

        html = html
            .replace("{{logoDataUri}}", BrandAssets.logoDataUri)
            .replace("{{bgBase64}}", bgBase64)
            .replace("{{refNo}}", refNo)
            .replace("{{date}}", date)
            .replace("{{formattedHtmlBody}}", bodyWithSign)

        val outputStream = ByteArrayOutputStream()
        PdfRendererBuilder()
            .useFastMode()
            .withHtmlContent(html, null)
            .toStream(outputStream)
            .run()

        return outputStream.toByteArray()
    }
}