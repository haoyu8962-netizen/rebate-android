package com.couli.app.clientcore

import com.couli.core.bridge.BridgeProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LOCK-android（D10-1612 ④ (i)）：bridge_call 的 schema_version 与 bridge_version 各有来源——
 * schema_version = 打包的 bridge.schema.json 顶层 version，bridge_version = BridgeProtocol.contractVersion（app.getEnv 的 bridge_version 同一来源）。
 * 改变其中一个来源只影响对应字段。
 */
class LockBridgeCallDiagnosticsTest {
    private fun fields(
        schemaVersion: String = EventDiagnostics.bridgeSchemaVersion,
        bridgeVersion: Int = BridgeProtocol.contractVersion,
        config: JsonObject? = null,
    ): Map<String, String> =
        EventDiagnostics
            .bridgeCall("WebContainer", "share.open", CODE, config, BridgeVersions(schemaVersion, bridgeVersion))
            .fields
            .mapValues { it.value.content }

    /** 不传来源时（生产调用方式）的字段值。 */
    private fun defaultField(key: String): String =
        EventDiagnostics
            .bridgeCall("P", "m", CODE, null)
            .fields
            .getValue(key)
            .content

    @Test fun schemaVersionComesFromThePackagedBridgeSchemaTopLevelVersion() {
        val packaged =
            requireNotNull(javaClass.classLoader?.getResourceAsStream("bridge.schema.json")) { "缺打包的 bridge.schema.json" }
                .use { Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
                .getValue("version")
                .jsonPrimitive.content
        assertEquals(packaged, EventDiagnostics.bridgeSchemaVersion)
        assertEquals(packaged, defaultField("schema_version"))
    }

    @Test fun bridgeVersionComesFromTheBridgeProtocolLikeAppGetEnv() {
        assertEquals(
            BridgeProtocol.contractVersion.toString(),
            defaultField("bridge_version"),
        )
    }

    @Test fun changingOnlyTheSchemaSourceChangesOnlySchemaVersion() {
        val base = fields()
        val changed = fields(schemaVersion = "99")
        assertEquals("99", changed.getValue("schema_version"))
        assertEquals(base - "schema_version", changed - "schema_version")
    }

    @Test fun changingOnlyTheBridgeSourceChangesOnlyBridgeVersion() {
        val base = fields()
        val changed = fields(bridgeVersion = 7)
        assertEquals("7", changed.getValue("bridge_version"))
        assertEquals(base - "bridge_version", changed - "bridge_version")
    }

    @Test fun configVersionFromConfigAndH5ReleaseUnknown() {
        val withConfig = fields(config = JsonObject(mapOf("config_version" to JsonPrimitive("abc123"))))
        assertEquals("abc123", withConfig.getValue("config_version"))
        assertEquals(EventDiagnostics.UNKNOWN, fields().getValue("config_version"))
        assertEquals(EventDiagnostics.UNKNOWN, withConfig.getValue("h5_release"))
        assertEquals(CODE.toString(), withConfig.getValue("code"))
    }

    private companion object {
        const val CODE = 90403
    }
}
