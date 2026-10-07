package com.couli.core.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** APP-04 三端共性第 3 条：桥白名单只来自 bridge_origins；h5_base_url 不当白名单，形状不对或缺失时为空。 */
class WebConfigReaderTest {
    private fun config(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun h5BaseUrlIsNotAWhitelist() {
        val data = config("""{"h5_base_url":"https://h5.example.test/"}""")
        assertTrue(WebConfigReader.trustedOrigins(data).isEmpty())
    }

    @Test
    fun onlyHttpsOriginsFromBridgeOriginsAreTrusted() {
        val data =
            config(
                """{"h5_base_url":"https://h5.example.test/","bridge_origins":["https://A.example.test:443","http://b.example.test",1]}""",
            )
        assertEquals(setOf("https://a.example.test"), WebConfigReader.trustedOrigins(data))
    }

    @Test
    fun unknownShapeOrMissingContractGivesAnEmptyWhitelist() {
        assertTrue(WebConfigReader.trustedOrigins(config("""{"bridge_origins":{}}""")).isEmpty())
        assertTrue(WebConfigReader.trustedOrigins(config("{}")).isEmpty())
    }
}
