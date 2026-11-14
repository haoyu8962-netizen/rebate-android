package com.couli.app

import android.content.Context
import com.couli.feature.wallet.LaborBackend
import com.couli.feature.wallet.RealNameBackend
import java.io.File

/**
 * debug / staging：实名（只有 debug 接线采用，staging 走真实实现，见 chooseRealNameBackend）与劳务协议（契约没有接口，G02）的进程内假实现，与假钱包服务共用状态；统一闸门
 * （开关 + MOCK_DATA + 环境就绪，F-X-02）关着时一律按断网回答。
 */
internal fun variantRealNameBackend(context: Context): RealNameBackend? = fakeWallet(context).realnameBackend

internal fun variantLaborBackend(context: Context): LaborBackend? = fakeWallet(context).laborBackend

/**
 * debug / staging：二次验证发码槽位的键（B1-02zzz 起 purpose=step_up 不带 phone，服务端发往账号绑定的手机号，这里的值不上送、不显示）。
 * 假服务接管时给样例号码 sample-data §12 的 138****5678 对应的测试号（与既有冒烟、单测同一把槽位键）；没接管（连真实环境）时与 Release 相同，
 * 有账号就给固定的 [BOUND_PHONE_SLOT]。没有账号为 null（不发码）。
 */
internal fun variantStepUpPhone(context: Context): (String) -> String? {
    val gate = DebugMockGate.of(context)
    // 每次取值时过统一闸门（F-X-02）：假服务接管才用测试号作槽位键
    return { account ->
        when {
            account.isEmpty() -> null
            gate() -> TEST_PHONE
            else -> BOUND_PHONE_SLOT
        }
    }
}

/** 进程内唯一的假钱包服务；实名、劳务两个端口每次调用都过统一闸门（F-X-02：旧实例切到 prod 后按断网）。 */
private fun fakeWallet(context: Context): DebugFakeWallet =
    DebugFakeWallet.of(File(DebugMockGate.stateDir(context), FAKE_WALLET_STATE_FILE)).also { it.switchOn = DebugMockGate.of(context) }

private const val TEST_PHONE = "13800005678"

/** 发码槽位里代表「账号当前绑定的手机号」的占位（不含数字，不会被当成号码）。 */
private const val BOUND_PHONE_SLOT = "bound"
