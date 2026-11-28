package com.couli.app.trade

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.trade.AuthStepResult
import com.couli.feature.trade.WebCodeAttempt
import com.couli.feature.trade.WebCodeAttemptStore
import com.couli.feature.trade.WebCodeCallback
import com.couli.feature.trade.WebCodeInbox
import com.couli.feature.trade.webCodeAccountTag
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 带账号标签、按哈希删除的内存尝试记录（本线的授权器单测用；甲的 MemoryWebCodeAttempts 不动）。 */
internal class TaggedWebCodeAttempts : WebCodeAttemptStore {
    var attempt: WebCodeAttempt? = null

    override fun record(
        state: String,
        nowMillis: Long,
    ) = record(state, nowMillis, null)

    override fun record(
        state: String,
        nowMillis: Long,
        accountTag: String?,
    ) {
        attempt = WebCodeAttempt(webCodeSha256(state), nowMillis, accountTag)
    }

    override fun current(): WebCodeAttempt? = attempt

    override fun clear() {
        attempt = null
    }

    override fun clearIfCurrent(stateSha256: String) {
        if (attempt?.stateSha256 == stateSha256) attempt = null
    }
}

/**
 * FU-AND-TB-WEBCODE-B 测试：尝试记录的归属（甲评审 S3 F2；设计稿 §2 ②、§6.4 第 2 步）。
 * - 授权器记尝试时带上当前账号标签；删记录只删自己那条（sha256(state) 相同才删），两次尝试并存时先结束的那次不删后一次的记录；
 * - SharedPreferencesWebCodeAttempts 落盘账号标签（是哈希，不是用户 id 原文），clearIfCurrent 按哈希删。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class WebCodeAttemptOwnershipTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs: SharedPreferences = context.getSharedPreferences("webcode-attempts-ownership-test", Context.MODE_PRIVATE)
    private var now = 1_700_000_000_000L
    private val inbox = WebCodeInbox(clock = { now })
    private val launcher = RecordingLauncher()
    private val signal = TestReturnSignal()

    private fun stored(): String = prefs.all.entries.joinToString("\n") { (key, value) -> "$key=$value" }

    // ---------------------------------------------------------------- 授权器（F2 与账号标签）

    @Test
    fun o01_theAuthorizerRecordsTheCurrentAccountTag() =
        runTest {
            val attempts = TaggedWebCodeAttempts()
            val authorizer = BrowserWebCodeAuthorizer(launcher, inbox, attempts, signal, clock = { now }, accountTag = { TAG_A })
            backgroundScope.async { authorizer.authorize(URL_A, STATE_A) }
            runCurrent()
            assertEquals("记下发起时的账号标签", WebCodeAttempt(webCodeSha256(STATE_A), now, TAG_A), attempts.current())
        }

    @Test
    fun o02_aFinishedAttemptDoesNotDeleteANewerAttemptsRecord() =
        runTest {
            val attempts = TaggedWebCodeAttempts()
            val authorizer = BrowserWebCodeAuthorizer(launcher, inbox, attempts, signal, clock = { now }, accountTag = { TAG_A })
            val first = backgroundScope.async { authorizer.authorize(URL_A, STATE_A) }
            runCurrent()
            now += 1_000L
            backgroundScope.async { authorizer.authorize(URL_B, STATE_B) }
            runCurrent()
            assertEquals("只存 1 条：后一次覆盖", webCodeSha256(STATE_B), attempts.current()?.stateSha256)
            inbox.deliver(WebCodeCallback.Code("c0de_a", STATE_A))
            runCurrent()
            assertTrue("先发起的那次拿到凭证", first.isCompleted && first.getCompleted() is AuthStepResult.Credential)
            assertEquals(
                "先结束的那次不删后一次的记录（sha256 相同才删）",
                WebCodeAttempt(webCodeSha256(STATE_B), now, TAG_A),
                attempts.current(),
            )
        }

    @Test
    fun o03_anIncompleteCallbackOfTheOlderAttemptAlsoKeepsTheNewerRecord() =
        runTest {
            val attempts = TaggedWebCodeAttempts()
            val authorizer = BrowserWebCodeAuthorizer(launcher, inbox, attempts, signal, clock = { now })
            val first = backgroundScope.async { authorizer.authorize(URL_A, STATE_A) }
            runCurrent()
            backgroundScope.async { authorizer.authorize(URL_B, STATE_B) }
            runCurrent()
            inbox.deliver(WebCodeCallback.Incomplete(STATE_A))
            runCurrent()
            assertEquals(AuthStepResult.Failed, first.getCompleted())
            assertEquals("后一次的记录还在", webCodeSha256(STATE_B), attempts.current()?.stateSha256)
        }

    @Test
    fun o04_theAttemptsOwnCallbackStillDeletesItsOwnRecord() =
        runTest {
            val attempts = TaggedWebCodeAttempts()
            val authorizer = BrowserWebCodeAuthorizer(launcher, inbox, attempts, signal, clock = { now })
            val running = backgroundScope.async { authorizer.authorize(URL_A, STATE_A) }
            runCurrent()
            inbox.deliver(WebCodeCallback.Code("c0de_a", STATE_A))
            runCurrent()
            assertTrue(running.getCompleted() is AuthStepResult.Credential)
            assertNull("自己的记录照样删（热路径不变）", attempts.current())
        }

    // ---------------------------------------------------------------- SharedPreferences 实现

    @Test
    fun p01_theStorePersistsTheAccountTagButNotTheUserId() {
        val tag = webCodeAccountTag(USER_ID)
        SharedPreferencesWebCodeAttempts(prefs).record(STATE_A, 42L, tag)
        assertEquals(
            "新建实例读得回账号标签",
            WebCodeAttempt(webCodeSha256(STATE_A), 42L, webCodeSha256(USER_ID)),
            SharedPreferencesWebCodeAttempts(prefs).current(),
        )
        val text = stored()
        assertTrue("落盘的是账号标签（哈希）：$text", text.contains(webCodeSha256(USER_ID)))
        assertFalse("不落用户 id 原文：$text", text.contains(USER_ID))
        assertFalse("不落明文 state：$text", text.contains(STATE_A))
    }

    @Test
    fun p02_clearIfCurrentOnlyDeletesTheSameHash() {
        val store = SharedPreferencesWebCodeAttempts(prefs)
        store.record(STATE_B, 7L, TAG_A)
        store.clearIfCurrent(webCodeSha256(STATE_A))
        assertEquals("哈希不同：不删", webCodeSha256(STATE_B), store.current()?.stateSha256)
        store.clearIfCurrent(webCodeSha256(STATE_B))
        assertNull("哈希相同：删", store.current())
        assertTrue("删干净：${stored()}", prefs.all.isEmpty())
    }

    @Test
    fun p03_aRecordWithoutTagDoesNotInheritThePreviousTag() {
        val store = SharedPreferencesWebCodeAttempts(prefs)
        store.record(STATE_A, 1L, TAG_A)
        store.record(STATE_B, 2L)
        assertEquals("新的覆盖旧的，旧标签不留", WebCodeAttempt(webCodeSha256(STATE_B), 2L, null), store.current())
    }

    private companion object {
        const val STATE_A = "st_A-1"
        const val STATE_B = "st_B-2"
        const val URL_A = "https://oauth.taobao.com/authorize?state=st_A-1"
        const val URL_B = "https://oauth.taobao.com/authorize?state=st_B-2"
        const val TAG_A = "tag-A"
        const val USER_ID = "user-42"
    }
}
