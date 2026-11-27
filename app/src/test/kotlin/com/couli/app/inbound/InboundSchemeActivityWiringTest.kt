package com.couli.app.inbound

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.MainActivity
import com.couli.feature.trade.WebCodeCallback
import com.couli.feature.trade.WebCodeCallbackSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 测试用 Application：把交到收件箱的回跳记下来（只在测试内存里）。 */
class InboundTestApp :
    Application(),
    WebCodeCallbackSinkProvider {
    val received = mutableListOf<WebCodeCallback>()

    override fun webCodeCallbackSink(): WebCodeCallbackSink = WebCodeCallbackSink { received += it }
}

/**
 * FU-AND-TB-WEBCODE-A 测试：couli 入站中转 Activity（设计稿 §3.1、§5.3；任务书 D10-3561 口径）。
 * - `couli://auth/taobao?…`：解析后先交给收件箱，再以 NEW_TASK | CLEAR_TOP | SINGLE_TOP 拉起**不带 data、不带 code / state** 的 MainActivity
 *   （把 App 带回前台、关掉叠在上面的 Custom Tab，设计稿 §4.1 第 3 条、§5.3 第 4 步），清掉自身 Intent，finish；
 * - `couli://r/…`：原样转给 MainActivity；
 * - 其他 host、解析不过的回跳：忽略并 finish，什么都不交出去；
 * - 从最近任务恢复、配置变更重建：不处理；只读 data，不读 extras。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = InboundTestApp::class, sdk = [36])
class InboundSchemeActivityWiringTest {
    private val app: InboundTestApp get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clear() = app.received.clear()

    private fun view(
        uri: String,
        flags: Int = 0,
    ): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setClass(app, InboundSchemeActivity::class.java)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(flags)
        }

    @Test
    fun w01_taobaoCallbackGoesToTheInboxThenBringsBackMainActivityWithoutData() {
        val activity = Robolectric.buildActivity(InboundSchemeActivity::class.java, view(CALLBACK)).create().get()
        assertEquals("交给收件箱一次", 1, app.received.size)
        val got = app.received.single()
        assertTrue("解析为 Code：$got", got is WebCodeCallback.Code)
        assertEquals("c0de_1", (got as WebCodeCallback.Code).code)
        assertEquals("st_1", got.state)
        assertMainWithoutCallback(shadowOf(activity).nextStartedActivity)
        assertNull("自己的 Intent 不留回跳地址", activity.intent.data)
        assertNull("自己的 Intent 不留 extras", activity.intent.extras)
        assertTrue("立即 finish", activity.isFinishing)
    }

    @Test
    fun w02_errorOneCallbackIsDeliveredAsIncomplete() {
        val activity =
            Robolectric.buildActivity(InboundSchemeActivity::class.java, view("couli://auth/taobao?state=st_1&error=1")).create().get()
        val got = app.received.singleOrNull()
        assertTrue("error=1 也交给收件箱（按未完成）：$got", got is WebCodeCallback.Incomplete)
        assertMainWithoutCallback(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun w03_routeLinkIsForwardedToMainActivityAsIs() {
        val link = "couli://r/Search?q=a"
        val activity = Robolectric.buildActivity(InboundSchemeActivity::class.java, view(link)).create().get()
        val main = shadowOf(activity).nextStartedActivity
        assertTrue("couli://r/ 转给 MainActivity：$main", main != null)
        assertEquals(ComponentName(app, MainActivity::class.java), main!!.component)
        assertEquals("原样转发 data", link, main.dataString)
        assertTrue("路由深链不进收件箱", app.received.isEmpty())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun w04_otherHostsAreIgnored() {
        listOf(
            "couli://oauth/taobao?code=c0de_1&state=st_1",
            "couli://x/y",
            "couli://auth/jd?code=c0de_1&state=st_1",
        ).forEach { uri ->
            app.received.clear()
            val activity = Robolectric.buildActivity(InboundSchemeActivity::class.java, view(uri)).create().get()
            assertTrue("忽略：$uri", app.received.isEmpty())
            val next = shadowOf(activity).nextStartedActivity
            assertTrue("不把地址转给别的页面：$uri → $next", next == null || next.data == null)
            assertTrue("finish：$uri", activity.isFinishing)
        }
    }

    @Test
    fun w05_invalidTaobaoCallbackIsDropped() {
        val activity =
            Robolectric.buildActivity(InboundSchemeActivity::class.java, view("couli://auth/taobao?code=c0de_1")).create().get()
        assertTrue("缺 state：丢弃，不交收件箱", app.received.isEmpty())
        val next = shadowOf(activity).nextStartedActivity
        assertTrue("不把回跳地址转给别的页面：$next", next == null || next.data == null)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun w06_recentsRelaunchAndRecreationDoNotHandleAgain() {
        Robolectric.buildActivity(
            InboundSchemeActivity::class.java,
            view(CALLBACK, flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY),
        ).create()
        assertTrue("最近任务恢复不处理", app.received.isEmpty())
        Robolectric.buildActivity(InboundSchemeActivity::class.java, view(CALLBACK)).create(Bundle())
        assertTrue("重建（有保存状态）不处理", app.received.isEmpty())
    }

    @Test
    fun w07_extrasAreNeverRead() {
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setClass(app, InboundSchemeActivity::class.java)
                putExtra("code", "c0de_1")
                putExtra("state", "st_1")
                putExtra(Intent.EXTRA_TEXT, CALLBACK)
            }
        val activity = Robolectric.buildActivity(InboundSchemeActivity::class.java, intent).create().get()
        assertTrue("没有 data 只有 extras：不处理", app.received.isEmpty())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun w08_positiveControlBeforeRecentsCheck() {
        // w06 的正向对照：同一 Intent 不带历史标记、无保存状态时确实会交付（防止 w06 因为什么都不做而绿）
        Robolectric.buildActivity(InboundSchemeActivity::class.java, view(CALLBACK)).create()
        assertEquals(1, app.received.size)
    }

    /** auth 回跳之后拉起的 MainActivity：带回前台用，不带 data（不转发 couli URI），extras 里没有 code / state 明文。 */
    private fun assertMainWithoutCallback(main: Intent?) {
        assertTrue("auth 回跳后拉起 MainActivity（带回前台、关掉 Custom Tab）：$main", main != null)
        main!!
        assertEquals(ComponentName(app, MainActivity::class.java), main.component)
        assertNull("MainActivity 的 Intent 不带 data（不转发 couli URI）", main.data)
        val extras = main.extras
        val texts = extras?.keySet()?.flatMap { listOf(it, extras.getString(it).orEmpty(), extras.getCharSequence(it)?.toString().orEmpty()) }.orEmpty()
        assertTrue("MainActivity 的 extras 不含 code / state 明文：$texts", texts.none { "c0de_1" in it || "st_1" in it })
        assertTrue((main.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertTrue((main.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0)
        assertTrue((main.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0)
    }

    private companion object {
        const val CALLBACK = "couli://auth/taobao?code=c0de_1&state=st_1"
    }
}
