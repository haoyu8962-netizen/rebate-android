package com.couli.app.followup

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.home.homeLkgStore
import com.couli.app.orderCacheStore
import com.couli.app.orderRestoreStore
import com.couli.app.persistedLoginEpochOf
import com.couli.app.walletCacheStore
import com.couli.core.home.HomeLkgScope
import com.couli.core.home.HomeLkgStore
import com.couli.core.network.ApiClient
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.EncryptedPreferences
import com.couli.core.network.KeystoreSessionTokens
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.SecretCipher
import com.couli.core.network.SessionScopeRaw
import com.couli.core.network.UpdateRaw
import com.couli.core.orders.OrderListData
import com.couli.core.orders.OrderRestoreRecord
import com.couli.core.orders.OrderSummary
import com.couli.core.wallet.WalletSummary
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FU-AND-0714 第 1 轮评审 R1-02、R1-07：持久化登录世代与会话可用性共用状态，并覆盖到首页 LKG。
 * - R1-02：真实 ApiClient 清会话时删除提交失败（会话被隔离，加密存储里仍是旧值）→ persistedLoginEpoch() 同步为 null，订单缓存、钱包摘要缓存、
 *   订单页恢复记录在隔离期间一律读不出；成功登录（新会话）后恢复，旧世代的数据仍读不回。
 * - R1-07：会员首页 LKG 按持久化登录世代分区（作用域目录与 HomeLkgScope 向量不变）：待清理标记与删除都失败后，同账号重新登录、进程重建都读不回；
 *   世代未知时不读不写。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class FollowupAnd0714FixR1EpochTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private object NoCipher : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain

        override fun decrypt(sealed: ByteArray): ByteArray = sealed
    }

    /** 能模拟「提交失败」的 SharedPreferences：失败时 commit 返回 false、什么都不改（读到的仍是旧值）。 */
    private class FailingPreferences(
        private val inner: SharedPreferences,
    ) : SharedPreferences by inner {
        @Volatile var failing = false

        override fun edit(): SharedPreferences.Editor {
            val editor = inner.edit()
            return object : SharedPreferences.Editor by editor {
                override fun commit(): Boolean = if (failing) false else editor.commit()

                override fun putString(
                    key: String?,
                    value: String?,
                ): SharedPreferences.Editor = apply { editor.putString(key, value) }

                override fun remove(key: String?): SharedPreferences.Editor = apply { editor.remove(key) }
            }
        }
    }

    private object Device : DeviceCredentials {
        override val deviceId: String = "device-fixture"
        override val installSecret: String = "test-only.install.device-a"
    }

    private fun prefs(name: String) = FailingPreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE))

    private fun tokens(prefs: SharedPreferences) = KeystoreSessionTokens(EncryptedPreferences(context, NoCipher, backing = prefs))

    private fun client(tokens: KeystoreSessionTokens) =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
            baseUrl = { "https://api.example.test/" },
            context = { NetworkContext(PrivacyRaw.FULL, true, true, UpdateRaw.SUPPORTED) },
            device = Device,
            tokens = tokens,
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            sleeper = {},
        )

    private fun item(id: String) =
        OrderSummary(id, "taobao", "样例商品", null, 4560, 1, "3741234567890123456", null, "PAID", null, 234, null, false)

    private val summary = WalletSummary(1234, 1234, 0, 0, 5678, 2222, 3456, 456, "2026-10", false, 500, null, true)

    @Test
    fun r1_02_api_client_clear_commit_failure_quarantines_and_login_epoch_is_null_until_a_new_session_is_saved() {
        val prefs = prefs("fuand0714-r1-02-session")
        val tokens = tokens(prefs)
        tokens.save("access-1", "refresh-1", SessionScopeRaw.FULL, "user-a")
        val api = client(tokens)
        assertEquals("user-a", api.activeUserId())
        val first = tokens.persistedLoginEpoch()
        assertNotNull(first)
        val orders = orderCacheStore(context, tokens)
        val wallet = walletCacheStore(context, tokens)
        val restore = orderRestoreStore(context, tokens)
        orders.writeList("user-a", "self", OrderListData(listOf(item("o1")), null, null), 1L)
        wallet.write("user-a", summary, 1L)
        val ref = restore.newRef()
        val record = OrderRestoreRecord(owner = "owner-a", q = "3741234567890123456", input = "3741234567890123456")
        assertTrue(restore.write(ref, restore.epoch, record, restore.loginEpochNow()))
        assertNotNull(orders.readList("user-a", "self"))

        // 真实 ApiClient 清会话：删除提交失败 → 会话隔离（加密存储里仍是旧令牌与旧世代）
        prefs.failing = true
        api.clearSessionIf(tokens.loginEpoch)
        assertEquals("存储里旧值仍在（删除没落盘）", "access-1", tokens.accessToken)
        assertNull("ApiClient 按隔离：不再交出旧会话", api.activeUserId())
        assertNull("登录世代与会话可用性同步：隔离期间为 null", tokens.persistedLoginEpoch())
        assertNull("订单缓存隔离期间禁止读", orderCacheStore(context).readList("user-a", "self"))
        assertNull("钱包摘要缓存隔离期间禁止读", walletCacheStore(context).read("user-a"))
        assertNull("订单页恢复记录隔离期间禁止读", orderRestoreStore(context).read(ref))
        assertNull(persistedLoginEpochOf(tokens)())

        // 存储恢复正常、同账号重新登录（新会话落盘）：世代恢复且换新，旧世代的数据仍读不回
        prefs.failing = false
        tokens.save("access-2", "refresh-2", SessionScopeRaw.FULL, "user-a")
        val second = tokens.persistedLoginEpoch()
        assertNotNull("新会话成功建立后恢复", second)
        assertNotEquals(first, second)
        assertNull(orderCacheStore(context).readList("user-a", "self"))
        assertNull(walletCacheStore(context).read("user-a"))
        assertNull(orderRestoreStore(context).read(ref))
        orderCacheStore(context).writeList("user-a", "self", OrderListData(listOf(item("o2")), null, null), 2L)
        assertNotNull("新世代照常读写", orderCacheStore(context).readList("user-a", "self"))
    }

    @Test
    fun r1_02_refresh_write_failure_also_withholds_the_login_epoch() {
        val prefs = prefs("fuand0714-r1-02-refresh")
        val tokens = tokens(prefs)
        tokens.save("access-1", "refresh-1", SessionScopeRaw.FULL, "user-a")
        assertNotNull(tokens.persistedLoginEpoch())
        prefs.failing = true
        assertTrue(runCatching { tokens.replaceIf(tokens.generation, "access-2", "refresh-2", SessionScopeRaw.FULL) }.isFailure)
        assertNull("刷新写入失败（ApiClient 随之隔离）：世代不可用", tokens.persistedLoginEpoch())
        prefs.failing = false
        assertTrue(runCatching { tokens.replaceIf(tokens.generation, "access-3", "refresh-3", SessionScopeRaw.FULL) }.isSuccess)
        assertNull("刷新成功不算新会话：仍不可用", tokens.persistedLoginEpoch())
        tokens.save("access-4", "refresh-4", SessionScopeRaw.FULL, "user-a")
        assertNotNull(tokens.persistedLoginEpoch())
    }

    // ---------------------------------------------------------------- R1-07 首页 LKG

    private val scope = HomeLkgScope.of("member", "user-a", "test")

    /** 待清理标记写不进、删除也失败（三者同时故障）；[failing] 关掉后恢复。 */
    private inner class Faults {
        var failing = true
        val entries = mutableSetOf<String>()

        val flag =
            object : HomeLkgStore.PendingFlag {
                override fun entries(): Set<String>? = if (failing) null else entries.toSet()

                override fun add(entry: String): Boolean = !failing && entries.add(entry).let { true }

                override fun remove(entry: String): Boolean = !failing && entries.remove(entry).let { true }
            }

        fun store(root: File) = HomeLkgStore(root, flag, deleter = { dir -> !failing && dir.deleteRecursively() && !dir.exists() })
    }

    @Test
    fun r1_07_member_home_lkg_is_partitioned_by_login_epoch_cleanup_faults_relogin_and_rebuild_never_read_back() {
        val prefs = prefs("fuand0714-r1-07-session")
        val tokens = tokens(prefs)
        tokens.save("access-1", "refresh-1", SessionScopeRaw.FULL, "user-a")
        val root = File(context.noBackupFilesDir, "fuand0714-r1-07-lkg").apply { deleteRecursively() }
        val faults = Faults().apply { failing = false }
        val store = faults.store(root).apply { bindLoginEpoch(persistedLoginEpochOf(tokens)) }
        assertTrue(store.write(store.ticket(scope), """{"page":"old"}"""))
        assertEquals("""{"page":"old"}""", store.read(scope))
        assertTrue("作用域目录名不变（向量）", File(root, HomeLkgStore.digest(scope)).isDirectory)
        val guest = HomeLkgScope.of("guest", null, "test")
        assertTrue(store.write(store.ticket(guest), """{"page":"guest"}"""))

        // 退出登录：作用域清理时标记写不进、删除也失败（目录外标记、删除同时故障）
        faults.failing = true
        assertTrue(runCatching { store.clearScope(scope) }.isFailure)
        assertNull("本进程内阻断", store.read(scope))
        tokens.clear()
        assertNull("世代未知：不读", faults.store(root).apply { bindLoginEpoch(persistedLoginEpochOf(tokens)) }.read(scope))

        // 同账号重新登录 + 进程重建（内存阻断消失、目录外标记没写进）：旧页面仍在盘上，但属于旧世代，读不回
        faults.failing = false
        tokens.save("access-2", "refresh-2", SessionScopeRaw.FULL, "user-a")
        val rebuilt = faults.store(root).apply { bindLoginEpoch(persistedLoginEpochOf(tokens)) }
        assertTrue("旧页面确实还在盘上", File(root, HomeLkgStore.digest(scope)).walkTopDown().any { it.name == "page.json" })
        assertNull("同账号重登、进程重建都读不回旧世代的 LKG", rebuilt.read(scope))
        assertEquals("游客作用域不分世代（原口径）", """{"page":"guest"}""", rebuilt.read(guest))

        // 新世代照常写读；取票之后再换世代的旧写入不落进新世代
        val ticket = rebuilt.ticket(scope)
        assertTrue(rebuilt.write(ticket, """{"page":"new"}"""))
        assertEquals("""{"page":"new"}""", rebuilt.read(scope))
        tokens.save("access-3", "refresh-3", SessionScopeRaw.FULL, "user-a")
        assertTrue("旧票据（旧世代）写不进", !rebuilt.write(ticket, """{"page":"late"}"""))
        assertNull(rebuilt.read(scope))
        root.deleteRecursively()
    }

    @Test
    fun r1_07_app_home_store_binds_the_token_store_login_epoch() {
        val prefs = prefs("fuand0714-r1-07-wiring")
        val tokens = tokens(prefs)
        tokens.save("access-1", "refresh-1", SessionScopeRaw.FULL, "user-a")
        // 清理入口先建出实例（不带令牌），首页取实例时接上
        homeLkgStore(context)
        val store = homeLkgStore(context, tokens)
        assertTrue(store.write(store.ticket(scope), """{"page":"a"}"""))
        assertEquals("""{"page":"a"}""", homeLkgStore(context).read(scope))
        // 删除提交失败（会话隔离）：世代不可用 → 不读
        prefs.failing = true
        runCatching { tokens.clear() }
        assertNull(homeLkgStore(context).read(scope))
        prefs.failing = false
        tokens.save("access-2", "refresh-2", SessionScopeRaw.FULL, "user-a")
        assertNull("同账号重新登录：旧世代的 LKG 读不回", homeLkgStore(context).read(scope))
        homeLkgStore(context).clearAll()
    }
}
