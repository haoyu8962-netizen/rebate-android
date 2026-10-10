package com.couli.core.clientcore

import android.content.Context
import android.content.SharedPreferences
import com.couli.core.config.CouliPreferenceNamespaces
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 金额隐藏开关（BR-TEXT-10 细则「金额隐藏」；APP-09 G24 从 core:orders 挪到共用 Core，存储名与键不变、不做迁移）：本机偏好，存本机、不上传，
 * 换账号沿用、卸载即清（不在撤回与切换环境的清理范围，SharedPreferences [CouliPreferenceNamespaces.AMOUNT_MASK] 仍在
 * preservedAcrossEnvironmentSwitch）。订单列表与详情、钱包（APP-09）的眼睛按钮切换同一份；「我的」页随 APP-12 接入。隐藏后各页按自己的规则
 * 显示「****」；接口与计算不变。
 *
 * 同一份偏好可能有多个实例（App 只建一个；测试与旧接线各建一个）：监听 SharedPreferences 的变化，任一实例切换后其余实例的 [hidden] 同步更新。
 */
class AmountMaskStore(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(CouliPreferenceNamespaces.AMOUNT_MASK, Context.MODE_PRIVATE),
    )

    private val mutable = MutableStateFlow(read())
    val hidden: StateFlow<Boolean> = mutable.asStateFlow()

    /** 强引用持有（SharedPreferences 只弱引用监听器）。 */
    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_HIDDEN) mutable.value = read()
        }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun toggle() {
        val next = !mutable.value
        mutable.value = next
        prefs.edit().putBoolean(KEY_HIDDEN, next).apply()
    }

    private fun read(): Boolean = runCatching { prefs.getBoolean(KEY_HIDDEN, false) }.getOrDefault(false)

    private companion object {
        const val KEY_HIDDEN = "hidden"
    }
}
