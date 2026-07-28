package com.h.simplecall.ui

import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.h.simplecall.MainActivity
import com.h.simplecall.R
import com.h.simplecall.data.Contact
import com.h.simplecall.databinding.FragmentContactsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val INDEX_LETTERS = listOf(
    "★", "…", "A", "Â", "B", "C", "D", "Đ", "G", "H", "J", "K", "L", "M", "N",
    "O", "Ô", "P", "Q", "R", "S", "T", "U", "V", "X", "Y", "Z", "#"
)

class ContactsFragment : Fragment() {

    private var _b: FragmentContactsBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: ContactsAdapter
    private val indexViews = mutableMapOf<String, TextView>()
    private var activeIndexLetter: String? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentContactsBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val headers = listOf(
            ContactHeader(R.drawable.ic_person, getString(R.string.my_info)) { openMyProfile() },
            ContactHeader(R.drawable.ic_group, getString(R.string.my_groups)) { openMyGroups() }
        )

        adapter = ContactsAdapter(emptyList(), headers) { contact ->
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, ContactDetailFragment.newInstance(contact.number, contact.name))
                .addToBackStack("contactDetail")
                .commit()
            (activity as? MainActivity)?.hideNav()
        }
        b.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerView.adapter = adapter
        b.recyclerView.itemAnimator = null  // không flash khi update

        b.btnContactsSettings.setOnClickListener { (activity as? MainActivity)?.openSettings() }

        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { adapter.filter(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b2: Int, c: Int) {}
        })

        setupAlphabetIndex()
        highlightIndexLetter(adapter.letterAtOrBefore(0))
        // Nút "+" thêm liên hệ giờ là fabAddContact ở activity_main.xml (MainActivity gọi
        // openCreateContactPublic() khi bấm) - không còn FAB riêng trong layout này nữa.

        b.recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val firstVisible = lm.findFirstVisibleItemPosition()
                if (firstVisible == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return
                highlightIndexLetter(adapter.letterAtOrBefore(firstVisible))
            }
        })

        // Hiện cache ngay nếu ContactsRepository đã có sẵn (thường là do MainActivity.onCreate()
        // nạp trước từ lúc mở app) → vào tab = hiện liền, không chờ.
        com.h.simplecall.data.ContactsRepository.peek()?.let { cached ->
            if (cached.isNotEmpty()) {
                adapter.updateContacts(cached)
                b.tvContactsCount.text = getString(R.string.contacts_count, cached.size)
                b.tvContactsCount.visibility = View.VISIBLE
                setupAlphabetIndex()
                highlightIndexLetter(adapter.letterAtOrBefore(0))
                forceRecyclerRedraw()
            }
        }

        // Luôn đọc lại (đồng bộ liên hệ mới thêm/sửa/xoá) - dùng CHUNG ContactsRepository nên
        // kết quả cũng được lưu lại cho các lần mở tab/app sau, không riêng lẻ mỗi Fragment.
        viewLifecycleOwner.lifecycleScope.launch {
            val contacts = withContext(Dispatchers.IO) {
                com.h.simplecall.data.ContactsRepository.getContacts(requireContext())
            }
            if (_b == null) return@launch
            adapter.updateContacts(contacts)
            if (contacts.isNotEmpty()) {
                b.tvContactsCount.text = getString(R.string.contacts_count, contacts.size)
                b.tvContactsCount.visibility = View.VISIBLE
            }
            setupAlphabetIndex()
            highlightIndexLetter(adapter.letterAtOrBefore(0))
            forceRecyclerRedraw()
        }
    }

    /** Ép RecyclerView vẽ lại NGAY sau khi cập nhật dữ liệu, thay vì chỉ trông chờ
     *  notifyDataSetChanged() tự kích hoạt layout đúng lúc. Có trường hợp danh sách đã có
     *  dữ liệu trong bộ nhớ (adapter đã update) nhưng màn hình chưa vẽ lại kịp, chỉ hiện ra
     *  khi người dùng chạm vào đâu đó (như thanh chỉ mục A-Z) - gọi thẳng requestLayout +
     *  invalidate để loại bỏ độ trễ vẽ lại đó. */
    private fun forceRecyclerRedraw() {
        _b?.recyclerView?.apply {
            requestLayout()
            invalidate()
        }
    }

    private fun setupAlphabetIndex() {
        b.llAlphabetIndex.removeAllViews()
        indexViews.clear()
        INDEX_LETTERS.forEach { letter ->
            val tv = TextView(requireContext()).apply {
                text = letter
                textSize = 9.5f
                gravity = android.view.Gravity.CENTER
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 1, 0, 1)
            }
            indexViews[letter] = tv
            b.llAlphabetIndex.addView(tv)
        }
        setupAlphabetIndexTouch()
    }

    /** Cho phép chạm/kéo (giữ ngón tay rê lên xuống) dọc thanh chữ cái: lướt tới đâu, danh
     *  bạ tự cuộn theo chữ đó tới đó, giống thanh index của Zalo/Danh bạ Google. */
    private fun setupAlphabetIndexTouch() {
        b.llAlphabetIndex.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_MOVE -> {
                    letterAtY(event.y)?.let { letter ->
                        if (letter != activeIndexLetter) {
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.TEXT_HANDLE_MOVE)
                        }
                        jumpTo(letter)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    /** Xác định chữ cái tương ứng với vị trí Y đang chạm/kéo trên thanh chỉ mục. */
    private fun letterAtY(y: Float): String? {
        val container = b.llAlphabetIndex
        val count = container.childCount
        if (count == 0) return null
        val first = container.getChildAt(0)
        val last = container.getChildAt(count - 1)
        if (y <= first.top) return INDEX_LETTERS.firstOrNull()
        if (y >= last.bottom) return INDEX_LETTERS.lastOrNull()
        for (idx in 0 until count) {
            val child = container.getChildAt(idx)
            if (y >= child.top && y < child.bottom) return INDEX_LETTERS.getOrNull(idx)
        }
        return activeIndexLetter
    }

    private fun highlightIndexLetter(letter: String?) {
        if (letter == activeIndexLetter) return
        activeIndexLetter = letter
        val normalColor = ContextCompat.getColor(requireContext(), R.color.text_secondary)
        val activeColor  = ContextCompat.getColor(requireContext(), R.color.accent_yellow)
        indexViews.forEach { (key, tv) ->
            val isActive = key == letter
            tv.setTextColor(if (isActive) activeColor else normalColor)
            tv.setTypeface(null, if (isActive) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    private fun jumpTo(letter: String) {
        val lm = b.recyclerView.layoutManager as? LinearLayoutManager ?: return
        val pos = when (letter) {
            "★", "…" -> adapter.firstContactPosition()
            "#"       -> adapter.positionForLetter("#") ?: adapter.lastPosition()
            else      -> adapter.positionForLetter(letter)
        }
        if (pos != null && pos >= 0) {
            lm.scrollToPositionWithOffset(pos, 0)
            highlightIndexLetter(adapter.letterAtOrBefore(pos))
        }
    }

    private fun openMyProfile() {
        try { startActivity(Intent(Intent.ACTION_VIEW, ContactsContract.Profile.CONTENT_URI)) }
        catch (_: Exception) { Toast.makeText(requireContext(), "Không thể mở thông tin của bạn", Toast.LENGTH_SHORT).show() }
    }

    private fun openMyGroups() {
        Toast.makeText(requireContext(), "Tính năng nhóm liên hệ đang được phát triển", Toast.LENGTH_SHORT).show()
    }

    fun openCreateContactPublic() = openCreateContact()

    private fun openCreateContact() {
        try { startActivity(Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)) }
        catch (_: Exception) { Toast.makeText(requireContext(), "Không tìm thấy ứng dụng để tạo liên hệ", Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
