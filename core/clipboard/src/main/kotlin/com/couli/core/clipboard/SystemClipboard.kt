package com.couli.core.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context

/**
 * 系统剪贴板端口（CAP-X-02）：生产为 [AndroidSystemClipboard]，测试用计数的假剪贴板（读取次数、写入次数）。
 * 读到的原文只交给调用方在内存里过滤，本端口不缓存、不记日志。
 */
interface SystemClipboard {
    /**
     * 读主剪贴板的文本：先看描述是不是文本 MIME，是文本才取内容（brief §3 Android）。
     * - [ClipRead.Text]：读到文本；
     * - [ClipRead.NotText]：剪贴板里是图片等非文本，不读内容；
     * - [ClipRead.Unavailable]：拿到的是 null。Android 10+ 窗口没有焦点时 getPrimaryClip 返回 null——不能当成「空」，不记 hash，
     *   等获焦后再读；用户点击（窗口一定有焦点）时读到 null 才按空处理。
     */
    fun read(): ClipRead

    /** 写入纯文本（只由 [ClipboardWriter] 调用：写入前先记 hash）。 */
    fun write(
        text: String,
        label: String,
    ): Boolean
}

sealed interface ClipRead {
    data class Text(
        val value: String,
    ) : ClipRead

    data object NotText : ClipRead

    data object Unavailable : ClipRead
}

/** Android 实现：ClipboardManager。12+ 首次读取弹系统 toast、13+ 自动清剪贴板都是系统行为，不绕开。 */
class AndroidSystemClipboard(
    context: Context,
) : SystemClipboard {
    private val app = context.applicationContext

    private val manager: ClipboardManager? get() = app.getSystemService(ClipboardManager::class.java)

    @Suppress("ReturnCount") // 没有服务、没有描述、非文本、没有内容、空、取不出文本逐项短路
    override fun read(): ClipRead {
        val clipboard = manager ?: return ClipRead.Unavailable
        val description = runCatching { clipboard.primaryClipDescription }.getOrNull() ?: return ClipRead.Unavailable
        if (TEXT_TYPES.none(description::hasMimeType)) return ClipRead.NotText
        val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return ClipRead.Unavailable
        if (clip.itemCount == 0) return ClipRead.Unavailable
        // 只取文本本身，不经 coerceToText 去解析 URI（不读别的 App 的内容提供方）
        val text = clip.getItemAt(0)?.text?.toString() ?: return ClipRead.NotText
        return ClipRead.Text(text)
    }

    override fun write(
        text: String,
        label: String,
    ): Boolean =
        runCatching {
            val clipboard = manager ?: return false
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        }.getOrDefault(false)

    private companion object {
        val TEXT_TYPES =
            listOf(ClipDescription.MIMETYPE_TEXT_PLAIN, ClipDescription.MIMETYPE_TEXT_HTML, ClipDescription.MIMETYPE_TEXT_URILIST)
    }
}

/**
 * App 写剪贴板的唯一入口（brief §2.3）：桥 clipboard.write、复制口令 copy_tpwd、各原生复制按钮都经它。写入前先把 hash 记进
 * [ClipboardWriteLedger]，之后识别时排除（商品、邀请两种都不提示）。识别、提示、上传、绑定的前后都不经这里写——识别流程里写入次数为 0。
 */
class ClipboardWriter(
    private val system: SystemClipboard,
    private val ledger: ClipboardWriteLedger,
) {
    fun write(
        text: String,
        label: String = DEFAULT_LABEL,
    ): Boolean {
        ledger.record(text)
        return system.write(text, label)
    }

    private companion object {
        const val DEFAULT_LABEL = "couli"
    }
}
