package com.h.simplecall

import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.view.View
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.h.simplecall.call.BlockedNumbersManager
import com.h.simplecall.call.CallForwardManager
import com.h.simplecall.call.MissedCallNotifier
import com.h.simplecall.databinding.ActivityMainBinding
import com.h.simplecall.ui.CallHistoryFragment
import com.h.simplecall.ui.CallLogFragment
import com.h.simplecall.ui.ContactsFragment
import com.h.simplecall.ui.DialerFragment
import com.h.simplecall.ui.ForwardSettingsFragment

/** Fragment nào cần biết khi trạng thái "ứng dụng gọi mặc định" thay đổi thì implement cái này. */
interface DefaultDialerStatusListener {
    fun onDefaultDialerStatusChanged()
}

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** Tab đang chọn ở bottom nav, dùng để biết có nên hiện lại fabDialpad hay không
     *  khi quay lại từ backstack (vd. đóng màn Cài đặt/Bàn phím số). */
    private var currentNavId: Int = R.id.nav_recents

    private val permissions: Array<String> = buildList {
        add(android.Manifest.permission.CALL_PHONE)
        add(android.Manifest.permission.READ_PHONE_STATE)
        add(android.Manifest.permission.READ_CALL_LOG)
        add(android.Manifest.permission.WRITE_CALL_LOG)
        add(android.Manifest.permission.READ_CONTACTS)
        add(android.Manifest.permission.ANSWER_PHONE_CALLS)
        add(android.Manifest.permission.VIBRATE)
        add(android.Manifest.permission.RECORD_AUDIO)
        // Thiếu quyền này trước đây khiến app không bao giờ xin phép hiển thị
        // thông báo cuộc gọi nhỡ trên Android 13+ (dù đã khai báo trong Manifest).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { results ->
        requestDefaultDialer()

        // Quyền nào vẫn bị từ chối SAU khi hộp thoại vừa đóng, và hệ thống báo không cần giải
        // thích thêm (shouldShowRequestPermissionRationale = false) trong khi mình ĐÃ từng xin
        // qua rồi (đánh dấu ở prefsHasRequestedPermsBefore) => chắc chắn là bị từ chối VĨNH VIỄN
        // ("Không hỏi lại"). Trường hợp này gọi lại permLauncher lần nữa sẽ KHÔNG hiện hộp thoại
        // gì cả (im lặng trả về denied ngay), nên phải tự dẫn người dùng sang Cài đặt ứng dụng.
        val permanentlyDenied = results.filter { (perm, granted) ->
            !granted && !shouldShowRequestPermissionRationale(perm) && hasRequestedPermsBefore()
        }.keys
        if (permanentlyDenied.isNotEmpty()) {
            showPermanentlyDeniedDialog(permanentlyDenied)
        }
        markHasRequestedPermsBefore()
    }
    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { updateDefaultDialerStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Warm-up DB connection ngay khi app mở để lần đầu vào tab Gần đây không delay
        val appCtx = applicationContext
        Thread {
            com.h.simplecall.data.local.AppDatabase.warmUp(appCtx)
        }.start()

        CallForwardManager.init(this)
        BlockedNumbersManager.init(this)
        MissedCallNotifier.init(this)

        requestPermissions()

        binding.bottomNav.setOnItemSelectedListener { item -> goToTab(item.itemId); true }
        binding.bottomNav.setOnItemReselectedListener { item -> goToTab(item.itemId) }
        setupTabSwipeGesture()
        // Ép tắt tint icon bằng code (không chỉ dựa vào app:itemIconTint="@null" trong XML) -
        // đây chính là nguyên nhân icon "Gần đây" khi được chọn bị tô ĐÈ thành xanh LÁ (trùng
        // colorPrimary của theme) thay vì giữ đúng màu xanh DƯƠNG + kim đồng hồ trắng đã vẽ sẵn
        // trong ic_tab_recents_blue. Gọi thẳng setItemIconTintList(null) đảm bảo tắt tint trên
        // mọi phiên bản thư viện Material, không phụ thuộc việc XML có được áp dụng đúng hay không.
        binding.bottomNav.itemIconTintList = null

        binding.fabAddContact.setOnClickListener {
            val frag = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
            if (frag is com.h.simplecall.ui.ContactsFragment) frag.openCreateContactPublic()
        }

        binding.fabDialpad.setOnClickListener {
            // Nếu đang đứng sẵn trong DialerFragment (trường hợp FAB đang hiện vì người dùng vừa
            // ẩn bàn phím) thì chỉ cần MỞ LẠI bàn phím trên fragment đó, không tạo fragment mới
            // (tránh mất số đang gõ). Ngược lại (đang ở tab Gần đây/Danh bạ) mới tạo mới như cũ.
            val current = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
            if (current is DialerFragment) {
                current.showKeypad()
            } else {
                // Chỉ thay nội dung bằng DialerFragment, KHÔNG push vào back stack và KHÔNG ẩn
                // thanh điều hướng dưới (Gần đây/Danh bạ) - bàn phím số phải hiện cùng lúc với
                // thanh điều hướng, không được che/ẩn nó đi.
                navigateTo(DialerFragment())
                binding.fabDialpad.visibility = View.GONE
            }
        }

        supportFragmentManager.addOnBackStackChangedListener {
            val empty = supportFragmentManager.backStackEntryCount == 0
            binding.bottomNav.visibility   = if (empty) View.VISIBLE else View.GONE
            binding.fabDialpad.visibility  =
                if (empty && currentNavId != R.id.nav_contacts) View.VISIBLE else View.GONE
        }

        // Khi bấm back từ CallHistoryFragment (mở từ icon "i" ở Gần đây):
        // back stack pop về 0 nhưng fragment container vẫn đang hiện CallHistoryFragment
        // (không có gì ở dưới để quay về) → Android thoát app. Fix: nếu back stack đã rỗng
        // nhưng fragment hiện tại không phải fragment tab gốc, điều hướng lại về tab đang chọn.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
                // Back stack còn entry → pop bình thường (bao gồm cả khi đang ở CallHistoryFragment)
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                    return
                }
                // Bàn phím số đang mở → back chỉ tắt bàn phím
                if (current is DialerFragment && current.isKeypadVisible()) {
                    current.hideKeypad()
                    return
                }
                // Đã ở trang gốc → thu app về background (không thoát hẳn)
                moveTaskToBack(true)
            }
        })

        if (savedInstanceState == null) {
            val data = intent?.data
            if (data?.scheme == "tel") {
                // Mở app qua liên kết "tel:" (vd. từ ứng dụng khác) - ưu tiên xử lý số đó
                handleIntent(intent)
            } else {
                // Mặc định mở app: vào thẳng màn "Gần đây" ĐÃ MỞ SẴN bàn phím số (DialerFragment
                // tự hiện danh sách gần đây phía trên bàn phím), không cần bấm FAB mới có bàn phím.
                navigateTo(DialerFragment())
                binding.fabDialpad.visibility = View.GONE
            }
        }
        updateMissedBadge()
    }

    override fun onResume() { super.onResume(); updateMissedBadge() }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "tel") {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, DialerFragment.newInstanceWithNumber(data.schemeSpecificPart))
                .addToBackStack("dialpad")
                .commit()
            hideNav()
        }
    }

    fun navigateTo(f: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, f).commit()
    }

    /** Chuyển sang tab Gần đây/Danh bạ. Tách riêng để dùng chung cho cả lần bấm đầu tiên
     *  (OnItemSelectedListener) VÀ khi bấm lại đúng tab đang được chọn (OnItemReselectedListener) -
     *  trường hợp thứ 2 cần thiết để người dùng có thể thoát khỏi bàn phím số (mở qua FAB, không
     *  đổi tab đang chọn) quay lại danh sách Gần đây/Danh bạ.
     *  @param animate false khi gọi từ code (khởi động app...), không cần hiệu ứng trượt. */
    private var isTabSwitching = false

    private fun goToTab(itemId: Int, animate: Boolean = true) {
        if (animate && isTabSwitching) return
        val goingToContacts = itemId == R.id.nav_contacts
        val wasContacts = currentNavId == R.id.nav_contacts
        val directionChanged = animate && goingToContacts != wasContacts
        currentNavId = itemId
        val tag = if (itemId == R.id.nav_contacts) "contacts" else "recents"
        val dest = supportFragmentManager.findFragmentByTag(tag) ?: run {
            if (itemId == R.id.nav_contacts) ContactsFragment() else CallLogFragment()
        }
        val tx = supportFragmentManager.beginTransaction()
        if (directionChanged) {
            // Vuốt/chuyển qua lại giữa Gần đây <-> Danh bạ trượt như lật ảnh trong thư viện:
            // sang Danh bạ (bên phải) trượt từ phải qua; quay lại Gần đây trượt từ trái qua.
            if (goingToContacts) tx.setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
            else tx.setCustomAnimations(R.anim.slide_in_left, R.anim.slide_out_right)
        }
        tx.replace(R.id.fragmentContainer, dest, tag).commit()
        // TRƯỚC ĐÂY gọi executePendingTransactions() ở đây để tránh 2 transaction chồng lên
        // nhau khi bấm đổi tab liên tục/rất nhanh - nhưng việc ép dựng layout Fragment mới chạy
        // ĐỒNG BỘ ngay tại đây khiến toàn bộ animation bị "khựng" 1 nhịp (đứng hình) trước khi
        // kịp hiện ra, vì hệ thống phải dựng xong toàn bộ view mới cho animation chạy.
        // Thay bằng debounce nhẹ: khi đang trong lúc animation (isTabSwitching=true), lờ đi các
        // lần bấm đổi tab dồn dập tiếp theo, để FragmentManager xử lý transaction bình thường
        // (bất đồng bộ, mượt hơn) mà vẫn tránh được tình trạng chồng transaction gây crash.
        isTabSwitching = true
        binding.root.postDelayed({ isTabSwitching = false }, 440L)
        // Tab Danh bạ đã có sẵn nút "+" riêng (fabAddContact) ở đúng vị trí này,
        // nên phải ẩn FAB bàn phím số đi để không bị đè lên nhau.
        if (itemId == R.id.nav_contacts) {
            binding.fabDialpad.visibility = View.GONE
            binding.fabAddContact.visibility = View.VISIBLE
        } else {
            binding.fabDialpad.visibility = View.VISIBLE
            binding.fabAddContact.visibility = View.GONE
        }
        if (itemId == R.id.nav_recents)
            binding.bottomNav.getBadge(R.id.nav_recents)?.isVisible = false
    }

    /** Vuốt ngang qua lại trên nội dung chính để chuyển tab Gần đây <-> Danh bạ, giống thao
     *  tác lướt ảnh qua lại trong thư viện ảnh. Vuốt sang trái = tab kế tiếp (Danh bạ);
     *  vuốt sang phải = tab trước đó (Gần đây). Chỉ bắt cử chỉ vuốt NGANG rõ rệt, đủ nhanh
     *  (như ném/fling) để không đụng độ với cuộn dọc bình thường của danh sách bên trong.
     *
     *  Bắt ở dispatchTouchEvent() của Activity (không phải setOnTouchListener trên FrameLayout
     *  cha) vì RecyclerView bên trong luôn "nuốt" ACTION_DOWN để dự phòng cuộn dọc, khiến các
     *  sự kiện MOVE/UP sau đó không còn nổi bọt lên tới listener của View cha nữa. Activity thì
     *  luôn nhận được toàn bộ luồng sự kiện chạm trước tiên, bất kể view con xử lý ra sao. */
    private lateinit var tabSwipeDetector: GestureDetector

    private fun setupTabSwipeGesture() {
        tabSwipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val minDist = 80f
                val minVel = 400f
                if (kotlin.math.abs(dx) <= kotlin.math.abs(dy)) return false // ưu tiên cuộn dọc
                if (kotlin.math.abs(dx) < minDist || kotlin.math.abs(velocityX) < minVel) return false
                if (dx < 0 && currentNavId != R.id.nav_contacts) {
                    binding.bottomNav.selectedItemId = R.id.nav_contacts // vuốt trái -> Danh bạ
                    return true
                }
                if (dx > 0 && currentNavId != R.id.nav_recents) {
                    binding.bottomNav.selectedItemId = R.id.nav_recents // vuốt phải -> Gần đây
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::tabSwipeDetector.isInitialized) tabSwipeDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    fun hideNav() {
        binding.bottomNav.visibility   = View.GONE
        binding.fabDialpad.visibility  = View.GONE
    }

    fun setDialpadFabVisible(visible: Boolean) {
        binding.fabDialpad.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Mở màn Cài đặt chuyển hướng cuộc gọi. Được gọi từ icon lục giác ở mỗi tab
     *  (Danh bạ / Gần đây) — trước đây có một nút 3 chấm riêng đè lên icon này,
     *  giờ đã gộp lại thành một nút cài đặt duy nhất. */
    fun openSettings() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, ForwardSettingsFragment())
            .addToBackStack("settings")
            .commit()
        hideNav()
    }

    private fun requestPermissions() {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            binding.root.post { permLauncher.launch(missing.toTypedArray()) }
        } else {
            binding.root.post { requestDefaultDialer() }
        }
    }

    // Đánh dấu "đã từng hiện hộp thoại xin quyền ít nhất 1 lần" bằng SharedPreferences, để lần
    // sau phân biệt được "lần đầu bị từ chối" (chưa chắc là vĩnh viễn) với "bị từ chối vĩnh viễn
    // thật sự" (shouldShowRequestPermissionRationale=false NGAY CẢ KHI đã từng xin qua rồi).
    private fun hasRequestedPermsBefore(): Boolean =
        getSharedPreferences("perms", MODE_PRIVATE).getBoolean("requested_once", false)

    private fun markHasRequestedPermsBefore() {
        getSharedPreferences("perms", MODE_PRIVATE).edit()
            .putBoolean("requested_once", true).apply()
    }

    /** Tên tiếng Việt dễ hiểu cho từng quyền, dùng trong hộp thoại dẫn sang Cài đặt. */
    private fun permissionLabel(perm: String): String = when (perm) {
        android.Manifest.permission.CALL_PHONE -> "Gọi điện thoại"
        android.Manifest.permission.READ_PHONE_STATE -> "Trạng thái điện thoại"
        android.Manifest.permission.READ_CALL_LOG -> "Đọc nhật ký cuộc gọi"
        android.Manifest.permission.WRITE_CALL_LOG -> "Ghi nhật ký cuộc gọi"
        android.Manifest.permission.READ_CONTACTS -> "Danh bạ"
        android.Manifest.permission.ANSWER_PHONE_CALLS -> "Trả lời cuộc gọi"
        android.Manifest.permission.RECORD_AUDIO -> "Micro (ghi âm cuộc gọi)"
        android.Manifest.permission.POST_NOTIFICATIONS -> "Thông báo"
        else -> perm.substringAfterLast(".")
    }

    /** Một hoặc nhiều quyền QUAN TRỌNG đã bị từ chối vĩnh viễn ("Không hỏi lại") - hộp thoại
     *  xin quyền của hệ thống sẽ KHÔNG bao giờ tự hiện lại nữa, nên phải chủ động dẫn người
     *  dùng sang đúng màn Cài đặt > Quyền của ứng dụng để họ tự bật lại. Không làm gì (im lặng)
     *  sẽ khiến nhiều tính năng cốt lõi (đọc nhật ký, danh bạ, gọi điện...) không hoạt động mà
     *  người dùng không hiểu vì sao. */
    private fun showPermanentlyDeniedDialog(perms: Collection<String>) {
        if (isFinishing || isDestroyed) return
        val names = perms.joinToString(", ") { permissionLabel(it) }
        AlertDialog.Builder(this)
            .setTitle("Cần cấp thêm quyền")
            .setMessage("Ứng dụng cần quyền: $names để hoạt động đầy đủ, nhưng đã bị từ chối " +
                "vĩnh viễn. Vào Cài đặt > Quyền để cấp lại thủ công.")
            .setPositiveButton("Mở Cài đặt") { _, _ -> openAppSettings() }
            .setNegativeButton("Để sau", null)
            .setCancelable(true)
            .show()
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)))
        } catch (_: Exception) {
            Toast.makeText(this, "Không mở được màn hình Cài đặt", Toast.LENGTH_SHORT).show()
        }
    }

    /** Có thể gọi lại thủ công (vd. từ màn Cài đặt) nếu người dùng lỡ từ chối lần đầu. */
    fun requestDefaultDialer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm == null) {
                Toast.makeText(this, "Thiết bị không hỗ trợ đặt ứng dụng gọi mặc định", Toast.LENGTH_LONG).show()
                return
            }
            if (!rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                Toast.makeText(this, "Thiết bị/ROM này không hỗ trợ vai trò ứng dụng gọi mặc định", Toast.LENGTH_LONG).show()
                return
            }
            if (!rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
                try {
                    roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                } catch (_: Exception) {
                    openManualDefaultAppsSettings()
                }
            }
        } else {
            val tm = getSystemService(TelecomManager::class.java) ?: return
            if (packageName != tm.defaultDialerPackage) {
                try {
                    roleLauncher.launch(Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                        putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
                    })
                } catch (_: Exception) {
                    openManualDefaultAppsSettings()
                }
            }
        }
        updateDefaultDialerStatus()
    }

    private fun openManualDefaultAppsSettings() {
        val opened = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                true
            } else false
        } catch (_: Exception) { false }
        if (!opened) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)))
            } catch (_: Exception) {
                Toast.makeText(this,
                    "Không thể mở cài đặt tự động. Vào Cài đặt > Ứng dụng > Ứng dụng mặc định để đặt thủ công.",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    fun isDefaultDialer(): Boolean {
        val tm = getSystemService(TelecomManager::class.java) ?: return false
        return packageName == tm.defaultDialerPackage
    }

    private fun updateDefaultDialerStatus() {
        supportFragmentManager.fragments.forEach { (it as? DefaultDialerStatusListener)?.onDefaultDialerStatusChanged() }
    }

    /** Đếm số cuộc gọi nhỡ CHƯA đọc để hiện badge đỏ trên tab "Gần đây". Truy vấn CallLog
     *  chạy ở BACKGROUND THREAD (contentResolver.query có thể chậm nếu nhật ký cuộc gọi dài) -
     *  trước đây chạy thẳng trên main thread trong onCreate()/onResume(), có thể gây giật/đơ
     *  (ANR) mỗi lần mở app hoặc quay lại từ màn khác. */
    private fun updateMissedBadge() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) return
        Thread {
            val cur = try {
                contentResolver.query(
                    android.provider.CallLog.Calls.CONTENT_URI,
                    arrayOf(android.provider.CallLog.Calls.TYPE),
                    "${android.provider.CallLog.Calls.NEW} = 1 AND " +
                        "${android.provider.CallLog.Calls.TYPE} = ${android.provider.CallLog.Calls.MISSED_TYPE}",
                    null, null)
            } catch (_: Exception) { null }
            val count = cur?.count ?: 0; cur?.close()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val badge = binding.bottomNav.getOrCreateBadge(R.id.nav_recents)
                if (count > 0) { badge.isVisible = true; badge.number = count }
                else badge.isVisible = false
            }
        }.start()
    }

    // ============================================================================================
    // GHI CHÚ CỦA CLAUDE — vì sao mình KHÔNG ghi đè file này lên repo:
    //
    // Hàm placeCall() bên dưới gọi CallForwardManager.prepareCall(number) rồi
    // CallForwardManager.resolveNumber(number) để lấy ra "actual" — số THỰC SỰ sẽ được quay,
    // có thể khác với số người dùng vừa bấm trên bàn phím. Khi tính năng "chuyển hướng" trong
    // ForwardSettingsFragment được bật (switch on + đã lưu 1 số đích 10 số), MỌI cuộc gọi đi từ
    // app — bất kể người dùng bấm số nào — sẽ bị quay sang số đích cố định đó thay vì số đã bấm.
    // Biến `lastDisplayNumber` trong CallForwardManager được chú thích là "chỉ dùng để hiển thị
    // UI", gợi ý rằng giao diện cuộc gọi có thể vẫn hiện số người dùng đã bấm trong khi máy thực
    // sự đang gọi sang số khác.
    //
    // Do app này đã được đăng ký làm ứng dụng gọi điện MẶC ĐỊNH trên máy (có toàn quyền chặn mọi
    // số bấm), cơ chế này về bản chất là "chặn & chuyển hướng cuộc gọi đi một cách không hiển thị
    // rõ ràng cho người dùng số nào thực sự được gọi". Mình không nghĩ ra kịch bản sử dụng hợp
    // pháp nào mà chính chủ điện thoại lại cần tự ý đổi số mình bấm sang 1 số cố định khác mà
    // không được thông báo minh bạch — đây là mẫu hành vi thường thấy ở spyware/stalkerware (ai
    // đó cài lên máy người khác để chặn/theo dõi cuộc gọi họ định thực hiện) hoặc lừa đảo (chuyển
    // hướng cuộc gọi tới số thật sang số giả mạo).
    //
    // Mình để nguyên file này (không đụng vào placeCall/CallForwardManager) để bạn tự xem và
    // quyết định sửa lại theo hướng minh bạch hơn, ví dụ:
    //   - Luôn hiển thị RÕ RÀNG trên màn hình gọi số THỰC SỰ đang được quay (không phải số gốc),
    //     và/hoặc hỏi xác nhận người dùng trước mỗi lần chuyển hướng xảy ra.
    //   - Hoặc bỏ hẳn cơ chế đổi số đi ngầm này, thay bằng chuyển hướng CUỘC GỌI ĐẾN (incoming)
    //     qua mã lệnh chuyển hướng của nhà mạng (**21*số#) — đây là cách "chuyển hướng cuộc gọi"
    //     đúng nghĩa, minh bạch, và không cần app tự ý đổi số người dùng bấm.
    // ============================================================================================

    /** @param phoneAccountHandle SIM cụ thể để gọi (khi máy có 2 SIM và người dùng bấm
     *  nút "1" hoặc "2"); null nghĩa là để hệ thống tự chọn/hỏi như trước. */
    fun placeCall(number: String, phoneAccountHandle: PhoneAccountHandle? = null) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) { requestPermissions(); return }

        if (!isDefaultDialer()) {
            Toast.makeText(this,
                "Hãy đặt \"${getString(R.string.app_name)}\" làm ứng dụng gọi điện mặc định để dùng giao diện gọi riêng",
                Toast.LENGTH_LONG).show()
            requestDefaultDialer()
        }

        CallForwardManager.prepareCall(number)
        val actual = CallForwardManager.resolveNumber(number)
        val extras = phoneAccountHandle?.let {
            Bundle().apply { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        }
        getSystemService(TelecomManager::class.java)
            ?.placeCall(Uri.fromParts("tel", actual, null), extras)
    }
}
