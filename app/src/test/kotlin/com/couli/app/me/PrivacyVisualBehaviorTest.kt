package com.couli.app.me

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.me.MeTags
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * brief-b §4 第 4、5 条：每个改了样式或换了控件的入口，真实点击一次 → 动作 / 导航恰好一次、目标与改前一致；进入隐私中心 / 关于零请求。
 * 个性化开关：本机先保存 → 已注册设备才发 recordConsent（type=personalization、channel=privacy_center）至多一次；未注册只记本地。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h864dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivacyVisualBehaviorTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PrivacyVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    @Test fun enteringPrivacyCenterSendsNothing() {
        host.launchPrivacy()
        host.idle()
        assertTrue("进入隐私中心零请求：${host.api.calls}", host.api.calls.isEmpty())
        assertTrue(host.env.opened.isEmpty())
    }

    @Test fun eachListRowOpensAgreementOnceWithItsType() {
        host.launchPrivacy()
        val expected =
            listOf(
                MeTags.PRIVACY_LIST_PREFIX + "collect" to "privacy",
                MeTags.PRIVACY_LIST_PREFIX + "share" to "sdk_list",
                MeTags.PRIVACY_POLICY to "privacy",
                MeTags.PRIVACY_AGREEMENT to "agreement",
            )
        expected.forEachIndexed { index, (tag, type) ->
            host.tap(tag)
            assertEquals("第 ${index + 1} 行恰一次", index + 1, host.env.opened.size)
            assertEquals("去向不变（G-P19）：$tag", "Agreement" to JsonObject(mapOf("type" to JsonPrimitive(type))), host.env.opened.last())
        }
        assertTrue("清单行零请求", host.api.calls.isEmpty())
    }

    @Test fun memberSwitchSavesLocallyThenRecordsConsentOnce() {
        host.launchPrivacy()
        assertTrue(host.local.personalizationOn.value)
        host.tap(MeTags.PRIVACY_SWITCH)
        host.settle("recordConsent") { host.api.count(PrivacyVisualApi.RECORD_CONSENT) == 1 }
        assertFalse("本机先保存", host.local.personalizationOn.value)
        val body =
            Json
                .parseToJsonElement(
                    requireNotNull(
                        host.api.calls
                            .single()
                            .body,
                    ),
                ).jsonObject
        assertEquals("personalization", body.getValue("type").jsonPrimitive.content)
        assertEquals("false", body.getValue("accepted").jsonPrimitive.content)
        assertEquals("privacy_center", body.getValue("channel").jsonPrimitive.content)
        assertEquals("3", body.getValue("version").jsonPrimitive.content)
        assertEquals("一次切换至多一次 recordConsent", listOf(PrivacyVisualApi.RECORD_CONSENT), host.api.calls.map { it.operationId })
    }

    @Test fun guestUnregisteredDeviceOnlySavesLocally() {
        host.launchPrivacy(session = "privacy_guest")
        host.tap(MeTags.PRIVACY_SWITCH)
        host.idle()
        assertFalse("本机保存", host.local.personalizationOn.value)
        assertTrue("设备未注册只记本地（G03、G24）", host.api.calls.isEmpty())
    }

    @Test fun withdrawingDisablesTheSwitch() {
        host.launchPrivacy(withdrawing = true)
        host.tap(MeTags.PRIVACY_SWITCH)
        assertTrue("撤回进行中开关不生效", host.local.personalizationOn.value)
        assertTrue(host.api.calls.isEmpty())
    }

    @Test fun enteringAboutSendsNothingAndCopyIsOneWrite() {
        host.launchAbout()
        host.idle()
        assertTrue("关于零请求", host.api.calls.isEmpty())
        host.tap(MeTags.ABOUT_COPY)
        assertEquals("【复制诊断信息】→ 统一写入口一次", 1, host.env.copies.size)
        assertTrue(host.api.calls.isEmpty())
    }

    @Test fun csCopyIsOneWriteOfTheRawLink() {
        host.launchCs("cs_wechat_missing")
        assertTrue("未装微信不直接打开", host.ports.opened.isEmpty())
        host.tap(MeTags.CS_COPY)
        assertEquals(listOf(PrivacyVisualFixtures.kf), host.ports.copies)
        host.tap(MeTags.CS_HELP)
        assertEquals("【去帮助中心】→ 关半屏 + Help 一次", 1, host.ports.help)
        assertFalse(host.has(MeTags.CS_SHEET))
    }
}
