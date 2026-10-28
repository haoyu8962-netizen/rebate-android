package com.couli.app.clipboard

import android.view.View
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.app.search.SearchDraftTexts
import com.couli.core.clipboard.ClipboardClassifier
import com.couli.core.clipboard.ClipboardNotice
import com.couli.core.clipboard.ClipboardPromptBar
import com.couli.core.clipboard.ClipboardPromptInfo
import com.couli.core.clipboard.HomePasteCard
import com.couli.core.clipboard.ManualPasteAction
import com.couli.core.clipboard.ParseResultAction
import com.couli.core.clipboard.ParseRow
import com.couli.core.clipboard.PasteEntry
import com.couli.core.designsystem.BuyButtonState
import com.couli.core.designsystem.CouliAnnouncer
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
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
import com.couli.core.designsystem.DisclaimerKeys
import com.couli.core.designsystem.EndWrapRow
import com.couli.core.designsystem.LocalCouliAnnouncer
import com.couli.core.designsystem.ProductCardList
import com.couli.core.designsystem.ProductCardMapper
import com.couli.core.designsystem.couliCard
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

    /** 网络异常结果的显式重试条（R24）与它的【重试】。 */
    const val RETRY = "clipboard_retry"
    const val RETRY_ACTION = "clipboard_retry_action"

    /** 多卡弹层里行内 30132 的【用商品名搜索】（后接行号）。 */
    const val ROW_SEARCH_BY_NAME_PREFIX = "clipboard_row_search_by_name_"
    const val SETTINGS_ROW = "clipboard_settings_row"
}

/**
 * 首页根页的剪贴板区上半（HomeClipboard / HomeClipboardInvite / Main-v3）：提示条（邀请、系统分享；商品自 FU-AND-0724 起直接出卡，不出提示条）与 30132、
 * 网络异常重试、邀请确认的内联提示。APP-11v（G-V02）起作为首页列表的头部项（不再单独滚动），每块只在显示时带下方 12 间距，没有内容时不占高度。
 * 42901 限流中按钮置灰。
 */
@Composable
@Suppress("CyclomaticComplexMethod") // 剪贴板、邀请、分享三种提示条的文案逐项选择
fun HomeClipboardTop(
    clipboard: AppClipboard,
    modifier: Modifier = Modifier,
) {
    val prompt by clipboard.prompt.collectAsStateWithLifecycle()
    val blocked by clipboard.blocked.collectAsStateWithLifecycle()
    // 共享 busy（R1-05）：商品 parse 在途时提示条按钮与粘贴入口一起置灰
    val busy by clipboard.busy.collectAsStateWithLifecycle()
    val texts = ClipboardDraftTexts
    val gap = Modifier.padding(bottom = CouliSpacing.space3)
    Column(modifier = modifier.fillMaxWidth().testTag(ClipboardScreens.HOME_AREA)) {
        prompt?.let { current ->
            // 每条提示一个独立节点（按编号 key）：换成新提示时旧节点整体丢弃，旧节点上已捕获的点击 / 关闭回调只带旧编号，执行时与当前提示比较后
            // 无效果，不会转调新提示的回调（FU-CLIP 共性第 3 条、APP-09 第 63 条）
            key(current.id) {
                val invite = current.kind == ClipboardClassifier.KIND_INVITE
                val platform = current.display?.let { PromptPlatformTexts.of(it, current.share) }
                val id = current.id
                Box(modifier = gap) {
                    ClipboardPromptBar(
                        message =
                            when {
                                invite -> texts.format(INVITE_PROMPT_KEY, mapOf("invite_code" to current.inviteCode.orEmpty()))
                                platform != null && current.share -> platform.title
                                else -> texts.text(PROMPT_KEY)
                            },
                        actionText =
                            texts.text(
                                when {
                                    invite -> "clipboard.invite_prompt.action"
                                    current.share -> "clipboard.share_prompt.action"
                                    else -> "clipboard.prompt.action"
                                },
                            ),
                        // 分享条按钮读屏名与可见字一致「查返利」（分享不是粘贴）；剪贴板与邀请条的读屏名不变
                        actionLabel =
                            texts.text(
                                when {
                                    invite -> "clipboard.invite_prompt.action_label"
                                    current.share -> "clipboard.share_prompt.action"
                                    else -> "clipboard.prompt.action_label"
                                },
                            ),
                        closeLabel = texts.text("clipboard.prompt.close"),
                        // 回调绑定渲染时这条提示的编号（APP-07 共性第 18 条）
                        onAction = { clipboard.onPromptAction(id) },
                        onClose = { clipboard.onPromptClose(id) },
                        enabled = !blocked && (current.busy || !busy),
                        busy = current.busy,
                        detail = platform?.detail,
                        badge = platform?.badge,
                        messageLabel = platform?.label,
                        keepLastClause = invite,
                    )
                }
            }
        }
        UnrecognizedInline(clipboard, origin = AppClipboard::isHomeOrigin, modifier = gap)
        RetryInline(clipboard, origin = AppClipboard::isHomeOrigin, modifier = gap)
        InviteConfirmInline(clipboard, origin = AppClipboard::isHomeOrigin, enabled = !blocked, modifier = gap)
    }
}

/**
 * 首页常驻粘贴卡（Main-v3，G-V03）：一张卡、一个按钮——卡标题「粘贴查返利」（clipboard.paste_hint），按钮文字「粘贴查返利」（BR-ID-16 Android 自绘，
 * clipboard.paste_entry）。自动识别开关开或关都只有这一个入口；首次点击未看过用途说明先出用途说明（[AppClipboard.paste] 内，行为不变）。
 * 42901 禁用期 / 在途置灰。
 */
@Composable
fun HomeClipboardPaste(
    clipboard: AppClipboard,
    modifier: Modifier = Modifier,
) {
    val blocked by clipboard.blocked.collectAsStateWithLifecycle()
    val busy by clipboard.busy.collectAsStateWithLifecycle()
    HomePasteCard(
        title = ClipboardDraftTexts.text("clipboard.paste_hint"),
        actionText = ClipboardDraftTexts.text("clipboard.paste_entry"),
        onClick = { clipboard.paste(ManualPasteAction.ENTRY_HOME) },
        enabled = !blocked && !busy,
        modifier = modifier.padding(bottom = CouliSpacing.space3),
        buttonModifier = Modifier.testTag(ClipboardScreens.HOME_PASTE_BUTTON),
    )
}

/** SearchTyping「粘贴查返利」（自绘按钮 + 说明文字）；42901 禁用期置灰（执行层另在 [AppClipboard.paste] 拦）。结果绑定当前页（发起页）。 */
@Composable
fun SearchTypingPasteEntry(clipboard: AppClipboard) {
    val blocked by clipboard.blocked.collectAsStateWithLifecycle()
    val busy by clipboard.busy.collectAsStateWithLifecycle()
    PasteEntry(
        text = ClipboardDraftTexts.text("clipboard.paste_entry"),
        hint = ClipboardDraftTexts.text("clipboard.paste_entry.hint"),
        onClick = { clipboard.paste(ManualPasteAction.ENTRY_SEARCH_TYPING) },
        enabled = !blocked && !busy,
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
    RetryInline(clipboard, origin = { it == entryId }, modifier = inset)
    InviteConfirmInline(clipboard, origin = { it == entryId }, enabled = !blocked, modifier = inset)
}

/**
 * 手动粘贴到只有邀请内容（游客，A-R3-01）：在发起页内联出与首页邀请提示条同一样式的确认条（文案同 clipboard.invite_prompt）；点确认记为
 * 待绑定码（APP-17c，不打开登录页），关闭即放弃。只在发起页仍是当前页时显示。
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
    val message = texts.format(INVITE_PROMPT_KEY, mapOf("invite_code" to current.code))
    // 提示条自带礼貌型实时区域（文字变了会读）；连续两次同一邀请码时文字不变，按结果编号补读一次（APP-07 共性第 33 条）
    AnnounceResult(current.id, message, surfaceAnnouncesChanges = true)
    // 共享 busy：读取或 parse 在途时确认按钮同步置灰（FU-CLIP 共性第 4 条）；按结果编号独立节点（共性第 3 条）
    val busy by clipboard.busy.collectAsStateWithLifecycle()
    key(current.id) {
        InviteConfirmBar(clipboard, current, message, enabled && !busy, modifier)
    }
}

@Composable
private fun InviteConfirmBar(
    clipboard: AppClipboard,
    current: ClipboardResult.InviteConfirm,
    message: String,
    enabled: Boolean,
    modifier: Modifier,
) {
    val texts = ClipboardDraftTexts
    Box(modifier = modifier.fillMaxWidth().testTag(ClipboardScreens.INVITE_CONFIRM)) {
        ClipboardPromptBar(
            message = message,
            actionText = texts.text("clipboard.invite_prompt.action"),
            actionLabel = texts.text("clipboard.invite_prompt.action_label"),
            closeLabel = texts.text("clipboard.prompt.close"),
            onAction = { clipboard.confirmManualInvite(current) },
            onClose = { clipboard.consumeResult(current) },
            enabled = enabled,
            keepLastClause = true,
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
        // 网络异常、可显式重试的结果（R24）在发起页内联显示（[RetryInline]），不按 2 秒轻提示自动消失
        is ClipboardResult.Notice -> {
            if (clipboard.canRetry(current)) return
            LaunchedEffect(current) {
                delay(NOTICE_MILLIS)
                clipboard.consumeResult(current)
            }
            Box(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                CouliToast(
                    message = current.text,
                    icon = null,
                    announcementId = current.id,
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
 * 30132 的提示（HomeClipboardResults-v3 ①，APP-11v A18）：白卡（圆角 16、内边距 16），左 20 信息图标 + 「这个口令暂时识别不了」+ 右侧描边次按钮【用商品名搜索】；
 * 放不下时按钮整体换到下一行靠右。只在发起页仍是当前页时显示（[origin] 与结果的发起页相同）。出现时读屏播报一次。
 */
@Composable
fun UnrecognizedInline(
    clipboard: AppClipboard,
    origin: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val result by clipboard.result.collectAsStateWithLifecycle()
    // 42901 共享禁用期：【用商品名搜索】置灰（执行层另在 AppClipboard.searchByName 拦，APP-07 共性第 16 条）
    val blocked by clipboard.blocked.collectAsStateWithLifecycle()
    val current = result as? ClipboardResult.Unrecognized ?: return
    if (!origin(current.origin)) return
    // 每个新结果读一次（按结果编号，APP-07 共性第 33 条）：连续两次 30132 文字相同、视图不重建，实时区域不会再读
    AnnounceResult(current.id, current.text, surfaceAnnouncesChanges = false)
    // 按结果编号独立节点：旧节点的点击回调只带旧结果（FU-CLIP 共性第 3 条）
    key(current.id) {
        val busy by clipboard.busy.collectAsStateWithLifecycle()
        InlineResultCard(
            icon = CouliIcons.info,
            text = current.text,
            actionText = ClipboardDraftTexts.text("clipboard.search_by_name"),
            enabled = !blocked && !busy,
            onAction = { clipboard.searchByName(current) },
            modifier = modifier.testTag(ClipboardScreens.UNRECOGNIZED),
            actionModifier = Modifier.testTag(ClipboardScreens.SEARCH_BY_NAME),
        )
    }
}

/**
 * 网络异常结果的显式重试（R24；HomeClipboardResults-v3 ⑤，与 30132 提示同一卡式）：左 20 警示图标 + 文案 + 右侧描边次按钮【重试】，复用已批准的候选
 * 重发同一个请求，不读此刻的剪贴板。只在发起页仍是当前页时显示；42901 禁用期与 parse 在途时置灰（执行层另在 [AppClipboard.retryResult] 拦）。
 * 出现时读屏播报一次。
 */
@Composable
fun RetryInline(
    clipboard: AppClipboard,
    origin: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val result by clipboard.result.collectAsStateWithLifecycle()
    val blocked by clipboard.blocked.collectAsStateWithLifecycle()
    val busy by clipboard.busy.collectAsStateWithLifecycle()
    val current = result as? ClipboardResult.Notice ?: return
    if (!origin(current.origin) || !clipboard.canRetry(current)) return
    AnnounceResult(current.id, current.text, surfaceAnnouncesChanges = false)
    key(current.id) {
        InlineResultCard(
            icon = CouliIcons.alert,
            text = current.text,
            actionText = ClipboardDraftTexts.text("clipboard.retry"),
            enabled = !blocked && !busy,
            onAction = { clipboard.retryResult(current) },
            modifier = modifier.testTag(ClipboardScreens.RETRY),
            actionModifier = Modifier.testTag(ClipboardScreens.RETRY_ACTION),
        )
    }
}

/** ①⑤ 的卡：白卡 + 左图标（装饰，读屏跳过）+ 14 主文字 + 右描边次按钮（组件库 SECONDARY 紧凑，命中区 48）；放不下时按钮换行靠右。 */
@Composable
private fun InlineResultCard(
    icon: ImageVector,
    text: String,
    actionText: String,
    enabled: Boolean,
    onAction: () -> Unit,
    modifier: Modifier,
    actionModifier: Modifier,
) {
    EndWrapRow(
        modifier =
            modifier
                .fillMaxWidth()
                .couliCard()
                .padding(start = CouliSpacing.space4, end = CouliSpacing.space4, top = CouliSpacing.space3, bottom = CouliSpacing.space3),
        horizontalGap = CouliSpacing.space3,
        verticalGap = CouliSpacing.space2,
        start = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                Icon(icon, contentDescription = null, tint = CouliColors.textPrimary, modifier = Modifier.size(CouliSizes.iconMedium))
                Text(text = text, style = CouliTypography.footnote, color = CouliColors.textPrimary)
            }
        },
        end = {
            CouliButton(
                text = actionText,
                onClick = onAction,
                variant = CouliButtonVariant.SECONDARY,
                size = CouliButtonSize.COMPACT,
                enabled = enabled,
                modifier = actionModifier,
            )
        },
    )
}

/**
 * 按结果编号读屏播报（APP-07 共性第 33 条，iOS R3-02）：[id] 每换一次读出 [text] 一次，普通重组不重复读。
 * [surfaceAnnouncesChanges]：界面自己会读第一次出现或文字变化（弹层打开移焦到标题、礼貌型实时区域），这时只在结果被替换而文字没变时补读。
 */
@Composable
private fun AnnounceResult(
    id: Long,
    text: String,
    surfaceAnnouncesChanges: Boolean,
) {
    val injected = LocalCouliAnnouncer.current
    val view = LocalView.current
    val last = remember { arrayOfNulls<Pair<Long, String>>(1) }
    LaunchedEffect(id) {
        val previous = last[0]
        last[0] = id to text
        val replacedSameText = previous != null && previous.first != id && previous.second == text
        if (text.isNotEmpty() && (!surfaceAnnouncesChanges || replacedSameText)) announce(injected, view, text)
    }
}

// API 36 起不推荐主动播报，但同文再提示需要立即读出（与 designsystem 的 ViewAnnouncer、iOS UIAccessibility.post 对齐）
@Suppress("DEPRECATION")
private fun announce(
    injected: CouliAnnouncer?,
    view: View,
    text: String,
) {
    if (injected != null) injected.announce(text) else view.announceForAccessibility(text)
}

private fun Modifier.wrapClick(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = this.clickable(enabled = enabled, role = Role.Button, onClick = onClick)

@Composable
private fun CardListSheet(
    result: ClipboardResult.CardList,
    clipboard: AppClipboard,
) {
    // 42901 共享禁用期（含本次结果里的行内 42901）：卡片与【用商品名搜索】一并置灰（执行层另在 openCard / searchByName 拦，APP-07 共性第 16 条）
    val rateLimited by clipboard.blocked.collectAsStateWithLifecycle()
    // 共享 busy：读取或 parse 在途时卡片、购买与【用商品名搜索】一并置灰（FU-CLIP 共性第 4 条；执行层另拦）
    val busy by clipboard.busy.collectAsStateWithLifecycle()
    val blocked = rateLimited || busy
    // 平台购买状态变了（配置、50301 覆盖）按当前结果重新映射（APP-07 共性第 32 条）；读它即订阅
    val statusVersion by clipboard.purchaseStatusVersion.collectAsStateWithLifecycle()
    val title = ClipboardDraftTexts.text("clipboard.card_list.title")
    // 弹层打开时焦点进标题（读出）；弹层没关、换成新的一组结果时按结果编号再读一次标题（APP-07 共性第 33 条）
    AnnounceResult(result.id, title, surfaceAnnouncesChanges = true)
    // 卡下口径一句：只看显示了价格的卡（④ 价格不可用的卡不显示金额，不配口径句），按其下发的 disclaimer_keys 选（[DisclaimerKeys.listFooterPriceKey]）
    val basis =
        remember(result) {
            val shownKeys =
                result.rows
                    .mapNotNull { it.card }
                    .map { ProductCardMapper.map(it, SearchDraftTexts).data }
                    .filter { it.price != null && !it.priceUnavailable }
                    .map { it.disclaimerKeys }
            DisclaimerKeys.listFooterPriceKey(shownKeys)?.let(DisclaimerKeys::text)
        }
    // HomeClipboardCard-v3（APP-11v A17）：只有一条结果时不画可见标题（只把手 + 右上关闭），标题仍是读屏 paneTitle；多条按列表（无专稿，保留标题）
    CouliSheet(
        title = title,
        onDismissRequest = { clipboard.consumeResult(result) },
        closeLabel = ClipboardDraftTexts.text("clipboard.card_list.close"),
        showTitle = result.rows.size > 1,
    ) {
        // 弹层内容按结果编号独立节点：换成新的一组结果时旧卡片、旧按钮整体丢弃，旧回调不会转调新结果（FU-CLIP 共性第 3 条）
        key(result.id) {
            CardListRows(result, clipboard, blocked, statusVersion)
        }
        // 卡下口径一句（price_basis / price_basis.general，按各卡下发的 disclaimer_keys 选；没有带价格口径的卡不出）
        basis?.let { Text(text = it, style = CouliTypography.caption, color = CouliColors.textSecondary) }
    }
}

@Composable
private fun CardListRows(
    result: ClipboardResult.CardList,
    clipboard: AppClipboard,
    blocked: Boolean,
    statusVersion: Long,
) {
    val texts = SearchDraftTexts
    Column(
        modifier = Modifier.fillMaxWidth().testTag(ClipboardScreens.CARD_LIST),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        result.rows.forEachIndexed { index, row ->
            val card = row.card
            if (card != null) {
                // 与搜索卡同一映射与同一平台购买状态：not_launched 隐藏返利金额、预估返后与读屏金额，maintenance 禁用购买
                val mapped = statusVersion.let { ProductCardMapper.map(card, texts, purchaseStatus = clipboard.purchaseStatusOf(card)) }
                val open = { clipboard.openCard(result, card) }
                val buyState = if (blocked) BuyButtonState.DISABLED else mapped.buyState
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .wrapClick(enabled = !blocked, onClick = open)
                            .testTag(ClipboardScreens.CARD_PREFIX + index),
                ) {
                    // ④ 价格不可用的「稍后再试」按 HomeClipboardResults-v3 画成卡内右下描边次按钮（仍不可点，行为不变）
                    ProductCardList(
                        data = mapped.data,
                        onBuy = open,
                        buyState = buyState,
                        retryLaterText = mapped.retryLaterText,
                        retryLaterOutlined = true,
                        // 单张（HomeClipboardCard-v3）卡直接排在半屏里、不再套卡框；多张仍按搜索卡外观逐张成卡
                        framed = result.rows.size > 1,
                    )
                }
            } else {
                ErrorRow(result, row, index, clipboard, enabled = !blocked)
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
    enabled: Boolean,
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
                        .wrapClick(enabled = enabled) { clipboard.searchByName(result, row) }
                        .testTag(ClipboardScreens.ROW_SEARCH_BY_NAME_PREFIX + index),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = ClipboardDraftTexts.text("clipboard.search_by_name"),
                    style = CouliTypography.footnote.copy(fontWeight = CouliTextStyles.bodySemibold.fontWeight),
                    color = if (enabled) CouliColors.brandEmphasis else CouliColors.textSecondary,
                )
            }
        }
    }
}

/**
 * 首次用途说明弹层的宿主（R02；AppShell 挂在首启隐私弹窗之外）：[AppClipboard.consentNotice] 非 null 时显示 Core 的 [ClipboardNotice]，文案取草稿键
 * clipboard.consent.*（TODO(N-30) 待定稿，不得提审）。
 */
@Composable
fun ClipboardConsentHost(clipboard: AppClipboard) {
    val notice by clipboard.consentNotice.collectAsStateWithLifecycle()
    if (notice == null) return
    ClipboardNotice(
        text = ClipboardDraftTexts::text,
        onEnable = clipboard::onNoticeEnable,
        onNotNow = clipboard::onNoticeNotNow,
    )
}

/**
 * 设置页「打开 App 自动识别剪贴板」一行（Settings / SettingsGuest；只 Android）：标题 17、副标题 12（纵向 2），上下内边距 8，
 * 右侧开关；读屏为开关角色，读作「名称，副标题，开关，已打开 / 已关闭」——状态由开关角色读出，副标题只作说明、
 * 不再覆盖状态描述（APP-12w-a G-S12）。设备级、默认关闭（打开时没有有效功能同意先出用途弹层，FU-AND-0722）；已同意隐私政策的游客也显示，基本模式与撤回同意后不显示（由调用方按隐私结论决定是否组合本行）。
 * APP-12：这一行原样挂进正式设置页的「通用」组（存储与键不变，基本模式不显示）。
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
                }.padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space2)
                .testTag(ClipboardScreens.SETTINGS_ROW),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1 / 2)) {
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

/**
 * 提示条上的平台文字（FU-CLIP C7；K26、K28 草稿键，TODO(N-34)）：分享条标题 clipboard.share_prompt「收到分享的{平台}商品，查返利？」；第二行
 * clipboard.prompt.detail「{平台}商品链接 {缩略主机}」或 .tpwd「{平台}口令」，多个候选加 .more「等 {count} 个」；读屏整条读 clipboard.share_prompt.label
 * 「检测到{平台}商品链接，查返利」（口令为 label_tpwd）。只用缩略候选，不含路径与查询。
 */
internal class PromptPlatformTexts(
    val title: String,
    val detail: String,
    val badge: String,
    val label: String,
) {
    companion object {
        fun of(
            info: ClipboardPromptInfo,
            share: Boolean,
        ): PromptPlatformTexts {
            val texts = ClipboardDraftTexts
            val vars = mapOf("platform_name" to info.displayName, "display" to info.display.orEmpty(), "count" to info.count.toString())
            val tpwd = info.labelRaw == ClipboardPromptInfo.LABEL_TPWD || info.display == null
            var detail = texts.format(if (tpwd) "clipboard.prompt.detail.tpwd" else "clipboard.prompt.detail", vars)
            if (info.count > 1) detail += texts.format("clipboard.prompt.detail.more", vars)
            val title = if (share) texts.format("clipboard.share_prompt", vars) else texts.text(PROMPT_KEY)
            val label = texts.format(if (tpwd) "clipboard.share_prompt.label_tpwd" else "clipboard.share_prompt.label", vars)
            return PromptPlatformTexts(title, detail, info.displayName, label)
        }
    }
}

private const val INVITE_PROMPT_KEY = "clipboard.invite_prompt"
private const val NOTICE_MILLIS = 2_000L
