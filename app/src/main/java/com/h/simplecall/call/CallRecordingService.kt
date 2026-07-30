package com.h.simplecall.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.h.simplecall.R
import java.io.File

/**
 * Service nền chạy foreground trong lúc đang có 1 cuộc gọi active để ghi âm cuộc gọi đó.
 * Được CallStateReceiver bật lên khi trạng thái điện thoại chuyển sang OFFHOOK (đã nhấc máy)
 * và tắt khi chuyển về IDLE (cuộc gọi kết thúc).
 *
 * Chạy dưới dạng foreground service (thay vì ghi âm thẳng từ BroadcastReceiver) để:
 * 1. Không bị hệ thống giới hạn truy cập micro ở nền (Android 9+ hạn chế app nền dùng mic).
 * 2. Có thông báo rõ ràng cho người dùng biết đang bị ghi âm (minh bạch, đúng tinh thần
 *    cần thông báo cho các bên khi ghi âm cuộc gọi).
 */
class CallRecordingService : Service() {

    companion object {
        const val ACTION_START = "com.h.simplecall.call.action.START_RECORDING"
        const val ACTION_STOP = "com.h.simplecall.call.action.STOP_RECORDING"
        const val EXTRA_NUMBER = "extra_number"
        private const val CHANNEL_ID = "call_recording"
        private const val NOTIF_ID = 2001

        fun start(ctx: Context, number: String) {
            val i = Intent(ctx, CallRecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_NUMBER, number)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CallRecordingService::class.java).setAction(ACTION_STOP))
        }
    }

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var currentNumber: String = ""
    private var startTimeMillis: Long = 0L
    private var speakerWasOn: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> beginRecording(intent.getStringExtra(EXTRA_NUMBER) ?: "")
            ACTION_STOP -> finishRecording()
        }
        return START_NOT_STICKY
    }

    private fun beginRecording(number: String) {
        if (recorder != null) return // đã đang ghi (vd. nhận 2 lần OFFHOOK liên tiếp) - bỏ qua
        if (!CallRecordingManager.isEnabled(this)) { stopSelf(); return }

        ensureChannel()
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_record_dot)
            .setContentTitle(getString(R.string.recording_notif_title))
            .setContentText(getString(R.string.recording_notif_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        currentNumber = number
        startTimeMillis = System.currentTimeMillis()
        currentFile = CallRecordingManager.newFileFor(this, number, startTimeMillis)

        // Bật loa ngoài để mic bắt được cả 2 chiều khi dùng nguồn MIC/VOICE_COMMUNICATION.
        // VOICE_CALL bị chặn trên hầu hết máy Android 10+ — loa ngoài là cách duy nhất để mic
        // thật sự thu được tiếng đầu dây bên kia (âm thanh loa thoát ra → mic trong máy bắt).
        // Lưu lại trạng thái loa cũ để khôi phục đúng khi ghi xong.
        val am = getSystemService(AudioManager::class.java)
        if (am != null) {
            speakerWasOn = am.isSpeakerphoneOn
            if (!speakerWasOn) am.isSpeakerphoneOn = true
        }

        // Thử lần lượt 3 nguồn âm thanh, từ "tốt nhất nếu máy cho phép" tới "chắc chắn hoạt
        // động nhưng chỉ thu giọng người dùng máy này". Xem ghi chú giới hạn trong
        // CallRecordingManager.kt để hiểu vì sao không có nguồn nào đảm bảo thu được cả 2 chiều.
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )
        var started = false
        for (source in sources) {
            if (tryStart(source)) { started = true; break }
        }
        if (!started) {
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun tryStart(source: Int): Boolean {
        val file = currentFile ?: return false
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(source)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            true
        } catch (_: Exception) {
            runCatching { r.release() }
            false
        }
    }

    private fun finishRecording() {
        val r = recorder
        val file = currentFile
        val number = currentNumber
        val start = startTimeMillis
        recorder = null
        if (r != null) {
            try {
                r.stop()
            } catch (_: Exception) {
                // stop() ném lỗi khi cuộc gọi kết thúc quá nhanh (chưa ghi được dữ liệu nào) -
                // file tạo ra sẽ không hợp lệ, xoá đi thay vì lưu 1 bản ghi rỗng/hỏng.
                runCatching { file?.delete() }
                r.release(); cleanupAndStop(); return
            }
            r.release()

            val durationSec = ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(0)
            if (file != null && file.exists() && file.length() > 0 && durationSec > 0) {
                CallRecordingManager.addEntry(
                    this,
                    CallRecording(number, file.absolutePath, start, durationSec)
                )
            } else {
                runCatching { file?.delete() }
            }
        }
        cleanupAndStop()
    }

    private fun cleanupAndStop() {
        // Khôi phục trạng thái loa ngoài về như trước khi bắt đầu ghi âm
        if (!speakerWasOn) {
            runCatching {
                getSystemService(AudioManager::class.java)?.isSpeakerphoneOn = false
            }
        }
        currentFile = null
        currentNumber = ""
        speakerWasOn = false
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, getString(R.string.recording_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        // Phòng trường hợp bị hệ thống kill service giữa chừng - đảm bảo giải phóng MediaRecorder,
        // không để giữ mic mãi hoặc rò rỉ tài nguyên.
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        super.onDestroy()
    }
}
