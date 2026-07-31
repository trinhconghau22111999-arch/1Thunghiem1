package com.h.simplecall.data

data class Contact(
    val name: String,
    val number: String,
    val photoUri: String? = null,
    val starred: Boolean = false, // đã được đánh dấu sao (yêu thích) trong danh bạ hệ thống
    // contactId + lookupKey: lưu lại từ lần đồng bộ để mở/sửa liên hệ hệ thống (ACTION_EDIT)
    // hoặc dựng lại Uri liên hệ mà KHÔNG cần truy vấn lại ContactsContract - xem
    // ContactsRepository.getContactUri(). Mặc định rỗng cho các Contact tạo tạm (vd. số lạ).
    val contactId: Long = 0L,
    val lookupKey: String? = null
)

data class CallLogEntry(
    val name: String,
    val number: String,
    val type: Int,
    val date: Long,
    val simSlot: Int? = null,      // 0 = SIM 1, 1 = SIM 2
    val numberType: String = "",   // "Di động", "Việt Nam", v.v.
    val duration: Long = 0         // giây - dùng để hiển thị "Chưa được kết nối" khi = 0
)
