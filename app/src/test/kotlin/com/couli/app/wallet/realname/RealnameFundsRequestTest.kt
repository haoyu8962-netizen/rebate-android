package com.couli.app.wallet.realname

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.couli.feature.wallet.WalletTags
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 接线、请求形状、同意先行与失败不提交（真实 ApiClient，经 MockWebServer 观察）。 */
class RealnameFundsRequestTest : RealnameFundsHarness() {
    @Test fun productionWiringIsAvailable() {
        launch()
        enterRealNameFromWallet()
        assertFalse("生产接线不显示「暂未开放」", has(WalletTags.REALNAME_UNAVAILABLE))
        fillVectorForm()
        assertTrue("填好后可提交", submitEnabled())
    }

    @Test fun submitBodyAndHeaders() {
        launch()
        enterRealNameFromWallet()
        fillVectorForm()
        submitAndAgree(1)
        assertEquals("只发 1 次实名提交", 1, submits().size)
        val post = submits().single()
        assertEquals("请求体键恰好是 name、id_no", setOf("name", "id_no"), post.json().keys)
        assertTrue("姓名去首尾空白", post.text("name") == normalizedName)
        assertTrue("身份证号 x 转大写", post.text("id_no") == normalizedId)
        assertKeyShape(post.header("Idempotency-Key"))
        listOf("X-Sign", "X-Timestamp", "X-Nonce").forEach { assertTrue("带 $it", !post.header(it).isNullOrEmpty()) }
        assertTrue("带 Bearer", post.header("Authorization").orEmpty().startsWith("Bearer "))
        assertEquals("没有查询串", null, post.query)
    }

    @Test fun consentBodyAndVersionFromConfig() {
        launch()
        enterRealNameFromWallet()
        fillVectorForm()
        submitAndAgree(1)
        val consent = consents().single()
        assertEquals("同意体键恰好五个", setOf("type", "version", "accepted", "channel", "client_at"), consent.json().keys)
        assertEquals("id_verification", consent.text("type"))
        assertEquals("true", consent.text("accepted"))
        assertEquals("realname_sheet", consent.text("channel"))
        assertEquals(
            "version 取 /v1/config.legal.privacy.version，不取本机已同意版本（D10-887 ①）",
            CONFIG_PRIVACY_VERSION.toString(),
            consent.text("version"),
        )
        assertTrue(
            "client_at 是上海时区 ISO 8601",
            CLIENT_AT.matches(consent.text("client_at")),
        )
        assertFalse("同意体不含哨兵", consent.body.contains(normalizedName) || consent.body.contains(normalizedId))
        val ordered = synchronized(requests) { requests.filter { it.path == CONSENT_PATH || it.path == SUBMIT_PATH }.map { it.path } }
        assertEquals("同意先于实名提交", listOf(CONSENT_PATH, SUBMIT_PATH), ordered)
    }

    @Test fun versionFallsBackToConsentedWhenConfigHasNoLegal() {
        launch(config = CONFIG_NO_LEGAL)
        enterRealNameFromWallet()
        fillVectorForm()
        submitAndAgree(1)
        assertEquals(
            "配置取不到时用本机已同意版本（三端回落顺序一致）",
            CONSENTED_VERSION.toString(),
            consents().single().text("version"),
        )
    }

    @Test fun submitWaitsForConsentResult() {
        consentReply = { envelope("null").setBodyDelay(CONSENT_DELAY_MS, TimeUnit.MILLISECONDS) }
        launch()
        enterRealNameFromWallet()
        fillVectorForm()
        click(WalletTags.REALNAME_SUBMIT)
        idleUntil { has(WalletTags.CONSENT_SHEET) }
        toggleAndAgree()
        idleUntil { consents().size == 1 }
        observe(OBSERVE_STEP_MS)
        assertEquals("同意在途时不发实名提交", 0, submits().size)
        assertFalse("同意在途时按钮锁住", submitEnabled())
        idleUntil { submits().size == 1 }
    }

    @Test fun consentBusinessFailureBlocksSubmit() {
        consentReply = { envelope("null", code = 50001) }
        launch()
        enterRealNameFromWallet()
        fillVectorForm()
        click(WalletTags.REALNAME_SUBMIT)
        idleUntil { has(WalletTags.CONSENT_SHEET) }
        toggleAndAgree()
        idleUntil { errorBar() != null }
        assertEquals("同意失败不提交", 0, submits().size)
        assertTrue("同意失败后可再点", submitEnabled())
        consentReply = { envelope("null") }
        submitAndAgree(1)
        assertEquals("同意成功后才提交", 2, consents().size)
    }

    @Test fun consentDisconnectBlocksSubmit() {
        consentReply = { disconnect() }
        launch()
        enterRealNameFromWallet()
        fillVectorForm()
        click(WalletTags.REALNAME_SUBMIT)
        idleUntil { has(WalletTags.CONSENT_SHEET) }
        toggleAndAgree()
        idleUntil { errorBar() != null }
        assertEquals("同意断连不提交", 0, submits().size)
        assertTrue("断连后可再点", submitEnabled())
    }

    @Test fun declineOrUncheckedSendsNothing() {
        launch()
        enterRealNameFromWallet()
        fillVectorForm()
        click(WalletTags.REALNAME_SUBMIT)
        idleUntil { has(WalletTags.CONSENT_SHEET) }
        // 没勾选时【同意并继续】不可点
        click(WalletTags.CONSENT_OK)
        assertEquals("未勾选不发同意", 0, consents().size)
        assertEquals("未勾选不发提交", 0, submits().size)
        click(WalletTags.CONSENT_CANCEL)
        idleUntil { !has(WalletTags.CONSENT_SHEET) }
        observe(OBSERVE_STEP_MS)
        assertEquals("【暂不认证】不发同意", 0, consents().size)
        assertEquals("【暂不认证】不发提交", 0, submits().size)
    }

    @Test fun repeatedTapsSendOnce() {
        submitReply = { success().setBodyDelay(SUBMIT_DELAY_MS, TimeUnit.MILLISECONDS) }
        launch()
        enterRealNameFromWallet()
        fillVectorForm()
        click(WalletTags.REALNAME_SUBMIT)
        idleUntil { has(WalletTags.CONSENT_SHEET) }
        click(WalletTags.CONSENT_CHECK)
        val ok = composeRule.onNodeWithTag(WalletTags.CONSENT_OK, useUnmergedTree = true)
        ok.performClick()
        runCatching { ok.performClick() }
        settle()
        idleUntil { submits().size >= 1 }
        runCatching { click(WalletTags.REALNAME_SUBMIT) }
        assertFalse("在途时不重开半屏", has(WalletTags.CONSENT_SHEET))
        idleUntil { realnameVerified() }
        assertEquals("同意只发一次", 1, consents().size)
        assertEquals("实名提交只发一次", 1, submits().size)
        assertTrue("成功后出栈", top()?.name != "RealName")
    }

    @Test fun incompleteFormSendsNothing() {
        launch()
        enterRealNameFromWallet()
        fillForm(EDITED_NAME, "0000000000000000")
        assertFalse("身份证号不足 18 位不可提交", submitEnabled())
        click(WalletTags.REALNAME_SUBMIT)
        assertFalse("不弹单独同意", has(WalletTags.CONSENT_SHEET))
        assertEquals("不发同意", 0, consents().size)
        assertEquals("不发提交", 0, submits().size)
    }

    private companion object {
        val CLIENT_AT = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\+08:00$")
        const val CONSENT_DELAY_MS = 800L
        const val SUBMIT_DELAY_MS = 400L
    }
}
