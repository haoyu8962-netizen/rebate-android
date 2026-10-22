package com.couli.app.me

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.couli.core.clientcore.AmountMaskStore
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalCouliAnnouncer
import com.couli.core.me.MeDraftTexts
import com.couli.core.me.MeLocalStore
import com.couli.core.me.MeTexts
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.feature.me.MeDeps
import com.couli.feature.me.MeGates
import com.couli.feature.me.MeTabScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

internal class MeVisualTestHost(
    private val rule: ComposeContentTestRule,
) : AutoCloseable {
    private val service = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val context: Context = ApplicationProvider.getApplicationContext()
    val env = MeVisualEnv()
    val api = MeVisualApi()
    val announcements = MeVisualAnnouncementProbe(rule)
    val spoken get() = announcements.messages
    val visible = mutableStateOf(true)
    val guideText = mutableStateOf<String?>(null)
    lateinit var deps: MeDeps
    lateinit var mask: AmountMaskStore
    lateinit var lifecycle: Lifecycle
    var muted = Color.Unspecified
    var surface = Color.Unspecified
    var solid = Color.Unspecified
    var primary = Color.Unspecified
    var secondary = Color.Unspecified
    var link = Color.Unspecified
    var success = Color.Unspecified
    val controller get() = checkNotNull(deps.tab.bound)

    fun launch(
        name: String = "me_member",
        dark: Boolean = false,
        fontScale: Float = 1f,
        hidden: Boolean = false,
        statusBar: Boolean = false,
    ) {
        context
            .getSharedPreferences("couli_amount_mask", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        val local = MeLocalStore(context).also { it.clearAll() }
        mask = AmountMaskStore(context)
        if (hidden) mask.toggle()
        if (api.answer == null) api.answer = MeVisualFixtures.ok(MeVisualFixtures.profile(name))
        val texts =
            object : MeTexts by MeDraftTexts {
                override fun text(key: String) =
                    if (key ==
                        "perm.push.guide_bar"
                    ) {
                        guideText.value ?: MeDraftTexts.text(key)
                    } else {
                        MeDraftTexts.text(key)
                    }
            }
        deps =
            MeDeps(
                api,
                env,
                MeGates(service, clock = { MeVisualFixtures.now }),
                texts,
                local,
                clock = { MeVisualFixtures.now },
                service = service,
            )
        rule.setContent {
            val density = LocalDensity.current
            lifecycle = LocalLifecycleOwner.current.lifecycle
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalCouliAnnouncer provides announcements.announcer,
            ) {
                CouliTheme(darkTheme = dark) {
                    muted = CouliColors.backgroundMuted
                    surface = CouliColors.backgroundSurface
                    solid = CouliColors.rebateSolidBackground
                    primary = CouliColors.textPrimary
                    secondary = CouliColors.textSecondary
                    link = CouliColors.textLink
                    success = CouliColors.statusSuccessText
                    Column(Modifier.fillMaxSize().background(CouliColors.backgroundCanvas)) {
                        if (statusBar) Spacer(Modifier.height(54.dp))
                        if (visible.value) MeTabScreen(deps, mask)
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    fun refresh(result: ApiResult) {
        api.answer = result
        rule.runOnIdle { controller.refresh() }
        rule.waitForIdle()
    }

    override fun close() {
        rule.runOnUiThread {
            if (::deps.isInitialized) deps.tab.leave()
            service.cancel()
        }
    }
}

internal class MeVisualApi : PageApi {
    var answer: ApiResult? = null
    var hold: CompletableDeferred<Unit>? = null
    val calls = mutableListOf<ApiCall>()
    val cancelled = mutableListOf<CallOwnership>()

    override suspend fun send(call: ApiCall): ApiResult {
        calls += call
        val result = checkNotNull(answer)
        hold?.await()
        return result
    }

    override fun cancelOwned(owner: CallOwnership) {
        cancelled += owner
    }

    override fun epochs() = CallEpochs(0, 0)
}
