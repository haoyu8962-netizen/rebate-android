package com.couli.core.orders

import android.content.Context
import android.content.SharedPreferences
import com.couli.core.config.CouliPreferenceNamespaces
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 金额隐藏开关（BR-TEXT-10 细则「金额隐藏」，G21）：本机偏好，存本机、不上传，换账号沿用、卸载即清（不在撤回与切换环境的清理范围）。
 * 订单列表与详情的眼睛按钮切换；APP-09 的「我的」、钱包复用同一份。隐藏后预估返、实返、推广收益、扣回显示「****」，实付与已付定金不隐藏；
 * 接口与计算不变。
 */
class AmountMaskStore(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(CouliPreferenceNamespaces.AMOUNT_MASK, Context.MODE_PRIVATE),
    )

    private val mutable = MutableStateFlow(runCatching { prefs.getBoolean(KEY_HIDDEN, false) }.getOrDefault(false))
    val hidden: StateFlow<Boolean> = mutable.asStateFlow()

    fun toggle() {
        val next = !mutable.value
        mutable.value = next
        prefs.edit().putBoolean(KEY_HIDDEN, next).apply()
    }

    private companion object {
        const val KEY_HIDDEN = "hidden"
    }
}
