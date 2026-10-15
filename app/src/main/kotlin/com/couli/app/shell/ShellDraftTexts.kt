package com.couli.app.shell

/**
 * 外壳草稿文字：契约 texts.default.json 里还没有键的几处（needs.md N-16），照设计稿逐字放在这一处。
 * 有键的（privacy.btn.*、privacy.basic_mode.*）一律读 DefaultTexts，不放这里。
 *
 * TODO(N-16) 待法务定稿，不得提审：本文件每一条都是设计稿上的草稿文字（LaunchPrivacy、LaunchSecondNotice、BasicMode、HomeGuest），
 * 契约补 privacy.first_launch.* 等键后整文件删除，改读 DefaultTexts；提审前（QA-07）必须替换。
 */
internal object ShellDraftTexts {
    // TODO(N-16) 待法务定稿，不得提审：首启弹窗标题（LaunchPrivacy）
    const val LAUNCH_TITLE = "个人信息保护提示"

    // TODO(N-16) 待法务定稿，不得提审：首启弹窗摘要（LaunchPrivacy）
    const val LAUNCH_INTRO = "欢迎使用凑狸。使用前请阅读以下说明："

    // TODO(N-16) 待法务定稿，不得提审：首启弹窗摘要要点（LaunchPrivacy）
    val LAUNCH_POINTS: List<String> =
        listOf(
            "同意后，我们会收集设备信息、手机号等，用于登录、查找商品、记录订单和发放返利；不同用途收集哪些信息，见《个人信息收集清单》。",
            "App 接入了推送、联盟、崩溃统计、微信等第三方 SDK，它们只在你同意后才会启动，详见《第三方 SDK 共享清单》。",
            "你可以随时在「设置 → 隐私中心」查看清单、撤回同意，或注销账号。",
        )

    // TODO(N-16) 待法务定稿，不得提审：二次说明标题（LaunchSecondNotice）
    const val SECOND_TITLE = "温馨提示"

    // TODO(N-16) 待法务定稿，不得提审：二次说明正文（LaunchSecondNotice）
    const val SECOND_BODY = "不同意的话，只能用基本模式浏览首页、搜索和商品详情，不能登录，也拿不到返利。之后随时可以点【开启完整功能】重新选择。"

    // TODO(N-16) 待法务定稿，不得提审：协议链接（书名号与名称照设计稿；契约只有 app_update.privacy_policy「隐私政策」不带书名号）
    const val LINK_PRIVACY_POLICY = "《隐私政策》"

    // TODO(N-16) 待法务定稿，不得提审
    const val LINK_USER_AGREEMENT = "《用户协议》"

    // TODO(N-16) 待法务定稿，不得提审
    const val LINK_COLLECT_LIST = "《个人信息收集清单》"

    // TODO(N-16) 待法务定稿，不得提审
    const val LINK_SDK_LIST = "《第三方 SDK 共享清单》"

    // TODO(N-16) 待法务定稿，不得提审：底部 Tab 名称（HomeGuest / BasicMode）
    const val TAB_HOME = "首页"

    // TODO(N-16) 待法务定稿，不得提审
    const val TAB_AGENT = "AI 助手"

    // TODO(N-38) 代拟：agent.entry.visible=false 时底部 AI 助手 Tab 换成「搜索」（BR-AI-12，APP-10b G32）
    const val TAB_SEARCH = "搜索"

    // TODO(N-16) 待法务定稿，不得提审
    const val TAB_ORDERS = "订单"

    // TODO(N-16) 待法务定稿，不得提审
    const val TAB_ME = "我的"

    // TODO(N-16) 待法务定稿，不得提审：基本模式页搜索框占位（BasicMode）
    const val BASIC_SEARCH_PLACEHOLDER = "搜索商品"

    // TODO(N-16) 待法务定稿，不得提审：基本模式页【设置】图标按钮的读屏名称（BasicMode aria-label）
    const val SETTINGS_LABEL = "设置"
}
