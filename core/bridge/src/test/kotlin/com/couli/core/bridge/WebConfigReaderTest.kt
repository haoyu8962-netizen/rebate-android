package com.couli.core.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** APP-04 三端共性第 3 条：桥白名单只来自 bridge_origins（N-22 起只认验签通过的 jws）；h5_base_url 不当白名单，形状不对或缺失时为空。 */
class WebConfigReaderTest {
    private fun config(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    // N-22（D10-2539）：只读数组的 WebConfigReader.trustedOrigins 已删，可信源只来自验签通过的 bridge_origins.jws；
    // 下面三条仍测原来那件事，改为经 BridgeOriginsVerifier.verify(config = …) 判定（拒绝 = 白名单为空）。
    private fun verdict(data: JsonObject): BridgeOriginsVerdict =
        BridgeOriginsVerifier.verify(
            config = data,
            appId = N22Vectors.APP_ID,
            env = N22Vectors.STAGING_ENV,
            lastGoodVersion = null,
            keys = N22Vectors.keys,
        )

    @Test
    fun h5BaseUrlIsNotAWhitelist() {
        val data = config("""{"h5_base_url":"https://h5.example.test/"}""")
        assertEquals(BridgeOriginsVerdict.Rejected("malformed"), verdict(data))
    }

    @Test
    fun arrayShapedBridgeOriginsIsNoLongerTrusted() {
        val data =
            config(
                """{"h5_base_url":"https://h5.example.test/","bridge_origins":["https://A.example.test:443","http://b.example.test",1]}""",
            )
        assertEquals("未签名的数组形状整份不认（不再挑出 https 项）", BridgeOriginsVerdict.Rejected("malformed"), verdict(data))
    }

    @Test
    fun unknownShapeOrMissingContractGivesAnEmptyWhitelist() {
        assertEquals(BridgeOriginsVerdict.Rejected("malformed"), verdict(config("""{"bridge_origins":{}}""")))
        assertEquals(BridgeOriginsVerdict.Rejected("malformed"), verdict(config("{}")))
    }
}
