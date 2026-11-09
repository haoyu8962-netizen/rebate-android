package com.couli.app.clipboard

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.AppModule
import com.couli.core.config.AppEnvironment
import com.couli.feature.login.LoginViewModel
import com.couli.feature.login.PendingInviteCode
import com.couli.feature.login.PendingInviteRow
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(
    application = Application::class,
    sdk = [36],
    shadows = [ClipboardReviewLogin0724Wire::class, ClipboardReviewCipher0724::class],
)
class PendingInviteWiring17cTest {
    @Test
    fun viewModelNotifiesRowChangesAfterReplaceRemoveSmsConsumptionAndEnvironmentCleanup() {
        ClipboardReviewLogin0724Graph().use { graph ->
            assertTrue(graph.pendingInvite.set("K7M2QX"))
            val model = LoginViewModel(graph.login.pageConfig(false))
            val observed = mutableListOf<Pair<String?, PendingInviteRow?>>()
            val expected = mutableListOf<Pair<String?, PendingInviteRow?>>()
            val collector =
                model.viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    model.pendingInviteChanges.collect { observed += it to model.pendingInviteRow }
                }

            fun expectNotification(code: String?) {
                graph.settle() // 清空生产主线程队列后再断言；行快照只在通知回调里抓取。
                expected += code to code?.let { PendingInviteRow(it, "login.pending_invite", "login.pending_invite.remove") }
                assertEquals("每次变化都有通知，且通知时行内容已同步", expected, observed)
            }
            try {
                expectNotification("K7M2QX")
                assertTrue(graph.pendingInvite.set("P8N3RY"))
                expectNotification("P8N3RY")
                model.removePendingInvite()
                expectNotification(null)

                assertTrue(graph.pendingInvite.set("K7M2QX"))
                expectNotification("K7M2QX")
                graph.loginAs(ClipboardReviewLogin0724Wire.PHONE_A) // 等真实 LoginService / ApiClient 请求及结算完成。
                val sms = ClipboardReviewLogin0724Wire.calls.single { it.first == "/v1/auth/login/sms" }
                assertEquals(
                    "消费来自实际短信登录请求",
                    "K7M2QX",
                    Json
                        .parseToJsonElement(sms.second)
                        .jsonObject
                        .getValue("invite_code")
                        .jsonPrimitive.content,
                )
                expectNotification(null)

                assertTrue(graph.pendingInvite.set("P8N3RY"))
                expectNotification("P8N3RY")
                var committed = false
                graph.cleaners.switching(AppEnvironment.TEST, AppEnvironment.STAGING) { committed = true }
                expectNotification(null)
                assertTrue("经过生产环境切换注册表并完成提交", committed)
            } finally {
                collector.cancel()
                model.viewModelScope.cancel()
            }
        }
    }

    @Test
    fun productionProvidersPassTheSingletonToClipboardAppLoginServiceAndPage() {
        ClipboardReviewLogin0724Graph().use { graph ->
            assertSame(graph.pendingInvite, graph.board.pendingInvite)
            assertSame(graph.pendingInvite, graph.login.pendingInvite)
            assertSame(graph.pendingInvite, graph.login.service.pendingInvite)
            assertTrue(graph.prefs().salt.write(SAMPLE_SALT))
            assertTrue(graph.prefs().importPreferences { ClipboardReview0724Harness.NOW }.setOn())
            graph.recognize("邀请码 K7M2QX")
            graph.board.onPromptAction(checkNotNull(graph.board.prompt.value).id)
            graph.settle()
            assertEquals("真实确认入口已写码", "K7M2QX", graph.pendingInvite.peek())
            val page = LoginViewModel(graph.login.pageConfig(false))
            val restricted = LoginViewModel(graph.login.pageConfig(true))
            try {
                assertEquals("K7M2QX", page.pendingInviteRow?.code)
                assertNull(restricted.pendingInviteRow)
                graph.login.syncSession()
                assertEquals("同步会话不能清码", "K7M2QX", graph.pendingInvite.peek())
                graph.login
                    .pageConfig(false)
                    .actions
                    .onClose()
                assertEquals("关闭入口不能清码", "K7M2QX", graph.pendingInvite.peek())
                graph.loginAs(ClipboardReviewLogin0724Wire.PHONE_A)
                val sms = ClipboardReviewLogin0724Wire.calls.single { it.first == "/v1/auth/login/sms" }
                assertEquals(
                    "K7M2QX",
                    Json
                        .parseToJsonElement(sms.second)
                        .jsonObject["invite_code"]
                        ?.jsonPrimitive
                        ?.content,
                )
                assertNull(graph.pendingInvite.peek())
                assertNull(page.pendingInviteRow)
            } finally {
                page.viewModelScope.cancel()
                restricted.viewModelScope.cancel()
            }
        }
    }

    @Test
    fun realSessionLossAndClipboardBackgroundDoNotClearTheSharedContainer() {
        ClipboardReviewLogin0724Graph().use { graph ->
            graph.loginAs(ClipboardReviewLogin0724Wire.PHONE_A)
            graph.pendingInvite.set("K7M2QX")
            graph.tokens.clear()
            graph.login.syncSession()
            graph.settle()
            assertEquals(com.couli.core.router.SessionState.GUEST, graph.routing.session.value)
            assertEquals("真正从已登录变游客仍保留", "K7M2QX", graph.pendingInvite.peek())
            graph.board.onBackground()
            graph.settle()
            assertEquals("K7M2QX", graph.pendingInvite.peek())
        }
    }

    @Test
    fun providerIsSingletonAndRequiredConstructorParametersCannotCreateASecondContainer() {
        val provider = AppModule::class.java.declaredMethods.single { it.name == "pendingInviteCode" }
        assertEquals(PendingInviteCode::class.java, provider.returnType)
        assertTrue(provider.isAnnotationPresent(Singleton::class.java))
        // Kotlin 默认参数不会体现在 Java 普通构造签名；补源码检查防止“生产显式覆盖，但缺省仍建第二份”。
        val root =
            generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
                .first { File(it, "contract.lock").isFile }
        listOf(
            "app/src/main/kotlin/com/couli/app/clipboard/AppClipboard.kt",
            "app/src/main/kotlin/com/couli/app/login/AppLogin.kt",
            "feature/login/src/main/kotlin/com/couli/feature/login/LoginService.kt",
        ).forEach { path ->
            val source = File(root, path).readText()
            assertTrue(path, Regex("pendingInvite\\s*:\\s*PendingInviteCode").containsMatchIn(source))
            assertFalse(path, Regex("pendingInvite\\s*:\\s*PendingInviteCode\\s*[?]?\\s*=").containsMatchIn(source))
        }
    }
}
