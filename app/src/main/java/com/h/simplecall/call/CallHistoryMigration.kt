package com.h.simplecall.call

import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.h.simplecall.data.local.AppDatabase
import com.h.simplecall.data.local.CallHistoryEntity

/**
 * Di trú lịch sử cuộc gọi CŨ (đã có sẵn từ trước khi app chuyển sang tự lưu lịch sử bằng Room)
 * từ CallLog provider của hệ thống sang DB nội bộ của app - CHẠY ĐÚNG 1 LẦN.
 *
 * LƯU Ý QUAN TRỌNG: dữ liệu cũ trong CallLog hệ thống chỉ có "số thật sự đã kết nối", KHÔNG
 * có khái niệm "số hiển thị trên màn hình gọi" (vì tính năng đó mới được thêm). Nên các dòng
 * lịch sử được di trú sẽ dùng tạm số của CallLog làm số hiển thị - chỉ những cuộc gọi MỚI sau
 * khi cập nhật mới đảm bảo đúng 100% quy tắc "lưu theo số hiển thị trên màn hình gọi".
 */
object CallHistoryMigration {

    private const val PREFS = "call_history_migration"
    private const val KEY_DONE = "migrated_from_system_call_log"

    /** Gọi ở NỀN (không phải main thread) - hàm này tự kiểm tra điều kiện và bỏ qua an toàn
     *  nếu đã di trú rồi, chưa có quyền đọc CallLog, hoặc DB app đã có dữ liệu sẵn.
     *
     *  TOÀN BỘ thân hàm được bọc try/catch: đây là tác vụ chạy trên bgExecutor (không phải main
     *  thread), nhưng trên Android 1 exception không bắt được ở BẤT KỲ thread nào cũng làm CRASH
     *  TOÀN BỘ app, không riêng gì thread đó. Vì đây chỉ là bước "di trú tiện lợi" (không bắt
     *  buộc để app hoạt động), thà bỏ qua và log lỗi còn hơn làm sập cả ứng dụng. */
    fun runIfNeeded(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_DONE, false)) return

            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) {
                // Chưa được cấp quyền - có thể người dùng cấp sau, nên KHÔNG đánh dấu "đã xong"
                // để lần khởi động kế tiếp còn thử lại.
                return
            }

            val dao = AppDatabase.getInstance(context).callHistoryDao()
            val legacyEntries = readLegacyCallLog(context)
            if (legacyEntries.isNotEmpty()) dao.insertAll(legacyEntries)
            prefs.edit().putBoolean(KEY_DONE, true).apply()
        } catch (e: Exception) {
            Log.e("CallHistoryMigration", "Di trú lịch sử cũ thất bại, bỏ qua an toàn", e)
        }
    }

    private fun readLegacyCallLog(context: Context): List<CallHistoryEntity> {
        val list = mutableListOf<CallHistoryEntity>()
        try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER,
                    CallLog.Calls.DATE, CallLog.Calls.TYPE,
                    CallLog.Calls.DURATION, CallLog.Calls.PHONE_ACCOUNT_ID,
                    CallLog.Calls.NEW
                ),
                null, null, "${CallLog.Calls.DATE} DESC"
            ) ?: return list
            cursor.use {
                val iName = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val iNum  = it.getColumnIndex(CallLog.Calls.NUMBER)
                val iDate = it.getColumnIndex(CallLog.Calls.DATE)
                val iType = it.getColumnIndex(CallLog.Calls.TYPE)
                val iDur  = it.getColumnIndex(CallLog.Calls.DURATION)
                val iAcct = it.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
                val iNew  = it.getColumnIndex(CallLog.Calls.NEW)
                while (it.moveToNext()) {
                    val name = if (iName >= 0) it.getString(iName) ?: "" else ""
                    val type = if (iType >= 0) it.getInt(iType) else CallLog.Calls.OUTGOING_TYPE
                    val acctId = if (iAcct >= 0) it.getString(iAcct) ?: "" else ""
                    val simSlot = resolveSimSlot(context, acctId)
                    list.add(
                        CallHistoryEntity(
                            name = name,
                            number = if (iNum >= 0) it.getString(iNum) ?: "" else "",
                            date = if (iDate >= 0) it.getLong(iDate) else 0L,
                            type = type,
                            duration = if (iDur >= 0) it.getLong(iDur) else 0L,
                            simSlot = simSlot,
                            numberType = if (name.isNotEmpty()) "Di động" else "Việt Nam",
                            isNew = type == CallLog.Calls.MISSED_TYPE &&
                                iNew >= 0 && it.getInt(iNew) == 1
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
            // Thiếu quyền tại thời điểm đọc thực tế (hiếm, do checkSelfPermission đã qua ở trên)
        }
        return list
    }

    private fun resolveSimSlot(context: Context, acctId: String): Int? {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val subId = acctId.toIntOrNull() ?: return null
            context.getSystemService(SubscriptionManager::class.java)
                ?.getActiveSubscriptionInfo(subId)?.simSlotIndex?.takeIf { it >= 0 }
        } catch (_: Exception) {
            null
        }
    }
}
