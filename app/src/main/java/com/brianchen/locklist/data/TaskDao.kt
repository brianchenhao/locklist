package com.brianchen.locklist.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<Task>>

    @Query("SELECT * FROM tasks ORDER BY sortOrder ASC")
    suspend fun getAll(): List<Task>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: Task)

    @Update
    suspend fun update(vararg tasks: Task)

    @Delete
    suspend fun delete(task: Task)
}
