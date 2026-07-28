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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.h.simplecall.MainActivity
import com.h.simplecall.R
import com.h.simplecall.data.CallLogEntry
import com.h.simplecall.data.Contact
import com.h.simplecall.call.CallHistoryManager
import com.h.simplecall.data.local.AppDatabase
import com.h.simplecall.data.local.toCallLogEntry
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
    private var keypadVisible = true
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

        b.btnDialerSettings.setOnClickListener { (activity as? MainActivity)?.openSettings() }
        b.btnDialerSearch.setOnClickListener {
            android.widget.Toast.makeText(requireContext(), "Tìm kiếm đang được phát triển", android.widget.Toast.LENGTH_SHORT).show()
        }
        b.tabAll.setOnClickListener { selectTab(missed = false) }
        b.tabMissed.setOnClickListener { selectTab(missed = true) }

        arguments?.getString("number")?.let { b.etNumber.setText(it) }

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

        setupCallButtons()

        // Nút video dùng FrameLayout có id btnVideoCall
        view.findViewById<View>(R.id.btnVideoCall)?.setOnClickListener {
            android.widget.Toast.makeText(requireContext(),
                getString(R.string.video_call_unsupported), android.widget.Toast.LENGTH_SHORT).show()
        }

        b.rowCreateContact.setOnClickListener {
            val raw = b.etNumber.text.toString().filter { it.isDigit() || it == '+' }
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

    private fun setupCallButtons() {
        val accounts = callCapableAccounts()
        if (accounts.size >= 2) {
            b.btnCall.visibility = View.GONE
            b.llCallDual.visibility = View.VISIBLE
            b.btnCallSim1.setOnClickListener { callWith(accounts[0]) }
            b.btnCallSim2.setOnClickListener { callWith(accounts[1]) }
        } else {
            b.llCallDual.visibility = View.GONE
            b.btnCall.visibility = View.VISIBLE
            b.btnCall.setOnClickListener { callWith(accounts.firstOrNull()) }
        }
    }

    // Giữ lại chữ số, "+" và các ký hiệu dừng/chờ (","=2 giây dừng, ";"=chờ) khi lọc nội dung
    // ô nhập số. Dùng chung cho cả gõ phím lẫn thêm dấu dừng/chờ từ menu 3 chấm, để 2 luồng
    // nhập không xoá mất ký hiệu của nhau.
    private fun dialableFilter(s: String) = s.filter { it.isDigit() || it == '+' || it == ',' || it == ';' }

    private fun appendDigit(d: String) {
        val cur = b.etNumber.text.toString()
        val raw = dialableFilter(cur) + d
        b.etNumber.setText(formatVN(raw))
        b.etNumber.setSelection(b.etNumber.text.length)
        syncBackspace()
    }

    private fun formatVN(raw: String): String {
        if (raw.isEmpty()) return raw
        // Có dấu dừng/chờ: không áp dụng định dạng nhóm số VN, giữ nguyên chuỗi người dùng gõ.
        if (raw.contains(',') || raw.contains(';')) return raw
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
            if (recentsCacheLoaded && cachedRecents.isNotEmpty()) {
                allRecentEntries = cachedRecents
                renderRecents(isDualSim)
            }

            val appContext = requireContext().applicationContext
            bgExecutor.execute {
                val entries = queryRecents(appContext)
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
     *  Dùng chung cho lần tải đầu tiên VÀ mỗi khi người dùng đổi tab. */
    private fun renderRecents(isDualSim: Boolean) {
        val entries = if (showMissedOnly)
            allRecentEntries.filter { it.type == CallLog.Calls.MISSED_TYPE }
        else allRecentEntries
        b.rvRecents.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        b.rvRecents.adapter = CallLogAdapter(
            entries,
            isDualSim = isDualSim,
            onCall = { (activity as? MainActivity)?.placeCall(it) },
            onShowHistory = { number ->
                val entry = entries.firstOrNull { it.number == number }
                val name = entry?.name ?: number
                requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, CallHistoryFragment.newInstance(number, name))
                    .addToBackStack("history")
                    .commit()
                (activity as? MainActivity)?.hideNav()
            }
        )
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
    private fun queryRecents(ctx: Context): List<CallLogEntry> {
        CallHistoryManager.awaitReady() // đảm bảo di trú lịch sử cũ (nếu có) đã chạy xong
        return try {
            AppDatabase.getInstance(ctx).callHistoryDao().getRecent(50).map { it.toCallLogEntry() }
        } catch (e: Exception) {
            android.util.Log.e("DialerFragment", "Đọc lịch sử gần đây thất bại", e)
            emptyList()
        }
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

    private fun queryContactSuggestions(ctx: Context, raw: String): List<Contact> {
        val list = mutableListOf<Contact>()
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val cur: Cursor? = ctx.contentResolver.query(uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?",
                arrayOf("%$raw%"), null)
            cur?.use {
                val iName = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val iNum  = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    list.add(Contact(it.getString(iName) ?: "", it.getString(iNum) ?: ""))
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
        setKeypadVisible(true)
        setupCallButtons()
        if (hasResumedOnce) loadRecents()
        hasResumedOnce = true
    }

    override fun onDestroyView() {
        toneGen?.release(); toneGen = null
        bgExecutor.shutdownNow()
        super.onDestroyView(); _b = null
    }
}
