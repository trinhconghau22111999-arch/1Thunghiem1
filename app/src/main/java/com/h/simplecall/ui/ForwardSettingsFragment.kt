package com.h.simplecall.ui

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.h.simplecall.DefaultDialerStatusListener
import com.h.simplecall.MainActivity
import com.h.simplecall.R
import com.h.simplecall.call.CallForwardManager

class ForwardSettingsFragment : Fragment(), DefaultDialerStatusListener {

    private var tvDefaultDialerStatus: TextView? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_forward_settings, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnBack   = view.findViewById<ImageButton>(R.id.btnBack)
        val switch    = view.findViewById<SwitchMaterial>(R.id.switchForward)
        val etTarget  = view.findViewById<EditText>(R.id.etTargetNumber)
        val btnOk     = view.findViewById<MaterialButton>(R.id.btnOk)
        val tvStatus  = view.findViewById<TextView>(R.id.tvForwardStatus)
        val tvIcon    = view.findViewById<TextView>(R.id.tvStatusIcon)
        val card      = view.findViewById<View>(R.id.cardStatus)

        tvDefaultDialerStatus = view.findViewById(R.id.tvDefaultDialerStatus)
        val btnSetDefaultDialer = view.findViewById<MaterialButton>(R.id.btnSetDefaultDialer)
        refreshDefaultDialerStatus()
        btnSetDefaultDialer.setOnClickListener {
            (activity as? MainActivity)?.requestDefaultDialer()
        }

        switch.isChecked = CallForwardManager.isEnabled
        etTarget.setText(CallForwardManager.targetNumber)
        btnOk.isEnabled = CallForwardManager.targetNumber.length == 10
        refreshStatus(tvStatus, tvIcon, card, CallForwardManager.isEnabled, CallForwardManager.targetNumber)

        btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        etTarget.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { btnOk.isEnabled = s?.length == 10 }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        btnOk.setOnClickListener {
            val number = etTarget.text.toString().trim()
            CallForwardManager.targetNumber = number
            CallForwardManager.isEnabled = true
            switch.isChecked = true
            refreshStatus(tvStatus, tvIcon, card, true, number)
            Toast.makeText(requireContext(), "Đã lưu số đích", Toast.LENGTH_SHORT).show()
            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(etTarget.windowToken, 0)
        }

        switch.setOnCheckedChangeListener { _, on ->
            if (on && CallForwardManager.targetNumber.length != 10) {
                switch.isChecked = false
                Toast.makeText(requireContext(), "Hãy nhập và lưu số đích trước", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            CallForwardManager.isEnabled = on
            refreshStatus(tvStatus, tvIcon, card, on, CallForwardManager.targetNumber)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDefaultDialerStatus() // phòng khi người dùng quay lại từ hộp thoại hệ thống
    }

    override fun onDefaultDialerStatusChanged() = refreshDefaultDialerStatus()

    private fun refreshDefaultDialerStatus() {
        val isDefault = (activity as? MainActivity)?.isDefaultDialer() ?: false
        tvDefaultDialerStatus?.text =
            if (isDefault) "Đã là ứng dụng gọi mặc định ✓" else "Chưa đặt làm mặc định"
    }

    private fun refreshStatus(tv: TextView, tvIcon: TextView, card: View, on: Boolean, t: String) {
        if (on && t.length == 10) {
            tv.text = "Đang chuyển → $t"
            tvIcon.setTextColor(requireContext().getColor(R.color.status_on))
            card.setBackgroundResource(R.drawable.bg_status_pill)
        } else {
            tv.text = "Chuyển hướng đang tắt"
            tvIcon.setTextColor(requireContext().getColor(R.color.status_off))
            card.setBackgroundResource(R.drawable.bg_status_pill_off)
        }
    }
}
