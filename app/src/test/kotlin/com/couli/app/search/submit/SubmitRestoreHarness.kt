@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("TooManyFunctions") // 保存恢复用例共用的步骤与断言

package com.couli.app.search.submit

import android.net.Uri
import android.os.Looper
import com.couli.app.clipboard.AppClipboard
import com.couli.core.network.ApiCall
import com.couli.core.router.UpdateCheck
import com.couli.feature.search.SearchPhase
import com.couli.generated.Sort
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf

/**
 * OWNER v3（评审 r2 S0-1、S2-1）：保存恢复用例共用的驱动与断言（只是步骤与读取，不含产品逻辑）。
 *
 * 两种重建：[RestoreMode.PROCESS] 进程回收（用保存状态构造全新夹具，内存全部重来）；[RestoreMode.SAME_PROCESS] 同进程重建（ViewModel 清理后按保存
 * 状态重建，App、剪贴板不变）。两种恢复门：[RestoreGate.OPEN] 版本检查已有结论；[RestoreGate.GATED] 版本检查中，由用例放行。
 */
internal enum class RestoreMode(
    val label: String,
) {
    PROCESS("进程回收"),
    SAME_PROCESS("同进程重建"),
}

internal enum class RestoreGate(
    val label: String,
    val initial: UpdateCheck,
) {
    OPEN("恢复门已开", UpdateCheck.OK),
    GATED("恢复门等待", UpdateCheck.CHECKING),
}

/** 保存之前发生的事。[NONE] 是「未作废直接回收」的对照：恢复后归属已无法证明，同样不得复活。 */
internal enum class BeforeSave(
    val label: String,
    val act: (SearchSubmitFixture) -> Unit,
) {
    NONE("未作废", {}),
    COVER("页面覆盖", SearchSubmitFixture::coverWithDetail),
    BACK("覆盖后返回", { f ->
        f.coverWithDetail()
        f.onSearchPage()
    }),
    ACCOUNT("切号", SearchSubmitFixture::switchAccount),
    ENVIRONMENT("切环境", SearchSubmitFixture::switchEnvironment),
    PRIVACY("撤回隐私", SearchSubmitFixture::withdrawPrivacy),
    SENSITIVE("敏感浮层", { f -> f.clipboard.onSensitiveOverlay() }),
    RESUBMIT("再提交在途", { f -> f.submit(SubmitReplies.SECOND_LINK) }),
}

internal const val RESTORE_PARSE = "parseInput"
internal const val RESTORE_SEARCH = "searchProducts"

/** 新生命开始时的基线：之后的请求、导航、历史都和它比。 */
internal data class RestoreBaseline(
    val calls: Int,
    val written: Int,
    val navigations: Int,
    val history: List<String>,
) {
    companion object {
        fun of(f: SearchSubmitFixture) =
            RestoreBaseline(f.api.calls.size, f.api.written.size, f.navigations.size, f.pageConfig.history.items)
    }
}

/** 排空主线程：协程（不推进虚拟时间，恢复门的 5 秒超时不会被触发）与主 Looper。 */
internal fun TestScope.drainRestore() {
    repeat(RESTORE_DRAIN_ROUNDS) {
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }
}

internal fun TestScope.settleRestore() {
    repeat(RESTORE_DRAIN_ROUNDS) {
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }
}

/** 取保存状态、按 [mode] 重建，在新的一生上跑 [body]。进程回收时旧夹具先关掉（在途协程随之取消）。 */
@Suppress("LongParameterList") // 重建方式、恢复门、新进程的隐私与路由参数
internal fun TestScope.restartedPage(
    f: SearchSubmitFixture,
    mode: RestoreMode,
    gate: RestoreGate,
    basic: Boolean = false,
    query: String? = null,
    body: (SearchSubmitFixture, RestoreBaseline) -> Unit,
) {
    val snapshot = f.saveState()
    if (mode == RestoreMode.PROCESS) {
        f.close()
        SearchSubmitFixture(backgroundScope, basic = basic, query = query, savedState = snapshot, initialUpdate = gate.initial).use { g ->
            body(g, RestoreBaseline.of(g))
        }
    } else {
        f.setUpdateCheck(gate.initial)
        f.rebuildPage(snapshot)
        body(f, RestoreBaseline.of(f))
    }
}

/** 恢复门等待期间什么都不得发；然后放行。 */
internal fun TestScope.openRestoreGate(
    g: SearchSubmitFixture,
    gate: RestoreGate,
    base: RestoreBaseline,
    combo: String,
) {
    g.onSearchPage()
    drainRestore()
    if (gate == RestoreGate.GATED) {
        assertNoNewCalls(g, base, "$combo 恢复门未放行")
        g.setUpdateCheck(UpdateCheck.OK)
    }
    settleRestore()
}

internal fun assertNoNewCalls(
    g: SearchSubmitFixture,
    base: RestoreBaseline,
    stage: String,
) {
    val calls = g.api.calls.drop(base.calls)
    assertTrue("$stage：来源为识别提交的旧查询不得再交到网络层，也不得补发识别（实际 ${describeCalls(calls)}）", calls.isEmpty())
    val written = g.api.written.drop(base.written)
    assertTrue("$stage：不得写出（实际 ${describeCalls(written)}）", written.isEmpty())
}

/** 来源为识别提交的旧查询没有复活：零请求、零消费，页面在输入态，输入框保留，没有补发新票据。 */
internal fun assertQueryDropped(
    g: SearchSubmitFixture,
    base: RestoreBaseline,
    input: String,
    stage: String,
) {
    assertNoNewCalls(g, base, stage)
    val page = g.page
    assertEquals("$stage：回到输入态（实际 ${page.phase}）", SearchPhase.IDLE, page.phase)
    assertEquals("$stage：不得保留旧查询关键词（否则配置变化、翻页还能按它发请求）", "", page.keyword)
    assertEquals("$stage：输入框文字保留", input, g.model.input.value)
    assertNull("$stage：不得消费结果（shownSeq）", page.shownSeq)
    assertTrue("$stage：不得展示结果（items ${page.items.size}）", page.items.isEmpty() && page.fallbackItems.isEmpty())
    assertNull("$stage：不得展示错误态", page.error)
    assertNull("$stage：不得展示翻页错误态", page.loadMoreError)
    assertEquals("$stage：不得导航", base.navigations, g.navigations.size)
    assertNull("$stage：不得出卡 / 提示", g.clipboard.result.value)
    assertEquals("$stage：不得记历史", base.history, g.pageConfig.history.items)
    assertNull("$stage：不得自动补发新票据（searchSubmit 槽应为空）", publishedSubmitSlot(g.clipboard))
}

/** 恢复后只读这一页的状态：配置切换、自动翻页、重试、换 Tab / 排序都不得让旧查询复活；配置开关仍照常驱动页面。 */
internal fun TestScope.probeDroppedPage(
    g: SearchSubmitFixture,
    base: RestoreBaseline,
    input: String,
    combo: String,
) {
    g.pushSearchStatus(taobao = "on", jd = "on")
    settleRestore()
    assertTrue("$combo 配置变化后 Tab 仍要刷新（实际 ${g.page.tabs}）", "jd" in g.page.tabs)
    assertTrue("$combo 自动翻页不得启动", !g.model.controller.loadMore())
    assertTrue("$combo 重试不得启动", !g.model.controller.retry())
    g.model.controller.selectTab("jd")
    g.model.controller.selectSort(Sort.SALES_DESC.raw)
    settleRestore()
    assertQueryDropped(g, base, input, "$combo 换平台 / 翻页 / 换条件后")
    g.pushSearchStatus(taobao = "off", jd = "off")
    settleRestore()
    assertEquals("$combo 配置全关：无可搜索平台（配置订阅仍在）", SearchPhase.UNAVAILABLE, g.page.phase)
    assertNoNewCalls(g, base, "$combo 配置全关后")
    g.pushSearchStatus(taobao = "on", jd = "off")
    settleRestore()
    assertQueryDropped(g, base, input, "$combo 配置恢复平台后")
}

/** 恢复后用户自己再点一次搜索：照常发一次新识别，结果照常呈现（不是把入口锁死）。 */
internal fun TestScope.assertUserCanResubmit(
    g: SearchSubmitFixture,
    base: RestoreBaseline,
    combo: String,
) {
    val parsed = g.api.parseCalls.size
    g.model.onSubmit()
    drainRestore()
    assertEquals("$combo 用户重新提交：发一次新识别", parsed + 1, g.api.parseCalls.size)
    g.api.reply(g.api.parseCalls.size - 1).complete(SubmitReplies.single())
    settleRestore()
    assertEquals("$combo 用户重新提交：结果照常呈现（直达详情一次）", base.navigations + 1, g.navigations.size)
    assertEquals("$combo 用户重新提交：直达详情", "ProductDetail", g.navigations.last().first)
}

/** 剪贴板发布的 searchSubmit 槽（结构断言钉住的字段名）。 */
private fun publishedSubmitSlot(clipboard: AppClipboard): Any? {
    val published =
        AppClipboard::class.java
            .getDeclaredField("published")
            .apply { isAccessible = true }
            .get(clipboard)
    val snapshot = checkNotNull((published as AtomicReference<*>).get())
    return snapshot.javaClass
        .getDeclaredField("searchSubmit")
        .apply { isAccessible = true }
        .get(snapshot)
}

internal fun keywordOfCall(call: ApiCall): String? = Uri.parse("https://fixture.invalid/?${call.query}").getQueryParameter("q")

internal fun platformOfCall(call: ApiCall): String? = Uri.parse("https://fixture.invalid/?${call.query}").getQueryParameter("platform")

internal fun describeCalls(calls: List<ApiCall>): String =
    calls.map { "${it.operationId}(q=${keywordOfCall(it)}, platform=${platformOfCall(it)})" }.toString()

private const val RESTORE_DRAIN_ROUNDS = 3
