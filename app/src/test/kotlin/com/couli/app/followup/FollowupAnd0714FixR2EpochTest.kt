package com.couli.app.followup

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.orderCachePendingFlag
import com.couli.app.orderCacheStore
import com.couli.app.orderRestorePendingFlag
import com.couli.app.orderRestoreStore
import com.couli.app.orders.AppOrders
import com.couli.app.persistedLoginEpochOf
import com.couli.app.wallet.AppWallet
import com.couli.app.walletCachePendingFlag
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
import com.couli.core.orders.OrderRestoreStore
import com.couli.core.orders.OrderStaleCache
import com.couli.core.orders.OrderSummary
import com.couli.core.wallet.WalletStaleCache
import com.couli.core.wallet.WalletSummary
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FU-AND-0714 第 2 轮评审 R2-03（App 接线）：令牌删除与四类缓存清理同时失败、**未重新登录即重建进程**——重建的令牌存储从落盘的撤销标记得知
 * 这份会话已撤销：不交出旧令牌、持久化登录世代为 null，订单缓存、钱包摘要缓存、订单页恢复记录、会员首页 LKG（旧数据仍在盘上）一律读不回；
 * 之后同账号重新登录（新世代）同样读不回。清会话经真实 ApiClient（clearSessionIf）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class FollowupAnd0714FixR2EpochTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private object NoCipher : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain

        override fun decrypt(sealed: ByteArray): ByteArray = sealed
    }

    /** 删除令牌的提交失败（[failRemovals] 时：改动里删 access_token 的提交返回 false、什么都不改）；其余提交照常。 */
    private class RemovalFailingPreferences(
        private val inner: SharedPreferences,
    ) : SharedPreferences by inner {
        @Volatile var failRemovals = false

        override fun edit(): SharedPreferences.Editor {
            val editor = inner.edit()
            var removesAccess = false
            return object : SharedPreferences.Editor by editor {
                override fun putString(
                    key: String?,
                    value: String?,
                ): SharedPreferences.Editor = apply { editor.putString(key, value) }

                override fun remove(key: String?): SharedPreferences.Editor =
                    apply {
                        if (key == KeystoreSessionTokens.KEY_ACCESS) removesAccess = true
                        editor.remove(key)
                    }

                override fun commit(): Boolean = if (failRemovals && removesAccess) false else editor.commit()
            }
        }
    }

    private object Device : DeviceCredentials {
        override val deviceId: String = "device-fixture"
        override val installSecret: String = "test-only.install.device-a"
    }

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
    private val scope = HomeLkgScope.of("member", "user-a", "test")

    /** 模拟进程重建：按同一份盘面新建四类存储（内存里的待清理与阻断都没了），接上 [tokens] 的持久化登录世代。 */
    private inner class Rebuilt(
        tokens: KeystoreSessionTokens,
        homeRoot: File,
    ) {
        private val source = persistedLoginEpochOf(tokens)

        // FU-AND-0717（F-X-17）：缓存按调用方给出的固定世代分区（这里取重建那一刻令牌存储的世代，等同原来的来源）
        val epoch: String? = source()
        val orders = OrderStaleCache.Store(AppOrders.cacheRoot(context), orderCachePendingFlag(context)).apply { partitionByLoginEpoch() }
        val wallet = WalletStaleCache.Store(AppWallet.cacheRoot(context), walletCachePendingFlag(context)).apply { partitionByLoginEpoch() }
        val restore = OrderRestoreStore(AppOrders.restoreRoot(context), orderRestorePendingFlag(context)).apply { bindLoginEpoch(source) }
        val home = HomeLkgStore(homeRoot, NoFlag, deleter = { false }).apply { bindLoginEpoch(source) }
    }

    /** 首页 LKG 的目录外待清理标记：写不进、读得出（空）。 */
    private object NoFlag : HomeLkgStore.PendingFlag {
        override fun entries(): Set<String> = emptySet()

        override fun add(entry: String): Boolean = false

        override fun remove(entry: String): Boolean = false
    }

    @Test
    fun r2_03_token_delete_and_cache_cleanup_both_fail_then_process_rebuild_without_relogin_reads_nothing_back() {
        val raw = context.getSharedPreferences("fuand0714-r2-03-app", Context.MODE_PRIVATE)
        val prefs = RemovalFailingPreferences(raw)
        val tokens = KeystoreSessionTokens(EncryptedPreferences(context, NoCipher, backing = prefs))
        tokens.save("access-1", "refresh-1", SessionScopeRaw.FULL, "user-a")
        val api = client(tokens)
        assertEquals("user-a", api.activeUserId())
        val homeRoot = File(context.noBackupFilesDir, "fuand0714-r2-03-lkg").apply { deleteRecursively() }

        // 四类缓存按当前世代写入（App 取实例处的接线）
        orderCacheStore(
            context,
            tokens,
        ).writeList("user-a", "self", OrderListData(listOf(item("o1")), null, null), 1L, tokens.persistedLoginEpoch())
        walletCacheStore(context, tokens).write("user-a", summary, 1L, tokens.persistedLoginEpoch())
        val restore = orderRestoreStore(context, tokens)
        val ref = restore.newRef()
        val record = OrderRestoreRecord(owner = "owner-a", q = "3741234567890123456", input = "3741234567890123456")
        assertTrue(restore.write(ref, restore.epoch, record, restore.loginBindingNow()))
        val home = HomeLkgStore(homeRoot, NoFlag, deleter = { false }).apply { bindLoginEpoch(persistedLoginEpochOf(tokens)) }
        assertTrue(home.write(home.ticket(scope), """{"page":"old"}"""))
        val before = Rebuilt(tokens, homeRoot)
        assertNotNull("前提：同一世代读得回", before.orders.readList("user-a", "self", before.epoch))
        assertNotNull(before.wallet.read("user-a", before.epoch))
        assertEquals(record, before.restore.read(ref))
        assertEquals("""{"page":"old"}""", before.home.read(scope))

        // 退出登录：令牌删除提交失败（真实 ApiClient 清会话 → 隔离）；四类缓存的待清理标记与删除同时失败（盘面原样留着、没有待清理标记）
        prefs.failRemovals = true
        api.clearSessionIf(tokens.loginEpoch)
        assertTrue("首页 LKG 清理同样失败", runCatching { home.clearScope(scope) }.isFailure)
        assertNotNull("前提：旧令牌仍在盘上", raw.getString(KeystoreSessionTokens.KEY_ACCESS, null))
        assertNotNull("前提：旧世代仍在盘上", raw.getString(KeystoreSessionTokens.KEY_LOGIN_EPOCH, null))
        assertTrue("前提：旧订单缓存仍在盘上", AppOrders.cacheRoot(context).walkTopDown().any { it.isFile && it.readText().contains("o1") })
        assertTrue("前提：旧恢复记录仍在盘上", File(AppOrders.restoreRoot(context), "$ref.json").exists())
        assertTrue("前提：旧首页 LKG 仍在盘上", homeRoot.walkTopDown().any { it.name == "page.json" })

        // 未重新登录即重建进程（删除仍失败）：令牌存储按落盘的撤销标记——不交出旧会话、世代为 null；四类缓存一律读不回
        val rebuiltTokens = KeystoreSessionTokens(EncryptedPreferences(context, NoCipher, backing = prefs))
        assertNull("重建后旧会话不交出", rebuiltTokens.accessToken)
        assertNull("重建后世代为 null", rebuiltTokens.persistedLoginEpoch())
        val after = Rebuilt(rebuiltTokens, homeRoot)
        assertNull("订单缓存读不回", after.orders.readList("user-a", "self", after.epoch))
        assertNull("钱包摘要缓存读不回", after.wallet.read("user-a", after.epoch))
        assertNull("订单页恢复记录读不回", after.restore.read(ref))
        assertNull("会员首页 LKG 读不回", after.home.read(scope))

        // 存储恢复、同账号重新登录（新世代）后再重建：旧世代的数据仍读不回
        prefs.failRemovals = false
        rebuiltTokens.save("access-2", "refresh-2", SessionScopeRaw.FULL, "user-a")
        val relogin = Rebuilt(KeystoreSessionTokens(EncryptedPreferences(context, NoCipher, backing = prefs)), homeRoot)
        assertNull(relogin.orders.readList("user-a", "self", relogin.epoch))
        assertNull(relogin.wallet.read("user-a", relogin.epoch))
        assertNull(relogin.restore.read(ref))
        assertNull(relogin.home.read(scope))
        homeRoot.deleteRecursively()
    }
}
