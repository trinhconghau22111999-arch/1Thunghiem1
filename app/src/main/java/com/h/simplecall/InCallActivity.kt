package com.h.simplecall

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.ContactsContract
import android.telecom.Call
import android.telephony.SubscriptionManager
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.h.simplecall.call.CallManager
import com.h.simplecall.databinding.ActivityInCallBinding

class InCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInCallBinding
    private var isMuted   = false
    private var isSpeaker = false
    private var dtmfVisible = false
    private var isHeld = false
    private var isRecording = false
    private var isClarityOn = false
    private var recorder: MediaRecorder? = null
    private var recordingFile: java.io.File? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    private val timerHandler = Handler(Looper.getMainLooper())
    private var callStartMs = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            val e = (System.currentTimeMillis() - callStartMs) / 1000
            val h = e / 3600; val m = (e % 3600) / 60; val s = e % 60
            binding.tvCallStatus.text =
                if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
            timerHandler.postDelayed(this, 1000)
        }
    }

    private val listener: (Call?, Int) -> Unit = { call, state ->
        runOnUiThread { updateUi(call, state) }
    }

    private val avatarBgs  = intArrayOf(R.color.av0,R.color.av1,R.color.av2,R.color.av3,R.color.av4,R.color.av5)
    private val avatarTxts = intArrayOf(R.color.av0t,R.color.av1t,R.color.av2t,R.color.av3t,R.color.av4t,R.color.av5t)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAnswer.setOnClickListener  { CallManager.answer() }
        binding.btnDecline.setOnClickListener { CallManager.reject() }
        binding.btnEndCall.setOnClickListener { CallManager.hangup() }

        binding.btnMute.setOnClickListener {
            isMuted = !isMuted
            CallManager.toggleMute(isMuted)
            binding.btnMute.setImageResource(
                if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
            binding.tvMuteLabel.text = "Im lặng"
        }

        binding.btnSpeaker.setOnClickListener {
            isSpeaker = !isSpeaker
            CallManager.setSpeaker(isSpeaker)
            binding.btnSpeaker.imageTintList = android.content.res.ColorStateList.valueOf(
                if (isSpeaker) android.graphics.Color.parseColor("#4A90E2")
                else android.graphics.Color.WHITE
            )
        }

        // DTMF toggle
        binding.btnDtmf.setOnClickListener {
            dtmfVisible = !dtmfVisible
            binding.dtmfPanel.visibility = if (dtmfVisible) View.VISIBLE else View.GONE
        }

        // DTMF keys
        val dtmfGrid = binding.dtmfPanel
        for (i in 0 until dtmfGrid.childCount) {
            val btn = dtmfGrid.getChildAt(i) as? Button ?: continue
            val tag = (btn.tag as? String)?.firstOrNull() ?: continue
            btn.setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        CallManager.playDtmf(tag)
                        haptic()
                    }
                    android.view.MotionEvent.ACTION_UP -> CallManager.stopDtmf()
                }
                false
            }
        }

        // Giữ máy: dùng đúng API Telecom (call.hold()/unhold()), không phải giả lập UI
        binding.btnHold.setOnClickListener {
            isHeld = !isHeld
            if (isHeld) CallManager.currentCall?.hold() else CallManager.currentCall?.unhold()
            binding.tvHoldLabel.text = getString(if (isHeld) R.string.unhold_call else R.string.hold_call)
        }

        binding.btnRecord.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }

        // "Gọi rõ ràng": khử tiếng ồn bằng AudioEffect chuẩn của Android (NoiseSuppressor).
        // LƯU Ý: đây không phải công nghệ tăng cường giọng nói độc quyền như máy Samsung thật -
        // hiệu quả tuỳ thuộc chip xử lý âm thanh của từng máy, và trên nhiều thiết bị hầu như
        // không có tác dụng rõ rệt với đường tiếng của cuộc gọi (đường tiếng cuộc gọi thường đi
        // qua phần cứng modem, ứng dụng thường không can thiệp trực tiếp được).
        binding.btnClarity.setOnClickListener {
            isClarityOn = !isClarityOn
            try {
                if (isClarityOn) {
                    if (NoiseSuppressor.isAvailable()) {
                        noiseSuppressor = NoiseSuppressor.create(0)
                        noiseSuppressor?.enabled = true
                    } else {
                        Toast.makeText(this, "Máy không hỗ trợ khử tiếng ồn", Toast.LENGTH_SHORT).show()
                        isClarityOn = false
                    }
                } else {
                    noiseSuppressor?.release(); noiseSuppressor = null
                }
            } catch (_: Exception) {
                Toast.makeText(this, "Máy không hỗ trợ khử tiếng ồn", Toast.LENGTH_SHORT).show()
                isClarityOn = false
            }
        }

        // "Thêm cuộc gọi" (ghép cuộc gọi thứ 2/hội nghị) và "Thêm" (tuỳ chọn khác) chưa được
        // xây dựng đầy đủ - app hiện chỉ quản lý 1 cuộc gọi tại một thời điểm (CallManager chỉ
        // giữ currentCall duy nhất), nên chưa thể ghép/giữ nhiều cuộc gọi cùng lúc một cách an
        // toàn. Thông báo rõ cho người dùng thay vì giả vờ hoạt động.
        binding.btnAddCall.setOnClickListener {
            Toast.makeText(this, getString(R.string.feature_coming_soon), Toast.LENGTH_SHORT).show()
        }
        binding.btnMore.setOnClickListener {
            Toast.makeText(this, getString(R.string.feature_coming_soon), Toast.LENGTH_SHORT).show()
        }

        CallManager.addListener(listener)
        updateUi(CallManager.currentCall, CallManager.currentCall?.state ?: Call.STATE_NEW)

        // Nút Back: cuộc gọi CHƯA kết thúc thì màn hình này phải luôn tồn tại được (xem lại
        // qua mục đa nhiệm) - không được finish()/đóng hẳn như hành vi Back mặc định. Chỉ lùi
        // về nền giống hệt bấm Home. Màn hình chỉ thực sự đóng khi cuộc gọi kết thúc thật (xem
        // updateUi() gọi finish() khi state == DISCONNECTED).
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
    }

    override fun onDestroy() {
        timerHandler.removeCallbacksAndMessages(null) // huỷ mọi callback đang chờ, kể cả
        // postDelayed({ finish() }, 1500) ẩn danh - removeCallbacks(timerRunnable) trước đây
        // chỉ huỷ đúng 1 Runnable (đồng hồ đếm giờ), bỏ sót các finish() hẹn giờ khác.
        CallManager.removeListener(listener)
        contactLookupExecutor.shutdownNow()
        if (isRecording) stopRecording()
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, getString(R.string.recording_failed), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val dir = java.io.File(getExternalFilesDir(null), "CallRecordings").apply { mkdirs() }
            val file = java.io.File(dir, "call_${System.currentTimeMillis()}.m4a")
            val rec = MediaRecorder()
            // VOICE_CALL ghi được cả 2 chiều tiếng trên một số máy, nhưng nhiều hãng/ROM
            // (đặc biệt Android 10 trở lên) CHẶN nguồn ghi âm này vì lý do riêng tư của người
            // gọi tới. Nếu không dùng được, thử lại bằng MIC (chỉ ghi được giọng người dùng).
            try {
                rec.setAudioSource(MediaRecorder.AudioSource.VOICE_CALL)
            } catch (_: Exception) {
                rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            recordingFile = file
            isRecording = true
            binding.btnRecord.setBackgroundResource(android.R.color.transparent)
            binding.tvRecordLabel.text = getString(R.string.stop_recording)
        } catch (e: Exception) {
            // Rất nhiều máy (đặc biệt Samsung/Xiaomi các đời mới) chặn hẳn việc ghi âm cuộc
            // gọi ở tầng hệ thống bất kể quyền đã cấp - báo rõ cho người dùng thay vì im lặng.
            Toast.makeText(this, getString(R.string.recording_failed), Toast.LENGTH_LONG).show()
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null; isRecording = false
        }
    }

    private fun stopRecording() {
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        isRecording = false
        binding.btnRecord.setBackgroundResource(android.R.color.transparent)
        binding.tvRecordLabel.text = getString(R.string.start_recording)
        recordingFile?.let {
            Toast.makeText(this, "${getString(R.string.recording_saved)}: ${it.name}", Toast.LENGTH_LONG).show()
        }
    }

    private var trackedCall: Call? = null
    private var isOutgoingCall = false

    // Truy vấn danh bạ chạy nền: tránh block main thread (nguyên nhân gây ANR "Gọi Điện tiếp tục dừng")
    private val contactLookupExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastLookedUpNumber: String? = null
    private var lastContactInfo: Pair<String, android.net.Uri?>? = null

    /** Làm mờ + vô hiệu hoá 4 nút chỉ có tác dụng lúc đang nói chuyện thật (Ghi âm, Giữ,
     *  Rõ tiếng, Thêm cuộc gọi) trong lúc cuộc gọi ĐI còn đang đổ chuông/kết nối, chưa ai bắt
     *  máy - bấm vào lúc này chưa có ý nghĩa gì. "Im lặng", "Thêm" và "Loa ngoài" vẫn hữu ích
     *  ngay cả khi chưa kết nối (tắt mic trước, mở menu thêm, hoặc bật loa ngoài để nghe tiếng
     *  đổ chuông/thông báo) nên KHÔNG đụng tới 3 nút đó. */
    private fun setPreConnectDimming(dim: Boolean) {
        // 4 icon chỉ dùng được SAU KHI đã kết nối (Ghi âm/Giữ/Rõ ràng/Thêm cuộc gọi): mờ hẳn
        // (0.35) khi đang đổ chuông/kết nối, sáng đủ (1.0) khi đã kết nối.
        val dimIconAlpha = if (dim) 0.35f else 1f
        listOf(binding.btnRecord, binding.btnHold, binding.btnClarity, binding.btnAddCall)
            .forEach { it.alpha = dimIconAlpha }
        // Chữ nhãn dưới 4 icon trên: mờ thêm 1 bậc (0.35) khi icon còn mờ; khi icon đã sáng đủ
        // (đã kết nối) thì chữ CHỈ sáng bằng 50% màu số điện thoại - không sáng ngang icon.
        listOf(binding.tvRecordLabel, binding.tvHoldLabel, binding.tvClarityLabel, binding.tvAddCallLabel)
            .forEach { it.alpha = if (dim) 0.35f else 0.5f }

        // btnMute/btnMore/btnSpeaker/btnDtmf LUÔN sáng (1.0) bất kể đã kết nối hay chưa - dùng
        // được ngay cả khi đang đổ chuông.
        binding.btnMute.alpha = 1f
        binding.btnMore.alpha = 1f
        binding.btnSpeaker.alpha = 1f
        binding.btnDtmf.alpha = 1f
        // Nhãn chữ Im lặng/Thêm: CHỈ lúc CHƯA kết nối (dim=true) mới sáng ngang icon (100%) -
        // vì đây là 2 nút duy nhất còn dùng được lúc đó nên cần nổi bật rõ ràng. Khi ĐÃ kết
        // nối, quay lại mức 50% chuẩn giống mọi nhãn khác (không còn lý do nổi bật hơn nữa).
        val muteMoreLabelAlpha = if (dim) 1f else 0.5f
        binding.tvMuteLabel.alpha = muteMoreLabelAlpha
        binding.tvMoreLabel.alpha = muteMoreLabelAlpha

        binding.btnRecord.isEnabled = !dim
        binding.btnHold.isEnabled = !dim
        binding.btnClarity.isEnabled = !dim
        binding.btnAddCall.isEnabled = !dim
        // Nút Kết thúc KHÔNG bao giờ bị mờ/vô hiệu hoá - luôn phải cúp máy được dù đang đổ
        // chuông hay đã kết nối.
    }

    private fun updateUi(call: Call?, state: Int) {
        // Khi đối phương TỪ CHỐI cuộc gọi hoặc máy đang bận (DisconnectCause.BUSY): không được
        // finish() ngay và im lặng thoát ra như các trường hợp kết thúc bình thường khác - phải
        // hiện "Đường dây bận" thay cho "Đang gọi...", đồng thời làm mờ các icon chỉ dùng được
        // sau khi đã kết nối, giữ màn hình 1.5 giây để người dùng đọc được trước khi tự đóng.
        if (call != null && state == Call.STATE_DISCONNECTED) {
            val cause = call.details?.disconnectCause?.code
            if (cause == android.telecom.DisconnectCause.BUSY) {
                timerHandler.removeCallbacks(timerRunnable)
                binding.tvCallStatus.text = "Đường dây bận"
                setPreConnectDimming(true)
                timerHandler.postDelayed({ finish() }, 1500)
                return
            }
        }
        if (call == null) {
            timerHandler.removeCallbacks(timerRunnable)
            // Bất kể số ĐÃ lưu danh bạ hay CHƯA lưu: đều phải hiện "Cuộc gọi đã kết thúc" và
            // GIỮ NGUYÊN icon SIM + nhãn "Việt Nam"/loại số bên dưới tên/số (không ẩn đi).
            // Giữ màn hình 1.5 giây, đủ thời gian đọc, rồi tự đóng.
            binding.tvCallStatus.text = "Cuộc gọi đã kết thúc"
            timerHandler.postDelayed({ finish() }, 1500)
            return
        }
        if (state == Call.STATE_DISCONNECTED) {
            timerHandler.removeCallbacks(timerRunnable)
            finish(); return
        }

        // Xác định gọi đi/gọi đến MỘT LẦN DUY NHẤT khi nhận call, không tính lại theo state.
        // Trước đây dùng "state != STATE_RINGING" mỗi lần cập nhật UI: sau khi TRẢ LỜI một
        // cuộc gọi ĐẾN, trạng thái chuyển ACTIVE khiến điều kiện này hiểu nhầm thành gọi đi,
        // có thể hiển thị nhầm số của lần chuyển hướng trước đó thay vì số người gọi đến.
        if (call !== trackedCall) {
            trackedCall = call
            isOutgoingCall = CallManager.isOutgoingCall(call, state)
        }

        // Số hiển thị trên màn hình gọi - CŨNG LÀ số sẽ được lưu vào lịch sử cuộc gọi
        // (xem CallManager.resolveDisplayNumber + CallHistoryManager).
        val number = CallManager.resolveDisplayNumber(call, isOutgoingCall)

        // Tra cứu tên + ảnh trong danh bạ CHẠY NỀN (contentResolver.query có thể chậm,
        // không được gọi trên main thread vì sẽ gây treo/ANR). Kết quả được cache theo số
        // để tránh truy vấn lại mỗi lần trạng thái cuộc gọi đổi (ringing -> active -> ...).
        if (number != lastLookedUpNumber) {
            lastLookedUpNumber = number
            lastContactInfo = null
            renderCallerInfo(number, null, state)
            contactLookupExecutor.execute {
                val info = lookupContact(number)
                mainHandler.post {
                    // Bỏ qua nếu số đã thay đổi trong lúc chờ (ví dụ chuyển sang cuộc gọi khác)
                    if (number == lastLookedUpNumber) {
                        lastContactInfo = info
                        renderCallerInfo(number, info, state)
                    }
                }
            }
        } else {
            renderCallerInfo(number, lastContactInfo, state)
        }

        val isRinging = state == Call.STATE_RINGING
        binding.incomingControls.visibility = if (isRinging) View.VISIBLE else View.GONE
        binding.activeControls.visibility   = if (isRinging) View.GONE   else View.VISIBLE

        if (binding.llSimLine.tag != call) {
            binding.llSimLine.tag = call
            renderSimLine(call)
        }

        when (state) {
            Call.STATE_RINGING -> {
                timerHandler.removeCallbacks(timerRunnable)
                binding.tvCallStatus.text = getString(R.string.incoming_call)
                binding.tvHdBadge.visibility = View.GONE
            }
            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                timerHandler.removeCallbacks(timerRunnable)
                binding.tvCallStatus.text = "Đang gọi..."
                binding.tvHdBadge.visibility = View.GONE
                // Chưa kết nối được với đầu bên kia: các nút chỉ có tác dụng LÚC ĐANG NÓI
                // CHUYỆN (Ghi âm, Giữ, Rõ tiếng, Thêm cuộc gọi) làm mờ đi + vô hiệu hoá tạm
                // thời. "Im lặng" và "Thêm" vẫn dùng được bình thường nên giữ nguyên độ sáng.
                setPreConnectDimming(true)
            }
            Call.STATE_ACTIVE -> {
                isHeld = false
                binding.btnHold.setBackgroundResource(android.R.color.transparent)
                binding.tvHoldLabel.text = getString(R.string.hold_call)
                binding.tvHdBadge.visibility = View.VISIBLE
                // Đã kết nối - trả lại độ sáng/bật lại đầy đủ các nút vừa bị làm mờ lúc đang gọi.
                setPreConnectDimming(false)
                if (callStartMs == 0L) {
                    callStartMs = System.currentTimeMillis()
                    timerHandler.post(timerRunnable)
                }
            }
            Call.STATE_HOLDING -> {
                isHeld = true
                binding.btnHold.setBackgroundResource(android.R.color.transparent)
                binding.tvHoldLabel.text = getString(R.string.unhold_call)
                timerHandler.removeCallbacks(timerRunnable)
                binding.tvCallStatus.text = "Đang giữ máy"
                binding.tvHdBadge.visibility = View.GONE
            }
            Call.STATE_DISCONNECTING -> {
                timerHandler.removeCallbacks(timerRunnable)
                binding.tvCallStatus.text = "Đang kết thúc..."
            }
        }
    }

    /** Hiện icon SIM (1/2) đang dùng để gọi, dựa vào PhoneAccountHandle của cuộc gọi.
     *  Máy 1 SIM hoặc không tra được subscription thì ẩn icon này đi. Không hiện tên nhà
     *  mạng/quốc gia nữa - chỉ icon thẻ SIM + số bên trong. */
    private fun renderSimLine(call: Call) {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) { binding.llSimLine.visibility = View.GONE; return }
            val subId = call.details?.accountHandle?.id?.toIntOrNull()
            val info = if (subId != null)
                getSystemService(SubscriptionManager::class.java)?.getActiveSubscriptionInfo(subId)
            else null
            if (info != null) {
                binding.llSimLine.visibility = View.VISIBLE
                binding.tvSimBadge.text = (info.simSlotIndex + 1).toString()
            } else {
                binding.llSimLine.visibility = View.GONE
            }
        } catch (_: Exception) {
            binding.llSimLine.visibility = View.GONE
        }
    }

    private fun renderCallerInfo(number: String, contactInfo: Pair<String, android.net.Uri?>?, state: Int) {
        val displayName = when {
            contactInfo != null -> contactInfo.first
            number.isNotEmpty() -> formatNumberForDisplay(number)
            else -> "Không xác định"
        }
        val photoUri = contactInfo?.second

        binding.tvCallerName.text = displayName
        // Số CHƯA lưu danh bạ → font light; đã lưu → thin (mảnh hơn bold ~50%)
        if (contactInfo == null) {
            binding.tvCallerName.setTypeface(
                android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL))
        } else {
            binding.tvCallerName.setTypeface(
                android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL))
        }
        val isVietnamFormat = number.startsWith("0")
        // Hiện "Việt Nam" ở MỌI trạng thái cuộc gọi (đang gọi/đã kết nối/kết thúc/bận), kể cả
        // số lạ chưa lưu danh bạ - đúng theo ảnh mẫu cập nhật (số lạ cũng hiện ngay từ lúc
        // "Đang gọi..."). Số ĐÃ lưu danh bạ: "số | Việt Nam". Số CHƯA lưu: chỉ "Việt Nam" một
        // mình (không lặp số vì số đã hiện to ở tvCallerName/displayName phía trên rồi).
        if (number.isNotEmpty() && isVietnamFormat) {
            binding.tvCallerNumber.text = if (contactInfo != null)
                getString(R.string.number_with_carrier, formatNumberForDisplay(number), "Việt Nam")
            else "Việt Nam"
            binding.tvCallerNumber.visibility = View.VISIBLE
        } else {
            binding.tvCallerNumber.visibility = View.GONE
        }

    }

    private fun formatNumberForDisplay(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return raw
        return if (raw.startsWith("+")) {
            when {
                digits.length <= 2 -> "+$digits"
                digits.length <= 5 -> "+${digits.take(2)} ${digits.drop(2)}"
                digits.length <= 8 -> "+${digits.take(2)} ${digits.drop(2).take(3)} ${digits.drop(5)}"
                else -> "+${digits.take(2)} ${digits.drop(2).take(3)} ${digits.drop(5).take(3)} ${digits.drop(8)}"
            }
        } else {
            when {
                digits.length <= 4 -> digits
                digits.length <= 7 -> "${digits.take(4)} ${digits.drop(4)}"
                else -> "${digits.take(4)} ${digits.drop(4).take(3)} ${digits.drop(7)}"
            }
        }
    }

    private fun lookupContact(number: String): Pair<String, android.net.Uri?>? {
        if (number.isEmpty()) return null
        val uri = android.net.Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            android.net.Uri.encode(number)
        )
        val cursor = contentResolver.query(uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI,
                ContactsContract.PhoneLookup._ID),
            null, null, null) ?: return null
        return cursor.use {
            if (!it.moveToFirst()) return null
            val name = it.getString(0) ?: return null
            val photoUriStr = it.getString(1)
            val photoUri = if (photoUriStr != null) android.net.Uri.parse(photoUriStr) else null
            Pair(name, photoUri)
        }
    }

    private fun haptic() {
        val v = getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") v.vibrate(30)
    }
}
