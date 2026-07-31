package com.h.simplecall.data

import android.content.Context
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.h.simplecall.data.firstLetterKey

/**
 * Bo nho dem + BAN SAO LUU danh ba dung chung cho ca app (singleton object).
 *
 * Toan bo danh ba may duoc sao luu vao 1 bang SQLite rieng cua app (xem [ContactsDbHelper]) ngay
 * trong lan dau tien app can toi danh ba. Tu do ve sau, MOI thao tac doc (hien thi danh sach, tra
 * ten theo so khi co cuoc goi, goi y so khi go ban phim...) deu doc tu ban sao luu nay thay vi
 * hoi lai ContactsContract cua he thong - nhanh hon han vi khong con ton chi phi goi lien tien
 * trinh (IPC) sang ContactsProvider cho tung thao tac.
 *
 * "Moi lan vao app": [ensureSynced] duoc goi tu MainActivity ngay khi mo app (khong can doi
 * nguoi dung bam vao tab Danh ba) de tu kiem tra danh ba may co gi moi/doi/xoa ke tu lan mo
 * truoc hay khong va cap nhat vao ban sao luu:
 *  - Lan DAU TIEN (ban sao luu con rong): quet FULL toan bo danh ba may 1 lan - day la buoc
 *    duy nhat co the mat chut thoi gian tuy so luong lien he, khong tranh duoc vi phai doc het
 *    du lieu goc lan dau.
 *  - Cac lan sau: chi quet CHENH LECH (lien he co CONTACT_LAST_UPDATED_TIMESTAMP moi hon moc lan
 *    truoc, cong them danh sach lien he da bi xoa tu DeletedContacts) - cuc nhanh vi gan nhu
 *    khong co gi thay doi giua 2 lan mo app lien tiep.
 */
object ContactsRepository {

    private const val PREFS = "contacts_sync_prefs"
    private const val KEY_LAST_SYNC = "last_sync_time_ms"

    @Volatile private var cache: List<Contact>? = null
    @Volatile private var syncedThisProcess = false

    /** Chỉ bảo vệ THAO TÁC NHANH trên cache trong RAM (đọc/ghi biến [cache]) - KHÔNG bao giờ
     *  được giữ trong lúc làm việc IO nặng (query ContactsProvider, ghi SQLite), để không chặn
     *  các coroutine khác chỉ cần ĐỌC cache trong lúc 1 coroutine khác đang đồng bộ. */
    private val cacheMutex = Mutex()

    /** Riêng cho quá trình đồng bộ (có thể mất vài giây nếu danh bạ lớn/lần đầu) - tách khỏi
     *  [cacheMutex] để KHÔNG chặn các lệnh đọc cache khác trong lúc đồng bộ đang chạy. Chỉ cần
     *  đảm bảo tối đa 1 lần đồng bộ chạy cùng lúc (2 coroutine cùng gọi ensureSynced() lúc app
     *  vừa mở sẽ chờ nhau ở đây thay vì cả 2 cùng quét trùng danh bạ). */
    private val syncMutex = Mutex()

    /** Co cache san hay chua - dung de quyet dinh co can hien loading hay khong. */
    fun peek(): List<Contact>? = cache

    /** Tra cache ngay neu co; neu chua co thi dam bao ban sao luu trong app da duoc dong bo
     *  (day du hoac chenh lech, xem [ensureSynced]) roi nap tu do vao cache. Neu luc goi CHUA co
     *  quyen doc danh ba, tra rong nhung KHONG luu cache - de lan goi ke tiep (sau khi co quyen)
     *  thu dong bo lai that, thay vi bi ket mai o ket qua rong. */
    suspend fun getContacts(context: Context): List<Contact> {
        cache?.let { return it }
        if (!hasPermission(context)) return emptyList()
        // Đồng bộ (có thể chậm) chạy TRƯỚC và NGOÀI cacheMutex - không giữ cacheMutex trong lúc
        // chờ đồng bộ xong, để những coroutine khác chỉ muốn ĐỌC cache (không cần đồng bộ) không
        // bị kẹt lại theo.
        ensureSynced(context)
        return cacheMutex.withLock {
            cache?.let { return@withLock it }
            ContactsDbHelper.get(context).queryAll()
                .sortedBy { if (firstLetterKey(it.name) == "#") 1 else 0 }
                .also { cache = it }
        }
    }

    /** Goi ngay khi app mo (MainActivity.onCreate/sau khi vua duoc cap quyen) de tu kiem tra va
     *  cap nhat ban sao luu, KHONG can doi nguoi dung mo tab Danh ba. An toan de goi nhieu lan -
     *  chi thuc su dong bo dung 1 lan cho moi lan chay tien trinh app (tru khi [invalidate] duoc
     *  goi lai, vi du khi phat hien danh ba he thong doi trong luc app dang mo). */
    suspend fun syncOnAppStart(context: Context) {
        if (!hasPermission(context)) return
        getContacts(context)
    }

    /** LỖI ĐÃ SỬA: trước đây withContext(Dispatchers.IO) nằm BÊN TRONG mutex.withLock dùng
     *  chung với cacheMutex - nghĩa là suốt thời gian fullSync/incrementalSync chạy (có thể vài
     *  giây với danh bạ lớn), MỌI coroutine khác chỉ muốn ĐỌC cache (khả năng cao đã có sẵn)
     *  cũng bị chặn theo vì cùng tranh 1 lock. Giờ dùng [syncMutex] riêng: lock này chỉ ảnh
     *  hưởng các coroutine CÙNG cần đồng bộ (đúng, cần chờ nhau để không quét trùng), không đụng
     *  chạm gì tới [cacheMutex] ở [getContacts]. */
    private suspend fun ensureSynced(context: Context) {
        // Kiểm tra NGOÀI lock trước để trả sớm cho đường đi thường (đã đồng bộ rồi) mà không cần
        // xếp hàng chờ syncMutex - vẫn AN TOÀN vì kiểm tra lại LẦN NỮA bên trong lock bên dưới
        // (double-checked locking): nếu 2 coroutine cùng thấy false ở đây, chỉ 1 trong 2 vào
        // được syncMutex trước, chạy xong set cờ = true, coroutine còn lại vào sau thấy đã true
        // thì return sớm ngay, không chạy lại.
        if (syncedThisProcess) return
        syncMutex.withLock {
            if (syncedThisProcess) return@withLock
            withContext(Dispatchers.IO) {
                val db = ContactsDbHelper.get(context)
                val lastSync = lastSyncTime(context)
                if (lastSync == 0L || db.isEmpty()) {
                    fullSync(context, db)
                } else {
                    incrementalSync(context, db, lastSync)
                }
            }
            syncedThisProcess = true
        }
    }

    private fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Tra ten lien he theo so dien thoai - doc thang tu BAN SAO LUU cua app (da co index theo
     *  so, tra rat nhanh, khong ton IPC sang ContactsProvider he thong nua). Dung cho lich su
     *  cuoc goi DI (Telecom khong tu dien callerDisplayName cho chieu goi di) va cho InCallActivity
     *  khi cuoc goi den khong kem san ten. */
    fun lookupNameByNumber(context: Context, number: String): String? {
        if (number.isBlank() || !hasPermission(context)) return null
        val norm = normalizePhoneNumber(number)
        if (norm.isBlank()) return null
        return try {
            ContactsDbHelper.get(context).queryByNormNumber(norm)?.name?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /** Tim cac lien he co so khop 1 phan voi [raw] (dang go o ban phim quay so) - doc tu cache
     *  RAM neu da co (gan nhu tuc thoi), roi ve doc thang ban sao luu SQLite cua app neu cache
     *  RAM chua kip nap (hiem khi xay ra vi [syncOnAppStart] da chay ngay luc mo app). KHONG con
     *  truy van ContactsContract.PhoneLookup cua he thong o day nua. */
    fun searchContacts(context: Context, raw: String): List<Contact> {
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return emptyList()

        // Substring match: tìm chuỗi digits xuất hiện bất kỳ đâu trong số đầy đủ (chỉ giữ chữ số).
        // Gõ "0703" → chỉ khớp số có chứa "0703" liên tiếp (vd. "0703123456", "0987070312").
        // Không dùng norm_number (9 số cuối) để tránh khớp nhầm: "0703" sẽ không khớp "0946440703"
        // vì "0946440703" không chứa chuỗi "0703" liên tiếp ở đầu.
        cache?.let { list ->
            return list.asSequence()
                .filter { it.number.isNotBlank() && it.number.filter(Char::isDigit).contains(digits) }
                .distinctBy { it.name to it.number }
                .toList()
        }
        if (!hasPermission(context)) return emptyList()
        return try {
            ContactsDbHelper.get(context).queryByDigitsContains(digits)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Dung lai Uri lien he he thong (dung de mo/sua lien he) tu du lieu DA LUU trong ban sao luu
     *  cua app (contact_id + lookup_key) - khong can hoi lai ContactsProvider. Chi khi so nay
     *  chua kip co trong ban sao luu (vd. lien he vua tao o noi khac, chua toi luot dong bo) moi
     *  roi ve tra truc tiep qua PhoneLookup cua he thong nhu phuong an du phong cuoi cung. */
    fun getContactUri(context: Context, number: String): android.net.Uri? {
        if (number.isBlank()) return null
        val norm = normalizePhoneNumber(number)
        try {
            val local = ContactsDbHelper.get(context).queryByNormNumber(norm)
            if (local != null && local.contactId > 0 && !local.lookupKey.isNullOrBlank()) {
                return ContactsContract.Contacts.getLookupUri(local.contactId, local.lookupKey)
            }
        } catch (_: Exception) {}
        if (!hasPermission(context)) return null
        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number)
            )
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.LOOKUP_KEY),
                null, null, null
            )?.use { cur ->
                if (cur.moveToFirst()) ContactsContract.Contacts.getLookupUri(cur.getLong(0), cur.getString(1))
                else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Quet TOAN BO danh ba may 1 lan (buoc "tao ban sao luu" ban dau) va ghi de vao bang SQLite
     *  cua app. TRUOC DAY: chi truy van Phone.CONTENT_URI - bo sot lien he khong co so. GIO: doc
     *  Contacts.CONTENT_URI truoc (liet ke DAY DU moi lien he ke ca khong co so), tra them so
     *  dien thoai cho tung lien he qua 1 truy van Phone.CONTENT_URI DUY NHAT (khong lap theo
     *  tung lien he - tranh N+1 query), roi ghi xuong SQLite bang 1 transaction + compiled
     *  statement tai su dung (xem ContactsDbHelper.replaceAll) de viec ghi hang nghin dong nhanh
     *  nhat co the. */
    private fun fullSync(context: Context, db: ContactsDbHelper) {
        if (!hasPermission(context)) return

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

        val rows = mutableListOf<ContactRow>()
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
                ContactsContract.Contacts.STARRED
            ), null, null, null
        )?.use { it ->
            val iId      = it.getColumnIndex(ContactsContract.Contacts._ID)
            val iKey     = it.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
            val iName    = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            val iPhoto   = it.getColumnIndex(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)
            val iStarred = it.getColumnIndex(ContactsContract.Contacts.STARRED)
            while (it.moveToNext()) {
                val contactId = it.getLong(iId)
                val lookupKey = it.getString(iKey)
                val name      = it.getString(iName) ?: ""
                val photo     = it.getString(iPhoto)
                val starred   = iStarred >= 0 && it.getInt(iStarred) != 0
                val numbers   = numbersByContactId[contactId]
                if (numbers.isNullOrEmpty()) {
                    // Lien he khong co so dien thoai -> bo qua hoan toan (theo yeu cau cu)
                } else {
                    numbers.forEach { num ->
                        rows.add(
                            ContactRow(
                                contactId = contactId, lookupKey = lookupKey, name = name,
                                number = num, normNumber = normalizePhoneNumber(num),
                                photoUri = photo, starred = starred
                            )
                        )
                    }
                }
            }
        }
        val deduped = rows.distinctBy { it.contactId to it.number.trim() }
        db.replaceAll(deduped)
        setLastSyncTime(context, System.currentTimeMillis())
    }

    private fun incrementalSync(context: Context, db: ContactsDbHelper, lastSync: Long) {
        if (!hasPermission(context)) return

        val changedIds = mutableListOf<Long>()
        val changedMeta = mutableMapOf<Long, Triple<String?, String, Pair<String?, Boolean>>>()
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
                ContactsContract.Contacts.STARRED
            ),
            "${ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP} > ?",
            arrayOf(lastSync.toString()), null
        )?.use { cur ->
            val iId      = cur.getColumnIndex(ContactsContract.Contacts._ID)
            val iKey     = cur.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
            val iName    = cur.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            val iPhoto   = cur.getColumnIndex(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)
            val iStarred = cur.getColumnIndex(ContactsContract.Contacts.STARRED)
            while (cur.moveToNext()) {
                val id = cur.getLong(iId)
                changedIds.add(id)
                changedMeta[id] = Triple(
                    cur.getString(iKey), cur.getString(iName) ?: "",
                    Pair(cur.getString(iPhoto), iStarred >= 0 && cur.getInt(iStarred) != 0)
                )
            }
        }

        val deletedIds = mutableSetOf<Long>()
        try {
            context.contentResolver.query(
                ContactsContract.DeletedContacts.CONTENT_URI,
                arrayOf(ContactsContract.DeletedContacts.CONTACT_ID),
                "${ContactsContract.DeletedContacts.CONTACT_DELETED_TIMESTAMP} > ?",
                arrayOf(lastSync.toString()), null
            )?.use { cur ->
                val iId = cur.getColumnIndex(ContactsContract.DeletedContacts.CONTACT_ID)
                while (cur.moveToNext()) deletedIds.add(cur.getLong(iId))
            }
        } catch (_: Exception) {
        }

        val allTouchedIds = (changedIds.asSequence() + deletedIds.asSequence()).toMutableSet()
        if (allTouchedIds.isEmpty()) {
            setLastSyncTime(context, System.currentTimeMillis())
            return
        }

        val numbersByContactId = mutableMapOf<Long, MutableList<String>>()
        if (changedIds.isNotEmpty()) {
            // Batch 500 ID/lần vì ContentResolver cũng có giới hạn độ dài selection string
            for (chunk in changedIds.chunked(500)) {
                val placeholders = chunk.joinToString(",") { "?" }
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} IN ($placeholders)",
                    chunk.map { it.toString() }.toTypedArray(), null
                )?.use { cur ->
                    val iId  = cur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    val iNum = cur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (cur.moveToNext()) {
                        val num = cur.getString(iNum) ?: continue
                        numbersByContactId.getOrPut(cur.getLong(iId)) { mutableListOf() }.add(num)
                    }
                }
            }
        }

        val newRows = mutableListOf<ContactRow>()
        for (id in changedIds) {
            val (lookupKey, name, photoStarred) = changedMeta[id] ?: continue
            val (photo, starred) = photoStarred
            val numbers = numbersByContactId[id]
            if (!numbers.isNullOrEmpty()) {
                numbers.forEach { num ->
                    newRows.add(
                        ContactRow(
                            contactId = id, lookupKey = lookupKey, name = name,
                            number = num, normNumber = normalizePhoneNumber(num),
                            photoUri = photo, starred = starred
                        )
                    )
                }
            }
        }

        db.applyIncrementalChanges(allTouchedIds, newRows.distinctBy { it.contactId to it.number.trim() })
        setLastSyncTime(context, System.currentTimeMillis())
    }

    private fun lastSyncTime(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_SYNC, 0L)

    private fun setLastSyncTime(context: Context, time: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LAST_SYNC, time).apply()
    }

    /** Goi khi biet chac danh ba he thong vua doi (them/sua/xoa so) trong luc app dang chay, de
     *  lan doc ke tiep buoc phai dong bo lai (chenh lech, van nhanh) thay vi tra cache cu. */
    fun invalidate() {
        cache = null
        syncedThisProcess = false
    }
}
