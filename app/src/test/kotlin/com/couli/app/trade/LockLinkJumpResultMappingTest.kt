package com.couli.app.trade

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.routing.AppRouting
import com.couli.core.clientcore.ClientPlatformRaw
import com.couli.core.clientcore.PrivacyStateRaw
import com.couli.core.clientcore.TrackerQueue
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.core.tradejump.UnavailableBaichuan
import com.couli.feature.trade.JumpPorts
import com.couli.feature.trade.JumpTypes
import com.couli.feature.trade.UnavailableWebCodeAuthorizer
import javax.inject.Provider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * LOCK-android（锁 56c99d0，events.yaml version 6 link_jump.result / failure_reason）：AppTrade 上报口把步骤执行器的结果词
 * 逐条映射成契约取值。launched / copied → requested（不带 failure_reason）；failed → failed + step_failed；
 * sdk_init_failed → failed + sdk_init_failed；契约值 requested / unknown 原样；其余不上报。失败一律带 failure_reason、绝不记成 requested。
 * 经实际链路（LinkJumpSink → ClientEvents.linkJump → TrackerQueue）断言，不碰私有函数。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class LockLinkJumpResultMappingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val api =
        object : PageApi {
            override suspend fun send(call: ApiCall): ApiResult = error("not used")

            override fun cancelOwned(owner: CallOwnership) = Unit

            override fun epochs(): CallEpochs = CallEpochs(0, 0)
        }

    private val ports =
        object : JumpPorts {
            override fun installed(platform: String): String = "unknown"

            override fun launchApp(
                type: String,
                value: String,
            ): Boolean = false

            override fun openBrowser(url: String): Boolean = false

            override fun copy(text: String): Boolean = false
        }

    private var file: String? = null

    private val queue =
        TrackerQueue(
            readFile = { file },
            writeFile = { file = it },
            sink = { _: List<String> -> false },
            privacy = PrivacyStateRaw.CONSENTED,
            userId = { null },
            deviceId = "device-fixture",
            platform = ClientPlatformRaw.ANDROID,
            appVersion = "1.0.0",
            sessionId = "session-test",
            clock = { 0L },
            canUpload = { false },
        )

    private fun trade(): AppTrade {
        val gate = EnvironmentGate { EnvironmentStartupReconciler.Outcome.CLEANED_AND_RECORDED }.also { it.settleAtStartup() }
        val trade =
            AppTrade(
                api = api,
                store = null,
                routing = AppRouting(context, gate),
                tracker = Provider { queue },
                baichuan = UnavailableBaichuan(),
                webCode = UnavailableWebCodeAuthorizer,
                ports = ports,
                browserNoticeMillis = 0L,
            )
        shadowOf(Looper.getMainLooper()).idle()
        return trade
    }

    private fun queuedLinkJumps(): List<JsonObject> {
        val found = mutableListOf<JsonObject>()

        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    if ((element["name"] as? JsonPrimitive)?.content == "link_jump") found += element
                    element.values.forEach(::walk)
                }

                is JsonArray -> {
                    element.forEach(::walk)
                }

                is JsonPrimitive -> {
                    if (element.isString && element.content.trimStart().startsWith("{")) {
                        runCatching { Json.parseToJsonElement(element.content) }.getOrNull()?.let(::walk)
                    }
                }
            }
        }
        file?.let { walk(Json.parseToJsonElement(it)) }
        return found
    }

    private fun JsonObject.flat(): Map<String, JsonElement> = this + ((this["fields"] as? JsonObject) ?: emptyMap())

    /** 购买外跳的一步，step 与 result 可换；返回上报后队列里的 link_jump 字段（没上报为 null）。 */
    private fun report(
        result: String,
        step: String = "sdk",
    ): Map<String, JsonElement>? {
        val before = queuedLinkJumps().size
        trade().sink.report(
            Json
                .parseToJsonElement(
                    """{"phase":"purchase","attempt_id":"attempt-1","link_id":"link-1","union_platform":"taobao",""" +
                        """"step":"$step","result":"$result","duration_ms":10,"installed":"true"}""",
                ).jsonObject,
        )
        val after = queuedLinkJumps()
        assertTrue("at most one link_jump per report", after.size - before <= 1)
        return if (after.size == before + 1) after.last().flat() else null
    }

    private fun Map<String, JsonElement>.text(key: String): String? = (this[key] as? JsonPrimitive)?.content

    @Test
    fun launchedIsRequestedWithoutFailureReason() {
        val fields = checkNotNull(report(JumpTypes.LAUNCHED)) { "launched must be reported" }
        assertEquals("requested", fields.text("result"))
        assertFalse("requested carries no failure_reason", "failure_reason" in fields)
    }

    @Test
    fun copiedIsRequestedWithoutFailureReason() {
        val fields = checkNotNull(report(JumpTypes.COPIED, step = "copy_tpwd")) { "copied must be reported" }
        assertEquals("requested", fields.text("result"))
        assertFalse("requested carries no failure_reason", "failure_reason" in fields)
    }

    @Test
    fun failedIsFailedWithStepFailed() {
        val fields = checkNotNull(report(JumpTypes.FAILED, step = "scheme")) { "failed must be reported" }
        assertEquals("failed", fields.text("result"))
        assertEquals("step_failed", fields.text("failure_reason"))
    }

    @Test
    fun sdkInitFailedIsFailedWithSdkInitFailed() {
        val fields = checkNotNull(report(JumpTypes.SDK_INIT_FAILED)) { "sdk_init_failed must be reported" }
        assertEquals("failed", fields.text("result"))
        assertEquals("sdk_init_failed", fields.text("failure_reason"))
    }

    @Test
    fun failuresAlwaysCarryAReasonAndAreNeverRequested() {
        for (raw in listOf(JumpTypes.FAILED, JumpTypes.SDK_INIT_FAILED)) {
            val fields = checkNotNull(report(raw)) { "$raw must be reported" }
            assertNotEquals("$raw never becomes requested", "requested", fields.text("result"))
            assertEquals("$raw result", "failed", fields.text("result"))
            assertTrue("$raw carries failure_reason", fields.text("failure_reason") in setOf("sdk_init_failed", "step_failed"))
        }
    }

    @Test
    fun contractValuesPassThroughAndUnknownWordsAreDropped() {
        assertEquals("requested", report("requested")?.text("result"))
        val unknown = checkNotNull(report("unknown")) { "unknown must be reported" }
        assertEquals("unknown", unknown.text("result"))
        assertFalse("unknown carries no failure_reason", "failure_reason" in unknown)
        assertEquals("unlisted word is not reported", null, report("something_else"))
    }
}
