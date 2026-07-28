package com.h.simplecall

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * Lưu & áp dụng lựa chọn giao diện Sáng/Tối của người dùng - ĐỘC LẬP với cài đặt tối/sáng của
 * hệ thống Android (dùng AppCompatDelegate.setDefaultNightMode() để ép app dùng đúng bộ
 * values/ (Sáng) hoặc values-night/ (Tối) bất kể máy đang bật/tắt Dark mode hệ thống).
 *
 * Mặc định: SÁNG khi người dùng chưa từng chọn (lần đầu mở app) - đúng yêu cầu.
 */
object ThemePrefs {
    private const val PREFS = "theme_prefs"
    private const val KEY_DARK = "dark_mode_enabled"

    /** Gọi CÀNG SỚM CÀNG TỐT - lý tưởng là dòng đầu tiên trong Application.onCreate(), TRƯỚC
     *  khi bất kỳ Activity nào được tạo, để tránh hiện tượng nhấp nháy đổi giao diện. */
    fun applySavedTheme(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode(context)) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    fun isDarkMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DARK, false) // mặc định false = Sáng

    /** Gọi khi người dùng đổi lựa chọn ở màn Cài đặt - lưu lại VÀ áp dụng ngay lập tức
     *  (setDefaultNightMode sẽ tự động recreate() mọi Activity đang mở để đổi giao diện tức thì,
     *  không cần code gọi recreate() thủ công). */
    fun setDarkMode(context: Context, dark: Boolean) {
        prefs(context).edit().putBoolean(KEY_DARK, dark).apply()
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
