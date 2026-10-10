package com.couli.app

import android.content.Context
import com.couli.feature.wallet.LaborBackend
import com.couli.feature.wallet.RealNameBackend
import java.io.File

/**
 * debug / staging：实名与劳务协议的进程内假实现（契约没有接口，G01 / G02），与假钱包服务共用状态；假登录服务的开关文件不存在时一律按断网回答。
 */
internal fun variantRealNameBackend(context: Context): RealNameBackend? = fakeWallet(context).realnameBackend

internal fun variantLaborBackend(context: Context): LaborBackend? = fakeWallet(context).laborBackend

/**
 * debug / staging：二次验证发码用的测试号码（假登录服务接管时，按会话给样例号码 sample-data §12 的 138****5678 对应的测试号）。Release 取不到号码，
 * 不发码（Android 空白 A01）。
 */
internal fun variantStepUpPhone(context: Context): (String) -> String? =
    { account -> if (account.isNotEmpty() && fakeLoginServerSwitch(context).exists()) TEST_PHONE else null }

private fun fakeWallet(context: Context): DebugFakeWallet {
    val switch = fakeLoginServerSwitch(context)
    return DebugFakeWallet.of(File(switch.parentFile, FAKE_WALLET_STATE_FILE)).also { it.switchOn = { switch.exists() } }
}

private const val TEST_PHONE = "13800005678"
