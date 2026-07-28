package com.h.simplecall.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telecom.TelecomManager
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.h.simplecall.MainActivity
import com.h.simplecall.R
import com.h.simplecall.data.CallLogEntry
import com.h.simplecall.databinding.FragmentCallLogBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CallLogFragment : Fragment() {

    private var _b: FragmentCallLogBinding? = null
    private val b get() = _b!!
    private var allEntries: List<CallLogEntry> = emptyList()
    private var isDualSim: Boolean = false
    private var showMissedOnly = false
    private var adapter: CallLogAdapter? = null

    // Observer để tự reload khi hệ thống ghi thêm cuộc gọi mới vào CallLog
    private val callLogObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { loadFromSystem() }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentCallLogBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.btnRecentsSettings.setOnClickListener {
            // Mở cài đặt ứng dụng điện thoại hệ thống
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_CALL_SETTINGS)
                startActivity(intent)
            } catch (_: Exception) {
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = android.net.Uri.parse("package:com.android.phone")
                    startActivity(intent)
                } catch (_: Exception) {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
                }
            }
        }
        // Tìm kiếm inline giống Danh bạ
        b.btnRecentsSearch.setOnClickListener {
            b.searchBar.visibility = android.view.View.VISIBLE
            b.llCallLogHeader.visibility = android.view.View.GONE
            b.etSearch.requestFocus()
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(b.etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        b.btnSearchClose.setOnClickListener {
            b.searchBar.visibility = android.view.View.GONE
            b.llCallLogHeader.visibility = android.view.View.VISIBLE
            b.etSearch.setText("")
            adapter?.updateItems(allEntries)
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(b.etSearch.windowToken, 0)
        }
        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b2: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString().trim()
                val filtered = if (q.isEmpty()) allEntries
                else allEntries.filter {
                    it.number.contains(q, ignoreCase = true) ||
                    it.name.contains(q, ignoreCase = true)
                }
                adapter?.updateItems(filtered)
            }
        })
        b.tabAll.setOnClickListener { selectTab(missed = false) }
        b.tabMissed.setOnClickListener { selectTab(missed = true) }

        isDualSim = callCapableAccountCount() >= 2

        adapter = CallLogAdapter(
            emptyList(),
            isDualSim = isDualSim,
            onCall = { (activity as? MainActivity)?.placeCall(it) },
            onShowHistory = { number ->
                val entry = allEntries.firstOrNull { it.number == number }
                val name = entry?.name ?: number
                requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, CallHistoryFragment.newInstance(number, name))
                    .addToBackStack("history")
                    .commit()
                (activity as? MainActivity)?.hideNav()
            }
        )
        b.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerView.adapter = adapter
        b.recyclerView.itemAnimator = null

        // Đăng ký observer: tự reload khi có cuộc gọi mới
        requireContext().contentResolver.registerContentObserver(
            CallLog.Calls.CONTENT_URI, true, callLogObserver)

        loadFromSystem()
    }

    private fun callCapableAccountCount(): Int {
        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_PHONE_STATE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return 0
        return try {
            requireContext().getSystemService(TelecomManager::class.java)
                ?.callCapablePhoneAccounts?.size ?: 0
        } catch (_: SecurityException) { 0 }
    }

    /** Đọc thẳng lịch sử cuộc gọi từ CallLog hệ thống — không dùng Room DB nội bộ nữa.
     *  Kết quả khớp 100% với ứng dụng điện thoại gốc vì cùng nguồn dữ liệu. */
    private fun loadFromSystem() {
        val ctx = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { querySystemCallLog(ctx) }
            if (_b == null) return@launch
            allEntries = entries
            renderList()
        }
    }

    private fun querySystemCallLog(ctx: Context): List<CallLogEntry> {
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CALL_LOG)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()
        val entries = mutableListOf<CallLogEntry>()
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.PHONE_ACCOUNT_ID
        )
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null, null,
            "${CallLog.Calls.DATE} DESC LIMIT 200"
        )?.use { cursor ->
            val iNum      = cursor.getColumnIndex(CallLog.Calls.NUMBER)
            val iName     = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val iType     = cursor.getColumnIndex(CallLog.Calls.TYPE)
            val iDate     = cursor.getColumnIndex(CallLog.Calls.DATE)
            val iDuration = cursor.getColumnIndex(CallLog.Calls.DURATION)
            val iSim      = cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
            while (cursor.moveToNext()) {
                val number   = cursor.getString(iNum) ?: continue
                val name     = cursor.getString(iName) ?: ""
                val type     = cursor.getInt(iType)
                val date     = cursor.getLong(iDate)
                val duration = cursor.getLong(iDuration)
                val simId    = cursor.getString(iSim)
                val simSlot  = when {
                    simId.isNullOrEmpty() -> null
                    simId.contains("1") -> 0
                    simId.contains("2") -> 1
                    else -> null
                }
                entries.add(CallLogEntry(
                    name     = name,
                    number   = number,
                    type     = type,
                    date     = date,
                    duration = duration,
                    simSlot  = simSlot
                ))
            }
        }
        return entries
    }

    private fun selectTab(missed: Boolean) {
        showMissedOnly = missed
        val accent      = ContextCompat.getColor(requireContext(), R.color.accent_blue)
        val bright      = ContextCompat.getColor(requireContext(), R.color.text_primary)
        val secondary   = ContextCompat.getColor(requireContext(), R.color.text_secondary)
        val transparent = ContextCompat.getColor(requireContext(), android.R.color.transparent)
        b.tvTabAll.setTextColor(if (missed) secondary else bright)
        b.tvTabAll.setTypeface(null, if (missed) android.graphics.Typeface.NORMAL else android.graphics.Typeface.BOLD)
        b.tvTabMissed.setTextColor(if (missed) bright else secondary)
        b.tvTabMissed.setTypeface(null, if (missed) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        b.tabAllUnderline.setBackgroundColor(if (missed) transparent else accent)
        b.tabMissedUnderline.setBackgroundColor(if (missed) accent else transparent)
        renderList()
    }

    private fun renderList() {
        val entries = if (showMissedOnly)
            allEntries.filter { it.type == CallLog.Calls.MISSED_TYPE }
        else allEntries

        // Gộp các cuộc gọi LIÊN TIẾP cùng số điện thoại thành 1 dòng
        val collapsed = mutableListOf<CallLogEntry>()
        for (entry in entries) {
            if (collapsed.isNotEmpty() && collapsed.last().number == entry.number) continue
            collapsed.add(entry)
        }

        if (collapsed.isEmpty()) {
            b.tvEmpty.text = if (showMissedOnly) "Không có cuộc gọi nhỡ" else "Chưa có nhật ký cuộc gọi"
            b.tvEmpty.visibility = View.VISIBLE
            b.recyclerView.visibility = View.GONE
        } else {
            b.tvEmpty.visibility = View.GONE
            b.recyclerView.visibility = View.VISIBLE
            adapter?.updateItems(collapsed)
        }
    }

    fun setHeaderVisible(visible: Boolean) {
        _b?.llCallLogHeader?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { requireContext().contentResolver.unregisterContentObserver(callLogObserver) }
        catch (_: Exception) {}
        _b = null
    }
}
