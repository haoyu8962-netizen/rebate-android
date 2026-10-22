package com.couli.app.wallet.visual

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.couli.core.designsystem.CouliAnnouncer
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalCouliAnnouncer
import com.couli.core.wallet.StepUpTokenCache
import com.couli.core.wallet.WalletGates
import com.couli.feature.wallet.PageHooks
import com.couli.feature.wallet.PayoutAccountScreen
import com.couli.feature.wallet.PayoutAccountViewModel
import com.couli.feature.wallet.SensitiveFlow
import com.couli.feature.wallet.WalletDeps
import com.couli.feature.wallet.WalletPageConfig
import com.couli.feature.wallet.WalletTags
import com.couli.feature.wallet.WithdrawScreen
import com.couli.feature.wallet.WithdrawViewModel
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.robolectric.shadows.ShadowLooper

internal class WithdrawVisualTestHost(
    private val rule: ComposeContentTestRule,
) : AutoCloseable {
    private val zone = TimeZone.getDefault()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val api = WithdrawVisualApi()
    val env = WithdrawVisualEnv()
    val tokens = StepUpTokenCache()
    val saved = SavedStateHandle()
    val spoken = mutableListOf<String>()
    val colors = mutableMapOf<String, androidx.compose.ui.graphics.Color>()

    // v2 遮罩白卡采样，仅在测试显式提供时绘制；旧场景默认不增加节点或高度。
    var reference: @Composable () -> Unit = {}
    lateinit var model: WithdrawViewModel
    lateinit var payoutModel: PayoutAccountViewModel
    private var payoutPage = false
    private var leave: () -> Unit = {}
    val flow: SensitiveFlow get() = if (payoutPage) payoutModel.controller.flow else model.controller.flow
    val deps =
        WalletDeps(
            api,
            env,
            WalletGates(scope) { WithdrawVisualFixtures.now },
            tokens,
            null,
            null,
            null,
            null,
            clock = { WithdrawVisualFixtures.now },
            newKey = { "visual-created-${++keys}" },
            service = scope,
        )
    var keys = 0
        private set

    fun launch(
        payout: Boolean = false,
        dark: Boolean = false,
        fontScale: Float = 1f,
        shell: Boolean = false,
        token: Boolean = true,
    ) {
        payoutPage = payout
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        if (token) {
            tokens.put(
                if (payout) "payout_account_change" else "withdraw",
                env.user,
                "fixture-existing-token",
                WithdrawVisualFixtures.now + 600_000,
                env.epoch,
                env.sessionScope(),
            )
        }
        val config = WalletPageConfig(deps, onBack = {}, hooks = PageHooks(bindLeave = { leave = it }))
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalCouliAnnouncer provides CouliAnnouncer { spoken += it },
            ) {
                CouliTheme(darkTheme = dark) {
                    colors.putAll(
                        com.couli.core.designsystem
                            .couliColorTokens(),
                    )
                    Column(Modifier.fillMaxSize().background(CouliColors.backgroundCanvas)) {
                        reference()
                        if (shell) Spacer(Modifier.height(54.dp))
                        if (payout) {
                            payoutModel = viewModel { PayoutAccountViewModel(config) }
                            PayoutAccountScreen(config)
                        } else {
                            model = viewModel { WithdrawViewModel(config, saved) }
                            WithdrawScreen(config)
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    fun input(value: String = "10") {
        rule.onNodeWithTag(WalletTags.WITHDRAW_INPUT).performScrollTo().performTextReplacement(value)
        rule.waitForIdle()
    }

    fun settleEstimate() {
        rule.runOnUiThread { ShadowLooper.idleMainLooper(650, TimeUnit.MILLISECONDS) }
        rule.mainClock.advanceTimeBy(650)
        rule.waitForIdle()
    }

    override fun close() {
        rule.runOnIdle {
            leave()
            scope.cancel()
        }
        TimeZone.setDefault(zone)
    }
}
