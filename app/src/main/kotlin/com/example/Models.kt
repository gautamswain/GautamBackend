package com.example

import kotlinx.serialization.Serializable

@Serializable
data class ReminderDto(
    val id: Int,
    val id1: Int = 0,
    val id2: Int = 0,
    val title: String,
    val timeInfo: String,
    val isCompleted: Boolean = false
)

@Serializable
data class MoonPhaseResponse(
    val rawMnData: String
)

@Serializable
data class GeminiRequest(
    val prompt: String
)

@Serializable
data class GeminiResponse(
    val reply: String
)

@Serializable
data class DayDescriptionResponse(
    val date: String,
    val description: String
)

@Serializable
data class MasterDataDto(
    val id: Int = 0,
    val empId: String,
    val employeeName: String,
    val designation: String
)