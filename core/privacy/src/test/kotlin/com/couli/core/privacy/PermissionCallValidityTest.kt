package com.couli.core.privacy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.clientcore.PermissionStatusRaw
import com.couli.core.clientcore.PermissionTypeRaw
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * APP-04 三端共性第 14 条：桥调用的有效性（隐私、环境、会话、页面世代）一路带进 PermissionRequester，在出队时、用途说明结束时、
 * 调系统接口前分别检查。失效后不显示任何界面、不调系统接口、不记「申请过」与被拒时刻。
 */
@RunWith(AndroidJUnit4::class)
class PermissionCallValidityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences = permissionPreferences(context)

    private class Host : PermissionActivityHost {
        val requested = mutableListOf<String>()
        var gate: CompletableDeferred<Boolean>? = null

        override fun shouldShowRationale(androidPermission: String): Boolean = false

        override suspend fun request(androidPermission: String): Boolean {
            requested += androidPermission
            return gate?.await() ?: false
        }
    }

    private val host = Host()
    private lateinit var system: AndroidPermissionSystem

    @Before
    fun reset() {
        preferences.edit().clear().commit()
    }

    private fun requester(): AndroidPermissionRequester {
        system =
            AndroidPermissionSystem(
                context,
                preferences,
                sdkInt = 34,
                declared = { true },
                granted = { false },
                notificationsEnabled = { false },
            ).also { it.host = host }
        return AndroidPermissionRequester(system, PermissionPrompts(), preferences, clock = { 1_000_000L })
    }

    @Test
    fun invalidAtDequeueShowsNothingAndNeverCallsTheSystem() =
        runBlocking {
            val requester = requester()
            val result = requester.request(PermissionTypeRaw.PUSH, isValid = { false })
            assertEquals(PermissionStatusRaw.UNAVAILABLE, result.resultRaw)
            assertTrue(host.requested.isEmpty())
            assertNull(requester.prompts.current.value)
            assertEquals("没有记下「申请过」", PermissionStatusRaw.NOT_ASKED, system.status(PermissionTypeRaw.PUSH))
        }

    @Test
    fun invalidAfterThePurposeIsShownStopsBeforeTheSystemCall() =
        runBlocking {
            val requester = requester()
            var checks = 0
            // 出队时有效；用途说明之后（第 2 次起）失效
            val result = requester.request(PermissionTypeRaw.PUSH, isValid = { ++checks <= 1 })
            assertTrue(host.requested.isEmpty())
            assertEquals(PermissionStatusRaw.UNAVAILABLE, result.resultRaw)
            assertNull("没有留下任何界面", requester.prompts.current.value)
            assertEquals("不记被拒（不节流）", PermissionStatusRaw.NOT_ASKED, system.status(PermissionTypeRaw.PUSH))
        }

    @Test
    fun queuedRequestThatLostValidityWhileWaitingIsDropped() =
        runBlocking {
            val requester = requester()
            host.gate = CompletableDeferred()
            var secondValid = true
            val first = async { requester.request(PermissionTypeRaw.PUSH) }
            yield()
            val second = async { requester.request(PermissionTypeRaw.PHOTOS, isValid = { secondValid }) }
            yield()
            // 排队期间撤回同意 / 页面失效
            secondValid = false
            host.gate?.complete(true)
            assertEquals(PermissionStatusRaw.GRANTED, first.await().resultRaw)
            assertEquals(PermissionStatusRaw.UNAVAILABLE, second.await().resultRaw)
            assertEquals("只有第一个申请调了系统接口", listOf(AndroidPermissionSystem.POST_NOTIFICATIONS), host.requested)
        }
}
