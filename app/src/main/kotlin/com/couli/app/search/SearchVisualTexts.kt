package com.couli.app.search

import com.couli.core.router.PrivacyState
import com.couli.feature.search.SearchTexts

/**
 * 搜索页文案按隐私模式取（D10-617 Q11，BR-TEXT-14 表 C）：输入框占位用 `search.placeholder`；从基本模式进入时改用
 * `basic.search.placeholder`（基本模式不能识别链接，不提「粘贴链接」）。其余键原样走 [SearchDraftTexts]（先契约、后草稿），不新增键。
 */
internal fun searchTexts(privacy: PrivacyState): SearchTexts =
    if (privacy == PrivacyState.BASIC_MODE) BasicModeSearchTexts else SearchDraftTexts

/** 基本模式的搜索页文案：只把搜索框占位换成基本模式的键，可见文字与读屏名同源。 */
private object BasicModeSearchTexts : SearchTexts by SearchDraftTexts {
    private const val PLACEHOLDER = "search.placeholder"
    private const val BASIC_PLACEHOLDER = "basic.search.placeholder"

    override fun text(key: String): String = SearchDraftTexts.text(if (key == PLACEHOLDER) BASIC_PLACEHOLDER else key)
}
