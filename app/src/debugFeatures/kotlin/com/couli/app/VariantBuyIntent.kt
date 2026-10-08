package com.couli.app

/** debug / staging：冒烟时提示已发出购买意图（开发字样；TODO(APP-06b)：换成 open 流程）。Release 没有这句。 */
@Suppress("FunctionOnlyReturningConstant") // 与 release 源码集同一签名
internal fun variantBuyIntentNotice(): String? = "已发出购买意图（APP-06b 接 open 流程）"
