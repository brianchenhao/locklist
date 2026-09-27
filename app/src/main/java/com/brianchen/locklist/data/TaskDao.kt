package com.brianchen.locklist.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    // Ties break on createdAt, the same way the portal orders equal sorts.
    @Query("SELECT * FROM tasks WHERE deleted = 0 ORDER BY sortOrder ASC, createdAt ASC, id ASC")
    fun observeVisible(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE deleted = 0 ORDER BY sortOrder ASC, createdAt ASC, id ASC")
    suspend fun getVisible(): List<Task>

    @Query("SELECT * FROM tasks")
    suspend fun getAll(): List<Task>

    @Query("SELECT * FROM tasks WHERE dirty = 1")
    suspend fun getDirty(): List<Task>

    @Query("SELECT COUNT(*) FROM tasks WHERE dirty = 1")
    fun observeDirtyCount(): Flow<Int>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: String): Task?

    @Query("SELECT * FROM tasks WHERE deleted = 0 AND recurring = 1 AND done = 1")
    suspend fun getRecurringDone(): List<Task>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: Task)

    /** Returns -1 when a row with this id already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(task: Task): Long

    @Query("UPDATE tasks SET recurring = :recurring WHERE id = :id")
    suspend fun setRecurring(id: String, recurring: Boolean)

    /** Records what the server now holds for this row. True whatever the phone did meanwhile. */
    @Query(
        "UPDATE tasks SET syncedAt = :syncedAt, remoteUpdatedAt = :remoteUpdatedAt, " +
            "remoteCompletedAt = :remoteCompletedAt, remoteBase = :remoteBase, " +
            "pendingSent = NULL WHERE id = :id"
    )
    suspend fun recordServerState(
        id: String,
        syncedAt: Long,
        remoteUpdatedAt: String?,
        remoteCompletedAt: String?,
        remoteBase: String?
    )

    /** Remembers what a push is about to send, in case its reply is lost. */
    @Query("UPDATE tasks SET pendingSent = :sent WHERE id = :id")
    suspend fun recordSent(id: String, sent: String?)

    /** Clears dirty only if nobody edited the row after it was pushed. */
    @Query(
        "UPDATE tasks SET dirty = 0, updatedAt = :serverUpdatedAt " +
            "WHERE id = :id AND updatedAt = :pushedUpdatedAt"
    )
    suspend fun markClean(id: String, pushedUpdatedAt: Long, serverUpdatedAt: Long): Int

    @Query(
        "UPDATE tasks SET title = :title, done = :done, sortOrder = :sortOrder, " +
            "createdAt = :createdAt, updatedAt = :updatedAt, deleted = 0, status = :status, " +
            "notes = :notes, imagePaths = :imagePaths, area = :area, dirty = 0, " +
            "syncedAt = :syncedAt, remoteCompletedAt = :remoteCompletedAt, " +
            "remoteUpdatedAt = :remoteUpdatedAt, remoteBase = :remoteBase, pendingSent = NULL " +
            "WHERE id = :id AND dirty = 0"
    )
    suspend fun applyRemoteIfClean(
        id: String,
        title: String,
        done: Boolean,
        sortOrder: Int,
        createdAt: Long,
        updatedAt: Long,
        status: String,
        notes: String,
        imagePaths: String,
        area: String,
        syncedAt: Long,
        remoteCompletedAt: String?,
        remoteUpdatedAt: String?,
        remoteBase: String?
    ): Int

    @Query(
        "UPDATE tasks SET title = :title, done = :done, sortOrder = :sortOrder, " +
            "createdAt = :createdAt, updatedAt = :updatedAt, deleted = 0, status = :status, " +
            "notes = :notes, imagePaths = :imagePaths, area = :area, dirty = 0, " +
            "syncedAt = :syncedAt, remoteCompletedAt = :remoteCompletedAt, " +
            "remoteUpdatedAt = :remoteUpdatedAt, remoteBase = :remoteBase, pendingSent = NULL " +
            "WHERE id = :id AND updatedAt = :expectedUpdatedAt"
    )
    suspend fun applyRemoteIfUnchanged(
        id: String,
        expectedUpdatedAt: Long,
        title: String,
        done: Boolean,
        sortOrder: Int,
        createdAt: Long,
        updatedAt: Long,
        status: String,
        notes: String,
        imagePaths: String,
        area: String,
        syncedAt: Long,
        remoteCompletedAt: String?,
        remoteUpdatedAt: String?,
        remoteBase: String?
    ): Int

    /**
     * Push: puts the merge of a web conflict onto the row. It stays dirty with the same
     * updatedAt so the push that follows can clear it, and only if nobody edited it meanwhile.
     */
    @Query(
        "UPDATE tasks SET title = :title, done = :done, sortOrder = :sortOrder, status = :status, " +
            "notes = :notes, imagePaths = :imagePaths, area = :area, " +
            "remoteCompletedAt = :remoteCompletedAt, remoteUpdatedAt = :remoteUpdatedAt, " +
            "remoteBase = :remoteBase, pendingSent = NULL " +
            "WHERE id = :id AND updatedAt = :expectedUpdatedAt AND deleted = 0"
    )
    suspend fun applyMergeIfUnchanged(
        id: String,
        expectedUpdatedAt: Long,
        title: String,
        done: Boolean,
        sortOrder: Int,
        status: String,
        notes: String,
        imagePaths: String,
        area: String,
        remoteCompletedAt: String?,
        remoteUpdatedAt: String?,
        remoteBase: String?
    ): Int

    /** The server row may be gone (a DELETE is going out); if the task comes back, push it as new. */
    @Query("UPDATE tasks SET syncedAt = 0, remoteUpdatedAt = NULL WHERE id = :id")
    suspend fun markNeverSynced(id: String)

    @Query("DELETE FROM tasks WHERE id = :id AND updatedAt = :expectedUpdatedAt")
    suspend fun hardDeleteIfUnchanged(id: String, expectedUpdatedAt: Long): Int

    @Query("DELETE FROM tasks WHERE id = :id AND dirty = 0")
    suspend fun hardDeleteIfClean(id: String): Int
}
