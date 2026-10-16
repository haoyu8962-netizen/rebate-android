package com.couli.core.wallet

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * FU-AND-0714 第 3 项（F-AND-25）：钱包摘要缓存绑定「账号 + 令牌存储里的持久化登录世代」（与订单缓存同一做法）。目录外标记、目录内标记、删除
 * 三者同时失败 → 同账号重新登录 → 重建 → 不读回；同一登录世代照常读回；世代或标记读不出不读。
 */
class FollowupAnd0714WalletBindingTest {
    private val root: File = File(Files.createTempDirectory("fuand0714-wallet").toFile(), "wallet")

    @Volatile private var loginEpoch: String? = "login-1"

    private class Flag(
        var commitOk: Boolean = true,
        var readFails: Boolean = false,
    ) : WalletStaleCache.PendingClearFlag {
        var value = false

        override fun isSet(): Boolean {
            if (readFails) throw IOException("flag unreadable")
            return value
        }

        override fun set(): Boolean {
            if (commitOk) value = true
            return commitOk
        }

        override fun clear(): Boolean {
            if (commitOk) value = false
            return commitOk
        }
    }

    private val summary = WalletSummary(1234, 1234, 0, 0, 5678, 2222, 3456, 456, "2026-10", false, 500, null, true)

    // FU-AND-0717（F-X-17）：世代由调用方给出（这里每次按 [loginEpoch] 的当前值给，等同原来的来源）
    private fun store(flag: WalletStaleCache.PendingClearFlag) = WalletStaleCache.Store(root, flag).apply { partitionByLoginEpoch() }

    @Test
    fun fand25_wallet_flag_marker_and_delete_all_fail_relogin_same_account_rebuilt_store_never_reads_back() {
        val flag = Flag(commitOk = false)
        val before = store(flag)
        before.write("user-a", summary, 1L, loginEpoch)
        assertNotNull(before.read("user-a", loginEpoch))
        val accountDir = root.listFiles()!!.first { it.isDirectory }
        root.setWritable(false)
        accountDir.setWritable(false)
        try {
            assumeTrue("本机能模拟目录不可写（非 root 运行）", runCatching { before.clearAll() }.isFailure)
            assertFalse(flag.value)
            assertFalse(File(root, "pending_clear").exists())
            loginEpoch = "login-2"
            assertNull("同账号重新登录 + 重建：不读回旧余额", store(flag).read("user-a", loginEpoch))
            assertTrue("前提：旧余额仍在盘上", root.walkTopDown().any { it.isFile && it.name == "summary.json" })
        } finally {
            accountDir.setWritable(true)
            root.setWritable(true)
        }
        val healed = store(Flag())
        healed.reconcileOwner("user-a", loginEpoch)
        assertTrue("旧登录世代的缓存已删", root.walkTopDown().none { it.isFile && it.name == "summary.json" })
    }

    @Test
    fun fand25_wallet_same_epoch_reads_back_unknown_epoch_or_flag_reads_nothing() {
        val flag = Flag()
        store(flag).write("user-a", summary, 1L, loginEpoch)
        assertNotNull(store(flag).read("user-a", loginEpoch))
        loginEpoch = null
        assertNull(store(flag).read("user-a", loginEpoch))
        loginEpoch = "login-1"
        assertNull("调用方给不出世代：不读", store(flag).read("user-a", null))
        flag.readFails = true
        assertNull("目录外标记读不出按待清理", store(flag).read("user-a", loginEpoch))
    }
}
