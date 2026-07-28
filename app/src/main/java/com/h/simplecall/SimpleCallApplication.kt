package com.h.simplecall

import android.app.Application
import com.h.simplecall.call.CallHistoryManager

/**
 * Trước đây CallHistoryManager (và việc di trú lịch sử cũ từ CallLog hệ thống) chỉ được khởi
 * tạo trong MyInCallService.onCreate() - mà InCallService CHỈ được hệ thống bind khi có cuộc
 * gọi. Nghĩa là nếu người dùng mở app nhưng CHƯA gọi lần nào, DB lịch sử của app còn trống,
 * "Gần đây" sẽ trống dù CallLog hệ thống đã có sẵn lịch sử cũ.
 *
 * Khởi tạo ở đây (Application.onCreate) đảm bảo CallHistoryManager.init() - và migration đi
 * kèm - luôn chạy NGAY KHI APP MỞ, bất kể người dùng vào bằng cách nào (mở từ launcher, hay
 * có cuộc gọi đến). MyInCallService.init(this) gọi lại vẫn an toàn vì có cờ `initialized`
 * chặn khởi tạo trùng.
 */
class SimpleCallApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CallHistoryManager.init(this)
    }
}
