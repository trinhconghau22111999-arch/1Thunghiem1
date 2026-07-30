package com.h.simplecall.call

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Lưu cài đặt "Tự động ghi âm cuộc gọi" + danh mục các bản ghi âm đã tạo.
 *
 * KHÔNG dùng Room DB (đúng hướng đi hiện tại của app - xem SimpleCallApplication.kt), chỉ lưu
 * 1 mảng JSON đơn giản trong SharedPreferences, đủ dùng vì số lượng bản ghi âm không lớn.
 *
 * GIỚI HẠN QUAN TRỌNG CẦN BIẾT (đọc kỹ trước khi bật tính năng này):
 * - Từ Android 10 trở lên, chỉ ứng dụng HỆ THỐNG mới được cấp quyền CAPTURE_AUDIO_OUTPUT để
 *   ghi trực tiếp âm thanh cuộc gọi (MediaRecorder.AudioSource.VOICE_CALL). Ứng dụng thường
 *   (kể cả khi đã là app gọi điện mặc định) sẽ KHÔNG dùng được nguồn này trên hầu hết máy đời
 *   mới (Pixel, Samsung/Xiaomi bản ROM mới...) - hãng cố tình chặn vì lý do riêng tư.
 * - App vẫn thử VOICE_CALL trước (một số ROM cũ/tuỳ biến vẫn cho phép), nếu thất bại sẽ rơi
 *   xuống VOICE_COMMUNICATION rồi MIC - hai nguồn này CHỈ chắc chắn thu được giọng của CHÍNH
 *   người dùng máy, giọng đầu dây bên kia có thể bị mất hoặc rất nhỏ tuỳ máy/tuỳ có bật loa
 *   ngoài hay không (bật loa ngoài khi ghi âm sẽ tăng khả năng mic bắt được cả 2 chiều).
 * - Ghi âm cuộc gọi có thể vi phạm pháp luật ở một số nơi nếu không thông báo cho người kia -
 *   người dùng cần tự tìm hiểu quy định tại nơi mình sinh sống trước khi bật tính năng này.
 */
object CallRecordingManager {

    private const val PREFS = "call_recording_prefs"
    private const val KEY_ENABLED = "auto_record_enabled"
    private const val KEY_ENTRIES = "recording_entries_json"
    private const val KEY_THIRD_PARTY_PKG = "third_party_recorder_package"
    private const val FOLDER_NAME = "CallRecordings"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Package name của app ghi âm bên thứ 3 mà user đã chọn. null = chưa chọn. */
    fun getThirdPartyRecorderPackage(ctx: Context): String? =
        prefs(ctx).getString(KEY_THIRD_PARTY_PKG, null).takeIf { !it.isNullOrBlank() }

    fun setThirdPartyRecorderPackage(ctx: Context, packageName: String?) {
        prefs(ctx).edit().putString(KEY_THIRD_PARTY_PKG, packageName ?: "").apply()
        // Khi đã chọn app bên thứ 3, bật enabled luôn để CallStateReceiver biết cần launch
        setEnabled(ctx, packageName != null)
    }

    /** Tìm tất cả app ghi âm đang cài trên máy (có thể mở được bằng MAIN/LAUNCHER).
     *  Dùng để hiện picker cho user chọn. */
    fun findRecorderApps(ctx: Context): List<android.content.pm.ResolveInfo> {
        val pm = ctx.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        val all = pm.queryIntentActivities(intent, 0)
        // Lọc theo package name và label — chỉ dùng keyword rõ ràng liên quan đến ghi âm,
        // KHÔNG dùng keyword chung chung ("audio", "voice", "easy") để tránh lọt app không liên quan.
        val pkgKeywords  = listOf("record", "recorder", "callrecord", "acr", "cube.acr", "boldbeast")
        val nameKeywords = listOf("ghi âm", "ghi am", "recorder", "record call", "acr", "call recorder")
        return all.filter { ri ->
            val name = ri.loadLabel(pm).toString().lowercase()
            val pkg  = ri.activityInfo.packageName.lowercase()
            pkgKeywords.any { pkg.contains(it) } || nameKeywords.any { name.contains(it) }
        }.sortedBy { it.loadLabel(pm).toString() }
    }

    /** Trả toàn bộ app đang cài (cho picker "chọn thủ công"). */
    fun findAllApps(ctx: Context): List<android.content.pm.ResolveInfo> {
        val pm = ctx.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        return pm.queryIntentActivities(intent, 0).sortedBy { it.loadLabel(pm).toString() }
    }

    /** Thư mục lưu file ghi âm - nằm trong bộ nhớ riêng của app (không cần xin quyền lưu trữ,
     *  tự xoá khi gỡ app), nhưng người dùng vẫn lấy ra được qua Chia sẻ (FileProvider). */
    fun recordingsDir(ctx: Context): File {
        val base = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: ctx.filesDir
        return File(base, FOLDER_NAME).apply { if (!exists()) mkdirs() }
    }

    /** Tên file duy nhất theo số + thời điểm bắt đầu, dễ tra ngược nếu cần. */
    fun newFileFor(ctx: Context, number: String, startTimeMillis: Long): File {
        val safeNumber = number.filter { it.isDigit() }.ifEmpty { "unknown" }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(startTimeMillis)
        return File(recordingsDir(ctx), "${ts}_$safeNumber.m4a")
    }

    /** Lưu 1 bản ghi vào danh mục sau khi ghi xong. */
    fun addEntry(ctx: Context, recording: CallRecording) {
        val arr = readAll(ctx)
        arr.put(JSONObject().apply {
            put("number", recording.number)
            put("filePath", recording.filePath)
            put("startTimeMillis", recording.startTimeMillis)
            put("durationSeconds", recording.durationSeconds)
        })
        prefs(ctx).edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    fun deleteEntry(ctx: Context, recording: CallRecording) {
        runCatching { File(recording.filePath).delete() }
        val arr = readAll(ctx)
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("filePath") != recording.filePath) kept.put(o)
        }
        prefs(ctx).edit().putString(KEY_ENTRIES, kept.toString()).apply()
    }

    fun getAll(ctx: Context): List<CallRecording> {
        val arr = readAll(ctx)
        val list = mutableListOf<CallRecording>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                CallRecording(
                    number = o.optString("number"),
                    filePath = o.optString("filePath"),
                    startTimeMillis = o.optLong("startTimeMillis"),
                    durationSeconds = o.optLong("durationSeconds")
                )
            )
        }
        return list.sortedByDescending { it.startTimeMillis }
    }

    /** So khớp lỏng: cùng 9 số cuối là coi như cùng 1 số điện thoại (giống cách CallHistoryFragment
     *  đang so khớp CallLog), để không bị lệch vì đầu số +84/0 khác nhau. */
    fun getForNumber(ctx: Context, number: String): List<CallRecording> {
        val target = number.filter { it.isDigit() }.takeLast(9)
        if (target.isEmpty()) return emptyList()
        return getAll(ctx).filter { it.number.filter(Char::isDigit).takeLast(9) == target }
    }

    /** Tìm ĐÚNG bản ghi âm của 1 dòng cụ thể trong "Nhật ký cuộc gọi" (thay vì cả danh sách theo
     *  số) - dùng khi bấm vào 1 dòng lịch sử để mở lại bản ghi của riêng cuộc gọi đó. Vì không
     *  lưu chung ID với CallLog hệ thống, so khớp gần đúng: cùng số + thời điểm bắt đầu ghi cách
     *  thời điểm CallLog ghi nhận không quá [toleranceMillis] (mặc định 2 phút, đủ rộng để chấp
     *  nhận độ trễ nhỏ giữa lúc Telecom báo OFFHOOK và lúc CallLog thực sự ghi dòng mới, nhưng đủ
     *  hẹp để không lấy nhầm sang cuộc gọi khác cùng số ở thời điểm khác trong ngày). Nếu có nhiều
     *  ứng viên trong ngưỡng, chọn bản ghi gần nhất về thời gian.
     */
    fun getForCallLogEntry(
        ctx: Context, number: String, callDateMillis: Long, toleranceMillis: Long = 2 * 60_000L
    ): CallRecording? {
        return getForNumber(ctx, number)
            .filter { kotlin.math.abs(it.startTimeMillis - callDateMillis) <= toleranceMillis }
            .minByOrNull { kotlin.math.abs(it.startTimeMillis - callDateMillis) }
    }

    private fun readAll(ctx: Context): JSONArray = try {
        JSONArray(prefs(ctx).getString(KEY_ENTRIES, null) ?: "[]")
    } catch (_: Exception) { JSONArray() }
}
