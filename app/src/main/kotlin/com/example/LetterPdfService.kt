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

        val hasOwnHeader = formattedHtmlBody.contains("Ref No", ignoreCase = true) ||
            formattedHtmlBody.contains("Dept", ignoreCase = true) ||
            formattedHtmlBody.contains("Date:", ignoreCase = true) ||
            formattedHtmlBody.contains("Adhikar Finance", ignoreCase = true)

        val headerBlock = if (hasOwnHeader) {
            ""
        } else {
            """
            <div style="text-align: center; margin-bottom: 10px; border-bottom: 2px solid #E65100; padding-bottom: 5px;">
                <img src="${BrandAssets.logoDataUri}" alt="Adhikar Logo" style="height: 40px; width: auto;" />
                <div style="font-size: 14px; font-weight: bold; color: #E65100; margin-top: 4px;">Adhikar Finance</div>
            </div>
            <table class="meta-table">
                <tr>
                    <td style="text-align:left;">Ref No: $refNo</td>
                    <td style="text-align:right;">Date: $date</td>
                </tr>
            </table>
            """.trimIndent()
        }

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
            .replace("{{headerBlock}}", headerBlock)
            .replace("{{bgBase64}}", bgBase64)
            .replace("{{formattedHtmlBody}}", bodyWithSign)
            .replace("{{secretCodeFooter}}", secretCodeFooter)

        val doc = org.jsoup.Jsoup.parse(html)
        doc.outputSettings()
            .syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml)
            .escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml)
            .charset(Charsets.UTF_8)
            .prettyPrint(true)

        doc.select("style").forEach { styleEl ->
            val cssText = styleEl.data()
            styleEl.text(cssText)
        }

        val validXhtml = doc.html()
            .replace("/*<![CDATA[*/", "")
            .replace("/*]]>*/", "")
            .replace("&lt;![CDATA[", "")
            .replace("--&gt;]]&gt;", "")

        val outputStream = ByteArrayOutputStream()
        PdfRendererBuilder()
            .useFastMode()
            .withHtmlContent(validXhtml, null)
            .toStream(outputStream)
            .run()

        return outputStream.toByteArray()
    }
}