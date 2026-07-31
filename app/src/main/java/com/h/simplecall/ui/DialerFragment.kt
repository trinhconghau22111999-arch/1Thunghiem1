package com.h.simplecall.ui

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.h.simplecall.MainActivity
import com.h.simplecall.R
import com.h.simplecall.data.CallLogEntry
import com.h.simplecall.data.Contact
import com.h.simplecall.databinding.FragmentDialerBinding

class DialerFragment : Fragment() {

    companion object {
        /** Cache "Gần đây" giữa các lần mở lại tab/app – hiện ngay từ cache trong lúc chờ
         *  đọc lại DB ở nền, giống hệt cơ chế cachedContacts của ContactsFragment, để tránh
         *  màn hình trắng/giật khi mở lại. Dữ liệu gốc vẫn luôn lấy từ Room (đã lưu bền), đây
         *  chỉ là bản sao trong RAM để hiển thị tức thời. */
        @Volatile var cachedRecents: List<CallLogEntry> = emptyList()
        @Volatile var recentsCacheLoaded: Boolean = false

        private val SUB_LABELS = mapOf(
            "2" to "ABC", "3" to "DEF", "4" to "GHI",
            "5" to "JKL", "6" to "MNO", "7" to "PQRS",
            "8" to "TUV", "9" to "WXYZ", "0" to "+"
        )
        private val DTMF_MAP = mapOf(
            "0" to ToneGenerator.TONE_DTMF_0, "1" to ToneGenerator.TONE_DTMF_1,
            "2" to ToneGenerator.TONE_DTMF_2, "3" to ToneGenerator.TONE_DTMF_3,
            "4" to ToneGenerator.TONE_DTMF_4, "5" to ToneGenerator.TONE_DTMF_5,
            "6" to ToneGenerator.TONE_DTMF_6, "7" to ToneGenerator.TONE_DTMF_7,
            "8" to ToneGenerator.TONE_DTMF_8, "9" to ToneGenerator.TONE_DTMF_9,
            "*" to ToneGenerator.TONE_DTMF_S, "#" to ToneGenerator.TONE_DTMF_P
        )

        fun newInstanceWithNumber(number: String?): DialerFragment {
            return DialerFragment().also {
                it.arguments = Bundle().apply { putString("number", number) }
            }
        }
    }

    private var _b: FragmentDialerBinding? = null
    private val b get() = _b!!
    private var toneGen: ToneGenerator? = null
    private lateinit var suggestAdapter: ContactSuggestAdapter
    // Adapter DÙNG LẠI cho rvRecents thay vì tạo mới mỗi lần renderRecents() chạy (xem lý do
    // trong renderRecents() bên dưới - gọi RecyclerView.setAdapter() lại trong lúc người dùng
    // đang chạm vào 1 dòng sẽ HUỶ NGANG cử chỉ chạm đó).
    private var recentsAdapter: CallLogAdapter? = null
    private var keypadVisible = true
    private var keypadVisibleBeforeSearch = true
    private var pendingNumberToAdd: String = ""
    private var allRecentEntries: List<CallLogEntry> = emptyList()
    private var showMissedOnly = false
    // Truy vấn CallLog/Contacts CHẠY NỀN: trước đây chạy thẳng trên main thread mỗi khi mở màn
    // hình này (onViewCreated + onResume) và mỗi lần gõ số (searchSuggestions), gây lag/giật khi
    // bật bàn phím lên và trong lúc gõ — cùng nhóm lỗi ANR đã sửa ở các màn hình khác.
    private val bgExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // "Phiên bản" mỗi lần gõ số, dùng để huỷ kết quả tra cứu cũ trả về trễ (gõ nhanh nhiều ký tự)
    private var searchGeneration = 0
    // Runnable debounce đang chờ chạy searchSuggestions() thật sự (nếu có) - huỷ đi mỗi khi có
    // thay đổi mới trước khi kịp chạy. Khi DÁN cả 1 số dài vào, addTextChangedListener vẫn có
    // thể bắn afterTextChanged() cho từng bước thay đổi trung gian -> nếu bgExecutor (chỉ 1
    // luồng) phải chạy NGAY truy vấn ContactsContract (LIKE '%...%', không dùng được index, quét
    // toàn bảng danh bạ) cho mọi trạng thái trung gian, các truy vấn xếp hàng dồn cục khiến kết
    // quả cuối cùng (số vừa dán xong) phải đợi hết các truy vấn thừa đã lỗi thời trước đó chạy
    // xong - đây chính là nguyên nhân "check số bị chậm rất nhiều" khi dán. Debounce 150ms để chỉ
    // thực sự truy vấn 1 lần, cho trạng thái ổn định cuối cùng.
    private var pendingSearchRunnable: Runnable? = null

    // ContentObserver lắng nghe CallLog.Calls.CONTENT_URI: hệ thống ghi xong 1 cuộc gọi vào
    // CallLog (thường 1-3 giây sau khi kết thúc) thì observer này tự kích hoạt loadRecents()
    // lại ngay, không phụ thuộc vào onResume() đã chạy trước hay sau khi CallLog cập nhật.
    private var callLogObserver: android.database.ContentObserver? = null

    private val pickContactLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickContact()
    ) { contactUri ->
        if (contactUri == null) return@registerForActivityResult
        try {
            startActivity(Intent(Intent.ACTION_EDIT).apply {
                setDataAndType(contactUri, android.provider.ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, pendingNumberToAdd)
                putExtra("finishActivityOnSaveCompleted", true)
            })
        } catch (_: Exception) {
            android.widget.Toast.makeText(requireContext(), "Không thể mở màn hình sửa liên hệ", android.widget.Toast.LENGTH_SHORT).show()
        }
    }


    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentDialerBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try { toneGen = ToneGenerator(AudioManager.STREAM_DTMF, 80) } catch (_: Exception) {}

        suggestAdapter = ContactSuggestAdapter { number ->
            (activity as? MainActivity)?.placeCall(number)
        }
        b.rvSuggestions.layoutManager = LinearLayoutManager(requireContext())
        b.rvSuggestions.adapter = suggestAdapter

        b.rvRecents.layoutManager = LinearLayoutManager(requireContext())
        b.rvRecents.itemAnimator = null // không nháy khi cache hiện trước rồi refresh nền đè lên
        loadRecents()

        // Đồng bộ với tab Gần đây/Danh bạ: mở đúng màn Cài đặt thay vì chỉ hiện toast báo
        // "Không có cài đặt" — app đã có SettingsFragment thật (tuỳ chỉnh giao diện Sáng/Tối).
        b.btnDialerSettings.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, SettingsFragment.newInstance())
                .addToBackStack("settings")
                .commit()
            (activity as? MainActivity)?.hideNav()
        }
        // Tìm kiếm inline giống hệt tab Gần đây: lọc trực tiếp trên danh sách allRecentEntries
        // đã tải sẵn ở nền, không cần toast placeholder nữa.
        //
        // LỖI ĐÃ SỬA: trước đây bấm icon tìm kiếm chỉ hiện thanh tìm kiếm + bật bàn phím HỆ THỐNG
        // để gõ, nhưng KHÔNG ẩn panelKeypad (bàn phím số 0-9 riêng của app, luôn hiện sẵn ở màn
        // Gần đây) - kết quả là 2 bàn phím cùng hiện chồng lên nhau. Giờ ẩn hẳn panelKeypad lúc
        // mở tìm kiếm (chỉ cần bàn phím hệ thống là đủ để gõ tên/số tìm), và khôi phục lại ĐÚNG
        // trạng thái bàn phím số trước đó (đang mở hay đã thu gọn) khi đóng tìm kiếm.
        b.btnDialerSearch.setOnClickListener {
            keypadVisibleBeforeSearch = keypadVisible
            if (keypadVisible) setKeypadVisible(false)
            b.searchBarDialer.visibility = View.VISIBLE
            b.llDialerTitleTabs.visibility = View.GONE
            b.etSearchDialer.requestFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(b.etSearchDialer, InputMethodManager.SHOW_IMPLICIT)
        }
        b.btnSearchCloseDialer.setOnClickListener {
            b.searchBarDialer.visibility = View.GONE
            b.llDialerTitleTabs.visibility = View.VISIBLE
            b.etSearchDialer.setText("")
            renderRecents(callCapableAccounts().size >= 2)
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(b.etSearchDialer.windowToken, 0)
            if (keypadVisibleBeforeSearch) setKeypadVisible(true)
        }
        b.etSearchDialer.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b2: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString().trim()
                val filtered = if (q.isEmpty()) allRecentEntries
                else allRecentEntries.filter {
                    it.number.contains(q, ignoreCase = true) ||
                    it.name.contains(q, ignoreCase = true)
                }
                b.rvRecents.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
                val existing = recentsAdapter
                if (existing == null) {
                    val adapter = CallLogAdapter(
                        filtered,
                        isDualSim = callCapableAccounts().size >= 2,
                        onCall = { (activity as? MainActivity)?.placeCall(it) },
                        onShowHistory = { number ->
                            val entry = filtered.firstOrNull { it.number == number }
                            val name = entry?.name ?: number
                            requireActivity().supportFragmentManager.beginTransaction()
                                .replace(R.id.fragmentContainer, CallHistoryFragment.newInstance(number, name))
                                .addToBackStack("history")
                                .commit()
                            (activity as? MainActivity)?.hideNav()
                        }
                    )
                    recentsAdapter = adapter
                    b.rvRecents.adapter = adapter
                } else {
                    existing.updateItems(filtered)
                }
            }
        })
        b.tabAll.setOnClickListener { selectTab(missed = false) }
        b.tabMissed.setOnClickListener { selectTab(missed = true) }

        setupKeypad(view)

        b.btnBackspace.setOnClickListener {
            val t = b.etNumber.text.toString()
            if (t.isNotEmpty()) b.etNumber.setText(t.dropLast(1))
            syncBackspace()
        }
        b.btnBackspace.setOnLongClickListener {
            b.etNumber.setText(""); syncBackspace(); true
        }

        b.btnDialMenu.setOnClickListener { showDialMenu(it) }

        b.etNumber.addTextChangedListener(object : TextWatcher {
            private var editing = false
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b2: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (editing) return; editing = true
                val raw = dialableFilter(s.toString())
                val fmt = formatVN(raw)
                if (fmt != s.toString()) {
                    b.etNumber.setText(fmt)
                    b.etNumber.setSelection(fmt.length)
                }
                syncBackspace()
                searchSuggestions(raw.filter { it.isDigit() || it == '+' })
                editing = false
            }
        })

        // Điền số tới từ bên ngoài (dán/chia sẻ/link tel:) SAU KHI đã gắn TextWatcher ở trên,
        // để setText() ở đây tự kích hoạt afterTextChanged() → searchSuggestions() giống hệt
        // như khi người dùng tự gõ tay từng số — tức đối chiếu danh bạ ngay lập tức.
        arguments?.getString("number")?.let { b.etNumber.setText(it) }

        setupCallButtons()

        // Nút video dùng FrameLayout có id btnVideoCall
        view.findViewById<View>(R.id.btnVideoCall)?.setOnClickListener {
            android.widget.Toast.makeText(requireContext(),
                getString(R.string.video_call_unsupported), android.widget.Toast.LENGTH_SHORT).show()
        }

        b.rowCreateContact.setOnClickListener {
            val raw = b.etNumber.text.toString().filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
            try {
                startActivity(Intent(Intent.ACTION_INSERT, android.provider.ContactsContract.Contacts.CONTENT_URI)
                    .putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, raw))
            } catch (_: Exception) {
                android.widget.Toast.makeText(requireContext(), "Không thể mở màn hình tạo liên hệ", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        b.rowAddToExisting.setOnClickListener {
            pendingNumberToAdd = b.etNumber.text.toString().filter { it.isDigit() || it == '+' }
            try { pickContactLauncher.launch(null) } catch (_: Exception) {
                android.widget.Toast.makeText(requireContext(), "Không thể chọn liên hệ", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        b.rowSendSms.setOnClickListener {
            val raw = b.etNumber.text.toString().filter { it.isDigit() || it == '+' }
            try { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$raw"))) } catch (_: Exception) {
                android.widget.Toast.makeText(requireContext(), "Không tìm thấy ứng dụng nhắn tin", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        b.rowVideoMeet.setOnClickListener {
            android.widget.Toast.makeText(requireContext(),
                getString(R.string.video_call_unsupported), android.widget.Toast.LENGTH_SHORT).show()
        }

        b.btnKeypadToggle.setOnClickListener {
            setKeypadVisible(!keypadVisible)
        }

        syncBackspace()

        // Bàn phím số luôn bật sẵn khi vào app/tab Gần đây
        setKeypadVisible(true)

        // KHÔNG bật bàn phím hệ thống của máy ở đây nữa. etNumber chỉ dùng để HIỂN THỊ số đang
        // gõ, việc nhập số chỉ đến từ các phím bấm 0-9 * # trong bàn phím số riêng của app (xem
        // setupKeypad bên dưới). "android:showSoftInputOnFocus" không phải attribute XML công
        // khai (aapt2 từ chối biên dịch) nên phải set qua code bằng method tương ứng.
        b.etNumber.showSoftInputOnFocus = false
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(b.etNumber.windowToken, 0)
    }

    /** Ép chiều cao 1 dòng văn bản về đúng [targetHeight] (px), KHÔNG phụ thuộc cỡ chữ thật của
     *  dòng đó. Dùng để khoảng cách (line spacing) giữa 2 dòng luôn nhất quán dù dòng dưới có
     *  cỡ chữ to nhỏ khác nhau (ví dụ dấu "+" to hơn "ABC" nhưng khoảng cách với số phía trên
     *  vẫn phải bằng nhau). */
    private class FixedLineHeightSpan(private val targetHeight: Int) : android.text.style.LineHeightSpan {
        override fun chooseHeight(
            text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int,
            fm: android.graphics.Paint.FontMetricsInt
        ) {
            val original = fm.descent - fm.ascent
            if (original <= 0) return
            val ratio = targetHeight.toFloat() / original
            fm.descent = Math.round(fm.descent * ratio)
            fm.ascent = fm.descent - targetHeight
            fm.top = fm.ascent
            fm.bottom = fm.descent
        }
    }

    private fun setupKeypad(view: View) {
        val grid = view.findViewById<GridLayout>(R.id.keypad)
        for (i in 0 until grid.childCount) {
            val btn = grid.getChildAt(i) as? Button ?: continue
            val tag = btn.tag as? String ?: continue

            val sub = SUB_LABELS[tag]
            if (sub != null) {
                val ss = SpannableStringBuilder()
                ss.append(tag); ss.append("\n")
                val subStart = ss.length; ss.append(sub)
                // Phím "0": dấu "+" giảm còn 90% mức trước (0.63 * 0.9 ≈ 0.57x)
                // Phím "0": dấu "+" to gấp đôi mức bình thường (0.35 * 2 = 0.7). Trước đó bị
                // đẩy lên 1.05x + setLineSpacing 1.75x khiến tổng chiều cao 2 dòng vượt quá
                // chiều cao cố định của phím (68dp) -> dấu "+" bị cắt mất/ẩn. Bỏ line spacing
                // dư thừa, giữ đúng 2x như yêu cầu gốc để vừa khít trong khung phím.
                // Giờ thu nhỏ dấu "+" còn 80% mức trên (0.7 * 0.8 = 0.56x) và rút khoảng cách
                // với số "0" còn 80% (line spacing multiplier 0.8) theo yêu cầu mới nhất.
                val subScale = if (tag == "0") 0.7f * 0.8f else 0.35f
                ss.setSpan(RelativeSizeSpan(subScale), subStart, ss.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                ss.setSpan(ForegroundColorSpan(requireContext().getColor(R.color.text_secondary)),
                    subStart, ss.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (tag == "0") {
                    // Khoảng cách "+" với "0" phải bằng đúng khoảng cách "ABC" với "2"... - đo
                    // chiều cao dòng chuẩn (cỡ chữ 0.35x, y hệt các phím khác) rồi ÉP dòng "+"
                    // (cỡ chữ 0.56x, to hơn) dùng chung chiều cao đó bằng FixedLineHeightSpan,
                    // thay vì chỉnh lineSpacingMultiplier áng chừng như trước.
                    val standardPaint = android.text.TextPaint(btn.paint)
                    standardPaint.textSize = btn.textSize * 0.35f
                    val standardFm = standardPaint.fontMetricsInt
                    val standardHeight = standardFm.descent - standardFm.ascent
                    ss.setSpan(FixedLineHeightSpan(standardHeight), subStart, ss.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                btn.text = ss; btn.setLines(2); btn.textSize = 30f
                btn.setLineSpacing(0f, 0.9f)
            }

            if (tag == "*") {
                // Trước đó đã tăng gấp đôi (30sp -> 60sp), giờ thu nhỏ lại còn 2/3 kích thước đó
                // theo yêu cầu (60 * 2/3 = 40sp), không ảnh hưởng các phím khác.
                btn.textSize = 40f
            }

            if (tag == "1") {
                // Khoảng cách dòng 2 (icon) với số "1" phải giống hệt khoảng cách "2"-"ABC",
                // "3"-"DEF"... của các phím khác - các phím đó dùng cỡ chữ 0.35× để tính chiều
                // cao dòng 2. Trước đây phím 1 tự tính chiều cao dòng 2 trực tiếp từ cỡ icon đã
                // thu nhỏ (0.175×), khiến dòng 2 bị "co" lại theo icon nhỏ -> icon dính sát vào
                // số 1. Giờ tách riêng: chiều cao KHUNG dòng 2 (rowHeight) vẫn tính theo 0.35×
                // giống các phím khác để khoảng cách bằng nhau, còn ICON thật sự vẽ bên trong
                // vẫn nhỏ (0.175×) và được canh giữa khung đó bằng InsetDrawable.
                val ss = SpannableStringBuilder()
                ss.append("1"); ss.append("\n")
                val sub2Start = ss.length; ss.append("  ")  // 2 space để icon có chỗ
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_key1_glasses)?.let { d ->
                    val rowPaint = android.text.TextPaint(btn.paint)
                    rowPaint.textSize = btn.textSize * 0.35f
                    val fmRow = rowPaint.fontMetricsInt
                    val rowHeight = fmRow.descent - fmRow.ascent

                    val iconPaint = android.text.TextPaint(btn.paint)
                    iconPaint.textSize = btn.textSize * 0.175f
                    val fmIcon = iconPaint.fontMetricsInt
                    val iconHeight = fmIcon.descent - fmIcon.ascent
                    val iconWidth = (iconHeight * 2.2f).toInt()

                    // Tăng khoảng cách giữa số "1" và icon thêm 20% so với mức hiện tại
                    // (2.8f * 1.2 = 3.36f)
                    val gap = ((rowHeight - iconHeight) / 2f).coerceAtLeast(0f)
                    val insetTop = (gap * 3.36f).toInt()
                    val insetBottom = gap.toInt()
                    val newRowHeight = iconHeight + insetTop + insetBottom
                    val inset = android.graphics.drawable.InsetDrawable(d, 0, insetTop, 0, insetBottom)
                    inset.setBounds(0, 0, iconWidth, newRowHeight)
                    ss.setSpan(ImageSpan(inset, ImageSpan.ALIGN_BASELINE),
                        sub2Start, ss.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                btn.text = ss; btn.setLines(2); btn.textSize = 30f
                btn.setLineSpacing(0f, 0.9f)
            }

            btn.setOnClickListener {
                appendDigit(tag)
                haptic()
                toneGen?.startTone(DTMF_MAP[tag] ?: ToneGenerator.TONE_DTMF_0, 120)
            }

            if (tag == "0") {
                btn.setOnLongClickListener {
                    val cur = b.etNumber.text.toString()
                    val raw = dialableFilter(cur)
                    val newRaw = if (raw.endsWith("0")) raw.dropLast(1) + "+" else raw + "+"
                    b.etNumber.setText(formatVN(newRaw))
                    b.etNumber.setSelection(b.etNumber.text.length)
                    syncBackspace(); true
                }
            }
        }
    }

    private fun callCapableAccounts(): List<PhoneAccountHandle> {
        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_PHONE_STATE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()
        return try {
            val tm = requireContext().getSystemService(TelecomManager::class.java) ?: return emptyList()
            tm.callCapablePhoneAccounts ?: emptyList()
        } catch (_: SecurityException) { emptyList() }
    }

    private fun callWith(handle: PhoneAccountHandle?) {
        val raw = dialableFilter(b.etNumber.text.toString())
        if (raw.isNotEmpty()) {
            (activity as? MainActivity)?.placeCall(raw, handle)
        } else {
            val last = getLastCalledNumber()
            if (last != null) {
                b.etNumber.setText(formatVN(last))
                b.etNumber.setSelection(b.etNumber.text.length)
                syncBackspace()
            }
        }
    }

    // Trả về PhoneAccountHandle đang được đặt làm mặc định gọi đi (SIM chính), nếu người
    // dùng đã chọn cố định trong Cài đặt hệ thống. Trả null nếu để "Hỏi mỗi lần" hoặc lỗi quyền.
    private fun defaultOutgoingAccount(): PhoneAccountHandle? {
        return try {
            val tm = requireContext().getSystemService(TelecomManager::class.java) ?: return null
            tm.getDefaultOutgoingPhoneAccount(android.telecom.PhoneAccount.SCHEME_TEL)
        } catch (_: SecurityException) { null }
    }

    private fun setupCallButtons() {
        val accounts = callCapableAccounts()
        if (accounts.size >= 2) {
            b.btnCall.visibility = View.GONE
            b.llCallDual.visibility = View.VISIBLE
            b.btnCallSim1.setOnClickListener { callWith(accounts[0]) }
            b.btnCallSim2.setOnClickListener { callWith(accounts[1]) }

            // SIM chính (mặc định gọi) chiếm 60% diện tích nút gọi, SIM còn lại chiếm 40%.
            // Nếu không xác định được SIM mặc định (để "Hỏi mỗi lần"), giữ SIM 1 làm SIM chính.
            val defaultHandle = defaultOutgoingAccount()
            val sim1IsMain = defaultHandle == null || defaultHandle == accounts[0]

            val lp1 = b.btnCallSim1.layoutParams as LinearLayout.LayoutParams
            val lp2 = b.btnCallSim2.layoutParams as LinearLayout.LayoutParams
            lp1.weight = if (sim1IsMain) 3f else 2f
            lp2.weight = if (sim1IsMain) 2f else 3f
            b.btnCallSim1.layoutParams = lp1
            b.btnCallSim2.layoutParams = lp2
        } else {
            b.llCallDual.visibility = View.GONE
            b.btnCall.visibility = View.VISIBLE
            b.btnCall.setOnClickListener { callWith(accounts.firstOrNull()) }
        }
    }

    // Giữ lại chữ số, "+" và các ký hiệu dừng/chờ (","=2 giây dừng, ";"=chờ) khi lọc nội dung
    // ô nhập số. Dùng chung cho cả gõ phím lẫn thêm dấu dừng/chờ từ menu 3 chấm, để 2 luồng
    // nhập không xoá mất ký hiệu của nhau.
    private fun dialableFilter(s: String) = s.filter { it.isDigit() || it == '+' || it == ',' || it == ';' || it == '*' || it == '#' }

    private fun appendDigit(d: String) {
        val cur = b.etNumber.text.toString()
        val raw = dialableFilter(cur) + d
        b.etNumber.setText(formatVN(raw))
        b.etNumber.setSelection(b.etNumber.text.length)
        syncBackspace()
    }

    private fun formatVN(raw: String): String {
        if (raw.isEmpty()) return raw
        // Có dấu dừng/chờ hoặc * hoặc #: không áp dụng định dạng nhóm số VN, giữ nguyên.
        if (raw.contains(',') || raw.contains(';') || raw.contains('*') || raw.contains('#')) return raw
        val digits = raw.filter { it.isDigit() }
        return when {
            raw.startsWith("+") -> when {
                digits.length <= 2  -> "+$digits"
                digits.length <= 5  -> "+${digits.take(2)} ${digits.drop(2)}"
                digits.length <= 8  -> "+${digits.take(2)} ${digits.drop(2).take(3)} ${digits.drop(5)}"
                else -> "+${digits.take(2)} ${digits.drop(2).take(3)} ${digits.drop(5).take(3)} ${digits.drop(8)}"
            }
            digits.length <= 4  -> digits
            digits.length <= 7  -> "${digits.take(4)} ${digits.drop(4)}"
            else                -> "${digits.take(4)} ${digits.drop(4).take(3)} ${digits.drop(7)}"
        }
    }

    // Icon nút bật/tắt bàn phím phải phản ánh đúng trạng thái hiện tại: đang MỞ bàn phím thì
    // hiện mũi tên xuống (báo bấm để ẨN), đang ẨN thì hiện icon lưới chấm (báo bấm để MỞ).
    private fun updateKeypadToggleIcon() {
        _b?.btnKeypadToggle?.setImageResource(
            if (keypadVisible) R.drawable.ic_keyboard_hide else R.drawable.ic_dialpad
        )
    }

    // Ẩn/hiện lưới số VÀ hàng nút video/gọi/toggle CÙNG LÚC - trước đây chỉ ẩn mỗi lưới số nên
    // hàng nút gọi bị "chừa lại" một mình phía dưới. Khi ẩn, FAB bàn phím ở MainActivity (đặt
    // cạnh thanh tab Gần đây/Danh bạ) sẽ hiện lên thay thế, dùng để mở lại bàn phím.
    private fun setKeypadVisible(visible: Boolean) {
        keypadVisible = visible
        val panel = _b?.panelKeypad ?: run {
            updateKeypadToggleIcon()
            (activity as? MainActivity)?.setDialpadFabVisible(!visible)
            return
        }
        if (visible) {
            // Slide UP: ẩn FAB ngay, hiện panel rồi trượt từ dưới lên
            (activity as? MainActivity)?.setDialpadFabVisible(false)
            panel.visibility = View.VISIBLE
            panel.translationY = panel.height.toFloat().coerceAtLeast(300f)
            panel.animate()
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        } else {
            // Slide DOWN: trượt xuống, đợi xong hẳn mới hiện FAB
            // Nhanh hơn = tốc độ x1.2 so với trước: 220ms / 1.2 ≈ 183ms
            panel.animate()
                .translationY(panel.height.toFloat().coerceAtLeast(300f))
                .setDuration(183)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    panel.visibility = View.GONE
                    (activity as? MainActivity)?.setDialpadFabVisible(true)
                }
                .start()
        }
        updateKeypadToggleIcon()
    }

    /** Gọi từ MainActivity khi người dùng bấm FAB bàn phím lúc đang ở màn này với bàn phím đã ẩn. */
    fun showKeypad() = setKeypadVisible(true)
    fun hideKeypad() = setKeypadVisible(false)
    fun isKeypadVisible() = keypadVisible

    private fun syncBackspace() {
        val hasNumber = b.etNumber.text.isNotEmpty()
        _b?.btnBackspace?.visibility = if (hasNumber) View.VISIBLE else View.INVISIBLE
        _b?.btnDialMenu?.visibility = if (hasNumber) View.VISIBLE else View.INVISIBLE
        // Ẩn ô nhập số khi chưa gõ gì, hiện lên khi có số
        _b?.frameNumber?.visibility = if (hasNumber) View.VISIBLE else View.GONE
    }

    // Menu 3 chấm cạnh ô nhập số: chèn ký tự dừng (,) hoặc chờ (;) vào cuối số đang gõ,
    // giống hành vi bàn phím quay số chuẩn của Android khi gọi vào hệ thống IVR/tổng đài.
    private fun showDialMenu(anchor: View) {
        val popup = android.widget.PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, getString(R.string.add_2s_pause))
        popup.menu.add(0, 2, 1, getString(R.string.add_wait))
        popup.setOnMenuItemClickListener { item ->
            val symbol = when (item.itemId) { 1 -> ","; 2 -> ";"; else -> "" }
            if (symbol.isNotEmpty()) {
                val raw = dialableFilter(b.etNumber.text.toString()) + symbol
                b.etNumber.setText(formatVN(raw))
                b.etNumber.setSelection(b.etNumber.text.length)
                syncBackspace()
            }
            true
        }
        popup.show()
    }

    private fun loadRecents() {
        // Bảo vệ: hàm này đụng requireContext()/b.* (view binding) - nếu bị gọi đúng lúc
        // fragment không còn sẵn sàng (view đã huỷ, chưa attach) sẽ crash toàn app.
        if (_b == null || !isAdded) return
        try {
            if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_CALL_LOG)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                b.rvRecents.visibility = View.GONE
                return
            }
            val isDualSim = callCapableAccounts().size >= 2

            // Hiện cache ngay nếu đã có (từ lần mở trước, cùng phiên app) → không chờ, không giật/trắng
            var hasSomethingShown = false
            if (recentsCacheLoaded && cachedRecents.isNotEmpty()) {
                allRecentEntries = cachedRecents
                renderRecents(isDualSim)
                hasSomethingShown = true
            } else {
                // Cache RAM rỗng (app vừa mở lại từ đầu / bị hệ thống kill hẳn) → đọc cache ĐĨA
                // (còn nguyên qua mọi lần mở app, khác cache RAM) để hiện NGAY lịch sử cũ trong
                // lúc chờ đồng bộ lại thật ở nền, thay vì màn hình trắng.
                val diskCached = com.h.simplecall.data.CallLogCache.load(requireContext().applicationContext)
                if (!diskCached.isNullOrEmpty() && _b != null && isAdded) {
                    allRecentEntries = diskCached
                    renderRecents(isDualSim)
                    hasSomethingShown = true
                }
            }

            val appContext = requireContext().applicationContext
            bgExecutor.execute {
                val entries = if (!hasSomethingShown) {
                    // LẦN ĐẦU THẬT SỰ (không có cache RAM lẫn đĩa nào cả) → hiện danh sách LỚN
                    // DẦN từ trên xuống theo từng đợt 40 dòng ngay trong lúc đang đọc, thay vì
                    // giữ màn hình trắng tới khi đọc xong toàn bộ mới hiện 1 lần.
                    queryRecents(appContext) { partial ->
                        mainHandler.post {
                            if (_b == null || !isAdded) return@post
                            allRecentEntries = partial
                            renderRecents(isDualSim)
                        }
                    }
                } else {
                    // Đã có gì đó hiện sẵn (cache) rồi - chỉ cần đồng bộ lại thật ở nền, không cần
                    // hiện dần (tránh danh sách đang hiện bị "giật" lùi về rồi lớn dần lại).
                    queryRecents(appContext)
                }
                com.h.simplecall.data.CallLogCache.save(appContext, entries) // ghi đè cache đĩa với bản mới nhất
                mainHandler.post {
                    if (_b == null || !isAdded) return@post // fragment đã bị huỷ trong lúc chờ
                    cachedRecents = entries
                    recentsCacheLoaded = true
                    allRecentEntries = entries
                    renderRecents(isDualSim)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("DialerFragment", "loadRecents() lỗi bất ngờ, bỏ qua an toàn", e)
        }
    }

    /** Áp bộ lọc tab (Tất cả/Cuộc gọi nhỡ) đang chọn lên allRecentEntries rồi bơm vào adapter.
     *  Dùng chung cho lần tải đầu tiên VÀ mỗi khi người dùng đổi tab.
     *
     *  LỖI ĐÃ SỬA: hàm này được gọi TỪ LUỒNG NỀN (sau khi queryRecents() ở loadRecents() chạy
     *  xong, có thể mất vài trăm ms) - nếu trong lúc chờ đó người dùng đã DÁN/gõ 1 số vào
     *  etNumber (khiến searchSuggestions() ẩn rvRecents đi để tập trung vào số đang nhập), kết
     *  quả trả về trễ ở đây sẽ VÔ TÌNH bật lại rvRecents (vì entries không rỗng), khiến danh
     *  sách "Gần đây" bất ngờ đè lên ngay phía trên số vừa dán/gõ - trông như app "quên" là
     *  đang nhập số / vừa check số vừa hiện lịch sử chồng lên nhau. Phải luôn kiểm tra ô nhập
     *  số HIỆN TẠI có đang rỗng không trước khi quyết định hiện rvRecents, chứ không chỉ dựa
     *  vào entries.isEmpty(). */
    private fun renderRecents(isDualSim: Boolean) {
        val entries = if (showMissedOnly)
            allRecentEntries.filter { it.type == CallLog.Calls.MISSED_TYPE }
        else allRecentEntries
        val isEnteringNumber = b.etNumber.text.isNotEmpty()
        b.rvRecents.visibility = if (isEnteringNumber || entries.isEmpty()) View.GONE else View.VISIBLE
        val onCall: (String) -> Unit = { (activity as? MainActivity)?.placeCall(it) }
        val onShowHistory: (String) -> Unit = { number ->
            val entry = entries.firstOrNull { it.number == number }
            val name = entry?.name ?: number
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, CallHistoryFragment.newInstance(number, name))
                .addToBackStack("history")
                .commit()
            (activity as? MainActivity)?.hideNav()
        }
        // TRƯỚC ĐÂY: mỗi lần renderRecents() chạy (vd. loadRecents() đồng bộ lại ở nền sau khi có
        // cuộc gọi mới, hoặc đổi tab Tất cả/Nhỡ) đều tạo CallLogAdapter MỚI rồi gán thẳng vào
        // b.rvRecents.adapter. Gọi RecyclerView.setAdapter() trong lúc ngón tay người dùng ĐANG
        // CHẠM vào 1 dòng sẽ khiến framework HUỶ NGANG (cancel) cử chỉ chạm đang diễn ra - dòng đó
        // "giật" một cái như đang vuốt/trượt nhưng onClick không bao giờ được gọi - đúng lỗi
        // "bấm vào số để gọi ở Gần đây bị giật, không gọi". Giờ chỉ tạo adapter 1 LẦN DUY NHẤT,
        // các lần sau tái sử dụng adapter cũ qua updateItems() (đã có sẵn DiffUtil, không tạo
        // view holder mới, không huỷ cử chỉ chạm đang diễn ra).
        val existing = recentsAdapter
        if (existing == null) {
            val adapter = CallLogAdapter(entries, isDualSim = isDualSim, onCall = onCall, onShowHistory = onShowHistory)
            recentsAdapter = adapter
            b.rvRecents.adapter = adapter
        } else {
            existing.updateItems(entries)
        }
    }

    private fun selectTab(missed: Boolean) {
        showMissedOnly = missed
        val accent = ContextCompat.getColor(requireContext(), R.color.accent_blue)
        val bright = ContextCompat.getColor(requireContext(), R.color.text_primary)
        val secondary = ContextCompat.getColor(requireContext(), R.color.text_secondary)
        val transparent = ContextCompat.getColor(requireContext(), android.R.color.transparent)
        b.tvTabAll.setTextColor(if (missed) secondary else bright)
        b.tvTabAll.setTypeface(null, if (missed) android.graphics.Typeface.NORMAL else android.graphics.Typeface.BOLD)
        b.tvTabMissed.setTextColor(if (missed) bright else secondary)
        b.tvTabMissed.setTypeface(null, if (missed) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        b.tabAllUnderline.setBackgroundColor(if (missed) transparent else accent)
        b.tabMissedUnderline.setBackgroundColor(if (missed) accent else transparent)
        renderRecents(callCapableAccounts().size >= 2)
    }

    /** Chỉ hiển thị gợi ý "gần đây" trong màn hình quay số nên KHÔNG cần tải toàn bộ lịch sử
     *  (có máy hàng nghìn cuộc gọi) — giới hạn 50 dòng mới nhất là đủ và tránh lag khi mở màn.
     *  Đọc từ DB nội bộ của app (Room) - số của mỗi dòng LÀ số đã hiển thị trên màn hình gọi
     *  tại thời điểm gọi, không phải số CallLog hệ thống tự ghi. */
    /** Đọc thẳng lịch sử cuộc gọi từ CallLog hệ thống — không dùng Room DB nội bộ nữa.
     *  Kết quả khớp 100% với ứng dụng điện thoại gốc vì cùng nguồn dữ liệu. */
    private fun queryRecents(ctx: Context, onBatch: ((List<CallLogEntry>) -> Unit)? = null): List<CallLogEntry> {
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
        // TRƯỚC ĐÂY: "... DATE DESC LIMIT 50" - chỉ đọc đúng 50 cuộc gọi gần nhất, cắt cụt lịch
        // sử nếu máy có nhiều hơn 50 cuộc gọi. Bỏ LIMIT để đọc TOÀN BỘ lịch sử đang có trong
        // CallLog hệ thống, khớp đúng với app điện thoại gốc (cùng nguồn dữ liệu).
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
            // Gọi lại onBatch mỗi 40 dòng đọc được (nếu có truyền) để danh sách hiện LỚN DẦN từ
            // trên xuống ngay trong lúc đang đọc, thay vì đợi đọc hết mới hiện 1 lần - áp dụng
            // cho lần mở app ĐẦU TIÊN (chưa có cache nào) khi việc đọc mất thời gian đáng kể.
            var sinceLastBatch = 0
            while (cursor.moveToNext()) {
                val number   = cursor.getString(iNum) ?: continue
                var name     = cursor.getString(iName) ?: ""
                val type     = cursor.getInt(iType)
                val date     = cursor.getLong(iDate)
                val duration = cursor.getLong(iDuration)
                val simId    = cursor.getString(iSim)
                // CallLog không phải lúc nào cũng tự điền CACHED_NAME cho cuộc gọi ĐI tới số đã
                // lưu (khác cuộc gọi ĐẾN, luôn được hệ thống tự tra caller ID) -> tự tra bù qua
                // PhoneLookup để không hiện trơ số khi số đó đã có trong danh bạ.
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
                sinceLastBatch++
                if (onBatch != null && sinceLastBatch >= 40) {
                    sinceLastBatch = 0
                    onBatch(entries.toList())
                }
            }
        }
        return entries
    }

    private fun searchSuggestions(raw: String) {
        // Header "Gần đây" (tiêu đề + tab) LUÔN nằm cố định trên cùng, KHÔNG bị bàn phím che -
        // chỉ ẩn hẳn khi người dùng bắt đầu gõ số, nhường chỗ cho "Tất cả liên hệ" bên dưới.
        b.llDialerHeader.visibility = if (raw.isEmpty()) View.VISIBLE else View.GONE
        if (raw.length < 1) {
            searchGeneration++
            b.llSuggestionsWrap.visibility = View.GONE
            b.llNoMatchActions.visibility = View.GONE
            b.rvRecents.visibility = if ((b.rvRecents.adapter?.itemCount ?: 0) > 0) View.VISIBLE else View.GONE
            return
        }
        b.rvRecents.visibility = View.GONE
        val myGeneration = ++searchGeneration
        val appContext = requireContext().applicationContext
        // Huỷ truy vấn đang chờ (nếu có) của trạng thái trung gian trước đó - chỉ trạng thái ổn
        // định cuối cùng (sau khi dán/gõ xong 150ms không đổi thêm) mới thực sự chạy truy vấn.
        pendingSearchRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            bgExecutor.execute {
                val list = queryContactSuggestions(appContext, raw)
                mainHandler.post {
                    // Người dùng đã gõ thêm/xoá ký tự khác trong lúc chờ: bỏ qua kết quả trễ này
                    if (_b == null || myGeneration != searchGeneration) return@post
                    if (list.isEmpty()) {
                        b.llSuggestionsWrap.visibility = View.GONE
                        b.llNoMatchActions.visibility = View.VISIBLE
                    } else {
                        suggestAdapter.update(list, raw)
                        b.llSuggestionsWrap.visibility = View.VISIBLE
                        b.llNoMatchActions.visibility = View.GONE
                    }
                }
            }
        }
        pendingSearchRunnable = runnable
        mainHandler.postDelayed(runnable, 150)
    }

    private fun queryContactSuggestions(ctx: Context, raw: String): List<Contact> {
        // Danh bạ đã được ĐỒNG BỘ & LƯU SẴN trong bộ nhớ qua ContactsRepository (nạp 1 lần khi
        // người dùng từng mở tab Danh bạ, giữ cache suốt phiên chạy app - xem ContactsRepository).
        // Nếu cache này đã có, so khớp NGAY trên List đã có sẵn trong RAM - nhanh gần như tức
        // thời (micro giây) bất kể danh bạ bao nhiêu số, vì không cần gọi sang tiến trình
        // ContactsProvider của hệ thống nữa (mỗi lần gọi sang đó luôn tốn phí IPC, và nếu phải
        // quét toàn bảng như cách làm CŨ thì tốn tới VÀI GIÂY với danh bạ lớn - đúng lỗi "dán 10
        // số vào check còn chậm hơn bấm từng số", vì bấm từng số còn có PhoneLookup đỡ 1 phần,
        // còn rơi vào trường hợp PhoneLookup không khớp thì lại tụt về quét toàn bảng rất chậm).
        val cached = com.h.simplecall.data.ContactsRepository.peek()
        if (cached != null) {
            val digitsRaw = raw.filter { it.isDigit() }
            if (digitsRaw.isEmpty()) return emptyList()
            return cached.asSequence()
                .filter { it.number.isNotBlank() && it.number.filter { d -> d.isDigit() }.contains(digitsRaw) }
                .distinctBy { it.name to it.number }
                .toList()
        }
        // Cache CHƯA có (người dùng chưa từng mở tab Danh bạ trong phiên chạy này) - tra trực
        // tiếp bằng PhoneLookup, bảng tra cứu số điện thoại RIÊNG có index sẵn của hệ thống, KHÔNG
        // quét toàn bộ bảng danh bạ như "NUMBER LIKE '%...%'" cách làm cũ.
        val list = mutableListOf<Contact>()
        try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(raw))
            ctx.contentResolver.query(uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.NUMBER),
                null, null, null)?.use { cur ->
                val iName = cur.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                val iNum  = cur.getColumnIndex(ContactsContract.PhoneLookup.NUMBER)
                while (cur.moveToNext()) {
                    list.add(Contact(cur.getString(iName) ?: "", cur.getString(iNum) ?: raw))
                }
            }
        } catch (_: Exception) {}
        return list
    }

    /** allRecentEntries đã được tải sẵn ở nền (bgExecutor) và sắp theo DATE DESC, nên số gọi
     *  gần nhất chính là phần tử đầu tiên - không cần query Room lần nữa trên main thread. */
    private fun getLastCalledNumber(): String? = allRecentEntries.firstOrNull()?.number

    private fun haptic() {
        val v = requireContext().getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            v.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") v.vibrate(25)
    }

    // Khi mở màn hình này lần đầu, quyền READ_PHONE_STATE có thể CHƯA được cấp (hộp thoại xin
    // quyền của MainActivity chạy bất đồng bộ). Nếu không làm mới lại ở đây, nút gọi sẽ bị kẹt
    // vĩnh viễn ở chế độ 1 SIM ngay cả sau khi người dùng đã cấp quyền / cắm thêm SIM.
    private var hasResumedOnce = false

    override fun onResume() {
        super.onResume()
        // Bảo vệ: onResume() có thể chạy vào đúng lúc FragmentManager đang xử lý back stack
        // (fragment chưa/không còn "sẵn sàng" - view đã bị huỷ hoặc chưa attach xong) -> đụng
        // vào b.* (view binding) hay requireContext() lúc này sẽ crash TOÀN BỘ app. Luôn kiểm
        // tra _b != null và isAdded trước khi làm bất cứ gì.
        if (_b == null || !isAdded) return

        // Xóa số đã gõ sau khi gọi xong → về màn hình chưa bấm số, bàn phím vẫn HIỆN sẵn
        // (không ẩn đi) với ô số trống.
        if (hasResumedOnce) {
            b.etNumber.setText("")
            syncBackspace()
        }
        if (b.searchBarDialer.visibility == View.VISIBLE) {
            b.searchBarDialer.visibility = View.GONE
            b.llDialerTitleTabs.visibility = View.VISIBLE
            b.etSearchDialer.setText("")
        }
        setKeypadVisible(true)
        setupCallButtons()
        if (hasResumedOnce) loadRecents()
        hasResumedOnce = true

        // Đăng ký lắng nghe thay đổi CallLog để tự refresh khi hệ thống ghi xong cuộc gọi mới.
        // Đăng ký trong onResume / huỷ trong onPause để không tiêu tài nguyên khi tab không hiện.
        if (callLogObserver == null) {
            callLogObserver = object : android.database.ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    if (_b == null || !isAdded) return
                    loadRecents()
                }
            }
            requireContext().contentResolver.registerContentObserver(
                android.provider.CallLog.Calls.CONTENT_URI,
                true,
                callLogObserver!!
            )
        }
    }

    override fun onPause() {
        super.onPause()
        callLogObserver?.let {
            runCatching { requireContext().contentResolver.unregisterContentObserver(it) }
        }
        callLogObserver = null
    }

    override fun onDestroyView() {
        toneGen?.release(); toneGen = null
        pendingSearchRunnable?.let { mainHandler.removeCallbacks(it) }
        bgExecutor.shutdownNow()
        super.onDestroyView(); _b = null
    }
}
