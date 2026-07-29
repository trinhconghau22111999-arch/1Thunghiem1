package com.h.simplecall.data

import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.h.simplecall.data.CallLogEntry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Cache nhật ký cuộc gọi dùng chung toàn app.
 * - peek()       : lấy cache ngay lập tức (null nếu chưa có)
 * - getAll()     : trả cache nếu có, không thì load từ hệ thống rồi lưu cache
 * - invalidate() : xóa cache để lần sau load lại (gọi khi có cuộc gọi mới)
 * - loadBatched(): load và gọi callback từng batch 50 item → UI hiện dần từ trên xuống
 */
object CallLogRepository {

    @Volatile private var cache: List<CallLogEntry>? = null
    private val mutex = Mutex()

    fun peek(): List<CallLogEntry>? = cache

    suspend fun getAll(context: Context): List<CallLogEntry> {
        cache?.let { return it }
        if (!hasPermission(context)) return emptyList()
        return mutex.withLock {
            cache ?: loadFromSystem(context).also { cache = it }
        }
    }

    /**
     * Load toàn bộ rồi gọi onBatch mỗi 50 item để UI hiện dần.
     * Kết thúc: gọi onBatch với full list và lưu vào cache.
     */
    suspend fun loadBatched(
        context: Context,
        onBatch: suspend (List<CallLogEntry>) -> Unit
    ) {
        if (!hasPermission(context)) return

        // Nếu đã có cache → hiện ngay, rồi vẫn refresh nền
        cache?.let { onBatch(it) }

        mutex.withLock {
            val all = loadFromSystem(context)
            cache = all
            onBatch(all)
        }
    }

    fun invalidate() { cache = null }

    private fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun loadFromSystem(context: Context): List<CallLogEntry> {
        val list = mutableListOf<CallLogEntry>()
        try {
            val cur = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.DATE,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.PHONE_ACCOUNT_ID,
                    CallLog.Calls.CACHED_NUMBER_TYPE,
                    CallLog.Calls.CACHED_NUMBER_LABEL
                ),
                null, null,
                "${CallLog.Calls.DATE} DESC"
            ) ?: return list

            cur.use {
                val iName    = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val iNum     = it.getColumnIndex(CallLog.Calls.NUMBER)
                val iDate    = it.getColumnIndex(CallLog.Calls.DATE)
                val iType    = it.getColumnIndex(CallLog.Calls.TYPE)
                val iDur     = it.getColumnIndex(CallLog.Calls.DURATION)
                val iAcct    = it.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
                val iNumType = it.getColumnIndex(CallLog.Calls.CACHED_NUMBER_TYPE)
                val iLabel   = it.getColumnIndex(CallLog.Calls.CACHED_NUMBER_LABEL)

                while (it.moveToNext()) {
                    val acctId = if (iAcct >= 0) it.getString(iAcct) ?: "" else ""
                    val simSlot: Int? = try {
                        val subId = acctId.toIntOrNull()
                        if (subId != null) {
                            val sm = context.getSystemService(android.telephony.SubscriptionManager::class.java)
                            sm?.getActiveSubscriptionInfo(subId)?.simSlotIndex?.takeIf { idx -> idx >= 0 }
                        } else null
                    } catch (_: Exception) { null }

                    val numType = if (iNumType >= 0) it.getInt(iNumType) else 0
                    val label   = if (iLabel >= 0) it.getString(iLabel) ?: "" else ""
                    val typeLabel = when (numType) {
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Di động"
                        ContactsContract.CommonDataKinds.Phone.TYPE_HOME   -> "Nhà riêng"
                        ContactsContract.CommonDataKinds.Phone.TYPE_WORK   -> "Cơ quan"
                        ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM -> label.ifEmpty { "Di động" }
                        else -> "Di động"
                    }

                    // Nếu chưa có tên cache → tra thêm từ ContactsRepository
                    var name = if (iName >= 0) it.getString(iName) ?: "" else ""
                    if (name.isBlank()) {
                        name = ContactsRepository.lookupNameByNumber(context, it.getString(iNum) ?: "") ?: ""
                    }

                    list.add(CallLogEntry(
                        name        = name,
                        number      = it.getString(iNum) ?: "",
                        date        = it.getLong(iDate),
                        type        = it.getInt(iType),
                        simSlot     = simSlot,
                        numberType  = typeLabel
                    ))
                }
            }
        } catch (_: Exception) {}
        return list
    }
}
