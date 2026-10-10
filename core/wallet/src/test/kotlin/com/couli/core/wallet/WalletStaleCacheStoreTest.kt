package com.couli.core.wallet

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 钱包摘要断网缓存的落盘（G22）：按账号隔离、坏文件按没有缓存、删除失败保留待清理且不读回旧数据、区分「目录不存在」与「枚举失败」。 */
class WalletStaleCacheStoreTest {
    private val root: File = Files.createTempDirectory("wallet-cache").toFile()

    private class MemoryFlag : WalletStaleCache.PendingClearFlag {
        var value = false
        var writable = true

        override fun isSet(): Boolean = value

        override fun set(): Boolean {
            if (writable) value = true
            return writable
        }

        override fun clear(): Boolean {
            if (writable) value = false
            return writable
        }
    }

    private val summary =
        WalletSummary(1234, 1234, 0, 0, 5678, 2222, 3456, 456, "2026-10", false, 500, null, true)

    @Test
    fun summaryIsIsolatedPerAccountAndOtherAccountsAreWipedOnWrite() {
        val store = WalletStaleCache.Store(root)
        store.write("user-a", summary, 1L)
        val cached = store.read("user-a")
        assertNotNull(cached)
        assertEquals(summary, cached!!.summary)
        assertEquals(1L, cached.fetchedAt)
        assertNull("别的账号读不到", store.read("user-b"))
        store.write("user-b", summary.copy(withdrawableFen = 1), 2L)
        assertNull("换账号写入前删掉旧账号的缓存", store.read("user-a"))
        assertFalse("目录名不落明文账号", root.walkTopDown().any { it.name.contains("user-b") })
    }

    @Test
    fun reconcileOwnerClearsOnLogoutOrSwitch() {
        val store = WalletStaleCache.Store(root)
        store.write("user-a", summary, 1L)
        store.reconcileOwner("user-a")
        assertNotNull(store.read("user-a"))
        store.reconcileOwner(null)
        assertNull(store.read("user-a"))
        assertTrue(root.walkTopDown().none { it.isFile && it.name == "summary.json" })
    }

    @Test
    fun corruptFileIsTreatedAsNoCacheAndDeleted() {
        val store = WalletStaleCache.Store(root)
        store.write("user-a", summary, 1L)
        val file = root.walkTopDown().first { it.name == "summary.json" }
        file.writeText("{not json")
        assertNull(store.read("user-a"))
        assertFalse(file.exists())
    }

    @Test
    fun missingRequiredFieldIsBadDataNotZero() {
        val store = WalletStaleCache.Store(root)
        store.write("user-a", summary, 1L)
        val file = root.walkTopDown().first { it.name == "summary.json" }
        file.writeText(file.readText().replace("\"frozen_fen\":0,", ""))
        assertNull("缺字段整条按坏数据，不补 0", store.read("user-a"))
    }

    @Test
    fun listingFailureKeepsPendingFlagOutsideTheCacheAndNeverReadsBack() {
        val flag = MemoryFlag()
        var failListing = false
        val store = WalletStaleCache.Store(root, flag) { dir -> if (failListing) null else dir.listFiles() }
        store.write("user-a", summary, 1L)
        failListing = true
        assertTrue("目录在、却枚举失败：报失败", runCatching { store.clearAll() }.isFailure)
        assertTrue("待清理状态落在缓存目录之外", flag.value)
        assertNull("清理完成前不读回旧数据", store.read("user-a"))
        failListing = false
        assertNull(store.read("user-a"))
        assertFalse("确认全删后才清标记", flag.value)
    }

    @Test
    fun missingRootIsNotAListingFailure() {
        val flag = MemoryFlag()
        val absent = File(root, "absent")
        val store = WalletStaleCache.Store(absent, flag) { null }
        store.clearAll()
        assertFalse(flag.value)
    }

    @Test
    fun flagSetButCacheUnreadableStillBlocksReads() {
        val flag = MemoryFlag()
        val store = WalletStaleCache.Store(root, flag)
        store.write("user-a", summary, 1L)
        // 上次清理的待清理状态还在（进程重启前没清完）：读之前先完成清理
        flag.value = true
        assertNull(store.read("user-a"))
        assertFalse(flag.value)
    }
}
