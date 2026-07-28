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
        b.btnContactCard.setOnClickListener { openFullContactCard(number) }
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
        b.rowCallRecording.setOnClickListener { CallRecordingListDialog.show(this, number) }

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
     *  Sửa/Xem thẻ liên hệ), cùng cách CallHistoryFragment.btnEdit đã làm — PhoneLookup tự
     *  chuẩn hoá số nên đáng tin hơn so với so khớp chuỗi thô. Trả về null nếu số này chưa
     *  từng được lưu (trường hợp hiếm ở màn này vì màn chỉ mở từ liên hệ ĐÃ LƯU, nhưng vẫn
     *  phòng hờ nếu liên hệ vừa bị xoá ở nơi khác trong lúc đang xem). */
    private fun lookupContactUri(number: String): android.net.Uri? {
        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number)
            )
            requireContext().contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.LOOKUP_KEY, ContactsContract.PhoneLookup._ID),
                null, null, null
            )?.use { cur ->
                if (cur.moveToFirst()) {
                    ContactsContract.Contacts.getLookupUri(cur.getLong(1), cur.getString(0))
                } else null
            }
        } catch (_: Exception) { null }
    }

    /** Nút bút chì: mở màn sửa liên hệ hệ thống. Nếu vì lý do gì đó không còn tra ra được liên
     *  hệ (ví dụ vừa bị xoá ở app Danh bạ khác trong lúc đang xem màn này), rơi về tạo mới với
     *  số đã điền sẵn thay vì im lặng không làm gì. */
    private fun openContactEditor(number: String) {
        val contactUri = lookupContactUri(number)
        try {
            if (contactUri != null) {
                startActivity(android.content.Intent(android.content.Intent.ACTION_EDIT)
                    .setDataAndType(contactUri, ContactsContract.Contacts.CONTENT_ITEM_TYPE))
            } else {
                startActivity(android.content.Intent(android.content.Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
                    .putExtra(ContactsContract.Intents.Insert.PHONE, number))
            }
        } catch (_: Exception) {
            android.widget.Toast.makeText(requireContext(), "Không thể mở màn hình sửa liên hệ", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Icon thẻ liên hệ: mở thẻ liên hệ ĐẦY ĐỦ của app Danh bạ hệ thống (ảnh đại diện lớn,
     *  toàn bộ số/email/địa chỉ đã lưu...) — khác với màn rút gọn đang hiện ở đây. */
    private fun openFullContactCard(number: String) {
        val contactUri = lookupContactUri(number)
        if (contactUri == null) {
            android.widget.Toast.makeText(requireContext(), "Chưa lưu trong danh bạ", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, contactUri))
        } catch (_: Exception) {
            android.widget.Toast.makeText(requireContext(), "Không thể mở thẻ liên hệ", android.widget.Toast.LENGTH_SHORT).show()
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
                    mainHandler.post {
                        if (_b == null || !isAdded) return@post
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
            .setNegativeButton("Huỷ", null)
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

        rb.root.setOnClickListener { (activity as? MainActivity)?.placeCall(item.number) }
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
