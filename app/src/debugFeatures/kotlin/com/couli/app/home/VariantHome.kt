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
import com.couli.core.home.HomeContractPort
import java.io.File

/**
 * debug / staging：首页的待定 operation（G02、G03）由本机假服务实现；它只在「假服务开关在 + 当前环境允许 MOCK_DATA」时回答（prod 下按断网，F-X-02）。
 */
internal fun variantHomePort(context: Context): HomeContractPort? = debugFakeHome(context)

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
