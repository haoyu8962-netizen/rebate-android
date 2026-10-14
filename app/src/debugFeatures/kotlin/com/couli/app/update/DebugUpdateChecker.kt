package com.couli.app.update

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.edit
import com.couli.app.DebugMockGate
import com.couli.core.config.CouliPreferenceNamespaces
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTypography
import com.couli.core.update.SharedPreferencesUpdateStore
import com.couli.core.update.UpdateCheckAdapter
import com.couli.core.update.UpdateCheckOutcome
import com.couli.core.update.UpdateCheckResult
import com.couli.core.update.UpdateChecker
import com.couli.core.update.UpdateStore
import com.couli.generated.AppVersionCheckExample
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 调试面板的版本检查假数据（只在 debug、staging 编译；Release 里是永远失败的占位，release-scan 查
 * [AppVersionCheckExample.MARKER]）。数据一律从契约 openapi GET /v1/app-versions/check 的响应示例
 * （[AppVersionCheckExample.DATA_JSON]）改几个字段得到，不臆造报文字段。
 */
enum class DebugUpdateScenario(
    val key: String,
    val label: String,
) {
    /** 正常：recommended_version 改成与 min_supported_version 相同（本机 1.0.0 不低于两者）。 */
    NORMAL("normal", "Update: normal"),

    /** 建议更新：契约示例原样（min 1.0.0、recommended 1.1.0、latest 1.2.0）。 */
    RECOMMEND("recommend", "Update: recommend"),

    /** 强制更新：min_supported_version 改成 latest_version。 */
    FORCE("force", "Update: force"),

    /** 商店都不可用：强制更新，且各商店 listed_version 都为 null（没有已知上架版本）。 */
    STORES_UNAVAILABLE("stores_unavailable", "Update: no store"),

    /** 请求失败。 */
    FAILURE("failure", "Update: request failed"),
    ;

    companion object {
        fun fromKey(key: String?): DebugUpdateScenario = entries.firstOrNull { it.key == key } ?: NORMAL
    }
}

/** 调试场景的选择：存在 couli_debug_update（不在 couli. 命名空间，切换环境不清）。 */
internal class DebugUpdateScenarioStore(
    context: Context,
) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    var current: DebugUpdateScenario
        get() = DebugUpdateScenario.fromKey(prefs.getString(KEY, null))
        set(value) {
            prefs.edit { putString(KEY, value.key) }
        }

    private companion object {
        const val PREFERENCES = "couli_debug_update"
        const val KEY = "scenario"
    }
}

/**
 * 读调试场景、返回假结果的检查器；模拟一点网络耗时，让「检查中」可见。
 *
 * 闸门（F-X-02）：[mockAllowed] 放行才给假结果，否则交给真实检查器 [real]；开始时与交付前各判一次（等待期间切到 prod、关掉假服务的不交假结果，
 * 改走真实检查）。生产接统一闸门 [DebugMockGate.of]（假服务开关 + MOCK_DATA + 环境就绪，FU-AND-0714 R1-06：与其余替身同一双重闸门，不再例外）。
 */
internal class DebugUpdateChecker(
    private val scenarios: DebugUpdateScenarioStore,
    private val real: UpdateChecker,
    private val mockAllowed: () -> Boolean,
    private val latencyMillis: Long = FAKE_LATENCY_MILLIS,
) : UpdateChecker {
    private fun allowed(): Boolean = runCatching(mockAllowed).getOrDefault(false)

    @Suppress("ReturnCount") // 入口、交付前两次闸门与失败场景
    override suspend fun check(): UpdateCheckOutcome {
        if (!allowed()) return real.check()
        delay(latencyMillis)
        if (!allowed()) return real.check()
        val scenario = scenarios.current
        if (scenario == DebugUpdateScenario.FAILURE) return UpdateCheckOutcome.Failure
        return UpdateCheckOutcome.Success(adapt(scenarioData(scenario)))
    }

    private companion object {
        const val FAKE_LATENCY_MILLIS = 300L
    }
}

/** 契约示例按场景改字段后的 check.data。 */
internal fun scenarioData(scenario: DebugUpdateScenario): JsonObject {
    val example = Json.parseToJsonElement(AppVersionCheckExample.DATA_JSON).jsonObject
    val fields = example.toMutableMap()
    when (scenario) {
        DebugUpdateScenario.NORMAL -> {
            fields["recommended_version"] = example.getValue("min_supported_version")
        }

        DebugUpdateScenario.RECOMMEND, DebugUpdateScenario.FAILURE -> {
            Unit
        }

        DebugUpdateScenario.FORCE -> {
            fields["min_supported_version"] = example.getValue("latest_version")
        }

        DebugUpdateScenario.STORES_UNAVAILABLE -> {
            fields["min_supported_version"] = example.getValue("latest_version")
            fields["stores"] =
                JsonArray(
                    example.getValue("stores").jsonArray.map { entry ->
                        JsonObject(entry.jsonObject + ("listed_version" to JsonNull))
                    },
                )
        }
    }
    return JsonObject(fields)
}

/**
 * check.data → [UpdateCheckResult]（只投影字段，不做判定；与 Release 真实检查器共用 [UpdateCheckAdapter]）。listed_version 为 null 的商店
 * 丢弃（rule-gaps G04）；recheck_interval_sec 不在 check 响应里，用包内默认（rule-gaps G01）。
 */
internal fun adapt(data: JsonObject): UpdateCheckResult = UpdateCheckAdapter.adapt(data)

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface DebugUpdateEntryPoint {
    fun appUpdate(): AppUpdate
}

/**
 * debug / staging 的版本检查器：统一闸门 [DebugMockGate.of] 放行（假服务开 且 环境允许 MOCK_DATA 且 环境就绪）时给调试假数据，否则（假服务关、prod、
 * 切换中）用 App 组合的真实检查器 [real]（F-X-02；FU-AND-0714 R1-06：不再保留只看 MOCK_DATA 的例外）。入口与交付前各判一次。
 */
internal fun variantUpdateChecker(
    context: Context,
    real: UpdateChecker,
): UpdateChecker {
    val app = context.applicationContext
    return DebugUpdateChecker(DebugUpdateScenarioStore(app), real, mockAllowed = DebugMockGate.of(app))
}

/**
 * debug / staging 的版本检查结论存储（R1-03）：调试假结果只写 couli.update_debug，不写 Release 读取的 couli.update，
 * 同签名覆盖安装成 Release 后不会被假的强更结论永久拦住。
 */
internal fun variantUpdateStore(context: Context): UpdateStore =
    SharedPreferencesUpdateStore(context, CouliPreferenceNamespaces.UPDATE_DEBUG)

/** 调试面板里的「版本检查假数据」切换：选中后立即重新检查（冒烟与设计稿比对截图用）。 */
@Composable
internal fun DebugUpdateSection() {
    val context = LocalContext.current
    val scenarios = remember(context) { DebugUpdateScenarioStore(context) }
    val update =
        remember(context) {
            EntryPointAccessors.fromApplication(context.applicationContext, DebugUpdateEntryPoint::class.java).appUpdate()
        }
    var selected by remember { mutableStateOf(scenarios.current) }
    Column(modifier = Modifier.testTag(AppVersionCheckExample.MARKER)) {
        Text(text = "Update check: ${selected.key}", style = CouliTypography.footnote, color = CouliColors.textSecondary)
        DebugUpdateScenario.entries.forEach { scenario ->
            TextButton(
                onClick = {
                    scenarios.current = scenario
                    selected = scenario
                    update.recheck()
                },
                enabled = scenario != selected,
                modifier = Modifier.testTag("debug_update_" + scenario.key),
            ) {
                Text(text = scenario.label)
            }
        }
    }
}
