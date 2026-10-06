package com.couli.core.designsystem

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay

/** 读屏播报出口（adaptation-a11y-spec §10）。默认走 View 的无障碍播报；测试经 [LocalCouliAnnouncer] 替换。 */
fun interface CouliAnnouncer {
    fun announce(text: String)
}

/** 注入播报出口（测试用；null 时用当前 View）。 */
val LocalCouliAnnouncer = staticCompositionLocalOf<CouliAnnouncer?> { null }

@Composable
internal fun rememberCouliAnnouncer(): CouliAnnouncer {
    val injected = LocalCouliAnnouncer.current
    val view = LocalView.current
    return remember(injected, view) { injected ?: ViewAnnouncer(view) }
}

private class ViewAnnouncer(
    private val view: View,
) : CouliAnnouncer {
    // API 36 起不推荐主动播报，但轻提示「同文再提示」与错误态都需要立即读出（与 iOS UIAccessibility.post 对齐）。
    @Suppress("DEPRECATION")
    override fun announce(text: String) {
        if (text.isNotEmpty()) view.announceForAccessibility(text)
    }
}

/**
 * 弹层的读屏焦点规则（adaptation-a11y-spec §4、§8；F-AND-05，与 iOS ModalFocus 同义）：打开时焦点进面板标题；
 * 关闭后（按钮、遮罩、返回键都一样）回到打开它的触发控件。纯逻辑单独拿出来便于单测。
 */
enum class ModalFocus {
    FOCUS_PANEL_TITLE,
    RESTORE_TRIGGER,
    NONE,
    ;

    companion object {
        /** 打开后等面板挂上无障碍树再移焦点。 */
        const val TITLE_FOCUS_DELAY_MILLIS = 150L

        /** 关闭动画结束、底层恢复可达后再还焦点。 */
        const val RESTORE_DELAY_MILLIS = 300L

        fun transition(
            wasPresented: Boolean,
            isPresented: Boolean,
        ): ModalFocus =
            when {
                !wasPresented && isPresented -> FOCUS_PANEL_TITLE
                wasPresented && !isPresented -> RESTORE_TRIGGER
                else -> NONE
            }
    }
}

/**
 * 可被程序移入焦点的目标：挂 [requester]，并允许在触摸模式下获得焦点（按钮默认只在键盘模式可聚焦）。
 * 弹层的触发控件挂上它，再把同一个 [FocusRequester] 传给 [CouliDialog] / [CouliSheet] 的 restoreFocusTo。
 */
fun Modifier.couliFocusTarget(requester: FocusRequester): Modifier = focusRequester(requester).focusProperties { canFocus = true }

/** 出现后（[key] 变化也算）等 [delayMillis] 把焦点移到 [requester]。 */
@Composable
internal fun FocusOnAppear(
    requester: FocusRequester,
    key: Any? = Unit,
    delayMillis: Long = ModalFocus.TITLE_FOCUS_DELAY_MILLIS,
) {
    LaunchedEffect(requester, key) {
        delay(delayMillis)
        requester.requestFocusSafely()
    }
}

/** 离开组合（弹层关闭）后把焦点还给 [target]。 */
@Composable
internal fun RestoreFocusOnDispose(target: FocusRequester?) {
    val view = LocalView.current
    DisposableEffect(target, view) {
        onDispose {
            if (target != null) view.postDelayed({ target.requestFocusSafely() }, ModalFocus.RESTORE_DELAY_MILLIS)
        }
    }
}

/** 目标已不在界面上（调用方先关了页面）时 requestFocus 会抛 IllegalStateException：此时什么都不做。 */
internal fun FocusRequester.requestFocusSafely(): Boolean =
    try {
        requestFocus()
    } catch (
        @Suppress("SwallowedException") detached: IllegalStateException,
    ) {
        false
    }

/** 错误态的播报与移焦（F-AND-06；与 iOS ErrorStateFocusCoordinator 同义）：先播报整句，读完后把焦点移到【重试】。 */
object ErrorStateFocus {
    /** 「加载失败，网络不太好，请重试」；没有说明时只有标题。 */
    fun announcement(
        title: String,
        message: String?,
    ): String = if (message.isNullOrEmpty()) title else title + PriceTerms.SPOKEN_PAUSE + message

    /**
     * 播报后等多久移焦：Android 没有播报结束回调，按字数估读完的时间（每字 [MILLIS_PER_CHAR]，加起读 [BASE_MILLIS]），
     * 不超过 [MAX_MILLIS]（与 iOS 兜底 5 秒一致），免得移焦打断播报。
     */
    fun focusDelayMillis(announcement: String): Long = minOf(MAX_MILLIS, BASE_MILLIS + MILLIS_PER_CHAR * announcement.length)

    const val BASE_MILLIS = 600L
    const val MILLIS_PER_CHAR = 150L
    const val MAX_MILLIS = 5_000L
}
