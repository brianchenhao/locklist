package com.brianchen.locklist.data

import java.util.UUID
import kotlinx.coroutines.flow.Flow

class TaskRepository(private val dao: TaskDao) {
    fun observeTasks(): Flow<List<Task>> = dao.observeAll()

    suspend fun add(title: String) {
        val now = System.currentTimeMillis()
        val nextOrder = (dao.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
        dao.upsert(
            Task(
                id = UUID.randomUUID().toString(),
                title = title.trim(),
                done = false,
                sortOrder = nextOrder,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun rename(task: Task, title: String) {
        dao.upsert(task.copy(title = title.trim(), updatedAt = System.currentTimeMillis()))
    }

    suspend fun setDone(task: Task, done: Boolean) {
        dao.upsert(task.copy(done = done, updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(task: Task) {
        dao.delete(task)
    }

    suspend fun moveUp(task: Task) {
        val all = dao.getAll()
        val index = all.indexOfFirst { it.id == task.id }
        if (index <= 0) return
        swapOrder(all[index], all[index - 1])
    }

    suspend fun moveDown(task: Task) {
        val all = dao.getAll()
        val index = all.indexOfFirst { it.id == task.id }
        if (index < 0 || index >= all.lastIndex) return
        swapOrder(all[index], all[index + 1])
    }

    private suspend fun swapOrder(a: Task, b: Task) {
        val now = System.currentTimeMillis()
        dao.update(
            a.copy(sortOrder = b.sortOrder, updatedAt = now),
            b.copy(sortOrder = a.sortOrder, updatedAt = now)
        )
    }
}
