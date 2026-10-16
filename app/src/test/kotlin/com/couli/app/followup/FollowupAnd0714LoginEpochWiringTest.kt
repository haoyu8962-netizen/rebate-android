package com.couli.app.followup

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.orderCacheStore
import com.couli.app.orderRestoreStore
import com.couli.app.walletCacheStore
import com.couli.core.network.EncryptedPreferences
import com.couli.core.network.KeystoreSessionTokens
import com.couli.core.network.SecretCipher
import com.couli.core.network.SessionScopeRaw
import com.couli.core.orders.OrderListData
import com.couli.core.orders.OrderRestoreRecord
import com.couli.core.orders.OrderSummary
import com.couli.core.wallet.WalletSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FU-AND-0714 第 3、5 项的接线：AppModule 取订单缓存、钱包摘要缓存、订单页恢复记录的实例时接上真实令牌存储（KeystoreSessionTokens）里的持久化
 * 登录世代。实例先由清理入口（不带令牌）建出来的，页面取实例时也会接上。同一登录世代（含刷新）照常读回；同账号退出再登录之后旧数据读不回。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class FollowupAnd0714LoginEpochWiringTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private object NoCipher : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain

        override fun decrypt(sealed: ByteArray): ByteArray = sealed
    }

    private val tokens = KeystoreSessionTokens(EncryptedPreferences(context, NoCipher, name = "fuand0714-session"))

    private fun item(id: String) =
        OrderSummary(id, "taobao", "样例商品", null, 4560, 1, "3741234567890123456", null, "PAID", null, 234, null, false)

    private val summary = WalletSummary(1234, 1234, 0, 0, 5678, 2222, 3456, 456, "2026-10", false, 500, null, true)

    @Test
    fun fand25_app_stores_bind_the_token_store_login_epoch_even_when_a_cleaner_created_the_instance_first() {
        // 清理入口先建出实例（不带令牌）
        orderCacheStore(context)
        walletCacheStore(context)
        orderRestoreStore(context)
        tokens.save("access-1", "refresh-1", SessionScopeRaw.FULL, "user-a")
        val orders = orderCacheStore(context, tokens)
        val wallet = walletCacheStore(context, tokens)
        val restore = orderRestoreStore(context, tokens)
        orders.writeList("user-a", "self", OrderListData(listOf(item("o1")), null, null), 1L, tokens.persistedLoginEpoch())
        wallet.write("user-a", summary, 1L, tokens.persistedLoginEpoch())
        val ref = restore.newRef()
        val record = OrderRestoreRecord(owner = "owner-a", q = "3741234567890123456", input = "3741234567890123456")
        assertTrue(restore.write(ref, restore.epoch, record, restore.loginEpochNow()))

        // 刷新令牌：登录世代沿用，照常读回
        assertTrue(tokens.replaceIf(tokens.generation, "access-2", "refresh-2", SessionScopeRaw.FULL))
        assertNotNull(orderCacheStore(context).readList("user-a", "self", tokens.persistedLoginEpoch()))
        assertNotNull(walletCacheStore(context).read("user-a", tokens.persistedLoginEpoch()))
        assertEquals(record, orderRestoreStore(context).read(ref))

        // 同账号退出再登录（缓存本身的清理没跑：只看登录世代）：旧数据读不回
        tokens.clear()
        tokens.save("access-3", "refresh-3", SessionScopeRaw.FULL, "user-a")
        assertNull(orderCacheStore(context).readList("user-a", "self", tokens.persistedLoginEpoch()))
        assertNull(walletCacheStore(context).read("user-a", tokens.persistedLoginEpoch()))
        assertNull(orderRestoreStore(context).read(ref))
    }
}
