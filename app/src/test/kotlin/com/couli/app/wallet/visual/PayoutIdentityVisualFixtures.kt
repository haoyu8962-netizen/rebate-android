package com.couli.app.wallet.visual

import com.couli.core.network.ApiResult
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.ReplayMode
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** APP-09v-e：只读取新增的 visualPayout 组；收款账号报文是锁定契约形状，实名 / 劳务是 Debug 手写接口形状（G-W24）。 */
internal object PayoutIdentityVisualFixtures {
    val root: File = WithdrawVisualFixtures.root
    val group: JsonObject =
        WithdrawVisualFixtures
            .obj(File(root, "specs/wallet-vectors.json").readText())
            .getValue("visualPayout")
            .jsonObject
    val now: Long =
        group
            .getValue("clock")
            .jsonObject
            .getValue("nowMillis")
            .jsonPrimitive.long

    const val TRACE = "visual-e-abc123"

    fun payout(name: String): JsonObject = section("payout").getValue(name).jsonObject

    fun form(
        name: String,
        field: String,
    ): String =
        section("forms")
            .getValue(name)
            .jsonObject
            .getValue(field)
            .jsonPrimitive.content

    fun realnameForm(field: String): String =
        section("realname")
            .getValue("form")
            .jsonObject
            .getValue(field)
            .jsonPrimitive.content

    fun saveError(name: String): ApiResult = failure(section("saveErrors").getValue(name).jsonObject)

    fun realnameError(name: String): ApiResult =
        failure(
            section("realname")
                .getValue("errors")
                .jsonObject
                .getValue(name)
                .jsonObject,
        )

    fun labor(name: String = "first"): JsonObject = section("labor").getValue(name).jsonObject

    fun laborSignFailure(): ApiResult = failure(labor("signFailed"))

    fun boards(): List<String> = group.getValue("boards").jsonArray.map { it.jsonPrimitive.content }

    fun ok(data: JsonObject = WithdrawVisualFixtures.obj("{}")): ApiResult = ApiResult.Success(data, TRACE, null)

    private fun section(name: String): JsonObject = group.getValue(name).jsonObject

    private fun failure(entry: JsonObject): ApiResult {
        val code = entry.getValue("code").jsonPrimitive.int
        return ApiResult.Failure(
            code,
            "",
            entry.getValue("data").jsonObject,
            TRACE,
            ErrorEffect(code, ErrorEffectKind.GENERIC_ERROR, ReplayMode.NONE, false, null),
            null,
        )
    }
}
