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

    suspend fun add(title: String) {
        val now = System.currentTimeMillis()
        val nextOrder = (dao.getVisible().maxOfOrNull { it.sortOrder } ?: -1) + 1
        dao.upsert(
            Task(
                id = UUID.randomUUID().toString(),
                title = title.trim(),
                done = false,
                sortOrder = nextOrder,
                createdAt = now,
                updatedAt = now,
                deleted = false
            )
        )
        onChanged()
    }

    suspend fun rename(task: Task, title: String) {
        dao.upsert(task.copy(title = title.trim(), updatedAt = System.currentTimeMillis()))
        onChanged()
    }

    suspend fun setDone(task: Task, done: Boolean) {
        dao.upsert(task.copy(done = done, updatedAt = System.currentTimeMillis()))
        onChanged()
    }

    suspend fun delete(task: Task) {
        dao.upsert(task.copy(deleted = true, updatedAt = System.currentTimeMillis()))
        onChanged()
    }

    suspend fun moveUp(task: Task) {
        val visible = dao.getVisible()
        val index = visible.indexOfFirst { it.id == task.id }
        if (index <= 0) return
        swapOrder(visible[index], visible[index - 1])
        onChanged()
    }

    suspend fun moveDown(task: Task) {
        val visible = dao.getVisible()
        val index = visible.indexOfFirst { it.id == task.id }
        if (index < 0 || index >= visible.lastIndex) return
        swapOrder(visible[index], visible[index + 1])
        onChanged()
    }

    suspend fun applyRemote(remote: Task) {
        val local = dao.getById(remote.id)
        if (local != null && local.updatedAt > remote.updatedAt) return
        dao.upsert(remote)
    }

    private suspend fun swapOrder(a: Task, b: Task) {
        val now = System.currentTimeMillis()
        dao.update(
            a.copy(sortOrder = b.sortOrder, updatedAt = now),
            b.copy(sortOrder = a.sortOrder, updatedAt = now)
        )
    }
}
