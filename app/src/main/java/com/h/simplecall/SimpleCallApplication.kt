package com.h.simplecall

import android.app.Application

/** Áp dụng giao diện Sáng/Tối đã lưu (mặc định Sáng) ngay khi app khởi động - xem ThemePrefs.kt.
 *  Ngoài ra không cần khởi tạo gì thêm vì lịch sử cuộc gọi đọc thẳng từ CallLog hệ thống, không
 *  dùng Room DB nội bộ. */
class SimpleCallApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemePrefs.applySavedTheme(this) // gọi TRƯỚC MỌI THỨ KHÁC để tránh nhấp nháy đổi giao diện
    }
}
