package com.couli.feature.login

/*
 * 协议整句与确认框的文案键（APP-17a；BR-ID-04、BR-TEXT-14 表 C；鸿蒙 HM-13a C01，G10、G16）。只是数据：页面按它渲染（视觉闸后再换 DS-02 组件），
 * 本模块不写中文（BR-TEXT-12）。共享向量 specs/login-vectors.json 的 agreementConfirm 段（LoginAgreement17aTest）。
 */

/** 整句里的一个链接区间：[type] 协议类型（[LoginAgreementType] 的取值），[start, end) 是 UTF-16 下标。 */
data class AgreementSpan(
    val type: String,
    val start: Int,
    val end: Int,
)

/**
 * 协议整句（G16）：[text] 是字典里的整句原文（不拆前后段），[spans] 是按「《…》」原文定位到的链接区间，[accessibilityText] 是读屏名（整句）。
 */
data class AgreementSentence(
    val text: String,
    val spans: List<AgreementSpan>,
    val accessibilityText: String,
) {
    companion object {
        /**
         * [links]：协议类型 → 链接原文（如 agreement → 《用户协议》）。按 Map 的声明顺序在整句里找第一次出现的位置，与已占用的区间重叠的
         * 不要（先声明的先占位），找不到的丢掉；最后按起点排序。区间都是 UTF-16 下标（与 Kotlin String 下标一致，含 emoji 代理对）。
         */
        fun of(
            text: String,
            links: Map<String, String>,
        ): AgreementSentence {
            val spans = mutableListOf<AgreementSpan>()
            links.forEach { (type, needle) ->
                if (needle.isEmpty()) return@forEach
                val start = text.indexOf(needle)
                if (start < 0) return@forEach
                val span = AgreementSpan(type, start, start + needle.length)
                if (spans.none { it.start < span.end && span.start < it.end }) spans += span
            }
            return AgreementSentence(text, spans.sortedBy { it.start }, text)
        }
    }
}

/** 需要先同意协议的动作（[AgreementConfirmKeys.of] 的入参；三端同名）。继续输入验证码与获取验证码同一个动作。 */
object LoginAgreementAction {
    const val GET_CODE = "get_code"
    const val OAUTH = "oauth"
    const val LOGIN = "login"
    const val ONE_TAP = "one_tap"
}

/**
 * 协议确认框（LoginAgreeConfirm）的四个文案键，按动作取（G10）：获取验证码 → 主按钮 btn_get_code；第三方、一键与其余登录 → btn_login；
 * 第三方正文 body_oauth，其余 body；次按钮一律 btn_disagree（只关弹窗、留在本页）。
 */
data class AgreementConfirmKeys(
    val titleKey: String,
    val bodyKey: String,
    val primaryKey: String,
    val secondaryKey: String,
) {
    companion object {
        private const val PREFIX = "login.agree_confirm."

        fun of(action: String): AgreementConfirmKeys =
            AgreementConfirmKeys(
                titleKey = PREFIX + "title",
                bodyKey = PREFIX + if (action == LoginAgreementAction.OAUTH) "body_oauth" else "body",
                primaryKey = PREFIX + if (action == LoginAgreementAction.GET_CODE) "btn_get_code" else "btn_login",
                secondaryKey = PREFIX + "btn_disagree",
            )
    }
}
