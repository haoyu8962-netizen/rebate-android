package com.couli.core.network

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 任务书第 2 处；03 §4.2 第六条：「与清会话同一口径——会话世代加一并通知界面转为未登录」。
 * 真实 KeystoreSessionTokens → ApiClient → NetworkHooks.onSessionStored（AppModule 接 AppLogin 的现有路径）。
 * 不用自建通知替身代替 ApiClient 接线；回调里的观测只存数据，断言放外面，避免被 discard 的 runCatching 吞掉。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SessionCorruptionIdentityGapsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val raw get() = context.getSharedPreferences("identity-gaps-session", Context.MODE_PRIVATE)
    private lateinit var prefs: FailingPreferences
    private lateinit var tokens: KeystoreSessionTokens
    private lateinit var client: ApiClient
    private lateinit var sessionLock: Any
    private val notices: MutableList<Notice> = Collections.synchronizedList(mutableListOf())

    @Before
    fun start() {
        raw.edit().clear().commit()
        prefs = FailingPreferences(raw)
        tokens = KeystoreSessionTokens(EncryptedPreferences(context, TestCipher, backing = prefs))
        tokens.save("access-A", "refresh-A", SessionScopeRaw.FULL, "account-A")
        // 精确检查持锁，而不是同线程重入（Java monitor 可重入，重读成功不能证明已释放锁）。
        sessionLock =
            KeystoreSessionTokens::class.java
                .getDeclaredField("lock")
                .apply { isAccessible = true }
                .get(tokens)
        client =
            ApiClient(
                identity = ClientIdentity("couli", "android", "1.0.0", "1", "official"),
                baseUrl = { null },
                context = { NetworkContext(PrivacyRaw.FULL, true, true, UpdateRaw.SUPPORTED) },
                device =
                    object : DeviceCredentials {
                        override val deviceId: String? = null
                        override val installSecret: String? = null
                    },
                tokens = tokens,
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
                hooks =
                    NetworkHooks(onSessionStored = { generation ->
                        val held = Thread.holdsLock(sessionLock)
                        val session = tokens.snapshot()
                        notices += Notice(generation, session.userId, session.accessToken, held)
                    }),
            )
        assertEquals("account-A", client.activeUserId())
    }

    // 第 2 处 / 第六条「会话世代加一并通知界面转为未登录，之后按确认游客处理」。
    @Test
    fun firstCorruptReadNotifiesExistingSessionHookAsLoggedOutOnce() {
        val generation = tokens.generation
        val epoch = tokens.loginEpoch
        corrupt()
        assertNull(client.activeUserId())
        assertEquals(generation + 1, tokens.generation)
        assertEquals(epoch + 1, tokens.loginEpoch)
        assertGuestNotices(1)
        assertEquals(tokens.generation, notices.single().generation)
    }

    // 第 2 处 / 第六条：缓存读取持久化登录世代也会先发现损坏，必须经同一出口在锁外通知。
    @Test
    fun corruptionFirstFoundByPersistedEpochReadAlsoNotifiesUiOutsideLock() {
        corrupt()
        assertNull(tokens.persistedLoginEpoch())
        assertGuestNotices(1)
        assertEquals(tokens.generation, notices.single().generation)
    }

    // 第 2 处 / 第六条「条目删不掉时后续读取不重复换代」且「只……通知一次」。
    @Test
    fun failedDeletionAndThreeReadsNotifyUiOnlyOnce() {
        corrupt()
        prefs.writable = false
        val generation = tokens.generation
        repeat(3) { assertNull(client.activeUserId()) }
        assertTrue("确实仍有删不掉的条目", raw.contains(KeystoreSessionTokens.KEY_SCOPE))
        assertEquals(generation + 1, tokens.generation)
        assertGuestNotices(1)
    }

    // 第 2 处 / 第六条「下一次成功写入会话后恢复」。
    @Test
    fun successfulNewSessionRearmsOneNotificationForTheNextCorruption() {
        corrupt()
        prefs.writable = false
        repeat(3) { tokens.readSession() }
        assertGuestNotices(1)
        prefs.writable = true
        tokens.save("access-B", "refresh-B", SessionScopeRaw.FULL, "account-B")
        assertEquals("account-B", client.activeUserId())
        val generation = tokens.generation
        corrupt()
        prefs.writable = false
        repeat(3) { tokens.readSession() }
        assertGuestNotices(2)
        assertEquals(generation + 1, tokens.generation)
        assertEquals(generation + 1, notices.last().generation)
    }

    // 第 2 处 / 第六条「通知不在持有会话读锁时回调界面」；独立钉存储回调，免得被缺通知遮住锁问题。
    @Test
    fun selfDiscardCallbackRunsOutsideSessionReadLock() {
        val held = mutableListOf<Boolean>()
        tokens.onSelfDiscarded { held += Thread.holdsLock(sessionLock) }
        corrupt()
        tokens.readSession()
        assertEquals(listOf(false), held)
    }

    // R1 S1-1 / 第五条：发送闸门之后的 admission 才发现损坏，换代后也必须零发送。
    @Test
    fun corruptionFirstDiscoveredByAdmissionAfterSendGateSendsNoPrivacyWithdrawal() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"code":0,"msg":"","data":null,"trace_id":"identity-gaps"}"""))
            val confirmedEpoch = tokens.loginEpoch
            val checkedEpochs = mutableListOf<Long>()
            val discardSites = mutableListOf<Boolean>()
            var corrupted = false
            configureAdmissionClient(server, discardSites)
            client.execute(
                ApiCall(
                    "recordConsent",
                    body =
                        """{"type":"privacy","version":3,"accepted":false,"channel":"privacy_center",""" +
                            """"client_at":"2026-11-16T10:00:00+08:00"}""",
                    ownership =
                        CallOwnership(
                            client.inFlight.epochs(),
                            sendAllowed = {
                                val epoch = tokens.loginEpoch
                                checkedEpochs += epoch
                                val allowed = epoch == confirmedEpoch
                                // 先按确认世代核对；只在首次闸门之后破坏磁盘条目，这里不读会话、不推进世代。
                                // ownerReject 与组头读取都已拿到正常 A；紧接着 admission 的 rawRead 才发现损坏。
                                if (!corrupted) {
                                    corrupt()
                                    corrupted = true
                                }
                                allowed
                            },
                        ) { true },
                    sendsWhenIdentityUnknown = true,
                ),
            )
            assertTrue("确实经过发送前闸门", checkedEpochs.isNotEmpty())
            assertEquals("首次发送闸门核对时仍是确认的登录世代", confirmedEpoch, checkedEpochs.first())
            assertEquals("首次损坏发现必须发生在 admission 内，且只通知一次", listOf(true), discardSites)
            assertEquals("准入读取发现损坏并推进登录世代", confirmedEpoch + 1, tokens.loginEpoch)
            assertNull("损坏后确实转为游客", tokens.snapshot().userId)
            assertEquals("admission 在闸门之后发现损坏换代：撤回 HTTP 必须零发送", 0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    // R1 S2-1 / 第六条：A 的通知尚未取走时保存 B、再损坏 B，两份会话各通知一次。
    @Test
    fun twoCorruptSessionsBeforeFirstReaderDeliversNotifyTwice() {
        val generation = tokens.generation
        val epoch = tokens.loginEpoch
        val firstReadUnlocked = CountDownLatch(1)
        val releaseFirstReader = CountDownLatch(1)
        val heldAtPause = AtomicBoolean(true)
        val reader = Executors.newSingleThreadExecutor()
        tokens.guardLoginEpochReads { read ->
            val persisted = read()
            // epochNow 已返回、会话锁已释放；persistedLoginEpoch 尚未进入 deliverDiscardNotice。
            heldAtPause.set(Thread.holdsLock(sessionLock))
            firstReadUnlocked.countDown()
            check(releaseFirstReader.await(5, TimeUnit.SECONDS)) { "等待释放 A 的读取线程超时" }
            persisted
        }
        corrupt()
        val firstRead = reader.submit<String?> { tokens.persistedLoginEpoch() }
        try {
            assertTrue("A 已发现损坏并暂停在送通知之前", firstReadUnlocked.await(5, TimeUnit.SECONDS))
            assertFalse("暂停点不得仍占着会话锁，否则 B 无法保存", heldAtPause.get())
            assertEquals(generation + 1, tokens.generation)
            assertEquals(epoch + 1, tokens.loginEpoch)
            assertEquals("A 的通知仍待送出", 0, notices.size)

            // 当前测试线程就是另一线程。save 成功返回才复位 ended；不能在这里 snapshot/activeUserId，
            // 否则那次读取会提前送走 A 的通知，破坏本用例要钉的交错。
            tokens.save("access-B", "refresh-B", SessionScopeRaw.FULL, "account-B")
            assertEquals(generation + 2, tokens.generation)
            assertEquals(epoch + 2, tokens.loginEpoch)
            corrupt()
            val secondRead = tokens.readSession()
            assertTrue("B 损坏后读取返回游客", secondRead is SessionRead.Present)
            assertNull((secondRead as SessionRead.Present).snapshot.userId)
            assertEquals("A 损坏、保存 B、B 损坏均已各推进一次", generation + 3, tokens.generation)
            assertEquals(epoch + 3, tokens.loginEpoch)

            releaseFirstReader.countDown()
            assertNull(firstRead.get(5, TimeUnit.SECONDS))
            // 两个读取入口都完成，再观察通知总数；当前布尔标记会把两次 true 合并为一次。
            assertGuestNotices(2)
        } finally {
            releaseFirstReader.countDown()
            reader.shutdownNow()
            assertTrue("读取线程必须退出", reader.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun configureAdmissionClient(
        server: MockWebServer,
        discardSites: MutableList<Boolean>,
    ) {
        client =
            ApiClient(
                identity = ClientIdentity("couli", "android", "1.0.0", "1", "official"),
                baseUrl = { server.url("/").toString() },
                context = { NetworkContext(PrivacyRaw.FULL, true, true, UpdateRaw.SUPPORTED) },
                device =
                    object : DeviceCredentials {
                        override val deviceId: String = "identity-gaps-device"
                        override val installSecret: String = "identity-gaps-install"
                    },
                tokens = tokens,
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
                hooks =
                    NetworkHooks(onSessionStored = {
                        // 回调只记录；断言放在 execute 返回后，不能被存储的 runCatching 吞掉。
                        discardSites +=
                            Thread.currentThread().stackTrace.any {
                                it.className == ApiClient::class.java.name && it.methodName == "admission"
                            }
                    }),
            )
    }

    private fun corrupt() {
        raw.edit().putString(KeystoreSessionTokens.KEY_SCOPE, "%%%not-base64%%%").commit()
    }

    private fun assertGuestNotices(expected: Int) {
        assertEquals("必须经现有 onSessionStored 通知界面", expected, notices.size)
        notices.forEach {
            assertNull("界面重读应得到未登录", it.account)
            assertNull(it.access)
            assertFalse("回调界面时不得持有会话读锁", it.lockHeld)
        }
    }

    private data class Notice(
        val generation: Long,
        val account: String?,
        val access: String?,
        val lockHeld: Boolean,
    )

    private object TestCipher : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain.map { it.toInt().inv().toByte() }.toByteArray()

        override fun decrypt(sealed: ByteArray): ByteArray = encrypt(sealed)
    }

    /** 沿用 FollowupAnd0715BindingTest.HookedPreferences：失败的 commit 不碰实际存储。 */
    private class FailingPreferences(
        private val inner: SharedPreferences,
    ) : SharedPreferences by inner {
        var writable = true

        override fun edit(): SharedPreferences.Editor {
            val editor = inner.edit()
            return object : SharedPreferences.Editor by editor {
                override fun commit(): Boolean = writable && editor.commit()

                override fun apply() {
                    commit()
                }
            }
        }
    }
}
