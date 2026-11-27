package com.couli.app.inbound

import android.app.Activity
import android.os.Bundle
import com.couli.feature.trade.WebCodeCallbackSink

/** Application 实现它，把 couli://auth/taobao 回跳交给进程内收件箱（测试的 Application 注入假实现）。 */
interface WebCodeCallbackSinkProvider {
    fun webCodeCallbackSink(): WebCodeCallbackSink
}

/**
 * couli 入站中转（设计稿 §3.1、§5.3；仿 ShareReceiverActivity；intent-filter 由 tools/codegen 生成到 core/generated 的清单）：
 * - `couli://auth/taobao?…`：解析后当场交给 [WebCodeCallbackSinkProvider] 的收件箱，清掉自身 Intent，再以 NEW_TASK | CLEAR_TOP | SINGLE_TOP
 *   拉起不带 data、不带 code / state 的 MainActivity（带回前台、关掉 Custom Tab），finish；
 * - `couli://r/…`：原样转给 MainActivity；
 * - 其他：忽略并 finish。
 * 从最近任务恢复、savedInstanceState 不为空时不处理；只读 data，不读 extras。
 *
 * TODO(FU-AND-TB-WEBCODE-A 实现)：接口桩，现在什么都不处理、直接 finish。
 */
class InboundSchemeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
