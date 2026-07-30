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
        b.rowAutoRecord.setOnClickListener { showRecorderAppPicker() }
        b.switchAutoRecord.setOnClickListener { showRecorderAppPicker() }
    }

    private fun showRecorderAppPicker() {
        showAppPickerDialog(useAllApps = false)
    }

    private fun showAppPickerDialog(useAllApps: Boolean) {
        val ctx = requireContext()
        val pm = ctx.packageManager
        val apps = if (useAllApps) CallRecordingManager.findAllApps(ctx)
                   else CallRecordingManager.findRecorderApps(ctx)
        val currentPkg = CallRecordingManager.getThirdPartyRecorderPackage(ctx)

        if (!useAllApps && apps.isEmpty()) {
            // Không tìm thấy app nào qua filter → hỏi có muốn chọn thủ công không
            android.app.AlertDialog.Builder(ctx)
                .setTitle("Chọn app ghi âm")
                .setMessage("Không tìm thấy app ghi âm nào trên máy.\n\nBạn có thể chọn thủ công từ danh sách tất cả app, hoặc cài thêm app ghi âm (ACR, Easy Voice Recorder…) từ CH Play.")
                .setPositiveButton("Chọn từ tất cả app") { _, _ -> showAppPickerDialog(useAllApps = true) }
                .setNeutralButton("Mở CH Play") { _, _ ->
                    try {
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("market://search?q=call+recorder&c=apps")))
                    } catch (_: Exception) {
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://play.google.com/store/search?q=call+recorder&c=apps")))
                    }
                }
                .setNegativeButton("Huỷ", null)
                .show()
            return
        }

        // Tạo danh sách: "Tắt" + các app tìm được
        val labels = mutableListOf<CharSequence>("Tắt (không ghi âm tự động)")
        apps.forEach { labels.add(it.loadLabel(pm)) }

        val checkedItem = if (currentPkg == null) 0
        else apps.indexOfFirst { it.activityInfo.packageName == currentPkg }.let {
            if (it < 0) 0 else it + 1
        }

        val title = if (useAllApps) "Chọn app (tất cả)" else "Chọn app ghi âm"

        android.app.AlertDialog.Builder(ctx)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checkedItem) { dialog, which ->
                if (which == 0) {
                    CallRecordingManager.setThirdPartyRecorderPackage(ctx, null)
                } else {
                    val pkg = apps[which - 1].activityInfo.packageName
                    CallRecordingManager.setThirdPartyRecorderPackage(ctx, pkg)
                }
                refreshAutoRecordUi()
                dialog.dismiss()
            }
            .setNeutralButton(if (useAllApps) null else "Tất cả app") { _, _ ->
                showAppPickerDialog(useAllApps = true)
            }
            .setNegativeButton("Huỷ", null)
            .show()
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
        val pkg = CallRecordingManager.getThirdPartyRecorderPackage(ctx)
        b.switchAutoRecord.isChecked = pkg != null
        b.tvAutoRecordSubtitle.text = if (pkg != null) {
            try {
                val appName = ctx.packageManager.getApplicationLabel(
                    ctx.packageManager.getApplicationInfo(pkg, 0)
                )
                "Đang dùng: $appName"
            } catch (_: Exception) { "Đang bật (app không xác định)" }
        } else {
            "Tắt (mặc định)"
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
