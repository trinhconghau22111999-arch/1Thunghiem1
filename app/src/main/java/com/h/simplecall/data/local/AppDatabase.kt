package com.h.simplecall.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [CallHistoryEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun callHistoryDao(): CallHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "call_history.db"
                )
                    .fallbackToDestructiveMigration()
                    // WAL mode: đọc và ghi song song không chặn nhau → hiển thị nhanh hơn
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .build().also { INSTANCE = it }
            }

        /** Gọi khi app khởi động để warm-up DB connection trước, tránh delay lần đầu */
        fun warmUp(context: Context) {
            getInstance(context).callHistoryDao().count()
        }
    }
}
