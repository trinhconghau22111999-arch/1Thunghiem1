package com.h.simplecall.call

import android.content.Context
import android.content.SharedPreferences

object CallForwardManager {

    private const val PREF_NAME = "call_forward_prefs"
    private const val KEY_ENABLED = "forward_enabled"
    private const val KEY_TARGET  = "forward_target"

    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    var isEnabled: Boolean
        get() = prefs?.getBoolean(KEY_ENABLED, false) ?: false
        set(v) { prefs?.edit()?.putBoolean(KEY_ENABLED, v)?.apply() }

    var targetNumber: String
        get() = prefs?.getString(KEY_TARGET, "") ?: ""
        set(v) { prefs?.edit()?.putString(KEY_TARGET, v)?.apply() }

    /** Số người dùng nhập – chỉ dùng để hiển thị UI khi forward bật */
    var lastDisplayNumber: String = ""

    fun resolveNumber(original: String): String {
        val t = targetNumber
        return if (isEnabled && t.length >= 9) t else original
    }

    /** Gọi khi đặt số đi – lưu display number, reset nếu forward tắt */
    fun prepareCall(number: String) {
        lastDisplayNumber = if (isEnabled && targetNumber.length >= 9) number else ""
    }
}
