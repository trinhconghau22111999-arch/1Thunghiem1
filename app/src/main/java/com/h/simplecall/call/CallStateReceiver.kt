package com.h.simplecall.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/**
 * Nhận broadcast android.intent.action.PHONE_STATE (đăng ký trong AndroidManifest.xml) để biết
 * khi nào 1 cuộc gọi THỰC SỰ bắt đầu (OFFHOOK = đã nhấc máy/đã kết nối) và khi nào kết thúc
 * (IDLE), từ đó bật/tắt CallRecordingService đúng lúc.
 *
 * Số điện thoại của cuộc gọi:
 * - Cuộc gọi ĐẾN: lấy từ extra EXTRA_INCOMING_NUMBER khi trạng thái là RINGING (cần quyền
 *   READ_CALL_LOG từ Android 10 trở lên mới có, app đã xin quyền này).
 * - Cuộc gọi ĐI: broadcast NEW_OUTGOING_CALL không còn đáng tin cậy cho app thường từ Android 10,
 *   nên MainActivity.placeCall() tự set CallStateReceiver.pendingOutgoingNumber TRƯỚC khi quay
 *   số - receiver dùng lại giá trị này khi thấy OFFHOOK mà không có số RINGING trước đó.
 */
class CallStateReceiver : BroadcastReceiver() {

    companion object {
        /** Số vừa được MainActivity.placeCall() quay, chờ receiver này xác nhận khi cuộc gọi
         *  đi vào trạng thái OFFHOOK. Dùng @Volatile vì broadcast có thể tới trên thread khác. */
        @Volatile
        var pendingOutgoingNumber: String? = null

        @Volatile
        private var ringingNumber: String? = null

        @Volatile
        private var isRecordingActive: Boolean = false
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                ringingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (isRecordingActive) return // đã bật rồi (vd. broadcast gửi trùng)
                if (!CallRecordingManager.isEnabled(context)) return
                val number = ringingNumber ?: pendingOutgoingNumber ?: ""
                isRecordingActive = true

                val thirdPartyPkg = CallRecordingManager.getThirdPartyRecorderPackage(context)
                if (thirdPartyPkg != null) {
                    // Dùng app ghi âm bên thứ 3: launch bằng Intent MAIN/LAUNCHER theo package.
                    // FLAG_ACTIVITY_NEW_TASK bắt buộc vì đang gọi từ BroadcastReceiver (không có Activity context).
                    // FLAG_ACTIVITY_REORDER_TO_FRONT: nếu app đó đang chạy ở nền thì kéo lên foreground thay vì tạo instance mới.
                    try {
                        val pm = context.packageManager
                        val launchIntent = pm.getLaunchIntentForPackage(thirdPartyPkg)
                        if (launchIntent != null) {
                            launchIntent.addFlags(
                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            )
                            context.startActivity(launchIntent)
                        }
                    } catch (_: Exception) { /* app bị gỡ/lỗi - bỏ qua */ }
                } else {
                    // Không có app bên thứ 3 → fallback về ghi âm nội bộ (nếu vẫn muốn dùng)
                    CallRecordingService.start(context, number)
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                ringingNumber = null
                pendingOutgoingNumber = null
                if (isRecordingActive) {
                    isRecordingActive = false
                    // Chỉ dừng service nội bộ nếu không dùng app bên thứ 3
                    if (CallRecordingManager.getThirdPartyRecorderPackage(context) == null) {
                        CallRecordingService.stop(context)
                    }
                }
            }
        }
    }
}
