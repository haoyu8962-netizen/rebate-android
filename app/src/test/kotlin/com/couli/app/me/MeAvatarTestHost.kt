package com.couli.app.me

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalProductImageLoader
import com.couli.core.me.MeDraftTexts
import com.couli.core.me.MeLevel
import com.couli.core.me.MeLocalStore
import com.couli.feature.me.MeDeps
import com.couli.feature.me.MeGates
import com.couli.feature.me.MeTabScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 独立宿主，不修改已冻结的 MeVisualTestHost；只注入既有 port，实际渲染 MeTabScreen。 */
internal class MeAvatarTestHost(
    private val rule: ComposeContentTestRule,
) : AutoCloseable {
    private val service = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val env = MeVisualEnv()
    val api = MeVisualApi()
    val images = MeAvatarImages()
    val showReference = mutableStateOf(true)
    lateinit var deps: MeDeps
    var surface = Color.Unspecified
    var muted = Color.Unspecified

    fun launch(
        url: String? = MeAvatarImages.A,
        dark: Boolean = false,
        fontScale: Float = 1f,
        withLoader: Boolean = true,
    ) {
        val context: Context = ApplicationProvider.getApplicationContext()
        val local = MeLocalStore(context).also { it.clearAll() }
        api.answer = MeVisualFixtures.ok(profile(url))
        deps =
            MeDeps(
                api,
                env,
                MeGates(service, clock = { MeVisualFixtures.now }),
                MeDraftTexts,
                local,
                clock = { MeVisualFixtures.now },
                service = service,
            )
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalProductImageLoader provides if (withLoader) images else null,
            ) {
                CouliTheme(darkTheme = dark) {
                    surface = CouliColors.backgroundSurface
                    muted = CouliColors.backgroundMuted
                    Box(Modifier.fillMaxSize().background(CouliColors.backgroundCanvas)) {
                        MeTabScreen(deps)
                        if (showReference.value) {
                            AvatarFallbackReference(Modifier.align(Alignment.BottomEnd))
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    fun changeAccount(url: String = MeAvatarImages.B) {
        rule.runOnIdle {
            api.answer = MeVisualFixtures.ok(profile(url, "avatar-account-b"))
            env.identityFlow.value = env.identity().copy(account = "avatar-account-b", epoch = env.identity().epoch + 1)
        }
        rule.waitForIdle()
    }

    /** 同一账号下拉 / 回前台重读：资料换了头像地址。 */
    fun refresh(url: String) {
        rule.runOnIdle {
            api.answer = MeVisualFixtures.ok(profile(url))
            checkNotNull(deps.tab.bound).refresh()
        }
        rule.waitForIdle()
    }

    fun logout() {
        rule.runOnIdle {
            env.identityFlow.value = env.identity().copy(level = MeLevel.GUEST, account = null, epoch = env.identity().epoch + 1)
        }
        rule.waitForIdle()
    }

    fun finish(
        index: Int,
        color: Int?,
    ) {
        rule.runOnIdle { images.finish(index, color) }
        rule.waitForIdle()
    }

    override fun close() {
        rule.runOnUiThread {
            if (::deps.isInitialized) deps.tab.leave()
            service.cancel()
            images.release()
        }
    }

    private fun profile(
        url: String?,
        account: String = "me-visual-a",
    ) = JsonObject(
        MeVisualFixtures.profile() +
            mapOf(
                "user_id" to JsonPrimitive(account),
                "avatar_url" to (url?.let { JsonPrimitive(it) } ?: JsonNull),
            ),
    )
}

/** 画板规定的内置图标参照，不参与图片加载或产品状态判定；截图前移除。 */
@Composable
private fun AvatarFallbackReference(modifier: Modifier = Modifier) {
    Box(modifier.background(CouliColors.backgroundSurface).testTag(AVATAR_REFERENCE)) {
        Box(
            Modifier.size(56.dp).background(CouliColors.backgroundMuted, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CouliIcons.user, contentDescription = null, tint = CouliColors.textSecondary, modifier = Modifier.size(32.dp))
        }
    }
}

internal const val AVATAR_REFERENCE = "me-avatar-test-reference"
