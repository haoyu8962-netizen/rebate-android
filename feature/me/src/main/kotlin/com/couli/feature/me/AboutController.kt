package com.couli.feature.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTypography
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
    // 视觉（APP-12w-b P12–P18，设计稿 About-v2 / AboutBeforeFiling / AboutDark）：内容上 8、左右 16、底 16，块间 16；成功轻提示带对勾、失败不带（G-P12）
    MeSubPage(
        tag = MeTags.ABOUT_PAGE,
        title = texts.text("about.title"),
        texts = texts,
        onBack = onBack,
        overlay = {
            MeToastHost(
                toast?.message(texts),
                toast?.id,
                icon = if (toast?.key == AboutController.DONE_KEY) CouliIcons.check else null,
            ) { toast?.id?.let(controller::consumeToast) }
        },
        contentPadding = SETTINGS_CONTENT_PADDING,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space4)) {
            AboutHeader(
                name = texts.text("about.app_name"),
                version = texts.format("about.version", mapOf("version" to deps.env.appVersion(), "build" to deps.env.appBuild())),
            )
            controller.filing()?.let { filing -> AboutFiling(label = texts.text("about.filing_title"), text = filing) }
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                CouliButton(
                    text = texts.text("about.copy_diagnostics"),
                    onClick = controller::copyDiagnostics,
                    variant = CouliButtonVariant.SECONDARY,
                    leadingIcon = CouliIcons.copy,
                    modifier = Modifier.fillMaxWidth().testTag(MeTags.ABOUT_COPY),
                )
                Text(
                    text = texts.text("about.copy_desc"),
                    style = CouliTypography.caption,
                    color = CouliColors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
