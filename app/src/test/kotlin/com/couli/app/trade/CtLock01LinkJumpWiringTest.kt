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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * CT-LOCK-01：events.yaml v2 在 link_jump 的 link_id 与 duration_ms 之间插入可选 sdk_open_by、sdk_error_code，生成函数的参数随之后移。
 * 经 AppTrade 的实际上报链路（LinkJumpSink → ClientEvents.linkJump → TrackerQueue）证明调用处参数不错位：duration_ms、installed 落在
 * 原字段上，事件不因必填校验失败被丢弃，也不带新字段（按 sdk 上报归百川接入任务，G05）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class CtLock01LinkJumpWiringTest {
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

    /** 队列文件里所有对象（不依赖队列文件的具体外形：递归收集含 name=link_jump 的对象）。 */
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

    /** 事件里的字段（公共字段与业务字段平铺或在 fields 下，都收进来）。 */
    private fun JsonObject.flat(): Map<String, JsonElement> = this + ((this["fields"] as? JsonObject) ?: emptyMap())

    @Test
    fun purchaseStepFieldsLandOnTheirOwnKeys() {
        val trade = trade()
        trade.sink.report(
            Json
                .parseToJsonElement(
                    """{"phase":"purchase","attempt_id":"attempt-1","link_id":"link-1","union_platform":"taobao","step":"sdk",""" +
                        """"result":"sdk_init_failed","duration_ms":123,"installed":"true"}""",
                ).jsonObject,
        )
        val events = queuedLinkJumps()
        assertEquals("one link_jump queued (not dropped by required-field checks)", 1, events.size)
        val fields = events.single().flat()
        assertEquals("123", (fields["duration_ms"] as JsonPrimitive).content)
        assertEquals("true", (fields["installed"] as JsonPrimitive).content)
        assertEquals("attempt-1", (fields["attempt_id"] as JsonPrimitive).content)
        assertEquals("link-1", (fields["link_id"] as JsonPrimitive).content)
        assertEquals("sdk_init_failed", (fields["result"] as JsonPrimitive).content)
        assertFalse("sdk_open_by not reported", "sdk_open_by" in fields)
        assertFalse("sdk_error_code not reported", "sdk_error_code" in fields)
    }

    @Test
    fun startupStepStillQueuesWithoutDurationOrInstalled() {
        val trade = trade()
        trade.sink.report(
            Json
                .parseToJsonElement("""{"phase":"startup","union_platform":"taobao","step":"sdk","result":"sdk_init_failed"}""")
                .jsonObject,
        )
        val fields = queuedLinkJumps().single().flat()
        assertTrue("startup", (fields["phase"] as JsonPrimitive).content == "startup")
        assertFalse("duration_ms" in fields)
        assertFalse("sdk_open_by" in fields)
    }
}
