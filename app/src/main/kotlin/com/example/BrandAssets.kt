package com.example

import java.util.Base64

object BrandAssets {
    val logoDataUri: String by lazy {
        try {
            val bytes = this::class.java.classLoader
                .getResourceAsStream("images/adhikar_logo25.png")
                ?.use { it.readBytes() }
            if (bytes != null && bytes.isNotEmpty()) {
                val base64 = Base64.getEncoder().encodeToString(bytes)
                "data:image/png;base64,$base64"
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun renderDocumentHeader(documentTitle: String, secretCode: String = ""): String {
        val secretHtml = if (secretCode.isNotBlank()) {
            """<div style="text-align: right; font-size: 9px; color: #6B7280; letter-spacing: 0.6px; margin-bottom: 4px;">$secretCode</div>"""
        } else {
            ""
        }

        val logoImg = if (logoDataUri.isNotBlank()) {
            """<img src="$logoDataUri" alt="Adhikar Logo" style="height: 44px; width: auto;" />"""
        } else {
            """<span style="font-size: 16px; font-weight: bold; color: #E65100;">Adhikar Finance</span>"""
        }

        return """
            $secretHtml
            <table style="width: 100%; border-collapse: collapse; border-bottom: 2px solid #E65100; padding-bottom: 8px; margin-bottom: 12px;">
                <tr>
                    <td style="width: 25%; vertical-align: middle; border: none; padding: 0;">
                        $logoImg
                    </td>
                    <td style="width: 75%; text-align: right; vertical-align: middle; border: none; padding: 0;">
                        <div style="font-size: 15px; font-weight: bold; color: #E65100; margin: 0;">Adhikar Finance</div>
                        <div style="font-size: 9px; color: #4B5563; margin: 3px 0 0 0;">Plot No. 77/180/970, Subudhipur, Tamando, Kalinga Vihar, Bhubaneswar-751028, Odisha</div>
                    </td>
                </tr>
            </table>
            <div style="font-size: 13px; font-weight: bold; background: #E65100; color: #fff; text-align: center; padding: 5px; margin-top: 8px; margin-bottom: 12px;">
                $documentTitle
            </div>
        """.trimIndent()
    }
}