package com.couli.app.clipboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.couli.app.MainActivity

/** 系统分享的接收方（由 Application 提供；生产为 CouliApplication 注入的 AppClipboard）。 */
fun interface ShareIntake {
    /** [text] 是 EXTRA_TEXT 原文：当场抽候选后即丢弃（AppClipboard.receiveShare）。 */
    fun receiveShare(
        mime: String?,
        text: String?,
    ): Boolean
}

/** Application 实现它，把分享交给剪贴板模块（测试的 Application 注入假实现）。 */
interface ShareIntakeProvider {
    fun shareIntake(): ShareIntake
}

/**
 * 系统分享的专用接收 Activity（FU-CLIP §2.5 第 1 条；K17、K23）：exported、excludeFromRecents、noHistory、无界面主题，intent-filter 只收
 * `ACTION_SEND` + `text/plain`。在 onCreate 里：
 * 1. 校验 action 与 MIME，取 EXTRA_TEXT（getCharSequenceExtra，按不可信数据处理；取不出按空），**当场**交给 [ShareIntake]：抽出候选、原文丢弃，
 *    候选只放进进程内的一次性交接（不持久化，进程被杀即丢失）；
 * 2. 清掉本 Activity 自己 Intent 里的内容（不让原文留在任何保存的 Intent 里）；
 * 3. 以 NEW_TASK | CLEAR_TOP | SINGLE_TOP 拉起 MainActivity（不带任何分享内容），finish。
 * 从最近任务恢复（FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY）、配置变更重建（savedInstanceState 不为空）都不再处理。
 */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = intent
        val fromHistory = incoming != null && (incoming.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (incoming != null && savedInstanceState == null && !fromHistory) {
            val text =
                if (incoming.action == Intent.ACTION_SEND) {
                    runCatching { incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }.getOrNull()
                } else {
                    null
                }
            val mime = if (incoming.action == Intent.ACTION_SEND) incoming.type else null
            (application as? ShareIntakeProvider)?.shareIntake()?.receiveShare(mime, text)
        }
        // 原文不留在本 Activity 的 Intent 里（noHistory 本来就不进最近任务，这里再清一次）
        intent = Intent()
        startActivity(mainIntent())
        finish()
    }

    private fun mainIntent(): Intent =
        Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
}
