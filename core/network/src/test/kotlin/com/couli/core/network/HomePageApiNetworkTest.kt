package com.couli.core.network

import com.couli.core.generated.ApiOperations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-X-HOME-PAGE-API-android 测试 v1（独立测试作者）：首页两个契约接口在生成表与请求头策略里的形状（brief §2.1 升锁、§2.2 D10-607 安卓第 2 处）。
 * - 生成表（升锁到 rebate-platform 00deded0ef6f 后 gen.py 产出）：getPage、listPageSectionItems 都在，GET、optional、不签名、不幂等、非 planned；
 * - 基本模式匿名白名单（BR-ID-11；RequestHeaderPolicy.kt:158 附近）含两者：基本模式首页照样取页、取卡，且不带 Authorization / X-Device-Id / 签名；
 * - 隐私尚未决定（首启弹窗前）的白名单不因此放宽。
 */
class HomePageApiNetworkTest {
    private val pageOps = listOf("getPage", "listPageSectionItems")

    private fun state(
        privacy: String,
        credentialsReadable: Boolean = true,
    ) = NetworkRequestState(
        privacy = privacy,
        sessionScope = SessionScopeRaw.FULL,
        hasAccessToken = true,
        deviceId = "device-fixture-ABC123",
        environmentSettled = true,
        credentialsReadable = credentialsReadable,
    )

    @Test
    fun n01GeneratedTableHasBothHomeOperations() {
        val page = ApiOperations.all.getValue("getPage")
        assertEquals("GET", page.method)
        assertEquals("/v1/pages/{page_key}", page.pathTemplate)
        assertEquals("optional", page.auth)
        assertFalse(page.signed)
        assertFalse(page.idempotent)
        assertFalse(page.planned)
        assertTrue(30701 in page.errorCodes)
        val items = ApiOperations.all["listPageSectionItems"]
        assertNotNull("升锁 00deded0ef6f 后生成表必须有 listPageSectionItems（a7c9f1f 没有）", items)
        requireNotNull(items)
        assertEquals("GET", items.method)
        assertEquals("/v1/pages/{page_key}/sections/{section_id}/items", items.pathTemplate)
        assertEquals("optional", items.auth)
        assertFalse(items.signed)
        assertFalse(items.idempotent)
        assertFalse(items.planned)
        assertEquals(listOf(20001, 30701), items.errorCodes)
    }

    @Test
    fun n02BasicModeAllowlistContainsBothHomeOperationsAndKeepsTheOldOnes() {
        val allowed = RequestHeaderPolicy.BASIC_MODE_ALLOWED
        pageOps.forEach { assertTrue("基本模式匿名白名单缺 $it（D10-607 ②）", it in allowed) }
        listOf("getConfig", "searchProducts", "getProduct", "listArticles").forEach { assertTrue("原有的 $it 不得删", it in allowed) }
    }

    @Test
    fun n03BasicModeHomeRequestsAreSentAnonymously() {
        pageOps.forEach { id ->
            val op = ApiOperations.all[id]
            assertNotNull("生成表缺 $id", op)
            val decision = RequestHeaderPolicy.decide(state(PrivacyRaw.BASIC), requireNotNull(op))
            assertTrue("基本模式下 $id 必须发出（不得 basic_mode_denied）：${decision.localRejectReason}", decision.send)
            assertFalse("$id 基本模式不带 Authorization", decision.includeAuthorization)
            assertFalse("$id 基本模式不带 X-Device-Id", decision.includeDeviceId)
            assertFalse("$id 不签名", decision.includeSignature)
            assertFalse("$id 读接口不带 Idempotency-Key", decision.includeIdempotencyKey)
            assertEquals(HeaderNames.ANONYMOUS, decision.headerNames)
        }
    }

    @Test
    fun n04MemberHomeRequestsCarryTokenAndDeviceIdForGrayBucketing() {
        // 契约：带 X-Device-Id 时按设备分桶读灰度版本；会员卡片按当前用户计算（x-auth optional）
        pageOps.forEach { id ->
            val decision = RequestHeaderPolicy.decide(state(PrivacyRaw.FULL), requireNotNull(ApiOperations.all[id]) { "生成表缺 $id" })
            assertTrue(decision.send)
            assertTrue("$id 已同意时带 Authorization", decision.includeAuthorization)
            assertTrue("$id 已同意时带 X-Device-Id", decision.includeDeviceId)
            assertFalse(decision.includeSignature)
            assertFalse(decision.includeIdempotencyKey)
        }
    }

    @Test
    fun n05UndecidedPrivacyStillOnlyAllowsConfigAndAgreementText() {
        assertEquals(setOf("getConfig", "listArticles"), RequestHeaderPolicy.UNDECIDED_ALLOWED)
        pageOps.forEach { id ->
            val op = ApiOperations.all[id] ?: return@forEach
            val decision = RequestHeaderPolicy.decide(state(PrivacyRaw.UNDECIDED), op)
            assertFalse("隐私未决定时 $id 仍不发（BR-ID-11 同意前只放行配置与协议文本）", decision.send)
            assertEquals(LocalRejectReason.BASIC_MODE_DENIED, decision.localRejectReason)
        }
    }
}
