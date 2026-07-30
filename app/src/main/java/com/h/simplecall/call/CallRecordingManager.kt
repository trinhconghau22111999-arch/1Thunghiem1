package com.h.simplecall.call

import android.content.Context
import android.content.Intent

/**
 * App KHÔNG còn tự ghi âm cuộc gọi nội bộ (đã gỡ MediaRecorder/CallRecordingService cũ) - toàn bộ
 * việc ghi âm giờ giao thẳng cho ĐÚNG 1 ứng dụng ngoài duy nhất: "VOX Ghi Âm" (package
 * [RECORDER_PACKAGE]). Object này chỉ còn 2 việc:
 *  1) Lưu cờ "Tự động mở VOX Ghi Âm khi có cuộc gọi" (bật/tắt ở Cài đặt).
 *  2) Mở app đó lên (dùng Intent MAIN/LAUNCHER theo package, không có API liên app nào khác vì
 *     VOX Ghi Âm không export bất kỳ thành phần nào cho app khác điều khiển từ xa).
 */
object CallRecordingManager {

    /** Package name CỐ ĐỊNH của app ghi âm duy nhất được dùng - không còn cho chọn app khác. */
    const val RECORDER_PACKAGE = "com.vox.ghiam"
    const val RECORDER_APP_NAME = "VOX Ghi Âm"

    private const val PREFS = "call_recording_prefs"
    private const val KEY_ENABLED = "auto_record_enabled"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Có tự động mở VOX Ghi Âm mỗi khi có cuộc gọi bắt đầu hay không (xem CallStateReceiver.kt). */
    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** VOX Ghi Âm đã được cài trên máy chưa. */
    fun isRecorderAppInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getApplicationInfo(RECORDER_PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** Mở thẳng VOX Ghi Âm lên (kéo lên foreground nếu đang chạy nền). Dùng
     *  FLAG_ACTIVITY_NEW_TASK vì có thể được gọi từ BroadcastReceiver (không có Activity context).
     *  Trả về false nếu chưa cài app. */
    fun openRecorderApp(ctx: Context): Boolean {
        val pm = ctx.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(RECORDER_PACKAGE) ?: return false
        return try {
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            ctx.startActivity(launchIntent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
