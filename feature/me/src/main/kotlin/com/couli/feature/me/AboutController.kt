package com.couli.feature.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.couliCard
import com.couli.core.me.AboutView
import com.couli.core.me.Diagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 关于（About 路由；brief §2.12，G22、G23；BR-ID-15、F-OBS-06、AC-S1-136）：「关于凑狸」、App 名、版本与构建号；生成式 AI 服务公示只显示 agent.filing_text 原文
 * （空串、未配置整节不显示）；备案号行不显示（TODO(N-33)）；【复制诊断信息】只含 G23 白名单字段，经统一写入口，成功 polite 播报 about.copy_diagnostics.done。
 */
class AboutController(
    private val deps: MeDeps,
) {
    private var left = false
    private var ids = 0L
    private val mutable = MutableStateFlow<MeNotice?>(null)
    val toast: StateFlow<MeNotice?> = mutable.asStateFlow()

    fun filing(): String? {
        val agent = deps.env.configData()?.get("agent") as? JsonObject
        val text = (agent?.get("filing_text") as? JsonPrimitive)?.takeIf { it.isString }?.content
        return AboutView.filing(text)
    }

    /** 诊断信息正文（每行一项；不含任何令牌、完整 device_id、手机号、uid）。 */
    fun diagnosticsText(): String = Diagnostics.lines(deps.env.diagnosticsFields()).joinToString("\n")

    fun copyDiagnostics() {
        if (left) return
        val ok = deps.env.copy(diagnosticsText())
        mutable.value = MeNotice(if (ok) DONE_KEY else FAILED_KEY, ++ids, assertive = !ok)
    }

    fun consumeToast(id: Long) {
        if (mutable.value?.id == id) mutable.value = null
    }

    fun leave() {
        left = true
    }

    companion object {
        const val DONE_KEY = "about.copy_diagnostics.done"
        const val FAILED_KEY = "me.copy_failed"
    }
}

@Composable
fun AboutScreen(
    deps: MeDeps,
    onBack: () -> Unit,
    bindLeave: (() -> Unit) -> Unit = {},
) {
    val controller = remember(deps) { AboutController(deps) }
    DisposableEffect(controller) {
        bindLeave(controller::leave)
        onDispose { controller.leave() }
    }
    val toast by controller.toast.collectAsStateWithLifecycle()
    val texts = deps.texts
    MeSubPage(
        tag = MeTags.ABOUT_PAGE,
        title = texts.text("about.title"),
        texts = texts,
        onBack = onBack,
        overlay = { MeToastHost(toast?.message(texts), toast?.id) { toast?.id?.let(controller::consumeToast) } },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = CouliSpacing.space6),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
            // App 图标：没有图标资源，用品牌色文字块代替（compare 写明）
            Box(
                modifier = Modifier.size(CouliSpacing.space16).background(CouliColors.brandPrimary, RoundedCornerShape(CouliRadius.card)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = texts.text("about.app_name"), style = CouliTextStyles.bodySemibold, color = CouliColors.textInverse)
            }
            Text(
                text = texts.text("about.app_name"),
                style = CouliTypography.title,
                color = CouliColors.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = texts.format("about.version", mapOf("version" to deps.env.appVersion(), "build" to deps.env.appBuild())),
                style = CouliTypography.footnote,
                color = CouliColors.textSecondary,
            )
        }
        controller.filing()?.let { filing ->
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .couliCard()
                        .padding(CouliSpacing.space4)
                        .testTag(MeTags.ABOUT_FILING),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
            ) {
                Text(
                    text = texts.text("about.filing_title"),
                    style = CouliTextStyles.bodySemibold,
                    color = CouliColors.textPrimary,
                    modifier = Modifier.semantics { heading() },
                )
                Text(text = filing, style = CouliTypography.footnote, color = CouliColors.textSecondary)
            }
        }
        CouliButton(
            text = texts.text("about.copy_diagnostics"),
            onClick = controller::copyDiagnostics,
            variant = CouliButtonVariant.SECONDARY,
            modifier = Modifier.fillMaxWidth().testTag(MeTags.ABOUT_COPY),
        )
        Text(text = texts.text("about.copy_desc"), style = CouliTypography.caption, color = CouliColors.textSecondary)
    }
}
