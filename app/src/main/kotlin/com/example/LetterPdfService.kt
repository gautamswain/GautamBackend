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
        date: String,
        secretCode: String = ""
    ): ByteArray {
        var html = this::class.java.classLoader
            .getResource("templates/adhikar_letterhead.html")
            ?.readText(Charsets.UTF_8)
            ?: error("adhikar_letterhead.html not found in src/main/resources/templates/!")

        val bodyWithSign = formattedHtmlBody.replace("{{signBase64}}", signBase64)

        val secretCodeFooter = if (secretCode.isNotBlank()) {
            val qrUri = BrandAssets.generateQrDataUri(secretCode)
            val qrImg = if (qrUri.isNotBlank()) """<img src="$qrUri" alt="QR" style="width: 50px; height: 50px;" />""" else ""
            """
            <table style="width: 100%; border-collapse: collapse; margin-top: 25px; border-top: 1px solid #222; padding-top: 6px;">
                <tr>
                    <td style="width: 75%; vertical-align: middle; border: none; padding: 0;">
                        <div style="font-size: 9px; color: #6B7280; letter-spacing: 0.6px;">$secretCode</div>
                    </td>
                    <td style="width: 25%; text-align: right; vertical-align: middle; border: none; padding: 0;">
                        $qrImg
                    </td>
                </tr>
            </table>
            """.trimIndent()
        } else {
            ""
        }

        html = html
            .replace("{{logoDataUri}}", BrandAssets.logoDataUri)
            .replace("{{bgBase64}}", bgBase64)
            .replace("{{refNo}}", refNo)
            .replace("{{date}}", date)
            .replace("{{formattedHtmlBody}}", bodyWithSign)
            .replace("{{secretCodeFooter}}", secretCodeFooter)

        // XHTML Sanitization for OpenHTMLToPDF
        html = html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "<br/>")
        html = html.replace(Regex("<hr\\s*/?>", RegexOption.IGNORE_CASE), "<hr/>")
        html = html.replace(Regex("<img([^>]*?)(?<!/)>", RegexOption.IGNORE_CASE), "<img$1/>")

        html = html
            .replace("&nbsp;", "&#160;")
            .replace("&ndash;", "&#8211;")
            .replace("&mdash;", "&#8212;")
            .replace("&rsquo;", "&#8217;")
            .replace("&lsquo;", "&#8216;")
            .replace("&rdquo;", "&#8221;")
            .replace("&ldquo;", "&#8220;")

        html = html.replace(Regex("&(?!(amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);)"), "&amp;")
        html = html.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")

        val outputStream = ByteArrayOutputStream()
        PdfRendererBuilder()
            .useFastMode()
            .withHtmlContent(html, null)
            .toStream(outputStream)
            .run()

        return outputStream.toByteArray()
    }
}