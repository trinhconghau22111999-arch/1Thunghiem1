package com.h.simplecall

import android.app.Application

/** Application đơn giản - không cần khởi tạo gì thêm vì lịch sử cuộc gọi
 *  giờ đọc thẳng từ CallLog hệ thống, không dùng Room DB nội bộ nữa. */
class SimpleCallApplication : Application()
