package com.h.simplecall.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.provider.CallLog
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Lưu cài đặt "Tự động ghi âm cuộc gọi" + danh mục các bản ghi âm đã tạo.
 *
 * KHÔNG dùng Room DB (đúng hướng đi hiện tại của app - xem SimpleCallApplication.kt), chỉ lưu
 * 1 mảng JSON đơn giản trong SharedPreferences, đủ dùng vì số lượng bản ghi âm không lớn.
 *
 * GIỚI HẠN QUAN TRỌNG CẦN BIẾT (đọc kỹ trước khi bật tính năng này):
 * - Từ Android 10 trở lên, chỉ ứng dụng HỆ THỐNG mới được cấp quyền CAPTURE_AUDIO_OUTPUT để
 *   ghi trực tiếp âm thanh cuộc gọi (MediaRecorder.AudioSource.VOICE_CALL). Ứng dụng thường
 *   (kể cả khi đã là app gọi điện mặc định) sẽ KHÔNG dùng được nguồn này trên hầu hết máy đời
 *   mới (Pixel, Samsung/Xiaomi bản ROM mới...) - hãng cố tình chặn vì lý do riêng tư.
 * - App vẫn thử VOICE_CALL trước (một số ROM cũ/tuỳ biến vẫn cho phép), nếu thất bại sẽ rơi
 *   xuống VOICE_COMMUNICATION rồi MIC - hai nguồn này CHỈ chắc chắn thu được giọng của CHÍNH
 *   người dùng máy, giọng đầu dây bên kia có thể bị mất hoặc rất nhỏ tuỳ máy/tuỳ có bật loa
 *   ngoài hay không (bật loa ngoài khi ghi âm sẽ tăng khả năng mic bắt được cả 2 chiều).
 * - Ghi âm cuộc gọi có thể vi phạm pháp luật ở một số nơi nếu không thông báo cho người kia -
 *   người dùng cần tự tìm hiểu quy định tại nơi mình sinh sống trước khi bật tính năng này.
 */
object CallRecordingManager {

    private const val PREFS = "call_recording_prefs"
    private const val KEY_ENABLED = "auto_record_enabled"
    private const val KEY_ENTRIES = "recording_entries_json"
    private const val KEY_THIRD_PARTY_PKG = "third_party_recorder_package"
    private const val FOLDER_NAME = "CallRecordings"

    /** Ngưỡng thời gian mặc định coi 1 file ghi âm là "của" 1 cuộc gọi cụ thể (xem giải thích ở
     *  getForCallLogEntry). Dùng chung cho cả khớp bản ghi nội bộ lẫn quét file bên thứ 3. */
    private const val DEFAULT_MATCH_TOLERANCE_MS = 2 * 60_000L

    /** Từ khoá tên thư mục / đường dẫn hay gặp ở các app ghi âm cuộc gọi bên thứ 3 phổ biến (Ghi
     *  âm cuộc gọi tự động MIUI, Cube ACR, Boldbeast, CallRecorder S, Automatic Call Recorder...).
     *  Dùng để LỌC BỚT các file audio không liên quan (nhạc, ghi âm giọng nói thường...) khi quét
     *  toàn bộ MediaStore, vì không biết trước app bên thứ 3 mà người dùng chọn sẽ lưu file ở thư
     *  mục cụ thể nào. */
    private val THIRD_PARTY_FOLDER_KEYWORDS = listOf(
        "callrecord", "call record", "call recording", "call_rec", "recordings/call",
        "phone record", "phonerecord", "acr", "cube", "boldbeast", "sound_recorder",
        "callrecorder", "ghi âm cuộc gọi", "ghi am cuoc goi"
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Package name của app ghi âm bên thứ 3 mà user đã chọn. null = chưa chọn. */
    fun getThirdPartyRecorderPackage(ctx: Context): String? =
        prefs(ctx).getString(KEY_THIRD_PARTY_PKG, null).takeIf { !it.isNullOrBlank() }

    fun setThirdPartyRecorderPackage(ctx: Context, packageName: String?) {
        prefs(ctx).edit().putString(KEY_THIRD_PARTY_PKG, packageName ?: "").apply()
        // Khi đã chọn app bên thứ 3, bật enabled luôn để CallStateReceiver biết cần launch
        setEnabled(ctx, packageName != null)
    }

    /** Tìm tất cả app ghi âm đang cài trên máy (có thể mở được bằng MAIN/LAUNCHER).
     *  Dùng để hiện picker cho user chọn. */
    fun findRecorderApps(ctx: Context): List<android.content.pm.ResolveInfo> {
        val pm = ctx.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        val all = pm.queryIntentActivities(intent, 0)
        // Lọc theo package name và label — chỉ dùng keyword rõ ràng liên quan đến ghi âm,
        // KHÔNG dùng keyword chung chung ("audio", "voice", "easy") để tránh lọt app không liên quan.
        val pkgKeywords  = listOf("record", "recorder", "callrecord", "acr", "cube.acr", "boldbeast")
        val nameKeywords = listOf("ghi âm", "ghi am", "recorder", "record call", "acr", "call recorder")
        return all.filter { ri ->
            val name = ri.loadLabel(pm).toString().lowercase()
            val pkg  = ri.activityInfo.packageName.lowercase()
            pkgKeywords.any { pkg.contains(it) } || nameKeywords.any { name.contains(it) }
        }.sortedBy { it.loadLabel(pm).toString() }
    }

    /** Trả toàn bộ app đang cài (cho picker "chọn thủ công"). */
    fun findAllApps(ctx: Context): List<android.content.pm.ResolveInfo> {
        val pm = ctx.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        return pm.queryIntentActivities(intent, 0).sortedBy { it.loadLabel(pm).toString() }
    }

    /** Thư mục lưu file ghi âm - nằm trong bộ nhớ riêng của app (không cần xin quyền lưu trữ,
     *  tự xoá khi gỡ app), nhưng người dùng vẫn lấy ra được qua Chia sẻ (FileProvider). */
    fun recordingsDir(ctx: Context): File {
        val base = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: ctx.filesDir
        return File(base, FOLDER_NAME).apply { if (!exists()) mkdirs() }
    }

    /** Tên file duy nhất theo số + thời điểm bắt đầu, dễ tra ngược nếu cần. */
    fun newFileFor(ctx: Context, number: String, startTimeMillis: Long): File {
        val safeNumber = number.filter { it.isDigit() }.ifEmpty { "unknown" }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(startTimeMillis)
        return File(recordingsDir(ctx), "${ts}_$safeNumber.m4a")
    }

    /** Lưu 1 bản ghi vào danh mục sau khi ghi xong. */
    fun addEntry(ctx: Context, recording: CallRecording) {
        val arr = readAll(ctx)
        arr.put(JSONObject().apply {
            put("number", recording.number)
            put("filePath", recording.filePath)
            put("startTimeMillis", recording.startTimeMillis)
            put("durationSeconds", recording.durationSeconds)
        })
        prefs(ctx).edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    /** Xoá 1 bản ghi. Áp dụng cho CẢ 2 trường hợp: bản ghi nội bộ (file nằm trong recordingsDir
     *  của app) LẪN file do app ghi âm bên thứ 3 tạo (không có entry trong prefs, nên đoạn xoá
     *  khỏi prefs bên dưới chỉ đơn giản không tìm thấy gì để xoá - không lỗi).
     *  @return true nếu file đã thật sự bị xoá khỏi máy (hoặc vốn không còn tồn tại), false nếu
     *  file bên thứ 3 vẫn còn đó (thường do hệ điều hành không cho app này xoá file KHÔNG do
     *  chính nó tạo ra khi truy cập trực tiếp qua đường dẫn - giới hạn của scoped storage từ
     *  Android 10 trở lên, không phải lỗi của app). */
    fun deleteEntry(ctx: Context, recording: CallRecording): Boolean {
        val file = File(recording.filePath)
        var deleted = !file.exists() || runCatching { file.delete() }.getOrDefault(false)
        if (!deleted) {
            // Fallback: xoá qua MediaStore bằng đúng file đã lập chỉ mục (đường dẫn khớp cột
            // DATA) - vẫn xoá được nếu file do CHÍNH app này sở hữu trong MediaStore, hoặc trên
            // Android <10 (chưa có scoped storage). Với file thật sự do app khác sở hữu trên
            // Android 10+, hệ thống sẽ ném SecurityException - bắt lại, coi như không xoá được.
            deleted = runCatching {
                val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val rows = ctx.contentResolver.delete(
                    uri, "${MediaStore.Audio.Media.DATA} = ?", arrayOf(recording.filePath)
                )
                rows > 0
            }.getOrDefault(false)
        }
        val arr = readAll(ctx)
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("filePath") != recording.filePath) kept.put(o)
        }
        prefs(ctx).edit().putString(KEY_ENTRIES, kept.toString()).apply()
        return deleted
    }

    fun getAll(ctx: Context): List<CallRecording> {
        val arr = readAll(ctx)
        val list = mutableListOf<CallRecording>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                CallRecording(
                    number = o.optString("number"),
                    filePath = o.optString("filePath"),
                    startTimeMillis = o.optLong("startTimeMillis"),
                    durationSeconds = o.optLong("durationSeconds")
                )
            )
        }
        return list.sortedByDescending { it.startTimeMillis }
    }

    /** So khớp lỏng: cùng 9 số cuối là coi như cùng 1 số điện thoại (giống cách CallHistoryFragment
     *  đang so khớp CallLog), để không bị lệch vì đầu số +84/0 khác nhau.
     *
     *  Gộp cả 2 nguồn: bản ghi nội bộ (do chính app này tự ghi) VÀ file do app ghi âm BÊN THỨ 3
     *  tạo ra (khi user chọn dùng app khác để ghi thay vì để app này tự ghi) - xem
     *  [scanThirdPartyAudioFiles]. File bên thứ 3 được khớp với số điện thoại gián tiếp qua thời
     *  điểm các cuộc gọi tới/đi số đó trong Nhật ký cuộc gọi hệ thống, vì bản thân file đó không
     *  lưu kèm số điện thoại nào cả. */
    fun getForNumber(ctx: Context, number: String): List<CallRecording> {
        val target = number.filter { it.isDigit() }.takeLast(9)
        if (target.isEmpty()) return emptyList()
        val internal = getAll(ctx).filter { it.number.filter(Char::isDigit).takeLast(9) == target }

        val thirdParty = mutableListOf<CallRecording>()
        val candidates = scanThirdPartyAudioFiles(ctx)
        if (candidates.isNotEmpty()) {
            // Không gán trùng 1 file cho 2 cuộc gọi khác nhau khi số này gọi nhiều lần.
            val usedPaths = mutableSetOf<String>()
            callLogDatesForNumber(ctx, number).forEach { date ->
                findThirdPartyRecording(number, date, DEFAULT_MATCH_TOLERANCE_MS, candidates, usedPaths)
                    ?.let { thirdParty += it }
            }
        }
        return (internal + thirdParty)
            .distinctBy { it.filePath }
            .sortedByDescending { it.startTimeMillis }
    }

    /** Tìm ĐÚNG bản ghi âm của 1 dòng cụ thể trong "Nhật ký cuộc gọi" (thay vì cả danh sách theo
     *  số) - dùng khi bấm vào 1 dòng lịch sử để mở lại bản ghi của riêng cuộc gọi đó. Vì không
     *  lưu chung ID với CallLog hệ thống, so khớp gần đúng: cùng số + thời điểm bắt đầu ghi cách
     *  thời điểm CallLog ghi nhận không quá [toleranceMillis] (mặc định 2 phút, đủ rộng để chấp
     *  nhận độ trễ nhỏ giữa lúc Telecom báo OFFHOOK và lúc CallLog thực sự ghi dòng mới, nhưng đủ
     *  hẹp để không lấy nhầm sang cuộc gọi khác cùng số ở thời điểm khác trong ngày). Nếu có nhiều
     *  ứng viên trong ngưỡng, chọn bản ghi gần nhất về thời gian.
     *
     *  [getForNumber] ở trên đã gộp sẵn cả nguồn nội bộ lẫn nguồn bên thứ 3, nên chỉ cần lọc theo
     *  ngưỡng thời gian ở đây - bản ghi nào gần callDateMillis nhất sẽ được chọn dù đến từ nguồn
     *  nào, tự nhiên "ưu tiên" nguồn nào khớp thời gian tốt hơn mà không cần phân biệt thủ công.
     */
    fun getForCallLogEntry(
        ctx: Context, number: String, callDateMillis: Long,
        toleranceMillis: Long = DEFAULT_MATCH_TOLERANCE_MS
    ): CallRecording? {
        return getForNumber(ctx, number)
            .filter { kotlin.math.abs(it.startTimeMillis - callDateMillis) <= toleranceMillis }
            .minByOrNull { kotlin.math.abs(it.startTimeMillis - callDateMillis) }
    }

    /** Có quyền đọc audio ngoài (tên quyền khác nhau tuỳ phiên bản Android) để quét file bên thứ
     *  3 hay chưa. KHÔNG tự xin quyền ở đây - việc xin quyền do MainActivity lo lúc mở app; nếu
     *  chưa có quyền, các hàm quét bên dưới đơn giản trả về rỗng (coi như không tìm thấy file bên
     *  thứ 3, fallback về đúng bản ghi nội bộ nếu có). */
    private fun hasAudioReadPermission(ctx: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    }

    /** Quét toàn bộ file audio mà MediaStore đã lập chỉ mục, giữ lại những file nằm trong thư mục
     *  "trông giống" thư mục ghi âm cuộc gọi (xem [THIRD_PARTY_FOLDER_KEYWORDS]).
     *
     *  Dùng MediaStore (ContentResolver) thay vì File.listFiles() quét trực tiếp thư mục, vì từ
     *  Android 10 trở lên việc dùng File để đọc thư mục CỦA APP KHÁC trong bộ nhớ dùng chung bị
     *  chặn bởi "scoped storage" - trong khi các file audio ĐÃ ĐƯỢC MEDIASTORE LẬP CHỈ MỤC (mọi
     *  app ghi âm bên thứ 3 đều tự động được lập chỉ mục khi lưu file audio ra bộ nhớ dùng chung)
     *  vẫn đọc metadata + đường dẫn thật (cột DATA) được bình thường, miễn có quyền
     *  READ_MEDIA_AUDIO/READ_EXTERNAL_STORAGE - đây chính là mục đích MediaStore sinh ra: thư
     *  viện media dùng chung giữa các app. */
    private fun scanThirdPartyAudioFiles(ctx: Context): List<Triple<String, Long, Long>> {
        // Triple = (đường dẫn file thật trên máy, thời điểm sửa đổi cuối tính bằng millis,
        // thời lượng tính bằng millis - có thể = 0 nếu MediaStore chưa đọc được).
        if (!hasAudioReadPermission(ctx)) return emptyList()
        val result = mutableListOf<Triple<String, Long, Long>>()
        val projection = arrayOf(
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.BUCKET_DISPLAY_NAME
        )
        try {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, null, null,
                "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
            )?.use { c ->
                val iData = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                val iDate = c.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                val iDur = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val iBucket = c.getColumnIndex(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
                if (iData < 0 || iDate < 0) return@use
                while (c.moveToNext()) {
                    val path = c.getString(iData) ?: continue
                    // Bỏ qua file do CHÍNH app này tạo - đã có sẵn trong danh mục nội bộ rồi,
                    // tránh liệt kê trùng 2 lần.
                    if (path.contains("/$FOLDER_NAME/")) continue
                    val bucket = (if (iBucket >= 0) c.getString(iBucket) else null)?.lowercase() ?: ""
                    val lowerPath = path.lowercase()
                    val looksLikeCallRecording = THIRD_PARTY_FOLDER_KEYWORDS.any {
                        bucket.contains(it) || lowerPath.contains(it)
                    }
                    if (!looksLikeCallRecording) continue
                    val dateModifiedMillis = c.getLong(iDate) * 1000L
                    val durationMillis = if (iDur >= 0) c.getLong(iDur) else 0L
                    result += Triple(path, dateModifiedMillis, durationMillis)
                }
            }
        } catch (_: Exception) {
            // Thiết bị/ROM lạ trả lỗi khi query MediaStore -> coi như không tìm thấy file bên thứ
            // 3, không crash, phần còn lại của app (bản ghi nội bộ) vẫn hoạt động bình thường.
        }
        return result
    }

    /** Tìm file ghi âm bên thứ 3 khớp gần nhất với 1 thời điểm cuộc gọi cụ thể, trong ngưỡng
     *  [toleranceMillis]. [excludePaths] để không gán trùng 1 file cho 2 cuộc gọi khác nhau (được
     *  cập nhật ngay khi tìm thấy 1 match). */
    private fun findThirdPartyRecording(
        number: String, targetTimeMillis: Long, toleranceMillis: Long,
        candidates: List<Triple<String, Long, Long>>, excludePaths: MutableSet<String>
    ): CallRecording? {
        val match = candidates
            .asSequence()
            .filter { it.first !in excludePaths }
            .filter { kotlin.math.abs(it.second - targetTimeMillis) <= toleranceMillis }
            .minByOrNull { kotlin.math.abs(it.second - targetTimeMillis) }
            ?: return null
        excludePaths += match.first
        return CallRecording(
            number = number,
            filePath = match.first,
            startTimeMillis = match.second,
            durationSeconds = match.third / 1000L
        )
    }

    /** Đọc danh sách thời điểm các cuộc gọi (từ Nhật ký cuộc gọi hệ thống) khớp với [number], dùng
     *  làm "mốc thời gian" để dò tìm file ghi âm bên thứ 3 tương ứng - vì file bên thứ 3 không lưu
     *  kèm số điện thoại nào trong danh mục của app này cả, chỉ có thời điểm tạo file. */
    private fun callLogDatesForNumber(ctx: Context, number: String): List<Long> {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        val target = number.filter { it.isDigit() }.takeLast(9)
        if (target.isEmpty()) return emptyList()
        val dates = mutableListOf<Long>()
        try {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.DATE),
                "${CallLog.Calls.NUMBER} LIKE ?", arrayOf("%$target"), null
            )?.use { c ->
                val iDate = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
                while (c.moveToNext()) dates += c.getLong(iDate)
            }
        } catch (_: Exception) { }
        return dates
    }

    /** Đọc thời lượng thật của 1 file audio bằng MediaMetadataRetriever - dùng làm fallback khi
     *  file bên thứ 3 có [CallRecording.durationSeconds] = 0 (MediaStore đôi khi chưa đọc được
     *  duration lúc lập chỉ mục, tuỳ định dạng file mà app ghi âm bên thứ 3 dùng). */
    fun readDurationSeconds(filePath: String): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(filePath)
            val ms = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            retriever.release()
            ms / 1000L
        } catch (_: Exception) {
            0L
        }
    }

    private fun readAll(ctx: Context): JSONArray = try {
        JSONArray(prefs(ctx).getString(KEY_ENTRIES, null) ?: "[]")
    } catch (_: Exception) { JSONArray() }
}
