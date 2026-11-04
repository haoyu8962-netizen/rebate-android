package com.couli.core.home

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** FU-X-HOME-PAGE-API 测试 v3，D10-618 T3：调用归属保留首页固定身份的世代（MX3）。 */
class HomePageApiEpochsTest {
    private class RecordingPageApi : PageApi {
        val calls = mutableListOf<ApiCall>()

        override suspend fun send(call: ApiCall): ApiResult {
            calls += call
            return ApiResult.Success(JsonObject(emptyMap()), null, null)
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs(): CallEpochs = CallEpochs(7, 11)
    }

    @Test
    fun t3ExplicitHomeEpochsOwnBothCallsEvenWhenExecutionEpochsDiffer() {
        val api = RecordingPageApi()
        val fixedEpochs = CallEpochs(3, 5)
        val home =
            HomeCall(
                anonymous = false,
                expectedAccount = null,
                allowed = { true },
                stillOwned = { true },
                epochs = fixedEpochs,
            )
        assertNotEquals("前提：隐私世代不同", fixedEpochs.privacy, api.epochs().privacy)
        assertNotEquals("前提：环境世代不同", fixedEpochs.environment, api.epochs().environment)
        val port = ApiClientHomeContractPort(api)
        runBlocking {
            port.getPage("home", home)
            port.listSectionItems("home", "draft-grid", null, home)
        }
        assertEquals(listOf("getPage", "listPageSectionItems"), api.calls.map { it.operationId })
        api.calls.forEach { call ->
            assertEquals("${call.operationId} 必须沿用 HomeCall 的固定世代", fixedEpochs, call.ownership?.epochs)
        }
    }
}
