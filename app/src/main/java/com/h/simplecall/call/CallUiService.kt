package com.h.simplecall.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.h.simplecall.InCallActivity
import com.h.simplecall.MainActivity
import com.h.simplecall.R
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile

/**
 * ── VÌ SAO FILE NÀY BẮT BUỘC PHẢI TỒN TẠI ──
 * Trước đây (commit "de3c9dc") file MyInCallService/InCallActivity gốc bị xoá với suy nghĩ "dùng
 * UI cuộc gọi mặc định của hệ thống" — nhưng Android KHÔNG hoạt động như vậy: theo tài liệu chính
 * thức (developer.android.com/develop/connectivity/telecom/dialer-app), để app được xem là ỨNG
 * CỬ VIÊN hợp lệ cho vai trò RoleManager.ROLE_DIALER ("ứng dụng điện thoại mặc định"), app BẮT
 * BUỘC phải khai báo MỘT InCallService thật trong Manifest — thiếu nó, hệ thống sẽ không bao giờ
 * liệt kê app này trong màn "Cài đặt > Ứng dụng mặc định > Ứng dụng điện thoại" để chọn, đúng như
 * lỗi "Không có chỗ cài đặt làm ứng dụng điện thoại" đã gặp. Nếu app CÓ giữ vai trò này nhưng
 * InCallService trả về null khi bind (hoặc không tồn tại), hệ thống coi app KHÔNG còn đủ điều
 * kiện và có thể tự thu hồi vai trò / rơi về dùng lại ứng dụng gọi điện cài sẵn của máy giữa
 * chừng cuộc gọi — trải nghiệm xấu, đúng kiểu hành vi bất định gây cảm giác "văng".
 *
 * KHÔNG cần dựng lại 1 màn hình gọi (Activity) tuỳ chỉnh phức tạp như bản gốc: dùng đúng pattern
 * CHÍNH THỨC Android khuyến nghị cho InCallService — hiển thị UI cuộc gọi đến/đang gọi bằng
 * Notification toàn màn hình (full-screen intent khi máy khoá + heads-up khi đang dùng máy), có
 * nút Trả lời/Từ chối/Kết thúc ngay trên thông báo. Cách này NHẸ, ổn định, và đúng chuẩn tài liệu
 * "Showing the Incoming Call Notification" của Android.
 */
class CallUiService : InCallService() {

    companion object {
        private const val CHANNEL_INCOMING = "call_incoming"
        private const val CHANNEL_ONGOING = "call_ongoing"
        private const val NOTIF_ID = 7001
        const val ACTION_ANSWER = "com.h.simplecall.call.ACTION_ANSWER"
        const val ACTION_REJECT = "com.h.simplecall.call.ACTION_REJECT"
        const val ACTION_HANGUP = "com.h.simplecall.call.ACTION_HANGUP"

        /** Cuộc gọi đang được InCallService này quản lý — CallActionReceiver cần truy cập trực
         *  tiếp đối tượng Call thật để gọi answer()/reject()/disconnect(), không có cách nào
         *  khác để "tìm lại" đúng Call đó từ 1 Intent broadcast đơn thuần. */
        @Volatile var activeCall: Call? = null

        /** Tham chiếu tới chính InCallService này — BẮT BUỘC để đổi đường tiếng (loa ngoài/tai
         *  nghe) vì setAudioRoute() CHỈ tồn tại trên InCallService, không có trên Call hay bất kỳ
         *  API công khai nào khác. (Từng chỉ dùng đúng 1 mình setAudioRoute() vì nghĩ AudioManager
         *  sẽ luôn bị Telecom ghi đè hoàn toàn - thực tế 1 số máy/ROM tuỳ biến vẫn cần thêm bước
         *  AudioManager làm lớp bảo hiểm mới thực sự đổi được loa vật lý, xem setSpeakerOn()). */
        @Volatile var instance: CallUiService? = null
    }

    /** InCallActivity đăng ký lắng nghe ở đây để biết đường tiếng/trạng thái mic THẬT SỰ đã đổi
     *  (Telecom xác nhận qua onCallAudioStateChanged), thay vì tự đoán "chắc là đã bật" ngay khi
     *  bấm nút - đó là lý do trước đây nút Loa đổi màu "đã bật" dù thực tế Telecom có thể không
     *  áp dụng được (vd. đang có thiết bị Bluetooth giữ quyền audio route), khiến người dùng
     *  tưởng đã bật nhưng loa ngoài trên máy vẫn không kêu. */
    @Volatile var audioStateListener: ((CallAudioState) -> Unit)? = null

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        audioStateListener?.invoke(audioState)
    }

    /** Đổi đường tiếng cuộc gọi qua đúng API Telecom (CallAudioState).
     *
     *  BỔ SUNG: chỉ gọi setAudioRoute() đôi khi KHÔNG đủ để phần cứng thật sự đổi loa trên 1 số
     *  máy/ROM tuỳ biến (đặc biệt máy Android cũ hơn hoặc ROM Samsung/Xiaomi có lớp quản lý audio
     *  riêng chồng lên Telecom chuẩn) - Telecom báo route đã đổi (CallAudioState cập nhật đúng)
     *  nhưng loa vật lý vẫn im. Gọi thêm AudioManager làm lớp bảo hiểm thứ 2:
     *  - Android 12+ (S): dùng setCommunicationDevice()/clearCommunicationDevice() - API CHÍNH
     *    THỨC hiện tại để chọn thiết bị audio khi đang gọi, thay thế isSpeakerphoneOn cũ.
     *  - Android cũ hơn: dùng lại isSpeakerphoneOn (dù deprecated) vì setCommunicationDevice()
     *    chưa tồn tại trước API 31.
     *
     *  LỖI ĐÃ SỬA: nhánh TẮT loa trước đây chỉ gọi clearCommunicationDevice() NẾU tự đọc lại
     *  am.communicationDevice thấy ĐANG đúng là loa ngoài - nhưng giá trị đọc lại này có thể
     *  CHƯA KỊP CẬP NHẬT (bất đồng bộ, nhất là khi bấm tắt ngay sau khi vừa bật) khiến điều kiện
     *  sai và bỏ qua luôn bước tắt - đây chính là lý do "bấm tắt mà không ăn ngay". Bỏ hẳn điều
     *  kiện, LUÔN gọi clearCommunicationDevice() khi tắt - hàm này tự no-op an toàn nếu vốn chưa
     *  ép thiết bị nào, không cần tự kiểm tra trước. */
    fun setSpeakerOn(on: Boolean) {
        setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE)
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (on) {
                    val speakerDevice = am.availableCommunicationDevices
                        .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speakerDevice != null) am.setCommunicationDevice(speakerDevice)
                } else {
                    am.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = on
            }
        } catch (e: Exception) {
            android.util.Log.e("CallUiService", "setSpeakerOn() lop bao hiem AudioManager loi, bo qua an toan", e)
        }
    }

    /** Tắt/bật mic qua đúng API Telecom (InCallService.setMuted) - AudioManager.isMicrophoneMute
     *  cũng bị Telecom ghi đè y hệt như trường hợp loa ngoài ở trên. */
    fun setMutedState(muted: Boolean) = setMuted(muted)

    /** Đọc đúng trạng thái loa đang bật hay tắt TỪ chính Telecom (callAudioState thật), thay vì
     *  tự giữ 1 biến boolean cục bộ dễ bị lệch nếu route bị hệ thống tự đổi (vd. khi cắm/rút tai
     *  nghe có dây hoặc Bluetooth trong lúc đang gọi). */
    fun isSpeakerOn(): Boolean = callAudioState?.route == CallAudioState.ROUTE_SPEAKER

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val call = activeCall ?: return
            when (intent.action) {
                ACTION_ANSWER -> call.answer(VideoProfile.STATE_AUDIO_ONLY)
                ACTION_REJECT -> call.reject(false, null)
                ACTION_HANGUP -> call.disconnect()
            }
        }
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            updateNotification(call, state)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannels()
        val filter = IntentFilter().apply {
            addAction(ACTION_ANSWER); addAction(ACTION_REJECT); addAction(ACTION_HANGUP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(actionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(actionReceiver, filter)
        }
    }

    override fun onCallAdded(call: Call) {
        activeCall = call
        call.registerCallback(callCallback)
        updateNotification(call, call.state)
        // TRƯỚC ĐÂY: chỉ setFullScreenIntent() cho trạng thái RINGING (cuộc gọi ĐẾN) - với cuộc
        // gọi ĐI (bấm gọi từ trong app), notification cho DIALING/ACTIVE chỉ có setContentIntent
        // (chỉ mở khi người dùng TỰ kéo thanh thông báo ra và bấm vào), nên màn hình cuộc gọi
        // không bao giờ tự nổi lên - người dùng bấm gọi xong chỉ thấy app của mình, gần như
        // không điều khiển được cuộc gọi (không thấy nút tắt tiếng/loa ngoài/kết thúc) trừ khi tự
        // mò vào thông báo. Gọi thẳng startActivity() ở đây đảm bảo màn gọi LUÔN tự hiện ngay lập
        // tức, cho cả cuộc gọi đến lẫn đi - đây là hành vi bắt buộc phải có của 1 InCallService
        // đúng chuẩn (ứng dụng điện thoại mặc định), không phải tuỳ chọn.
        startActivity(Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        })
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callCallback)
        if (activeCall === call) activeCall = null
        getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }

    override fun onDestroy() {
        try { unregisterReceiver(actionReceiver) } catch (_: Exception) {}
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return

        val ringtoneUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val ringAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val incoming = NotificationChannel(CHANNEL_INCOMING, "Cuộc gọi đến", NotificationManager.IMPORTANCE_MAX).apply {
            setSound(ringtoneUri, ringAttrs)
            enableVibration(true)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val ongoing = NotificationChannel(CHANNEL_ONGOING, "Cuộc gọi đang diễn ra", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            description = "Thông báo khi đang trong cuộc gọi"
        }
        nm.createNotificationChannel(incoming)
        nm.createNotificationChannel(ongoing)
    }

    private fun actionPendingIntent(action: String): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(this, action.hashCode(), Intent(action).setPackage(packageName), flags)
    }

    private fun callerLabel(call: Call): String {
        val handle = call.details?.handle
        val fromName = call.details?.callerDisplayName
        return when {
            !fromName.isNullOrBlank() -> fromName
            handle?.schemeSpecificPart?.isNotBlank() == true -> handle.schemeSpecificPart
            else -> "Không xác định"
        }
    }

    private fun updateNotification(call: Call, state: Int) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val name = callerLabel(call)

        val fullScreenIntent = Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION
        }
        val contentPi = PendingIntent.getActivity(this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notif: Notification = when (state) {
            Call.STATE_RINGING -> {
                NotificationCompat.Builder(this, CHANNEL_INCOMING)
                    .setSmallIcon(R.drawable.ic_call)
                    .setContentTitle("Cuộc gọi đến")
                    .setContentText(name)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setFullScreenIntent(contentPi, true)
                    .setContentIntent(contentPi)
                    .addAction(R.drawable.ic_call_end, "Từ chối", actionPendingIntent(ACTION_REJECT))
                    .addAction(R.drawable.ic_call, "Trả lời", actionPendingIntent(ACTION_ANSWER))
                    .build()
            }
            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                NotificationCompat.Builder(this, CHANNEL_ONGOING)
                    .setSmallIcon(R.drawable.ic_call_outgoing)
                    .setContentTitle("Đang gọi...")
                    .setContentText(name)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setOngoing(true)
                    .setContentIntent(contentPi)
                    .addAction(R.drawable.ic_call_end, "Kết thúc", actionPendingIntent(ACTION_HANGUP))
                    .build()
            }
            Call.STATE_ACTIVE -> {
                NotificationCompat.Builder(this, CHANNEL_ONGOING)
                    .setSmallIcon(R.drawable.ic_call)
                    .setContentTitle("Đang trong cuộc gọi")
                    .setContentText(name)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setOngoing(true)
                    .setUsesChronometer(true)
                    .setContentIntent(contentPi)
                    .addAction(R.drawable.ic_call_end, "Kết thúc", actionPendingIntent(ACTION_HANGUP))
                    .build()
            }
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> {
                nm.cancel(NOTIF_ID)
                return
            }
            else -> return
        }
        nm.notify(NOTIF_ID, notif)
    }
}
