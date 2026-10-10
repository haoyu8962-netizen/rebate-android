// 执行器的每个入口逐项核对身份、版本、禁用期与结果类别（APP-09 第 52、63、65 条）
@file:Suppress("CyclomaticComplexMethod", "ComplexCondition", "TooManyFunctions")

package com.couli.feature.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialog
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.me.MeLevel
import com.couli.core.me.MePlatform
import com.couli.core.me.Personalization
import com.couli.core.me.PrivacyCenterModel
import com.couli.core.me.PrivacyCenterView
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.wallet.WalletError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 隐私中心的页内状态。 */
data class PrivacyState(
    /** 撤回确认框的一次性编号（R1-02）；null = 没有显示。 */
    val confirm: Long? = null,
    val toast: MeNotice? = null,
    val version: Long = 0,
)

/**
 * 隐私中心执行器（PrivacyCenter 路由；brief §2.4–2.6，§0 第 1、2 条；G03、G21、G24–G26；BR-ID-02 / 11 / 13）。构造时固定身份；隐私 / 环境 / 会话归属变了页面按新身份
 * **重建**本执行器，旧执行器与旧确认框作废（鸿蒙 R3-02，共性第 18 条），旧回调被拒、新操作按新身份执行：
 * - 两份清单、协议、系统权限一节（只查状态、点击跳系统设置，不申请、不改节流）；基本模式只读 + 原位【开启完整功能】；
 * - 个性化开关（G03）：本机先保存（用户意愿优先，不回滚），再记 recordConsent（channel=privacy_center）；失败提示一次；设备未注册只记本地；
 * - 【撤回同意】先出确认框（【取消】主、【确认撤回】次；点遮罩不执行、系统返回 = 取消），确认后交给应用级协调器（[MeEnv.withdrawConsent]），本地撤回一定执行。
 * 强更受限上下文（[forceUpdate]）只给协议、两份清单与【撤回同意】，执行层拒绝个性化修改（鸿蒙 R2-05，03 §4.1）。
 */
class PrivacyCenterController(
    private val deps: MeDeps,
    val identity: MeIdentity,
) {
    private var left = false
    private var ids = 0L
    private var dialogIds = 0L
    private val mutable = MutableStateFlow(PrivacyState())
    val state: StateFlow<PrivacyState> = mutable.asStateFlow()

    /** 打开时固定的强更受限上下文。 */
    val forceUpdate: Boolean = deps.env.forceUpdateContext()

    fun alive(): Boolean = !left && identity.current(deps.env)

    fun model(): PrivacyCenterModel {
        val entries = deps.env.permissionEntries()
        val status = entries.mapNotNull { (it["type"] as? JsonPrimitive)?.content }.associateWith(deps.env::permissionStatus)
        return PrivacyCenterView.model(identity.level, MePlatform.ANDROID, entries, status, forceUpdate)
    }

    /** 两份清单与协议（H03：收集清单 → Agreement privacy，共享清单 → sdk_list；包内副本优先的现有实现照用）。 */
    fun openDoc(type: String) {
        if (alive()) deps.env.open(AGREEMENT, JsonObject(mapOf("type" to JsonPrimitive(type))))
    }

    fun openPermission() {
        if (alive() && !forceUpdate) deps.env.openAppSettings()
    }

    fun enableFull() {
        if (alive() && identity.level == MeLevel.BASIC && !forceUpdate) deps.env.requestFullFunction()
    }

    /** 个性化开关（渲染时的执行器）：执行层拒绝基本模式、强更上下文与旧身份。 */
    @Suppress("ReturnCount")
    fun setPersonalization(on: Boolean) {
        val local = deps.local ?: return
        if (!alive() || identity.level == MeLevel.BASIC || forceUpdate || deps.env.forceUpdateContext()) return
        if (deps.env.withdrawing.value) return
        val now = deps.clock()
        local.setPersonalization(on, now)
        val version = deps.env.legalPrivacyVersion() ?: return
        val body = Personalization.consentBody(on, version, now, identity.level) ?: return
        // 设备未注册（游客、设备注册还在退避）：只记本地，注册成功后不补报（G03、G24）
        if (!deps.env.deviceRegistered()) return
        val gate = deps.gates.consent
        if (gate.active) return
        val probe = gate.probe()
        val owner = CallOwnership(deps.api.epochs(), sendAllowed = probe::allowed) { identity.current(deps.env) }
        deps.service.launch {
            val result =
                deps.api.send(ApiCall(RECORD_CONSENT, body = body.toString(), ownership = owner, expectedAccount = identity.account))
            if (result is ApiResult.Success) return@launch
            val error = MeErrors.of(result)
            if (error.kind == WalletError.RETRY_AFTER) gate.start(error.disableMillis)
            if (error.kind == WalletError.FORCE_UPDATE) deps.env.forceUpdate()
            // 切换失败：本机仍按用户选择保存，提示一次通用失败（不回滚开关，brief §2.5）
            if (alive() && !(error.kind == WalletError.CANCELLED && !probe.rejected)) {
                mutable.value = mutable.value.copy(toast = MeNotice(FAILED_KEY, ++ids))
            }
        }
    }

    /** 【撤回同意】：先出确认框（撤回进行中不重复）。 */
    fun askWithdraw() {
        if (alive() && model().withdrawButton && !deps.env.withdrawing.value) {
            mutable.value = mutable.value.copy(confirm = ++dialogIds, version = mutable.value.version + 1)
        }
    }

    /** 【取消】、系统返回：不撤回（同一编号才生效，R1-02）。 */
    fun cancelWithdraw(confirm: Long) {
        if (mutable.value.confirm == confirm) mutable.value = mutable.value.copy(confirm = null)
    }

    /**
     * 【确认撤回】（确认框生成时的编号）：只认正显示的这一个确认框，用过即作废（R1-02）；启动前再核对身份与页面（鸿蒙 R1-07）；已合法启动的撤回由应用级
     * 协调器继续完成清理。
     */
    fun confirmWithdraw(confirm: Long) {
        val current = mutable.value
        if (current.confirm != confirm) return
        mutable.value = current.copy(confirm = null)
        if (!alive() || deps.env.withdrawing.value) return
        deps.env.withdrawConsent(identity)
    }

    fun consumeToast(id: Long) {
        if (mutable.value.toast?.id == id) mutable.value = mutable.value.copy(toast = null)
    }

    fun leave() {
        left = true
        mutable.value = PrivacyState()
    }

    companion object {
        const val AGREEMENT = "Agreement"
        const val RECORD_CONSENT = "recordConsent"
        const val FAILED_KEY = "privacy_center.personalization_failed"
        const val TYPE_PRIVACY = "privacy"
        const val TYPE_AGREEMENT = "agreement"
        const val TYPE_SDK_LIST = "sdk_list"
    }
}

class PrivacyCenterConfig(
    val deps: MeDeps,
    val onBack: () -> Unit,
    val bindLeave: (() -> Unit) -> Unit = {},
)

/**
 * 隐私中心（设计稿 PrivacyCenter、PrivacyCenterBasic、PrivacyCenterWithdraw；AC-S1-77、AC-S1-135）：清单与协议卡片 → 个性化推荐开关 → 「系统权限」一节（标题、提示与本端
 * 条目；Android 清单为空，G26）→ 撤回同意说明 +【撤回同意】；基本模式原位显示「开启完整功能 / 同意隐私政策后…」+【开启完整功能】。
 */
@Suppress("LongMethod")
@Composable
fun PrivacyCenterScreen(config: PrivacyCenterConfig) {
    val deps = config.deps
    val identity by deps.env.identityFlow.collectAsStateWithLifecycle()
    // 执行器保存在回退栈条目的 ViewModel（R1-08）：配置重建不关确认框；出栈、换身份才作废
    val controller = rememberPageController("PrivacyCenter", identity, { PrivacyCenterController(deps, identity) }) { it.leave() }
    DisposableEffect(controller) {
        config.bindLeave(controller::leave)
        onDispose { }
    }
    val state by controller.state.collectAsStateWithLifecycle()
    val withdrawing by deps.env.withdrawing.collectAsStateWithLifecycle()
    val noLocal = remember { MutableStateFlow(true) }
    val on by (deps.local?.personalizationOn ?: noLocal).collectAsStateWithLifecycle()
    var resumes by remember { mutableLongStateOf(0L) }
    OnResumed(controller) { resumes += 1 }
    val model = remember(controller, resumes) { controller.model() }
    val texts = deps.texts
    MeSubPage(
        tag = MeTags.PRIVACY_PAGE,
        title = texts.text("privacy_center.title"),
        texts = texts,
        onBack = config.onBack,
        overlay = { MeToastHost(state.toast?.message(texts), state.toast?.id) { state.toast?.id?.let(controller::consumeToast) } },
    ) {
        if (identity.level == MeLevel.BASIC && !controller.forceUpdate) {
            NoteStrip(text = texts.text("privacy.basic_mode.notice"))
        }
        RowCard(
            listOf(
                {
                    NavRow(texts.text("privacy_center.collect_list"), MeTags.PRIVACY_LIST_PREFIX + "collect", onClick = {
                        controller.openDoc(PrivacyCenterController.TYPE_PRIVACY)
                    })
                },
                {
                    NavRow(texts.text("privacy_center.share_list"), MeTags.PRIVACY_LIST_PREFIX + "share", onClick = {
                        controller.openDoc(PrivacyCenterController.TYPE_SDK_LIST)
                    })
                },
                {
                    NavRow(texts.text("privacy_center.privacy_policy"), MeTags.PRIVACY_POLICY, onClick = {
                        controller.openDoc(PrivacyCenterController.TYPE_PRIVACY)
                    })
                },
                {
                    NavRow(texts.text("privacy_center.agreement"), MeTags.PRIVACY_AGREEMENT, onClick = {
                        controller.openDoc(PrivacyCenterController.TYPE_AGREEMENT)
                    })
                },
            ),
        )
        if (model.personalizationSwitch) {
            RowCard(
                listOf {
                    SwitchRow(
                        title = texts.text("privacy_center.personalization"),
                        desc = texts.text("privacy_center.personalization_desc"),
                        checked = on,
                        texts = texts,
                        tag = MeTags.PRIVACY_SWITCH,
                        enabled = !withdrawing,
                        onChange = controller::setPersonalization,
                    )
                },
            )
        }
        if (model.permissionSection) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = CouliSpacing.space2).testTag(MeTags.PRIVACY_PERMISSIONS),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
            ) {
                Text(
                    text = texts.text("privacy.system_permissions.title"),
                    style = CouliTextStyles.footnoteSemibold,
                    color = CouliColors.textSecondary,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = texts.text("privacy.system_permissions.hint"),
                    style = CouliTypography.caption,
                    color = CouliColors.textSecondary,
                )
            }
            if (model.permissionRows.isNotEmpty()) {
                RowCard(
                    model.permissionRows.map { row ->
                        {
                            NavRow(
                                title = row.purposeKey?.let(texts::text) ?: row.type,
                                tag = MeTags.PRIVACY_PERMISSION_PREFIX + row.type,
                                value = texts.text(row.statusKey),
                                onClick = controller::openPermission,
                            )
                        }
                    },
                )
            }
        }
        if (model.enableFullButton) {
            Text(
                text = texts.text("privacy_center.basic_title"),
                style = CouliTypography.title,
                color = CouliColors.textPrimary,
                modifier = Modifier.padding(top = CouliSpacing.space3).semantics { heading() },
            )
            Text(text = texts.text("privacy_center.basic_desc"), style = CouliTypography.body, color = CouliColors.textSecondary)
            CouliButton(
                text = texts.text("privacy.basic_mode.enable"),
                onClick = controller::enableFull,
                modifier = Modifier.fillMaxWidth().testTag(MeTags.PRIVACY_ENABLE_FULL),
            )
        }
        if (model.withdrawButton) {
            Text(
                text = texts.text("privacy_center.withdraw_title"),
                style = CouliTypography.title,
                color = CouliColors.textPrimary,
                modifier = Modifier.padding(top = CouliSpacing.space3).semantics { heading() },
            )
            Text(text = texts.text("privacy_center.withdraw_desc"), style = CouliTypography.body, color = CouliColors.textSecondary)
            CouliButton(
                text = texts.text("privacy_center.withdraw"),
                onClick = controller::askWithdraw,
                variant = CouliButtonVariant.SECONDARY,
                enabled = !withdrawing,
                loading = withdrawing,
                modifier = Modifier.fillMaxWidth().testTag(MeTags.PRIVACY_WITHDRAW),
            )
        }
    }
    val confirm = state.confirm
    if (confirm != null) {
        // 确认框生成时捕获执行器与一次性编号（共性第 7、20 条）；【取消】主按钮、【确认撤回】次按钮（BR-TEXT-14 表 D）；点遮罩不执行、系统返回 = 取消
        CouliDialog(
            title = texts.text("privacy.withdraw.confirm.title"),
            message = texts.text("privacy.withdraw.confirm.body"),
            confirmText = texts.text("privacy.withdraw.confirm.cancel"),
            onConfirm = { controller.cancelWithdraw(confirm) },
            dismissText = texts.text("privacy.withdraw.confirm.ok"),
            onDismiss = { controller.confirmWithdraw(confirm) },
            onDismissRequest = { controller.cancelWithdraw(confirm) },
            dismissOnClickOutside = false,
            dismissTag = MeTags.WITHDRAW_DIALOG,
        )
    }
}
