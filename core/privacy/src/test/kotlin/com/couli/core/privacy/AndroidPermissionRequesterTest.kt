package com.couli.core.privacy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.clientcore.PermissionStatusRaw
import com.couli.core.clientcore.PermissionTypeRaw
import com.couli.core.config.AppEnvironment
import com.couli.core.config.CouliPreferenceNamespaces
import com.couli.core.config.SharedPreferencesNamespaceCleaner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android 的 PermissionRequester 接线（APP-03b，03 §4.9）：系统调用经可注入的 [PermissionActivityHost]；用途说明浮层与系统框同时
 * 出现、系统返回后收起；被拒后节流（BR-ID-13）且节流记录跨重建、切换环境都保留；永久拒绝引导去设置；清单里没声明的权限 unavailable。
 */
@RunWith(AndroidJUnit4::class)
class AndroidPermissionRequesterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences = permissionPreferences(context)
    private var now = 1_000_000L

    private class FakeHost : PermissionActivityHost {
        var rationale = false
        var answer = false
        val requested = mutableListOf<String>()
        var promptDuringRequest: PermissionPrompt? = null
        lateinit var prompts: PermissionPrompts

        override fun shouldShowRationale(androidPermission: String): Boolean = rationale

        override suspend fun request(androidPermission: String): Boolean {
            requested += androidPermission
            promptDuringRequest = prompts.current.value
            return answer
        }
    }

    private val host = FakeHost()

    @Before
    fun reset() {
        preferences.edit().clear().commit()
    }

    private fun requester(declared: Boolean = true): AndroidPermissionRequester {
        val system =
            AndroidPermissionSystem(
                context,
                preferences,
                sdkInt = 34,
                declared = { declared },
                granted = { false },
                notificationsEnabled = { false },
            ).also { it.host = host }
        return AndroidPermissionRequester(system, PermissionPrompts(), preferences, clock = { now }).also { host.prompts = it.prompts }
    }

    @Test
    fun rationaleShowsWithTheSystemDialogAndIsDismissedAfterwards() =
        runBlocking {
            host.answer = true
            val requester = requester()
            val result = requester.request(PermissionTypeRaw.PUSH)
            assertEquals(PermissionStatusRaw.GRANTED, result.resultRaw)
            assertEquals(listOf(AndroidPermissionSystem.POST_NOTIFICATIONS), host.requested)
            assertEquals(PermissionPrompt.Rationale("perm.push.card_hint"), host.promptDuringRequest)
            assertNull("系统返回后收起说明", requester.prompts.current.value)
        }

    @Test
    fun deniedIsThrottledAcrossRebuildAndEnvironmentSwitch() =
        runBlocking {
            host.rationale = false
            host.answer = false
            // 第一次拒绝但还能再问
            host.rationale = true
            assertEquals(PermissionStatusRaw.DENIED, requester().request(PermissionTypeRaw.PUSH).resultRaw)

            SharedPreferencesNamespaceCleaner(context).clean(AppEnvironment.TEST, AppEnvironment.STAGING)
            val again = requester()
            assertEquals(PermissionStatusRaw.THROTTLED, again.request(PermissionTypeRaw.PUSH).resultRaw)
            assertEquals("节流期内不再弹系统框", 1, host.requested.size)
            val guide = again.prompts.current.value as PermissionPrompt.SettingsGuide
            assertEquals("perm.go_settings", guide.buttonKey)
            assertEquals("perm.push.card_hint", guide.purposeKey)

            // 从设置入口主动点击不受节流
            assertEquals(PermissionStatusRaw.DENIED, again.request(PermissionTypeRaw.PUSH, fromSettings = true).resultRaw)
            assertEquals(2, host.requested.size)
        }

    @Test
    fun permanentlyDeniedGuidesToSettingsWithoutCallingTheSystem() =
        runBlocking {
            host.answer = false
            host.rationale = false
            assertEquals(PermissionStatusRaw.PERMANENTLY_DENIED, requester().request(PermissionTypeRaw.PUSH).resultRaw)
            val requester = requester()
            assertEquals(PermissionStatusRaw.PERMANENTLY_DENIED, requester.request(PermissionTypeRaw.PUSH, fromSettings = true).resultRaw)
            assertEquals("永久拒绝后不再调系统接口", 1, host.requested.size)
            val guide = requester.prompts.current.value as PermissionPrompt.SettingsGuide
            assertEquals("perm.push.denied_forever", guide.messageKey)
        }

    @Test
    fun undeclaredPermissionIsUnavailable() =
        runBlocking {
            val result = requester(declared = false).request(PermissionTypeRaw.PUSH)
            assertEquals(PermissionStatusRaw.UNAVAILABLE, result.resultRaw)
            assertTrue(host.requested.isEmpty())
        }

    @Test
    fun photosNeedNoRuntimePermissionOnScopedStorage() =
        runBlocking {
            assertEquals(PermissionStatusRaw.GRANTED, requester().request(PermissionTypeRaw.PHOTOS).resultRaw)
            assertTrue(host.requested.isEmpty())
        }

    @Test
    fun throttleRecordsLiveInThePreservedNamespace() {
        assertTrue(CouliPreferenceNamespaces.PERMISSION in CouliPreferenceNamespaces.preservedAcrossEnvironmentSwitch)
    }
}
