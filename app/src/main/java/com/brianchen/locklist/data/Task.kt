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
    val area: String = TaskArea.PERSONAL
) {
    val images: List<String>
        get() = TaskStatus.imageList(imagePaths)
}
