package com.couli.app.trade

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.trade.AuthStepResult
import com.couli.feature.trade.WebCodeAttempt
import com.couli.feature.trade.WebCodeCallback
import com.couli.feature.trade.WebCodeInbox
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FU-AND-TB-WEBCODE-A 测试：本机尝试记录（设计稿 §3.1、§5.5 第 1 条、§8）。只落 sha256(state) 与时间；不落明文 state、不落 code；
 * 只存 1 条，新的覆盖旧的；clear 清空。存储内容按 SharedPreferences 的全部键值与数据目录下的全部文件核对。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class WebCodeAttemptStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs: SharedPreferences = context.getSharedPreferences("webcode-attempts-test", Context.MODE_PRIVATE)

    /** SharedPreferences 的全部键与值（按文本）。 */
    private fun stored(): String =
        prefs.all.entries.joinToString("\n") { (key, value) -> "$key=$value" }

    /** 数据目录下全部文件的文本（apply 未落盘时可能为空，只用来核对「没有」）。 */
    private fun onDisk(): String =
        context.dataDir
            .walkTopDown()
            .filter(File::isFile)
            .joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }

    @Test
    fun s01_recordKeepsOnlyTheHashAndTime() {
        val store = SharedPreferencesWebCodeAttempts(prefs)
        store.record(PLAIN_STATE, 1_700_000_000_123L)
        assertEquals(WebCodeAttempt(webCodeSha256(PLAIN_STATE), 1_700_000_000_123L), store.current())
        val text = stored()
        assertTrue("落的是 sha256(state)：$text", text.contains(webCodeSha256(PLAIN_STATE)))
        assertFalse("不落明文 state：$text", text.contains(PLAIN_STATE))
        assertFalse("磁盘上也没有明文 state", onDisk().contains(PLAIN_STATE))
    }

    @Test
    fun s02_recordSurvivesANewInstance() {
        SharedPreferencesWebCodeAttempts(prefs).record(PLAIN_STATE, 42L)
        assertEquals(
            "同一命名空间新建实例仍读得到（进程内恢复）",
            WebCodeAttempt(webCodeSha256(PLAIN_STATE), 42L),
            SharedPreferencesWebCodeAttempts(prefs).current(),
        )
    }

    @Test
    fun s03_newerAttemptReplacesTheOlderOne() {
        val store = SharedPreferencesWebCodeAttempts(prefs)
        store.record("older_state-1", 1L)
        store.record("newer_state-2", 2L)
        assertEquals(WebCodeAttempt(webCodeSha256("newer_state-2"), 2L), store.current())
        assertFalse("只存 1 条：旧的哈希不留", stored().contains(webCodeSha256("older_state-1")))
    }

    @Test
    fun s04_clearRemovesTheAttempt() {
        val store = SharedPreferencesWebCodeAttempts(prefs)
        store.record(PLAIN_STATE, 1L)
        assertNotNull("先记下", store.current())
        store.clear()
        assertNull(store.current())
        assertFalse("清掉后哈希也不在", stored().contains(webCodeSha256(PLAIN_STATE)))
    }

    @Test
    fun s05_fullAuthorizationNeverPersistsCodeOrPlainState() =
        runTest {
            val store = SharedPreferencesWebCodeAttempts(prefs)
            val inbox = WebCodeInbox()
            val authorizer = BrowserWebCodeAuthorizer(RecordingLauncher(), inbox, store, TestReturnSignal(), clock = { 7L })
            val running =
                backgroundScope.async {
                    authorizer.authorize("https://oauth.taobao.com/authorize?response_type=code&state=$PLAIN_STATE&view=wap", PLAIN_STATE)
                }
            runCurrent()
            assertEquals("打开后记下的是哈希", webCodeSha256(PLAIN_STATE), store.current()?.stateSha256)
            inbox.deliver(WebCodeCallback.Code(SECRET_CODE, PLAIN_STATE))
            advanceUntilIdle()
            assertTrue("拿到凭证", running.isCompleted && running.getCompleted() is AuthStepResult.Credential)
            val everything = stored() + "\n" + onDisk()
            assertFalse("code 不落盘", everything.contains(SECRET_CODE))
            assertFalse("明文 state 不落盘", everything.contains(PLAIN_STATE))
        }

    private companion object {
        const val PLAIN_STATE = "PlainState_Zq9-x"
        const val SECRET_CODE = "SecretCode_Wk7-y"
    }
}
