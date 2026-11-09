@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.search.submit

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.search.submit.SubmitReplies.LINK
import com.couli.app.search.submit.SubmitReplies.SERVER_ERROR
import com.couli.app.search.submit.SubmitReplies.WORD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 修复 r3 / 评审 r2 S2-3（测试 v6）：搜索历史由关键词请求的「实际写出」触发（网络层的 [com.couli.core.network.CallOwnership.onWritten] 一类
 * 出口），不是「选中了可搜平台」。夹具的写出前守卫与 ApiClient 同口径：守卫放行才写出、才调 onWritten。
 * - 请求交到网络层、还在 IO 排队（写出前）：此刻不写历史；真正写出后才写；
 * - 写出前被取消（再次提交另一个词、离页）：不写；
 * - 被网络层本地拒绝：不写。
 * 普通词与识别失败的回落各一组。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
@Suppress("TooManyFunctions") // 每个发送结局一条
class SearchKeywordHistoryWrittenTest {
    @Before fun useTestMain() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun resetMain() = Dispatchers.resetMain()

    // ---------------------------------------------------------------- 普通词

    @Test
    fun wordHistoryWaitsUntilRequestIsWritten() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                val hold = f.api.holdWrite(SEARCH)
                submitWord(f, WORD)
                assertEquals("关键词请求还在 IO 排队、未写出：不写历史", NONE, SubmitReplies.history(f))
                hold.complete(Unit)
                advanceUntilIdle()
                assertEquals("前提：已写出", listOf(SEARCH), f.api.writtenOperations)
                assertEquals("真正写出后写历史", listOf(WORD) to listOf(WORD), SubmitReplies.history(f))
            }
        }

    @Test
    fun wordCancelledBeforeWriteByNewerWordWritesOnlyNewer() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                val hold = f.api.holdWrite(SEARCH, 0)
                submitWord(f, WORD)
                // 第一个词还在排队时用户又搜了另一个词：旧请求作废、取消
                submitWord(f, OTHER)
                hold.complete(Unit)
                advanceUntilIdle()
                assertEquals("前提：只有新词写出", listOf(OTHER), writtenKeywords(f))
                assertEquals("写出前被取消的词不写历史", listOf(OTHER) to listOf(OTHER), SubmitReplies.history(f))
            }
        }

    @Test
    fun wordCancelledBeforeWriteByLeavingWritesNoHistory() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                val hold = f.api.holdWrite(SEARCH)
                f.onSearchPage()
                submitWord(f, WORD)
                f.leaveSearch()
                hold.complete(Unit)
                advanceUntilIdle()
                assertEquals("前提：离页后请求没有写出", emptyList<String>(), f.api.writtenOperations)
                assertEquals("写出前离页取消：不写历史", NONE, SubmitReplies.history(f))
            }
        }

    @Test
    fun wordLocallyRejectedWritesNoHistory() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                f.api.rejectWrite(SEARCH)
                submitWord(f, WORD)
                advanceUntilIdle()
                assertEquals("前提：网络层本地拒绝，没有写出", emptyList<String>(), f.api.writtenOperations)
                assertEquals("本地拒绝：不写历史", NONE, SubmitReplies.history(f))
            }
        }

    // ---------------------------------------------------------------- 识别失败的回落

    @Test
    fun fallbackHistoryWaitsUntilRequestIsWritten() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                val hold = f.api.holdWrite(SEARCH)
                startFallback(f)
                assertEquals("回落请求还在 IO 排队、未写出：不写历史", NONE, SubmitReplies.history(f))
                hold.complete(Unit)
                advanceUntilIdle()
                assertEquals("前提：回落已写出", listOf(PARSE, SEARCH), f.api.writtenOperations)
                assertEquals("回落真正写出后按原提交写历史", listOf(LINK) to listOf(LINK), SubmitReplies.history(f))
            }
        }

    @Test
    fun fallbackCancelledBeforeWriteByNewerWordWritesOnlyNewer() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                val hold = f.api.holdWrite(SEARCH, 0)
                startFallback(f)
                submitWord(f, OTHER)
                hold.complete(Unit)
                advanceUntilIdle()
                assertEquals("前提：只有新词写出", listOf(OTHER), writtenKeywords(f))
                assertEquals("写出前被取消的回落不写历史", listOf(OTHER) to listOf(OTHER), SubmitReplies.history(f))
            }
        }

    @Test
    fun fallbackLocallyRejectedWritesNoHistory() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                f.api.rejectWrite(SEARCH)
                startFallback(f)
                advanceUntilIdle()
                assertEquals("前提：回落被网络层本地拒绝", listOf(PARSE), f.api.writtenOperations)
                assertEquals("回落被本地拒绝：不写历史", NONE, SubmitReplies.history(f))
            }
        }

    // ---------------------------------------------------------------- 公共

    private fun TestScope.submitWord(
        f: SearchSubmitFixture,
        word: String,
    ) {
        f.submit(word)
        runCurrent()
        assertEquals("前提：关键词请求已交到网络层", word, SubmitReplies.keywords(f).lastOrNull())
    }

    private fun TestScope.startFallback(f: SearchSubmitFixture) {
        f.submit(LINK)
        runCurrent()
        assertEquals("前提：先只发识别", listOf(PARSE), SubmitReplies.operations(f))
        f.api.parseReply.complete(SubmitReplies.failure(SERVER_ERROR))
        runCurrent()
        assertEquals("前提：识别失败后回落请求交到了网络层", listOf(PARSE, SEARCH), SubmitReplies.operations(f))
    }

    private fun writtenKeywords(f: SearchSubmitFixture): List<String?> =
        f.api.written
            .filter { it.operationId == SEARCH }
            .map { Uri.parse("https://fixture.invalid/?${it.query}").getQueryParameter("q") }

    private companion object {
        const val PARSE = "parseInput"
        const val SEARCH = "searchProducts"
        const val OTHER = "示例矿泉水"
        val NONE = emptyList<String>() to emptyList<String>()
    }
}
