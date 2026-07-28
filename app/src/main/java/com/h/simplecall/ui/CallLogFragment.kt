package com.h.simplecall.ui

import android.content.Context
import android.os.Bundle
import android.provider.CallLog
import android.telecom.TelecomManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import com.h.simplecall.MainActivity
import com.h.simplecall.R
import com.h.simplecall.call.CallHistoryManager
import com.h.simplecall.data.CallLogEntry
import com.h.simplecall.data.local.AppDatabase
import com.h.simplecall.data.local.toCallLogEntry
import com.h.simplecall.databinding.FragmentCallLogBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CallLogFragment : Fragment() {

    private var _b: FragmentCallLogBinding? = null
    private val b get() = _b!!
    private var allEntries: List<CallLogEntry> = emptyList()
    private var isDualSim: Boolean = false
    private var showMissedOnly = false
    private var adapter: CallLogAdapter? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentCallLogBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.btnRecentsSettings.setOnClickListener { (activity as? MainActivity)?.openSettings() }
        b.btnRecentsSearch.setOnClickListener {
            android.widget.Toast.makeText(requireContext(), "Tìm kiếm đang được phát triển", android.widget.Toast.LENGTH_SHORT).show()
        }
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
        b.recyclerView.itemAnimator = null  // không flash khi update

        observeCallLog()
    }

    private fun callCapableAccountCount(): Int {
        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_PHONE_STATE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return 0
        return try {
            requireContext().getSystemService(TelecomManager::class.java)
                ?.callCapablePhoneAccounts?.size ?: 0
        } catch (_: SecurityException) { 0 }
    }

    private fun observeCallLog() {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            // Đảm bảo migration chạy xong 1 lần duy nhất trên IO
            withContext(Dispatchers.IO) { CallHistoryManager.awaitReady() }

            // observe Flow: Room tự push data mỗi khi có cuộc gọi mới
            // flowOn(IO) = query trên IO thread, collect trên Main
            AppDatabase.getInstance(appContext)
                .callHistoryDao()
                .observeAll()
                .flowOn(Dispatchers.IO)
                .collect { entities ->
                    if (_b == null) return@collect
                    allEntries = entities.map { it.toCallLogEntry() }
                    renderList()
                    // Đánh dấu đã xem trên IO (không await)
                    launch(Dispatchers.IO) {
                        try {
                            AppDatabase.getInstance(appContext).callHistoryDao()
                                .markMissedAsRead(CallLog.Calls.MISSED_TYPE)
                        } catch (_: Exception) {}
                    }
                }
        }
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

        // Gộp các cuộc gọi LIÊN TIẾP cùng số điện thoại thành 1 dòng.
        // Ví dụ: gọi 0909 lúc 10:01, gọi lại 0909 lúc 10:02 -> chỉ hiện 1 dòng 0909.
        // Chỉ gộp khi 2 entry KỀ NHAU có cùng số, không gộp nếu xen giữa có số khác.
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

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
