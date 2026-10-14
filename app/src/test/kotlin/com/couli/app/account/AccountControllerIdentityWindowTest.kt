package com.couli.app.account

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.me.DeleteAccountController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 身份固定（S0，brief §0 第 1 条；APP-09 第 52、65 条）的控制器层窗口：会话已换（令牌已存为 B），而会话通知（同步重建、leave）还没执行时，
 * 旧控制器的回调只能拿固定身份与当前会话**比较**，不得因页面还没被作废就照常执行。页面层的重建与网络层的 expectedAccount 是另两道防线，
 * 这里单独证明控制器自己这一道（变异 M10b：alive() 不再比较固定身份即失败）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountControllerIdentityWindowTest : AccountHarness() {
    @Test
    fun s0_controllerWindow_afterTheSessionSwitchBeforeTheNoticeOldCallbacksNeitherWriteStateNorSend() {
        serverState.requireStepUp = false
        launch()
        openDeleteFromSettings()
        lateinit var controller: DeleteAccountController
        composeRule.runOnUiThread {
            val deps = account.deps
            controller = DeleteAccountController(deps, deps.env.accountIdentity(), SETTINGS_SOURCE, null).also { it.load() }
        }
        idleUntil("固定身份的控制器读完") { controller.view() != null }
        val posts = all("POST", DELETION).size
        composeRule.runOnUiThread {
            val version = controller.state.value.version
            // 令牌已换成 B，会话通知（syncSession / syncIdentity → 页面重建、leave）还没执行
            tokens.save("access-$USER_B", "refresh-$USER_B", "full", USER_B)
            controller.toggle(version)
            controller.apply(version)
        }
        settle()
        assertFalse("旧回调不得改写固定身份控制器的勾选", controller.state.value.checked)
        assertFalse("旧回调不得进入提交中", controller.state.value.submitting)
        assertEquals("零请求", posts, all("POST", DELETION).size)
        assertTrue("零写入（A、B 都没有未决记录）", records(USER_A).isEmpty() && records(USER_B).isEmpty())
    }

    private companion object {
        const val SETTINGS_SOURCE = "settings"
    }
}
