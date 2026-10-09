package com.couli.app.clipboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.app.search.SearchDraftTexts
import com.couli.core.clipboard.ClipboardClassifier
import com.couli.core.clipboard.ClipboardPromptBar
import com.couli.core.clipboard.HomePasteCard
import com.couli.core.clipboard.ManualPasteAction
import com.couli.core.clipboard.ParseResultAction
import com.couli.core.clipboard.ParseRow
import com.couli.core.clipboard.PasteEntry
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIconButton
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSheet
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliToast
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.ProductCardList
import com.couli.core.designsystem.ProductCardMapper
import kotlinx.coroutines.delay

/** 剪贴板界面的测试标记（App 层）。 */
object ClipboardScreens {
    const val HOME_AREA = "clipboard_home_area"
    const val HOME_PASTE_BUTTON = "clipboard_home_paste_button"
    const val UNRECOGNIZED = "clipboard_unrecognized"
    const val SEARCH_BY_NAME = "clipboard_search_by_name"
    const val NOTICE = "clipboard_notice"
    const val CARD_LIST = "clipboard_card_list"
    const val CARD_PREFIX = "clipboard_card_"

    /** 手动粘贴到只有邀请内容时的邀请确认条（游客，A-R3-01）。 */
    const val INVITE_CONFIRM = "clipboard_invite_confirm"

    /** 多卡弹层里行内 30132 的【用商品名搜索】（后接行号）。 */
    const val ROW_SEARCH_BY_NAME_PREFIX = "clipboard_row_search_by_name_"
    const val SETTINGS_ROW = "clipboard_settings_row"
}

/**
 * 首页根页的剪贴板区（HomeClipboard / HomeClipboardInvite / Main）：搜索框下面依次是提示条（有时）、Android 开关关闭时的常驻粘贴按钮、
 * 「粘贴链接，先查返利 · 去粘贴」卡。42901 限流中按钮与卡置灰。
 */
@Composable
fun HomeClipboardArea(clipboard: AppClipboard) {
    val prompt by clipboard.prompt.collectAsStateWithLifecycle()
    val blocked by clipboard.blocked.collectAsStateWithLifecycle()
    val autoSwitch by clipboard.autoSwitch.collectAsStateWithLifecycle()
    val texts = ClipboardDraftTexts
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = CouliSpacing.space4, end = CouliSpacing.space4, top = CouliSpacing.space2)
                .testTag(ClipboardScreens.HOME_AREA),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        prompt?.let { current ->
            val invite = current.kind == ClipboardClassifier.KIND_INVITE
            ClipboardPromptBar(
                message =
                    if (invite) {
                        texts.format(INVITE_PROMPT_KEY, mapOf("invite_code" to current.inviteCode.orEmpty()))
                    } else {
                        texts.text(PROMPT_KEY)
                    },
                actionText = texts.text(if (invite) "clipboard.invite_prompt.action" else "clipboard.prompt.action"),
                actionLabel = texts.text(if (invite) "clipboard.invite_prompt.action_label" else "clipboard.prompt.action_label"),
                closeLabel = texts.text("clipboard.prompt.close"),
                onAction = clipboard::onPromptAction,
                onClose = clipboard::onPromptClose,
                enabled = !blocked,
                busy = current.busy,
            )
        }
        UnrecognizedInline(clipboard, origin = AppClipboard::isHomeOrigin)
        InviteConfirmInline(clipboard, origin = AppClipboard::isHomeOrigin, enabled = !blocked)
        if (!autoSwitch) {
            PasteEntry(
                text = texts.text("clipboard.paste_entry"),
                onClick = { clipboard.paste(ManualPasteAction.ENTRY_HOME) },
                enabled = !blocked,
                modifier = Modifier.testTag(ClipboardScreens.HOME_PASTE_BUTTON),
            )
        }
        HomePasteCard(
            title = texts.text("clipboard.home_card.title"),
            subtitle = texts.text("clipboard.home_card.subtitle"),
            actionText = texts.text("clipboard.home_card.action"),
            onClick = { clipboard.paste(ManualPasteAction.ENTRY_HOME) },
            enabled = !blocked,
        )
    }
}

/** SearchTyping「粘贴查返利」（自绘按钮 + 说明文字）；42901 禁用期置灰（执行层另在 [AppClipboard.paste] 拦）。结果绑定当前页（发起页）。 */
@Composable
fun SearchTypingPasteEntry(clipboard: AppClipboard) {
    val blocked by clipboard.blocked.collectAsStateWithLifecycle()
    PasteEntry(
        text = ClipboardDraftTexts.text("clipboard.paste_entry"),
        hint = ClipboardDraftTexts.text("clipboard.paste_entry.hint"),
        onClick = { clipboard.paste(ManualPasteAction.ENTRY_SEARCH_TYPING) },
        enabled = !blocked,
    )
}

/**
 * SearchTyping 的粘贴结果（30132 提示 +【用商品名搜索】）：挂在搜索栏下面、不随搜索阶段隐藏（A-R2-02、AC-S1-08）；只显示本页（[entryId]）
 * 发起的结果。【用商品名搜索】填进本页搜索框并搜索（[fill]），登记不依赖粘贴入口是否在显示。
 */
@Composable
fun SearchTypingPasteResult(
    clipboard: AppClipboard,
    entryId: String?,
    fill: (String) -> Unit,
) {
    LaunchedEffect(entryId, fill) { clipboard.bindSearchFill(entryId, fill) }
    val blocked by clipboard.blocked.collectAsStateWithLifecycle()
    val inset = Modifier.padding(start = CouliSpacing.space4, end = CouliSpacing.space4, top = CouliSpacing.space2)
    UnrecognizedInline(clipboard, origin = { it == entryId }, modifier = inset)
    InviteConfirmInline(clipboard, origin = { it == entryId }, enabled = !blocked, modifier = inset)
}

/**
 * 手动粘贴到只有邀请内容（游客，A-R3-01）：在发起页内联出与首页邀请提示条同一样式的确认条（文案同 clipboard.invite_prompt）；点确认预填
 * 登录页，关闭即放弃。只在发起页仍是当前页时显示。
 */
@Composable
fun InviteConfirmInline(
    clipboard: AppClipboard,
    origin: (String) -> Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val result by clipboard.result.collectAsStateWithLifecycle()
    val current = result as? ClipboardResult.InviteConfirm ?: return
    if (!origin(current.origin)) return
    val texts = ClipboardDraftTexts
    Box(modifier = modifier.fillMaxWidth().testTag(ClipboardScreens.INVITE_CONFIRM)) {
        ClipboardPromptBar(
            message = texts.format(INVITE_PROMPT_KEY, mapOf("invite_code" to current.code)),
            actionText = texts.text("clipboard.invite_prompt.action"),
            actionLabel = texts.text("clipboard.invite_prompt.action_label"),
            closeLabel = texts.text("clipboard.prompt.close"),
            onAction = { clipboard.confirmManualInvite(current) },
            onClose = { clipboard.consumeResult(current) },
            enabled = enabled,
        )
    }
}

/**
 * parse / 手动粘贴的结果（显示在外壳上，只在发起页仍是当前页时出现）：轻提示（2 秒）、30132 提示（error.30132 +【用商品名搜索】，
 * ShareHubInvalid 的提示样式）、多卡弹层（按搜索卡样式；行内 error_code 的那条置灰并给该码文案）。
 */
@Composable
fun ClipboardResultHost(clipboard: AppClipboard) {
    val result by clipboard.result.collectAsStateWithLifecycle()
    when (val current = result) {
        is ClipboardResult.Notice -> {
            LaunchedEffect(current) {
                delay(NOTICE_MILLIS)
                clipboard.consumeResult(current)
            }
            Box(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                CouliToast(
                    message = current.text,
                    icon = null,
                    announcementId = current.serial,
                    modifier = Modifier.padding(top = CouliSpacing.space12).testTag(ClipboardScreens.NOTICE),
                )
            }
        }

        // 30132 的提示与手动粘贴的邀请确认条在发起页内联显示（首页剪贴板区、SearchTyping 的粘贴入口下面），见 [UnrecognizedInline]、
        // [InviteConfirmInline]
        is ClipboardResult.Unrecognized, is ClipboardResult.InviteConfirm -> {
            Unit
        }

        is ClipboardResult.CardList -> {
            CardListSheet(current, clipboard)
        }

        null -> {
            Unit
        }
    }
}

/**
 * 30132 的提示（ShareHubInvalid 的提示样式：错误底色圆角 12、内边距 12，图标 + 「这个口令暂时识别不了」+【用商品名搜索】，窄屏时换行）。只在发起页
 * 仍是当前页时显示（[origin] 与结果的发起页相同）。出现时读屏播报一次。
 */
@Composable
fun UnrecognizedInline(
    clipboard: AppClipboard,
    origin: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val result by clipboard.result.collectAsStateWithLifecycle()
    val current = result as? ClipboardResult.Unrecognized ?: return
    if (!origin(current.origin)) return
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(CouliColors.statusErrorBackground, RoundedCornerShape(CouliRadius.button))
                .padding(horizontal = CouliSpacing.space3, vertical = CouliSpacing.space1)
                .testTag(ClipboardScreens.UNRECOGNIZED),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Icon(
            CouliIcons.alert,
            contentDescription = null,
            tint = CouliColors.statusErrorText,
            modifier = Modifier.size(CouliSizes.iconMedium),
        )
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = current.text,
                style = CouliTypography.footnote,
                color = CouliColors.statusErrorText,
                modifier = Modifier.padding(vertical = CouliSpacing.space2).semantics { liveRegion = LiveRegionMode.Assertive },
            )
            Box(
                modifier =
                    Modifier
                        .heightIn(min = CouliSizes.touchTarget)
                        .wrapClick { clipboard.searchByName(current) }
                        .testTag(ClipboardScreens.SEARCH_BY_NAME),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = ClipboardDraftTexts.text("clipboard.search_by_name"),
                    style = CouliTypography.footnote.copy(fontWeight = CouliTextStyles.bodySemibold.fontWeight),
                    color = CouliColors.brandEmphasis,
                )
            }
        }
    }
}

private fun Modifier.wrapClick(onClick: () -> Unit): Modifier = this.clickable(role = Role.Button, onClick = onClick)

@Composable
private fun CardListSheet(
    result: ClipboardResult.CardList,
    clipboard: AppClipboard,
) {
    val texts = SearchDraftTexts
    CouliSheet(
        title = ClipboardDraftTexts.text("clipboard.card_list.title"),
        onDismissRequest = { clipboard.consumeResult(result) },
        closeLabel = ClipboardDraftTexts.text("clipboard.card_list.close"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().testTag(ClipboardScreens.CARD_LIST),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        ) {
            result.rows.forEachIndexed { index, row ->
                val card = row.card
                if (card != null) {
                    val mapped = ProductCardMapper.map(card, texts)
                    val open = { clipboard.openCard(result, card) }
                    Box(modifier = Modifier.fillMaxWidth().wrapClick(open).testTag(ClipboardScreens.CARD_PREFIX + index)) {
                        ProductCardList(data = mapped.data, onBuy = open, buyState = mapped.buyState)
                    }
                } else {
                    ErrorRow(result, row, index, clipboard)
                }
            }
        }
    }
}

/**
 * 多卡弹层里的一条错误行（A-R1-05，逐条按码处理）：置灰底、写该码文案（5xxxx 带 trace6，42901 时禁用期已经启动）；30132 另给
 * 【用商品名搜索】（预填这一行命中的「」内或首行）。
 */
@Composable
private fun ErrorRow(
    result: ClipboardResult.CardList,
    row: ParseRow,
    index: Int,
    clipboard: AppClipboard,
) {
    val code = row.errorCode ?: ParseResultAction.UNSUPPORTED
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CouliColors.backgroundMuted, RoundedCornerShape(CouliRadius.card))
                .padding(CouliSpacing.space4)
                .testTag(ClipboardScreens.CARD_PREFIX + index),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        Text(
            text =
                listOfNotNull(
                    row.hitRaw?.takeIf { code != ParseResultAction.UNRECOGNIZED },
                    clipboard.rowText(result, row),
                ).joinToString("\n"),
            style = CouliTypography.footnote,
            color = CouliColors.textSecondary,
        )
        if (code == ParseResultAction.UNRECOGNIZED) {
            Box(
                modifier =
                    Modifier
                        .heightIn(min = CouliSizes.touchTarget)
                        .wrapClick { clipboard.searchByName(result, row) }
                        .testTag(ClipboardScreens.ROW_SEARCH_BY_NAME_PREFIX + index),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = ClipboardDraftTexts.text("clipboard.search_by_name"),
                    style = CouliTypography.footnote.copy(fontWeight = CouliTextStyles.bodySemibold.fontWeight),
                    color = CouliColors.brandEmphasis,
                )
            }
        }
    }
}

/**
 * 设置页「打开 App 自动识别剪贴板」一行（Settings / SettingsGuest；只 Android）：标题 17、副标题 12，右侧开关；读屏为开关角色，名称为标题、
 * 说明为副标题。设备级、默认开启；已同意隐私政策的游客也显示，基本模式与撤回同意后不显示（由调用方按隐私结论决定是否组合本行）。
 * TODO(APP-09)：设置整页（分组、其余设置项）在设置任务里实现，这里只把这一行挂到现有设置页上。
 */
@Composable
fun ClipboardSettingsRow(clipboard: AppClipboard) {
    val enabled by clipboard.autoSwitch.collectAsStateWithLifecycle()
    val title = ClipboardDraftTexts.text("settings.clipboard_auto")
    val desc = ClipboardDraftTexts.text("settings.clipboard_auto.desc")
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = CouliSizes.touchTarget)
                .toggleable(value = enabled, role = Role.Switch, onValueChange = clipboard::setAutoSwitch)
                .semantics(mergeDescendants = true) {
                    toggleableState = if (enabled) ToggleableState.On else ToggleableState.Off
                    stateDescription = desc
                }.padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space2)
                .testTag(ClipboardScreens.SETTINGS_ROW),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space0)) {
            Text(text = title, style = CouliTypography.body, color = CouliColors.textPrimary)
            Text(text = desc, style = CouliTypography.caption, color = CouliColors.textSecondary)
        }
        Switch(
            checked = enabled,
            onCheckedChange = null,
            colors =
                SwitchDefaults.colors(
                    checkedTrackColor = CouliColors.brandPrimary,
                    checkedThumbColor = CouliColors.backgroundSurface,
                    uncheckedTrackColor = CouliColors.backgroundMuted,
                    uncheckedThumbColor = CouliColors.backgroundSurface,
                    uncheckedBorderColor = CouliColors.borderControl,
                ),
        )
    }
}

private const val PROMPT_KEY = "clipboard.prompt"
private const val INVITE_PROMPT_KEY = "clipboard.invite_prompt"
private const val NOTICE_MILLIS = 2_000L
