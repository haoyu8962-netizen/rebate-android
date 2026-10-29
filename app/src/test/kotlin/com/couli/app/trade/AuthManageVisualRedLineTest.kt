@file:Suppress("MagicNumber")

package com.couli.app.trade

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.trade.AuthManageVisualHost.Companion.AUTHORIZED_KEY
import com.couli.app.trade.AuthManageVisualHost.Companion.BACK_KEY
import com.couli.app.trade.AuthManageVisualHost.Companion.BIND
import com.couli.app.trade.AuthManageVisualHost.Companion.BLOCKED_KEY
import com.couli.app.trade.AuthManageVisualHost.Companion.CONTACT_KEY
import com.couli.app.trade.AuthManageVisualHost.Companion.GRANT
import com.couli.app.trade.AuthManageVisualHost.Companion.GRANT_KEY
import com.couli.app.trade.AuthManageVisualHost.Companion.LIST
import com.couli.app.trade.AuthManageVisualHost.Companion.OPEN
import com.couli.app.trade.AuthManageVisualHost.Companion.REBIND_KEY
import com.couli.app.trade.AuthManageVisualHost.Companion.RETRY_KEY
import com.couli.app.trade.AuthManageVisualHost.Companion.TITLE_KEY
import com.couli.app.trade.AuthManageVisualHost.Companion.UNAUTHORIZED_KEY
import com.couli.feature.trade.AuthManageTags
import com.couli.feature.trade.AuthStepResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * APP-06w 归属页红线逐条（brief §0.1、§5「红线逐条测试」redLine1…9，三端同名；裁定 D10-414 ①③）。全部从真实控件触摸出发、按次计数：
 * listUnionBindings / getUnionAuthUrl / bindUnion / openLink 请求数、installed 查询、幂等键、授权拉起与外跳次数、link_jump 上报次数（AppTrade 埋点出口）、
 * 客服出口次数与来源、返回次数、出栈后取消与迟到响应。除 redLine9 的「读屏节点集合」（G-A08，底版红）外，本组在底版与实现后都应绿（回归）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions", "LargeClass")
class AuthManageVisualRedLineTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { AuthManageVisualHost(rule) }

    @After fun tearDown() = host.close()

    private fun counts(
        list: Int,
        grant: Int = 0,
        bind: Int = 0,
        what: String,
    ) {
        assertEquals("$what：listUnionBindings", list, host.api.count(LIST))
        assertEquals("$what：getUnionAuthUrl", grant, host.api.count(GRANT))
        assertEquals("$what：bindUnion", bind, host.api.count(BIND))
        assertEquals("$what：openLink（本页不重放任何 open）", 0, host.api.count(OPEN))
        assertEquals("$what：link_jump 上报", 0, host.linkJumps)
    }

    // ---------------------------------------------------------------- ① 进页

    @Test fun redLine1_enteringLoadsOnceAndNeverStartsAGrant() {
        host.launch("both_unbound")
        counts(list = 1, what = "进页")
        assertEquals("进页零授权拉起 / 外跳", 0, host.ports.launches)
        host.stop()
        host.start()
        counts(list = 1, what = "回前台（无等待回流）0 次")
        host.recompose()
        counts(list = 1, what = "配置变化（页面重组、ViewModel 随持有者）不重复加载")
        assertEquals("进页零客服、零返回", Pair(0, 0), Pair(host.contacts.size, host.backs))
    }

    @Test fun redLine1_everyStatusProjectsAsBefore() {
        listOf(
            "normal",
            "blocked",
            "all_active",
            "both_unbound",
            "both_blocked",
            "pdd_released",
            "pdd_invalid",
            "pdd_pending",
            "with_jd",
            "empty",
        ).forEach { name ->
            host.api.calls.clear()
            host.launch(name)
            AuthManageVisualFixtures.projection(name).forEach { (p, want) ->
                val grant = host.has(host.grantTag(p))
                val authorized = host.countInRow(p, host.text(AUTHORIZED_KEY)) == 1
                val blockedNotice = host.countInRow(p, host.text(BLOCKED_KEY)) == 1
                when (want) {
                    "authorized" -> assertTrue("$name/$p 已授权、无按钮", authorized && !grant && !blockedNotice)
                    "can_grant" -> assertTrue("$name/$p 未授权 +【去授权】", !authorized && grant && !blockedNotice)
                    else -> assertTrue("$name/$p 停用：未授权、无【去授权】、有 30153", !authorized && !grant && blockedNotice)
                }
            }
            assertEquals("$name：只列淘宝、拼多多", 0, host.countText(host.name("jd")))
            assertEquals("$name：进页 1 次加载、0 授权", Pair(1, 0), Pair(host.api.count(LIST), host.api.count(GRANT)))
            host.pop()
        }
    }

    // ---------------------------------------------------------------- ② 【去授权】

    @Test fun redLine2_oneTapIsOneGrantUrlWithInstalledAndInFlightTapsAreInert() {
        host.launch("both_unbound")
        host.tap(host.grantTag("taobao"))
        counts(list = 1, grant = 1, what = "点一次【去授权】")
        assertEquals(
            "installed 照旧（percentEncode 原样）",
            "installed=unknown",
            host.api
                .of(GRANT)
                .single()
                .query,
        )
        assertEquals(
            "路径参数是该行平台",
            mapOf("platform" to "taobao"),
            host.api
                .of(GRANT)
                .single()
                .pathParams,
        )
        host.tap(host.grantTag("taobao"))
        host.tap(host.grantTag("pdd"))
        counts(list = 1, grant = 1, what = "在途中再点本行 / 别的平台 0 次")
        assertEquals("在途中零拉起", 0, host.ports.launches)
    }

    @Test fun redLine2_authorizedAndBlockedRowsHaveNoGrantNode() {
        host.launch("normal")
        assertFalse("已授权行无【去授权】节点", host.has(host.grantTag("taobao")))
        assertTrue("先正：未授权行有", host.has(host.grantTag("pdd")))
        host.pop()
        host.launch("blocked")
        assertFalse("停用行无【去授权】节点", host.has(host.grantTag("taobao")))
        assertFalse("已授权行无", host.has(host.grantTag("pdd")))
        assertEquals("可点的【去授权】个数 0", 0, host.countText(host.text(GRANT_KEY)))
    }

    // ---------------------------------------------------------------- ③ 授权成功

    @Test fun redLine3_taobaoBindsOnceWithAFreshKeyThenRefreshesWithoutOpen() {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.grant("taobao_web_code")
        host.api.sticky[BIND] = AuthManageVisualFixtures.bound()
        host.launch("taobao_unbound")
        host.nextList("taobao_unbound")
        host.tap(host.grantTag("taobao"))
        counts(list = 2, grant = 1, bind = 1, what = "授权成功后刷新 1 次")
        assertEquals("web_code 拉起恰一次", 1, host.ports.webGrants.size)
        val first =
            host.api
                .of(BIND)
                .single()
                .idempotencyKey
        assertFalse("Idempotency-Key 非空", first.isNullOrBlank())
        host.tap(host.grantTag("taobao"))
        counts(list = 3, grant = 2, bind = 2, what = "第二次授权")
        val second =
            host.api
                .of(BIND)
                .last()
                .idempotencyKey
        assertFalse(second.isNullOrBlank())
        assertNotEquals("两次绑定键不同（BR-ID-17 幂等键）", first, second)
        assertEquals("零外跳浏览器 / App", 0, host.ports.browser.size + host.ports.apps.size)
    }

    @Test fun redLine3_allAuthorizedAfterReturnShowsNoBuyButton() {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.grant("taobao_web_code")
        host.api.sticky[BIND] = AuthManageVisualFixtures.bound()
        host.launch("taobao_unbound")
        host.nextList("all_active")
        host.tap(host.grantTag("taobao"))
        host.settle("刷新成两个都已授权") { host.countText(host.text(AUTHORIZED_KEY)) == 2 }
        assertEquals("刷新后零【去授权】", 0, host.countText(host.text(GRANT_KEY)))
        assertEquals("不出购买类按钮 / 金额", 0, host.countText("¥", substring = true))
        counts(list = 2, grant = 1, bind = 1, what = "AuthManageStates ①")
    }

    @Test fun redLine3_30104RecoversOnceWithANewKey() {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.grant("taobao_web_code")
        host.api.queue(BIND, AuthManageVisualFixtures.failure("c30104_once"))
        host.api.sticky[BIND] = AuthManageVisualFixtures.bound()
        host.launch("taobao_unbound")
        host.tap(host.grantTag("taobao"))
        counts(list = 2, grant = 2, bind = 2, what = "30104 恢复一次（重取上下文、新凭证、新键）后成功")
        val keys = host.api.of(BIND).map { it.idempotencyKey }
        assertEquals("两次绑定键不同", 2, keys.toSet().size)
        assertEquals("恢复后再拉起一次（仍是用户那一下的许可）", 2, host.ports.webGrants.size)
    }

    @Test fun redLine3_pddRunsTheJumpOnceAndRefreshesOnReturnWithoutLinkJump() {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.grant("pdd_jump")
        host.launch("both_unbound")
        host.tap(host.grantTag("pdd"))
        assertEquals("auth_jump 外跳恰一次（浏览器）", 1, host.ports.browser.size)
        counts(list = 1, grant = 1, what = "外跳后未回前台")
        host.stop()
        host.start()
        counts(list = 2, grant = 1, what = "回前台刷新恰 1 次")
        host.stop()
        host.start()
        counts(list = 2, grant = 1, what = "再回前台（已无等待回流）0 次")
        assertEquals("不自动再拉起", 1, host.ports.browser.size)
    }

    // ---------------------------------------------------------------- ④ 授权取消

    @Test fun redLine4_cancelStaysUnauthorizedWithoutNoticeOrRequests() {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.grant("taobao_web_code")
        host.ports.step = AuthStepResult.Cancelled
        host.launch("taobao_unbound")
        host.tap(host.grantTag("taobao"))
        counts(list = 1, grant = 1, bind = 0, what = "授权取消：零绑定、零刷新")
        assertEquals("零轻提示播报（BR-ID-18）", emptyList<String>(), host.spoken.filterNot { it.isBlank() })
        assertEquals("仍「未授权」", 1, host.countInRow("taobao", host.text(UNAUTHORIZED_KEY)))
        assertTrue(
            "按钮复原可点",
            host
                .node(host.grantTag("taobao"))
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Disabled) == null,
        )
    }

    // ---------------------------------------------------------------- ⑤ 后台作废许可

    @Test fun redLine5_goingBackgroundWhileFetchingNeverLaunchesAndReturnDoesNotRelaunch() {
        host.launch("taobao_unbound")
        host.tap(host.grantTag("taobao"))
        host.stop()
        host.complete(GRANT, AuthManageVisualFixtures.grant("taobao_web_code"))
        assertEquals("ON_STOP 后取到地址：拉起 0 次", 0, host.ports.launches)
        host.start()
        counts(list = 1, grant = 1, bind = 0, what = "回前台不自动再拉起、不刷新")
        assertEquals("回前台仍 0 次拉起", 0, host.ports.launches)
        assertTrue("按钮复原，等用户再点", host.has(host.grantTag("taobao")))
    }

    @Test fun redLine5_pddJumpAfterBackgroundIsNotLaunched() {
        host.launch("both_unbound")
        host.tap(host.grantTag("pdd"))
        host.stop()
        host.complete(GRANT, AuthManageVisualFixtures.grant("pdd_jump"))
        host.start()
        assertEquals("被覆盖后不外跳", 0, host.ports.browser.size)
        assertEquals("回前台不重取地址", 1, host.api.count(GRANT))
    }

    // ---------------------------------------------------------------- ⑥ 结果分流

    @Test fun redLine6_authUnavailableOnlyNotices() {
        listOf("c30101", "c30102").forEach { code ->
            host.api.calls.clear()
            host.spoken.clear()
            host.api.sticky[GRANT] = AuthManageVisualFixtures.failure(code)
            host.launch("taobao_unbound")
            val message = host.text(AuthManageVisualFixtures.wording(code))
            host.toastAfter(message) { host.tap(host.grantTag("taobao")) }
            host.advance(500)
            assertEquals("$code：轻提示播报 1 次", 1, host.spoken.count { it == message })
            assertEquals("$code：拉起 0、外跳 0", 0, host.ports.launches)
            counts(list = 1, grant = 1, bind = 0, what = code)
            assertEquals("$code：仍未授权", 1, host.countInRow("taobao", host.text(UNAUTHORIZED_KEY)))
            rule.mainClock.autoAdvance = true
            host.pop()
        }
    }

    @Test fun redLine6_30151NoticesAndStaysUnauthorized() {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.grant("taobao_web_code")
        host.api.sticky[BIND] = AuthManageVisualFixtures.failure("c30151")
        host.launch("taobao_unbound")
        val message = host.text(AuthManageVisualFixtures.wording("c30151"))
        host.toastAfter(message) { host.tap(host.grantTag("taobao")) }
        host.advance(500)
        assertEquals("30151：轻提示 1 次", 1, host.spoken.count { it == message })
        counts(list = 1, grant = 1, bind = 1, what = "30151 不刷新成已授权")
        assertEquals("仍未授权", 1, host.countInRow("taobao", host.text(UNAUTHORIZED_KEY)))
        assertEquals("不显示已授权", 0, host.countInRow("taobao", host.text(AUTHORIZED_KEY)))
    }

    @Test fun redLine6_30153TurnsTheRowBlockedAndContactGoesToTheUnifiedExitOnce() {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.failure("c30153")
        host.launch("both_unbound")
        host.tap(host.grantTag("taobao"))
        host.settle("该行改停用") { host.has(AuthManageTags.CONTACT) }
        assertFalse("【去授权】消失", host.has(host.grantTag("taobao")))
        assertTrue("拼多多不受影响", host.has(host.grantTag("pdd")))
        assertEquals("显示 error.30153", 1, host.countInRow("taobao", host.text(BLOCKED_KEY)))
        host.tap(AuthManageTags.CONTACT)
        assertEquals("客服出口恰 1 次、来源 AuthManage", listOf("AuthManage"), host.contacts)
        counts(list = 1, grant = 1, what = "联系客服零请求")
    }

    @Test fun redLine6_listBlockedContactOnce() {
        host.launch("blocked")
        host.tap(AuthManageTags.CONTACT)
        assertEquals(listOf("AuthManage"), host.contacts)
        assertEquals("零返回", 0, host.backs)
    }

    // ---------------------------------------------------------------- ⑦ 列表失败 / 限流 / 断网

    @Test fun redLine7_serverErrorShowsTrace6AndRetryLoadsOnce() {
        host.launchListError(AuthManageVisualFixtures.failure("c50001"))
        assertEquals("50001 带 trace6", 1, host.countText(AuthManageVisualFixtures.trace6, substring = true))
        host.nextList("normal")
        host.tap(AuthManageTags.RETRY)
        assertEquals("【重试】点一次 → listUnionBindings +1", 2, host.api.count(LIST))
        host.settle("重试成功") { host.has(host.row("taobao")) }
    }

    @Test fun redLine7_listRateLimitDisablesRetryAndGrantsUntilRetryAfter() {
        host.launchListError(AuthManageVisualFixtures.failure("c42901", retryAfterMillis = AuthManageVisualFixtures.recoverProbeMillis))
        assertEquals("error.42901 文案", 1, host.countText(host.text("error.42901")))
        val retry = host.node(AuthManageTags.RETRY)
        assertNotNull("【重试】置灰", retry.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled))
        host.tap(retry)
        assertEquals("禁用期点【重试】0 请求", 1, host.api.count(LIST))
        host.waitRetryAfter("【重试】") {
            host
                .node(AuthManageTags.RETRY)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Disabled) == null
        }
        host.nextList("normal")
        host.tap(AuthManageTags.RETRY)
        assertEquals("到期恢复后点一次 +1", 2, host.api.count(LIST))
    }

    @Test fun redLine7_grantRateLimitDisablesEveryGrantAndTheExecutorBlocksOldCallbacks() {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.failure("c42901", retryAfterMillis = AuthManageVisualFixtures.recoverProbeMillis)
        host.launch("both_unbound")
        val pddOld = host.clickAction(host.node(host.grantTag("pdd")))
        host.tap(host.grantTag("taobao"))
        listOf("taobao", "pdd").forEach {
            assertNotNull(
                "$it 置灰",
                host
                    .node(host.grantTag(it))
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(SemanticsProperties.Disabled),
            )
        }
        host.tap(host.grantTag("pdd"))
        rule.runOnUiThread { pddOld() }
        host.idle()
        assertEquals("禁用期：点击与旧回调都 0 请求（执行层拦截）", 1, host.api.count(GRANT))
        host.waitRetryAfter("【去授权】") {
            host
                .node(host.grantTag("pdd"))
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Disabled) == null
        }
        host.api.sticky.remove(GRANT)
        host.tap(host.grantTag("pdd"))
        assertEquals("到期后点一次 +1", 2, host.api.count(GRANT))
    }

    @Test fun redLine7_offlineUsesTheNetworkWording() {
        host.launchListError(AuthManageVisualFixtures.offline())
        assertEquals("断网文案 buy.timeout", 1, host.countText(host.text(AuthManageVisualFixtures.wording("offline"))))
        assertEquals("不冒充服务端错误（无 trace6）", 0, host.countText(AuthManageVisualFixtures.trace6, substring = true))
        assertTrue(
            "【重试】可点",
            host
                .node(AuthManageTags.RETRY)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Disabled) == null,
        )
    }

    // ---------------------------------------------------------------- ⑧ 返回 / 离页

    @Test fun redLine8_backButtonAndSystemBackEachCallOnBackOnce() {
        host.launch("normal")
        host.tap(host.back())
        assertEquals("顶栏返回 → onBack 1 次", 1, host.backs)
        host.systemBack()
        assertEquals("系统返回 → onBack 再 1 次", 2, host.backs)
        counts(list = 1, what = "返回零请求")
    }

    @Test fun redLine8_leavingCancelsInFlightAndLateResponsesChangeNothing() {
        host.launch("both_unbound")
        val pddOld = host.clickAction(host.node(host.grantTag("pdd")))
        host.tap(host.grantTag("taobao"))
        val inFlight = host.api.held(GRANT)
        host.pop()
        assertTrue("离页取消在途（cancelOwned）", host.api.cancelled.isNotEmpty())
        rule.runOnUiThread { inFlight.result.complete(AuthManageVisualFixtures.grant("taobao_web_code")) }
        host.idle()
        assertEquals("迟到响应：拉起 0、外跳 0", 0, host.ports.launches)
        rule.runOnUiThread { pddOld() }
        host.idle()
        counts(list = 1, grant = 1, what = "离页后旧回调 0 请求")
        assertEquals("离页后零客服", 0, host.contacts.size)
    }

    @Test fun redLine8_leavingDuringTheGrantStepNeverBinds() {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.grant("taobao_web_code")
        val step = CompletableDeferred<AuthStepResult>()
        host.ports.hold = step
        host.launch("taobao_unbound")
        host.tap(host.grantTag("taobao"))
        assertEquals("授权页已拉起", 1, host.ports.webGrants.size)
        host.pop()
        rule.runOnUiThread {
            step.complete(
                AuthStepResult.Credential(
                    com.couli.feature.trade.AuthCredential
                        .WebCode("late"),
                ),
            )
        }
        host.idle()
        counts(list = 1, grant = 1, bind = 0, what = "离页后授权回来：不绑定")
    }

    // ---------------------------------------------------------------- ⑨ 显示红线 / 可点与读屏节点集合

    @Test fun redLine9_pageShowsOnlyDictionaryWordsAndNoAccountFields() {
        listOf("with_jd", "blocked").forEach { name ->
            host.launch(name)
            val shown =
                rule
                    .onAllNodes(
                        androidx.compose.ui.test
                            .SemanticsMatcher("any") { true },
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes()
                    .flatMap { n ->
                        n.config
                            .getOrNull(SemanticsProperties.Text)
                            .orEmpty()
                            .map { it.text } +
                            n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    }.filter { it.isNotBlank() }
                    .toSet()
            val allowed =
                setOf(
                    host.text(BACK_KEY),
                    host.text(TITLE_KEY),
                    host.name("taobao"),
                    host.name("pdd"),
                    host.text(AUTHORIZED_KEY),
                    host.text(UNAUTHORIZED_KEY),
                    host.text(GRANT_KEY),
                    host.text(BLOCKED_KEY),
                    host.text(CONTACT_KEY),
                    host.text(REBIND_KEY),
                )
            assertEquals("$name：页面文字只来自字典白名单（无账号名 / 昵称 / 头像 / 授权时间 / 京东）", emptySet<String>(), shown - allowed)
            val forbidden =
                AuthManageVisualFixtures.data
                    .getValue("pageForbidden")
                    .jsonObject
                    .getValue("words")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            forbidden.forEach { w -> assertTrue("$name：不出现「$w」", shown.none { it.contains(w) }) }
            assertTrue("更换只出现在底部说明里", shown.filter { it.contains("更换") }.all { it == host.text(REBIND_KEY) })
            host.pop()
        }
    }

    @Test fun redLine9_clickableSetEqualsTheOldSet() {
        host.launch("both_unbound")
        host.api.sticky[GRANT] = AuthManageVisualFixtures.failure("c30153")
        host.tap(host.grantTag("taobao"))
        host.settle("停用") { host.has(AuthManageTags.CONTACT) }
        val clicks = host.clickables().map { n -> host.spokenOf(n).joinToString("|") }
        assertEquals(
            "可点节点集合 = 返回 + 【联系客服】×停用行 + 【去授权】×可授权行（不多出新入口）",
            listOf(host.text(BACK_KEY), host.text(CONTACT_KEY), host.text(GRANT_KEY)).sorted(),
            clicks.sorted(),
        )
        host.pop()
        host.api.calls.clear()
        host.launchListError(AuthManageVisualFixtures.failure("c50001"))
        assertEquals(
            "错误态可点集合 = 返回 + 【重试】",
            listOf(host.text(BACK_KEY), host.text(RETRY_KEY)).sorted(),
            host.clickables().map { host.spokenOf(it).joinToString("|") }.sorted(),
        )
    }

    /** G-A08（底版红）：读屏节点集合 = 返回、标题、「淘宝，已授权」一项、「拼多多，未授权」一项、【去授权】、底部说明；按钮不并进状态节点。 */
    @Test fun redLine9_readableNodeSetMergesNameAndStatus() {
        host.launch("normal")
        // 合并写法两种都认：mergeDescendants（Text = [名, 状态]）或整组 contentDescription「淘宝，已授权」——去标点后连读相同
        val nodes = host.readOrder().map { spoken(host.spokenOf(it)) }
        val want =
            listOf(
                spoken(listOf(host.text(BACK_KEY))),
                spoken(listOf(host.text(TITLE_KEY))),
                spoken(listOf(host.name("taobao"), host.text(AUTHORIZED_KEY))),
                spoken(listOf(host.name("pdd"), host.text(UNAUTHORIZED_KEY))),
                spoken(listOf(host.text(GRANT_KEY))),
                spoken(listOf(host.text(REBIND_KEY))),
            )
        assertEquals("读屏节点（合并树，含顺序；底版名与状态各一项，红）", want, nodes)
        val status = host.readOrder().first { host.spokenOf(it).contains(host.name("taobao")) }
        assertNull("平台名 + 状态节点不可点（按钮单独一项）", status.config.getOrNull(SemanticsActions.OnClick))
    }

    private fun spoken(parts: List<String>): String = parts.joinToString("").filterNot { it in PUNCT || it.isWhitespace() }

    private companion object {
        const val PUNCT = "，,、。.；;："
    }
}
