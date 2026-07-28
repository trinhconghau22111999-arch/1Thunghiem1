package com.h.simplecall.call

import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.telecom.Call
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.h.simplecall.data.local.AppDatabase
import com.h.simplecall.data.local.CallHistoryEntity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tự ghi lịch sử cuộc gọi vào DB cục bộ của app (Room) - KHÔNG đọc/ghi qua CallLog provider của
 * Android nữa. Đăng ký làm listener của [CallManager] nên nhận đúng luồng sự kiện mà
 * InCallActivity dùng để vẽ UI (onStateChanged + onDetailsChanged), đảm bảo số được lưu vào
 * lịch sử LUÔN LÀ số đang hiển thị trên màn hình gọi tại thời điểm đó (kể cả khi có chuyển
 * hướng cuộc gọi làm số kết nối thật khác với số hiển thị).
 */
object CallHistoryManager {

    private val bgExecutor = Executors.newSingleThreadExecutor()
    private var appContext: Context? = null
    private var initialized = false
    // Đóng lại khi di trú lịch sử cũ (CallHistoryMigration) xong - các màn hình đọc lịch sử
    // (CallLogFragment/CallHistoryFragment/DialerFragment) gọi awaitReady() ở NỀN của CHÍNH
    // chúng trước khi query Room, để không bao giờ đọc trúng lúc migration còn đang chạy dở
    // (ví dụ vừa mở app lần đầu, chưa gọi cuộc nào) -> tránh hiện "Gần đây" trống oan.
    private val migrationDoneLatch = CountDownLatch(1)

    // Trạng thái phiên gọi hiện tại - app chỉ quản lý 1 cuộc gọi tại một thời điểm
    // (giống giả định hiện có của CallManager.currentCall). @Volatile vì các field này được
    // đọc/ghi từ cả main thread (sự kiện cuộc gọi) lẫn bgExecutor (ghi DB).
    @Volatile private var trackedCall: Call? = null
    @Volatile private var isOutgoing = false
    @Volatile private var reachedActive = false
    @Volatile private var sessionStartMs = 0L
    @Volatile private var sessionNumber = ""
    @Volatile private var recordId: Long = -1
    // Cuộc gọi VỪA được chốt sổ (finalizeSession) gần nhất. Một số thiết bị/ROM bắn thêm 1-2
    // sự kiện MUỘN (ví dụ onDetailsChanged dọn dẹp) cho đúng cuộc gọi đã DISCONNECTED, SAU KHI
    // trackedCall đã bị đặt về null - nếu không chặn, code sẽ hiểu nhầm đó là 1 cuộc gọi hoàn
    // toàn mới và ghi thêm 1 dòng lịch sử thứ 2 cho cùng 1 cuộc gọi thật.
    @Volatile private var lastFinalizedCall: Call? = null

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        CallManager.addListener(::onCallEvent)

        // Di trú lịch sử cũ từ CallLog hệ thống sang Room - chỉ chạy 1 lần, ở nền.
        val ctx = appContext!!
        bgExecutor.execute {
            try {
                CallHistoryMigration.runIfNeeded(ctx)
            } finally {
                migrationDoneLatch.countDown()
            }
        }
    }

    /** Gọi ở NỀN (KHÔNG phải main thread) trước khi query lịch sử, để đảm bảo migration lịch
     *  sử cũ (nếu có) đã chạy xong. Có timeout để không bao giờ treo vô hạn nếu có sự cố. */
    fun awaitReady() {
        try {
            migrationDoneLatch.await(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
    }

    private fun dao() = AppDatabase.getInstance(appContext!!).callHistoryDao()

    private fun onCallEvent(call: Call?, state: Int) {
        if (call == null || appContext == null) return
        if (call === lastFinalizedCall) return // sự kiện muộn của cuộc gọi ĐÃ kết thúc - bỏ qua

        if (call !== trackedCall) {
            // An toàn: nếu phiên trước chưa được đóng lại (hi hữu, ví dụ callback bị bỏ lỡ)
            // thì chốt sổ nó trước khi mở phiên mới, tránh mất dữ liệu / ghi đè nhầm.
            if (trackedCall != null) finalizeSession()
            startSession(call, state)
        }

        val number = CallManager.resolveDisplayNumber(call, isOutgoing)
        if (number.isNotEmpty() && number != sessionNumber) {
            sessionNumber = number
        }

        when (state) {
            Call.STATE_ACTIVE -> reachedActive = true
            Call.STATE_DISCONNECTED -> finalizeSession()
        }
    }

    private fun startSession(call: Call, state: Int) {
        trackedCall = call
        isOutgoing = CallManager.isOutgoingCall(call, state)
        reachedActive = false
        sessionStartMs = System.currentTimeMillis()
        sessionNumber = CallManager.resolveDisplayNumber(call, isOutgoing)
        recordId = -1

        val simSlot = resolveSimSlot(call)
        val startedAt = sessionStartMs
        val outgoing = isOutgoing
        val number = sessionNumber
        val ctx = appContext

        bgExecutor.execute {
            try {
                val name = CallManager.callerName(call, ctx, number)
                val id = dao().insert(
                    CallHistoryEntity(
                        name = name,
                        number = number,
                        date = startedAt,
                        type = if (outgoing) CallLog.Calls.OUTGOING_TYPE else CallLog.Calls.INCOMING_TYPE,
                        duration = 0,
                        simSlot = simSlot,
                        numberType = if (name.isNotEmpty()) "Di động" else "Việt Nam",
                        isNew = false
                    )
                )
                // Chỉ áp dụng nếu vẫn đang là phiên này (tránh ghi đè nhầm nếu cuộc gọi đã đổi rất nhanh)
                if (trackedCall === call) recordId = id
            } catch (e: Exception) {
                android.util.Log.e("CallHistoryManager", "Ghi lịch sử cuộc gọi (bắt đầu) thất bại", e)
            }
        }
    }

    private fun finalizeSession() {
        val call = trackedCall ?: return
        val outgoing = isOutgoing
        val missed = !outgoing && !reachedActive
        val finalType = when {
            outgoing -> CallLog.Calls.OUTGOING_TYPE
            reachedActive -> CallLog.Calls.INCOMING_TYPE
            else -> CallLog.Calls.MISSED_TYPE
        }
        val duration = if (reachedActive) (System.currentTimeMillis() - sessionStartMs) / 1000 else 0L
        val number = sessionNumber
        val ctx = appContext

        lastFinalizedCall = call
        trackedCall = null

        bgExecutor.execute {
            try {
                val name = CallManager.callerName(call, ctx, number)
                // recordId có thể chưa kịp gán (insert() còn đang chạy) - đợi ngắn bằng cách đọc
                // lại từ chính executor tuần tự (mọi lệnh trong bgExecutor chạy lần lượt trên 1
                // thread), nên tới đây insert() ở startSession chắc chắn đã xong nếu gửi trước.
                val id = recordId
                if (id <= 0) return@execute
                val existing = dao().getById(id) ?: return@execute
                dao().update(
                    existing.copy(
                        number = number.ifEmpty { existing.number },
                        name = if (name.isNotEmpty()) name else existing.name,
                        type = finalType,
                        duration = duration,
                        isNew = missed
                    )
                )
            } catch (e: Exception) {
                android.util.Log.e("CallHistoryManager", "Ghi lịch sử cuộc gọi (kết thúc) thất bại", e)
            }
        }
    }

    private fun resolveSimSlot(call: Call): Int? {
        val ctx = appContext ?: return null
        return try {
            if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) return null
            val subId = call.details?.accountHandle?.id?.toIntOrNull() ?: return null
            ctx.getSystemService(SubscriptionManager::class.java)
                ?.getActiveSubscriptionInfo(subId)?.simSlotIndex?.takeIf { it >= 0 }
        } catch (_: Exception) {
            null
        }
    }
}
