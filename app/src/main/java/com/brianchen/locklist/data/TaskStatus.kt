package com.brianchen.locklist.data

object TaskStatus {
    const val ONGOING = "ongoing"
    const val MORE = "more"
    const val REVIEW = "review"
    const val DONE = "done"

    val COLUMNS = listOf(ONGOING, MORE, REVIEW, DONE)

    fun label(status: String): String {
        return when (normalize(status)) {
            ONGOING -> "Progressing"
            MORE -> "All task"
            REVIEW -> "Review"
            DONE -> "Done"
            else -> "All task"
        }
    }

    fun normalize(status: String): String {
        return if (status in COLUMNS) status else MORE
    }

    fun imageList(paths: String): List<String> {
        return paths.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun imagePaths(paths: List<String>): String {
        return paths.joinToString("\n")
    }
}
