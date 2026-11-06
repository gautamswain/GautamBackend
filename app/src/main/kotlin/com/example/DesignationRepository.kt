package com.example

import java.util.Locale

object DesignationRepository {
    fun getOfficialDesignations(): List<DesignationEntity> {
        val list = mutableListOf<DesignationEntity>()
        val conn = TursoDatabaseService.getConnection()
        if (conn != null) {
            try {
                val pstmt = conn.prepareStatement("SELECT * FROM Designation")
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    list.add(
                        DesignationEntity(
                            slno = rs.getString("Designationslno") ?: rs.getString(1) ?: "",
                            designation = rs.getString("designation") ?: rs.getString(2) ?: "",
                            tier = rs.getString("tier") ?: rs.getString(3) ?: "",
                            degcode = rs.getString("degcode") ?: rs.getString(4) ?: "",
                            department = rs.getString("department") ?: rs.getString(5) ?: "",
                            shtcode = rs.getString("shtcode") ?: rs.getString(6) ?: ""
                        )
                    )
                }
                rs.close()
                pstmt.close()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                conn.close()
            }
        }
        return list
    }

    // Rectify incoming raw designation against the official list
    fun rectifyDesignation(rawDesignation: String): DesignationEntity {
        val clean = rawDesignation.trim()
        val allDesignations = getOfficialDesignations()

        // 1. Exact match (case-insensitive)
        allDesignations.find { it.designation.equals(clean, ignoreCase = true) }?.let { return it }

        // 2. Normalized match (ignoring spaces, punctuation, symbols)
        val normalizedKey = clean.lowercase().replace(Regex("[^a-z0-9]"), "")
        allDesignations.find { 
            it.designation.lowercase().replace(Regex("[^a-z0-9]"), "") == normalizedKey 
        }?.let { return it }

        // 3. Fallback: Proper Case if not found in database
        val properCase = clean.lowercase().split(Regex("\\s+"))
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
            }
        return DesignationEntity(
            slno = "",
            designation = properCase,
            tier = "",
            degcode = "",
            department = "",
            shtcode = ""
        )
    }
}