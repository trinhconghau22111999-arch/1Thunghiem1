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
import com.h.simplecall.databinding.FragmentCallHistoryBinding
import com.h.simplecall.databinding.ItemCallHistoryEntryBinding
import java.text.SimpleDateFormat
import java.util.*

/**
 * Màn "chi tiết cuộc gọi", mở ra khi bấm icon "i" trên 1 dòng ở tab Gần đây. Dùng chung layout
 * gần như y hệt ContactDetailFragment (xem file đó để biết cấu trúc chi tiết), NHƯNG xử lý CẢ
 * trường hợp số LẠ CHƯA lưu trong danh bạ (hiện thêm thẻ "Tạo liên hệ mới / Thêm vào liên hệ
 * hiện có" - cardAddContact - vốn KHÔNG có trong layout của ContactDetailFragment vì màn đó chỉ
 * mở từ liên hệ ĐÃ LƯU sẵn).
 *
 * LƯU Ý: file này từng bị lệch hoàn toàn với layout fragment_call_history.xml thực tế (tham
 * chiếu tới các id không tồn tại như btnSmsRow, recyclerViewEntries, tvEntryType...) khiến
 * project không biên dịch được - đã viết lại toàn bộ cho khớp đúng layout hiện tại.
 */
class CallHistoryFragment : Fragment() {

    companion object {
        fun newInstance(number: String, name: String) = CallHistoryFragment().also {
            it.arguments = Bundle().apply {
                putString("number", number)
                putString("name", name)
            }
        }
    }

    private var _b: FragmentCallHistoryBinding? = null
    private val b get() = _b!!
    private var currentEntries: List<CallLogEntry> = emptyList()

    // Room không cho phép query trên main thread -> luôn đọc/ghi DB lịch sử ở nền.
    private val bgExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentCallHistoryBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val number = arguments?.getString("number") ?: ""
        val name   = arguments?.getString("name") ?: number
        val display = name.ifBlank { number }

        // ── Header: avatar tròn (chữ cái đầu) + tên + số ──
        b.tvAvatar.text = display.take(1).uppercase()
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
        b.tvCallSimNum.text = defaultSimSlot.toString()
        b.rowSimDefault.setOnClickListener {
            try { startActivity(android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)) }
            catch (_: Exception) {}
        }

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
        if (number.isBlank()) {
            val warn = { android.widget.Toast.makeText(requireContext(),
                "Số điện thoại không hợp lệ", android.widget.Toast.LENGTH_SHORT).show() }
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

        setupAddContactCard(number)
        loadHistoryAsync(number)
    }

    /** Thẻ "Tạo liên hệ mới / Thêm vào liên hệ hiện có" - CHỈ hiện khi số này CHƯA có trong danh
     *  bạ hệ thống (khác ContactDetailFragment, màn đó luôn mở từ liên hệ ĐÃ LƯU nên không cần
     *  thẻ này). */
    private fun setupAddContactCard(number: String) {
        if (number.isBlank()) { b.cardAddContact.visibility = View.GONE; return }
        val appContext = requireContext().applicationContext
        bgExecutor.execute {
            val exists = lookupContactUri(appContext, number) != null
            mainHandler.post {
                if (_b == null) return@post
                b.cardAddContact.visibility = if (exists) View.GONE else View.VISIBLE
            }
        }
        b.rowCreateContact.setOnClickListener {
            try {
                startActivity(android.content.Intent(android.content.Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
                    .putExtra(ContactsContract.Intents.Insert.PHONE, number))
            } catch (_: Exception) {}
        }
        b.rowAddExisting.setOnClickListener {
            try {
                startActivity(android.content.Intent(android.content.Intent.ACTION_INSERT_OR_EDIT)
                    .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                    .putExtra(ContactsContract.Intents.Insert.PHONE, number))
            } catch (_: Exception) {
                android.widget.Toast.makeText(requireContext(), "Không thể mở danh bạ", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
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

    /** Tra ngược từ số điện thoại ra Uri liên hệ thật trong danh bạ hệ thống. Nhận Context làm
     *  tham số (thay vì requireContext()) vì cũng được gọi từ luồng nền trong setupAddContactCard. */
    private fun lookupContactUri(ctx: android.content.Context, number: String): android.net.Uri? {
        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number)
            )
            ctx.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.LOOKUP_KEY, ContactsContract.PhoneLookup._ID),
                null, null, null
            )?.use { cur ->
                if (cur.moveToFirst()) {
                    ContactsContract.Contacts.getLookupUri(cur.getLong(1), cur.getString(0))
                } else null
            }
        } catch (_: Exception) { null }
    }

    private fun openContactEditor(number: String) {
        val contactUri = lookupContactUri(requireContext(), number)
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

    private fun openFullContactCard(number: String) {
        val contactUri = lookupContactUri(requireContext(), number)
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

    private fun deleteContact(number: String) {
        val contactUri = lookupContactUri(requireContext(), number)
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
                    catch (e: Exception) { android.util.Log.e("CallHistoryFragment", "Xoá liên hệ thất bại", e) }
                    mainHandler.post {
                        if (_b == null || !isAdded) return@post
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

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

        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeStr = timeFmt.format(Date(item.date))

        rb.tvEntryStatus.text = when {
            isMissed -> {
                val ring = item.duration
                if (ring > 0) "$timeStr  (Đổ chuông trong ${ring}giây)"
                else "$timeStr  (Đổ chuông trong 1 giây)"
            }
            item.duration <= 0 -> "$timeStr  Chưa được kết nối"
            else -> "$timeStr  (${formatDurationVi(item.duration)})"
        }

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

    private fun formatDurationVi(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return when {
            m > 0 && s > 0 -> "${m}phút ${s}giây"
            m > 0 -> "${m}phút"
            else -> "${s}giây"
        }
    }

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
                android.util.Log.e("CallHistoryFragment", "Xoá lịch sử theo số thất bại", e)
            }
        }
        currentEntries = emptyList()
        b.llHistoryEntries.removeAllViews()
    }

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
