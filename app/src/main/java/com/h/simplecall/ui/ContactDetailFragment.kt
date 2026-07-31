package com.h.simplecall.ui

import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.SubscriptionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.h.simplecall.MainActivity
import com.h.simplecall.R
import com.h.simplecall.data.CallLogEntry
import com.h.simplecall.databinding.FragmentContactDetailBinding
import com.h.simplecall.databinding.ItemCallHistoryEntryBinding
import java.text.SimpleDateFormat
import java.util.*

/**
 * Màn "chi tiết liên hệ", mở ra khi bấm vào 1 liên hệ trong tab Danh bạ (luôn là liên hệ
 * ĐÃ LƯU sẵn, có tên). Tách RIÊNG khỏi CallHistoryFragment (màn mở từ icon "i" ở Gần đây,
 * xử lý cả trường hợp số LẠ chưa lưu) để sửa 1 màn không ảnh hưởng màn kia.
 * Bố cục: avatar tròn, tên lớn, dòng SIM mặc định, thẻ số điện thoại + Zalo + Xem thêm,
 * thẻ Meet, thẻ Tóm tắt/Bản ghi âm cuộc gọi, và danh sách Nhật ký cuộc gọi của riêng số này.
 */
class ContactDetailFragment : Fragment() {

    companion object {
        fun newInstance(number: String, name: String) = ContactDetailFragment().also {
            it.arguments = Bundle().apply {
                putString("number", number)
                putString("name", name)
            }
        }
    }

    private var _b: FragmentContactDetailBinding? = null
    private val b get() = _b!!
    private var currentEntries: List<CallLogEntry> = emptyList()

    /** Đánh dấu vừa mở màn sửa/tạo liên hệ HỆ THỐNG (ACTION_EDIT/ACTION_INSERT) - 2 Intent này
     *  không trả về Activity Result đáng tin cậy (nhiều ROM/launcher trả RESULT_CANCELED dù
     *  người dùng đã lưu), nên không thể chỉ dựa vào registerForActivityResult() để biết có nên
     *  invalidate cache hay không. Thay vào đó: cứ hễ MỞ màn sửa/tạo là bật cờ này lên, rồi
     *  invalidate() ngay ở onResume() kế tiếp - đó chính là lúc chắc chắn người dùng đã quay lại
     *  từ màn hệ thống đó (dù lưu hay huỷ), an toàn hơn là bỏ sót cache cũ. */
    private var pendingContactChange = false

    override fun onResume() {
        super.onResume()
        if (pendingContactChange) {
            pendingContactChange = false
            // Bản sao lưu danh bạ của app có thể đã lỗi thời sau khi người dùng sửa/tạo liên hệ
            // ở màn hệ thống - lần đọc kế tiếp (getContacts/searchContacts) phải tự đồng bộ lại
            // (chỉ chênh lệch, rất nhanh) thay vì tiếp tục trả về cache cũ.
            com.h.simplecall.data.ContactsRepository.invalidate()
        }
    }

    // Room không cho phép query trên main thread -> luôn đọc/ghi DB lịch sử ở nền.
    private val bgExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentContactDetailBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val number = arguments?.getString("number") ?: ""
        val name   = arguments?.getString("name") ?: number
        val display = name.ifBlank { number }

        // ── Header: avatar tròn (chữ cái đầu) + tên + số ──
        b.tvAvatar.text = display.take(1).uppercase()
        // Nếu không có tên liên hệ (chỉ là 1 số lạ) thì số lớn phía trên PHẢI cách nhóm 3-3-2-2
        // giống số trong thẻ phía dưới, ví dụ "090 130 08 36" - đúng ảnh mẫu người dùng gửi.
        b.tvTitle.text = if (name.isBlank()) formatNumberGrouped(number) else display
        // Đọc SIM mặc định cho cuộc gọi từ hệ thống
        val defaultSimSlot: Int = try {
            if (android.content.pm.PackageManager.PERMISSION_GRANTED ==
                androidx.core.content.ContextCompat.checkSelfPermission(
                    requireContext(), android.Manifest.permission.READ_PHONE_STATE)) {
                val sm = requireContext().getSystemService(SubscriptionManager::class.java)
                val defaultSubId = SubscriptionManager.getDefaultVoiceSubscriptionId()
                val info = sm?.getActiveSubscriptionInfo(defaultSubId)
                (info?.simSlotIndex ?: 0) + 1
            } else 1
        } catch (_: Exception) { 1 }

        b.tvSubtitle.text = getString(R.string.default_sim_call, defaultSimSlot)
        b.tvSimBadge.text = defaultSimSlot.toString()
        // Số SIM trên icon gọi
        b.tvCallSimNum.text = defaultSimSlot.toString()

        b.tvNumber.text = formatNumberGrouped(number)
        val digitsOnly = number.filter { it.isDigit() }
        val nationalNumber = if (digitsOnly.startsWith("0")) digitsOnly.drop(1) else digitsOnly
        b.tvZalo.text = getString(R.string.zalo_call_with_number, nationalNumber)

        // ── Nút back / edit / thẻ liên hệ / thêm ──
        b.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        b.btnEdit.setOnClickListener { openContactEditor(number) }
        b.btnMore.setOnClickListener { showMoreMenu(it, number) }

        // ── Hàng hành động trên thẻ số: gọi / nhắn tin / video ──
        // Từ khi sửa lỗi "thiếu liên hệ không có số điện thoại", màn này có thể được mở với
        // number rỗng (liên hệ có tên nhưng chưa lưu số nào) — phải chặn gọi/nhắn/Zalo ở đây,
        // nếu không placeCall("") có thể ném lỗi từ Telecom hoặc gọi nhầm số rỗng.
        if (number.isBlank()) {
            val warn = { android.widget.Toast.makeText(requireContext(),
                "Liên hệ này chưa có số điện thoại — bấm sửa để thêm số", android.widget.Toast.LENGTH_SHORT).show() }
            b.btnCallRow.setOnClickListener { warn() }
            b.btnMessageRow.setOnClickListener { warn() }
            b.btnVideoRow.setOnClickListener { warn() }
            b.rowZalo.setOnClickListener { warn() }
        } else {
            b.btnCallRow.setOnClickListener { (activity as? MainActivity)?.placeCall(number) }
            b.btnMessageRow.setOnClickListener { openSms(number) }
            b.btnVideoRow.setOnClickListener { (activity as? MainActivity)?.placeCall(number) }
            b.rowZalo.setOnClickListener { openZalo(number) }
        }
        b.rowSeeMore.setOnClickListener { /* TODO: mở rộng thêm thông tin liên hệ */ }
        b.rowMeet.setOnClickListener { /* TODO: tích hợp Meet khi có */ }
        b.rowCallSummary.setOnClickListener { /* TODO: tóm tắt cuộc gọi (AI) khi có */ }
        // Hiện danh sách bản ghi âm THẬT của số này (đọc qua RecordingsProvider mới của VOX,
        // đối chiếu theo mốc thời gian đã tự lưu ở CallRecordingManager.startRecording()) -
        // trước đây chỉ mở app VOX lên, người dùng phải tự mò tìm đúng file trong đó.
        b.rowCallRecording.setOnClickListener { showRecordingsDialog(number) }

        b.btnClearLog.setOnClickListener { clearHistory(number) }
        loadHistoryAsync(number)
    }

    private fun loadHistoryAsync(number: String) {
        val appContext = requireContext().applicationContext
        bgExecutor.execute {
            val entries = loadHistory(appContext, number)
            mainHandler.post {
                if (_b == null) return@post // fragment đã bị huỷ trong lúc chờ
                currentEntries = entries
                renderEntries(entries)
            }
        }
    }

    private fun openSms(number: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO)
        intent.data = android.net.Uri.parse("smsto:$number")
        runCatching { startActivity(intent) }
    }

    /** Tra ngược từ số điện thoại ra Uri liên hệ thật trong danh bạ hệ thống (dùng chung cho
     *  Sửa/Xem thẻ liên hệ). Đọc thẳng từ BẢN SAO LƯU danh bạ của app (contact_id + lookup_key
     *  đã lưu sẵn từ lần đồng bộ - xem ContactsRepository.getContactUri()), không cần hỏi lại
     *  ContactsProvider của hệ thống mỗi lần bấm Sửa nữa. Chỉ rơi về tra trực tiếp qua
     *  PhoneLookup nếu số này chưa kịp có trong bản sao lưu (trường hợp hiếm ở màn này vì màn
     *  chỉ mở từ liên hệ ĐÃ LƯU, nhưng vẫn phòng hờ nếu liên hệ vừa đổi ở nơi khác trong lúc
     *  đang xem, trước khi kịp đồng bộ lại). */
    private fun lookupContactUri(number: String): android.net.Uri? =
        com.h.simplecall.data.ContactsRepository.getContactUri(requireContext(), number)

    /** Nút bút chì: mở màn sửa liên hệ hệ thống. Nếu vì lý do gì đó không còn tra ra được liên
     *  hệ (ví dụ vừa bị xoá ở app Danh bạ khác trong lúc đang xem màn này), rơi về tạo mới với
     *  số đã điền sẵn thay vì im lặng không làm gì. */
    private fun openContactEditor(number: String) {
        val contactUri = lookupContactUri(number)
        try {
            pendingContactChange = true
            if (contactUri != null) {
                startActivity(android.content.Intent(android.content.Intent.ACTION_EDIT)
                    .setDataAndType(contactUri, ContactsContract.Contacts.CONTENT_ITEM_TYPE))
            } else {
                startActivity(android.content.Intent(android.content.Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
                    .putExtra(ContactsContract.Intents.Insert.PHONE, number))
            }
        } catch (_: Exception) {
            pendingContactChange = false
            android.widget.Toast.makeText(requireContext(), "Không thể mở màn hình sửa liên hệ", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Menu 3 chấm: các thao tác phụ trên liên hệ này — chia sẻ số, hoặc xoá hẳn khỏi danh bạ. */
    private fun showMoreMenu(anchor: View, number: String) {
        val popup = android.widget.PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "Chia sẻ liên hệ")
        popup.menu.add(0, 2, 1, "Xoá liên hệ")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> shareContact(number)
                2 -> deleteContact(number)
            }
            true
        }
        popup.show()
    }

    private fun shareContact(number: String) {
        try {
            startActivity(android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, number)
                }, null))
        } catch (_: Exception) {}
    }

    /** Xoá liên hệ khỏi danh bạ hệ thống (không chỉ xoá nhật ký cuộc gọi như btnClearLog).
     *  Yêu cầu xác nhận trước vì đây là thao tác không thể hoàn tác. */
    private fun deleteContact(number: String) {
        val contactUri = lookupContactUri(number)
        if (contactUri == null) {
            android.widget.Toast.makeText(requireContext(), "Chưa lưu trong danh bạ", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Xoá liên hệ")
            .setMessage("Xoá liên hệ này khỏi danh bạ? Không thể hoàn tác.")
            .setPositiveButton("Xoá") { _, _ ->
                val appContext = requireContext().applicationContext
                bgExecutor.execute {
                    try { appContext.contentResolver.delete(contactUri, null, null) }
                    catch (e: Exception) { android.util.Log.e("ContactDetailFragment", "Xoá liên hệ thất bại", e) }
                    // Không đi qua Activity hệ thống nào (delete() gọi thẳng ContentResolver) nên
                    // không thể chờ onResume() như openContactEditor() - invalidate ngay tại đây.
                    com.h.simplecall.data.ContactsRepository.invalidate()
                    mainHandler.post {
                        if (_b == null || !isAdded) return@post
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    /** Hiện danh sách bản ghi âm cuộc gọi của đúng số này (đọc qua RecordingsProvider của VOX
     *  Ghi Âm, đối chiếu theo mốc thời gian - xem CallRecordingManager.recordingsForNumber()).
     *  Bấm vào 1 mục để phát trực tiếp qua app nghe nhạc/âm thanh mặc định trên máy (content://
     *  của VOX cho phép mọi app đọc, không cần FileProvider riêng của app này). */
    private fun showRecordingsDialog(number: String) {
        val ctx = requireContext()
        if (!com.h.simplecall.call.CallRecordingManager.isRecorderAppInstalled(ctx)) {
            android.widget.Toast.makeText(ctx,
                "Chưa cài ${com.h.simplecall.call.CallRecordingManager.RECORDER_APP_NAME} trên máy này",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val recordings = com.h.simplecall.call.CallRecordingManager.recordingsForNumber(ctx, number)
        if (recordings.isEmpty()) {
            android.widget.Toast.makeText(ctx, "Chưa có bản ghi âm nào cho số này", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("vi", "VN"))
        val labels = recordings.map { r ->
            val mins = r.durationMs / 1000 / 60
            val secs = r.durationMs / 1000 % 60
            "${fmt.format(Date(r.timestampMs))} · %d:%02d".format(mins, secs)
        }.toTypedArray<CharSequence>()
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Bản ghi âm cuộc gọi")
            .setItems(labels) { _, which ->
                val rec = recordings[which]
                try {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(rec.contentUri, "audio/mp4")
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                } catch (_: Exception) {
                    android.widget.Toast.makeText(ctx, "Không tìm thấy ứng dụng phát âm thanh phù hợp", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    /** Mở Zalo nếu máy đã cài (đúng như TODO gốc ghi rõ "nếu app cài trên máy") — Zalo không có
     *  API/deep-link công khai chính thức để tự động gọi thẳng tới 1 số cụ thể, nên mở đúng
     *  trang trò chuyện/hồ sơ của số đó qua zalo.me (app Zalo sẽ tự bắt link này nếu đã cài,
     *  người dùng chỉ cần bấm gọi trong Zalo). Nếu chưa cài, báo rõ thay vì im lặng. */
    private fun openZalo(number: String) {
        val pm = requireContext().packageManager
        val zaloInstalled = try {
            pm.getPackageInfo("com.zing.zalo", 0); true
        } catch (_: Exception) { false }
        if (!zaloInstalled) {
            android.widget.Toast.makeText(requireContext(), "Chưa cài đặt Zalo trên máy này", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val digits = number.filter { it.isDigit() }
        val national = if (digits.startsWith("0")) digits.drop(1) else digits
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://zalo.me/$national")))
        } catch (_: Exception) {
            android.widget.Toast.makeText(requireContext(), "Không thể mở Zalo", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Dựng danh sách "Nhật ký cuộc gọi" bằng tay (không RecyclerView) vì đã nằm trong 1
     *  ScrollView chung của toàn màn hình - tránh xung đột cuộn lồng nhau. */
    private fun renderEntries(entries: List<CallLogEntry>) {
        val container = b.llHistoryEntries
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        entries.forEachIndexed { index, item ->
            val rowBinding = ItemCallHistoryEntryBinding.inflate(inflater, container, false)
            bindEntry(rowBinding, item)
            container.addView(rowBinding.root)

            if (index != entries.lastIndex) {
                val divider = View(requireContext())
                val dividerHeightPx = (1 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
                divider.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dividerHeightPx
                )
                divider.setBackgroundColor(resources.getColor(R.color.divider, requireContext().theme))
                container.addView(divider)
            }
        }
    }

    private fun bindEntry(rb: ItemCallHistoryEntryBinding, item: CallLogEntry) {
        val ctx = requireContext()
        val isMissed = item.type == CallLog.Calls.MISSED_TYPE
        val isOutgoing = item.type == CallLog.Calls.OUTGOING_TYPE

        val (label, iconRes) = when {
            isMissed -> getString(R.string.call_type_missed) to R.drawable.ic_call_missed
            isOutgoing -> getString(R.string.call_type_outgoing) to R.drawable.ic_call_outgoing
            else -> getString(R.string.call_type_incoming) to R.drawable.ic_call_incoming
        }
        rb.tvEntryLabel.text = label
        rb.tvEntryLabel.setTextColor(
            ctx.getColor(if (isMissed) R.color.missed_red else R.color.text_primary)
        )
        rb.ivEntryType.setImageResource(iconRes)

        // Giờ:phút bắt đầu cuộc gọi
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeStr = timeFmt.format(Date(item.date))

        // Format status theo hình mẫu
        rb.tvEntryStatus.text = when {
            isMissed -> {
                // Nhỡ: đổ chuông = duration nếu > 0, không thì 0
                val ring = item.duration
                if (ring > 0) "$timeStr  (Đổ chuông trong ${ring}giây)"
                else "$timeStr  (Đổ chuông trong 1 giây)"
            }
            item.duration <= 0 -> "$timeStr  Chưa được kết nối"
            else -> "$timeStr  (${formatDurationVi(item.duration)})"
        }

        // Màu đỏ cho nhỡ
        rb.tvEntryStatus.setTextColor(
            requireContext().getColor(if (isMissed) R.color.missed_red else R.color.text_secondary)
        )

        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val cal = Calendar.getInstance().apply { timeInMillis = item.date }
        rb.tvEntryDate.text = if (cal.after(today))
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.date))
        else
            SimpleDateFormat("d/M", Locale.getDefault()).format(Date(item.date))

        // TRƯỚC ĐÂY: bấm vào 1 dòng nhật ký chỉ mở app VOX Ghi Âm lên, người dùng phải tự mò tìm
        // đúng bản ghi trong đó. Giờ hiện thẳng danh sách bản ghi âm của đúng số này (đối chiếu
        // qua mốc thời gian, xem CallRecordingManager.recordingsForNumber()).
        rb.root.setOnClickListener {
            showRecordingsDialog(item.number)
        }
    }

    /** Cách nhóm số điện thoại theo 3-3-2-2, ví dụ "0901300836" -> "090 130 08 36",
     *  đúng định dạng hiển thị trong ảnh mẫu. Giữ dấu "+" đầu số (nếu có) đứng riêng,
     *  không tính vào phần chia nhóm. Số dài hơn 10 chữ số thì phần dư được gộp vào nhóm cuối. */
    private fun formatNumberGrouped(raw: String): String {
        val hasPlus = raw.trimStart().startsWith("+")
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return raw
        val groups = mutableListOf<String>()
        var i = 0
        for (size in intArrayOf(3, 3, 2, 2)) {
            if (i >= digits.length) break
            val end = (i + size).coerceAtMost(digits.length)
            groups.add(digits.substring(i, end))
            i = end
        }
        if (i < digits.length) groups.add(digits.substring(i))
        return (if (hasPlus) "+" else "") + groups.joinToString(" ")
    }

    private fun formatDuration(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", m, s)
    }

    private fun formatDurationVi(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return when {
            m > 0 && s > 0 -> "${m}phút ${s}giây"
            m > 0 -> "${m}phút"
            else -> "${s}giây"
        }
    }

    /** Xoá nhật ký cuộc gọi của số này khỏi CallLog hệ thống. */
    private fun clearHistory(number: String) {
        val clean = number.filter { it.isDigit() }
        val appContext = requireContext().applicationContext
        bgExecutor.execute {
            try {
                appContext.contentResolver.delete(
                    CallLog.Calls.CONTENT_URI,
                    "${CallLog.Calls.NUMBER} LIKE ?", arrayOf("%$clean%")
                )
            } catch (e: Exception) {
                android.util.Log.e("ContactDetailFragment", "Xoá lịch sử theo số thất bại", e)
            }
        }
        currentEntries = emptyList()
        b.llHistoryEntries.removeAllViews()
    }

    /** Đọc lịch sử của riêng 1 số từ CallLog hệ thống. */
    private fun loadHistory(ctx: android.content.Context, number: String): List<CallLogEntry> {
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CALL_LOG)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()
        val clean = number.filter { it.isDigit() }
        val entries = mutableListOf<CallLogEntry>()
        val projection = arrayOf(
            CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION,
            CallLog.Calls.PHONE_ACCOUNT_ID
        )
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI, projection,
            "${CallLog.Calls.NUMBER} LIKE ?", arrayOf("%$clean%"),
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            val iNum = cursor.getColumnIndex(CallLog.Calls.NUMBER)
            val iName = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val iType = cursor.getColumnIndex(CallLog.Calls.TYPE)
            val iDate = cursor.getColumnIndex(CallLog.Calls.DATE)
            val iDur  = cursor.getColumnIndex(CallLog.Calls.DURATION)
            val iSim  = cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
            while (cursor.moveToNext()) {
                val num = cursor.getString(iNum) ?: continue
                val simId = cursor.getString(iSim)
                val slot = when {
                    simId.isNullOrEmpty() -> null
                    simId.contains("1") -> 0
                    simId.contains("2") -> 1
                    else -> null
                }
                entries.add(CallLogEntry(
                    name = cursor.getString(iName) ?: "",
                    number = num, type = cursor.getInt(iType),
                    date = cursor.getLong(iDate), duration = cursor.getLong(iDur),
                    simSlot = slot
                ))
            }
        }
        return entries
    }

    override fun onDestroyView() {
        bgExecutor.shutdownNow()
        super.onDestroyView(); _b = null
    }
}
