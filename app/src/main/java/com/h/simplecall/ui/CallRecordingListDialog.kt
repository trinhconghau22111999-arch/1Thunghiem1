package com.h.simplecall.ui

import android.app.AlertDialog
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
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

    fun show(fragment: Fragment, number: String) {
        val ctx = fragment.requireContext()
        val recordings = CallRecordingManager.getForNumber(ctx, number)
        if (recordings.isEmpty()) {
            Toast.makeText(ctx, R.string.no_recordings_for_number, Toast.LENGTH_SHORT).show()
            return
        }
        val fmt = SimpleDateFormat("d/M/yyyy HH:mm", Locale.getDefault())
        val labels = recordings.map { rec ->
            "${fmt.format(rec.startTimeMillis)}  •  ${formatDuration(rec.durationSeconds)}"
        }.toTypedArray()

        AlertDialog.Builder(ctx)
            .setTitle(R.string.recording_list_title)
            .setItems(labels) { _, index -> showActions(fragment, recordings[index]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
            Toast.makeText(ctx, R.string.recording_play, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(ctx, R.string.recording_play_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPlayback() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
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
                CallRecordingManager.deleteEntry(ctx, recording)
                Toast.makeText(ctx, R.string.recording_deleted, Toast.LENGTH_SHORT).show()
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
