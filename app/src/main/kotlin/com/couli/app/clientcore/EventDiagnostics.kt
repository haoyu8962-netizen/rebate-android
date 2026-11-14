package com.couli.app.clientcore

import com.couli.core.bridge.BridgeProtocol
import com.couli.core.clientcore.ClientEvent
import com.couli.core.clientcore.ClientEvents
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 诊断字段（规划/03 §4.7「诊断字段」；events.yaml bridge_call、sdui_card_error、agent_stream_*）：四项必填，
 * 所在层不存在或尚未取到时填固定值 [UNKNOWN]，不留空。
 */
internal object EventDiagnostics {
    const val UNKNOWN = "unknown"

    private const val CONFIG_VERSION = "config_version"
    private const val BRIDGE_SCHEMA_RESOURCE = "bridge.schema.json"
    private const val VERSION = "version"

    /** 生效配置里的 config_version（04 §10.1，= /v1/config 响应 ETag 去引号）；包内默认配置没有它，/v1/config 还没拉到时为 [UNKNOWN]。 */
    fun configVersion(data: JsonObject?): String =
        (data?.get(CONFIG_VERSION) as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.isNotEmpty() }
            ?: UNKNOWN

    /**
     * bridge_call 的 schema_version（03 §4.7，D10-1381 / D10-1606 ④）：本端打包的 contracts/bridge.schema.json（core:generated 资源原样复制）
     * 顶层 version 转成的字符串；资源缺失、读不了或没有 version 时为 [UNKNOWN]（不借用默认值）。
     */
    val bridgeSchemaVersion: String by lazy {
        runCatching {
            EventDiagnostics::class.java.classLoader
                ?.getResourceAsStream(BRIDGE_SCHEMA_RESOURCE)
                ?.use { Json.parseToJsonElement(it.readBytes().decodeToString()) as? JsonObject }
                ?.get(VERSION) as? JsonPrimitive
        }.getOrNull()
            ?.takeIf { it !is JsonNull }
            ?.content
            ?.takeIf { it.isNotEmpty() }
            ?: UNKNOWN
    }

    /**
     * bridge_call 被拒事件（03 §4.7，D10-1612 ④ (i)）：四项诊断字段各有来源——
     * schema_version 与 bridge_version 取 [versions]（见 [BridgeVersions]）；config_version 取生效配置；h5_release 原生取不到
     * （地址不带版本，TODO(N-31)），填 [UNKNOWN]。两个版本号数值可以相同，但不互相借用。
     */
    fun bridgeCall(
        page: String,
        method: String,
        code: Int,
        configData: JsonObject?,
        versions: BridgeVersions = BridgeVersions(),
    ): ClientEvent =
        ClientEvents.bridgeCall(
            page = page,
            method = method,
            code = code.toLong(),
            schemaVersion = versions.schemaVersion,
            bridgeVersion = versions.bridgeVersion.toString(),
            configVersion = configVersion(configData),
            h5Release = UNKNOWN,
        )
}

/**
 * bridge_call 两个版本号的来源（D10-1612 ④ (i)）：[schemaVersion] = 打包的 bridge.schema.json 顶层 version（[EventDiagnostics.bridgeSchemaVersion]）；
 * [bridgeVersion] = BridgeProtocol.contractVersion（与 app.getEnv 的 bridge_version 同一来源）。
 */
internal data class BridgeVersions(
    val schemaVersion: String = EventDiagnostics.bridgeSchemaVersion,
    val bridgeVersion: Int = BridgeProtocol.contractVersion,
)
