package com.h.simplecall.call

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Metadata 1 bản ghi âm đọc được từ RecordingsProvider của VOX Ghi Âm. */
data class VoxRecording(
    val name: String,
    val timestampMs: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val contentUri: Uri
)

/**
 * VOX Ghi Âm ([RECORDER_PACKAGE]) giờ đã mở API cho app ngoài điều khiển (không cần mở giao diện
 * của nó lên nữa):
 *   - Bật/tắt ghi âm nền: gửi Intent tường minh tới RecordingService (ACTION_START/ACTION_STOP).
 *   - Liệt kê + đọc file ghi âm: query/openFile qua RecordingsProvider (content://com.vox.ghiam.recordings).
 *
 * VOX không gắn số điện thoại vào từng bản ghi (nó không biết gì về cuộc gọi, chỉ biết
 * bắt đầu/dừng ghi khi được yêu cầu) - nên để tra "bản ghi của số X" ta phải tự lưu lại MỐC THỜI
 * GIAN bắt đầu ghi cho từng số ngay tại app này (xem [addMarker]), rồi đối chiếu với timestamp
 * của từng file lấy được từ RecordingsProvider ở [recordingsForNumber].
 */
object CallRecordingManager {

    const val RECORDER_PACKAGE = "com.vox.ghiam"
    const val RECORDER_APP_NAME = "VOX Ghi Âm"
    private const val SERVICE_CLASS = "com.vox.ghiam.RecordingService"
    private const val ACTION_START = "START"
    private const val ACTION_STOP = "STOP"
    private val RECORDINGS_LIST_URI: Uri = Uri.parse("content://com.vox.ghiam.recordings/list")

    private const val PREFS = "call_recording_prefs"
    private const val KEY_ENABLED = "auto_record_enabled"
    private const val KEY_AUTO_SPEAKER = "auto_speaker_when_recording"
    private const val KEY_MARKERS = "record_markers" // JSON [{number, startedAtMs}]

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Có tự động ghi âm mỗi khi có cuộc gọi bắt đầu hay không (xem CallStateReceiver.kt). */
    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** "Tự động bật loa ngoài khi ghi âm" - mặc định TẮT vì nó chủ động đổi trải nghiệm cuộc gọi
     *  (chuyển sang loa ngoài) mà người dùng không thao tác gì; chỉ nên bật nếu đã thử ghi âm mà
     *  không có tiếng đối phương (xem ghi chú trong CallStateReceiver.kt). */
    fun isAutoSpeakerEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO_SPEAKER, false)

    fun setAutoSpeakerEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_SPEAKER, enabled).apply()
    }

    /** VOX Ghi Âm đã được cài trên máy chưa. */
    fun isRecorderAppInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getApplicationInfo(RECORDER_PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** Mở thẳng VOX Ghi Âm lên - chỉ dùng khi người dùng CHỦ ĐỘNG muốn xem/quản lý app đó,
     *  KHÔNG dùng cho luồng tự động ghi âm nữa. */
    fun openRecorderApp(ctx: Context): Boolean {
        val pm = ctx.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(RECORDER_PACKAGE) ?: return false
        return try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            ctx.startActivity(launchIntent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Yêu cầu VOX bắt đầu ghi âm NỀN, KHÔNG mở giao diện của nó lên (trước đây openRecorderApp()
     *  bị gọi ngay lúc bắt máy, kéo hẳn app VOX lên đè lên màn hình gọi, rất khó chịu). Ghi lại
     *  mốc thời gian cho số này để sau đối chiếu ra đúng file ghi âm ở [recordingsForNumber]. */
    fun startRecording(ctx: Context, number: String) {
        if (!isRecorderAppInstalled(ctx)) return
        try {
            val intent = Intent().apply {
                setClassName(RECORDER_PACKAGE, SERVICE_CLASS)
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
            else ctx.startService(intent)
            addMarker(ctx, number, System.currentTimeMillis())
        } catch (_: Exception) {
            // Máy chặn khởi động service nền (hiếm, thường do OEM giới hạn) - im lặng bỏ qua,
            // không có ghi âm cho cuộc gọi này thay vì crash cả app đang gọi dở.
        }
    }

    fun stopRecording(ctx: Context) {
        if (!isRecorderAppInstalled(ctx)) return
        try {
            ctx.startService(Intent().apply {
                setClassName(RECORDER_PACKAGE, SERVICE_CLASS)
                action = ACTION_STOP
            })
        } catch (_: Exception) {}
    }

    /** Đọc toàn bộ danh sách bản ghi âm hiện có từ VOX qua ContentProvider, mới nhất trước
     *  (RecordingsProvider đã tự sắp xếp sẵn theo thời gian giảm dần). */
    fun listAllRecordings(ctx: Context): List<VoxRecording> {
        val list = mutableListOf<VoxRecording>()
        try {
            ctx.contentResolver.query(RECORDINGS_LIST_URI, null, null, null, null)?.use { c ->
                val iName = c.getColumnIndex("name")
                val iTs   = c.getColumnIndex("timestamp")
                val iDur  = c.getColumnIndex("duration")
                val iSize = c.getColumnIndex("size")
                val iUri  = c.getColumnIndex("uri")
                while (c.moveToNext()) {
                    val name = if (iName >= 0) c.getString(iName) else null
                    val uriStr = if (iUri >= 0) c.getString(iUri) else null
                    if (name == null || uriStr == null) continue
                    list.add(VoxRecording(
                        name = name,
                        timestampMs = if (iTs >= 0) c.getLong(iTs) else 0L,
                        durationMs = if (iDur >= 0) c.getLong(iDur) else 0L,
                        sizeBytes = if (iSize >= 0) c.getLong(iSize) else 0L,
                        contentUri = Uri.parse(uriStr)
                    ))
                }
            }
        } catch (_: Exception) {
            // VOX chưa cài, hoặc chưa từng chạy lần nào nên ContentProvider của nó chưa sẵn
            // sàng - trả về danh sách rỗng thay vì crash màn hình đang hiển thị.
        }
        return list
    }

    /** Bản ghi âm của riêng 1 số điện thoại: đối chiếu mốc "bắt đầu ghi lúc nào" đã tự lưu ở
     *  [startRecording] với timestamp thật của từng file - vì VOX không biết số điện thoại nào
     *  cả, chỉ file này CÓ THỂ ứng với cuộc gọi tới số X nếu nó được tạo trong vòng 10 phút SAU
     *  mốc bắt đầu ghi cho số đó (biên độ rộng để không bỏ sót cuộc gọi dài). */
    fun recordingsForNumber(ctx: Context, number: String): List<VoxRecording> {
        val markers = readMarkers(ctx).filter { it.first == number }
        if (markers.isEmpty()) return emptyList()
        val all = listAllRecordings(ctx)
        return all.filter { rec ->
            markers.any { (_, startedAt) -> rec.timestampMs in startedAt..(startedAt + 10 * 60 * 1000L) }
        }
    }

    private fun addMarker(ctx: Context, number: String, startedAtMs: Long) {
        val arr = readMarkersRaw(ctx)
        // Chỉ giữ lại tối đa 200 mốc gần nhất, tránh cấu hình phình to vô hạn theo thời gian.
        while (arr.length() >= 200) arr.remove(0)
        arr.put(JSONObject().apply { put("number", number); put("startedAtMs", startedAtMs) })
        prefs(ctx).edit().putString(KEY_MARKERS, arr.toString()).apply()
    }

    private fun readMarkersRaw(ctx: Context): JSONArray = try {
        JSONArray(prefs(ctx).getString(KEY_MARKERS, "[]"))
    } catch (_: Exception) {
        JSONArray()
    }

    private fun readMarkers(ctx: Context): List<Pair<String, Long>> {
        val arr = readMarkersRaw(ctx)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (!o.has("number")) return@mapNotNull null
            Pair(o.optString("number"), o.optLong("startedAtMs"))
        }
    }
}
