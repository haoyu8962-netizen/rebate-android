@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.feature.login

import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingInvite17cTest {
    @Test
    fun sharedValidVectorsAreAcceptedAndStoredAfterNormalization() {
        val rows = sharedSetVectors().getValue("valid").jsonArray
        assertEquals("三端共用合格样例数（含 U+0085）", 8, rows.size)
        rows.forEachIndexed { index, element ->
            val row = element.jsonObject
            val label = "valid[$index]"
            val pending = PendingInviteCode()
            val raw = row.getValue("raw").jsonPrimitive.content
            val stored = row.getValue("stored").jsonPrimitive.content
            assertTrue("$label.accepted", pending.set(raw))
            assertStored(label, stored, pending)
            assertEquals("$label.共用规范化", stored, InviteField.normalize(raw))
            assertTrue("$label.共用格式校验", InviteField.isValidCode(InviteField.normalize(raw)))
        }
    }

    @Test
    fun sharedInvalidVectorsRejectWithoutChangingEmptyOrPreviouslySetContainer() {
        val rows = sharedSetVectors().getValue("invalid").jsonArray
        assertEquals("三端共用不合格样例数", 11, rows.size)
        rows.forEachIndexed { index, element ->
            val raw = element.jsonPrimitive.content
            val label = "invalid[$index]"
            val empty = PendingInviteCode()
            assertNull("$label.初始为空", empty.peek())
            assertFalse("$label.空容器拒绝", empty.set(raw))
            assertStored("$label.空容器", null, empty)

            val seeded = PendingInviteCode()
            assertTrue("$label.先写旧码", seeded.set("K7M2QX"))
            assertFalse("$label.已有码容器拒绝", seeded.set(raw))
            assertStored("$label.已有码容器", "K7M2QX", seeded)
            assertFalse("$label.共用格式校验", InviteField.isValidCode(InviteField.normalize(raw)))
        }
    }

    @Test
    fun sharedSequencesApplyEveryStepAndKeepExpectedFinalValue() {
        val rows = sharedSetVectors().getValue("sequences").jsonArray
        assertEquals("三端共用顺序样例数", 3, rows.size)
        rows.forEachIndexed { index, element ->
            val row = element.jsonObject
            val label = "sequences[$index]"
            val pending = PendingInviteCode()
            val steps = row.getValue("steps").jsonArray
            assertFalse("$label.steps 不能为空", steps.isEmpty())
            steps.forEach { pending.set(it.jsonPrimitive.content) }
            assertStored(label, row.getValue("stored").jsonPrimitive.contentOrNull, pending)
        }
    }

    @Test
    fun combinedWhitespaceNormalizationCanReplacePreviousCode() {
        val pending = PendingInviteCode()
        assertTrue(pending.set("P8N3RY"))
        assertTrue(pending.set(" \t\u200B\uFEFFK7M2QX\u200B\uFEFF\r\n"))
        assertStored("端内补充：组合空白及零宽字符", "K7M2QX", pending)
    }

    @Test
    fun replaceRemoveConsumeAndClearAreIndependentInMemoryOperations() {
        val pending = PendingInviteCode()
        assertNull(pending.peek())
        assertTrue(pending.set("K7M2QX"))
        assertTrue(pending.set("P8N3RY"))
        assertEquals("P8N3RY", pending.consumeForRequest())
        assertNull(pending.consumeForRequest())
        pending.set("K7M2QX")
        pending.remove()
        assertNull(pending.peek())
        pending.set("K7M2QX")
        assertNull("新进程容器不读静态共享值", PendingInviteCode().peek())
        pending.clear()
        assertNull(pending.peek())
    }

    @Test
    fun stateFlowNotifiesSetReplaceRemoveConsumeAndClear() =
        runTest {
            val pending = PendingInviteCode()
            val seen = mutableListOf<String?>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { pending.state.toList(seen) }

            suspend fun expect(
                value: String?,
                action: () -> Unit,
            ) {
                val before = seen.size
                action()
                testScheduler.runCurrent()
                assertEquals(before + 1, seen.size)
                assertEquals(value, seen.last())
            }
            assertEquals(listOf<String?>(null), seen)
            expect("K7M2QX") { pending.set("K7M2QX") }
            assertFalse(pending.set("bad"))
            testScheduler.runCurrent()
            assertEquals(listOf(null, "K7M2QX"), seen)
            expect("P8N3RY") { pending.set("P8N3RY") }
            expect(null) { pending.remove() }
            expect("K7M2QX") { pending.set("K7M2QX") }
            expect(null) { pending.consumeForRequest() }
            expect("K7M2QX") { pending.set("K7M2QX") }
            expect(null) { pending.clear() }
            collector.cancel()
        }

    private fun sharedSetVectors(): JsonObject {
        val root =
            generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
                .first { File(it, "contract.lock").isFile }
        val vectors = Json.parseToJsonElement(File(root, "specs/login-vectors.json").readText()).jsonObject
        return vectors.getValue("pendingInviteSet").jsonObject
    }

    private fun assertStored(
        label: String,
        expected: String?,
        pending: PendingInviteCode,
    ) {
        assertEquals("$label.peek", expected, pending.peek())
        assertEquals("$label.peek 不消费", expected, pending.peek())
        assertEquals("$label.StateFlow", expected, pending.state.value)
    }
}
