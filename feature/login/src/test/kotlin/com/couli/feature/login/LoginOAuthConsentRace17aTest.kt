package com.couli.feature.login

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginOAuthConsentRace17aTest {
    @Test
    fun uncheckingDuringOAuthDropsReturnedCredentialUntilFreshConfirmation() {
        Login17aFixture().use { f ->
            val firstConsent = confirmAndStartAuthorization(f, 1)
            val beforeCallback = f.entries.get()
            f.advance(3_000)
            assertEquals(LoginStep.PHONE, f.model.state.value.step)
            f.model.onToggleAgreement()
            assertFalse(f.model.state.value.agreed)
            returnCredentialAndSettle(f, 1)
            // 先交付 SDK 凭证、等待授权调用结束及页面动作结算，再验 API 逻辑入口；也允许产品主动取消授权等待。
            assertEquals("F6：取消同意后不得用开始时取走的同意时刻换取登录凭证", beforeCallback, f.entries.get())
            assertEquals(0, f.requests.count { it.path == Login17aFixture.WECHAT })
            assertTrue(f.completed.isEmpty())
            assertNull(f.tokens.accessToken)
            assertNull(f.methods.read())
            assertEquals(LoginStep.PHONE, f.model.state.value.step)
            assertFalse(f.model.state.value.agreed)

            // 产品可在结算后提示重新同意，也可等用户再点入口；两种情况都从原第三方入口重新确认。
            f.adapter.prepareNextAuthorization()
            f.model.onThirdParty("wechat")
            f.settle()
            assertEquals(LoginDialog.AGREE_CONFIRM, f.model.state.value.dialog)
            assertEquals(beforeCallback, f.entries.get())
            f.advance(7_000)
            val freshConsent = f.now
            f.model.onConfirmAgree()
            assertTrue(freshConsent > firstConsent)
            // 不规定产品保留旧授权凭证还是重新拉起 SDK；若需新授权，独立屏障已备好这次回调。
            assertTrue(f.adapter.succeed())
            f.settle()
            assertWechatLogin(f, freshConsent)
            assertEquals(f.requests.count { it.path == Login17aFixture.ATTEMPTS } + 1, f.entries.get())
        }
    }

    @Test
    fun oauthWithoutWithdrawalUsesConfirmationTimeAfterAuthorizationSettles() {
        Login17aFixture().use { f ->
            val confirmedAt = confirmAndStartAuthorization(f, 1)
            f.advance(9_000)
            assertTrue(f.now > confirmedAt)
            assertTrue(f.model.state.value.agreed)
            returnCredentialAndSettle(f, 1)
            assertWechatLogin(f, confirmedAt)
            assertEquals(2, f.entries.get())
        }
    }

    @Test
    fun repeatedGetCodeWhileSendingDoesNotSendAgain() {
        Login17aFixture().use { f ->
            val reply = CountDownLatch(1)
            try {
                f.sendAnswer = {
                    check(reply.await(5, TimeUnit.SECONDS)) { "测试没有释放发码回包" }
                    Login17aFixture.sent()
                }
                f.model.onGetCode()
                f.model.onConfirmAgree()
                f.until("首个发码已到服务端，回包仍挂起") { f.sends() == 1 }
                assertEquals(LoginStep.PHONE, f.model.state.value.step)
                assertTrue(f.model.state.value.sending)
                assertTrue(f.model.state.value.agreed)
                repeat(3) { f.model.onGetCode() }
            } finally {
                reply.countDown()
            }
            f.settle()
            assertEquals("F7：在途重复点击不能新增逻辑请求，也不能在结算后补发", 1, f.entries.get())
            assertEquals(1, f.sends())
            assertEquals(LoginStep.CODE, f.model.state.value.step)
            assertFalse(f.model.state.value.sending)
        }
    }

    private fun confirmAndStartAuthorization(
        f: Login17aFixture,
        expectedLaunches: Int,
    ): Long {
        f.model.onThirdParty("wechat")
        assertEquals(LoginStep.PHONE, f.model.state.value.step)
        assertEquals(LoginDialog.AGREE_CONFIRM, f.model.state.value.dialog)
        assertEquals(
            "login.agree_confirm.btn_login",
            f.model.state.value.confirmation
                ?.primaryKey,
        )
        assertEquals(
            "login.agree_confirm.body_oauth",
            f.model.state.value.confirmation
                ?.bodyKey,
        )
        f.advance(7_000)
        val confirmedAt = f.now
        f.model.onConfirmAgree()
        f.until("生产第三方入口已拉起 SDK，授权回调仍挂起") { f.adapter.launches.get() == expectedLaunches }
        assertTrue(f.model.state.value.agreed)
        assertEquals("wechat", f.model.state.value.oauthBusy)
        assertEquals(expectedLaunches, f.requests.count { it.path == Login17aFixture.ATTEMPTS })
        assertEquals("授权阶段只有创建尝试请求，没有换取登录凭证", expectedLaunches, f.entries.get())
        return confirmedAt
    }

    private fun returnCredentialAndSettle(
        f: Login17aFixture,
        expectedFinished: Int,
    ) {
        assertTrue("每轮授权只交付一次凭证", f.adapter.succeed())
        f.until("SDK 授权调用已结束（回传或被产品取消等待）") { f.adapter.finished.get() == expectedFinished }
        f.settle()
        assertFalse(f.oauth.busy)
        assertNull(f.model.state.value.oauthBusy)
    }

    private fun assertWechatLogin(
        f: Login17aFixture,
        consentAt: Long,
    ) {
        val request = f.requests.single { it.path == Login17aFixture.WECHAT }
        val body = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject
        assertEquals(
            "使用确认时刻，不能用最早按钮时刻或 SDK 返回时刻",
            Instant.ofEpochMilli(consentAt).toString(),
            body.getValue("consent_at").jsonPrimitive.content,
        )
        assertEquals("code-17a", body.getValue("code").jsonPrimitive.content)
        assertEquals(listOf(LoginOutcome.LOGGED_IN), f.completed)
        assertEquals("access-17a", f.tokens.accessToken)
        assertEquals("wechat", f.methods.read())
    }
}
