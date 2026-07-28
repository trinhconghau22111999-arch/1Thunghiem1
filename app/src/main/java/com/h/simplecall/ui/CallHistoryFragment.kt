package com.h.simplecall.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.SubscriptionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.h.simplecall.MainActivity
import com.h.simplecall.R
import com.h.simplecall.databinding.FragmentCallHistoryBinding
import com.h.simplecall.databinding.ItemCallHistoryEntryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

data class CallEntry(
    val type: Int,
    val date: Long,
    val duration: Long,
    val simSlot: Int
)

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

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentCallHistoryBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val number = arguments?.getString("number") ?: ""
        val name   = arguments?.getString("name") ?: number

        b.btnBack.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
            (activity as? MainActivity)?.showNav()
        }

        // Hiện tên / số
        if (name.isNotEmpty() && name != number) {
            b.tvTitle.text = name
        } else {
            b.tvTitle.text = formatNumber(number)
        }

        // SIM mặc định
        val defaultSimSlot = getDefaultSimSlot()
        b.tvSimBadge.text = (defaultSimSlot + 1).toString()
        b.tvSubtitle.text = getString(R.string.default_sim_call, defaultSimSlot + 1)
        b.rowSimDefault.setOnClickListener {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_CALL_SETTINGS))
            } catch (_: Exception) {}
        }

        // Avatar
        setupAvatar(name)

        // Nút gọi
        b.btnCallRow.setOnClickListener { (activity as? MainActivity)?.placeCall(number) }
        b.tvCallSimNum.text = (defaultSimSlot + 1).toString()

        // Nút nhắn tin
        b.btnSmsRow.setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number"))) }
            catch (_: Exception) { Toast.makeText(requireContext(), "Không tìm thấy ứng dụng nhắn tin", Toast.LENGTH_SHORT).show() }
        }

        // Nút video
        b.btnVideoRow.setOnClickListener {
            Toast.makeText(requireContext(), getString(R.string.video_call_unsupported), Toast.LENGTH_SHORT).show()
        }

        // Nút sửa
        b.btnEdit.setOnClickListener {
            try {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
                val cur = requireContext().contentResolver.query(uri,
                    arrayOf(ContactsContract.PhoneLookup.LOOKUP_KEY, ContactsContract.PhoneLookup._ID), null, null, null)
                cur?.use {
                    if (it.moveToFirst()) {
                        val lookupKey = it.getString(0)
                        val contactUri = ContactsContract.Contacts.getLookupUri(it.getLong(1), lookupKey)
                        startActivity(Intent(Intent.ACTION_EDIT).setDataAndType(contactUri, ContactsContract.Contacts.CONTENT_ITEM_TYPE))
                        return@setOnClickListener
                    }
                }
                // Chưa có trong danh bạ → tạo mới
                startActivity(Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
                    .putExtra(ContactsContract.Intents.Insert.PHONE, number))
            } catch (_: Exception) {}
        }

        // Nút thêm vào danh bạ
        b.btnAddContact?.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
                    .putExtra(ContactsContract.Intents.Insert.PHONE, number))
            } catch (_: Exception) {}
        }

        // Xóa lịch sử
        b.btnDeleteLog.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        requireContext().contentResolver.delete(
                            CallLog.Calls.CONTENT_URI,
                            "${CallLog.Calls.NUMBER} = ?",
                            arrayOf(number)
                        )
                    } catch (_: Exception) {}
                }
                loadHistory(number)
            }
        }

        // Load lịch sử từ CallLog hệ thống
        viewLifecycleOwner.lifecycleScope.launch { loadHistory(number) }
    }

    private suspend fun loadHistory(number: String) {
        val entries = withContext(Dispatchers.IO) {
            val list = mutableListOf<CallEntry>()
            if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_CALL_LOG)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return@withContext list
            try {
                val cur = requireContext().contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.PHONE_ACCOUNT_ID),
                    "${CallLog.Calls.NUMBER} = ? OR ${CallLog.Calls.NUMBER} LIKE ?",
                    arrayOf(number, "%${number.takeLast(9)}"),
                    "${CallLog.Calls.DATE} DESC"
                )
                cur?.use {
                    val iType  = it.getColumnIndex(CallLog.Calls.TYPE)
                    val iDate  = it.getColumnIndex(CallLog.Calls.DATE)
                    val iDur   = it.getColumnIndex(CallLog.Calls.DURATION)
                    val iAcct  = it.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
                    while (it.moveToNext()) {
                        val acct = if (iAcct >= 0) it.getString(iAcct) ?: "" else ""
                        val simSlot = try {
                            val subId = acct.toIntOrNull()
                            if (subId != null) {
                                val sm = requireContext().getSystemService(SubscriptionManager::class.java)
                                sm?.getActiveSubscriptionInfo(subId)?.simSlotIndex ?: 0
                            } else 0
                        } catch (_: Exception) { 0 }
                        list.add(CallEntry(
                            type     = it.getInt(iType),
                            date     = it.getLong(iDate),
                            duration = it.getLong(iDur),
                            simSlot  = simSlot
                        ))
                    }
                }
            } catch (_: Exception) {}
            list
        }
        if (_b == null) return
        renderHistory(entries)
    }

    private fun renderHistory(entries: List<CallEntry>) {
        b.tvCallLogLabel.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        b.recyclerViewEntries.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerViewEntries.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun getItemCount() = entries.size
            override fun onCreateViewHolder(p: ViewGroup, t: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val vb = ItemCallHistoryEntryBinding.inflate(LayoutInflater.from(p.context), p, false)
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(vb.root) {}
            }
            override fun onBindViewHolder(h: androidx.recyclerview.widget.RecyclerView.ViewHolder, pos: Int) {
                val entry = entries[pos]
                val vb = ItemCallHistoryEntryBinding.bind(h.itemView)
                val isMissed = entry.type == CallLog.Calls.MISSED_TYPE

                // Loại cuộc gọi
                vb.tvEntryType.text = when (entry.type) {
                    CallLog.Calls.OUTGOING_TYPE -> "Cuộc gọi đi"
                    CallLog.Calls.INCOMING_TYPE -> "Cuộc gọi đến"
                    CallLog.Calls.MISSED_TYPE   -> "Cuộc gọi nhỡ"
                    CallLog.Calls.BLOCKED_TYPE  -> "Cuộc gọi bị chặn"
                    else -> "Cuộc gọi"
                }
                vb.tvEntryType.setTextColor(requireContext().getColor(
                    if (isMissed) R.color.missed_red else R.color.text_primary))

                // Icon
                when (entry.type) {
                    CallLog.Calls.MISSED_TYPE  -> { vb.ivEntryIcon.setImageResource(R.drawable.ic_call_missed); vb.ivEntryIcon.setColorFilter(requireContext().getColor(R.color.missed_red)) }
                    CallLog.Calls.OUTGOING_TYPE -> { vb.ivEntryIcon.setImageResource(R.drawable.ic_call_outgoing); vb.ivEntryIcon.clearColorFilter() }
                    else -> { vb.ivEntryIcon.setImageResource(R.drawable.ic_call_incoming); vb.ivEntryIcon.clearColorFilter() }
                }

                // SIM badge
                vb.tvEntrySimBadge?.text = (entry.simSlot + 1).toString()

                // Status: giờ + trạng thái
                val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                val timeStr = timeFmt.format(Date(entry.date))
                vb.tvEntryStatus.text = when {
                    isMissed -> {
                        val ring = entry.duration
                        if (ring > 0) "$timeStr  (Đổ chuông ${ring}giây)" else "$timeStr  (Nhỡ)"
                    }
                    entry.duration <= 0 -> "$timeStr  Chưa được kết nối"
                    else -> "$timeStr  (${formatDurationVi(entry.duration)})"
                }
                vb.tvEntryStatus.setTextColor(requireContext().getColor(
                    if (isMissed) R.color.missed_red else R.color.text_secondary))

                // Ngày
                val today = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                }
                val cal = Calendar.getInstance().apply { timeInMillis = entry.date }
                vb.tvEntryDate.text = if (cal.after(today))
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(entry.date))
                else SimpleDateFormat("d/M", Locale.getDefault()).format(Date(entry.date))
            }
        }
    }

    private fun setupAvatar(name: String) {
        val initial = name.firstOrNull { it.isLetter() }?.uppercaseChar()
        if (initial != null) {
            b.tvAvatar.text = initial.toString()
            b.tvAvatar.visibility = View.VISIBLE
            b.ivDefaultAvatar.visibility = View.GONE
        } else {
            b.tvAvatar.visibility = View.GONE
            b.ivDefaultAvatar.visibility = View.VISIBLE
        }
    }

    private fun getDefaultSimSlot(): Int = try {
        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_PHONE_STATE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return 0
        val sm = requireContext().getSystemService(SubscriptionManager::class.java)
        val defaultSubId = SubscriptionManager.getDefaultVoiceSubscriptionId()
        sm?.getActiveSubscriptionInfo(defaultSubId)?.simSlotIndex ?: 0
    } catch (_: Exception) { 0 }

    private fun formatNumber(number: String): String {
        val d = number.filter { it.isDigit() }
        return when {
            d.length == 10 -> "${d.take(3)} ${d.substring(3,6)} ${d.substring(6,8)} ${d.substring(8)}"
            else -> number
        }
    }

    private fun formatDurationVi(seconds: Long): String {
        val m = seconds / 60; val s = seconds % 60
        return when { m > 0 && s > 0 -> "${m}phút ${s}giây"; m > 0 -> "${m}phút"; else -> "${s}giây" }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
