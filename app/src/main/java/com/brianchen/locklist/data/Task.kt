package com.brianchen.locklist.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey val id: String,
    val title: String,
    val done: Boolean,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val recurring: Boolean = false,
    val status: String = TaskStatus.MORE,
    val notes: String = "",
    val imagePaths: String = "",
    val area: String = TaskArea.PERSONAL,
    // Local changes not yet confirmed by the server.
    val dirty: Boolean = false,
    // Phone time of the last confirmed server state; 0 = never on the server.
    val syncedAt: Long = 0L,
    // Server completed_at as last seen (ISO); never re-stamped by the phone. An Undo that puts a
    // task back into Done restores the stamp it had, so it keeps its original day.
    val remoteCompletedAt: String? = null,
    // Server updated_at as last seen (ISO). Tells "the web changed this row" apart from
    // "our own push bumped it", so a phone edit is not mistaken for an older one.
    val remoteUpdatedAt: String? = null,
    // Synced fields as the server held them when the phone last saw it (SyncPlan.Content
    // JSON). The common ancestor for the per-field merge when both sides changed the row.
    val remoteBase: String? = null,
    // Synced fields of a push whose reply never came (SyncPlan.Content JSON), sent on top of
    // remoteBase. A server row equal to it holds the phone's own write, not a web edit.
    val pendingSent: String? = null
) {
    val images: List<String>
        get() = TaskStatus.imageList(imagePaths)
}
