package com.couli.app.catalog

/**
 * 组件展示页的目录（DS-02）：按组件清单（couli-runs/design-hifi/components/component-inventory.md）§3 / §4 分组，
 * 每个组件列出变体 × 状态（每行是笛卡尔积）、Android 实现分类（「系统控件」含清单认可的系统交互语义 + 圆形矢量、系统容器 + 自绘内容）
 * 与一句实现说明。只在 debug / staging；样例本体见 [CatalogComponentSample]。
 */
internal data class CatalogEntry(
    val id: String,
    val group: String,
    val kind: String,
    val rows: List<Pair<List<String>, List<String>>>,
    val note: String,
) {
    /** 全部样例；浅 / 深与字号切换的比对样例排在最前（进页即见，不用滚动）。 */
    val cases: List<Pair<String, String>>
        get() =
            rows
                .flatMap { (variants, states) -> variants.flatMap { v -> states.map { v to it } } }
                .sortedBy { (v, s) -> if ("$id/$v/$s" in FEATURED) 0 else 1 }
}

/** 目录页浅 / 深、字号切换时拿来比对的样例（挂 catalog-control 标记的真实组件）。 */
internal val FEATURED = setOf("CouliButton/secondary-standard/default", "PriceTag/coupon-list/long-price", "RebateTag/range/wrap-at-dash")

internal const val KIND_SYSTEM = "系统控件"
internal const val KIND_CUSTOM = "自绘"

/** 官方平台标识未核准时目录里的统一标注（与组件内「待官方素材」一致）。 */
internal const val PENDING_ASSETS_NOTE = "官方平台标识待官方素材：圆内暂不画任何标识，不用通用图标冒充；素材核准入包后自动换上并重录基准。"

private fun e(
    id: String,
    group: String,
    kind: String,
    note: String,
    vararg rows: Pair<String, String>,
) = CatalogEntry(id, group, kind, rows.map { (v, s) -> v.split(',') to s.split(',') }, note)

private const val G_BUTTON = "§3.1 按钮"
private const val G_INPUT = "§3.2 输入"
private const val G_SELECT = "§3.3 选择"
private const val G_NAV = "§3.4 导航"
private const val G_LIST = "§3.5 列表与展示"
private const val G_FEEDBACK = "§3.6 反馈与弹层"
private const val G_LOGIN = "§3.7 登录专用件"
private const val G_BUSINESS = "§4 业务组件"

internal object CatalogRegistry {
    val entries: List<CatalogEntry> =
        listOf(
            e(
                "CouliButton",
                G_BUTTON,
                KIND_SYSTEM,
                "Box + clickable（Role.Button）+ 系统水波纹（M3 ripple），按下另换令牌底色；加载用 M3 转圈。",
                "primary-standard,primary-compact,primary-full,secondary-standard,secondary-compact,secondary-full" to
                    "default,pressed,disabled,loading",
                "text-standard,text-compact,onSolid-compact" to "default,pressed,disabled",
            ),
            e(
                "CouliIconButton",
                G_BUTTON,
                KIND_SYSTEM,
                "命中区 48 + 系统水波纹；切换类读屏带状态。",
                "normal,badge" to "default,pressed,disabled",
                "toggle" to "selected,unselected,pressed,disabled",
            ),
            e(
                "CouliTextField",
                G_INPUT,
                KIND_SYSTEM,
                "BasicTextField + 组件库外框（52 高 T6）；输入中 2 深色描边（T2）。",
                "single,clear,counter" to "empty,focused,filled,error,disabled,readonly",
            ),
            e(
                "CouliTextArea",
                G_INPUT,
                KIND_SYSTEM,
                "BasicTextField 多行，外框同单行。",
                "multiline" to "empty,focused,filled,error,disabled,readonly",
            ),
            e("PhoneField", G_INPUT, KIND_SYSTEM, "电话键盘 + 自动填充语义（电话号码），不预过滤（BR-ID-05）。", "prefix86-clear" to "empty,focused,filled,error"),
            e(
                "CodeInput",
                G_INPUT,
                KIND_SYSTEM,
                "整串 BasicTextField（数字键盘 + 短信验证码自动填充），6 格只负责显示；失败清空格子。",
                "six-cell,single" to
                    "empty,focused,submitting,error-20002,error-20003,error-42901,error-50001,error-network," +
                    "error-44001,error-10007,error-10405,cooldown,resend-ready",
            ),
            e(
                "SearchField",
                G_INPUT,
                KIND_SYSTEM,
                "入口是按钮；输入款 BasicTextField + 搜索键 + 清除。",
                "entry" to "default",
                "input" to "empty,focused,filled",
            ),
            e("PasteButton", G_INPUT, KIND_SYSTEM, "Android 没有系统粘贴控件：次按钮 + 剪贴板。", "secondary" to "default,disabled"),
            e(
                "CouliCheckbox",
                G_SELECT,
                KIND_SYSTEM,
                "系统交互语义 toggleable(Role.Checkbox) + 圆形矢量（直径 18 × 字号，上限 1.5 倍），命中区 48；M3 Checkbox 只有方形，不用。",
                "standalone,row,image22" to "unchecked,checked,pressed,disabled-unchecked,disabled-checked",
            ),
            e("CouliRadio", G_SELECT, KIND_SYSTEM, "M3 RadioButton + 整行 selectable。", "row" to "unselected,selected,disabled"),
            e("CouliSwitch", G_SELECT, KIND_SYSTEM, "M3 Switch 套令牌，整行可点、读屏一项。", "row" to "off,on,disabled-off,disabled-on"),
            e("Segmented", G_SELECT, KIND_SYSTEM, "M3 SingleChoiceSegmentedButtonRow。", "two,three,four" to "selected,unselected,disabled"),
            e(
                "FilterChip",
                G_SELECT,
                KIND_SYSTEM,
                "M3 FilterChip（视觉高 32 = T9，命中区 48），已选加对勾。",
                "filter,dropdown,sort" to "unselected,selected,disabled",
            ),
            e(
                "NavBar",
                G_NAV,
                KIND_SYSTEM,
                "M3 CenterAlignedTopAppBar / TopAppBar；返回为 Material Symbols arrow_back，系统返回走同一回调。",
                "secondary,primary,transparent" to "default,scrolled",
            ),
            e(
                "TopTabs",
                G_NAV,
                KIND_SYSTEM,
                "M3 PrimaryTabRow / PrimaryScrollableTabRow，指示条 2（T10）。",
                "fixed,scrollable" to "selected,unselected",
            ),
            e(
                "TabBar",
                G_NAV,
                KIND_SYSTEM,
                "现为组件库一行 selectable(Role.Tab) + 系统水波纹；换 M3 NavigationBar 属页面骨架改动，随首页定稿另排（清单 §3.4）。",
                "badge" to "selected,unselected",
            ),
            e(
                "ListRow",
                G_LIST,
                KIND_SYSTEM,
                "M3 ListItem，整行系统水波纹；带开关时整行是开关。",
                "arrow,value,switch-subtitle" to "default,pressed,disabled",
            ),
            e("Divider", G_LIST, KIND_SYSTEM, "M3 HorizontalDivider。", "full,inset,text" to "default"),
            e("CouliTag", G_LIST, KIND_CUSTOM, "组件库自绘胶囊；状态带图标，不只靠颜色。", "pending,success,warning,error,benefit,emphasis" to "default"),
            e("CouliBadge", G_LIST, KIND_SYSTEM, "M3 Badge（T4），读屏读文字「有未读」。", "dot,number" to "default"),
            e("Avatar", G_LIST, KIND_CUSTOM, "圆形裁切图片；无图用户图标占位。", "size56,size32" to "image,placeholder"),
            e("ImagePlaceholder", G_LIST, KIND_CUSTOM, "占位底 + 描边，加载中转圈、失败图标。", "list96,grid,horizontal,detail" to "loading,success,error"),
            e("Timeline", G_LIST, KIND_CUSTOM, "组件库自绘圆点 + 连线。", "default" to "completed,current,future"),
            e("DisclosureRow", G_LIST, KIND_SYSTEM, "整行按钮 + 读屏展开 / 收起操作。", "default" to "collapsed,expanded"),
            e("Loading", G_FEEDBACK, KIND_SYSTEM, "M3 CircularProgressIndicator。", "small,medium" to "loading"),
            e("PullToRefresh", G_FEEDBACK, KIND_SYSTEM, "M3 PullToRefreshBox 的指示器。", "default" to "pulling,refreshing"),
            e("Skeleton", G_FEEDBACK, KIND_CUSTOM, "组件库自绘骨架块。", "product,order,wallet" to "loading"),
            e("ProductCardSkeleton", G_FEEDBACK, KIND_CUSTOM, "列表商品卡骨架。", "list" to "loading"),
            e("EmptyState", G_FEEDBACK, KIND_CUSTOM, "组件库自绘空态。", "empty" to "default"),
            e("ErrorState", G_FEEDBACK, KIND_CUSTOM, "组件库自绘错态，出现即播报、焦点到【重试】。", "trace6,offline" to "default"),
            e("NoticeBar", G_FEEDBACK, KIND_CUSTOM, "组件库自绘提示条（原 InfoStrip 的通用版）。", "info,warning,error,success" to "default,action"),
            e("Toast", G_FEEDBACK, KIND_CUSTOM, "组件库自绘轻提示（系统 Toast 不能带图标、只在底部）。", "icon,text" to "default"),
            e(
                "AlertDialog",
                G_FEEDBACK,
                KIND_SYSTEM,
                "M3 BasicAlertDialog 系统窗口 + 确认框排版；样例是内容排版，页顶【打开真实窗口】看系统容器。",
                "normal,destructive" to "default",
            ),
            e(
                "Dialog",
                G_FEEDBACK,
                KIND_SYSTEM,
                "Compose Dialog 系统窗口 + 组件库内容；样例是内容排版，页顶可打开真实窗口。",
                "primary-secondary,single,scroll-body" to "horizontal,stacked",
            ),
            e(
                "Sheet",
                G_FEEDBACK,
                KIND_SYSTEM,
                "M3 ModalBottomSheet；保护型禁下滑与点遮罩，保留右上关闭与系统返回。样例是内容排版，页顶可打开真实半屏。",
                "normal,protected" to "medium,full,keyboard",
            ),
            e("ActionMenu", G_FEEDBACK, KIND_SYSTEM, "底部款 M3 ModalBottomSheet + 列表项；锚点款 M3 DropdownMenu。", "bottom,anchor" to "default"),
            e(
                "OneTapLoginPanel",
                G_LOGIN,
                KIND_SYSTEM,
                "SDK 页面示意：真实页面由阿里云号码认证 SDK 授权页提供（号码栏、认证说明、运营商协议名不能改），这里只示意配置与回退，不拉起 SDK。",
                "carrier" to "unchecked,checked,last-login,number-failure,unavailable,error-20004,error-50305",
            ),
            e(
                "OAuthButton",
                G_LOGIN,
                KIND_SYSTEM,
                "48 圆 + 命中区 64 × 48，clickable(Role.Button) 保留系统水波纹。$PENDING_ASSETS_NOTE",
                "wechat,huawei" to "default,pressed,authorizing,disabled,last-login",
            ),
            e(
                "AgreementCheckRow",
                G_LOGIN,
                KIND_SYSTEM,
                "圆形勾选 + 一段系统富文本，协议链接嵌在句子里（点链接不勾选）；读屏整句一项，链接另作自定义操作。",
                "two,three" to "unchecked,checked,confirmed,disabled-unchecked,disabled-checked",
            ),
            e(
                "ThirdPartyLoginRow",
                G_LOGIN,
                KIND_SYSTEM,
                "组件库组合：分隔线 + 圆钮 +「登录遇到问题」；华为渠道包华为排第一。$PENDING_ASSETS_NOTE",
                "standard,huawei-channel" to "wechat-installed,wechat-absent",
            ),
            e("LastLoginTag", G_LOGIN, KIND_CUSTOM, "组件库自绘小胶囊，读屏并入按钮名称。", "oauth,primary" to "visible,hidden"),
            e("BrandMark", G_LOGIN, KIND_CUSTOM, "正式 Logo 矢量（官方狸橙，不随主题），56，读屏跳过。", "size56" to "default"),
            e(
                "PriceTag",
                G_BUSINESS,
                KIND_CUSTOM,
                "组件库自绘，等宽数字；放不下整块换行，不缩字号、不省略。",
                "coupon-list,coupon-ai,coupon-detail,no-coupon-list,no-coupon-ai,no-coupon-detail,pdd-list,pdd-ai,pdd-detail," +
                    "presale-list,presale-ai,presale-detail" to "normal,long-price",
            ),
            e(
                "RebateTag",
                G_BUSINESS,
                KIND_CUSTOM,
                "组件库自绘；区间只在破折号处折行。",
                "single,range,large,amount_unknown,login_required" to "normal,amount-hidden",
                "range" to "wrap-at-dash",
            ),
            e("EstNetLine", G_BUSINESS, KIND_CUSTOM, "预估返后一行。", "default" to "normal,amount-hidden"),
            e("DisclaimerFooter", G_BUSINESS, KIND_CUSTOM, "口径说明全文 / 每屏一次 / 单卡 i 按钮。", "detail,list,card-info" to "default"),
            e("PlatformBadge", G_BUSINESS, KIND_CUSTOM, "来源平台标签；包内平台小图标等官方素材。", "list,order" to "loaded,fallback"),
            e(
                "ProductCard",
                G_BUSINESS,
                KIND_CUSTOM,
                "组件库自绘；商品标题是唯一允许按行数省略的文字（不含金额）。",
                "search,ai,favorite,ranking,grid,large" to "single,range,no-rebate,long-price,amount-hidden,skeleton",
            ),
            e(
                "BuyButton",
                G_BUSINESS,
                KIND_CUSTOM,
                "CouliButton 紧凑主按钮 + 业务文案（随按钮恢复系统水波纹）。",
                "single,range,no-rebate" to "default,loading,disabled,retry-later",
            ),
            e("AmountGrid", G_BUSINESS, KIND_CUSTOM, "数字格；200% 字号纵排。", "two-column,vertical200,help" to "normal,amount-hidden"),
            e("WalletHeroCard", G_BUSINESS, KIND_CUSTOM, "狸橙实底主卡；卡内【提现】用 T1（深色仍白底）。", "wallet,me" to "normal,amount-hidden,zero"),
            e("EntryGrid", G_BUSINESS, KIND_CUSTOM, "宫格入口，整格命中、系统水波纹。", "four-column" to "default,pressed,hidden-entry"),
            e("PriceTrendPanel", G_BUSINESS, KIND_CUSTOM, "折叠行 + 折线，图下文字摘要供读屏。", "records,no-records" to "collapsed,expanded,loading"),
            e(
                "ShareMaterialPicker",
                G_BUSINESS,
                KIND_CUSTOM,
                "4 列方格 + 22 圆形勾选。",
                "qr-first" to "selected,unselected,all-selected,save-count",
            ),
            e(
                "ShareCopyEditor",
                G_BUSINESS,
                KIND_CUSTOM,
                "分段勾选 / 多行编辑 / 覆盖提示。",
                "view,edit,overwrite-confirm" to "required-checked-disabled",
            ),
            e("MatchBadge", G_BUSINESS, KIND_CUSTOM, "CouliTag 款条件标记。", "normal,unconfirmed" to "default"),
            e("AiLabel", G_BUSINESS, KIND_CUSTOM, "AI 内容标识。", "plain-text" to "default"),
            e("AgentConsentSheet", G_BUSINESS, KIND_SYSTEM, "系统容器（M3 ModalBottomSheet）+ 自绘内容；样例是内容排版。", "normal" to "unchecked,checked"),
            e("AuthSheet", G_BUSINESS, KIND_SYSTEM, "系统容器（M3 ModalBottomSheet）+ 自绘内容；样例是内容排版。", "normal" to "default"),
            e("JumpTip", G_BUSINESS, KIND_SYSTEM, "系统容器（Compose Dialog）+ 自绘内容；样例是内容排版。", "dialog" to "loading"),
            e(
                "StepUpSheet",
                G_BUSINESS,
                KIND_SYSTEM,
                "保护型半屏（禁下滑与点遮罩，保留右上关闭与系统返回）+ CodeInput；样例是内容排版。",
                "protected" to "empty,focused,submitting,error",
            ),
        )

    fun find(id: String): CatalogEntry? = entries.firstOrNull { it.id == id }

    /** 样例标题：变体 / 状态的中文说明（目录页样例上方）。 */
    fun label(
        variant: String,
        state: String,
    ): String = "$variant · $state"
}
