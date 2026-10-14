package com.couli.app

import android.content.Context
import java.io.File

/**
 * debug / staging：撤回同意、切换环境时同步清掉假账号服务按账号保存的状态（注销进度、绑定号码、已受理的键、step-up token；APP-10 第 11 条）。
 * Release 为空实现。
 */
internal fun variantResetFakeAccount(context: Context) {
    DebugFakeAccount.of(File(DebugMockGate.stateDir(context), FAKE_ACCOUNT_STATE_FILE)).reset()
}
