package com.h.simplecall

import android.os.Bundle
import android.os.SystemClock
import android.provider.CallLog
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.VideoProfile
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.h.simplecall.call.CallUiService
import com.h.simplecall.data.CallLogEntry
import com.h.simplecall.databinding.ActivityInCallBinding
import com.h.simplecall.ui.DialerFragment

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
    private var isClarityOn = false
    private var noiseSuppressor: android.media.audiofx.NoiseSuppressor? = null
    private var dtmfInput = StringBuilder()
    private var audioStateListener: ((CallAudioState) -> Unit)? = null

    // ── Dữ liệu để tự thêm NGAY 1 dòng "Gần đây" khi cuộc gọi này kết thúc, không đợi hệ thống
    //    ghi xong vào CallLog thật (thường mất 1-3 giây) - xem pushRecentEntryOptimistically(). ──
    private var callerName: String = ""
    private var callerNumber: String = ""
    /** null = chưa xác định được hướng cuộc gọi (chưa nhận state RINGING/DIALING nào). */
    private var wasIncoming: Boolean? = null
    private var wasAnswered = false
    private var connectTimeMillis: Long = 0L
    private var optimisticEntryPushed = false

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

        val call = CallUiService.activeCall
        if (call == null) { finish(); return }

        call.registerCallback(callCallback)
        bindCallInfo(call)
        applyCallState(call, call.state)
        setupActionButtons(call)
        setupDtmfDialpad(call)

        // Lắng nghe CallAudioState THẬT từ Telecom (qua CallUiService) để icon Loa/Mic luôn
        // đúng sự thật, không tự đoán "chắc là đã bật" ngay khi bấm - xem ghi chú trong
        // CallUiService.audioStateListener.
        CallUiService.instance?.let { svc ->
            audioStateListener = { state ->
                runOnUiThread {
                    updateSpeakerIcon(state.route == CallAudioState.ROUTE_SPEAKER)
                    updateMuteIcon(state.isMuted)
                }
            }
            svc.audioStateListener = audioStateListener
            // Đồng bộ ngay trạng thái hiện tại (không đợi lần đổi tiếp theo), phòng route đã là
            // loa ngoài từ trước (vd. Activity bị tạo lại sau khi xoay máy/rotate).
            svc.callAudioState?.let { state ->
                updateSpeakerIcon(state.route == CallAudioState.ROUTE_SPEAKER)
                updateMuteIcon(state.isMuted)
            }
        }
    }

    override fun onDestroy() {
        CallUiService.activeCall?.unregisterCallback(callCallback)
        // Chỉ gỡ nếu ĐÚNG listener của chính Activity này đang được gắn - tránh trường hợp hiếm
        // 1 Activity mới đã kịp đăng ký listener khác trước khi Activity cũ này onDestroy().
        CallUiService.instance?.let { svc ->
            if (svc.audioStateListener === audioStateListener) svc.audioStateListener = null
        }
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    // ──────────────────────────────────────────────────────────────────
    // Hiển thị thông tin người gọi
    // ──────────────────────────────────────────────────────────────────

    private fun bindCallInfo(call: Call) {
        val details = call.details ?: return
        val rawName = details.callerDisplayName?.takeIf { it.isNotBlank() }
        val rawNumber = details.handle?.schemeSpecificPart ?: ""
        callerName = rawName ?: ""
        callerNumber = rawNumber

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
            Call.STATE_RINGING -> { if (wasIncoming == null) wasIncoming = true; showIncoming() }
            Call.STATE_DIALING, Call.STATE_CONNECTING -> { if (wasIncoming == null) wasIncoming = false; showDialing() }
            Call.STATE_ACTIVE  -> {
                wasAnswered = true
                if (connectTimeMillis == 0L) {
                    // connectTimeMillis do hệ thống Telecom cấp - chính xác hơn System.currentTimeMillis()
                    // gọi thủ công ở đây (có độ trễ nhỏ giữa lúc thực sự kết nối và lúc callback chạy tới).
                    val ct = call.details?.connectTimeMillis ?: 0L
                    connectTimeMillis = if (ct > 0L) ct else System.currentTimeMillis()
                }
                showActive()
            }
            Call.STATE_HOLDING -> showHolding()
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> {
                b.chronometer.stop()
                pushRecentEntryOptimistically()
                finish()
            }
        }
    }

    /** Thêm NGAY 1 dòng vào danh sách "Gần đây" (DialerFragment) ngay khi cuộc gọi này kết thúc
     *  trên chính app - KHÔNG đợi hệ thống Telephony ghi xong vào CallLog thật (quan sát thực tế
     *  thường mất 1-3 giây sau khi cúp máy), tránh cảm giác app "đứng hình"/chậm cập nhật ngay
     *  sau khi vừa gọi xong. Bản ghi tạm này sẽ tự động được bản ghi CHÍNH THỨC từ CallLog thay
     *  thế ngay sau đó (DialerFragment vẫn đang lắng nghe ContentObserver như cũ), nên không lo
     *  sai lệch dữ liệu lâu dài - chỉ là hiển thị NGAY thay vì phải chờ. */
    private fun pushRecentEntryOptimistically() {
        if (optimisticEntryPushed) return
        val number = callerNumber
        val incoming = wasIncoming
        if (number.isBlank() || incoming == null) return
        optimisticEntryPushed = true
        val type = when {
            incoming && !wasAnswered -> CallLog.Calls.MISSED_TYPE
            incoming -> CallLog.Calls.INCOMING_TYPE
            else -> CallLog.Calls.OUTGOING_TYPE
        }
        val hasConnected = wasAnswered && connectTimeMillis > 0L
        val durationSec = if (hasConnected)
            ((System.currentTimeMillis() - connectTimeMillis) / 1000L).coerceAtLeast(0L) else 0L
        DialerFragment.pushOptimisticRecentEntry(
            CallLogEntry(
                name = callerName,
                number = number,
                type = type,
                date = if (hasConnected) connectTimeMillis else System.currentTimeMillis(),
                duration = durationSec
            )
        )
    }

    private fun showIncoming() {
        b.tvCallStatus.text = "Đang đổ chuông..."
        b.chronometer.visibility = View.GONE
        b.gridActions.visibility = View.GONE
        b.btnSpeakerWrap.visibility = View.GONE
        b.btnDialpadWrap.visibility = View.GONE
        // Hiện cả 2 nút: Từ chối (trái) + Trả lời (phải)
        b.spacerMiddle.visibility = View.VISIBLE
        b.btnAnswerWrap.visibility = View.VISIBLE
        b.tvDeclineLabel.text = "Từ chối"
    }

    private fun showDialing() {
        b.tvCallStatus.text = "Đang gọi..."
        b.chronometer.visibility = View.GONE
        b.gridActions.visibility = View.VISIBLE
        b.btnSpeakerWrap.visibility = View.VISIBLE
        b.btnDialpadWrap.visibility = View.VISIBLE
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
        b.btnSpeakerWrap.visibility = View.VISIBLE
        b.btnDialpadWrap.visibility = View.VISIBLE
        b.spacerMiddle.visibility = View.GONE
        b.btnAnswerWrap.visibility = View.GONE
        b.tvDeclineLabel.text = "Kết thúc"
    }

    private fun showHolding() {
        b.tvCallStatus.text = "Đang giữ máy..."
        b.chronometer.stop()
    }

    private fun updateSpeakerIcon(on: Boolean) {
        isSpeaker = on
        b.ivSpeaker.setBackgroundResource(if (on) R.drawable.bg_action_btn_active else R.drawable.bg_action_btn)
        b.ivSpeaker.setColorFilter(if (on) getColor(R.color.white) else getColor(R.color.text_primary))
    }

    private fun updateMuteIcon(muted: Boolean) {
        isMuted = muted
        b.ivMute.setBackgroundResource(if (muted) R.drawable.bg_action_btn_active else R.drawable.bg_action_btn)
        b.ivMute.setColorFilter(if (muted) getColor(R.color.white) else getColor(R.color.text_primary))
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

        // Loa ngoài — nền tròn đổi hẳn sang xanh dương (accent_blue) + icon trắng khi BẬT, thay
        // vì chỉ đổi màu icon mờ nhạt như trước - dễ nhận biết trạng thái đang bật/tắt hơn hẳn.
        //
        // LỖI ĐÃ SỬA: trước đây isSpeaker là 1 biến cờ RIÊNG của Activity, tự đảo ngược và tự vẽ
        // icon "đã bật" NGAY khi bấm, bất kể setAudioRoute() có thực sự áp dụng được hay không -
        // khiến icon "nói dối" khi Telecom âm thầm bỏ qua yêu cầu (vd. có thiết bị Bluetooth
        // đang giữ quyền audio route). Giờ chỉ GỬI yêu cầu đổi route dựa trên trạng thái THẬT
        // đang có (isSpeakerOn()), rồi CHỜ Telecom xác nhận qua onCallAudioStateChanged() (đã
        // đăng ký ở onCreate) mới vẽ lại icon - icon luôn phản ánh đúng sự thật.
        b.ivSpeaker.setOnClickListener {
            val svc = CallUiService.instance
            if (svc == null) {
                Toast.makeText(this, "Không đổi được loa ngoài lúc này, thử lại sau", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            svc.setSpeakerOn(!svc.isSpeakerOn())
        }

        // Tắt / bật mic - cùng nguyên tắc như nút Loa ở trên: dựa vào trạng thái thật, để
        // callback vẽ lại icon thay vì tự đoán.
        b.btnMute.setOnClickListener {
            val svc = CallUiService.instance
            if (svc == null) {
                Toast.makeText(this, "Không đổi được mic lúc này, thử lại sau", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            svc.setMutedState(!isMuted)
        }

        // Phím bấm DTMF
        b.ivDialpad.setOnClickListener {
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

        // Ghi âm: KHÔNG tự mở MediaRecorder riêng (xem giải thích trong layout XML) - chỉ cho
        // biết trạng thái tự động ghi âm hiện tại (bật/tắt ở Cài đặt), tránh xung đột với
        // CallRecordingService đang tự chạy nền theo trạng thái cuộc gọi hệ thống.
        val autoRecordOn = com.h.simplecall.call.CallRecordingManager.isEnabled(this)
        b.tvRecordLabel.text = if (autoRecordOn) "Đang ghi âm" else getString(R.string.start_recording)
        if (autoRecordOn) {
            b.ivRecord.setBackgroundResource(R.drawable.bg_action_btn_active)
            b.ivRecord.setColorFilter(getColor(R.color.white))
        }
        b.btnRecord.setOnClickListener {
            Toast.makeText(this,
                if (com.h.simplecall.call.CallRecordingManager.isEnabled(this))
                    "Cuộc gọi này đang được tự động ghi âm (bật ở Cài đặt)"
                else "Tự động ghi âm đang TẮT - bật ở Cài đặt nếu muốn ghi âm cuộc gọi",
                Toast.LENGTH_LONG).show()
        }

        // "Gọi rõ ràng": khử tiếng ồn bằng NoiseSuppressor chuẩn Android. Hiệu quả tuỳ máy - nhiều
        // máy đường tiếng cuộc gọi đi qua modem, ứng dụng thường không can thiệp trực tiếp được.
        b.btnClarity.setOnClickListener {
            isClarityOn = !isClarityOn
            try {
                if (isClarityOn) {
                    if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                        noiseSuppressor = android.media.audiofx.NoiseSuppressor.create(0)
                        noiseSuppressor?.enabled = true
                        b.ivClarity.setBackgroundResource(R.drawable.bg_action_btn_active)
                        b.ivClarity.setColorFilter(getColor(R.color.white))
                    } else {
                        Toast.makeText(this, "Máy không hỗ trợ khử tiếng ồn", Toast.LENGTH_SHORT).show()
                        isClarityOn = false
                    }
                } else {
                    noiseSuppressor?.release(); noiseSuppressor = null
                    b.ivClarity.setBackgroundResource(R.drawable.bg_action_btn)
                    b.ivClarity.setColorFilter(getColor(R.color.text_primary))
                }
            } catch (_: Exception) {
                Toast.makeText(this, "Máy không hỗ trợ khử tiếng ồn", Toast.LENGTH_SHORT).show()
                isClarityOn = false
            }
        }

        // "Thêm cuộc gọi" và "Thêm": app chỉ quản lý 1 cuộc gọi tại 1 thời điểm - báo rõ thay vì
        // giả vờ hoạt động, giống các tính năng TODO khác trong app.
        b.btnAddCall.setOnClickListener {
            Toast.makeText(this, getString(R.string.feature_coming_soon), Toast.LENGTH_SHORT).show()
        }
        b.btnMore.setOnClickListener {
            Toast.makeText(this, getString(R.string.feature_coming_soon), Toast.LENGTH_SHORT).show()
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
