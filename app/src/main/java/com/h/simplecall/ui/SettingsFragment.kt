package com.h.simplecall.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.h.simplecall.MainActivity
import com.h.simplecall.R
import com.h.simplecall.ThemePrefs
import com.h.simplecall.call.CallRecordingManager
import com.h.simplecall.databinding.FragmentSettingsBinding

/** Màn Cài đặt - giống mục cài đặt của app điện thoại gốc trên máy. Có mục "Giao diện" cho phép
 *  chọn Sáng/Tối (xem ThemePrefs.kt), và mục "Ghi âm cuộc gọi" bật/tắt tự động ra lệnh cho VOX
 *  Ghi Âm ghi âm NỀN mỗi khi có cuộc gọi (xem CallRecordingManager.kt, CallStateReceiver.kt) -
 *  đây là app ghi âm DUY NHẤT được dùng, không còn cho chọn app khác hay ghi âm nội bộ nữa. */
class SettingsFragment : Fragment() {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentSettingsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? MainActivity)?.hideNav()

        b.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        refreshDarkModeUi()
        val toggle = {
            val newValue = !ThemePrefs.isDarkMode(requireContext())
            ThemePrefs.setDarkMode(requireContext(), newValue)
            // setDefaultNightMode() tự động recreate() Activity để đổi giao diện ngay lập tức -
            // không cần code gì thêm ở đây, màn này sẽ tự vẽ lại theo bộ màu mới.
        }
        b.rowDarkMode.setOnClickListener { toggle() }
        b.switchDarkMode.setOnClickListener { toggle() }

        refreshAutoRecordUi()
        b.rowAutoRecord.setOnClickListener { toggleAutoRecord() }
        b.switchAutoRecord.setOnClickListener { toggleAutoRecord() }
    }

    /** Bật/tắt "tự động mở VOX Ghi Âm khi có cuộc gọi" - KHÔNG còn cho chọn app khác, chỉ 1
     *  công tắc bật/tắt duy nhất cho đúng 1 app cố định ([CallRecordingManager.RECORDER_PACKAGE]). */
    private fun toggleAutoRecord() {
        val ctx = requireContext()
        if (!CallRecordingManager.isEnabled(ctx) && !CallRecordingManager.isRecorderAppInstalled(ctx)) {
            android.app.AlertDialog.Builder(ctx)
                .setTitle(CallRecordingManager.RECORDER_APP_NAME)
                .setMessage("Chưa cài ứng dụng ${CallRecordingManager.RECORDER_APP_NAME} trên máy này. Cài đặt xong hãy quay lại bật tính năng này.")
                .setPositiveButton("Đã hiểu", null)
                .show()
            return
        }
        CallRecordingManager.setEnabled(ctx, !CallRecordingManager.isEnabled(ctx))
        refreshAutoRecordUi()
    }

    private fun refreshDarkModeUi() {
        if (_b == null || !isAdded) return
        val dark = ThemePrefs.isDarkMode(requireContext())
        b.switchDarkMode.isChecked = dark
        b.tvDarkModeSubtitle.text = if (dark) "Đang bật" else "Đang tắt (mặc định)"
    }

    private fun refreshAutoRecordUi() {
        if (_b == null || !isAdded) return
        val ctx = requireContext()
        val enabled = CallRecordingManager.isEnabled(ctx)
        b.switchAutoRecord.isChecked = enabled
        b.tvAutoRecordSubtitle.text = if (enabled) {
            "Đang dùng: ${CallRecordingManager.RECORDER_APP_NAME}"
        } else {
            "Tắt (mặc định) - dùng ${CallRecordingManager.RECORDER_APP_NAME}"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    companion object {
        fun newInstance() = SettingsFragment()
    }
}
