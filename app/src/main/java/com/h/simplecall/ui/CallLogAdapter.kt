package com.h.simplecall.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.CallLog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.h.simplecall.R
import com.h.simplecall.call.BlockedNumbersManager
import com.h.simplecall.data.CallLogEntry
import com.h.simplecall.databinding.ItemCallLogBinding
import java.text.SimpleDateFormat
import java.util.*

class CallLogAdapter(
    items: List<CallLogEntry>,
    private val onCall: (String) -> Unit,
    private val onShowHistory: (String) -> Unit,
    private val isDualSim: Boolean = false
) : RecyclerView.Adapter<CallLogAdapter.VH>() {

    private var items: List<CallLogEntry> = items

    /** Cập nhật danh sách dùng DiffUtil – không flash, không tạo adapter mới */
    fun updateItems(newItems: List<CallLogEntry>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(o: Int, n: Int) =
                items[o].date == newItems[n].date && items[o].number == newItems[n].number
            override fun areContentsTheSame(o: Int, n: Int) = items[o] == newItems[n]
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    inner class VH(val b: ItemCallLogBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(ItemCallLogBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        val ctx = h.itemView.context
        val display = item.name.ifEmpty { item.number }
        val isBlocked = BlockedNumbersManager.isBlocked(item.number)
        val isMissed = item.type == CallLog.Calls.MISSED_TYPE

        // ── Icon cuộc gọi: xám (vào/ra), đỏ (nhỡ) ──
        when (item.type) {
            CallLog.Calls.MISSED_TYPE -> {
                h.b.ivType.setImageResource(R.drawable.ic_call_missed)
                h.b.ivType.setColorFilter(ContextCompat.getColor(ctx, R.color.missed_red))
            }
            CallLog.Calls.OUTGOING_TYPE -> {
                h.b.ivType.setImageResource(R.drawable.ic_call_outgoing)
                h.b.ivType.clearColorFilter()
            }
            else -> {
                h.b.ivType.setImageResource(R.drawable.ic_call_incoming)
                h.b.ivType.clearColorFilter()
            }
        }

        // ── Số / tên: SÁNG (trắng) nếu bình thường, ĐỎ nếu nhỡ ──
        h.b.tvName.text = if (isBlocked) "🚫 $display" else display
        h.b.tvName.setTextColor(
            ctx.getColor(if (isMissed) R.color.missed_red else R.color.text_primary)
        )

        // ── SIM badge: luôn xám đậm, chỉ số thay đổi ──
        val simNum = if (isDualSim && item.simSlot != null) item.simSlot + 1 else 1
        h.b.tvSimBadge.text = simNum.toString()
        h.b.tvSimBadge.visibility = View.VISIBLE
        h.b.tvSimBadge.setTextColor(ContextCompat.getColor(ctx, R.color.call_log_muted))

        // ── Khung "HD": chỉ hiện với số ĐÃ có trong danh bạ (item.name khác rỗng) ──
        h.b.tvHdBadge.visibility = if (item.name.isNotBlank()) View.VISIBLE else View.GONE
        h.b.tvHdBadge.setTextColor(ContextCompat.getColor(ctx, R.color.call_log_muted))

        // ── Loại đường dây: luôn xám đậm ──
        h.b.tvDate.text = item.numberType.ifEmpty { "Di động" }
        h.b.tvDate.setTextColor(ContextCompat.getColor(ctx, R.color.call_log_muted))

        // ── Ngày/giờ: luôn xám đậm ──
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val cal = Calendar.getInstance().apply { timeInMillis = item.date }
        val fmt = if (cal.after(today)) SimpleDateFormat("HH:mm", Locale.getDefault())
                  else SimpleDateFormat("d/M", Locale.getDefault())
        h.b.tvCallTime.text = fmt.format(Date(item.date))
        h.b.tvCallTime.setTextColor(ContextCompat.getColor(ctx, R.color.call_log_muted))

        // ── Icon info: luôn xám đậm ──
        h.b.btnCallBack.setColorFilter(ContextCompat.getColor(ctx, R.color.text_primary))

        h.b.root.setOnClickListener { onCall(item.number) }
        h.b.btnCallBack.setOnClickListener { onShowHistory(item.number) }
        h.b.root.setOnLongClickListener { showContextMenu(ctx, item.number, display); true }

        // Mở rộng VÙNG CHẠM của icon info thêm 8dp mỗi cạnh (32dp hiển thị -> ~48dp vùng chạm
        // hiệu quả, chuẩn tối thiểu khuyến nghị Android) - dùng TouchDelegate nên KHÔNG đổi
        // kích thước/layout hiển thị của icon, chỉ vùng nhận chạm rộng hơn trong code.
        (h.b.btnCallBack.parent as? View)?.post {
            val parent = h.b.btnCallBack.parent as? View ?: return@post
            val rect = android.graphics.Rect()
            h.b.btnCallBack.getHitRect(rect)
            val extra = (8 * ctx.resources.displayMetrics.density).toInt()
            rect.left -= extra; rect.top -= extra; rect.right += extra; rect.bottom += extra
            parent.touchDelegate = android.view.TouchDelegate(rect, h.b.btnCallBack)
        }
    }

    private fun showContextMenu(ctx: Context, number: String, display: String) {
        val isBlocked = BlockedNumbersManager.isBlocked(number)
        val blockLabel = if (isBlocked) ctx.getString(R.string.unblock_number)
                         else ctx.getString(R.string.block_number)
        AlertDialog.Builder(ctx)
            .setTitle(display)
            .setItems(arrayOf(ctx.getString(R.string.number_copied), blockLabel)) { _, which ->
                when (which) {
                    0 -> {
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("phone", number))
                        Toast.makeText(ctx, ctx.getString(R.string.number_copied), Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        if (isBlocked) {
                            BlockedNumbersManager.unblock(number)
                            Toast.makeText(ctx, "Đã bỏ chặn $display", Toast.LENGTH_SHORT).show()
                        } else {
                            AlertDialog.Builder(ctx)
                                .setTitle(ctx.getString(R.string.block_confirm, display))
                                .setMessage(ctx.getString(R.string.block_confirm_msg))
                                .setPositiveButton(ctx.getString(R.string.yes_block)) { _, _ ->
                                    BlockedNumbersManager.block(number)
                                    Toast.makeText(ctx, "Đã chặn $display", Toast.LENGTH_SHORT).show()
                                    notifyDataSetChanged()
                                }
                                .setNegativeButton(ctx.getString(R.string.cancel), null)
                                .show()
                        }
                    }
                }
            }.show()
    }
}
