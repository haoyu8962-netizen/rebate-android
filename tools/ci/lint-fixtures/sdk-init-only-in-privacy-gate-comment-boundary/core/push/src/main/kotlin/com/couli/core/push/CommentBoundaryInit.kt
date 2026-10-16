package com.couli.core.push

// lint 违反样例（sdk-init-only-in-privacy-gate，FU-AND-0717 F-X-07 补充，iOS FU-IOS-0712 R1-02 同类）：
// 第 10 行：块注释在同一行就结束，后面的调用是代码，行尾那个结束符在行注释里；第 11 行：反引号标识符包住的同一个调用。
// 第 9 行是嵌套块注释（Kotlin 块注释可嵌套），整段都是注释，不报。
object CommentBoundaryInit {
    fun run(context: Any) {
        val ratio = context.hashCode()!!/2
        /* /* */ MobSDK.init(context) // */
        /* note */ MobSDK.init(context) // */
        `MobSDK`.`init`(context)
    }
}
