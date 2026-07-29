package com.h.simplecall.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Cache lưu ĐĨA (file JSON trong bộ nhớ riêng của app, KHÔNG phải RAM) cho danh sách "Gần đây".
 * Khác với cache RAM (DialerFragment.cachedRecents - mất khi tắt hẳn app/kill process), cache
 * này còn nguyên qua mọi lần mở app, kể cả sau khi tắt hẳn - mở app lên là thấy NGAY lịch sử cũ,
 * không phải đợi đọc lại từ CallLog hệ thống. Dùng org.json có sẵn trong Android SDK, không cần
 * thêm thư viện ngoài.
 */
object CallLogCache {
    private const val FILE_NAME = "call_log_cache.json"

    /** Đọc cache đĩa. Trả về null nếu chưa từng lưu hoặc file hỏng (khi đó fragment sẽ tự đọc
     *  lại bình thường từ CallLog hệ thống, không có gì bị mất). */
    fun load(context: Context): List<CallLogEntry>? {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return null
            val arr = JSONArray(file.readText())
            val list = ArrayList<CallLogEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    CallLogEntry(
                        name = o.optString("name", ""),
                        number = o.optString("number", ""),
                        type = o.optInt("type", 0),
                        date = o.optLong("date", 0L),
                        simSlot = if (o.isNull("simSlot")) null else o.optInt("simSlot"),
                        numberType = o.optString("numberType", ""),
                        duration = o.optLong("duration", 0L)
                    )
                )
            }
            list
        } catch (_: Exception) {
            null
        }
    }

    /** Ghi đè cache đĩa với danh sách mới nhất vừa đọc xong từ CallLog hệ thống. */
    fun save(context: Context, entries: List<CallLogEntry>) {
        try {
            val arr = JSONArray()
            for (e in entries) {
                val o = JSONObject()
                o.put("name", e.name)
                o.put("number", e.number)
                o.put("type", e.type)
                o.put("date", e.date)
                o.put("simSlot", e.simSlot ?: JSONObject.NULL)
                o.put("numberType", e.numberType)
                o.put("duration", e.duration)
                arr.put(o)
            }
            File(context.filesDir, FILE_NAME).writeText(arr.toString())
        } catch (_: Exception) {
            // Ghi cache lỗi không quan trọng - lần mở app sau chỉ chậm lại như trước đây, không
            // crash, không mất dữ liệu thật (CallLog hệ thống vẫn còn nguyên).
        }
    }
}
