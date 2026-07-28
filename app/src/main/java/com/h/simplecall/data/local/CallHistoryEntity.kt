package com.h.simplecall.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.h.simplecall.data.CallLogEntry

/**
 * Một dòng lịch sử cuộc gọi do CHÍNH APP tự ghi lại (không đọc/ghi qua CallLog provider
 * của hệ thống Android nữa).
 *
 * QUAN TRỌNG: [number] luôn là SỐ ĐƯỢC HIỂN THỊ TRÊN MÀN HÌNH GỌI (InCallActivity) tại thời
 * điểm cuộc gọi diễn ra — kể cả khi có chuyển hướng cuộc gọi (CallForwardManager) làm số thực
 * sự được kết nối khác với số hiển thị. Đây là quy tắc mặc định bắt buộc của lịch sử cuộc gọi.
 */
@Entity(tableName = "call_history")
data class CallHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String = "",                 // tên liên hệ (nếu có) tại thời điểm gọi
    val number: String,                    // số HIỂN THỊ TRÊN MÀN HÌNH GỌI
    val date: Long,                        // thời điểm bắt đầu cuộc gọi (epoch millis)
    val type: Int,                         // dùng lại hằng số CallLog.Calls: INCOMING/OUTGOING/MISSED_TYPE
    val duration: Long = 0,                // giây
    val simSlot: Int? = null,              // 0 = SIM 1, 1 = SIM 2
    val numberType: String = "",           // "Di động", "Việt Nam", v.v.
    val isNew: Boolean = false             // cuộc gọi nhỡ chưa xem (thay cho CallLog.Calls.NEW)
)

/** Chuyển sang model UI dùng chung cho toàn app (CallLogAdapter, CallHistoryFragment, ...). */
fun CallHistoryEntity.toCallLogEntry() = CallLogEntry(
    name = name,
    number = number,
    type = type,
    date = date,
    simSlot = simSlot,
    numberType = numberType,
    duration = duration
)
