package com.h.simplecall.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * CSDL SQLite RIÊNG của app, dùng làm bản sao lưu (backup) toàn bộ danh bạ máy vào trong app.
 *
 * Vì sao cần bảng này thay vì cứ truy vấn ContactsContract của hệ thống mỗi lần cần:
 *  - Mỗi lần gọi sang ContactsProvider của hệ thống đều tốn chi phí IPC (liên tiến trình), và
 *    với danh bạ vài nghìn số, quét toàn bộ có thể mất tới vài giây (xem ghi chú cũ trong
 *    DialerFragment.queryContactSuggestions).
 *  - Đọc từ bảng SQLite của CHÍNH app (đã có index sẵn) trên cùng tiến trình nhanh hơn nhiều so
 *    với đi qua ContactsProvider, đặc biệt cho các thao tác lặp lại liên tục như tra tên theo số
 *    khi có cuộc gọi đến/gọi đi, hoặc gợi ý số khi đang gõ ở bàn phím quay số.
 *
 * Đồng bộ (sync) từ danh bạ máy vào bảng này được thực hiện ở ContactsRepository:
 *  - Lần đầu (bảng rỗng): quét TOÀN BỘ danh bạ máy 1 lần (không tránh được, đây chính là bước
 *    "tạo bản sao lưu" ban đầu - chấp nhận có thể hơi lâu tuỳ số lượng liên hệ trên máy).
 *  - Từ lần thứ 2 trở đi (mỗi lần mở app): CHỈ quét các liên hệ có thay đổi kể từ lần đồng bộ
 *    trước (dựa vào CONTACT_LAST_UPDATED_TIMESTAMP + DeletedContacts của hệ thống) - rất nhanh
 *    vì hầu như không có gì thay đổi giữa các lần mở app.
 */
class ContactsDbHelper private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "contacts_backup.db"
        private const val DB_VERSION = 1
        const val TABLE = "contacts"

        @Volatile private var instance: ContactsDbHelper? = null
        fun get(context: Context): ContactsDbHelper =
            instance ?: synchronized(this) {
                instance ?: ContactsDbHelper(context).also { instance = it }
            }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // WAL: cho phép đọc và ghi song song không chặn nhau - đọc danh bạ để hiện danh sách
        // không bị khựng lại nếu đúng lúc đó có 1 lượt đồng bộ đang ghi ở nền.
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                contact_id INTEGER NOT NULL,
                lookup_key TEXT,
                name TEXT NOT NULL DEFAULT '',
                number TEXT NOT NULL DEFAULT '',
                norm_number TEXT NOT NULL DEFAULT '',
                photo_uri TEXT,
                starred INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        // Index cho tra cứu theo số (lookupNameByNumber, gợi ý số khi bấm bàn phím) và theo
        // contact_id (đồng bộ tăng dần - xoá/nạp lại đúng các liên hệ vừa đổi).
        db.execSQL("CREATE INDEX idx_contacts_norm_number ON $TABLE(norm_number)")
        db.execSQL("CREATE INDEX idx_contacts_contact_id ON $TABLE(contact_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    /** Có bản sao nào trong app chưa - dùng để quyết định lần đầu phải quét FULL hay chỉ cần
     *  đồng bộ CHÊNH LỆCH (incremental). */
    fun isEmpty(): Boolean {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null)?.use { c ->
            if (c.moveToFirst()) return c.getLong(0) == 0L
        }
        return true
    }

    /** Ghi đè TOÀN BỘ bảng bằng danh sách mới - dùng cho lần đồng bộ ĐẦU TIÊN (tạo bản sao lưu).
     *  Dùng 1 transaction + 1 compiled statement tái sử dụng cho mọi dòng (thay vì gọi
     *  db.insert()/ContentValues riêng cho từng dòng) để việc ghi hàng nghìn liên hệ nhanh nhất
     *  có thể - đây là phần "chịu lâu" duy nhất, chỉ xảy ra đúng 1 lần trong đời app. */
    fun replaceAll(rows: List<ContactRow>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM $TABLE")
            insertRows(db, rows)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Đồng bộ CHÊNH LỆCH: xoá các dòng cũ thuộc những contact_id vừa đổi/vừa bị xoá rồi nạp lại
     *  đúng các dòng mới cho những contact_id vừa đổi - KHÔNG đụng tới các liên hệ không đổi,
     *  nên luôn nhanh bất kể tổng số liên hệ trên máy nhiều hay ít. */
    fun applyIncrementalChanges(changedOrDeletedContactIds: Set<Long>, newRows: List<ContactRow>) {
        if (changedOrDeletedContactIds.isEmpty() && newRows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (changedOrDeletedContactIds.isNotEmpty()) {
                // Xóa theo batch 500 ID/lần để tránh vượt giới hạn độ dài SQL của SQLite
                // (giới hạn mặc định ~1MB/câu lệnh, với >1000 ID nối thẳng sẽ bắt đầu rủi ro).
                val idList = changedOrDeletedContactIds.toList()
                for (chunk in idList.chunked(500)) {
                    val placeholders = chunk.joinToString(",") { "?" }
                    db.execSQL(
                        "DELETE FROM $TABLE WHERE contact_id IN ($placeholders)",
                        chunk.map { it.toString() }.toTypedArray()
                    )
                }
            }
            insertRows(db, newRows)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun insertRows(db: SQLiteDatabase, rows: List<ContactRow>) {
        if (rows.isEmpty()) return
        val stmt = db.compileStatement(
            "INSERT INTO $TABLE (contact_id, lookup_key, name, number, norm_number, photo_uri, starred) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)"
        )
        for (r in rows) {
            stmt.clearBindings()
            stmt.bindLong(1, r.contactId)
            stmt.bindString(2, r.lookupKey ?: "")
            stmt.bindString(3, r.name)
            stmt.bindString(4, r.number)
            stmt.bindString(5, r.normNumber)
            if (r.photoUri != null) stmt.bindString(6, r.photoUri) else stmt.bindNull(6)
            stmt.bindLong(7, if (r.starred) 1 else 0)
            stmt.executeInsert()
        }
    }

    /** Đọc toàn bộ bản sao lưu trong app - dùng để nạp vào cache trong bộ nhớ (RAM) của
     *  ContactsRepository, phục vụ hiển thị danh sách + lọc/tìm kiếm tức thời. */
    fun queryAll(): List<Contact> {
        val list = mutableListOf<Contact>()
        readableDatabase.rawQuery(
            "SELECT name, number, photo_uri, starred, contact_id, lookup_key FROM $TABLE", null
        )?.use { c ->
            while (c.moveToNext()) {
                list.add(
                    Contact(
                        name = c.getString(0) ?: "",
                        number = c.getString(1) ?: "",
                        photoUri = c.getString(2),
                        starred = c.getInt(3) != 0,
                        contactId = c.getLong(4),
                        lookupKey = c.getString(5)?.takeIf { it.isNotEmpty() }
                    )
                )
            }
        }
        return list
    }

    /** Tra 1 liên hệ theo số đã chuẩn hoá (norm_number, có index) - dùng cho tra tên theo số khi
     *  có cuộc gọi đến/đi và khi CHƯA có cache trong RAM (mới mở tiến trình app). */
    fun queryByNormNumber(normNumber: String): Contact? {
        if (normNumber.isBlank()) return null
        readableDatabase.rawQuery(
            "SELECT name, number, photo_uri, starred, contact_id, lookup_key FROM $TABLE " +
                "WHERE norm_number = ? ORDER BY starred DESC, name ASC LIMIT 1",
            arrayOf(normNumber)
        )?.use { c ->
            if (c.moveToFirst()) {
                return Contact(
                    name = c.getString(0) ?: "",
                    number = c.getString(1) ?: "",
                    photoUri = c.getString(2),
                    starred = c.getInt(3) != 0,
                    contactId = c.getLong(4),
                    lookupKey = c.getString(5)?.takeIf { it.isNotEmpty() }
                )
            }
        }
        return null
    }

    /** Tìm các liên hệ có số chứa đúng dãy số [digits] - dùng làm phương án dự phòng khi cache
     *  RAM chưa kịp nạp (rất hiếm khi xảy ra vì app đã tự đồng bộ ngay lúc mở, xem
     *  ContactsRepository.ensureSynced). Vẫn đọc từ bảng CỦA APP, không đụng ContactsContract. */
    fun queryByDigitsContains(digits: String, startsFromBeginning: Boolean = false): List<Contact> {
        if (digits.isBlank()) return emptyList()
        val list = mutableListOf<Contact>()
        // Nếu user gõ từ đầu số (0xxx/+84): dùng prefix match trên cột number gốc.
        // Nếu gõ chuỗi giữa/đuôi: substring match trên norm_number (9 số cuối).
        val (column, pattern) = if (startsFromBeginning)
            "replace(replace(number,' ',''),'-','') LIKE ?" to "$digits%"
        else
            "norm_number LIKE ?" to "%$digits%"
        readableDatabase.rawQuery(
            "SELECT name, number, photo_uri, starred, contact_id, lookup_key FROM $TABLE " +
                "WHERE $column LIMIT 50",
            arrayOf(pattern)
        )?.use { c ->
            while (c.moveToNext()) {
                list.add(
                    Contact(
                        name = c.getString(0) ?: "",
                        number = c.getString(1) ?: "",
                        photoUri = c.getString(2),
                        starred = c.getInt(3) != 0,
                        contactId = c.getLong(4),
                        lookupKey = c.getString(5)?.takeIf { it.isNotEmpty() }
                    )
                )
            }
        }
        return list
    }
}

/** 1 dòng dữ liệu chuẩn bị để ghi vào bảng contacts - contactId dùng để nhóm/đồng bộ theo liên
 *  hệ hệ thống, normNumber dùng để tra cứu nhanh theo số (xem [normalizePhoneNumber]). */
data class ContactRow(
    val contactId: Long,
    val lookupKey: String?,
    val name: String,
    val number: String,
    val normNumber: String,
    val photoUri: String?,
    val starred: Boolean
)

/** Biên dịch 1 LẦN DUY NHẤT khi class được nạp, dùng lại cho mọi lần gọi [firstLetterKey] -
 *  trước đây Regex bị biên dịch MỚI mỗi lần hàm này chạy (gọi ~2 lần/liên hệ: 1 lần lúc sắp xếp
 *  trong ContactsRepository, 1 lần lúc nhóm chữ cái trong ContactsAdapter) - với danh bạ vài
 *  nghìn số, riêng việc biên dịch Regex lặp lại đã cộng dồn thành độ trễ đáng kể, góp phần vào
 *  cảm giác "tra cứu danh bạ chậm". */
private val DIACRITIC_MARK_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")

/** Lấy chữ cái đầu (đã bỏ dấu, in hoa) của tên để sắp xếp/phân nhóm danh bạ. Trả về "#" cho
 *  tên bắt đầu bằng ký tự không phải chữ cái Latin/tiếng Việt.
 *  Đã chuyển từ ContactsAdapter.kt (ui layer) vào đây để ContactsRepository (data layer) có thể
 *  dùng mà không cần import ngược chiều data -> ui. */
fun firstLetterKey(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "#"
    val first = trimmed[0]
    if (!first.isLetter()) return "#"
    val upper = first.uppercaseChar()
    if (upper == 'Đ') return "Đ"
    val base = java.text.Normalizer.normalize(upper.toString(), java.text.Normalizer.Form.NFD)
        .replace(DIACRITIC_MARK_REGEX, "")
    return if (base.isNotEmpty() && base[0] in 'A'..'Z') base[0].toString() else "#"
}

/** Chuẩn hoá số điện thoại để so khớp bất kể cách ghi (khoảng trắng, dấu gạch, +84 vs 0...):
 *  chỉ giữ lại chữ số rồi lấy 9 chữ số CUỐI (đủ để phân biệt số VN mà không lệ thuộc đầu số
 *  vùng/quốc gia) - cùng quy ước "takeLast(9)" đã dùng ở CallHistoryFragment cho việc so khớp
 *  CallLog, nay dùng chung cho cả bảng danh bạ để tra cứu nhất quán trong toàn app. */
fun normalizePhoneNumber(raw: String): String = raw.filter { it.isDigit() }.takeLast(9)
