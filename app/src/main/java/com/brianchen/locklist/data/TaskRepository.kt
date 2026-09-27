package com.brianchen.locklist.data

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Every user edit re-reads the row inside a transaction and changes only the edited fields,
 * so an old copy held by the UI can never undo a change that arrived from the web meanwhile.
 * Edits mark the row dirty; only the sync clears that flag once the server has the change.
 */
class TaskRepository(
    private val db: AppDatabase,
    private val onChanged: () -> Unit = {}
) {
    private val dao = db.taskDao()

    fun observeTasks(): Flow<List<Task>> = dao.observeVisible()

    fun observePendingCount(): Flow<Int> = dao.observeDirtyCount()

    suspend fun getAll(): List<Task> = dao.getAll()

    suspend fun getById(id: String): Task? = dao.getById(id)

    suspend fun add(
        title: String,
        status: String = TaskStatus.MORE,
        notes: String = "",
        area: String = TaskArea.PERSONAL
    ) {
        val next = TaskStatus.normalize(status)
        db.withTransaction {
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
                    area = TaskArea.normalize(area),
                    dirty = true,
                    syncedAt = 0L
                )
            )
        }
        onChanged()
    }

    /**
     * A field counts as edited only if it differs from [task], the copy the caller started
     * from; any other field keeps the row's current value, even if it changed meanwhile.
     */
    suspend fun updateDetails(
        task: Task,
        title: String,
        notes: String,
        area: String = task.area,
        recurring: Boolean = task.recurring
    ) {
        val changed = db.withTransaction {
            val current = dao.getById(task.id) ?: return@withTransaction false
            if (current.deleted) return@withTransaction false
            val nextTitle = if (title.trim() != task.title.trim()) title.trim() else current.title
            val nextNotes = if (notes.trim() != task.notes.trim()) notes.trim() else current.notes
            val nextArea = if (TaskArea.normalize(area) != TaskArea.normalize(task.area)) {
                TaskArea.normalize(area)
            } else {
                TaskArea.normalize(current.area)
            }
            val nextRecurring = if (recurring != task.recurring) recurring else current.recurring
            val synced = nextTitle != current.title ||
                nextNotes != current.notes ||
                nextArea != TaskArea.normalize(current.area)
            if (synced) {
                dao.upsert(
                    current.copy(
                        title = nextTitle,
                        notes = nextNotes,
                        area = nextArea,
                        recurring = nextRecurring,
                        dirty = true,
                        updatedAt = stamp(current)
                    )
                )
            } else if (nextRecurring != current.recurring) {
                dao.setRecurring(current.id, nextRecurring)
            }
            synced
        }
        if (changed) onChanged()
    }

    suspend fun setDone(task: Task, done: Boolean) {
        setStatus(task, if (done) TaskStatus.DONE else TaskStatus.MORE)
    }

    suspend fun setStatus(task: Task, status: String) {
        val next = TaskStatus.normalize(status)
        val changed = db.withTransaction {
            val current = dao.getById(task.id) ?: return@withTransaction false
            if (current.deleted) return@withTransaction false
            if (TaskStatus.normalize(current.status) == next && current.done == (next == TaskStatus.DONE)) {
                return@withTransaction false
            }
            val sort = if (TaskStatus.normalize(current.status) == next) {
                current.sortOrder
            } else {
                nextSort(next)
            }
            dao.upsert(
                current.copy(
                    status = next,
                    done = next == TaskStatus.DONE,
                    sortOrder = sort,
                    dirty = true,
                    updatedAt = stamp(current)
                )
            )
            true
        }
        if (changed) onChanged()
    }

    suspend fun delete(task: Task) {
        val changed = db.withTransaction {
            val current = dao.getById(task.id) ?: return@withTransaction false
            if (current.deleted) return@withTransaction false
            dao.upsert(current.copy(deleted = true, dirty = true, updatedAt = stamp(current)))
            true
        }
        if (changed) onChanged()
    }

    /**
     * Puts back the column, position and visibility a task had in [snapshot]. If the row is
     * gone locally (its delete already reached the server), the snapshot comes back as a new
     * row that the next sync inserts again under the same id. Back in Done, it keeps the
     * completed time it had, not the time of the Undo.
     */
    suspend fun undo(snapshot: Task) {
        db.withTransaction {
            val current = dao.getById(snapshot.id)
            if (current != null) {
                // A delete that was never sent keeps its sync state, so a web delete made
                // meanwhile still wins. The sync forgets the confirmation itself before it
                // sends a DELETE (markNeverSynced).
                dao.upsert(
                    current.copy(
                        status = snapshot.status,
                        done = snapshot.done,
                        sortOrder = snapshot.sortOrder,
                        deleted = false,
                        dirty = true,
                        remoteCompletedAt = if (snapshot.done) {
                            snapshot.remoteCompletedAt
                        } else {
                            current.remoteCompletedAt
                        },
                        updatedAt = stamp(current)
                    )
                )
            } else {
                dao.upsert(
                    snapshot.copy(
                        deleted = false,
                        dirty = true,
                        syncedAt = 0L,
                        remoteUpdatedAt = null,
                        pendingSent = null,
                        updatedAt = maxOf(System.currentTimeMillis(), snapshot.updatedAt + 1)
                    )
                )
            }
        }
        onChanged()
    }

    suspend fun moveUp(task: Task) = move(task, up = true)

    suspend fun moveDown(task: Task) = move(task, up = false)

    suspend fun addImage(task: Task, path: String) {
        val changed = db.withTransaction {
            val current = dao.getById(task.id) ?: return@withTransaction false
            if (path in current.images) return@withTransaction false
            dao.upsert(
                current.copy(
                    imagePaths = TaskStatus.imagePaths(current.images + path),
                    dirty = true,
                    updatedAt = stamp(current)
                )
            )
            true
        }
        if (changed) onChanged()
    }

    suspend fun removeImage(task: Task, path: String) {
        val changed = db.withTransaction {
            val current = dao.getById(task.id) ?: return@withTransaction false
            if (path !in current.images) return@withTransaction false
            dao.upsert(
                current.copy(
                    imagePaths = TaskStatus.imagePaths(current.images - path),
                    dirty = true,
                    updatedAt = stamp(current)
                )
            )
            true
        }
        if (changed) onChanged()
    }

    /** Local-only flag; the portal has no such column, so this never needs a push. */
    suspend fun setRecurring(task: Task, recurring: Boolean) {
        dao.setRecurring(task.id, recurring)
    }

    /**
     * Moves recurring tasks that were ticked before [doneBefore] back to 'All task', each at
     * the end of that column. A tick made after that moment (for example on the web early in
     * the morning) is left alone.
     */
    suspend fun resetRecurringDone(doneBefore: Long) {
        val changed = db.withTransaction {
            val due = dao.getRecurringDone().filter { it.updatedAt < doneBefore }
            due.forEach { stale ->
                val current = dao.getById(stale.id) ?: return@forEach
                dao.upsert(
                    current.copy(
                        done = false,
                        status = TaskStatus.MORE,
                        sortOrder = nextSort(TaskStatus.MORE),
                        dirty = true,
                        updatedAt = stamp(current)
                    )
                )
            }
            due.isNotEmpty()
        }
        if (changed) onChanged()
    }

    // ---- sync only: these never touch dirty rows unless guarded by the pushed updatedAt ----

    suspend fun getDirty(): List<Task> = dao.getDirty()

    suspend fun recordServerState(
        id: String,
        syncedAt: Long,
        remoteUpdatedAt: String?,
        remoteCompletedAt: String?,
        remoteBase: String?
    ) = dao.recordServerState(id, syncedAt, remoteUpdatedAt, remoteCompletedAt, remoteBase)

    suspend fun recordSent(id: String, sent: String?) = dao.recordSent(id, sent)

    /** False when the row was edited after [pushedUpdatedAt]; it then stays dirty. */
    suspend fun markClean(id: String, pushedUpdatedAt: Long, serverUpdatedAt: Long): Boolean =
        dao.markClean(id, pushedUpdatedAt, serverUpdatedAt) > 0

    /** Pull: inserts a new row or overwrites a clean one. Dirty rows are never touched. */
    suspend fun applyRemote(remote: Task): Boolean {
        if (dao.insertIfAbsent(remote.copy(dirty = false)) != -1L) return true
        return dao.applyRemoteIfClean(
            id = remote.id,
            title = remote.title,
            done = remote.done,
            sortOrder = remote.sortOrder,
            createdAt = remote.createdAt,
            updatedAt = remote.updatedAt,
            status = remote.status,
            notes = remote.notes,
            imagePaths = remote.imagePaths,
            area = remote.area,
            syncedAt = remote.syncedAt,
            remoteCompletedAt = remote.remoteCompletedAt,
            remoteUpdatedAt = remote.remoteUpdatedAt,
            remoteBase = remote.remoteBase
        ) > 0
    }

    /** Push: the web won a conflict. Replaces the local edit only if it is still the one judged. */
    suspend fun applyRemoteOver(remote: Task, expectedUpdatedAt: Long): Boolean =
        dao.applyRemoteIfUnchanged(
            id = remote.id,
            expectedUpdatedAt = expectedUpdatedAt,
            title = remote.title,
            done = remote.done,
            sortOrder = remote.sortOrder,
            createdAt = remote.createdAt,
            updatedAt = remote.updatedAt,
            status = remote.status,
            notes = remote.notes,
            imagePaths = remote.imagePaths,
            area = remote.area,
            syncedAt = remote.syncedAt,
            remoteCompletedAt = remote.remoteCompletedAt,
            remoteUpdatedAt = remote.remoteUpdatedAt,
            remoteBase = remote.remoteBase
        ) > 0

    /**
     * Push: the phone's edit merged with a web change. [merged] carries the new content and
     * the server state it was merged with; false when the row was edited meanwhile.
     */
    suspend fun applyMerge(merged: Task, expectedUpdatedAt: Long): Boolean =
        dao.applyMergeIfUnchanged(
            id = merged.id,
            expectedUpdatedAt = expectedUpdatedAt,
            title = merged.title,
            done = merged.done,
            sortOrder = merged.sortOrder,
            status = merged.status,
            notes = merged.notes,
            imagePaths = merged.imagePaths,
            area = merged.area,
            remoteCompletedAt = merged.remoteCompletedAt,
            remoteUpdatedAt = merged.remoteUpdatedAt,
            remoteBase = merged.remoteBase
        ) > 0

    /** A DELETE is about to go out: if the task comes back (Undo), it is inserted again. */
    suspend fun markNeverSynced(id: String) = dao.markNeverSynced(id)

    suspend fun hardDeleteIfUnchanged(id: String, expectedUpdatedAt: Long): Boolean =
        dao.hardDeleteIfUnchanged(id, expectedUpdatedAt) > 0

    suspend fun hardDeleteIfClean(id: String): Boolean = dao.hardDeleteIfClean(id) > 0

    // ---- helpers ----

    private suspend fun move(task: Task, up: Boolean) {
        val changed = db.withTransaction {
            val current = dao.getById(task.id) ?: return@withTransaction false
            if (current.deleted) return@withTransaction false
            val siblings = siblings(current.status)
            val index = siblings.indexOfFirst { it.id == current.id }
            val otherIndex = if (up) index - 1 else index + 1
            if (index < 0 || otherIndex !in siblings.indices) return@withTransaction false
            val neighbor = siblings[otherIndex]
            // Swap by position, not by raw value: equal sorts would make a value swap a no-op
            // (same nudge as the portal's reorderTask).
            var taskSort = neighbor.sortOrder
            var neighborSort = current.sortOrder
            if (taskSort == neighborSort) {
                if (up) neighborSort = taskSort + 1 else taskSort = neighborSort + 1
            }
            dao.upsert(current.copy(sortOrder = taskSort, dirty = true, updatedAt = stamp(current)))
            dao.upsert(
                neighbor.copy(sortOrder = neighborSort, dirty = true, updatedAt = stamp(neighbor))
            )
            true
        }
        if (changed) onChanged()
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

    // Strictly increasing per row: the sync's "unchanged since push" guard compares updatedAt,
    // so two edits must never share a value even if the clock stalls or steps back.
    private fun stamp(current: Task): Long =
        maxOf(System.currentTimeMillis(), current.updatedAt + 1)
}
