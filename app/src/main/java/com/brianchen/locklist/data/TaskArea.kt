package com.brianchen.locklist.data

object TaskArea {
    const val PERSONAL = "personal"
    const val WORK = "work"

    val ALL = listOf(PERSONAL, WORK)

    fun normalize(area: String?): String = if (area == WORK) WORK else PERSONAL

    fun label(area: String?): String = if (normalize(area) == WORK) "Work" else "Personal"
}
