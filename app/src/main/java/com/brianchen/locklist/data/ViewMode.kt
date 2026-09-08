package com.brianchen.locklist.data

/** One list on screen: a status column, optionally narrowed to one area. */
data class BoardCell(
    val title: String,
    val status: String,
    val area: String?
) {
    fun matches(task: Task): Boolean {
        return TaskStatus.normalize(task.status) == status &&
            (area == null || TaskArea.normalize(task.area) == area)
    }
}

private val BOARD_COLUMNS = listOf(
    TaskStatus.MORE,
    TaskStatus.ONGOING,
    TaskStatus.REVIEW,
    TaskStatus.DONE
)

/**
 * The five ways of looking at the board. Both the lock screen and the app render the same
 * mode; it is stored in AppSettings so switching in one place switches the other.
 */
enum class ViewMode(
    val label: String,
    /** null means both areas are shown. */
    val area: String?,
    val columns: List<String>
) {
    OVERVIEW("Overview", null, BOARD_COLUMNS),
    PASSION("Passion", TaskArea.PERSONAL, BOARD_COLUMNS),
    WORK("Work", TaskArea.WORK, BOARD_COLUMNS),
    ANTI_DISTRACTION("Anti-distraction", TaskArea.PERSONAL, listOf(TaskStatus.ONGOING)),
    FOCUS("Focus", TaskArea.WORK, listOf(TaskStatus.ONGOING));

    /** Tasks this mode cares about, for progress counts. */
    fun includes(task: Task): Boolean = area == null || TaskArea.normalize(task.area) == area

    /** Area a task created from this mode gets. */
    fun defaultArea(): String = area ?: TaskArea.PERSONAL

    /** Column a task created from this mode lands in. */
    fun defaultStatus(): String = columns.first()

    /**
     * Pages to swipe through. A page with four cells is drawn as a 2x2 grid, otherwise as a
     * single full-height list.
     */
    fun pages(): List<List<BoardCell>> {
        return when (this) {
            OVERVIEW -> listOf(
                listOf(
                    BoardCell("All task · Personal", TaskStatus.MORE, TaskArea.PERSONAL),
                    BoardCell("All task · Work", TaskStatus.MORE, TaskArea.WORK),
                    BoardCell("Progressing", TaskStatus.ONGOING, null),
                    BoardCell("Done", TaskStatus.DONE, null)
                ),
                listOf(BoardCell("Review", TaskStatus.REVIEW, null))
            )
            else -> columns.map { status ->
                listOf(BoardCell(TaskStatus.label(status), status, area))
            }
        }
    }

    fun pageLabels(): List<String> {
        return when (this) {
            OVERVIEW -> listOf("Board", "Review")
            else -> columns.map { TaskStatus.label(it) }
        }
    }

    companion object {
        fun fromName(name: String?): ViewMode = entries.firstOrNull { it.name == name } ?: OVERVIEW
    }
}
