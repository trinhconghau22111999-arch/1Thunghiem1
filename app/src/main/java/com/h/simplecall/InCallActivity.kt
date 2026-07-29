package com.h.simplecall

import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.telecom.Call
import android.telecom.VideoProfile
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.h.simplecall.call.CallUiService
import com.h.simplecall.databinding.ActivityInCallBinding

/**
 * Màn hình gọi — thiết kế y hệt giao diện gọi hệ thống Android (Google Phone):
 *  • Avatar tròn lớn + tên + số + trạng thái + đồng hồ
 *  • 4 nút hành động: Loa / Tắt mic / Phím bấm / Giữ máy
 *  • Nút Kết thúc (đỏ) ở giữa dưới, cùng nút Trả lời (xanh) khi cuộc gọi đến
 *
 * Được mở bởi CallUiService.updateNotification() thông qua fullScreenIntent khi có cuộc gọi đến,
 * hoặc khi người dùng bấm vào thông báo cuộc gọi đang diễn ra. Activity này cũng tự cập nhật
 * trạng thái theo Call.Callback — khi cuộc gọi kết thúc (DISCONNECTED) sẽ tự đóng.
 */
class InCallActivity : AppCompatActivity() {

    private lateinit var b: ActivityInCallBinding
    private var isMuted   = false
    private var isSpeaker = false
    private var isOnHold  = false
    private var dtmfInput = StringBuilder()
    private var audioManager: AudioManager? = null

    /** Lắng nghe thay đổi trạng thái cuộc gọi để cập nhật UI và đóng Activity khi kết thúc. */
    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            runOnUiThread { applyCallState(call, state) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hiện màn hình ngay cả khi máy đang khoá (cuộc gọi đến)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        b = ActivityInCallBinding.inflate(layoutInflater)
        setContentView(b.root)
        audioManager = getSystemService(AudioManager::class.java)

        val call = CallUiService.activeCall
        if (call == null) { finish(); return }

        call.registerCallback(callCallback)
        bindCallInfo(call)
        applyCallState(call, call.state)
        setupActionButtons(call)
        setupDtmfDialpad(call)
    }

    override fun onDestroy() {
        CallUiService.activeCall?.unregisterCallback(callCallback)
        super.onDestroy()
    }

    // ──────────────────────────────────────────────────────────────────
    // Hiển thị thông tin người gọi
    // ──────────────────────────────────────────────────────────────────

    private fun bindCallInfo(call: Call) {
        val details = call.details ?: return
        val rawName = details.callerDisplayName?.takeIf { it.isNotBlank() }
        val rawNumber = details.handle?.schemeSpecificPart ?: ""

        if (rawName != null) {
            b.tvCallerName.text = rawName
            b.tvCallerNumber.text = rawNumber
            b.tvCallerNumber.visibility = View.VISIBLE
            b.tvAvatarLetter.text = rawName.take(1).uppercase()
            b.tvAvatarLetter.visibility = View.VISIBLE
            b.ivAvatar.visibility = View.GONE
        } else {
            b.tvCallerName.text = rawNumber.ifBlank { "Không xác định" }
            b.tvCallerNumber.visibility = View.GONE
            b.tvAvatarLetter.visibility = View.GONE
            b.ivAvatar.visibility = View.VISIBLE
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Áp dụng trạng thái cuộc gọi lên UI
    // ──────────────────────────────────────────────────────────────────

    private fun applyCallState(call: Call, state: Int) {
        when (state) {
            Call.STATE_RINGING -> showIncoming()
            Call.STATE_DIALING, Call.STATE_CONNECTING -> showDialing()
            Call.STATE_ACTIVE  -> showActive()
            Call.STATE_HOLDING -> showHolding()
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> {
                b.chronometer.stop()
                finish()
            }
        }
    }

    private fun showIncoming() {
        b.tvCallStatus.text = "Đang đổ chuông..."
        b.chronometer.visibility = View.GONE
        b.gridActions.visibility = View.GONE
        // Hiện cả 2 nút: Từ chối (trái) + Trả lời (phải)
        b.spacerMiddle.visibility = View.VISIBLE
        b.btnAnswerWrap.visibility = View.VISIBLE
        b.tvDeclineLabel.text = "Từ chối"
    }

    private fun showDialing() {
        b.tvCallStatus.text = "Đang gọi..."
        b.chronometer.visibility = View.GONE
        b.gridActions.visibility = View.VISIBLE
        b.spacerMiddle.visibility = View.GONE
        b.btnAnswerWrap.visibility = View.GONE
        b.tvDeclineLabel.text = "Kết thúc"
    }

    private fun showActive() {
        b.tvCallStatus.text = "Đã kết nối"
        if (b.chronometer.visibility != View.VISIBLE) {
            b.chronometer.base = SystemClock.elapsedRealtime()
            b.chronometer.start()
            b.chronometer.visibility = View.VISIBLE
        }
        b.gridActions.visibility = View.VISIBLE
        b.spacerMiddle.visibility = View.GONE
        b.btnAnswerWrap.visibility = View.GONE
        b.tvDeclineLabel.text = "Kết thúc"
    }

    private fun showHolding() {
        b.tvCallStatus.text = "Đang giữ máy..."
        b.chronometer.stop()
    }

    // ──────────────────────────────────────────────────────────────────
    // 4 nút hành động + Trả lời / Kết thúc
    // ──────────────────────────────────────────────────────────────────

    private fun setupActionButtons(call: Call) {

        // Trả lời
        b.btnAnswer.setOnClickListener {
            call.answer(VideoProfile.STATE_AUDIO_ONLY)
        }

        // Từ chối / Kết thúc
        b.btnDecline.setOnClickListener {
            when (call.state) {
                Call.STATE_RINGING -> call.reject(false, null)
                else               -> call.disconnect()
            }
        }

        // Loa ngoài
        b.btnSpeaker.setOnClickListener {
            isSpeaker = !isSpeaker
            audioManager?.isSpeakerphoneOn = isSpeaker
            b.ivSpeaker.setBackgroundResource(
                if (isSpeaker) R.drawable.bg_action_btn_active else R.drawable.bg_action_btn)
            b.ivSpeaker.setColorFilter(
                if (isSpeaker) getColor(R.color.white) else getColor(R.color.text_primary))
        }

        // Tắt / bật mic
        b.btnMute.setOnClickListener {
            isMuted = !isMuted
            audioManager?.isMicrophoneMute = isMuted
            b.ivMute.setBackgroundResource(
                if (isMuted) R.drawable.bg_action_btn_active else R.drawable.bg_action_btn)
            b.ivMute.setColorFilter(
                if (isMuted) getColor(R.color.white) else getColor(R.color.text_primary))
        }

        // Phím bấm DTMF
        b.btnDialpad.setOnClickListener {
            b.dialpadOverlay.visibility = View.VISIBLE
        }

        // Giữ máy / tiếp tục
        b.btnHold.setOnClickListener {
            if (isOnHold) {
                call.unhold()
                isOnHold = false
                b.ivHold.setBackgroundResource(R.drawable.bg_action_btn)
                b.ivHold.setColorFilter(getColor(R.color.text_primary))
            } else {
                call.hold()
                isOnHold = true
                b.ivHold.setBackgroundResource(R.drawable.bg_action_btn_active)
                b.ivHold.setColorFilter(getColor(R.color.white))
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Bàn phím DTMF
    // ──────────────────────────────────────────────────────────────────

    private fun setupDtmfDialpad(call: Call) {
        val keys = mapOf(
            b.dtmf1 to '1', b.dtmf2 to '2', b.dtmf3 to '3',
            b.dtmf4 to '4', b.dtmf5 to '5', b.dtmf6 to '6',
            b.dtmf7 to '7', b.dtmf8 to '8', b.dtmf9 to '9',
            b.dtmfStar to '*', b.dtmf0 to '0', b.dtmfHash to '#'
        )
        keys.forEach { (btn, digit) ->
            btn.setOnClickListener {
                call.playDtmfTone(digit)
                call.stopDtmfTone()
                dtmfInput.append(digit)
                b.tvDtmfInput.text = dtmfInput.toString()
            }
        }
        b.btnHideDialpad.setOnClickListener {
            b.dialpadOverlay.visibility = View.GONE
        }
    }

    override fun onBackPressed() {
        // Bấm back khi đang xem bàn phím DTMF → chỉ ẩn bàn phím, không đóng Activity
        if (b.dialpadOverlay.visibility == View.VISIBLE) {
            b.dialpadOverlay.visibility = View.GONE
            return
        }
        // Không cho phép back để thoát khỏi màn hình gọi đang diễn ra (di chuyển về background)
        moveTaskToBack(true)
    }
}
