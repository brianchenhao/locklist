package com.brianchen.locklist.data

import java.util.UUID
import kotlinx.coroutines.flow.Flow

class TaskRepository(
    private val dao: TaskDao,
    private val onChanged: () -> Unit = {}
) {
    fun observeTasks(): Flow<List<Task>> = dao.observeVisible()

    suspend fun getChangedSince(since: Long): List<Task> = dao.getChangedSince(since)

    suspend fun getAll(): List<Task> = dao.getAll()

    suspend fun getById(id: String): Task? = dao.getById(id)

    suspend fun add(
        title: String,
        status: String = TaskStatus.MORE,
        notes: String = "",
        area: String = TaskArea.PERSONAL
    ) {
        val next = TaskStatus.normalize(status)
        val now = System.currentTimeMillis()
        dao.upsert(
            Task(
                id = UUID.randomUUID().toString(),
                title = title.trim(),
                done = next == TaskStatus.DONE,
                sortOrder = nextSort(next),
                createdAt = now,
                updatedAt = now,
                deleted = false,
                recurring = false,
                status = next,
                notes = notes.trim(),
                imagePaths = "",
                area = TaskArea.normalize(area)
            )
        )
        onChanged()
    }

    suspend fun setArea(task: Task, area: String) {
        dao.upsert(
            task.copy(area = TaskArea.normalize(area), updatedAt = System.currentTimeMillis())
        )
        onChanged()
    }

    suspend fun rename(task: Task, title: String) {
        dao.upsert(task.copy(title = title.trim(), updatedAt = System.currentTimeMillis()))
        onChanged()
    }

    suspend fun setNotes(task: Task, notes: String) {
        dao.upsert(task.copy(notes = notes, updatedAt = System.currentTimeMillis()))
        onChanged()
    }

    suspend fun updateDetails(
        task: Task,
        title: String,
        notes: String,
        area: String = task.area
    ) {
        dao.upsert(
            task.copy(
                title = title.trim(),
                notes = notes.trim(),
                area = TaskArea.normalize(area),
                updatedAt = System.currentTimeMillis()
            )
        )
        onChanged()
    }

    suspend fun setDone(task: Task, done: Boolean) {
        setStatus(task, if (done) TaskStatus.DONE else TaskStatus.MORE)
    }

    suspend fun setStatus(task: Task, status: String) {
        val next = TaskStatus.normalize(status)
        val now = System.currentTimeMillis()
        val sort = if (TaskStatus.normalize(task.status) == next) {
            task.sortOrder
        } else {
            nextSort(next)
        }
        dao.upsert(
            task.copy(
                status = next,
                done = next == TaskStatus.DONE,
                sortOrder = sort,
                updatedAt = now
            )
        )
        onChanged()
    }

    suspend fun delete(task: Task) {
        dao.upsert(task.copy(deleted = true, updatedAt = System.currentTimeMillis()))
        onChanged()
    }

    suspend fun moveUp(task: Task) {
        val siblings = siblings(task.status)
        val index = siblings.indexOfFirst { it.id == task.id }
        if (index <= 0) return
        swapOrder(siblings[index], siblings[index - 1])
        onChanged()
    }

    suspend fun moveDown(task: Task) {
        val siblings = siblings(task.status)
        val index = siblings.indexOfFirst { it.id == task.id }
        if (index < 0 || index >= siblings.lastIndex) return
        swapOrder(siblings[index], siblings[index + 1])
        onChanged()
    }

    suspend fun setRecurring(task: Task, recurring: Boolean) {
        dao.upsert(task.copy(recurring = recurring))
    }

    suspend fun resetRecurringDone() {
        val now = System.currentTimeMillis()
        val due = dao.getRecurringDone()
        if (due.isEmpty()) return
        due.forEach {
            dao.upsert(
                it.copy(
                    done = false,
                    status = TaskStatus.MORE,
                    updatedAt = now
                )
            )
        }
        onChanged()
    }

    suspend fun applyRemote(remote: Task) {
        val local = dao.getById(remote.id)
        if (local != null && local.updatedAt > remote.updatedAt) return
        dao.upsert(remote.copy(recurring = local?.recurring ?: remote.recurring))
    }

    private suspend fun nextSort(status: String): Int {
        val normalized = TaskStatus.normalize(status)
        val max = dao.getVisible()
            .filter { TaskStatus.normalize(it.status) == normalized }
            .maxOfOrNull { it.sortOrder } ?: 0
        return max + 1
    }

    private suspend fun siblings(status: String): List<Task> {
        val normalized = TaskStatus.normalize(status)
        return dao.getVisible().filter { TaskStatus.normalize(it.status) == normalized }
    }

    private suspend fun swapOrder(a: Task, b: Task) {
        val now = System.currentTimeMillis()
        dao.update(
            a.copy(sortOrder = b.sortOrder, updatedAt = now),
            b.copy(sortOrder = a.sortOrder, updatedAt = now)
        )
    }
}
