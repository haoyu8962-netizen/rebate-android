package com.couli.core.clipboard

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** R18/G16：生产系统端口 + 生产适配器，URI 的隐式转文本会直接让测试失败。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class AndroidClipboardAdapter0724Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(ClipboardManager::class.java)
    private val adapter = AndroidClipboardAdapter(AndroidSystemClipboard(context), maxTextCodePoints = 64)

    @Test
    fun readsOnlyTheFirstItemAndNeverCoercesTheUriOrImage() {
        var secondaryReads = 0
        var coercions = 0
        val second =
            object : ClipData.Item("second-secret") {
                override fun getText(): CharSequence {
                    secondaryReads += 1
                    return "second-secret"
                }
            }
        val clip = ClipData.newPlainText("sample", "first-text")
        clip.addItem(second)
        manager.setPrimaryClip(clip)
        assertEquals(ImportReadResult("text", "first-text"), adapter.read())
        for (mime in listOf(ClipDescription.MIMETYPE_TEXT_URILIST, "image/png", ClipDescription.MIMETYPE_TEXT_PLAIN)) {
            val item =
                object : ClipData.Item(Uri.parse("content://clipboard-0724/private")) {
                    override fun coerceToText(context: Context): CharSequence {
                        coercions += 1
                        error("不允许解析 content URI")
                    }

                    override fun coerceToStyledText(context: Context): CharSequence {
                        coercions += 1
                        error("不允许隐式转文本")
                    }
                }
            val uriFirst = ClipData("sample", arrayOf(mime), item)
            uriFirst.addItem(second)
            manager.setPrimaryClip(uriFirst)
            val result = adapter.read()
            assertEquals("unavailable", result.kindRaw)
            assertNull(result.text)
        }
        assertEquals("不能读取次要项后再丢弃", 0, secondaryReads)
        assertEquals("异常吞掉也不能掩盖曾经 coerce", 0, coercions)
    }

    @Test
    fun boundsTextWithoutTruncatingItIntoADifferentCandidate() {
        for (text in listOf("a".repeat(64), "😀".repeat(64))) {
            manager.setPrimaryClip(ClipData.newPlainText("sample", text))
            assertEquals(ImportReadResult("text", text), adapter.read())
            manager.setPrimaryClip(ClipData.newPlainText("sample", text + "x"))
            assertEquals(ImportReadResult("unavailable"), adapter.read())
        }
    }

    @Test
    fun emptyUnavailableAndExceptionsAreSingleAttemptResultsAndDoNotWrite() {
        var reads = 0
        var writes = 0
        var answer: () -> ClipRead = { ClipRead.Text("") }
        val port =
            object : SystemClipboard {
                override fun read(): ClipRead {
                    reads += 1
                    return answer()
                }

                override fun write(
                    text: String,
                    label: String,
                ): Boolean {
                    writes += 1
                    return true
                }
            }
        val bounded = AndroidClipboardAdapter(port)
        assertEquals(ImportReadResult("empty"), bounded.read())
        answer = { ClipRead.Unavailable }
        assertEquals(ImportReadResult("unavailable"), bounded.read())
        answer = { throw SecurityException("private-clipboard-canary") }
        assertEquals(ImportReadResult("unavailable"), bounded.read())
        assertEquals(ImportReadResult("unavailable"), bounded.read())
        answer = { ClipRead.Text("recovered-text") }
        assertEquals(ImportReadResult("text", "recovered-text"), bounded.read())
        assertEquals(5, reads)
        assertEquals(0, writes)
    }
}
