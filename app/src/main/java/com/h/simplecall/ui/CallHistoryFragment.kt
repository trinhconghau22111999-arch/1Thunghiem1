package com.h.simplecall.ui

import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.SubscriptionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.h.simplecall.MainActivity
import com.h.simplecall.R
import com.h.simplecall.call.BlockedNumbersManager
import com.h.simplecall.call.CallRecordingManager
import com.h.simplecall.data.CallLogEntry
import com.h.simplecall.databinding.FragmentCallHistoryBinding
import com.h.simplecall.databinding.ItemCallHistoryEntryBinding
import java.text.SimpleDateFormat
import java.util.*

/**
 * Màn "chi tiết số điện thoại", mở ra khi bấm icon "i" trên 1 dòng ở Gần đây.
 * Đọc lịch sử trực tiếp từ CallLog hệ thống — không dùng Room DB nội bộ.
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
    private var pendingNumberForPick: String = ""
    private val bgExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val pickContactLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickContact()
    ) { contactUri ->
        if (contactUri == null) return@registerForActivityResult
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_EDIT).apply {
                setDataAndType(contactUri, ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                putExtra(ContactsContract.Intents.Insert.PHONE, pendingNumberForPick)
                putExtra("finishActivityOnSaveCompleted", true)
            })
        } catch (_: Exception) {
            android.widget.Toast.makeText(requireContext(), "Không thể mở màn hình sửa liên hệ", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentCallHistoryBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val number = arguments?.getString("number") ?: ""
        val name   = arguments?.getString("name") ?: ""
        val display = name.ifBlank { number }
        val isKnownContact = name.isNotBlank()

        // ── Header ──
        b.tvAvatar.text = display.take(1).uppercase()
        b.tvTitle.text = if (name.isBlank()) formatNumberGrouped(number) else display

        // ── Ẩn/hiện theo loại liên hệ ──
        if (isKnownContact) {
            b.tvAvatar.visibility = View.VISIBLE
            b.ivDefaultAvatar.visibility = View.GONE
            b.btnEdit.visibility = View.VISIBLE
            b.btnMore.visibility = View.VISIBLE
            b.divBeforeZalo.visibility = View.VISIBLE
            b.rowZalo.visibility = View.VISIBLE
            b.rowSeeMore.visibility = View.VISIBLE
            b.cardAddContact.visibility = View.GONE
        } else {
            b.tvAvatar.visibility = View.GONE
            b.ivDefaultAvatar.visibility = View.VISIBLE
            b.btnEdit.visibility = View.GONE
            b.btnMore.visibility = View.GONE
            b.divBeforeZalo.visibility = View.GONE
            b.rowZalo.visibility = View.GONE
            b.rowSeeMore.visibility = View.GONE
            b.tvNumberType.text = "Điện thoại"
            b.cardAddContact.visibility = View.VISIBLE
            pendingNumberForPick = number
            b.rowCreateContact.setOnClickListener {
                try {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
                        .putExtra(ContactsContract.Intents.Insert.PHONE, number))
                } catch (_: Exception) {
                    android.widget.Toast.makeText(requireContext(), "Không tìm thấy ứng dụng để tạo liên hệ", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            b.rowAddExisting.setOnClickListener {
                try { pickContactLauncher.launch(null) }
                catch (_: Exception) {
                    android.widget.Toast.makeText(requireContext(), "Không thể mở danh bạ", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        // ── SIM mặc định ──
        val defaultSimSlot: Int = try {
            if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_PHONE_STATE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val sm = requireContext().getSystemService(SubscriptionManager::class.java)
                val subId = SubscriptionManager.getDefaultVoiceSubscriptionId()
                (sm?.getActiveSubscriptionInfo(subId)?.simSlotIndex ?: 0) + 1
            } else 1
        } catch (_: Exception) { 1 }

        b.tvSubtitle.text = getString(R.string.default_sim_call, defaultSimSlot)
        b.tvSimBadge.text = defaultSimSlot.toString()
        b.tvCallSimNum.text = defaultSimSlot.toString()
        b.tvNumber.text = formatNumberGrouped(number)

        val digitsOnly = number.filter { it.isDigit() }
        val nationalNumber = if (digitsOnly.startsWith("0")) digitsOnly.drop(1) else digitsOnly
        b.tvZalo.text = getString(R.string.zalo_call_with_number, nationalNumber)

        // ── Nút back ──
        b.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        // ── Nút Sửa liên hệ ──
        b.btnEdit.setOnClickListener {
            val ctx = requireContext()
            // Đọc thẳng từ BẢN SAO LƯU danh bạ của app (contact_id + lookup_key đã lưu sẵn từ
            // lần đồng bộ) thay vì tra lại PhoneLookup của hệ thống mỗi lần bấm Sửa - xem
            // ContactsRepository.getContactUri() (tự rơi về PhoneLookup nếu số chưa kịp đồng bộ).
            val contactUri = com.h.simplecall.data.ContactsRepository.getContactUri(ctx, number)
            try {
                if (contactUri != null) {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_EDIT).apply {
                        setDataAndType(contactUri, ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                    })
                } else {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).apply {
                        putExtra(ContactsContract.Intents.Insert.PHONE, number)
                    })
                }
            } catch (_: Exception) {
                android.widget.Toast.makeText(ctx, "Không thể mở màn sửa liên hệ", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        // ── Nút ⋯ (Menu thêm: chặn/bỏ chặn, sao chép, chia sẻ) ──
        b.btnMore.setOnClickListener { anchor ->
            val ctx = requireContext()
            val popup = android.widget.PopupMenu(ctx, anchor)
            val isBlocked = BlockedNumbersManager.isBlocked(number)
            popup.menu.add(if (isBlocked) "Bỏ chặn số này" else "Chặn số này")
            popup.menu.add("Sao chép số")
            popup.menu.add("Chia sẻ số")
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Chặn số này" -> {
                        BlockedNumbersManager.block(number)
                        android.widget.Toast.makeText(ctx, "Đã chặn $number", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    "Bỏ chặn số này" -> {
                        BlockedNumbersManager.unblock(number)
                        android.widget.Toast.makeText(ctx, "Đã bỏ chặn $number", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    "Sao chép số" -> {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Số điện thoại", number))
                        android.widget.Toast.makeText(ctx, "Đã sao chép số", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    "Chia sẻ số" -> {
                        startActivity(android.content.Intent.createChooser(
                            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, number)
                            }, "Chia sẻ số"))
                    }
                }
                true
            }
            popup.show()
        }

        // ── Hành động gọi / nhắn tin ──
        b.btnCallRow.setOnClickListener { (activity as? MainActivity)?.placeCall(number) }
        b.btnMessageRow.setOnClickListener {
            runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:$number"))) }
        }
        b.btnVideoRow.setOnClickListener { (activity as? MainActivity)?.placeCall(number) }

        // ── Zalo ──
        b.rowZalo.setOnClickListener {
            try {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://zalo.me/$nationalNumber")))
            } catch (_: Exception) {
                android.widget.Toast.makeText(requireContext(), "Không thể mở Zalo", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        // ── Các hàng không cần làm (theo yêu cầu) — ẩn hoàn toàn ──
        b.rowSeeMore.visibility = View.GONE
        b.rowMeet.visibility = View.GONE
        b.rowCallSummary.visibility = View.GONE

        // ── Bản ghi âm cuộc gọi: hiện danh sách bản ghi âm thật của số này (đọc qua
        //    RecordingsProvider mới của VOX Ghi Âm) thay vì mở thẳng app VOX lên ──
        b.rowCallRecording.setOnClickListener { showRecordingsDialog(number) }

        // ── Xoá nhật ký (xoá khỏi CallLog hệ thống) ──
        b.btnClearLog.setOnClickListener { clearSystemCallLog(number) }

        // ── Tải lịch sử cuộc gọi từ CallLog hệ thống ──
        loadHistoryAsync(number)
    }

    private fun loadHistoryAsync(number: String) {
        val ctx = requireContext().applicationContext
        bgExecutor.execute {
            val entries = queryCallLog(ctx, number)
            mainHandler.post {
                if (_b == null) return@post
                renderEntries(entries)
            }
        }
    }

    private fun queryCallLog(ctx: android.content.Context, number: String): List<CallLogEntry> {
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CALL_LOG)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()

        val clean = number.filter { it.isDigit() }.takeLast(9) // 9 chữ số cuối để match mọi biến thể
        val entries = mutableListOf<CallLogEntry>()
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE,
                CallLog.Calls.DATE, CallLog.Calls.DURATION),
            "${CallLog.Calls.NUMBER} LIKE ?", arrayOf("%$clean"),
            "${CallLog.Calls.DATE} DESC"
        )?.use { cur ->
            val iNum  = cur.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val iName = cur.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val iType = cur.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val iDate = cur.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val iDur  = cur.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            while (cur.moveToNext()) {
                entries += CallLogEntry(
                    number = cur.getString(iNum) ?: number,
                    name   = cur.getString(iName) ?: "",
                    type   = cur.getInt(iType),
                    date   = cur.getLong(iDate),
                    duration = cur.getLong(iDur),
                    simSlot  = null   // PHONE_ACCOUNT_ID là String, CallLogEntry.simSlot là Int? — không map trực tiếp
                )
            }
        }
        return entries
    }

    private fun renderEntries(entries: List<CallLogEntry>) {
        val container = b.llHistoryEntries
        container.removeAllViews()
        if (entries.isEmpty()) return
        val inflater = LayoutInflater.from(requireContext())
        entries.forEachIndexed { index, item ->
            val rb = ItemCallHistoryEntryBinding.inflate(inflater, container, false)
            bindEntry(rb, item)
            container.addView(rb.root)
            if (index != entries.lastIndex) {
                val div = View(requireContext())
                div.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    resources.displayMetrics.density.toInt().coerceAtLeast(1))
                div.setBackgroundColor(resources.getColor(R.color.divider, requireContext().theme))
                container.addView(div)
            }
        }
    }

    private fun bindEntry(rb: ItemCallHistoryEntryBinding, item: CallLogEntry) {
        val ctx = requireContext()
        val isMissed   = item.type == CallLog.Calls.MISSED_TYPE
        val isOutgoing = item.type == CallLog.Calls.OUTGOING_TYPE
        val (label, iconRes) = when {
            isMissed   -> getString(R.string.call_type_missed)   to R.drawable.ic_call_missed
            isOutgoing -> getString(R.string.call_type_outgoing) to R.drawable.ic_call_outgoing
            else       -> getString(R.string.call_type_incoming) to R.drawable.ic_call_incoming
        }
        rb.tvEntryLabel.text = label
        rb.tvEntryLabel.setTextColor(ctx.getColor(if (isMissed) R.color.missed_red else R.color.text_primary))
        rb.ivEntryType.setImageResource(iconRes)

        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.date))
        rb.tvEntryStatus.text = when {
            isMissed        -> if (item.duration > 0) "$timeStr  (Đổ chuông trong ${item.duration}giây)"
                               else "$timeStr  (Đổ chuông trong 1 giây)"
            item.duration <= 0 -> "$timeStr  Chưa được kết nối"
            else            -> "$timeStr  (${formatDurationVi(item.duration)})"
        }
        rb.tvEntryStatus.setTextColor(ctx.getColor(if (isMissed) R.color.missed_red else R.color.text_secondary))

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
        // qua mốc thời gian, xem CallRecordingManager.recordingsForNumber()). Dùng item.number
        // (không phải biến "number" ngoài onViewCreated) vì bindEntry() là hàm cấp lớp, không
        // đóng gói (closure) được biến cục bộ của onViewCreated.
        rb.root.setOnClickListener { showRecordingsDialog(item.number) }
    }

    private fun clearSystemCallLog(number: String) {
        val ctx = requireContext().applicationContext
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.WRITE_CALL_LOG)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            android.widget.Toast.makeText(requireContext(), "Chưa có quyền xoá nhật ký cuộc gọi", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val clean = number.filter { it.isDigit() }.takeLast(9)
        bgExecutor.execute {
            ctx.contentResolver.delete(CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls.NUMBER} LIKE ?", arrayOf("%$clean"))
            mainHandler.post { if (_b != null) b.llHistoryEntries.removeAllViews() }
        }
    }

    /** Hiện danh sách bản ghi âm cuộc gọi của đúng số này (đọc qua RecordingsProvider của VOX
     *  Ghi Âm, đối chiếu theo mốc thời gian - xem CallRecordingManager.recordingsForNumber()). */
    private fun showRecordingsDialog(number: String) {
        val ctx = requireContext()
        if (!CallRecordingManager.isRecorderAppInstalled(ctx)) {
            android.widget.Toast.makeText(
                ctx, "Chưa cài ${CallRecordingManager.RECORDER_APP_NAME} trên máy này",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        val recordings = CallRecordingManager.recordingsForNumber(ctx, number)
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

    private fun formatNumberGrouped(raw: String): String {
        val hasPlus = raw.trimStart().startsWith("+")
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return raw
        val groups = mutableListOf<String>()
        var i = 0
        for (size in intArrayOf(3, 3, 2, 2)) {
            if (i >= digits.length) break
            groups.add(digits.substring(i, (i + size).coerceAtMost(digits.length)))
            i += size
        }
        if (i < digits.length) groups.add(digits.substring(i))
        return (if (hasPlus) "+" else "") + groups.joinToString(" ")
    }

    private fun formatDurationVi(seconds: Long): String {
        val m = seconds / 60; val s = seconds % 60
        return when { m > 0 && s > 0 -> "${m}phút ${s}giây"; m > 0 -> "${m}phút"; else -> "${s}giây" }
    }

    override fun onDestroyView() { bgExecutor.shutdownNow(); super.onDestroyView(); _b = null }
}
