package com.brianchen.locklist.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE deleted = 0 ORDER BY sortOrder ASC")
    fun observeVisible(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE deleted = 0 ORDER BY sortOrder ASC")
    suspend fun getVisible(): List<Task>

    @Query("SELECT * FROM tasks")
    suspend fun getAll(): List<Task>

    @Query("SELECT * FROM tasks WHERE updatedAt > :since")
    suspend fun getChangedSince(since: Long): List<Task>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: String): Task?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: Task)

    @Update
    suspend fun update(vararg tasks: Task)
}
