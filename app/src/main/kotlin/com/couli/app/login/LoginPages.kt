package com.couli.app.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.app.routing.AppRouting
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialog
import com.couli.core.designsystem.CouliIconButton
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliToast
import com.couli.core.designsystem.CouliTypography
import com.couli.core.router.SessionState
import com.couli.feature.login.BindPhoneGuideDialog
import com.couli.feature.login.BindPhoneScreen
import com.couli.feature.login.LoginScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 设置页标记（UI 测试、冒烟用）。 */
const val SETTINGS_PAGE_TAG = "settings_page"
const val SETTINGS_LOGOUT_TAG = "settings_logout"
const val SETTINGS_LOGOUT_CONFIRM_TAG = "settings_logout_confirm"

/** Login 路由：真实登录页（APP-05a）。[restricted]：从强更页进入的受限登录（APP-02c 已有标记）。 */
@Composable
fun LoginRoute(
    login: AppLogin,
    restricted: Boolean,
) {
    val config = remember(restricted) { login.pageConfig(restricted) }
    LoginScreen(config)
}

/** BindPhone 路由：绑手机页（APP-05b；BindPhone / BindPhoneMerge）。 */
@Composable
fun BindPhoneRoute(login: AppLogin) {
    val config = remember { login.bindPageConfig() }
    BindPhoneScreen(config)
}

/**
 * 设置页（Settings 占位，APP-05a 只放【退出登录】；其余设置项在设置任务实现）：已登录时显示【退出登录】→ 确认框（SettingsLogoutConfirm）
 * → 登出，回到游客并关闭设置页。
 */
@Composable
fun SettingsRoute(
    login: AppLogin,
    routing: AppRouting,
    onBack: () -> Unit,
) {
    val texts = LoginDraftTexts
    val session by routing.session.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(CouliColors.backgroundCanvas)
                .safeDrawingPadding()
                .testTag(SETTINGS_PAGE_TAG),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = CouliSpacing.space1)) {
            CouliIconButton(icon = CouliIcons.back, label = texts.text("login.back"), onClick = onBack)
            Text(
                text = texts.text("login.settings"),
                style = CouliTypography.title,
                color = CouliColors.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (session != SessionState.GUEST) {
            CouliButton(
                text = texts.text("settings.logout"),
                onClick = { confirming = true },
                variant = CouliButtonVariant.SECONDARY,
                loading = busy,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(CouliSpacing.space4)
                        .testTag(SETTINGS_LOGOUT_TAG),
            )
        }
    }
    if (confirming) {
        CouliDialog(
            title = texts.text("settings.logout.confirm"),
            confirmText = texts.text("settings.logout.confirm_ok"),
            onConfirm = {
                confirming = false
                busy = true
                scope.launch {
                    val done = login.logout()
                    busy = false
                    if (done) onBack()
                }
            },
            onDismissRequest = { confirming = false },
            dismissText = texts.text("settings.logout.confirm_cancel"),
        )
    }
}

/**
 * 登录成功后的邀请绑定结果（LoginInviteBound 轻提示 / LoginInviteFailed 弹窗；BR-INV-06 细则）：bound → 轻提示；failed → 弹窗，【去补填】
 * 打开「我的」（补填页另有任务）；ignored_existing_user 不提示。不改变登录后的路由计划（AFTER-03）。显示在外壳上（登录页已关闭）。
 */
@Composable
fun LoginNoticeHost(
    login: AppLogin,
    onOpenMe: () -> Unit,
) {
    val notice by login.notice.collectAsStateWithLifecycle()
    val texts = LoginDraftTexts
    val current = notice
    when {
        current == LoginNotice.InviteBound || current is LoginNotice.Message -> {
            LaunchedEffect(current) {
                delay(TOAST_MILLIS)
                login.consumeNotice()
            }
            val message = (current as? LoginNotice.Message)?.text ?: texts.text("login.invite_bound")
            Box(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                CouliToast(
                    message = message,
                    icon = if (current is LoginNotice.Message) null else CouliIcons.check,
                    modifier = Modifier.padding(top = CouliSpacing.space12),
                )
            }
        }

        // 第三方新号绑手机引导（LoginBindPhoneGuide）：【暂不】、遮罩、返回 → 只恢复待办；【去绑定】→ BindPhone
        current is LoginNotice.BindPhoneGuide -> {
            BindPhoneGuideDialog(texts = texts, onSkip = login::guideSkipped, onGo = login::guideGo)
        }

        current is LoginNotice.InviteFailed -> {
            CouliDialog(
                title = texts.text("login.invite_failed.title"),
                message = texts.text(if (current.rateLimited) "login.invite_failed.rate_limited" else "login.invite_failed.body"),
                confirmText = texts.text("login.invite_failed.backfill"),
                onConfirm = {
                    login.consumeNotice()
                    onOpenMe()
                },
                onDismissRequest = login::consumeNotice,
                dismissText = texts.text("login.invite_failed.ok"),
            )
        }
    }
}

private const val TOAST_MILLIS = 2_000L
