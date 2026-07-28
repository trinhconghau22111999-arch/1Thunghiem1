package com.h.simplecall.call

import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import com.h.simplecall.InCallActivity

class MyInCallService : InCallService() {

    companion object {
        var instance: MyInCallService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        MissedCallNotifier.init(this)
        BlockedNumbersManager.init(this)
    }

    override fun onDestroy() { instance = null; super.onDestroy() }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)

        val number = call.details?.handle?.schemeSpecificPart ?: ""

        // Chặn cuộc gọi đến nếu số bị block
        if (call.state == Call.STATE_RINGING && BlockedNumbersManager.isBlocked(number)) {
            call.reject(false, null)
            return
        }

        CallManager.onCallAdded(call)

        // Đăng ký callback để detect missed call
        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(c: Call, state: Int) {
                if (state == Call.STATE_DISCONNECTED
                    && c.details?.disconnectCause?.code ==
                    android.telecom.DisconnectCause.MISSED
                ) {
                    val name = c.details?.callerDisplayName ?: ""
                    MissedCallNotifier.show(this@MyInCallService, number, name)
                }
            }
        })

        startActivity(Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        CallManager.onCallRemoved(call)
    }

    fun muteCall(mute: Boolean) = setMuted(mute)

    fun setSpeaker(on: Boolean) {
        setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE)
    }

    fun playDtmf(digit: Char) {
        CallManager.currentCall?.playDtmfTone(digit)
    }

    fun stopDtmf() {
        CallManager.currentCall?.stopDtmfTone()
    }
}
