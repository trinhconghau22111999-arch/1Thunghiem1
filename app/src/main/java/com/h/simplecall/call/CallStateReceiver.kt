package com.h.simplecall.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/**
 * Nhận broadcast android.intent.action.PHONE_STATE (đăng ký trong AndroidManifest.xml) để biết
 * khi nào 1 cuộc gọi THỰC SỰ bắt đầu (OFFHOOK = đã nhấc máy/đã kết nối) và khi nào kết thúc
 * (IDLE). Khi bắt đầu, nếu người dùng đã bật "Tự động ghi âm" ở Cài đặt, mở thẳng app VOX Ghi Âm
 * lên (xem CallRecordingManager.kt) - KHÔNG còn ghi âm nội bộ, chỉ dùng đúng 1 app ngoài duy nhất.
 */
class CallStateReceiver : BroadcastReceiver() {

    companion object {
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
                isRecordingActive = true
                CallRecordingManager.openRecorderApp(context)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                ringingNumber = null
                pendingOutgoingNumber = null
                isRecordingActive = false
            }
        }
    }
}
