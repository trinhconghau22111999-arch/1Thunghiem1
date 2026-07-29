package com.h.simplecall.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.h.simplecall.MainActivity
import com.h.simplecall.R
import com.h.simplecall.ThemePrefs
import com.h.simplecall.call.CallRecordingManager
import com.h.simplecall.databinding.FragmentSettingsBinding

/** Màn Cài đặt - giống mục cài đặt của app điện thoại gốc trên máy. Có mục "Giao diện" cho phép
 *  chọn Sáng/Tối (xem ThemePrefs.kt), và mục "Ghi âm cuộc gọi" bật/tắt tự động ghi âm mỗi cuộc
 *  gọi (xem CallRecordingManager.kt, CallStateReceiver.kt, CallRecordingService.kt). */
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
        val toggleAutoRecord = {
            val turningOn = !CallRecordingManager.isEnabled(requireContext())
            if (turningOn && ContextCompat.checkSelfPermission(requireContext(),
                    android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // Quyền micro chưa được cấp (bị từ chối trước đó) - không bật ngầm được, dẫn
                // người dùng quay lại xin quyền từ màn chính thay vì im lặng bật 1 tính năng
                // sẽ không hoạt động.
                Toast.makeText(requireContext(),
                    "Cần cấp quyền Micro để ghi âm cuộc gọi - vào Cài đặt hệ thống > Ứng dụng để cấp",
                    Toast.LENGTH_LONG).show()
            } else {
                CallRecordingManager.setEnabled(requireContext(), turningOn)
                refreshAutoRecordUi()
            }
        }
        b.rowAutoRecord.setOnClickListener { toggleAutoRecord() }
        b.switchAutoRecord.setOnClickListener { toggleAutoRecord() }
    }

    private fun refreshDarkModeUi() {
        if (_b == null || !isAdded) return
        val dark = ThemePrefs.isDarkMode(requireContext())
        b.switchDarkMode.isChecked = dark
        b.tvDarkModeSubtitle.text = if (dark) "Đang bật" else "Đang tắt (mặc định)"
    }

    private fun refreshAutoRecordUi() {
        if (_b == null || !isAdded) return
        val enabled = CallRecordingManager.isEnabled(requireContext())
        b.switchAutoRecord.isChecked = enabled
        b.tvAutoRecordSubtitle.text = if (enabled) "Đang bật" else "Đang tắt (mặc định)"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    companion object {
        fun newInstance() = SettingsFragment()
    }
}
