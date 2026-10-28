package com.couli.feature.web

import android.webkit.WebView
import android.widget.FrameLayout
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * 评审 r1 S0-01 的设备取证（v3 合并门槛，P / F 各跑）：旧文档的迟到写入——setInterval(0) 与 pagehide 里属性赋值 / setItem 写
 * local、session，document.cookie，IndexedDB，sendBeacon 与 fetch(keepalive)（回环服务把切号后到达的扣到 rotate 成功之后才带
 * Set-Cookie 放出）——新文档与新可信存储都读不到；冷启动残留；进程内首次退出登录（无手动基线）。
 */
@RunWith(Parameterized::class)
class Fx25WebLateWriteTest(
    private val mode: Fx25WebMode,
) {
    @Test
    fun s0LateWritesFromTheRetiredDocumentNeverReachTheNewDocumentOrStore() {
        Fx25WebFixture(mode).use { f ->
            val page = f.open()
            val oldWeb = checkNotNull(f.onMain { page.web })
            val old = f.probe(page).read("window.fx25Document")
            val net = if (mode == Fx25WebMode.PROFILE) "always" else "pagehide"
            f.probe(page).fire("window.fx25Arm('$net')")
            f.probe(page).await("旧文档的定时写入在跑", "window.fx25Armed && window.fx25Writes > 20") { it == "true" }
            val before = f.readLate(oldWeb)
            assertEquals("正对照：属性赋值写 localStorage 真的写进去了", "interval", before.getValue("localProperty").jsonPrimitive.content)
            assertEquals("正对照：setItem 写 sessionStorage", "interval", before.getValue("sessionSetItem").jsonPrimitive.content)
            assertTrue("正对照：document.cookie", before.getValue("cookie").jsonPrimitive.content == "true")
            if (mode == Fx25WebMode.PROFILE) {
                WebWait.until("正对照：切号前的 beacon / keepalive 已到服务端") { f.lateBeforeSwitch.get() > 0 }
            }
            f.releaseLateAfterRotation()
            f.switch()
            assertTrue(f.settle())
            f.awaitDocument(page, old)
            assertTrue(f.onMain { oldWeb.destroyedByProduct })
            WebWait.until("扣住的迟到响应都已放出") { f.lateServed.get() == f.lateBeforeSwitch.get() + f.lateHeld.get() }
            val web = checkNotNull(f.onMain { page.web })
            assertEquals("新文档读不到任何迟到写入", Fx25WebFixture.LATE_EMPTY, f.readLate(web))
            assertEquals(Fx25WebFixture.EMPTY, f.readStorage(web))
            assertEquals("新可信存储的 Cookie 罐里没有旧文档的任何 Cookie", "", f.trustedCookies())
            // 放出的迟到响应再给一次落盘机会后复读
            f.probe(page).fire("location.reload()")
            f.awaitDocument(page, f.probe(page).read("window.fx25Document"))
            assertEquals(Fx25WebFixture.LATE_EMPTY, f.readLate(checkNotNull(f.onMain { page.web })))
            assertEquals("", f.trustedCookies())
        }
    }

    /**
     * 冷启动残留（门槛，v4 口径：D10-310 (5) + 编排 2026-10-27 23:04 照准）。
     * P：系统 WebView 把本进程挂过的配置文件一直视为「在用」，同进程内删不掉（实现方诊断：旧 WebView 全部销毁后 31 s / 62 次仍 in-use），
     * 所以设备上不再要求「同进程重启后删掉」，改为证明：旧配置文件名已持久记入 retired[]；删不掉不阻塞事务、也不阻塞冷启动加载，
     * 各计一次埋点；旧配置文件此后永不再挂到任何可信 WebView；冷启动新文档 / 活动配置文件读不到旧数据（旧数据确实还留在旧配置文件里，
     * 作正对照）。「冷启动时删除被调用、in-use 时保留且不阻塞、删成功移出列表」由 JVM 端口替身证明（R.i7OldProfileDeleteFailure…、
     * R.i7SuccessfulTransactionDiscards…）。
     * F：事务在换存储前中断，冷启动在加载可信页之前补完清理。
     */
    @Test
    @Suppress("LongMethod") // 一个跨「重启」的门槛场景：切号前取证、重启、冷启动读证必须在同一夹具里
    fun i3ColdStartRemovesResidueBeforeAnyTrustedPageLoads() {
        Fx25WebFixture(mode).use { f ->
            val page = f.open()
            f.seed(checkNotNull(f.onMain { page.web }))
            val oldStore = f.onMain { f.store.current() }
            var squatter: WebView? = null
            try {
                if (mode == Fx25WebMode.PROFILE) {
                    // 夹具自身建存储时的那次 discardPending 先结算，之后的计数才归这次切号 / 冷启动
                    WebWait.until("夹具建存储时的删除尝试已结算") { f.onMain { f.store.discards.isNotEmpty() } }
                    val deferredAtStart = f.deferredDiscards()
                    // 测试自建、不加载任何页的 WebView 占住旧配置文件：不论系统 WebView 版本，「仍在使用」都确定成立
                    squatter =
                        f.onMain {
                            WebView(InstrumentationContext.target()).also { web ->
                                WebViewCompat.setProfile(web, oldStore)
                                web.layoutParams = FrameLayout.LayoutParams(1, 1)
                            }
                        }
                    val discardsBefore = f.onMain { f.store.discards.size }
                    f.switch()
                    assertTrue("deleteProfile「仍在使用」不阻塞事务", f.settle())
                    f.awaitDocument(page)
                    WebWait.until("事务后尝试过删除旧配置文件") { f.onMain { f.store.discards.size > discardsBefore } }
                    assertFalse("旧配置文件在用：删除如实报未完成", f.onMain { f.store.discards.last() })
                    assertTrue("删不掉计埋点", f.deferredDiscards() > deferredAtStart)
                    assertTrue(f.onMain { oldStore in ProfileStore.getInstance().allProfileNames })
                    assertTrue("旧配置文件名已持久记入 retired[]", oldStore in f.retiredOnDisk())
                    assertNotEquals(oldStore, f.onMain { f.store.current() })
                    assertEquals(
                        "持久状态的 active 是切号后的新配置文件",
                        f.onMain { f.store.current() },
                        f
                            .persistedStoreState()
                            .getValue("active")
                            .jsonPrimitive.content,
                    )
                    assertTrue("正对照：旧数据确实还留在旧配置文件里", oldProfileCookies(f, oldStore).isNotEmpty())
                } else {
                    f.onMain { f.store.holdRotate = true }
                    f.switch()
                    WebWait.until("旧页已拆、换存储前中断") { f.onMain { f.store.held.isNotEmpty() } }
                }
                val deferredBeforeRestart = f.deferredDiscards()
                f.restart()
                if (mode == Fx25WebMode.PROFILE) {
                    assertTrue("重启后 retired[] 仍记着旧配置文件（删不掉时）", oldStore in f.retiredOnDisk())
                }
                val cold = f.open("cold", expectLoaded = false)
                f.onMain { f.sync.observe(f.identity) }
                assertTrue(f.settle())
                f.awaitDocument(cold)
                if (mode == Fx25WebMode.PROFILE) {
                    WebWait.until("冷启动调用了删除退役存储") { f.onMain { f.store.discards.isNotEmpty() } }
                    assertFalse("旧配置文件仍在用：冷启动删除如实报未完成", f.onMain { f.store.discards.last() })
                    assertTrue("冷启动删不掉计埋点、且没有阻塞加载", f.deferredDiscards() > deferredBeforeRestart)
                    assertTrue("仍删不掉的旧配置文件留在 retired[] 待下次冷启动", oldStore in f.retiredOnDisk())
                    assertEquals("冷启动页挂在活动配置文件上", f.onMain { f.store.current() }, f.onMain { checkNotNull(cold.web).profileName })
                    assertTrue(
                        "旧配置文件此后永不再挂到任何可信 WebView：切号后新建的一律不在旧配置文件上",
                        f.onMain { (page.webs.drop(1) + cold.webs).none { it.profileName == oldStore } },
                    )
                }
                assertEquals(Fx25WebFixture.EMPTY, f.readStorage(checkNotNull(f.onMain { cold.web })))
                assertEquals("", f.trustedCookies())
                assertTrue(f.authEvents(cold).isEmpty())
            } finally {
                f.onMain { squatter?.destroy() }
            }
        }
    }

    /** S0-02：上一个进程留下账号 A 的数据；本进程不建手动基线，第一次身份事件就是退出登录——游客打开的可信页读不到 A 的数据。 */
    @Test
    fun s0FirstLogoutInProcessWithoutManualBaselineHidesPreviousAccountData() {
        Fx25WebFixture(mode).use { f ->
            val page = f.open()
            f.seed(checkNotNull(f.onMain { page.web }))
            f.restart()
            val guestPage = f.open("guest", expectLoaded = false)
            assertEquals("身份比对前不加载", 0, f.onMain { guestPage.loads() })
            f.switch(Fx25WebFixture.GUEST)
            assertTrue(f.settle())
            f.awaitDocument(guestPage)
            assertEquals(Fx25WebFixture.EMPTY, f.readStorage(checkNotNull(f.onMain { guestPage.web })))
            assertEquals("", f.trustedCookies())
            assertFalse(f.onMain { f.owner.read()?.account != null })
        }
    }

    /** 旧配置文件自己的 Cookie 罐里本用例的 Cookie（正对照：残留确实在旧配置文件里，只是不再被挂载）。 */
    private fun oldProfileCookies(
        f: Fx25WebFixture,
        profile: String,
    ): String =
        f.onMain {
            val manager = checkNotNull(ProfileStore.getInstance().getProfile(profile)) { "旧配置文件仍在（删不掉）" }.cookieManager
            manager.flush()
            manager
                .getCookie(f.origin)
                .orEmpty()
                .split("; ")
                .filter { it.startsWith(f.key) }
                .joinToString("; ")
        }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun modes(): List<Array<Any>> = listOf(arrayOf(Fx25WebMode.PROFILE), arrayOf(Fx25WebMode.CLEARING))
    }
}

/** 测试 APK 的目标上下文（建占用旧配置文件的探针 WebView 用，不是可信容器）。 */
private object InstrumentationContext {
    fun target(): android.content.Context =
        androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation()
            .targetContext
}
