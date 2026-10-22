package com.couli.app.wallet

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import com.couli.core.clientcore.AmountMaskStore
import com.couli.core.designsystem.CouliAnnouncer
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalCouliAnnouncer
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingTarget
import com.couli.core.wallet.StepUpTokenCache
import com.couli.core.wallet.WalletGates
import com.couli.core.wallet.WalletJson
import com.couli.core.wallet.WalletStaleCache
import com.couli.feature.wallet.PageHooks
import com.couli.feature.wallet.WalletApi
import com.couli.feature.wallet.WalletController
import com.couli.feature.wallet.WalletDeps
import com.couli.feature.wallet.WalletEnv
import com.couli.feature.wallet.WalletPageConfig
import com.couli.feature.wallet.WalletScreen
import com.couli.feature.wallet.WalletViewModel
import java.nio.file.Files
import java.util.TimeZone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 真实 Screen / ViewModel / Controller / 解析器 / 字典；仅网络和导航出口替身。 */
internal class WalletVisualTestHost(
    private val rule: ComposeContentTestRule,
) : AutoCloseable {
    private val oldZone = TimeZone.getDefault()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val directory = Files.createTempDirectory("wallet-visual-").toFile()
    private val context: Context = ApplicationProvider.getApplicationContext()
    val api = WalletVisualApi()
    val env = WalletVisualEnv()
    val spoken = mutableListOf<String>()
    val cache = WalletStaleCache.Store(directory.resolve("summary"))
    lateinit var mask: AmountMaskStore
    lateinit var controller: WalletController
    var warning = Color.Unspecified
    var warningBackground = Color.Unspecified
    var rebate = Color.Unspecified
    var solid = Color.Unspecified
    var surface = Color.Unspecified
    var primary = Color.Unspecified
    var muted = Color.Unspecified
    private var leave: () -> Unit = {}

    fun launch(
        name: String = "normal",
        dark: Boolean = false,
        fontScale: Float = 1f,
        hidden: Boolean = false,
        statusBar: Boolean = false,
    ) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        context
            .getSharedPreferences("couli_amount_mask", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        mask = AmountMaskStore(context)
        if (hidden) mask.toggle()
        if (api.answer == null) api.answer = WalletVisualFixtures.ok(WalletVisualFixtures.summary(name))
        val deps =
            WalletDeps(
                api,
                env,
                WalletGates(scope) { WalletVisualFixtures.now },
                StepUpTokenCache(),
                cache,
                mask,
                null,
                null,
                clock = { WalletVisualFixtures.now },
                service = scope,
            )
        val config = WalletPageConfig(deps, onBack = {}, hooks = PageHooks(bindLeave = { leave = it }))
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalCouliAnnouncer provides CouliAnnouncer { spoken += it },
            ) {
                CouliTheme(darkTheme = dark) {
                    warning = CouliColors.statusWarningText
                    warningBackground = CouliColors.statusWarningBackground
                    rebate = CouliColors.rebateText
                    solid = CouliColors.rebateSolidText
                    surface = CouliColors.backgroundSurface
                    primary = CouliColors.textPrimary
                    muted = CouliColors.backgroundMuted
                    val model: WalletViewModel = viewModel { WalletViewModel(config) }
                    controller = model.controller
                    Column(Modifier.fillMaxSize().background(CouliColors.backgroundCanvas)) {
                        if (statusBar) Spacer(Modifier.height(54.dp))
                        WalletScreen(config)
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    fun seedCache(name: String = "normal") {
        cache.write(env.account(), checkNotNull(WalletJson.summary(WalletVisualFixtures.summary(name))), WalletVisualFixtures.cachedAt)
    }

    fun refresh(result: ApiResult) {
        api.answer = result
        rule.runOnIdle { controller.refresh() }
        rule.waitForIdle()
    }

    override fun close() {
        rule.runOnIdle {
            leave()
            scope.cancel()
        }
        directory.deleteRecursively()
        TimeZone.setDefault(oldZone)
    }
}

internal class WalletVisualApi : WalletApi {
    var answer: ApiResult? = null
    var hold: CompletableDeferred<Unit>? = null
    val calls = mutableListOf<ApiCall>()
    val pendingWrites = mutableListOf<String>()

    override suspend fun send(call: ApiCall): ApiResult {
        calls += call
        hold?.await()
        return checkNotNull(answer)
    }

    override fun pendingFor(operationId: String): PendingKeyRecord? = null

    override fun restorePending() = true

    override fun cancelOwned(owner: CallOwnership) = Unit

    override fun epochs() = CallEpochs(0, 0)

    override suspend fun confirmPending(
        operationId: String,
        stepUpToken: String?,
        target: PendingTarget,
    ): ApiResult {
        pendingWrites += "confirm:$operationId"
        return WalletVisualFixtures.failure()
    }

    override suspend fun abandonPending(
        operationId: String,
        target: PendingTarget,
    ): ApiResult {
        pendingWrites += "abandon:$operationId"
        return WalletVisualFixtures.failure()
    }
}

internal class WalletVisualEnv : WalletEnv {
    val opened = mutableListOf<Pair<String, JsonObject>>()
    var earnings = false
    var since: String? = null

    override fun loggedIn() = true

    override fun sessionEpoch() = 1L

    override fun account() = "wallet-visual-user"

    override fun appVersion() = "1.0.0"

    override fun earningsSince() = since

    override fun configData(): JsonObject =
        Json
            .parseToJsonElement(
                """{"features":{"flags":{"earnings.dashboard.visible":$earnings}}}""",
            ).jsonObject

    override fun open(
        route: String,
        params: JsonObject,
    ) {
        opened += route to params
    }

    override fun replaceTop(
        route: String,
        params: JsonObject,
        entryId: String?,
    ) {
        error("钱包不可替换栈顶")
    }

    override fun copy(text: String) = false

    override fun forceUpdate() = Unit

    override fun stepUpPhone(account: String): String? = null
}
