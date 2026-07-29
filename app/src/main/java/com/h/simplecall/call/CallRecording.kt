package com.h.simplecall.call

/**
 * Metadata của 1 bản ghi âm cuộc gọi đã lưu.
 * @param number số điện thoại đầu dây bên kia (đã chuẩn hoá thành chỉ chữ số khi so khớp).
 * @param filePath đường dẫn tuyệt đối tới file .m4a trong bộ nhớ riêng của app.
 * @param startTimeMillis thời điểm bắt đầu ghi (System.currentTimeMillis()).
 * @param durationSeconds thời lượng ghi được, tính khi dừng ghi.
 */
data class CallRecording(
    val number: String,
    val filePath: String,
    val startTimeMillis: Long,
    val durationSeconds: Long
)
