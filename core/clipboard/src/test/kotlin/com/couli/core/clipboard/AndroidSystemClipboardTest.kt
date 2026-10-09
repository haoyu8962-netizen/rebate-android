package com.couli.core.clipboard

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Android 系统剪贴板端口（brief §3）：先看描述是不是文本 MIME，是文本才取内容；非文本（图片 URI 等）不读内容；没有内容按 Unavailable（不当成空）；
 * 写入只经 [ClipboardWriter]，写之前记 hash。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AndroidSystemClipboardTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(ClipboardManager::class.java)
    private val port = AndroidSystemClipboard(context)

    @Test
    fun readsPlainText() {
        manager.setPrimaryClip(ClipData.newPlainText("t", "https://e.tb.cn/h.AbCdEf"))
        assertEquals(ClipRead.Text("https://e.tb.cn/h.AbCdEf"), port.read())
    }

    @Test
    fun nonTextIsNotRead() {
        manager.setPrimaryClip(ClipData.newRawUri("img", Uri.parse("content://media/external/images/1")))
        assertEquals(ClipRead.NotText, port.read())
    }

    @Test
    fun emptyClipboardIsUnavailableNotEmptyText() {
        manager.clearPrimaryClip()
        assertTrue(port.read() is ClipRead.Unavailable)
    }

    @Test
    fun writerRecordsHashBeforeWriting() {
        val ledger = ClipboardWriteLedger()
        val writer = ClipboardWriter(port, ledger)
        assertTrue(writer.write("￥AbCdEf12￥"))
        assertTrue(ledger.contains("￥AbCdEf12￥"))
        assertEquals(ClipRead.Text("￥AbCdEf12￥"), port.read())
    }
}
