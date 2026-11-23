package com.couli.app.wallet.labor

import com.couli.app.wallet.visual.WithdrawVisualFixtures
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingTarget
import com.couli.core.network.ReplayMode
import com.couli.feature.wallet.LaborBackend
import com.couli.feature.wallet.WalletApi
import com.couli.feature.wallet.WalletEnv
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * FU-AND-LABOR-AGREEMENT-API 写测的公共底座。
 *
 * 接口约定（实现照此写；HEAD 9f015b2 上都不存在，所以一律按名反射取，取不到由断言判红，不让编译失败）：
 * - `com.couli.feature.wallet.RemoteLaborBackend`（文件 feature/wallet/.../RemoteLaborBackend.kt，单类文件按类名命名，避开 key / api 字样）：
 *   `class RemoteLaborBackend(api: WalletApi, fetchText: suspend (url: String, expectedSha256: String) -> String?, account: () -> String?,
 *   newKey: () -> String) : LaborBackend`（参数顺序固定；newKey 可带缺省值 UUID）。fetchText 的第二个参数是服务端给的 text_sha256：生产实现按
 *   原始字节核对摘要，相符才交出正文，否则返回 null（改口 r1：摘要核对显式化，不再经协程上下文隐式传）。
 * - `com.couli.app.wallet.chooseLaborBackend`（文件 app/src/main/.../wallet/LaborWiring.kt → 门面类 LaborWiringKt）：
 *   `internal fun chooseLaborBackend(kind: String, fake: LaborBackend?, real: LaborBackend): LaborBackend`。
 */
internal object LaborRemoteReflect {
    const val BACKEND_CLASS = "com.couli.feature.wallet.RemoteLaborBackend"
    const val WIRING_CLASS = "com.couli.app.wallet.LaborWiringKt"
    const val CHOOSER = "chooseLaborBackend"
    private const val BACKEND_ARITY = 4
    private const val CHOOSER_ARITY = 3

    fun backend(
        api: WalletApi,
        fetchText: suspend (url: String, expectedSha256: String) -> String?,
        account: () -> String?,
        newKey: () -> String,
    ): LaborBackend {
        val type = runCatching { Class.forName(BACKEND_CLASS) }.getOrNull()
        assertNotNull("需要 feature/wallet 的 RemoteLaborBackend（经 WalletApi 调 getLaborAgreement / signLaborAgreement）", type)
        val constructor =
            checkNotNull(type).constructors.firstOrNull { c ->
                c.parameterTypes.size == BACKEND_ARITY &&
                    c.parameterTypes[0] == WalletApi::class.java &&
                    // suspend (url, expectedSha256) -> String? 在 JVM 上是 Function3（两个参数 + Continuation）
                    c.parameterTypes[1] == Function3::class.java
            }
        assertNotNull(
            "RemoteLaborBackend 构造参数应为 (api: WalletApi, fetchText: suspend (url, expectedSha256) -> String?, account, newKey)",
            constructor,
        )
        val instance = unwrap { checkNotNull(constructor).newInstance(api, fetchText, account, newKey) }
        assertTrue("RemoteLaborBackend 实现 LaborBackend", instance is LaborBackend)
        return instance as LaborBackend
    }

    fun choose(
        kind: String,
        fake: LaborBackend?,
        real: LaborBackend,
    ): Any? {
        val facade = runCatching { Class.forName(WIRING_CLASS) }.getOrNull()
        assertNotNull("需要 app 的 chooseLaborBackend（app/src/main/kotlin/com/couli/app/wallet/LaborWiring.kt）", facade)
        val method =
            checkNotNull(facade).declaredMethods.firstOrNull { m ->
                (m.name == CHOOSER || m.name.startsWith("$CHOOSER$")) && m.parameterTypes.size == CHOOSER_ARITY
            }
        assertNotNull("chooseLaborBackend(kind: String, fake: LaborBackend?, real: LaborBackend)", method)
        checkNotNull(method).isAccessible = true
        return unwrap { method.invoke(null, kind, fake, real) }
    }

    private fun <T> unwrap(block: () -> T): T =
        try {
            block()
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
}

/** 契约快照里的样例（contracts/snapshot/contracts/openapi.json）：响应、请求形状以契约为准。 */
internal object LaborContract {
    const val GET_OPERATION = "getLaborAgreement"
    const val SIGN_OPERATION = "signLaborAgreement"
    const val GET_PATH = "/v1/me/labor-agreement"
    const val SIGN_PATH = "/v1/me/labor-agreement/sign"
    const val TRACE = "trace-labor-0001"
    val KEY_PATTERN = Regex("^[A-Za-z0-9_-]{8,64}$")

    /** 法务正文的替身（text_url 指向的全文；测试值，不是真实协议）。 */
    const val TEXT = "劳务协议测试正文：第一条……第二条……（测试替身）"

    private val openapi: JsonObject by lazy {
        Json
            .parseToJsonElement(File(WithdrawVisualFixtures.root, "contracts/snapshot/contracts/openapi.json").readText())
            .jsonObject
    }

    private fun getExample(name: String): JsonObject =
        openapi
            .getValue("paths")
            .jsonObject
            .getValue(GET_PATH)
            .jsonObject
            .getValue("get")
            .jsonObject
            .getValue("responses")
            .jsonObject
            .getValue("200")
            .jsonObject
            .getValue("content")
            .jsonObject
            .getValue("application/json")
            .jsonObject
            .getValue("examples")
            .jsonObject
            .getValue(name)
            .jsonObject
            .getValue("value")
            .jsonObject
            .getValue("data")
            .jsonObject

    /** 契约样例 needSign：{version: 2, text_url, text_sha256, need_sign: true, signed_version: 1, signed_at}。 */
    val needSign: JsonObject by lazy { getExample("needSign") }

    /** 契约样例 signed：need_sign=false。 */
    val signed: JsonObject by lazy { getExample("signed") }

    val textUrl: String get() = needSign.text("text_url")
    val sha: String get() = needSign.text("text_sha256")
    val version: Int get() = needSign.text("version").toInt()

    fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
}

/** 钱包环境替身（照 WalletReviewR2Test.Env）。 */
internal class LaborRemoteEnv : WalletEnv {
    var epoch = 1L
    var user: String? = "user-a"
    var forced = 0

    override fun loggedIn() = user != null

    override fun sessionEpoch() = epoch

    override fun account() = user

    override fun configData(): JsonObject? = null

    override fun appVersion() = "1.0.0"

    override fun earningsSince(): String? = null

    override fun open(
        route: String,
        params: JsonObject,
    ) = Unit

    override fun replaceTop(
        route: String,
        params: JsonObject,
        entryId: String?,
    ) = Unit

    override fun copy(text: String) = true

    override fun forceUpdate() {
        forced += 1
    }

    override fun stepUpPhone(account: String) = "13800005678"
}

/** 可控的 [WalletApi]（照 WalletReviewR2Test.FakeApi）：记下每个 ApiCall，按 [answer] 同步回答。 */
internal class LaborRemoteApi : WalletApi {
    val calls = mutableListOf<ApiCall>()
    var answer: (ApiCall) -> ApiResult = { ApiResult.LocalReject("unanswered") }

    fun of(operationId: String) = calls.filter { it.operationId == operationId }

    override fun pendingFor(operationId: String): PendingKeyRecord? = null

    override fun restorePending() = true

    override suspend fun confirmPending(
        operationId: String,
        stepUpToken: String?,
        target: PendingTarget,
    ): ApiResult = ApiResult.LocalReject("unused")

    override suspend fun abandonPending(
        operationId: String,
        target: PendingTarget,
    ): ApiResult = ApiResult.LocalReject("unused")

    override suspend fun send(call: ApiCall): ApiResult {
        calls += call
        return answer(call)
    }

    override fun cancelOwned(owner: CallOwnership) = Unit

    override fun epochs() = CallEpochs(0, 0)
}

internal object LaborResults {
    fun ok(data: JsonObject) = ApiResult.Success(data, LaborContract.TRACE, null)

    fun ok(data: String) = ApiResult.Success(Json.parseToJsonElement(data), LaborContract.TRACE, null)

    fun fail(
        code: Int,
        data: String = "{}",
        kind: ErrorEffectKind = ErrorEffectKind.TOAST,
    ) = ApiResult.Failure(
        code,
        "",
        Json.parseToJsonElement(data).jsonObject,
        LaborContract.TRACE,
        ErrorEffect(code, kind, ReplayMode.NONE, false),
        null,
    )

    fun disconnect(key: String?) = ApiResult.TransportFailure("disconnect", null, key, false)
}
