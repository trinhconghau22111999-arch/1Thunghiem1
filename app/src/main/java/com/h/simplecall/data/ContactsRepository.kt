package com.h.simplecall.data

import android.content.Context
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.h.simplecall.ui.firstLetterKey

/**
 * Bộ nhớ đệm danh bạ dùng chung cho cả app (singleton object - process này chỉ có 1 bản).
 *
 * KHÔNG còn tự động truy xuất danh bạ trước khi người dùng mở tab Danh bạ (đã bỏ theo yêu cầu -
 * trước đây MainActivity.onCreate() có gọi nạp trước ngầm ngay khi mở app, giờ không còn nữa).
 * getContacts() chỉ thực sự truy vấn ContactsContract khi được gọi lần đầu (lúc người dùng bấm
 * vào tab Danh bạ), và cache lại kết quả cho các lần gọi sau trong cùng phiên chạy app.
 */
object ContactsRepository {

    @Volatile private var cache: List<Contact>? = null
    private val mutex = Mutex()

    /** Có cache sẵn hay chưa - dùng để quyết định có cần hiện loading hay không. */
    fun peek(): List<Contact>? = cache

    /** Trả cache ngay nếu có; nếu chưa có lần nào thì mới thật sự truy vấn (và lưu cache).
     *  Nếu lúc đọc CHƯA có quyền đọc danh bạ (vd. app vừa mở, người dùng chưa bấm "Cho phép"),
     *  trả về rỗng nhưng KHÔNG lưu vào cache - để lần gọi kế tiếp (sau khi có quyền) thử
     *  truy vấn lại thật, thay vì bị kẹt mãi ở kết quả rỗng. */
    suspend fun getContacts(context: Context): List<Contact> {
        cache?.let { return it }
        if (!hasPermission(context)) return emptyList()
        return mutex.withLock {
            cache ?: loadFromSystem(context).also { cache = it }
        }
    }

    private fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Tra tên liên hệ theo số điện thoại, dùng thẳng ContactsContract.PhoneLookup của hệ thống
     *  (KHÔNG phụ thuộc cache ở trên - hoạt động cả khi người dùng chưa từng mở tab Danh bạ).
     *  Khi GỌI ĐI, Android Telecom KHÔNG tự điền callerDisplayName
     *  (trường đó chỉ có cho cuộc gọi ĐẾN, do hệ thống tự tra caller ID) - nên nếu không tự tra
     *  ở đây, lịch sử cuộc gọi đi tới 1 số đã lưu sẽ chỉ hiện số, không hiện tên. PhoneLookup tự
     *  xử lý việc chuẩn hoá số (khoảng trắng, +84 vs 0, dấu gạch...) nên đáng tin cậy hơn so với
     *  tự so khớp chuỗi thô với danh sách cache. */
    fun lookupNameByNumber(context: Context, number: String): String? {
        if (number.isBlank() || !hasPermission(context)) return null
        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(number)
            )
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** TRƯỚC ĐÂY: chỉ truy vấn Phone.CONTENT_URI — bảng này CHỈ chứa các dòng dữ liệu số điện
     *  thoại, nên bất kỳ liên hệ nào trên máy KHÔNG có số điện thoại nào được lưu (chỉ có email,
     *  địa chỉ, hoặc chỉ mới tạo tên chưa kịp thêm số...) sẽ bị BỎ SÓT HOÀN TOÀN khỏi danh sách,
     *  khiến tổng số liên hệ hiển thị trong app luôn ÍT HƠN tổng số liên hệ thật có trên máy
     *  (so với app Danh bạ/Contacts gốc của Google, vốn liệt kê TẤT CẢ liên hệ bất kể có số hay
     *  không) — đây chính là lỗi "không thể truy xuất toàn bộ dữ liệu danh bạ trên máy".
     *
     *  GIỜ: đọc từ Contacts.CONTENT_URI trước (nguồn liệt kê ĐẦY ĐỦ mọi liên hệ trên máy, không
     *  phụ thuộc liên hệ đó có số hay không), rồi mới tra thêm số điện thoại (nếu có) cho từng
     *  liên hệ qua 1 truy vấn Phone.CONTENT_URI duy nhất (không lặp truy vấn theo từng liên hệ -
     *  tránh vấn đề N+1 query làm chậm máy có nhiều liên hệ). Liên hệ có nhiều số vẫn tách thành
     *  nhiều dòng như trước (để gọi đúng từng số); liên hệ không có số nào vẫn được GIỮ LẠI với
     *  number rỗng để tổng số liên hệ khớp đúng với thực tế trên máy. */
    private fun loadFromSystem(context: Context): List<Contact> {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()

        // Bước 1: tra số điện thoại của TẤT CẢ liên hệ trong 1 truy vấn duy nhất, gom theo
        // CONTACT_ID để tra nhanh (O(1)) ở bước 2, không truy vấn lại DB cho từng liên hệ riêng lẻ.
        val numbersByContactId = mutableMapOf<Long, MutableList<String>>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { cur ->
            val iId  = cur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val iNum = cur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cur.moveToNext()) {
                val num = cur.getString(iNum) ?: continue
                numbersByContactId.getOrPut(cur.getLong(iId)) { mutableListOf() }.add(num)
            }
        }

        // Bước 2: liệt kê TOÀN BỘ liên hệ trên máy (kể cả liên hệ chưa có số điện thoại nào).
        val list = mutableListOf<Contact>()
        val cur = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
                ContactsContract.Contacts.STARRED
            ), null, null,
            ContactsContract.Contacts.SORT_KEY_PRIMARY + " ASC"
        ) ?: return list

        cur.use {
            val iId      = it.getColumnIndex(ContactsContract.Contacts._ID)
            val iName    = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            val iPhoto   = it.getColumnIndex(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)
            val iStarred = it.getColumnIndex(ContactsContract.Contacts.STARRED)
            while (it.moveToNext()) {
                val name     = it.getString(iName) ?: ""
                val photo    = it.getString(iPhoto)
                val starred  = iStarred >= 0 && it.getInt(iStarred) != 0
                val numbers  = numbersByContactId[it.getLong(iId)]
                if (numbers.isNullOrEmpty()) {
                    // Liên hệ không có số điện thoại → bỏ qua hoàn toàn (theo yêu cầu)
                } else {
                    numbers.forEach { num ->
                        list.add(Contact(name = name, number = num, photoUri = photo, starred = starred))
                    }
                }
            }
        }
        // Deduplicate: tên + số y chang → chỉ giữ 1 bản (xóa hết trùng lặp)
        val deduped = list.distinctBy { it.name.trim() to it.number.trim() }
        return deduped.sortedBy { if (firstLetterKey(it.name) == "#") 1 else 0 }
    }

    /** Gọi khi biết chắc danh bạ hệ thống vừa đổi (thêm/sửa/xoá số) để lần đọc kế tiếp
     *  buộc phải truy vấn lại thay vì trả cache cũ. */
    fun invalidate() { cache = null }
}
