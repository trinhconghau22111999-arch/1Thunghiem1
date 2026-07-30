package com.h.simplecall.ui

import android.app.AlertDialog
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.h.simplecall.R
import com.h.simplecall.call.CallRecording
import com.h.simplecall.call.CallRecordingManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Hiện danh sách bản ghi âm cuộc gọi của 1 số điện thoại (dùng chung cho hàng "Bản ghi âm cuộc
 * gọi" ở cả ContactDetailFragment và CallHistoryFragment), cho phép Phát / Chia sẻ / Xóa.
 * Đơn giản dùng AlertDialog thay vì màn hình riêng vì số lượng bản ghi cho 1 số thường ít.
 */
object CallRecordingListDialog {

    private var player: MediaPlayer? = null
    private var progressDialog: AlertDialog? = null
    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    fun show(fragment: Fragment, number: String) {
        val ctx = fragment.requireContext()
        val recordings = CallRecordingManager.getForNumber(ctx, number)
        if (recordings.isEmpty()) {
            Toast.makeText(ctx, R.string.no_recordings_for_number, Toast.LENGTH_SHORT).show()
            return
        }
        val fmt = SimpleDateFormat("d/M/yyyy HH:mm", Locale.getDefault())
        val labels = recordings.map { rec ->
            // File do app ghi âm bên thứ 3 tạo đôi khi có durationSeconds = 0 (MediaStore chưa
            // đọc được lúc lập chỉ mục) - khi đó đọc lại thời lượng thật từ chính file bằng
            // MediaMetadataRetriever thay vì hiện "0:00".
            val durationSec = if (rec.durationSeconds > 0) rec.durationSeconds
                else CallRecordingManager.readDurationSeconds(rec.filePath)
            "${fmt.format(rec.startTimeMillis)}  •  ${formatDuration(durationSec)}"
        }.toTypedArray()

        AlertDialog.Builder(ctx)
            .setTitle(R.string.recording_list_title)
            .setItems(labels) { _, index -> showActions(fragment, recordings[index]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Mở đúng bản ghi âm của 1 dòng cụ thể trong "Nhật ký cuộc gọi" (không phải cả danh sách
     *  theo số) - dùng khi bấm vào 1 dòng lịch sử cuộc gọi, thay vì gọi lại số đó. */
    fun showForCallLogEntry(fragment: Fragment, number: String, callDateMillis: Long) {
        val ctx = fragment.requireContext()
        val recording = CallRecordingManager.getForCallLogEntry(ctx, number, callDateMillis)
        if (recording == null) {
            Toast.makeText(ctx, R.string.no_recording_for_this_call, Toast.LENGTH_SHORT).show()
            return
        }
        showActions(fragment, recording)
    }

    private fun showActions(fragment: Fragment, recording: CallRecording) {
        val ctx = fragment.requireContext()
        val actions = arrayOf(
            ctx.getString(R.string.recording_play),
            ctx.getString(R.string.recording_share),
            ctx.getString(R.string.recording_delete)
        )
        AlertDialog.Builder(ctx)
            .setTitle(R.string.recording_list_title)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> play(ctx, recording)
                    1 -> share(fragment, recording)
                    2 -> confirmDelete(fragment, recording)
                }
            }
            .show()
    }

    private fun play(ctx: android.content.Context, recording: CallRecording) {
        stopPlayback()
        val file = File(recording.filePath)
        if (!file.exists()) {
            Toast.makeText(ctx, R.string.recording_play_failed, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
            }
            player = mp

            // Tạo view dialog phát nhạc bằng code (không cần layout XML riêng)
            val dialogView = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val pad = (16 * ctx.resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad / 2)
            }
            val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = mp.duration.coerceAtLeast(1)
                progress = 0
                isIndeterminate = false
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                val margin = (8 * ctx.resources.displayMetrics.density).toInt()
                lp.setMargins(0, margin, 0, margin)
                layoutParams = lp
            }
            val tvTime = TextView(ctx).apply {
                text = "0:00 / ${formatDuration(mp.duration.toLong() / 1000)}"
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                layoutParams = lp
            }
            dialogView.addView(progressBar)
            dialogView.addView(tvTime)

            // Cập nhật progress mỗi 500ms
            val updateProgress = object : Runnable {
                override fun run() {
                    val p = player ?: return
                    try {
                        if (p.isPlaying) {
                            val pos = p.currentPosition
                            val dur = p.duration.coerceAtLeast(1)
                            progressBar.max = dur
                            progressBar.progress = pos
                            tvTime.text = "${formatDuration(pos.toLong() / 1000)} / ${formatDuration(dur.toLong() / 1000)}"
                            handler.postDelayed(this, 500)
                        }
                    } catch (_: Exception) {}
                }
            }
            progressRunnable = updateProgress

            val dialog = AlertDialog.Builder(ctx)
                .setTitle(R.string.recording_play)
                .setView(dialogView)
                .setNegativeButton(R.string.recording_pause) { _, _ -> stopPlayback() }
                .setOnDismissListener { stopPlayback() }
                .create()
            progressDialog = dialog

            mp.setOnCompletionListener {
                handler.post {
                    progressBar.progress = progressBar.max
                    tvTime.text = formatDuration(mp.duration.toLong() / 1000) + " / " + formatDuration(mp.duration.toLong() / 1000)
                    progressDialog?.dismiss()
                    stopPlayback()
                }
            }
            mp.start()
            handler.post(updateProgress)
            dialog.show()
        } catch (_: Exception) {
            Toast.makeText(ctx, R.string.recording_play_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPlayback() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { progressDialog?.dismiss() }
        progressDialog = null
    }

    private fun share(fragment: Fragment, recording: CallRecording) {
        val ctx = fragment.requireContext()
        val file = File(recording.filePath)
        if (!file.exists()) {
            Toast.makeText(ctx, R.string.recording_play_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { fragment.startActivity(Intent.createChooser(intent, ctx.getString(R.string.recording_share))) }
    }

    private fun confirmDelete(fragment: Fragment, recording: CallRecording) {
        val ctx = fragment.requireContext()
        AlertDialog.Builder(ctx)
            .setMessage(R.string.recording_delete_confirm)
            .setPositiveButton(R.string.recording_delete) { _, _ ->
                stopPlayback()
                val deleted = CallRecordingManager.deleteEntry(ctx, recording)
                val msgRes = if (deleted) R.string.recording_deleted else R.string.recording_delete_failed
                Toast.makeText(ctx, msgRes, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun formatDuration(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", m, s)
    }
}
