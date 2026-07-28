package com.h.simplecall.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CallHistoryDao {

    @Insert
    fun insert(entry: CallHistoryEntity): Long

    @Insert
    fun insertAll(entries: List<CallHistoryEntity>)

    @Update
    fun update(entry: CallHistoryEntity)

    @Query("SELECT COUNT(*) FROM call_history")
    fun count(): Int

    @Query("SELECT * FROM call_history WHERE id = :id LIMIT 1")
    fun getById(id: Long): CallHistoryEntity?

    /** Blocking – chỉ dùng trong background thread */
    @Query("SELECT * FROM call_history ORDER BY date DESC")
    fun getAll(): List<CallHistoryEntity>

    @Query("SELECT * FROM call_history ORDER BY date DESC LIMIT :limit")
    fun getRecent(limit: Int): List<CallHistoryEntity>

    /** Flow – Room tự emit mỗi khi DB thay đổi, không cần load lại thủ công */
    @Query("SELECT * FROM call_history ORDER BY date DESC")
    fun observeAll(): Flow<List<CallHistoryEntity>>

    @Query("SELECT * FROM call_history WHERE number LIKE :numberPattern ORDER BY date DESC")
    fun getByNumber(numberPattern: String): List<CallHistoryEntity>

    @Query("UPDATE call_history SET isNew = 0 WHERE type = :missedType AND isNew = 1")
    fun markMissedAsRead(missedType: Int)

    @Query("DELETE FROM call_history WHERE number LIKE :numberPattern")
    fun deleteByNumber(numberPattern: String)

    @Query("DELETE FROM call_history")
    fun clearAll()
}
