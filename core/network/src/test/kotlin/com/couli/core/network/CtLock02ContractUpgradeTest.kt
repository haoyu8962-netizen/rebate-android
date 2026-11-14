package com.couli.core.network

import com.couli.core.generated.ApiOperations
import com.couli.generated.DefaultTexts
import com.couli.generated.ErrorCodes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CT-LOCK-02（contract.lock 40cfc268 → a7c9f1fc）：升版后网络层的一致性。
 * - 新增的后台专用错误码 30154 / 30417 / 44004（进了错误码表、用户侧字典没有文案键）与表外码同一回落（服务端 msg）；
 *   「表内无文案的码按表外码处理」由 1xxxx 扩到所有码段（三端同口径），页面自处理码（30505 / 44003）与置灰码不受影响，
 *   有文案的码照旧；按「整张表里落到回落的码」对照，升版前后只多这 3 个；
 * - x-auth 新取值 phone（bindInviter / getMyInvite / submitRealname）按需登录处理（只看 auth != none）；
 * - 升版前的 62 个 operation 的方法、路径、鉴权、签名、幂等、二次验证、版本闸门都没变；登记表仍无 /admin/。
 */
class CtLock02ContractUpgradeTest {
    private fun map(
        code: Int,
        data: JsonObject = JsonObject(emptyMap()),
        operationId: String = "getMe",
    ) = ErrorActionMapper.map(
        ErrorActionInput(
            code = code,
            data = data,
            operation = checkNotNull(ApiOperations.byId(operationId)),
            headers = emptyMap(),
            isReplay = false,
            state = ErrorActionState(UpdateRaw.SUPPORTED, SessionScopeRaw.FULL, "1.2.0"),
        ),
    )

    @Test
    fun newAdminOnlyCodesAreInTheTableWithoutUserTexts() {
        for (code in NEW_ADMIN_ONLY) {
            val info = ErrorCodes.info(code)
            assertNotNull("ErrorCodes[$code]", info)
            assertFalse("$code deprecated", info!!.isDeprecated)
            assertNull("user dictionary has no error.$code", DefaultTexts.text("error.$code"))
        }
    }

    @Test
    fun newAdminOnlyCodesFallBackLikeUnlistedCodes() {
        for (unlistedCode in listOf(30999, 44999)) {
            assertNull(ErrorCodes.info(unlistedCode))
            val unlisted = map(unlistedCode)
            assertEquals(ErrorEffectKind.FALLBACK_MESSAGE, unlisted.kind)
            for (code in NEW_ADMIN_ONLY) {
                for (operationId in listOf("getMe", "openLink", "listOrders")) {
                    val effect = map(code, operationId = operationId)
                    assertEquals("$code/$operationId kind", unlisted.kind, effect.kind)
                    assertEquals("$code/$operationId route", unlisted.route, effect.route)
                    assertEquals("$code/$operationId replay", unlisted.replay, effect.replay)
                    assertEquals("$code/$operationId registerPending", unlisted.registerPending, effect.registerPending)
                    assertEquals("$code/$operationId textKey", unlisted.textKey, effect.textKey)
                    assertEquals("$code/$operationId includeTrace6", unlisted.includeTrace6, effect.includeTrace6)
                    assertEquals("$code/$operationId disableMillis", unlisted.disableMillis, effect.disableMillis)
                }
            }
        }
    }

    @Test
    fun existingCodesKeepTheirEffects() {
        // 页面自处理码：表内、字典也没有 error.<code>，仍由页面处理（不被回落吞掉）
        for (code in listOf(30505, 44003)) {
            assertNull(DefaultTexts.text("error.$code"))
            assertEquals("$code", ErrorEffectKind.PAGE_ACTION, map(code).kind)
        }
        // 置灰码
        assertEquals(ErrorEffectKind.DISABLE_BUTTON, map(30206).kind)
        assertEquals(ErrorEffectKind.DISABLE_BUTTON, map(30306).kind)
        // 有文案、无专门分支的码：仍是 Toast + error.<code>
        for (code in listOf(30201, 30401, 10401, 10403)) {
            val effect = map(code)
            assertEquals("$code kind", ErrorEffectKind.TOAST, effect.kind)
            assertEquals("$code textKey", "error.$code", effect.textKey)
        }
        // 10008 / 10009（CT-LOCK-01）仍回落；5xxxx 表内码仍是通用错误态
        assertEquals(ErrorEffectKind.FALLBACK_MESSAGE, map(10008).kind)
        assertEquals(ErrorEffectKind.FALLBACK_MESSAGE, map(10009).kind)
        assertEquals(ErrorEffectKind.GENERIC_ERROR, map(50001).kind)
        // 20001 的新 reason favorites_full 有子键文案：仍是字段错误，文案取子键
        val full = map(20001, JsonObject(mapOf("reason" to JsonPrimitive("favorites_full"))))
        assertEquals(ErrorEffectKind.FIELD_ERROR, full.kind)
        assertEquals("error.20001.favorites_full", full.textKey)
        // 字典将来给新码加了文案：回到表内末尾的 Toast（数据驱动）
        val withText =
            ErrorActionMapper.map(
                ErrorActionInput(
                    code = 30154,
                    data = JsonObject(emptyMap()),
                    operation = checkNotNull(ApiOperations.byId("getMe")),
                    headers = emptyMap(),
                    isReplay = false,
                    state = ErrorActionState(UpdateRaw.SUPPORTED, SessionScopeRaw.FULL, "1.2.0"),
                ),
                hasText = { it == "error.30154" },
            )
        assertEquals(ErrorEffectKind.TOAST, withText.kind)
        assertEquals("error.30154", withText.textKey)
    }

    @Test
    fun onlyTheNewAdminOnlyCodesJoinTheFallback() {
        // 不带 reason 时整张表里落到「服务端 msg 回落」的码：升版前是 10008 / 10009，升版后只多 3 个新码；
        // 锁 00deded0ef6f（FU-X-HOME-PAGE-API，#24）再多 3 个同类后台专用码 30155 / 30156 / 30419（同一判据：表内、无用户文案）
        for (code in NEW_ADMIN_ONLY_00DEDED) {
            assertNotNull("ErrorCodes[$code]", ErrorCodes.info(code))
            assertNull("user dictionary has no error.$code", DefaultTexts.text("error.$code"))
        }
        val fallback =
            ErrorCodes.all.keys
                .filter { ErrorCodes.info(it)?.isDeprecated == false }
                .filter { map(it).kind == ErrorEffectKind.FALLBACK_MESSAGE }
                .toSet()
        // 锁 56c99d0（LOCK-android，#24，D10-1606）再多 1 个同类后台专用码 30147（同一判据）
        for (code in NEW_ADMIN_ONLY_56C99D0) {
            assertNotNull("ErrorCodes[$code]", ErrorCodes.info(code))
            assertNull("user dictionary has no error.$code", DefaultTexts.text("error.$code"))
        }
        assertEquals(setOf(10008, 10009) + NEW_ADMIN_ONLY + NEW_ADMIN_ONLY_00DEDED + NEW_ADMIN_ONLY_56C99D0, fallback)
    }

    @Test
    fun phoneAuthOperationsAreRegisteredAsAuthenticated() {
        for (operationId in listOf("bindInviter", "getMyInvite", "submitRealname")) {
            val operation = ApiOperations.byId(operationId)
            assertNotNull(operationId, operation)
            assertEquals("$operationId auth", "phone", operation!!.auth)
            assertTrue("$operationId needs a session (auth != none)", operation.auth != "none")
        }
        val auths =
            ApiOperations.all.values
                .map { it.auth }
                .toSet()
        assertEquals(setOf("none", "optional", "login", "phone", "realname"), auths)
    }

    @Test
    fun existingOperationsAreUnchangedAndAdminIsStillExcluded() {
        for (row in OPERATIONS_BEFORE) {
            val fields = row.split('|')
            val id = fields[0]
            val method = fields[1]
            val path = fields[2]
            val auth = fields[3]
            val signed = fields[4]
            val idempotent = fields[5]
            val stepUp = fields[6]
            val gate = fields[7]
            val operation = ApiOperations.byId(id)
            assertNotNull(id, operation)
            assertEquals("$id method", method, operation!!.method)
            assertEquals("$id path", path, operation.pathTemplate)
            assertEquals("$id auth", auth, operation.auth)
            assertEquals("$id signed", signed.toBoolean(), operation.signed)
            assertEquals("$id idempotent", idempotent.toBoolean(), operation.idempotent)
            assertEquals("$id stepUp", stepUp.takeUnless { it == "-" }, operation.stepUp)
            assertEquals("$id gate", gate, operation.minVersionGate.name)
        }
        // 锁 00deded0ef6f（FU-X-HOME-PAGE-API，#24）：客户端 operation 86 → 101，只新增 NEW_OPERATIONS_00DEDED 的 15 个，没有删除
        // 锁 a89c1ec3a（FU-X-CTA-KEY-DRIFT，#24，D10-857）：101 → 103，只新增 NEW_OPERATIONS_A89C1EC 的 2 个，没有删除
        // 锁 56c99d0（LOCK-android，#24，D10-1606）：103 → 104，只新增 NEW_OPERATIONS_56C99D0 的 1 个，没有删除
        assertEquals(104, ApiOperations.all.size)
        assertEquals(
            OPERATIONS_BEFORE.size + NEW_OPERATIONS.size + NEW_OPERATIONS_00DEDED.size + NEW_OPERATIONS_A89C1EC.size +
                NEW_OPERATIONS_56C99D0.size,
            ApiOperations.all.size,
        )
        assertEquals(
            NEW_OPERATIONS + NEW_OPERATIONS_00DEDED + NEW_OPERATIONS_A89C1EC + NEW_OPERATIONS_56C99D0,
            ApiOperations.all.keys - OPERATIONS_BEFORE.map { it.substringBefore('|') }.toSet(),
        )
        assertTrue(ApiOperations.all.values.none { it.pathTemplate.startsWith("/admin/") })
    }

    private companion object {
        val NEW_ADMIN_ONLY = setOf(30154, 30417, 44004)

        /** 锁 00deded0ef6f 新增、只出现在 /admin/v1 的码（error-codes.yaml 30155 / 30156 / 30419，action 均为「后台：…」）。 */
        val NEW_ADMIN_ONLY_00DEDED = setOf(30155, 30156, 30419)

        /** 锁 00deded0ef6f 新增的客户端 operation（openapi.yaml 中非 /admin/ 的 operationId，a7c9f1f 没有）。 */
        val NEW_OPERATIONS_00DEDED =
            setOf(
                "getAgreementVersion",
                "listPageSectionItems",
                "createPayOrder",
                "getPayOrder",
                "payOrderReturned",
                "cancelPayOrder",
                "getReverify",
                "reverifyByIdNumber",
                "reverifyByOauth",
                "bindOauthIdentity",
                "listMyDataCopies",
                "getMyDataCopyDownload",
                "getHotRank",
                "getSearchHotWords",
                "createShare",
            )

        /** 锁 a89c1ec3a 新增的客户端 operation（openapi.yaml 中非 /admin/ 的 operationId，00deded0ef6f 没有；均 x-implementation planned）。 */
        val NEW_OPERATIONS_A89C1EC = setOf("submitProductFeedback", "openProductShop")

        /** 锁 56c99d0 新增的客户端 operation（openapi.yaml:2701 POST /v1/events，a89c1ec 没有）。 */
        val NEW_OPERATIONS_56C99D0 = setOf("ingestEvents")

        /** 锁 56c99d0 新增、只出现在 /admin/v1 的码（error-codes.yaml:480 30147，action「后台只提示」）。 */
        val NEW_ADMIN_ONLY_56C99D0 = setOf(30147)

        val NEW_OPERATIONS =
            setOf(
                "mergeAccount",
                "createOrderClaim",
                "listOrderClaims",
                "getAgreements",
                "getPage",
                "listMessages",
                "markMessagesRead",
                "getUnreadMessageCount",
                "updateMe",
                "bindInviter",
                "getMyInvite",
                "sendLandingSmsCode",
                "landingRegister",
                "submitRealname",
                "getLaborAgreement",
                "signLaborAgreement",
                "getNotificationSettings",
                "updateNotificationSettings",
                "getDict",
                "getProductPriceTrend",
                "listFavorites",
                "addFavorite",
                "removeFavorite",
                "batchDeleteFavorites",
            )

        /** 升版前（4dd3876 生成物）的 operation：id|method|path|auth|signed|idempotent|stepUp（- 为无）|minVersionGate。 */
        val OPERATIONS_BEFORE =
            listOf(
                "getHealthz|GET|/healthz|none|false|false|-|UNSPECIFIED",
                "registerDevice|POST|/v1/devices|none|false|false|-|EXEMPT",
                "reportPushToken|POST|/v1/devices/push-token|login|false|false|-|GATED",
                "sendSmsCode|POST|/v1/auth/sms-codes|none|true|false|-|CONDITIONAL",
                "loginBySms|POST|/v1/auth/login/sms|none|true|false|-|EXEMPT",
                "createOauthAttempt|POST|/v1/auth/oauth-attempts|optional|true|false|-|CONDITIONAL",
                "loginByWechat|POST|/v1/auth/login/wechat|none|true|false|-|EXEMPT",
                "loginByApple|POST|/v1/auth/login/apple|none|true|false|-|EXEMPT",
                "loginByHuawei|POST|/v1/auth/login/huawei|none|true|false|-|EXEMPT",
                "stepUp|POST|/v1/auth/step-up|login|true|false|-|CONDITIONAL",
                "refreshToken|POST|/v1/auth/refresh|none|true|false|-|EXEMPT",
                "logout|POST|/v1/auth/logout|login|false|false|-|EXEMPT",
                "recordConsent|POST|/v1/consents|optional|false|false|-|CONDITIONAL",
                "issueH5Token|POST|/v1/auth/h5-token|login|false|false|-|GATED",
                "listUnionBindings|GET|/v1/unions/bindings|login|false|false|-|UNSPECIFIED",
                "getUnionAuthUrl|GET|/v1/unions/{platform}/auth-url|login|false|false|-|UNSPECIFIED",
                "bindUnion|POST|/v1/unions/{platform}/bindings|login|true|true|-|GATED",
                "listOrders|GET|/v1/orders|login|false|false|-|UNSPECIFIED",
                "listPendingTracks|GET|/v1/orders/pending-tracks|login|false|false|-|UNSPECIFIED",
                "dismissPendingTrack|POST|/v1/orders/pending-tracks/{link_id}/dismiss|login|false|false|-|GATED",
                "getOrder|GET|/v1/orders/{order_id}|login|false|false|-|UNSPECIFIED",
                "listArticles|GET|/v1/articles|none|false|false|-|UNSPECIFIED",
                "getArticle|GET|/v1/articles/{article_id}|none|false|false|-|UNSPECIFIED",
                "checkAppVersion|GET|/v1/app-versions/check|none|false|false|-|UNSPECIFIED",
                "previewPage|GET|/v1/pages/{page_key}/preview|none|false|false|-|UNSPECIFIED",
                "getMessage|GET|/v1/messages/{message_id}|login|false|false|-|UNSPECIFIED",
                "getWalletSummary|GET|/v1/wallet/summary|login|false|false|-|UNSPECIFIED",
                "getEarningsSummary|GET|/v1/earnings/summary|login|false|false|-|UNSPECIFIED",
                "listWalletLedger|GET|/v1/wallet/ledger|login|false|false|-|UNSPECIFIED",
                "getWithdrawRules|GET|/v1/withdrawals/rules|login|false|false|-|UNSPECIFIED",
                "listWithdrawals|GET|/v1/withdrawals|login|false|false|-|UNSPECIFIED",
                "createWithdrawal|POST|/v1/withdrawals|realname|true|true|withdraw|GATED",
                "getWithdrawal|GET|/v1/withdrawals/{withdrawal_id}|login|false|false|-|UNSPECIFIED",
                "getMe|GET|/v1/me|login|false|false|-|UNSPECIFIED",
                "bindPhone|POST|/v1/me/phone|login|true|true|phone_change|GATED",
                "getPayoutAccount|GET|/v1/me/payout-account|realname|false|false|-|UNSPECIFIED",
                "savePayoutAccount|PUT|/v1/me/payout-account|realname|true|true|payout_account_change|GATED",
                "getTips|GET|/v1/me/tips|login|false|false|-|UNSPECIFIED",
                "markTipRead|POST|/v1/me/tips/{tip_key}/read|login|false|false|-|GATED",
                "resetTip|DELETE|/v1/me/tips/{tip_key}|login|false|false|-|GATED",
                "getDeletion|GET|/v1/me/deletion|login|false|false|-|UNSPECIFIED",
                "requestDeletion|POST|/v1/me/deletion|login|true|true|account_deletion|EXEMPT",
                "cancelDeletion|POST|/v1/me/deletion/cancel|login|false|false|-|EXEMPT",
                "listAppeals|GET|/v1/me/appeals|login|false|false|-|UNSPECIFIED",
                "submitAppeal|POST|/v1/me/appeals|login|false|true|-|GATED",
                "abandonIdempotencyKey|POST|/v1/idempotency-keys/abandon|login|true|false|-|CONDITIONAL",
                "getConfig|GET|/v1/config|optional|false|false|-|UNSPECIFIED",
                "searchProducts|GET|/v1/products/search|optional|false|false|-|UNSPECIFIED",
                "getProduct|GET|/v1/products/{product_key}|optional|false|false|-|UNSPECIFIED",
                "parseInput|POST|/v1/inputs/parse|optional|true|false|-|GATED",
                "openLink|POST|/v1/links/{link_id}/open|optional|true|true|-|GATED",
                "convertLink|POST|/v1/links/convert|login|true|true|-|GATED",
                "getLink|GET|/v1/links/{link_id}|optional|false|false|-|UNSPECIFIED",
                "getSharePage|GET|/v1/share-pages/{link_id}|none|false|false|-|UNSPECIFIED",
                "getShareTpwd|POST|/v1/share-pages/{link_id}/tpwd|none|false|false|-|EXEMPT",
                "createAgentSession|POST|/v1/agent/sessions|optional|false|false|-|GATED",
                "getCurrentAgentSession|GET|/v1/agent/sessions/current|optional|false|false|-|UNSPECIFIED",
                "listAgentSessionMessages|GET|/v1/agent/sessions/{id}/messages|optional|false|false|-|UNSPECIFIED",
                "sendAgentMessage|POST|/v1/agent/sessions/{id}/messages|optional|false|false|-|GATED",
                "cancelAgentRun|POST|/v1/agent/runs/{run_id}/cancel|optional|false|false|-|GATED",
                "setAgentMessageFeedback|POST|/v1/agent/messages/{message_id}/feedback|optional|false|false|-|GATED",
                "reportAgentMessage|POST|/v1/agent/messages/{message_id}/report|optional|false|false|-|GATED",
            )
    }
}
