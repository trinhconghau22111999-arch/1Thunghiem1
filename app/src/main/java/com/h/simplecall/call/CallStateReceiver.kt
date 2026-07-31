package com.h.simplecall.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/**
 * Nhận broadcast android.intent.action.PHONE_STATE (đăng ký trong AndroidManifest.xml) để biết
 * khi nào 1 cuộc gọi THỰC SỰ bắt đầu (OFFHOOK = đã nhấc máy/đã kết nối) và khi nào kết thúc
 * (IDLE). Khi bắt đầu, nếu người dùng đã bật "Tự động ghi âm" ở Cài đặt, gửi lệnh ghi âm NỀN cho
 * VOX Ghi Âm qua Intent tường minh tới RecordingService (xem CallRecordingManager.kt) - KHÔNG mở
 * giao diện của VOX lên nữa (trước đây gọi openRecorderApp() làm app VOX nhảy lên đè màn gọi).
 *
 * Nếu người dùng CÒN bật thêm "Tự động bật loa ngoài khi ghi âm" (mặc định TẮT, xem
 * SettingsFragment.kt), cũng tự chuyển cuộc gọi sang loa ngoài ngay lúc này - vì nhiều máy chặn
 * ghi âm micro thường lúc đang gọi (chỉ ghi được tiếng người dùng, không có tiếng đối phương);
 * bật loa ngoài giúp tiếng đối phương phát ra ngoài, tăng khả năng mic bắt được cả 2 chiều.
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
                // Gọi ĐẾN thì có ringingNumber (từ EXTRA_INCOMING_NUMBER ở trên); gọi ĐI thì
                // dùng pendingOutgoingNumber (MainActivity.placeCall() ghi lại trước khi gọi -
                // xem ở đó để biết vì sao không dùng được NEW_OUTGOING_CALL broadcast).
                val number = ringingNumber ?: pendingOutgoingNumber ?: ""
                isRecordingActive = true
                CallRecordingManager.startRecording(context, number)
                if (CallRecordingManager.isAutoSpeakerEnabled(context)) {
                    // Instance của InCallService chỉ tồn tại khi có cuộc gọi (đúng lúc này) -
                    // xem CallUiService.setSpeakerOn() để biết vì sao KHÔNG dùng AudioManager
                    // trực tiếp ở đây (bị Telecom ghi đè, không có tác dụng thật).
                    CallUiService.instance?.setSpeakerOn(true)
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (isRecordingActive) CallRecordingManager.stopRecording(context)
                ringingNumber = null
                pendingOutgoingNumber = null
                isRecordingActive = false
            }
        }
    }
}
