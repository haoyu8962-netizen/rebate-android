package com.couli.app.home

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.couli.app.DebugFakeHome
import com.couli.app.DebugMockGate
import com.couli.app.FAKE_HOME_STATE_FILE
import com.couli.app.VariantRootContent
import com.couli.app.buildDefaultEnvironment
import com.couli.app.routing.RoutingTrace
import com.couli.core.config.EnvironmentSwitchCleanerRegistry
import com.couli.core.config.EnvironmentSwitcher
import com.couli.core.home.HomeCall
import com.couli.core.home.HomeContractPort
import com.couli.core.network.ApiResult
import java.io.File

/**
 * debug / staging 的首页端口（FU-X-HOME-PAGE-API）：默认（staging 默认、假服务开关关）走 [real]（契约 getPage / listPageSectionItems 经 ApiClient）；
 * 只有统一闸门 [DebugMockGate] 打开（测试参数：开关文件 + 环境允许 MOCK_DATA + 环境就绪，F-X-02）时换成本机假服务。闸门**每次调用现判**，
 * 不在构造时选一次；读闸门出错按关闭（走 real）。选择在端口层做：拦截器没有 `/v1/pages/home` 的分支，不靠它回答。
 */
internal fun variantHomePort(
    context: Context,
    real: HomeContractPort,
): HomeContractPort = GatedHomePort(DebugMockGate.of(context), { debugFakeHome(context) }, real)

/** HomePreview 的草稿数据源（D10-607 D7 草稿预览不动）：仍是本机假服务（只在统一闸门打开时回答，否则按断网）。 */
internal fun variantPreviewPort(context: Context): HomeContractPort? = debugFakeHome(context)

/** 每次调用现判 [gate]：开 → [fake]，否则 → [real]。 */
private class GatedHomePort(
    private val gate: () -> Boolean,
    private val fake: () -> HomeContractPort,
    private val real: HomeContractPort,
) : HomeContractPort {
    private fun pick(): HomeContractPort = if (runCatching(gate).getOrDefault(false)) fake() else real

    override suspend fun getPage(
        pageKey: String,
        call: HomeCall,
    ): ApiResult = pick().getPage(pageKey, call)

    override suspend fun listSectionItems(
        pageKey: String,
        sectionId: String,
        cursor: String?,
        call: HomeCall,
    ): ApiResult = pick().listSectionItems(pageKey, sectionId, cursor, call)
}

/** 当前环境名（LKG 分区，G16 ②）：显式选择或构建默认。 */
internal fun variantEnvironmentKey(context: Context): String = currentEnvironment(context).key

private fun currentEnvironment(context: Context) =
    EnvironmentSwitcher(context.applicationContext, buildDefaultEnvironment(), EnvironmentSwitchCleanerRegistry()).current

/** 进程内唯一的假首页服务（待定 operation 端口与拦截器共用）。 */
internal fun debugFakeHome(context: Context): DebugFakeHome =
    synchronized(FakeHomes) {
        FakeHomes.instance ?: run {
            val app = context.applicationContext
            // 统一闸门（F-X-02，DebugMockGate）：开关 + MOCK_DATA + 环境就绪；第二道只核对环境（双重核对，结果相同）
            DebugFakeHome(
                stateFile = File(DebugMockGate.stateDir(app), FAKE_HOME_STATE_FILE),
                switchOn = DebugMockGate.of(app),
                mockAllowed = { DebugMockGate.mockDataAllowed(app) },
            ).also { FakeHomes.instance = it }
        }
    }

private object FakeHomes {
    var instance: DebugFakeHome? = null
}

/**
 * debug / staging 的首页 Tab 内容：正式首页（同一个渲染器）+ 列表底部的开发入口（Debug panel，英文开发字样）；打开后是原来的调试首屏（环境切换、组件目录、
 * 一致性页……），顶部【Back to home】回到首页。
 */
@Composable
internal fun VariantHomeContent(
    home: AppHome?,
    trace: RoutingTrace?,
) {
    var panel by rememberSaveable { mutableStateOf(home == null) }
    if (panel || home == null) {
        Box(modifier = Modifier.fillMaxSize()) {
            VariantRootContent(trace = trace)
            if (home != null) {
                TextButton(onClick = { panel = false }, modifier = Modifier.align(Alignment.TopEnd).testTag(DEBUG_BACK_HOME_TAG)) {
                    Text(text = "Back to home")
                }
            }
        }
        return
    }
    HomeRoute(home) {
        item(
            key =
                com.couli.feature.home.HomeListKeys
                    .extra("debug_entry"),
        ) {
            TextButton(onClick = { panel = true }, modifier = Modifier.fillMaxWidth().testTag(DEBUG_ENTRY_TAG)) {
                Text(text = "Debug panel")
            }
        }
    }
}

internal const val DEBUG_ENTRY_TAG = "home_debug_entry"
internal const val DEBUG_BACK_HOME_TAG = "home_debug_back"
