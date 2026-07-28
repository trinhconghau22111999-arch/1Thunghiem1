package com.h.simplecall.ui

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.h.simplecall.R
import com.h.simplecall.data.Contact
import com.h.simplecall.databinding.ItemContactSuggestBinding

class ContactSuggestAdapter(
    private val onCall: (String) -> Unit
) : RecyclerView.Adapter<ContactSuggestAdapter.VH>() {

    private val avatarBgs  = intArrayOf(R.color.av0,R.color.av1,R.color.av2,R.color.av3,R.color.av4,R.color.av5)
    private val avatarTxts = intArrayOf(R.color.av0t,R.color.av1t,R.color.av2t,R.color.av3t,R.color.av4t,R.color.av5t)

    private var items = listOf<Contact>()
    // Chuỗi số đang được gõ để tô màu phần trùng khớp trong số điện thoại hiển thị.
    private var query = ""

    fun update(list: List<Contact>, query: String = "") {
        items = list; this.query = query; notifyDataSetChanged()
    }

    inner class VH(val b: ItemContactSuggestBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(ItemContactSuggestBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val c = items[pos]; val ctx = h.itemView.context
        val idx = Math.abs(c.name.hashCode()) % avatarBgs.size
        h.b.avatarView.setBackgroundResource(R.drawable.bg_avatar)
        h.b.avatarView.background.setTint(ctx.getColor(avatarBgs[idx]))
        h.b.tvInitial.text = c.name.take(1).uppercase()
        h.b.tvInitial.setTextColor(ctx.getColor(avatarTxts[idx]))
        h.b.tvName.text = c.name

        // Tô màu (xanh @color/primary) đúng đoạn số điện thoại trùng với chuỗi đang gõ,
        // giống cách trình quay số hệ thống làm nổi bật kết quả tìm kiếm.
        h.b.tvNumber.text = highlightMatch(ctx, c.number, query)

        h.b.root.setOnClickListener { onCall(c.number.filter { it.isDigit() || it == '+' }) }
    }

    private fun highlightMatch(ctx: android.content.Context, number: String, query: String): CharSequence {
        if (query.isEmpty()) return number
        val start = number.indexOf(query)
        if (start < 0) return number
        val span = SpannableString(number)
        span.setSpan(
            ForegroundColorSpan(ctx.getColor(R.color.primary)),
            start, start + query.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return span
    }
}
