package com.h.simplecall.ui

import android.os.Bundle
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.h.simplecall.MainActivity
import com.h.simplecall.data.Contact
import com.h.simplecall.databinding.FragmentStarredContactsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Danh sách các liên hệ đã được đánh dấu sao (yêu thích) trong danh bạ hệ thống. */
class StarredContactsFragment : Fragment() {

    private var _b: FragmentStarredContactsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentStarredContactsBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.btnBack.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }

        val adapter = ContactsAdapter(emptyList(), emptyList()) { contact ->
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(com.h.simplecall.R.id.fragmentContainer, ContactDetailFragment.newInstance(contact.number, contact.name))
                .addToBackStack("contactDetail")
                .commit()
            (activity as? MainActivity)?.hideNav()
        }
        b.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerView.adapter = adapter

        // Tải trên IO thread, tránh ANR giống các màn hình danh bạ/nhật ký khác
        viewLifecycleOwner.lifecycleScope.launch {
            val starred = withContext(Dispatchers.IO) { loadStarredContacts() }
            if (_b == null) return@launch
            adapter.updateContacts(starred)
            b.tvEmpty.visibility = if (starred.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun loadStarredContacts(): List<Contact> {
        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_CONTACTS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()
        val list = mutableListOf<Contact>()
        val cur = requireContext().contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI
            ),
            "${ContactsContract.CommonDataKinds.Phone.STARRED} = 1", null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        ) ?: return list
        cur.use {
            val iName  = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val iNum   = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val iPhoto = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)
            while (it.moveToNext()) {
                list.add(Contact(
                    name     = it.getString(iName) ?: "",
                    number   = it.getString(iNum) ?: "",
                    photoUri = it.getString(iPhoto),
                    starred  = true
                ))
            }
        }
        return list
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
