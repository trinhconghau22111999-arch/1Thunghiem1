package com.h.simplecall.call

import android.content.Context
import android.content.SharedPreferences

object BlockedNumbersManager {

    private const val PREF = "blocked_numbers"
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    fun isBlocked(number: String): Boolean =
        prefs?.getBoolean(normalize(number), false) ?: false

    fun block(number: String) =
        prefs?.edit()?.putBoolean(normalize(number), true)?.apply()

    fun unblock(number: String) =
        prefs?.edit()?.remove(normalize(number))?.apply()

    fun toggle(number: String): Boolean {
        return if (isBlocked(number)) { unblock(number); false }
        else { block(number); true }
    }

    private fun normalize(n: String) = n.filter { it.isDigit() || it == '+' }
}
