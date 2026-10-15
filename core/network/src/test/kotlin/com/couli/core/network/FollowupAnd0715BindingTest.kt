package com.couli.core.network

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FU-AND-0715 第 2 项（F-AND-33；对照鸿蒙 FU-HM-0717 fand33_*）：已绑定的会话归属与会话有效性同步。真实 [ApiClient] + [KeystoreSessionTokens]
 * （计数解密）+ [CredentialReadBarrier]，切环境按 App 接线在屏障里发布「环境未就绪」（清理器不运行：状态已发布、第一个清理器未运行的窗口）。
 * - 正常刷新保存之后、原请求重放之前切环境：确认 / 放弃仍按原账号 → environment_unsettled（不是 pending_key_unresolved）；
 * - 令牌存储自己发现损坏并删除（删除成功或失败）、或被落盘撤销标记阻断：同步推进会话世代、作废已绑定归属 → 切环境后按游客（pending_key_unresolved），
 *   pendingFor 不返回旧记录。
 * 窗口内都断言：令牌零解密、零发送、未决记录保持 unknown。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class FollowupAnd0715BindingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private val requests = AtomicInteger()
    private val meExpired = AtomicBoolean(false)

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.incrementAndGet()
                    return when (request.path) {
                        "/v1/withdrawals" -> {
                            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                        }

                        "/v1/auth/refresh" -> {
                            envelope(0, """{"access_token":"access-2","refresh_token":"refresh-2","session_scope":"full"}""")
                        }

                        else -> {
                            envelope(if (meExpired.getAndSet(false)) 10002 else 0, """{"user_id":"user-a"}""")
                        }
                    }
                }
            }
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun envelope(
        code: Int,
        data: String,
    ) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"trace-0123456789"}""")

    private class CountingCipher : SecretCipher {
        val decrypts = AtomicInteger()

        override fun encrypt(plain: ByteArray): ByteArray = plain.map { it.toInt().inv().toByte() }.toByteArray()

        override fun decrypt(sealed: ByteArray): ByteArray {
            decrypts.incrementAndGet()
            return sealed.map { it.toInt().inv().toByte() }.toByteArray()
        }
    }

    /** 提交可控；[afterCommit] 在一次成功提交落盘之后、回到调用方之前同步回调（刷新写入的「保存后、重放前」窗口）。 */
    private class HookedPreferences(
        val inner: SharedPreferences,
    ) : SharedPreferences by inner {
        @Volatile var writable = true

        @Volatile var afterCommit: (Map<String, String?>) -> Unit = {}

        override fun edit(): SharedPreferences.Editor {
            val changes = linkedMapOf<String, String?>()
            val editor = inner.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(
                    key: String,
                    value: String?,
                ): SharedPreferences.Editor = apply { changes[key] = value }

                override fun remove(key: String): SharedPreferences.Editor = apply { changes[key] = null }

                override fun commit(): Boolean {
                    if (!writable) return false
                    val out = inner.edit()
                    changes.forEach { (k, v) -> if (v == null) out.remove(k) else out.putString(k, v) }
                    val ok = out.commit()
                    if (ok) afterCommit(LinkedHashMap(changes))
                    return ok
                }

                override fun apply() {
                    commit()
                }
            }
        }
    }

    private inner class Rig(
        name: String,
    ) {
        val raw: SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE).apply { edit().clear().commit() }
        val prefs = HookedPreferences(raw)
        val cipher = CountingCipher()
        val tokens = KeystoreSessionTokens(EncryptedPreferences(context, cipher, backing = prefs))
        val settled = AtomicBoolean(true)
        val barrier = CredentialReadBarrier()
        val inFlight = InFlightCalls()
        val pending = PendingKeyStore(MemoryPendingKeyBackend()) { 0L }
        val api =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
                baseUrl = { if (settled.get()) server.url("/").toString() else null },
                context = { NetworkContext(PrivacyRaw.FULL, settled.get(), true, UpdateRaw.SUPPORTED) },
                device =
                    object : DeviceCredentials {
                        override val deviceId: String = "device-fixture"
                        override val installSecret: String = "test-only.install.device-a"
                    },
                tokens = tokens,
                pendingKeys = pending,
                http = OkHttpClient(),
                sleeper = {},
                retryPolicy = { RetryPolicy.forOperation(it).copy(timeoutMillis = 3_000) },
                inFlight = inFlight,
                readBarrier = barrier,
            )

        init {
            tokens.save("access-1", "refresh-1", SessionScopeRaw.FULL, "user-a")
        }

        /** 与 AppModule 的切环境监听同一做法：屏障里作废在途、最后发布「环境未就绪」；清理器不运行。 */
        fun publishSwitching() = barrier.raise(beforePublish = { inFlight.onEnvironmentSwitching() }) { settled.set(false) }

        /** 一笔结果未知的提现（连接在写出后断开）：user-a 名下的未决记录。 */
        fun unknownWithdrawal(): String {
            val failure = api.execute(ApiCall("createWithdrawal", body = """{"amount_fen":1000}""")) as ApiResult.TransportFailure
            assertTrue(failure.pendingKeyUnknown)
            return checkNotNull(failure.idempotencyKey)
        }

        fun resetCounts() {
            cipher.decrypts.set(0)
            requests.set(0)
        }
    }

    /** 点击时的归属：此刻（切换已发布之后渲染的待确认卡）的隐私 / 环境世代，确认 / 放弃不因归属失效被 call_cancelled 挡在前面。 */
    private fun Rig.ownership() = CallOwnership(inFlight.epochs(), stillOwned = { true })

    private fun Rig.expectRejected(
        key: String,
        reason: String,
        record: Boolean,
    ) {
        val target = PendingTarget("user-a", key, ownership())
        assertEquals(ApiResult.LocalReject(reason), api.confirmPending("createWithdrawal", null, target))
        assertEquals(ApiResult.LocalReject(reason), api.abandonPending("createWithdrawal", target))
        if (record) {
            assertEquals(key, api.pendingFor("createWithdrawal")?.key)
        } else {
            assertNull("pendingFor 不返回旧记录", api.pendingFor("createWithdrawal"))
        }
        assertEquals(PendingKeyStore.UNKNOWN, pending.state(key, "user-a").stateRaw)
        assertEquals("令牌零解密", 0, cipher.decrypts.get())
        assertEquals("零发送", 0, requests.get())
    }

    @Test
    fun fand33_refresh_saved_then_switch_before_replay_keeps_environment_unsettled_zero_reads() {
        val rig = Rig("fuand0715-f33-refresh")
        val key = rig.unknownWithdrawal()
        val generation = rig.tokens.generation
        val saved = AtomicInteger()
        rig.prefs.afterCommit = { changes ->
            // 刷新写入（换令牌、不动登录世代）落盘之后、回到 ApiClient 之前：按 App 接线切环境
            if (changes[KeystoreSessionTokens.KEY_ACCESS] != null && KeystoreSessionTokens.KEY_LOGIN_EPOCH !in changes) {
                saved.incrementAndGet()
                rig.publishSwitching()
                rig.resetCounts()
            }
        }
        meExpired.set(true)
        val result = rig.api.execute(ApiCall("getMe"))
        assertEquals("刷新写入只一次", 1, saved.get())
        assertEquals(1, rig.api.refreshCoordinator.refreshCalls)
        assertFalse("原请求重放被环境闸门拦下：$result", result is ApiResult.Success)
        assertNotEquals("刷新让会话世代前进", generation, rig.tokens.generation)
        rig.expectRejected(key, LocalRejectReason.ENVIRONMENT_UNSETTLED, record = true)
        // 最后直接核对盘上：新令牌已落盘
        rig.settled.set(true)
        assertEquals("access-2", rig.tokens.accessToken)
    }

    private fun corruptAfterBinding(
        name: String,
        deleteFails: Boolean,
        revokedMark: Boolean,
    ) {
        val rig = Rig(name)
        val key = rig.unknownWithdrawal()
        assertEquals("已绑定 user-a", "user-a", rig.api.activeUserId())
        val generation = rig.tokens.generation
        if (revokedMark) {
            // 落盘的撤销标记（上一次清除的删除没落盘）
            rig.raw
                .edit()
                .putString(KeystoreSessionTokens.KEY_REVOKED, "x")
                .commit()
        } else {
            rig.raw
                .edit()
                .putString(KeystoreSessionTokens.KEY_SCOPE, "%%%not-base64%%%")
                .commit()
        }
        rig.prefs.writable = !deleteFails
        // ApiClient 之外的读取点（缓存归属读持久化登录世代）先发现：令牌存储自己丢弃会话
        assertNull(rig.tokens.persistedLoginEpoch())
        assertNotEquals("令牌存储自己丢弃会话：会话世代推进", generation, rig.tokens.generation)
        rig.publishSwitching()
        rig.resetCounts()
        rig.expectRejected(key, LocalRejectReason.PENDING_KEY_UNRESOLVED, record = false)
    }

    @Test
    fun fand33_corrupt_session_deleted_by_token_store_then_switch_classifies_as_guest_zero_reads() =
        corruptAfterBinding("fuand0715-f33-corrupt", deleteFails = false, revokedMark = false)

    @Test
    fun fand33_corrupt_session_whose_delete_fails_then_switch_classifies_as_guest_zero_reads() =
        corruptAfterBinding("fuand0715-f33-corrupt-stuck", deleteFails = true, revokedMark = false)

    @Test
    fun fand33_revoked_by_mark_on_read_then_switch_classifies_as_guest_zero_reads() =
        corruptAfterBinding("fuand0715-f33-revoked", deleteFails = false, revokedMark = true)

    @Test
    fun fand33_self_discard_notifies_once_and_a_new_login_binds_again() {
        val rig = Rig("fuand0715-f33-notify")
        val notified = AtomicInteger()
        val tokens = KeystoreSessionTokens(EncryptedPreferences(context, CountingCipher(), backing = rig.prefs))
        tokens.onSelfDiscarded { notified.incrementAndGet() }
        rig.raw
            .edit()
            .putString(KeystoreSessionTokens.KEY_SCOPE, "BROKEN")
            .commit()
        rig.prefs.writable = false
        val before = tokens.generation
        repeat(3) { assertNull(tokens.accessToken) }
        assertEquals("同一份会话只通知、推进一次", 1, notified.get())
        assertEquals(before + 1, tokens.generation)
        rig.prefs.writable = true
        tokens.save("access-9", "refresh-9", SessionScopeRaw.FULL, "user-b")
        assertNotNull(tokens.persistedLoginEpoch())
        assertEquals("access-9", tokens.accessToken)
        assertEquals(1, notified.get())
    }
}
