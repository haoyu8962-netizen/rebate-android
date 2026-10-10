package com.couli.app.clipboard

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 测试用 Application：把收到的分享记下来（只在测试内存里）。 */
class ShareTestApp :
    Application(),
    ShareIntakeProvider {
    val received = mutableListOf<Pair<String?, String?>>()

    override fun shareIntake(): ShareIntake =
        ShareIntake { mime, text ->
            received += mime to text
            true
        }
}

/**
 * FU-CLIP C6 Android：系统分享专用接收 Activity（K17、K23）。ACTION_SEND + text/plain 当场交给剪贴板模块；以不带任何分享内容的 Intent 拉起
 * MainActivity 并 finish；自己的 Intent 也清空；最近任务恢复、配置变更重建不重复处理；清单只声明 text/plain。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = ShareTestApp::class, sdk = [36])
class ShareReceiverActivityTest {
    private val app: ShareTestApp get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clear() = app.received.clear()

    private fun send(
        text: String?,
        type: String = "text/plain",
        flags: Int = 0,
    ): Intent =
        Intent(Intent.ACTION_SEND).apply {
            setClass(app, ShareReceiverActivity::class.java)
            this.type = type
            if (text != null) putExtra(Intent.EXTRA_TEXT, text)
            addFlags(flags)
        }

    @Test
    fun c6_theShareIsHandedOverOnceAndMainActivityGetsNoContent() {
        val raw = "【淘宝】https://m.tb.cn/h.x 「耳机」 私人备注"
        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, send(raw)).create()
        val activity = controller.get()
        assertEquals(listOf<Pair<String?, String?>>("text/plain" to raw), app.received)
        val main = checkNotNull(shadowOf(activity).nextStartedActivity) { "应拉起 MainActivity" }
        assertEquals(ComponentName(app, MainActivity::class.java), main.component)
        assertNull("MainActivity 的 Intent 不带任何分享内容", main.extras)
        assertNull(main.data)
        assertNull(main.clipData)
        assertTrue((main.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertTrue((main.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0)
        assertTrue((main.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0)
        assertNull("接收 Activity 自己的 Intent 也不留原文", activity.intent.extras)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun k17_recentsRelaunchAndRecreationDoNotHandleAgain() {
        Robolectric
            .buildActivity(
                ShareReceiverActivity::class.java,
                send("https://m.tb.cn/h.x", flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY),
            ).create()
        assertTrue("最近任务恢复不处理", app.received.isEmpty())
        Robolectric.buildActivity(ShareReceiverActivity::class.java, send("https://m.tb.cn/h.x")).create(Bundle())
        assertTrue("重建（有保存状态）不处理", app.received.isEmpty())
    }

    @Test
    fun k23_otherActionsCarryNoText() {
        val view =
            Intent(Intent.ACTION_VIEW).apply {
                setClass(app, ShareReceiverActivity::class.java)
                putExtra(Intent.EXTRA_TEXT, "https://m.tb.cn/h.x")
            }
        Robolectric.buildActivity(ShareReceiverActivity::class.java, view).create()
        assertEquals("不是 ACTION_SEND 不取 EXTRA_TEXT", listOf<Pair<String?, String?>>(null to null), app.received)
    }

    @Test
    fun k23_theManifestOnlyAcceptsPlainText() {
        val pm = app.packageManager
        val text =
            pm.queryIntentActivities(
                Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(app.packageName),
                PackageManager.MATCH_ALL,
            )
        assertTrue(text.any { it.activityInfo.name == ShareReceiverActivity::class.java.name })
        val image =
            pm.queryIntentActivities(
                Intent(Intent.ACTION_SEND).setType("image/png").setPackage(app.packageName),
                PackageManager.MATCH_ALL,
            )
        assertTrue("不声明 image/*", image.none { it.activityInfo.name == ShareReceiverActivity::class.java.name })
        val info = pm.getActivityInfo(ComponentName(app, ShareReceiverActivity::class.java), 0)
        assertTrue("不进最近任务", (info.flags and android.content.pm.ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS) != 0)
        assertTrue("不留历史", (info.flags and android.content.pm.ActivityInfo.FLAG_NO_HISTORY) != 0)
        assertTrue(info.exported)
    }
}
