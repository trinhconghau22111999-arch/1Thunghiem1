package com.h.simplecall.ui

/** LƯU Ý: kể từ khi tab "Gần đây" ở thanh điều hướng dưới chuyển sang dùng thống nhất
 *  DialerFragment (xem MainActivity.goToTab()) để tránh 2 implementation khác nhau gây lệch
 *  hành vi ("quay lại Gần đây từ Danh bạ thì mất lịch sử/không có bàn phím"), class này KHÔNG
 *  CÒN ĐƯỢC THAM CHIẾU Ở ĐÂU NỮA. Giữ lại file phòng trường hợp cần tham khảo lại logic đọc
 *  CallLog hệ thống, nhưng không dùng - tránh nhầm sửa nhầm file này mà tưởng đang sửa đúng
 *  màn "Gần đây" thật (chính là DialerFragment.kt). */

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

    companion object {
        /** Cache toàn bộ lịch sử cuộc gọi trong memory — chỉ load lại khi có cuộc gọi mới */
        var cachedEntries: List<CallLogEntry> = emptyList()
        /** Gọi hàm này từ InCallService/sau cuộc gọi để buộc reload lần sau */
        fun invalidateCache() { cachedEntries = emptyList() }
    }

    private var _b: FragmentCallLogBinding? = null
    private val b get() = _b!!
    private var allEntries: List<CallLogEntry> = emptyList()
    private var isDualSim: Boolean = false
    private var showMissedOnly = false
    private var adapter: CallLogAdapter? = null

    // Observer để tự reload khi hệ thống ghi thêm cuộc gọi mới vào CallLog
    private val callLogObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            invalidateCache()  // Có cuộc gọi mới → xóa cache, buộc load lại
            loadFromSystem()
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentCallLogBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.btnRecentsSettings.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, SettingsFragment.newInstance())
                .addToBackStack("settings")
                .commit()
            (activity as? MainActivity)?.hideNav()
        }
        // Tìm kiếm inline giống Danh bạ
        b.btnRecentsSearch.setOnClickListener {
            b.searchBar.visibility = android.view.View.VISIBLE
            b.llCallLogTitleTabs.visibility = android.view.View.GONE
            b.etSearch.requestFocus()
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(b.etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        b.btnSearchClose.setOnClickListener {
            b.searchBar.visibility = android.view.View.GONE
            b.llCallLogTitleTabs.visibility = android.view.View.VISIBLE
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

    override fun onResume() {
        super.onResume()
        // TRƯỚC ĐÂY chỉ gọi loadFromSystem() một lần duy nhất trong onViewCreated() - nếu vì bất
        // kỳ lý do gì (chuyển tab nhanh, coroutine bị timing lệch...) lần tải đầu không kịp hiển
        // thị đúng, màn hình sẽ đứng yên ở danh sách rỗng CHO ĐẾN KHI Fragment này bị huỷ và tạo
        // lại từ đầu — đúng triệu chứng "qua tab Danh bạ rồi quay lại Gần đây thì mất lịch sử,
        // phải bấm mở bàn phím (tạo DialerFragment mới, có tải lại) mới thấy lại". Gọi lại
        // loadFromSystem() mỗi khi tab này được hiển thị lại đảm bảo danh sách luôn được làm mới,
        // không phụ thuộc vào đúng 1 lần tải lúc tạo Fragment có thành công hay không.
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
     *  Lấy TOÀN BỘ lịch sử đang có trong hệ thống (không giới hạn số dòng), kết quả
     *  khớp 100% với ứng dụng điện thoại gốc vì cùng nguồn dữ liệu. */
    private fun loadFromSystem() {
        val ctx = context?.applicationContext ?: return

        // Nếu đã có cache → hiện ngay, không load lại (chỉ load khi cache rỗng)
        if (cachedEntries.isNotEmpty()) {
            allEntries = cachedEntries
            renderList()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            // Stream dần từng batch 50 dòng lên UI trong lúc đọc
            querySystemCallLogProgressive(ctx) { batch ->
                if (_b == null) return@querySystemCallLogProgressive
                allEntries = batch
                renderList()
            }
            // Lưu vào cache sau khi đọc xong toàn bộ
            cachedEntries = allEntries
        }
    }

    /** Đọc lịch sử và emit lên UI sau mỗi 50 dòng để hiện dần từ trên xuống */
    private suspend fun querySystemCallLogProgressive(
        ctx: Context,
        onBatch: suspend (List<CallLogEntry>) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CALL_LOG)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return@withContext
        val entries = mutableListOf<CallLogEntry>()
        val projection = arrayOf(
            CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE,
            CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.PHONE_ACCOUNT_ID
        )
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI, projection, null, null, "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            val iNum = cursor.getColumnIndex(CallLog.Calls.NUMBER)
            val iName = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val iType = cursor.getColumnIndex(CallLog.Calls.TYPE)
            val iDate = cursor.getColumnIndex(CallLog.Calls.DATE)
            val iDuration = cursor.getColumnIndex(CallLog.Calls.DURATION)
            val iSim = cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
            while (cursor.moveToNext()) {
                val number = cursor.getString(iNum) ?: continue
                var name = cursor.getString(iName) ?: ""
                if (name.isEmpty()) {
                    name = com.h.simplecall.data.ContactsRepository.lookupNameByNumber(ctx, number) ?: ""
                }
                val type = cursor.getInt(iType)
                val date = cursor.getLong(iDate)
                val duration = cursor.getLong(iDuration)
                val simId = cursor.getString(iSim)
                val simSlot = when {
                    simId.isNullOrEmpty() -> null
                    simId.contains("1") -> 0
                    simId.contains("2") -> 1
                    else -> null
                }
                entries.add(CallLogEntry(name = name, number = number, type = type,
                    date = date, duration = duration, simSlot = simSlot))
                // Emit batch mỗi 50 dòng để hiện dần lên UI
                if (entries.size % 50 == 0) {
                    val snapshot = entries.toList()
                    withContext(Dispatchers.Main) { onBatch(snapshot) }
                }
            }
        }
        // Emit phần còn lại
        withContext(Dispatchers.Main) { onBatch(entries.toList()) }
    }
        )
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null, null,
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            val iNum      = cursor.getColumnIndex(CallLog.Calls.NUMBER)
            val iName     = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val iType     = cursor.getColumnIndex(CallLog.Calls.TYPE)
            val iDate     = cursor.getColumnIndex(CallLog.Calls.DATE)
            val iDuration = cursor.getColumnIndex(CallLog.Calls.DURATION)
            val iSim      = cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
            while (cursor.moveToNext()) {
                val number   = cursor.getString(iNum) ?: continue
                var name     = cursor.getString(iName) ?: ""
                val type     = cursor.getInt(iType)
                val date     = cursor.getLong(iDate)
                val duration = cursor.getLong(iDuration)
                val simId    = cursor.getString(iSim)
                // CallLog không phải lúc nào cũng tự điền CACHED_NAME cho cuộc gọi ĐI tới số đã lưu
                // (khác với cuộc gọi ĐẾN, luôn được hệ thống tự tra caller ID) → tự tra bù qua
                // PhoneLookup để không bị hiện trơ số khi số đó đã có trong danh bạ.
                if (name.isEmpty()) {
                    name = com.h.simplecall.data.ContactsRepository.lookupNameByNumber(ctx, number) ?: ""
                }
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
        _b?.llCallLogTitleTabs?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { requireContext().contentResolver.unregisterContentObserver(callLogObserver) }
        catch (_: Exception) {}
        _b = null
    }
}
