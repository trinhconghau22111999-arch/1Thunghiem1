package com.h.simplecall.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.h.simplecall.R
import com.h.simplecall.data.Contact
import com.h.simplecall.data.firstLetterKey
import com.h.simplecall.databinding.ItemContactBinding
import com.h.simplecall.databinding.ItemContactHeaderBinding
import com.h.simplecall.databinding.ItemContactLetterHeaderBinding

/** Hàng tĩnh hiển thị phía trên danh sách (vd. "Thông tin của tôi", "Nhóm của tôi"). */
data class ContactHeader(val iconRes: Int, val label: String, val onClick: () -> Unit)

/** Một hàng bất kỳ trong danh sách: hàng tĩnh, hàng chữ cái nhóm (A, B, C...) hoặc một liên hệ. */
private sealed class Row {
    data class Static(val header: ContactHeader) : Row()
    data class Letter(val letter: String) : Row()
    data class Item(val contact: Contact, val colorIdx: Int) : Row()
}

class ContactsAdapter(
    private var allContacts: List<Contact>,
    private val headers: List<ContactHeader>,
    private val onOpenDetail: (Contact) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_STATIC_HEADER = 0
        private const val TYPE_LETTER_HEADER = 1
        private const val TYPE_CONTACT = 2
    }

    // Chỉ 3 màu xen kẽ cho nền ảnh đại diện
    private val avatarBgs = intArrayOf(R.color.av_c1, R.color.av_c2, R.color.av_c3)

    private var filtered = allContacts.toMutableList()
    private var query: String = ""
    private var rows: List<Row> = buildRows()

    private fun buildRows(): List<Row> {
        val list = mutableListOf<Row>()
        if (query.isEmpty()) headers.forEach { list.add(Row.Static(it)) }

        // Nhóm "★" (đã đánh dấu sao): bung trực tiếp ngay trong danh sách như 1 nhóm chữ cái
        // bình thường - không còn là 1 dòng bấm-để-điều-hướng-sang-màn-khác nữa. Các liên hệ
        // này vẫn xuất hiện thêm 1 lần nữa ở đúng nhóm chữ cái A-Z của chúng bên dưới (giống
        // cách danh bạ hệ thống hiển thị mục Yêu thích/Favorites).
        if (query.isEmpty()) {
            val starred = filtered.filter { it.starred }
            if (starred.isNotEmpty()) {
                list.add(Row.Letter("★"))
                starred.forEachIndexed { i, c -> list.add(Row.Item(c, i % avatarBgs.size)) }
            }
        }

        var lastLetter: String? = null
        filtered.forEachIndexed { i, c ->
            if (query.isEmpty()) {
                val letter = firstLetterKey(c.name)
                if (letter != lastLetter) {
                    list.add(Row.Letter(letter))
                    lastLetter = letter
                }
            }
            list.add(Row.Item(c, i % avatarBgs.size))
        }
        return list
    }

    inner class StaticHeaderVH(val b: ItemContactHeaderBinding) : RecyclerView.ViewHolder(b.root)
    inner class LetterHeaderVH(val b: ItemContactLetterHeaderBinding) : RecyclerView.ViewHolder(b.root)
    inner class ContactVH(val b: ItemContactBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Static -> TYPE_STATIC_HEADER
        is Row.Letter -> TYPE_LETTER_HEADER
        is Row.Item -> TYPE_CONTACT
    }

    override fun onCreateViewHolder(p: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            TYPE_STATIC_HEADER -> StaticHeaderVH(
                ItemContactHeaderBinding.inflate(LayoutInflater.from(p.context), p, false)
            )
            TYPE_LETTER_HEADER -> LetterHeaderVH(
                ItemContactLetterHeaderBinding.inflate(LayoutInflater.from(p.context), p, false)
            )
            else -> ContactVH(
                ItemContactBinding.inflate(LayoutInflater.from(p.context), p, false)
            )
        }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        when (val row = rows[pos]) {
            is Row.Static -> {
                h as StaticHeaderVH
                h.b.ivIcon.setImageResource(row.header.iconRes)
                h.b.tvLabel.text = row.header.label
                h.b.root.setOnClickListener { row.header.onClick() }
            }
            is Row.Letter -> {
                h as LetterHeaderVH
                h.b.tvLetter.text = row.letter
            }
            is Row.Item -> {
                h as ContactVH
                val c = row.contact; val ctx = h.itemView.context

                if (c.photoUri != null) {
                    h.b.ivContactPhoto.visibility = View.VISIBLE
                    h.b.avatarView.visibility = View.GONE
                    h.b.tvAvatar.visibility = View.GONE
                    h.b.ivContactPhoto.setImageURI(Uri.parse(c.photoUri))
                } else {
                    h.b.ivContactPhoto.visibility = View.GONE
                    h.b.avatarView.visibility = View.VISIBLE
                    h.b.tvAvatar.visibility = View.VISIBLE
                    h.b.avatarView.setBackgroundResource(R.drawable.bg_avatar)
                    h.b.avatarView.background.setTint(ctx.getColor(avatarBgs[row.colorIdx]))
                    h.b.tvAvatar.text = c.name.take(1).uppercase()
                    h.b.tvAvatar.setTextColor(ctx.getColor(R.color.white))
                }

                h.b.tvName.text = c.name
                h.b.root.setOnClickListener { onOpenDetail(c) }
            }
        }
    }

    fun filter(q: String) {
        query = q
        filtered = if (q.isEmpty()) allContacts.toMutableList()
        else allContacts.filter { it.name.contains(q, true) || it.number.contains(q) }.toMutableList()
        rows = buildRows()
        notifyDataSetChanged()
    }

    /** Vị trí của hàng tiêu đề chữ cái [letter] (để cuộn tới), hoặc null nếu không có. */
    fun positionForLetter(letter: String): Int? {
        val idx = rows.indexOfFirst { it is Row.Letter && it.letter == letter }
        return if (idx >= 0) idx else null
    }

    fun firstContactPosition(): Int = rows.indexOfFirst { it is Row.Item }.coerceAtLeast(0)
    fun lastPosition(): Int = (itemCount - 1).coerceAtLeast(0)

    /** Chữ cái của nhóm đang hiển thị ở đầu danh sách hiện tại (dùng để tô sáng
     *  chữ tương ứng trên thanh chỉ mục A-Z bên phải khi người dùng cuộn tay). */
    fun letterAtOrBefore(position: Int): String? {
        for (i in position downTo 0) {
            val row = rows.getOrNull(i)
            if (row is Row.Letter) return row.letter
        }
        return null
    }
    /** Cập nhật toàn bộ danh sách liên hệ (gọi từ coroutine sau khi load xong trên IO thread). */
    fun updateContacts(contacts: List<Contact>) {
        allContacts = contacts
        filter(query)
    }

}