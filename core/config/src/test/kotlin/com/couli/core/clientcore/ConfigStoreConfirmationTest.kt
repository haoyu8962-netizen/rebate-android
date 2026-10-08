package com.couli.core.clientcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-06a 共性第 8、10 条（H-R2-02、H-R3-01）：ConfigStore 的确认编号与订阅。
 * - 200 成功换内容、有效的 304 重新确认当前配置都推进 [ConfigStore.appliedFetchId] 并通知订阅者；
 * - 没有当前配置时的 304、失败都不推进、不通知；
 * - [ConfigStore.requestFresh] 遇到在途拉取时，等它结算后再发一次（在途的那次不能当作更新的配置）；
 * - 环境切换后晚到的结果丢弃，编号归零；
 * - [ConfigStore.searchStatusLayers] 给出三层原始取值（current 未知值原样保留）。
 */
class ConfigStoreConfirmationTest {
    private class Fetches {
        val pending = mutableListOf<Pair<String?, (ConfigReply) -> Unit>>()

        fun fetch(
            ifNoneMatch: String?,
            done: (ConfigReply) -> Unit,
        ) {
            pending += ifNoneMatch to done
        }

        fun answer(
            index: Int,
            reply: ConfigReply,
        ) = pending[index].second(reply)
    }

    private val fetches = Fetches()
    private var notified = 0
    private val store =
        ConfigStore(
            readFile = { null },
            writeFile = {},
            fetcher = fetches::fetch,
            clock = { 0L },
            bundled = """{"features":{"search_status":{"taobao":"on","jd":"off"}}}""",
        ).also { it.subscribe { notified += 1 } }

    private fun ok(jd: String) = ConfigReply(ConfigReply.OK, """{"features":{"search_status":{"taobao":"on","jd":"$jd"}}}""", "\"v1\"")

    private val notModified = ConfigReply(ConfigReply.NOT_MODIFIED, null, "\"v1\"")
    private val failure = ConfigReply(ConfigReply.FAILURE, null, null)

    @Test
    fun okAndValid304BothAdvanceTheConfirmationAndNotify() {
        store.onColdStart()
        val afterStart = notified
        fetches.answer(0, ok("on"))
        assertEquals(1L, store.appliedFetchId)
        assertEquals(afterStart + 1, notified)

        // 京东临时关闭 → 分界；刷新失败（不推进）
        val mark = store.issuedFetchId
        store.requestFresh()
        fetches.answer(1, failure)
        assertEquals(1L, store.appliedFetchId)
        assertEquals(afterStart + 1, notified)

        // 重新开放、配置恢复原值：回前台条件请求得到 304 → 确认编号推进到分界之后，并通知（H-R3-01）
        store.onForeground()
        assertEquals("带上次成功的 ETag", "\"v1\"", fetches.pending[2].first)
        fetches.answer(2, notModified)
        assertTrue(store.appliedFetchId > mark)
        assertEquals(afterStart + 2, notified)
        assertEquals("304 不改内容", ConfigSourceRaw.CURRENT, store.sourceRaw)
    }

    @Test
    fun requestFreshWhileAFetchIsInFlightSendsAnotherOneAfterItSettles_andTheOldOneCannotPassTheMark() {
        store.onColdStart()
        fetches.answer(0, ok("on"))
        store.onForeground() // 编号 2 在途（禁用之前发出）
        val mark = store.issuedFetchId
        store.requestFresh()
        assertEquals("在途时不另发", 2, fetches.pending.size)
        fetches.answer(1, notModified)
        assertEquals("旧在途 304 的编号不超过分界", mark, store.appliedFetchId)
        assertEquals("结算后补发一次", 3, fetches.pending.size)
        fetches.answer(2, notModified)
        assertTrue(store.appliedFetchId > mark)
    }

    @Test
    fun notModifiedWithoutACurrentConfigChangesNothing() {
        store.onColdStart()
        val before = notified
        fetches.answer(0, notModified)
        assertEquals(0L, store.appliedFetchId)
        assertEquals(before, notified)
        assertEquals(ConfigSourceRaw.BUNDLED, store.sourceRaw)
    }

    @Test
    fun environmentSwitchDropsLateRepliesAndResetsTheConfirmation() {
        store.onColdStart()
        fetches.answer(0, ok("on"))
        store.onForeground()
        store.clearForEnvironmentSwitch()
        assertEquals(0L, store.appliedFetchId)
        fetches.answer(1, notModified)
        assertEquals("旧环境晚到的 304 丢弃", 0L, store.appliedFetchId)
    }

    @Test
    fun searchStatusLayersKeepRawCurrentValues() {
        store.onColdStart()
        fetches.answer(0, ConfigReply(ConfigReply.OK, """{"features":{"search_status":{"taobao":"future","jd":"on"}}}""", null))
        val layers = store.searchStatusLayers()
        assertEquals("""{"taobao":"future","jd":"on"}""", layers["current"].toString())
        assertEquals("lkg 只记 on / off", """{"jd":"on"}""", layers["lkg"].toString())
        assertEquals("""{"taobao":"on","jd":"off"}""", layers["bundled"].toString())
        assertFalse(layers.isEmpty())
    }
}
