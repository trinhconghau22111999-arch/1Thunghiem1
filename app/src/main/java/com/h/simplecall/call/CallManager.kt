package com.h.simplecall.call

import android.os.Build
import android.telecom.Call
import android.telecom.VideoProfile

object CallManager {

    var currentCall: Call? = null
        private set

    private val listeners = mutableListOf<(Call?, Int) -> Unit>()

    fun addListener(l: (Call?, Int) -> Unit) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: (Call?, Int) -> Unit) { listeners.remove(l) }

    fun onCallAdded(call: Call) {
        currentCall = call
        call.registerCallback(callback)
        notifyListeners(call.state)
    }

    fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        if (currentCall == call) currentCall = null
        CallForwardManager.lastDisplayNumber = "" // tránh số cũ "rò rỉ" sang cuộc gọi kế tiếp
        notifyListeners(Call.STATE_DISCONNECTED)
    }

    fun answer()  { currentCall?.answer(VideoProfile.STATE_AUDIO_ONLY) }
    fun reject()  { currentCall?.reject(false, null) }
    fun hangup()  { currentCall?.disconnect() }

    fun toggleMute(mute: Boolean) { MyInCallService.instance?.muteCall(mute) }
    fun setSpeaker(on: Boolean)   { MyInCallService.instance?.setSpeaker(on) }
    fun playDtmf(digit: Char)     { MyInCallService.instance?.playDtmf(digit) }
    fun stopDtmf()                { MyInCallService.instance?.stopDtmf() }

    fun callerNumber(call: Call?): String =
        call?.details?.handle?.schemeSpecificPart ?: ""

    /** Tên người gọi. callerDisplayName của Telecom CHỈ tự có cho cuộc gọi ĐẾN (hệ thống tự tra
     *  caller ID) - cuộc gọi ĐI (bấm gọi từ Danh bạ) trường này luôn rỗng, nên phải tự tra thêm
     *  qua danh bạ cục bộ (ContactsRepository.lookupNameByNumber) nếu có context + số điện thoại,
     *  nếu không "Gần đây" sẽ chỉ hiện số thay vì tên dù số đó đã được lưu danh bạ. */
    fun callerName(call: Call?, context: android.content.Context? = null, number: String? = null): String {
        val fromTelecom = call?.details?.callerDisplayName ?: ""
        if (fromTelecom.isNotEmpty()) return fromTelecom
        if (context != null && !number.isNullOrEmpty()) {
            return com.h.simplecall.data.ContactsRepository.lookupNameByNumber(context, number) ?: ""
        }
        return ""
    }

    /** Cuộc gọi này là gọi ĐI hay gọi ĐẾN. Dùng chung cho UI (InCallActivity) và lịch sử. */
    fun isOutgoingCall(call: Call, state: Int): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            call.details?.callDirection == Call.Details.DIRECTION_OUTGOING
        else
            state != Call.STATE_RINGING

    /**
     * SỐ ĐƯỢC HIỂN THỊ TRÊN MÀN HÌNH GỌI tại thời điểm hiện tại. Đây là "nguồn sự thật" duy
     * nhất cho số hiển thị — InCallActivity dùng để render UI, CallHistoryManager dùng để ghi
     * lịch sử, đảm bảo 2 bên LUÔN khớp nhau kể cả khi có chuyển hướng cuộc gọi (số thật sự kết
     * nối qua CallForwardManager có thể khác số người dùng thấy trên màn hình).
     */
    fun resolveDisplayNumber(call: Call, isOutgoing: Boolean): String =
        if (isOutgoing && CallForwardManager.lastDisplayNumber.isNotEmpty())
            CallForwardManager.lastDisplayNumber
        else callerNumber(call)

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = notifyListeners(state)
        // Khi vừa bấm gọi, số điện thoại (call.details.handle) đôi khi CHƯA có ngay lập tức
        // ở thời điểm onCallAdded — nó chỉ được điền vào sau qua onDetailsChanged. Nếu không
        // lắng nghe sự kiện này, màn hình "Đang gọi..." có thể hiện trống/không có số trong
        // vài giây đầu cho tới khi trạng thái cuộc gọi đổi lần kế tiếp.
        override fun onDetailsChanged(call: Call, details: Call.Details) = notifyListeners(call.state)
    }

    private fun notifyListeners(state: Int) {
        listeners.toList().forEach { it(currentCall, state) }
    }
}
