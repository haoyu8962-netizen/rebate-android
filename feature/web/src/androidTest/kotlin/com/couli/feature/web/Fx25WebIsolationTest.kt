package com.couli.feature.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.couli.core.bridge.BridgeOutcome
import com.couli.core.bridge.TrustedAuthDocument
import com.couli.core.bridge.TrustedAuthSync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I1/I4/I7（v3，P 路径）：判定即隔离；旧 WebView 先拆（about:blank + destroy 确认）再换配置文件；新文档只在 rebuild 出的新 WebView 上。
 * 负向断言都在真实桥回复、导航回调及事务结算后观察。「同 WebView 重载」相关用例（onPageStarted 伪造窗口）随方案作废。
 */
@RunWith(AndroidJUnit4::class)
class Fx25WebIsolationTest {
    @Test
    fun i1EveryMountedDocumentIsFencedAtDecisionAndOnlyRebuiltDocumentsRecover() {
        Fx25WebFixture().use { f ->
            val pages = listOf(f.open(), f.open("under-login", hidden = true), f.open("background", hidden = true))
            val documents = pages.associateWith { f.probe(it).read("window.fx25Document") }
            val old = pages.associateWith { f.onMain { checkNotNull(it.web) } }
            // 扣住 A：旧文档在拆之前仍活着，借此观察判定那一刻起的隔离
            f.onMain { old.values.forEach { it.holdEvent = true } }
            f.onMain {
                f.switch()
                pages.forEach(f::assertNavigationBlocked)
            }
            pages.forEach { f.assertBridgeRejected(it) }
            assertEquals("A 确认前不换存储", 0, f.onMain { f.store.rotations })
            f.onMain { old.values.forEach { it.flushEvents() } }
            assertTrue(f.settle())
            pages.forEach { page ->
                f.awaitDocument(page, documents.getValue(page))
                val previous = old.getValue(page)
                val current = checkNotNull(f.onMain { page.web })
                assertNotSame("rebuild 必须换新 WebView", previous, current)
                assertTrue("旧 WebView 已由产品销毁", f.onMain { previous.destroyedByProduct })
                assertEquals(1, f.onMain { previous.blankLoads })
                assertEquals(f.onMain { f.store.current() }, f.onMain { WebViewCompat.getProfile(current).name })
                assertEquals(
                    listOf(buildJsonObject { put("logged_in", true) }),
                    f
                        .authEvents(
                            page,
                        ).filter { it.getValue("document").toString() == documents.getValue(page) }
                        .map { it.getValue("data") },
                )
                assertTrue("新文档没有收到 A", f.authEvents(page).none { it.getValue("document").toString() != documents.getValue(page) })
                f.probe(page).fire("window.fx25Call('new-document','app.getEnv')")
                WebWait.until("新文档真实桥重新开放") { f.replyCount(page, "new-document") == 1 }
            }
            assertEquals(3, f.onMain { f.envHandlerEntries })
            assertEquals(1, f.onMain { f.store.successfulRotations })
        }
    }

    /** 变异 3 / 变异 1：拆除拿不到确认（about:blank 不提交）= 失败——旧文档保持隔离、不换存储、不建新页、标记保留。 */
    @Test
    fun i1RetireWithoutConfirmationKeepsOldDocumentFencedAndNeverRotates() {
        Fx25WebFixture().use { f ->
            val page = f.open()
            val web = checkNotNull(f.onMain { page.web })
            val bindings = f.onMain { web.bindingHashes.size }
            assertTrue("令牌交付观察点必须先看到正对照", bindings > 0)
            f.onMain { web.holdBlank = true }
            f.switch()
            assertFalse(f.settle())
            assertSame("拆除未确认：仍是旧 WebView", web, f.onMain { page.web })
            assertFalse(f.onMain { web.destroyedByProduct })
            assertEquals(0, f.onMain { f.store.rotations })
            assertNotNull(f.onMain { f.journal.readPending() })
            f.probe(page).read("window.__couliBridgeChannel.postMessage('__couli_hello__'); true")
            f.assertBridgeRejected(page)
            f.assertNavigationBlocked(page)
            assertEquals("隔离中的 hello 不交新令牌", bindings, f.onMain { web.bindingHashes.size })
            assertEquals(1, f.onMain { f.created.size })
            f.onMain {
                web.holdBlank = false
                f.sync.retry()
            }
            assertTrue(f.settle())
            f.awaitDocument(page)
            assertTrue(f.onMain { web.destroyedByProduct })
        }
    }

    @Test
    fun i4NewTrustedContainerCannotStartLoadingDuringTransactionOrAfterFailure() {
        Fx25WebFixture().use { f ->
            val original = f.open()
            f.onMain { f.store.holdRotate = true }
            f.switch()
            WebWait.until("已拆完、等换存储") { f.onMain { f.store.held.isNotEmpty() } }
            val during = f.open("during", expectLoaded = false)
            assertEquals(0, f.onMain { during.loads() })
            f.assertNavigationBlocked(during)
            f.onMain { f.store.release(success = false) }
            assertFalse(f.settle())
            val failed = f.open("after-failure", expectLoaded = false)
            assertEquals(0, f.onMain { failed.loads() })
            f.assertNavigationBlocked(failed)
            f.onMain { f.sync.retry() }
            assertTrue(f.settle())
            listOf(original, during, failed).forEach { f.awaitDocument(it) }
            listOf(original, during, failed).forEach { page ->
                assertEquals("${page.name} 当前 WebView 只加载一次", 1, f.onMain { checkNotNull(page.web).loads })
                assertEquals(f.onMain { f.store.current() }, f.onMain { WebViewCompat.getProfile(checkNotNull(page.web)).name })
            }
            assertEquals(1, f.onMain { f.store.successfulRotations })
        }
    }

    /** D10-310 (1)：A 一直没有执行确认，限时后照样拆、换、建；只记埋点。 */
    @Test
    fun i7UnconfirmedAuthEventDoesNotKeepOldDocumentAlive() {
        Fx25WebFixture().use { f ->
            val page = f.open()
            val old = f.probe(page).read("window.fx25Document")
            val web = checkNotNull(f.onMain { page.web })
            f.onMain { web.holdEvent = true }
            f.switch()
            WebWait.until("A 已投递（脚本被扣住）") { f.onMain { web.heldEvents.isNotEmpty() } }
            assertTrue(f.settle())
            f.awaitDocument(page, old)
            assertTrue(f.onMain { web.destroyedByProduct })
            assertTrue(f.onMain { TrustedAuthSync.METRIC_AUTH_UNCONFIRMED in f.metrics })
            assertTrue("被扣住的 A 不会在新文档上补发", f.authEvents(page).isEmpty())
        }
    }

    @Test
    fun i7RevokedOriginDestroyedPageAndUndeliveredTokenReceiveNoAuthEvent() {
        Fx25WebFixture().use { f ->
            val revoked = f.open("revoked")
            val destroyed = f.open("destroyed")
            f.onMain { destroyed.product.destroy() }
            f.trusted = false
            f.switch(Fx25WebFixture.A.copy(loginEpoch = 2L))
            assertTrue(f.settle())
            assertTrue(f.authEvents(revoked).isEmpty())
            assertTrue(f.authEvents(destroyed).isEmpty())
        }
        Fx25WebFixture().use { f ->
            val page = f.open(holdToken = true, expectLoaded = false)
            WebWait.until("首个文档握手已发出、令牌绑定被扣住") { f.onMain { page.web?.heldBindings?.isNotEmpty() == true } }
            // v4：握手在文档开始阶段就发出，页面脚本（订阅 auth.changed、定义 fx25Call）要等 /__CACHE__ 之后才执行；
            // 先等脚本就绪（满足即返回，上限内），否则「没收到 A」可能只是还没订阅，后面的调用也会因 fx25Call 未定义而发不出去。
            f.awaitDocument(page)
            f.probe(page).await("页面脚本已就绪", "typeof window.fx25Call === 'function'") { it == "true" }
            val web = checkNotNull(f.onMain { page.web })
            assertTrue(f.onMain { web.bindingHashes.isEmpty() })
            f.switch(Fx25WebFixture.A.copy(loginEpoch = 2L))
            assertTrue(f.settle())
            assertTrue(f.authEvents(page).isEmpty())
            f.onMain { web.flushBindings() }
            f.probe(page).fire("window.fx25Call('positive-binding','app.getEnv')")
            WebWait.until("放开真实令牌交付后才可调用") { f.replyCount(page, "positive-binding") == 1 }
            assertEquals(1, f.onMain { f.envHandlerEntries })
        }
    }

    @Test
    fun i1LateH5TokenReplyFromOldDocumentCannotReachNewDocument() {
        Fx25WebFixture().use { f ->
            val page = f.open()
            val oldDocument = f.probe(page).read("window.fx25Document")
            val delayed = CompletableDeferred<BridgeOutcome>()
            f.onMain { f.tokenGate = delayed }
            f.probe(page).fire("window.fx25Call('old-h5-token','auth.getH5Token')")
            WebWait.until("真实 token 处理器已经挂起") { f.onMain { f.tokenHandlerEntries == 1 } }
            f.switch()
            assertTrue(f.settle())
            f.awaitDocument(page, oldDocument)
            f.onMain { delayed.complete(BridgeOutcome.Ok(buildJsonObject { put("token", "old-secret") })) }
            f.probe(page).fire("window.fx25Call('settlement-fence','app.getEnv')")
            WebWait.until("迟到完成后的真实桥结算栅栏") { f.replyCount(page, "settlement-fence") == 1 }
            val leaked =
                f.onMain {
                    page.receipts.any {
                        val reply = it["reply"]?.jsonObject
                        reply?.get("id")?.jsonPrimitive?.content == "old-h5-token" && reply["code"]?.jsonPrimitive?.content == "0"
                    }
                }
            assertFalse(leaked)
            assertEquals("0", f.probe(page).read("window.fx25Replies.filter(r => r.id === 'old-h5-token').length"))
        }
    }

    /** 旧文档的绑定脚本在新 WebView 的新文档里重放无效；旧 WebView（及其传输层）已销毁。 */
    @Test
    fun i1OldBindingCannotBindTheRebuiltDocumentAndOldWebViewIsGone() {
        Fx25WebFixture().use { f ->
            val page = f.open()
            val old = f.probe(page).read("window.fx25Document")
            val oldWeb = checkNotNull(f.onMain { page.web })
            val oldBinding = f.onMain { oldWeb.bindingScripts.last() }
            f.switch()
            assertTrue(f.settle())
            f.awaitDocument(page, old)
            assertTrue(f.onMain { oldWeb.destroyedByProduct })
            assertEquals("旧文档令牌绑定脚本不能在新文档生效", "false", f.probe(page).read(oldBinding))
            f.probe(page).fire("window.fx25Call('after-replay','app.getEnv')")
            WebWait.until("回放旧令牌后新桥仍正常结算") { f.replyCount(page, "after-replay") == 1 }
            assertEquals(1, f.onMain { f.envHandlerEntries })
        }
    }

    @Test
    fun i7SameAccountEpochAndLogoutHaveDifferentEffectsAndMinimalPayloads() {
        Fx25WebFixture().use { f ->
            val page = f.open()
            val document = f.probe(page).read("window.fx25Document")
            val web = checkNotNull(f.onMain { page.web })
            f.switch(Fx25WebFixture.A.copy(loginEpoch = 2L))
            assertTrue(f.settle())
            assertSame("同号换世代不拆", web, f.onMain { page.web })
            assertEquals(document, f.probe(page).read("window.fx25Document"))
            assertEquals(0, f.onMain { f.store.rotations })
            f.switch(Fx25WebFixture.GUEST)
            assertTrue(f.settle())
            f.awaitDocument(page, document)
            assertNotSame(web, f.onMain { page.web })
            assertEquals(
                listOf(buildJsonObject { put("logged_in", true) }, buildJsonObject { put("logged_in", false) }),
                f.authEvents(page).map { it.getValue("data") },
            )
        }
    }

    /** D10-310 (6) 注入计数：页面先后用过的每一份可信 WebView 都出自产品工厂，且建好时就挂在当时的活动配置文件上。 */
    @Test
    fun i6EveryTrustedWebViewComesFromTheFactoryOnTheActiveStore() {
        Fx25WebFixture().use { f ->
            val pages = listOf(f.open(), f.open("second", hidden = true))
            val before = f.onMain { f.store.current() }
            f.switch()
            assertTrue(f.settle())
            pages.forEach { f.awaitDocument(it) }
            val after = f.onMain { f.store.current() }
            assertFalse("P 路径换了新配置文件", before == after)
            pages.forEach { page ->
                val webs = f.onMain { page.webs.toList() }
                assertEquals(2, webs.size)
                assertTrue(f.onMain { webs.all { it in f.created } })
                assertEquals(listOf(before, after), f.onMain { webs.map { it.profileName } })
            }
            assertEquals("没有绕过工厂的可信 WebView", f.onMain { pages.sumOf { it.webs.size } }, f.onMain { f.created.size })
        }
    }

    /**
     * 评审 v2 r1 S1-01（load 路径）：主线程打开页、产品已读过「未隔离」、正在经工厂建 WebView 时，另一线程判定切号（同步隔离 + 写标记）。
     * 断言：竞态里建出的这份 WebView 从头到尾不发出真实加载、不交令牌、请求被拦（拆除前扣住 about:blank 观察），直到新存储上重建：
     * 页面最终换成新存储上的新 WebView、新文档桥可用，竞态那份已由产品销毁。交错由钩子 + 闩锁确定性制造，不靠 sleep。
     */
    @Test
    fun i1JudgementRacingControllerCreationOnLoadStillFencesTheNewController() {
        Fx25WebFixture().use { f ->
            var raced: Fx25WebFixture.ObservedWebView? = null
            f.onMain {
                f.onNextCreate = { web ->
                    raced = web
                    web.holdBlank = true
                    f.judgeFromOtherThread(Fx25WebFixture.B)
                }
            }
            val page = f.open("raced-load", expectLoaded = false)
            val web = checkNotNull(f.onMain { raced }) { "钩子必须在产品建 WebView 时触发" }
            assertRacedControllerFencedUntilRebuilt(f, page, web)
        }
    }

    /** 评审 v2 r1 S1-01（rebuild 路径）：上一轮 rebuild 正在建新 WebView 时，另一线程判定再次切号。断言同上。 */
    @Test
    fun i1JudgementRacingControllerCreationOnRebuildStillFencesTheNewController() {
        Fx25WebFixture().use { f ->
            val page = f.open()
            var raced: Fx25WebFixture.ObservedWebView? = null
            f.onMain {
                f.onNextCreate = { web ->
                    raced = web
                    web.holdBlank = true
                    f.judgeFromOtherThread(Fx25WebFixture.GUEST)
                }
            }
            f.switch()
            WebWait.until("rebuild 建 WebView 时钩子已触发") { f.onMain { raced != null } }
            assertRacedControllerFencedUntilRebuilt(f, page, checkNotNull(f.onMain { raced }))
        }
    }

    private fun assertRacedControllerFencedUntilRebuilt(
        f: Fx25WebFixture,
        page: Fx25WebFixture.Page,
        raced: Fx25WebFixture.ObservedWebView,
    ) {
        WebWait.until("新一轮已开始拆竞态里建出的 WebView（或产品已直接销毁它）") {
            f.onMain { raced.blankLoads > 0 || raced.destroyedByProduct }
        }
        assertEquals("竞态里建出的控制器不得发出真实加载", 0, f.onMain { raced.loads })
        assertTrue("竞态里建出的文档不得拿到令牌", f.onMain { raced.bindingHashes.isEmpty() })
        if (f.onMain { !raced.destroyedByProduct && page.web === raced }) {
            f.assertNavigationBlocked(page)
            f.onMain { raced.flushBlank() }
        }
        assertTrue(f.settle())
        f.awaitDocument(page)
        val current = checkNotNull(f.onMain { page.web })
        assertNotSame("重建在新 WebView 上", raced, current)
        assertTrue("竞态那份已由产品销毁", f.onMain { raced.destroyedByProduct })
        assertEquals("竞态那份从未加载", 0, f.onMain { raced.loads })
        assertEquals(f.onMain { f.store.current() }, f.onMain { WebViewCompat.getProfile(current).name })
        assertNotEquals("新文档在竞态之后的新存储上", f.onMain { raced.profileName }, f.onMain { current.profileName })
        f.probe(page).fire("window.fx25Call('after-race','app.getEnv')")
        WebWait.until("新文档真实桥可用") { f.replyCount(page, "after-race") == 1 }
    }

    /** 评审 v2 r1 S2-04：工厂挂不上配置文件时，打开可信页不得抛出（生产里是 App 崩溃），进错误层；不落回默认配置文件；之后可再打开。 */
    @Test
    fun i2ProfileAttachFailureOnLoadShowsErrorLayerInsteadOfCrashing() {
        Fx25WebFixture().use { f ->
            f.onMain { f.failNextCreate = true }
            val thrown = runCatching { f.open("attach-fails", expectLoaded = false) }.exceptionOrNull()
            assertNull("配置文件挂不上时 load 不得抛出：${thrown?.message}", thrown)
            val page = f.onMain { f.pages.last() }
            WebWait.until("显示错误层") { f.onMain { f.callbacks.loadFailures > 0 } }
            assertEquals("没有任何可信 WebView 发出加载（不落回默认配置文件）", 0, f.onMain { f.created.sumOf { it.loads } })
            assertTrue(f.onMain { page.web == null || page.web?.loads == 0 })
            assertTrue("重试：工厂恢复后可正常打开", f.onMain { page.product.load("${f.origin}/attach-fails") })
            f.awaitDocument(page)
            assertEquals(f.onMain { f.store.current() }, f.onMain { checkNotNull(page.web).profileName })
        }
    }

    /** 评审 v2 r1 S2-05：一页的渲染进程已退出，切号事务不得因等不到它的 about:blank 而失败；两页都在新存储上重建。 */
    @Test
    fun i1RendererGonePageDoesNotBlockTheSwitch() {
        assumeTrue(
            "环境前提：系统 WebView 支持取得并终止渲染进程",
            WebViewFeature.isFeatureSupported(WebViewFeature.GET_WEB_VIEW_RENDERER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_TERMINATE),
        )
        Fx25WebFixture().use { f ->
            val dead = f.open("renderer-gone")
            val alive = f.open("alive", hidden = true)
            val deadWeb = checkNotNull(f.onMain { dead.web })
            val aliveDocument = f.probe(alive).read("window.fx25Document")
            f.onMain {
                deadWeb.rendererGone = true
                assertTrue("终止渲染进程", checkNotNull(WebViewCompat.getWebViewRenderProcess(deadWeb)).terminate())
            }
            f.switch()
            assertTrue("渲染进程已退出的页不阻塞切号", f.settle())
            f.awaitDocument(dead)
            f.awaitDocument(alive, aliveDocument)
            assertNotSame(deadWeb, f.onMain { dead.web })
            assertTrue(f.onMain { deadWeb.destroyedByProduct })
            listOf(dead, alive).forEach { page ->
                assertEquals(f.onMain { f.store.current() }, f.onMain { checkNotNull(page.web).profileName })
            }
            assertEquals(1, f.onMain { f.store.successfulRotations })
        }
    }

    /**
     * v5（评审 v2 r2 S2-1）：打开页时，判定落在「产品读过隔离位之后、取比较基准 / 建控制器之前」（钩子挂在产品经工厂读活动存储的那一刻）。
     * 在产品 `load` 返回的同一个主线程消息里检查：不加载、回 false、页面隔离；如果产品建了控制器（即使还没有文档），它必须已经隔离——
     * 真实 shouldOverrideUrlLoading 拦截、后台拦截入口回 204/403，不能留一份「页面隔离、控制器不隔离」的空控制器。之后事务结算，页面在
     * 新存储上的新 WebView 里重建，竞态那份已由产品销毁、从未加载。
     * r2 实现应红：基准在读存储之后才取，build 不补隔离，`load` 看到隔离位只 return false，`shouldInterceptRequest` 回 null。
     */
    @Test
    fun s21JudgementBeforeControllerCreationOnLoadLeavesNoUnisolatedController() {
        Fx25WebFixture().use { f ->
            var raced: Fx25WebFixture.ObservedWebView? = null
            var checked = false
            val page =
                f.open(
                    "raced-before-create",
                    expectLoaded = false,
                    beforeLoad = { f.judgeOnNextStoreRead(Fx25WebFixture.B) },
                    afterLoad = { opened, loaded ->
                        checked = true
                        assertEquals("判定已在产品读活动存储时注入", 1, f.injectedJudgements)
                        assertFalse("隔离中 load 回 false", loaded)
                        assertTrue("页面隔离", opened.product.authBlocked)
                        raced = opened.web
                        val controller = opened.product.controller
                        if (controller != null || raced != null) {
                            val web = checkNotNull(raced) { "有控制器就有 WebView" }
                            assertWebViewFenced(web, f.origin)
                            assertFalse("控制器层 load 也被拦下", checkNotNull(controller).load("${f.origin}/raced-before-create"))
                        }
                        assertEquals("竞态里没有任何真实加载", 0, opened.loads())
                    },
                )
            assertTrue("同步检查已执行", checked)
            f.assertNoViolations()
            assertTrue(f.settle())
            f.awaitDocument(page)
            val current = checkNotNull(f.onMain { page.web })
            raced?.let { web ->
                assertNotSame("重建在新 WebView 上", web, current)
                assertTrue("竞态那份已由产品销毁", f.onMain { web.destroyedByProduct })
                assertEquals("竞态那份从未加载", 0, f.onMain { web.loads })
                assertTrue("竞态那份没拿到令牌", f.onMain { web.bindingHashes.isEmpty() })
            }
            assertEquals(f.onMain { f.store.current() }, f.onMain { WebViewCompat.getProfile(current).name })
            f.probe(page).fire("window.fx25Call('after-open-race','app.getEnv')")
            WebWait.until("新文档真实桥可用") { f.replyCount(page, "after-open-race") == 1 }
        }
    }

    /**
     * v5（评审 v2 r2 S1-01 页面一半）：协调器在锁内选定本页为 rebuild 目标、取了比较基准（`beginRebuild`）之后，另一线程判定再次切号，
     * 之后页面的 rebuild() 才被调用。按协调器的调用顺序在主线程同一个消息里直接驱动产品页面登记给协调器的那份文档：
     * 基准 → 他线程判定（同步返回）→ rebuild。断言：rebuild 不回 true、不发出任何真实加载；页面此刻挂着的 WebView（如有）被隔离；
     * 之后协调器的这一轮照常结算，页面在新存储上重建。
     * 实现若没有页面侧基准入口（只在协调器侧兜底），跳过并写明——那一半由 JVM `Fx25RebuildRaceTest` 覆盖。
     * 变异：TrustedWebPage.rebuild 忽略基准、在入口读序号（`val seen = isolations`）→ 这里建新 WebView 并在旧存储上加载，红。
     */
    @Test
    fun i1PageRebuildComparesAgainstTheBaselineTakenAtSelection() {
        Fx25WebFixture().use { f ->
            val page = f.open()
            val document = f.onMain { f.documentOf(page) }
            val begin = TrustedAuthDocument::class.java.methods.firstOrNull { it.name == "beginRebuild" && it.parameterCount == 0 }
            assumeTrue("实现没有页面侧的选定基准入口（beginRebuild），页面一半不适用；协调器一半见 JVM Fx25RebuildRaceTest", begin != null)
            val results = mutableListOf<Boolean>()
            f.onMain {
                val createdBefore = f.created.size
                checkNotNull(begin).invoke(document)
                f.judgeFromOtherThread(Fx25WebFixture.B)
                document.rebuild(f.store.current()) { results += it }
                val fresh = f.created.drop(createdBefore)
                assertTrue("基准之后有判定：rebuild 不得发出任何真实加载", fresh.all { it.loads == 0 })
                assertFalse("基准之后有判定：rebuild 不得回 true", results.any { it })
                page.web?.let { assertWebViewFenced(it, f.origin) }
            }
            f.assertNoViolations()
            assertTrue(f.settle())
            f.awaitDocument(page)
            assertFalse("迟到的结果也不能是 true", f.onMain { results.any { it } })
            val current = checkNotNull(f.onMain { page.web })
            assertEquals(f.onMain { f.store.current() }, f.onMain { WebViewCompat.getProfile(current).name })
            assertFalse(f.onMain { page.product.authBlocked })
        }
    }

    /**
     * v5（评审 v2 r2 S2-2）：切号的 rebuild 进行中（新 WebView 已建、入口已加载、令牌绑定被扣住），用户关掉这一页——关闭中、协调器那边
     * 还没注销。这不能让整轮切号失败：事务结算为成功、不进错误层、标记已删、归属 = B；关掉的页所有 WebView 都由产品销毁、没拿到令牌；
     * 之后新开的可信页直接在新存储上正常加载（没有遗留的失败待办把它挡住）。
     * 只放一页：协调器这一批只等它一页，关页时它的结果就是最后一个，结算时它一定还在名单里（多页时另一页若还没回结果，结算会晚于注销，
     * 当前实现也可能碰巧过，测不出）。
     * 当前实现（只在 rebuildOnMain 入口看 destroyed、结算只滤已注销）应红：关页时 failAwaiting 先回 false，协调器在注销之前结算，本轮失败。
     */
    @Test
    fun s22PageClosedWhileItsRebuildIsInFlightDoesNotFailTheSwitch() {
        Fx25WebFixture().use { f ->
            val closing = f.open("closing-during-rebuild", holdToken = true, expectLoaded = false)
            f.awaitDocument(closing)
            f.switch()
            WebWait.until("关闭页的 rebuild 已建出新 WebView、在等令牌绑定") {
                f.onMain {
                    val newest = closing.webs.lastOrNull()
                    closing.webs.size == 2 && closing.web === newest && newest?.heldBindings?.isNotEmpty() == true
                }
            }
            assertEquals("关闭页的新文档已发出加载", 1, f.onMain { closing.webs.last().loads })
            f.onMain { closing.product.destroy() }
            assertTrue("关闭中的页不得让整轮切号失败", f.settle())
            assertEquals("不进错误层", 0, f.onMain { f.callbacks.loadFailures })
            assertNull("标记已删", f.onMain { f.journal.readPending() })
            assertEquals(Fx25WebFixture.B.account, f.onMain { f.owner.read()?.account })
            WebWait.until("关闭页的 WebView 都已由产品销毁") { f.onMain { closing.webs.all { it.destroyedByProduct } } }
            assertTrue("关闭页的文档没拿到令牌", f.onMain { closing.webs.all { it.bindingHashes.isEmpty() } })
            val later = f.open("after-close")
            assertFalse("之后新开的页不隔离", f.onMain { later.product.authBlocked })
            assertEquals(f.onMain { f.store.current() }, f.onMain { checkNotNull(later.web).profileName })
            f.probe(later).fire("window.fx25Call('after-close','app.getEnv')")
            WebWait.until("新开页真实桥可用") { f.replyCount(later, "after-close") == 1 }
        }
    }

    /** 主线程：[web] 的真实导航与后台拦截入口都按隔离处理。 */
    private fun assertWebViewFenced(
        web: Fx25WebFixture.ObservedWebView,
        origin: String,
    ) {
        val request = Fx25WebFixture.request("$origin/forbidden-navigation")
        assertTrue("真实 shouldOverrideUrlLoading 必须拦截", web.webViewClient.shouldOverrideUrlLoading(web, request))
        val response = web.webViewClient.shouldInterceptRequest(web, request)
        assertTrue("后台拦截入口回 204/403（控制器已隔离）", response != null && response.statusCode in setOf(204, 403))
    }

    /**
     * 评审 v2 r1 S2-07（APP-04）：可信源被远程撤下后，已打开文档、以及之后在该源上打开的文档，桥调用都立即按 90403 结算，不挂住；
     * 处理器不执行。
     */
    @Test
    fun app04RevokedOriginBridgeCallsSettleImmediatelyWith90403() {
        Fx25WebFixture().use { f ->
            val opened = f.open()
            f.onMain { f.trusted = false }
            f.probe(opened).fire("window.fx25Call('revoked-open','app.getEnv')")
            WebWait.until("已打开文档的调用已结算") { f.replyCount(opened, "revoked-open") == 1 }
            val later = f.open("opened-after-revoke")
            f.probe(later).await("页面脚本已就绪", "typeof window.fx25Call === 'function'") { it == "true" }
            f.probe(later).fire("window.fx25Call('revoked-later','app.getEnv')")
            WebWait.until("撤下后打开的文档调用已结算（不挂住）") { f.replyCount(later, "revoked-later") == 1 }
            listOf(opened to "revoked-open", later to "revoked-later").forEach { (page, id) ->
                val code =
                    f.onMain {
                        page.receipts
                            .single {
                                it["reply"]
                                    ?.jsonObject
                                    ?.get("id")
                                    ?.jsonPrimitive
                                    ?.content == id
                            }.getValue("reply")
                            .jsonObject
                            .getValue("code")
                            .jsonPrimitive.content
                    }
                assertEquals("$id 按 90403 拒绝", "90403", code)
            }
            assertEquals("处理器不执行", 0, f.onMain { f.envHandlerEntries })
        }
    }
}
