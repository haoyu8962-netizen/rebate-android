package com.couli.app.wallet.visual

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import com.couli.core.clientcore.AmountMaskStore
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.couliColorTokens
import com.couli.core.network.ApiResult
import com.couli.core.wallet.StepUpTokenCache
import com.couli.core.wallet.WalletDraftTexts
import com.couli.core.wallet.WalletGates
import com.couli.core.wallet.WalletTexts
import com.couli.feature.wallet.PageHooks
import com.couli.feature.wallet.WalletDeps
import com.couli.feature.wallet.WalletPageConfig
import com.couli.feature.wallet.WalletTags
import com.couli.feature.wallet.WithdrawRecordsController
import com.couli.feature.wallet.WithdrawRecordsScreen
import com.couli.feature.wallet.WithdrawRecordsViewModel
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** 真实页面和控制器；只替换网络、剪贴板、导航边界。 */
internal class WithdrawRecordsVisualTestHost(
    private val rule: ComposeContentTestRule,
) : AutoCloseable {
    private val oldZone = TimeZone.getDefault()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val api = WithdrawRecordsVisualApi()
    val env = WithdrawRecordsVisualEnv()
    var hidden = false
    var texts: WalletTexts = WalletDraftTexts
    lateinit var controller: WithdrawRecordsController
    var colors: Map<String, Color> = emptyMap()
    private var leave: () -> Unit = {}
    private val windowWidth = mutableStateOf<Int?>(null)

    /** 同一 Activity / ViewModel 下重新约束页面宽度，并同步窗口信息；不保存或恢复任何滚动状态。 */
    fun resizeWindow(widthDp: Int) {
        rule.runOnIdle { windowWidth.value = widthDp }
        rule.waitForIdle()
    }

    fun launch(
        name: String = "list",
        dark: Boolean = false,
        fontScale: Float = 1f,
        focus: String? = null,
        shell: Boolean = false,
    ) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        val context: Context = ApplicationProvider.getApplicationContext()
        context
            .getSharedPreferences("couli_amount_mask", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val mask = AmountMaskStore(context)
        if (hidden) mask.toggle()
        if (api.answer == null) api.answer = WithdrawRecordsVisualFixtures.ok(WithdrawRecordsVisualFixtures.page(name))
        val deps =
            WalletDeps(
                api,
                env,
                WalletGates(scope) { WithdrawRecordsVisualFixtures.now },
                StepUpTokenCache(),
                null,
                mask,
                null,
                null,
                texts = texts,
                clock = { WithdrawRecordsVisualFixtures.now },
                service = scope,
            )
        val params = JsonObject(focus?.let { mapOf("withdrawal_id" to JsonPrimitive(it)) }.orEmpty())
        val config = WalletPageConfig(deps, params = params, onBack = {}, hooks = PageHooks(bindLeave = { leave = it }))
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalWindowInfo provides resizedWindowInfo(LocalWindowInfo.current, windowWidth.value, density),
            ) {
                CouliTheme(darkTheme = dark) {
                    colors = couliColorTokens()
                    val model: WithdrawRecordsViewModel = viewModel { WithdrawRecordsViewModel(config) }
                    controller = model.controller
                    val width = windowWidth.value?.let { Modifier.width(it.dp).testTag("records_test_window") } ?: Modifier
                    Column(width.fillMaxSize().background(CouliColors.backgroundCanvas)) {
                        if (shell) Spacer(Modifier.height(54.dp))
                        WithdrawRecordsScreen(config)
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    fun toggle(id: String) {
        rule.onNodeWithTag(WalletTags.RECORDS_LIST).performScrollToNode(hasTestTag(WalletTags.RECORD_PREFIX + id))
        rule
            .onNode(
                hasClickAction() and (
                    hasTestTag(
                        WalletTags.RECORD_PREFIX + id,
                    ) or hasAnyAncestor(hasTestTag(WalletTags.RECORD_PREFIX + id))
                ) and
                    androidx.compose.ui.test.SemanticsMatcher
                        .keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.StateDescription),
                useUnmergedTree = true,
            ).performClick()
        rule.waitForIdle()
    }

    fun refresh(result: ApiResult) {
        api.answer = result
        rule.runOnIdle { controller.refresh() }
        rule.waitForIdle()
    }

    fun assertCalls(vararg operations: String) {
        assertEquals(operations.toList(), api.calls.map { it.operationId })
        assertTrue("确认和放弃均不得发生", api.pendingWrites.isEmpty())
        assertTrue("每次读取仍归属原账号", api.calls.all { it.expectedAccount == env.account() })
    }

    override fun close() {
        rule.runOnIdle {
            leave()
            scope.cancel()
        }
        TimeZone.setDefault(oldZone)
    }
}

/** 使用与真实布局相同的像素宽度；未启用尺寸覆盖时仍提供原窗口对象，其余窗口属性委托原对象。 */
private fun resizedWindowInfo(
    current: WindowInfo,
    widthDp: Int?,
    density: Density,
): WindowInfo =
    if (widthDp == null) {
        current
    } else {
        object : WindowInfo by current {
            override val containerSize = IntSize(with(density) { widthDp.dp.roundToPx() }, current.containerSize.height)
            override val containerDpSize = DpSize(widthDp.dp, with(density) { containerSize.height.toDp() })
        }
    }
