package com.couli.core.network

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
 * FU-AND-0714 第 2 轮评审（令牌存储与网络层部分）：
 * - R2-01（S0）：刷新提交期间作废网络并发布「环境未就绪 / 撤回待清理」，随后提交失败——ApiClient 的隔离与隔离重试（[ApiClient] retryQuarantineClear，
 *   刷新写入失败后的直接入口）都经读取屏障现判：从发布那一刻起**零解密读取**；持久化登录世代的读取同样经屏障（按未知、不读）。
 * - R2-02：持久化登录世代与整份会话可用性同口径：单个会话字段解密暂时失败、session_scope 损坏且删除提交失败 → 世代为 null。
 * - R2-03：删除提交失败后的撤销跨进程：落盘的撤销标记让重建的令牌存储不交出旧会话、世代为 null，直到新会话落盘。
 * 全部用真实 [KeystoreSessionTokens] + [EncryptedPreferences]（内存加解密、可控提交的 SharedPreferences）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class FollowupAnd0714FixR2SessionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private val pool = Executors.newCachedThreadPool()
    private val firstCall = AtomicBoolean(true)

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.path) {
                        "/v1/auth/refresh" -> {
                            envelope(0, """{"access_token":"access-2","refresh_token":"refresh-2","session_scope":"full"}""")
                        }

                        else -> {
                            envelope(if (firstCall.getAndSet(false)) 10002 else 0, "{}")
                        }
                    }
            }
        server.start()
    }

    @After
    fun stop() {
        pool.shutdownNow()
        server.shutdown()
    }

    /** 可逆的假加密；解密计数（[published] 之后单记），明文落在 [failPlain] 里的按 Keystore 暂时不可用抛错。 */
    private class CountingCipher : SecretCipher {
        val published = AtomicBoolean(false)
        val afterPublish = AtomicInteger()
        val failPlain = mutableSetOf<String>()

        override fun encrypt(plain: ByteArray): ByteArray = plain.map { it.toInt().inv().toByte() }.toByteArray()

        override fun decrypt(sealed: ByteArray): ByteArray {
            if (published.get()) afterPublish.incrementAndGet()
            val plain = sealed.map { it.toInt().inv().toByte() }.toByteArray()
            if (plain.toString(Charsets.UTF_8) in failPlain) throw SecureStorageReadException("keystore busy")
            return plain
        }
    }

    /**
     * 提交可控的 SharedPreferences：[decide] 对每次提交的改动（键 → 新的已加密值，null 为删除）给出是否成功；失败时什么都不改（盘上仍是旧值），
     * 成功时整组写入底层（Robolectric 的真实 SharedPreferences，进程重建用同一个底层模拟「盘」）。
     */
    private class ControlledPreferences(
        private val inner: SharedPreferences,
    ) : SharedPreferences by inner {
        @Volatile var decide: (Map<String, String?>) -> Boolean = { true }

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
                    if (!decide(LinkedHashMap(changes))) return false
                    val out = inner.edit()
                    changes.forEach { (k, v) -> if (v == null) out.remove(k) else out.putString(k, v) }
                    return out.commit()
                }

                override fun apply() {
                    commit()
                }
            }
        }
    }

    private fun prefs(name: String) =
        ControlledPreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE).apply { edit().clear().commit() })

    private var serial = 0

    private fun tokens(
        prefs: SharedPreferences,
        cipher: SecretCipher,
    ) = KeystoreSessionTokens(EncryptedPreferences(context, cipher, backing = prefs)) { "epoch-${++serial}" }

    private class FixedDevice : DeviceCredentials {
        override val deviceId: String? = "device-fixture"
        override val installSecret: String? = "test-only.install.device-a"
    }

    private fun envelope(
        code: Int,
        data: String = "null",
    ) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"trace-0123456789"}""")

    // ---------------------------------------------------------------- R2-01

    /**
     * getMe 收到 10002 → 单飞刷新 → 写入新令牌的提交停住；期间按 App 的接线竖屏障（先关内部闸、等已准入读取 → 网络作废 → 最后发布 [publish]），
     * 然后让提交失败（刷新写入失败 → 隔离 → 隔离重试）。从发布那一刻起计数解密。
     */
    private fun refreshCommitFailsAcross(
        name: String,
        invalidate: (InFlightCalls) -> Unit,
        publish: (settled: AtomicBoolean, readable: AtomicBoolean) -> Unit,
    ) {
        val prefs = prefs(name)
        val cipher = CountingCipher()
        val store = tokens(prefs, cipher)
        store.save("access-1", "refresh-1", SessionScopeRaw.FULL, "user-a")
        val settled = AtomicBoolean(true)
        val readable = AtomicBoolean(true)
        val barrier = CredentialReadBarrier()
        val inFlight = InFlightCalls()
        val api =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
                baseUrl = { server.url("/").toString() },
                context = { NetworkContext(PrivacyRaw.FULL, settled.get(), readable.get(), UpdateRaw.SUPPORTED) },
                device = FixedDevice(),
                tokens = store,
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
                http = OkHttpClient(),
                sleeper = {},
                retryPolicy = { RetryPolicy.forOperation(it).copy(timeoutMillis = 2_000) },
                inFlight = inFlight,
                readBarrier = barrier,
            )
        assertNotNull("前提：世代可读", store.persistedLoginEpoch())
        val committing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val armed = AtomicBoolean(true)
        prefs.decide = { changes ->
            // 刷新写入（有 access_token、没有登录世代）：停住，放行后提交失败
            if (changes[KeystoreSessionTokens.KEY_ACCESS] != null &&
                KeystoreSessionTokens.KEY_LOGIN_EPOCH !in changes &&
                armed.compareAndSet(true, false)
            ) {
                committing.countDown()
                release.await(WAIT_S, TimeUnit.SECONDS)
                false
            } else {
                true
            }
        }
        val call = pool.submit<ApiResult> { api.execute(ApiCall("getMe")) }
        assertTrue("刷新写入应停在提交中", committing.await(WAIT_S, TimeUnit.SECONDS))
        // 刷新提交期间：屏障里作废网络、最后发布（环境未就绪 / 撤回待清理）
        val raised =
            pool.submit {
                barrier.raise(beforePublish = { invalidate(inFlight) }) {
                    publish(settled, readable)
                    cipher.published.set(true)
                }
            }
        raised.get(WAIT_S, TimeUnit.SECONDS)
        assertTrue(cipher.published.get())
        // 提交失败：刷新写入失败 → 隔离 → 隔离重试（直接入口）
        release.countDown()
        val result = call.get(WAIT_S, TimeUnit.SECONDS)
        assertFalse("最终请求不成功：$result", result is ApiResult.Success)
        assertEquals("从发布的那一刻起：令牌存储零解密读取（隔离与隔离重试都经屏障）", 0, cipher.afterPublish.get())
        // 发布之后的其他入口：登录世代（缓存归属）、新请求、账号查询，同样不读
        assertNull("登录世代读取经屏障：按未知", store.persistedLoginEpoch())
        api.execute(ApiCall("getMe"))
        assertNull(api.activeUserId())
        assertEquals("发布之后的任何入口都不解密", 0, cipher.afterPublish.get())
    }

    @Test
    fun r2_01_refresh_commit_failure_interleaved_with_environment_switch_reads_no_tokens_after_publish() =
        refreshCommitFailsAcross("fuand0714-r2-01-switch", InFlightCalls::onEnvironmentSwitching) { settled, _ -> settled.set(false) }

    @Test
    fun r2_01_refresh_commit_failure_interleaved_with_withdrawal_reads_no_tokens_after_publish() =
        refreshCommitFailsAcross("fuand0714-r2-01-withdraw", InFlightCalls::onPrivacyRevoked) { _, readable -> readable.set(false) }

    // ---------------------------------------------------------------- R2-02

    @Test
    fun r2_02_single_session_field_read_failure_makes_the_login_epoch_unknown_until_it_reads_again() {
        val cipher = CountingCipher()
        val store = tokens(prefs("fuand0714-r2-02-field"), cipher)
        store.save("access-1", "refresh-1", SessionScopeRaw.FULL, "user-a")
        val epoch = store.persistedLoginEpoch()
        assertNotNull(epoch)
        // 只有 access_token 解密暂时失败（世代键本身读得出）
        cipher.failPlain += "access-1"
        assertTrue("整份会话：读不出", store.readSession() is SessionRead.Unreadable)
        assertNull("任一会话字段读不出：世代未知（四类缓存不读）", store.persistedLoginEpoch())
        cipher.failPlain.clear()
        assertEquals("读取恢复（不是清除、不是隔离）：同一个世代", epoch, store.persistedLoginEpoch())
    }

    @Test
    fun r2_02_corrupt_session_scope_whose_delete_commit_fails_keeps_the_login_epoch_unknown() {
        val prefs = prefs("fuand0714-r2-02-corrupt")
        val store = tokens(prefs, CountingCipher())
        store.save("access-1", "refresh-1", SessionScopeRaw.FULL, "user-a")
        val epoch = store.persistedLoginEpoch()
        assertNotNull(epoch)
        // session_scope 损坏（不是合法 Base64），并且删除提交失败
        context
            .getSharedPreferences("fuand0714-r2-02-corrupt", Context.MODE_PRIVATE)
            .edit()
            .putString(KeystoreSessionTokens.KEY_SCOPE, "%%%not-base64%%%")
            .commit()
        prefs.decide = { false }
        val read = store.readSession() as SessionRead.Present
        assertNull("损坏：整组不能用", read.snapshot.accessToken)
        assertNull("损坏且删除失败：世代未知", store.persistedLoginEpoch())
        assertNull("再读一次（删除仍失败）：仍未知", store.persistedLoginEpoch())
        // 存储恢复：本进程仍禁用（直到新会话落盘）；重建的存储重试删除后也没有会话、没有世代
        prefs.decide = { true }
        assertNull(store.persistedLoginEpoch())
        assertNull("重建：损坏的会话被删掉", tokens(prefs, CountingCipher()).persistedLoginEpoch())
        store.save("access-3", "refresh-3", SessionScopeRaw.FULL, "user-a")
        val renewed = store.persistedLoginEpoch()
        assertNotNull(renewed)
        assertNotEquals(epoch, renewed)
    }

    // ---------------------------------------------------------------- R2-03

    @Test
    fun r2_03_clear_whose_delete_commit_fails_stays_revoked_across_process_rebuild_until_a_new_login() {
        val prefs = prefs("fuand0714-r2-03-session")
        val store = tokens(prefs, CountingCipher())
        store.save("access-1", "refresh-1", SessionScopeRaw.FULL, "user-a")
        val epoch = store.persistedLoginEpoch()
        assertNotNull(epoch)
        // 删除提交失败（删 access_token 的提交一律失败；其余提交照常）
        prefs.decide = { changes ->
            val removesAccess = KeystoreSessionTokens.KEY_ACCESS in changes && changes[KeystoreSessionTokens.KEY_ACCESS] == null
            !removesAccess
        }
        assertTrue(runCatching { store.clear() }.isFailure)
        val disk = context.getSharedPreferences("fuand0714-r2-03-session", Context.MODE_PRIVATE)
        assertNotNull("前提：旧令牌仍在盘上", disk.getString(KeystoreSessionTokens.KEY_ACCESS, null))
        assertNotNull("前提：旧世代仍在盘上", disk.getString(KeystoreSessionTokens.KEY_LOGIN_EPOCH, null))
        assertNull(store.persistedLoginEpoch())

        // 未重新登录即重建进程（删除仍失败）：不交出旧会话，世代为 null
        val rebuilt = tokens(prefs, CountingCipher())
        assertNull("重建后旧会话不交出", rebuilt.accessToken)
        assertNull("重建后世代为 null（四类缓存不读）", rebuilt.persistedLoginEpoch())
        assertNotNull("删除仍失败：旧令牌还在盘上", disk.getString(KeystoreSessionTokens.KEY_ACCESS, null))

        // 删除恢复：重建的存储读一次就清干净（连同标记）；之后新登录换新世代
        prefs.decide = { true }
        assertNull(tokens(prefs, CountingCipher()).accessToken)
        assertNull("撤销完成：盘上没有旧令牌", disk.getString(KeystoreSessionTokens.KEY_ACCESS, null))
        assertNull(disk.getString(KeystoreSessionTokens.KEY_REVOKED, null))
        val again = tokens(prefs, CountingCipher())
        again.save("access-2", "refresh-2", SessionScopeRaw.FULL, "user-a")
        val renewed = tokens(prefs, CountingCipher()).persistedLoginEpoch()
        assertNotNull(renewed)
        assertNotEquals(epoch, renewed)
    }

    private companion object {
        const val WAIT_S = 10L
    }
}
