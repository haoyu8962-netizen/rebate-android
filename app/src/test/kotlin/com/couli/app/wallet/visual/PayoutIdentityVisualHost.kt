package com.couli.app.wallet.visual

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.couli.core.designsystem.CouliAnnouncer
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalCouliAnnouncer
import com.couli.core.designsystem.couliColorTokens
import com.couli.core.network.ApiResult
import com.couli.core.wallet.StepUpTokenCache
import com.couli.core.wallet.WalletGates
import com.couli.feature.wallet.LaborAgreementScreen
import com.couli.feature.wallet.LaborAgreementViewModel
import com.couli.feature.wallet.LaborBackend
import com.couli.feature.wallet.PageHooks
import com.couli.feature.wallet.PayoutAccountScreen
import com.couli.feature.wallet.PayoutAccountViewModel
import com.couli.feature.wallet.RealNameBackend
import com.couli.feature.wallet.RealNameScreen
import com.couli.feature.wallet.RealNameViewModel
import com.couli.feature.wallet.WalletDeps
import com.couli.feature.wallet.WalletPageConfig
import com.couli.feature.wallet.WalletTags
import java.util.TimeZone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

internal enum class PayoutIdentityPage { PAYOUT, REALNAME, LABOR }

/** 实名提交 / 劳务读取与签署的替身：只记账、可挂起；不替控制器造状态（G-W24：仍是 Debug 手写接口）。 */
internal class IdentityVisualBackends :
    RealNameBackend,
    LaborBackend {
    val submits = mutableListOf<Triple<String, String, String>>()
    val signs = mutableListOf<Pair<String, String>>()
    var reads = 0
        private set
    var submitAnswer: ApiResult = PayoutIdentityVisualFixtures.ok()
    var submitHold: CompletableDeferred<Unit>? = null
    var currentAnswer: ApiResult = PayoutIdentityVisualFixtures.ok(PayoutIdentityVisualFixtures.labor())
    var readHold: CompletableDeferred<Unit>? = null
    var signAnswer: ApiResult = PayoutIdentityVisualFixtures.ok()
    var signHold: CompletableDeferred<Unit>? = null

    override suspend fun submit(
        name: String,
        idNo: String,
        idempotencyKey: String,
    ): ApiResult {
        submits += Triple(name, idNo, idempotencyKey)
        submitHold?.await()
        return submitAnswer
    }

    override suspend fun current(): ApiResult {
        reads += 1
        readHold?.await()
        return currentAnswer
    }

    override suspend fun sign(
        version: String,
        textSha256: String,
    ): ApiResult {
        signs += version to textSha256
        signHold?.await()
        return signAnswer
    }
}

/** 直组生产页面：真实 Screen → ViewModel → Controller → SensitiveFlow；替身只在 WalletApi / Env / 两个手写后端。 */
internal class PayoutIdentityVisualHost(
    private val rule: ComposeContentTestRule,
) : AutoCloseable {
    private val zone = TimeZone.getDefault()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val api = WithdrawVisualApi()
    val env = WithdrawVisualEnv()
    val tokens = StepUpTokenCache()
    val backends = IdentityVisualBackends()
    val spoken = mutableListOf<String>()
    val colors = mutableMapOf<String, Color>()
    var backs = 0
        private set
    var keys = 0
        private set

    /** 截图前置采样块（参考图标、遮罩白卡）；默认空，不增加节点或高度。 */
    var reference: @Composable () -> Unit = {}
    var release = false
    private var leave: () -> Unit = {}

    lateinit var deps: WalletDeps
        private set
    lateinit var payoutModel: PayoutAccountViewModel
        private set
    lateinit var realnameModel: RealNameViewModel
        private set
    lateinit var laborModel: LaborAgreementViewModel
        private set

    fun launch(
        page: PayoutIdentityPage,
        dark: Boolean = false,
        fontScale: Float = 1f,
        shell: Boolean = false,
    ) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        if (page == PayoutIdentityPage.PAYOUT) {
            tokens.put(
                "payout_account_change",
                env.user,
                "fixture-existing-token",
                PayoutIdentityVisualFixtures.now + 600_000,
                env.epoch,
                env.sessionScope(),
            )
        }
        deps =
            WalletDeps(
                api,
                env,
                WalletGates(scope) { PayoutIdentityVisualFixtures.now },
                tokens,
                null,
                null,
                backends.takeUnless { release },
                backends.takeUnless { release },
                clock = { PayoutIdentityVisualFixtures.now },
                newKey = { "visual-e-created-${++keys}" },
                service = scope,
            )
        val config = WalletPageConfig(deps, onBack = { backs += 1 }, hooks = PageHooks(bindLeave = { leave = it }))
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalCouliAnnouncer provides CouliAnnouncer { spoken += it },
            ) {
                CouliTheme(darkTheme = dark) {
                    colors.putAll(couliColorTokens())
                    Column(Modifier.fillMaxSize().background(CouliColors.backgroundCanvas)) {
                        reference()
                        if (shell) Spacer(Modifier.height(54.dp))
                        // 与页面内 viewModel {} 同键，取到的是页面实际使用的那一个；只读状态，不替控制器造状态
                        when (page) {
                            PayoutIdentityPage.PAYOUT -> {
                                payoutModel = viewModel { PayoutAccountViewModel(config) }
                                PayoutAccountScreen(config)
                            }

                            PayoutIdentityPage.REALNAME -> {
                                realnameModel = viewModel { RealNameViewModel(config) }
                                RealNameScreen(config)
                            }

                            PayoutIdentityPage.LABOR -> {
                                laborModel = viewModel { LaborAgreementViewModel(config) }
                                LaborAgreementScreen(config)
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    fun type(
        tag: String,
        text: String,
    ) {
        rule.onNodeWithTag(tag).performScrollTo().performTextReplacement(text)
        rule.waitForIdle()
    }

    fun fillRealName() {
        type(WalletTags.REALNAME_NAME, PayoutIdentityVisualFixtures.realnameForm("name"))
        type(WalletTags.REALNAME_ID, PayoutIdentityVisualFixtures.realnameForm("idNo"))
    }

    fun color(name: String): Color = colors.getValue(name)

    override fun close() {
        rule.runOnIdle {
            leave()
            scope.cancel()
        }
        TimeZone.setDefault(zone)
    }
}
