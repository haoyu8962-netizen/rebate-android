package com.couli.app.followup

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.AppModule
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
import com.couli.core.network.FileRevocationMarks
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FU-AND-0715 第 1 项（F-AND-32，App 接线）：令牌文件**整体**写不进（同文件撤销标记与令牌删除同时提交失败），四类缓存清理也失败，**未重新登录即重建
 * 进程**——独立介质（noBackupFilesDir 下的撤销标记文件，AppModule 接线）兜住：重建的令牌存储不交出旧会话、持久化登录世代为 null，订单缓存、钱包摘要缓存、
 * 订单页恢复记录、会员首页 LKG（旧数据仍在盘上）一律读不回；存储恢复后同账号重新登录（新世代）同样读不回。清会话经真实 ApiClient（clearSessionIf）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class FollowupAnd0715EpochTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private object NoCipher : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain

        override fun decrypt(sealed: ByteArray): ByteArray = sealed
    }

    /** [writable] 为 false 时每次提交都失败（令牌文件整体写不进，盘上原样）。 */
    private class WholeFilePreferences(
        private val inner: SharedPreferences,
    ) : SharedPreferences by inner {
        @Volatile var writable = true

        override fun edit(): SharedPreferences.Editor {
            val editor = inner.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(
                    key: String?,
                    value: String?,
                ): SharedPreferences.Editor = apply { editor.putString(key, value) }

                override fun remove(key: String?): SharedPreferences.Editor = apply { editor.remove(key) }

                override fun commit(): Boolean = writable && editor.commit()
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
    private val markFile = File(context.noBackupFilesDir, KeystoreSessionTokens.REVOCATION_FILE)

    /** 与 AppModule.sessionTokens 同一份独立介质（noBackupFilesDir/couli_session.revoked）。 */
    private fun tokens(prefs: SharedPreferences) =
        KeystoreSessionTokens(EncryptedPreferences(context, NoCipher, backing = prefs), FileRevocationMarks(markFile))

    /** 模拟进程重建：按同一份盘面新建四类存储（内存里的待清理与阻断都没了），接上 [tokens] 的持久化登录世代。 */
    private inner class Rebuilt(
        tokens: KeystoreSessionTokens,
        homeRoot: File,
    ) {
        private val source = persistedLoginEpochOf(tokens)
        val orders = OrderStaleCache.Store(AppOrders.cacheRoot(context), orderCachePendingFlag(context)).apply { bindLoginEpoch(source) }
        val wallet = WalletStaleCache.Store(AppWallet.cacheRoot(context), walletCachePendingFlag(context)).apply { bindLoginEpoch(source) }
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
    fun fand32_app_session_tokens_are_wired_to_the_independent_revocation_file() {
        markFile.delete()
        val tokens = AppModule.sessionTokens(context, NoCipher) as KeystoreSessionTokens
        tokens.save("access-w", "refresh-w", SessionScopeRaw.FULL, "user-w")
        // 令牌文件写得进：清除确认落盘、不留标记文件
        tokens.clear()
        assertFalse(markFile.exists())
    }

    @Test
    fun fand32_whole_token_file_unwritable_and_cache_cleanup_fail_then_rebuild_without_relogin_reads_nothing_back() {
        markFile.delete()
        val raw = context.getSharedPreferences("fuand0715-whole-app", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        val prefs = WholeFilePreferences(raw)
        val tokens = tokens(prefs)
        tokens.save("access-1", "refresh-1", SessionScopeRaw.FULL, "user-a")
        val api = client(tokens)
        assertEquals("user-a", api.activeUserId())
        val homeRoot = File(context.noBackupFilesDir, "fuand0715-whole-lkg").apply { deleteRecursively() }

        orderCacheStore(context, tokens).writeList("user-a", "self", OrderListData(listOf(item("o1")), null, null), 1L)
        walletCacheStore(context, tokens).write("user-a", summary, 1L)
        val restore = orderRestoreStore(context, tokens)
        val ref = restore.newRef()
        val record = OrderRestoreRecord(owner = "owner-a", q = "3741234567890123456", input = "3741234567890123456")
        assertTrue(restore.write(ref, restore.epoch, record, restore.loginBindingNow()))
        val home = HomeLkgStore(homeRoot, NoFlag, deleter = { false }).apply { bindLoginEpoch(persistedLoginEpochOf(tokens)) }
        assertTrue(home.write(home.ticket(scope), """{"page":"old"}"""))
        val before = Rebuilt(tokens(prefs), homeRoot)
        assertNotNull("前提：同一世代读得回", before.orders.readList("user-a", "self"))
        assertNotNull(before.wallet.read("user-a"))
        assertEquals(record, before.restore.read(ref))
        assertEquals("""{"page":"old"}""", before.home.read(scope))

        // 退出登录：令牌文件整体写不进（同文件撤销标记、令牌删除都失败）；四类缓存清理同时失败（盘面原样、没有待清理标记）
        prefs.writable = false
        api.clearSessionIf(tokens.loginEpoch)
        assertTrue(runCatching { home.clearScope(scope) }.isFailure)
        assertNotNull("前提：旧令牌仍在盘上", raw.getString(KeystoreSessionTokens.KEY_ACCESS, null))
        assertNotNull("前提：旧世代仍在盘上", raw.getString(KeystoreSessionTokens.KEY_LOGIN_EPOCH, null))
        assertNull("前提：同文件撤销标记没写进", raw.getString(KeystoreSessionTokens.KEY_REVOKED, null))
        assertTrue("独立介质的撤销标记已落盘", markFile.exists())

        // 未重新登录即重建进程（仍写不进）：不交出旧会话、世代为 null；四类缓存一律读不回
        val rebuiltTokens = tokens(prefs)
        assertNull("重建后旧会话不交出", rebuiltTokens.accessToken)
        assertNull("重建后世代为 null", rebuiltTokens.persistedLoginEpoch())
        assertNull("重建后 ApiClient 也没有账号", client(tokens(prefs)).activeUserId())
        val after = Rebuilt(tokens(prefs), homeRoot)
        assertNull("订单缓存读不回", after.orders.readList("user-a", "self"))
        assertNull("钱包摘要缓存读不回", after.wallet.read("user-a"))
        assertNull("订单页恢复记录读不回", after.restore.read(ref))
        assertNull("会员首页 LKG 读不回", after.home.read(scope))

        // 存储恢复、同账号重新登录（新世代）后再重建：旧世代的数据仍读不回；标记文件随新登录删除
        prefs.writable = true
        tokens(prefs).save("access-2", "refresh-2", SessionScopeRaw.FULL, "user-a")
        assertFalse(markFile.exists())
        val relogin = Rebuilt(tokens(prefs), homeRoot)
        assertNull(relogin.orders.readList("user-a", "self"))
        assertNull(relogin.wallet.read("user-a"))
        assertNull(relogin.restore.read(ref))
        assertNull(relogin.home.read(scope))
        assertNotNull("新登录照常可用", tokens(prefs).persistedLoginEpoch())
        homeRoot.deleteRecursively()
    }
}
