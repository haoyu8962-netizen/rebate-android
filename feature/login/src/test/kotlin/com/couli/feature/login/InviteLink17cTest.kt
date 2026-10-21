package com.couli.feature.login

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteLink17cTest {
    @Test
    fun decisionTruthTableHonoursConsentFormatOwnCodeGuestAndEligibilityOrder() {
        (0 until 32).forEach { bits ->
            val consented = bits and 1 != 0
            val valid = bits and 2 != 0
            val own = bits and 4 != 0
            val guest = bits and 8 != 0
            val eligible = bits and 16 != 0
            val input =
                InviteLinkInput(
                    rawCode = if (valid) " k7m2qx " else "K7M2Q!",
                    consented = consented,
                    guest = guest,
                    confirmedMe = if (guest) null else me(if (own) "K7M2QX" else "P8N3RY", eligible),
                )
            val kind =
                when {
                    !consented || !valid || (!guest && own) -> "drop"
                    guest -> "login"
                    eligible -> "invite_backfill"
                    else -> "home"
                }
            val result = InviteLinkDispatcher.decide(input)
            assertEquals(input.toString(), kind, result.kindRaw)
            assertEquals(if (kind == "login" || kind == "invite_backfill") "K7M2QX" else null, result.code)
        }
    }

    @Test
    fun dispatchDropLeavesOldCodeAndDoesNotNavigate() =
        runTest {
            listOf(false, true).forEach { guest ->
                listOf(
                    "",
                    "K7M2Q",
                    "K7M2QXX",
                    "K7M2Q0",
                    "K7M2Q1",
                    "K7M2QI",
                    "K7M2QO",
                    "K7M-QX",
                    "K7M2Q!",
                    "https://share.example.test/i/K7M2QX",
                ).forEach { invalid ->
                    val rig = InviteLink17cRig(guest = guest)
                    assertEquals("drop", rig.dispatcher.dispatch(invalid).kindRaw)
                    rig.untouched()
                    assertEquals("格式失败不查询本人资料", 0, rig.meReads)
                }
                val rig = InviteLink17cRig(guest = guest, consented = false)
                assertEquals("drop", rig.dispatcher.dispatch("K7M2QX").kindRaw)
                rig.untouched()
                assertEquals(0, rig.meReads)
            }
        }

    @Test
    fun guestSetsNormalizedCodeBeforeOpeningLoginAndOpenFailureKeepsIt() =
        runTest {
            listOf(false, true).forEach { opens ->
                val rig = InviteLink17cRig()
                rig.openResult = opens
                assertEquals("login", rig.dispatcher.dispatch("Ｋ７Ｍ２ＱＸ").kindRaw)
                assertEquals(listOf("Login" to emptyMap<String, String>()), rig.opens)
                assertEquals(listOf("K7M2QX"), rig.codeAtOpen)
                assertEquals("K7M2QX", rig.pending.peek())
                assertEquals("游客不查 getMe", 0, rig.meReads)
            }
        }

    @Test
    fun eligibleAndHomeBranchesPreserveBothEmptyAndExistingPending() =
        runTest {
            listOf(false, true).forEach { oldPresent ->
                listOf(false, true).forEach { eligible ->
                    val rig = InviteLink17cRig(guest = false)
                    if (!oldPresent) rig.pending.clear()
                    rig.snapshot = me("P8N3RY", eligible)
                    val result = rig.dispatcher.dispatch("K7M2QX")
                    val route = if (eligible) "InviteBackfill" else "Home"
                    val params = if (eligible) mapOf("prefill" to "K7M2QX") else emptyMap()
                    assertEquals(if (eligible) "invite_backfill" else "home", result.kindRaw)
                    assertEquals(listOf(route to params), rig.opens)
                    assertEquals(if (oldPresent) "AB23CD" else null, rig.pending.peek())
                    assertEquals(1, rig.meReads)
                }
            }
        }

    @Test
    fun ownCodeAndEligibilityComeFromOneConfirmedGetMe() =
        runTest {
            listOf("K7M2QX", "P8N3RY").forEach { incoming ->
                val rig = InviteLink17cRig(guest = false)
                rig.snapshot = me("K7M2QX", false)
                rig.secondSnapshot = me("AB23CD", true)
                assertEquals(if (incoming == "K7M2QX") "drop" else "home", rig.dispatcher.dispatch(incoming).kindRaw)
                val expectedOpens = if (incoming == "K7M2QX") emptyList() else listOf("Home" to emptyMap<String, String>())
                assertEquals("本人码 DROP 不得导航", expectedOpens, rig.opens)
                assertEquals("不能分别读取本人码和 eligible", 1, rig.meReads)
                assertEquals("AB23CD", rig.pending.peek())
            }
        }

    @Test
    fun missingOrUnconfirmedMeGoesHomeWithoutChangingCode() =
        runTest {
            listOf(null, JsonObject(emptyMap())).forEach { snapshot ->
                val rig = InviteLink17cRig(guest = false)
                rig.snapshot = snapshot
                assertEquals("home", rig.dispatcher.dispatch("K7M2QX").kindRaw)
                assertEquals(listOf("Home" to emptyMap<String, String>()), rig.opens)
                assertEquals("AB23CD", rig.pending.peek())
            }
            val decision = InviteLinkDispatcher.decide(InviteLinkInput("K7M2QX", true, false, null))
            assertNull(decision.code)
        }

    @Test
    fun failedGetMeGoesHomeAndUnknownOwnCodeUsesTheConfirmedEligibility() =
        runTest {
            val failed = InviteLink17cRig(guest = false)
            failed.meFailure = true
            assertEquals("home", failed.dispatcher.dispatch("K7M2QX").kindRaw)
            assertEquals(listOf("Home" to emptyMap<String, String>()), failed.opens)
            assertEquals("AB23CD", failed.pending.peek())
            val unknownOwn = InviteLink17cRig(guest = false)
            unknownOwn.snapshot =
                Json.parseToJsonElement(
                    """{"invite_code":null,"invite_backfill":{"eligible":true}}""",
                ) as JsonObject
            assertEquals("invite_backfill", unknownOwn.dispatcher.dispatch("K7M2QX").kindRaw)
            assertEquals(1, unknownOwn.meReads)
        }

    @Test
    fun unifiedEntryGuardRunsBeforeConsentLookupMeSetAndNavigation() =
        runTest {
            val rig = InviteLink17cRig()
            rig.allowed = false
            assertEquals("drop", rig.dispatcher.dispatch("K7M2QX").kindRaw)
            assertEquals(listOf("guard"), rig.events)
            rig.untouched()
            rig.allowed = true
            assertEquals("login", rig.dispatcher.dispatch("K7M2QX").kindRaw)
            assertEquals(listOf("guard", "guard", "consent", "guest", "open"), rig.events)
        }

    private fun me(
        own: String,
        eligible: Boolean,
    ) = Json.parseToJsonElement(
        """{"invite_code":"$own","invite_backfill":{"eligible":$eligible}}""",
    ) as JsonObject
}

internal class InviteLink17cRig(
    guest: Boolean = true,
    consented: Boolean = true,
) {
    val pending = PendingInviteCode().apply { set("AB23CD") }
    val opens = mutableListOf<Pair<String, Map<String, String>>>()
    val codeAtOpen = mutableListOf<String?>()
    val events = mutableListOf<String>()
    var meReads = 0
    var snapshot: JsonObject? = null
    var secondSnapshot: JsonObject? = null
    var meFailure = false
    var allowed = true
    var openResult = true
    val dispatcher =
        InviteLinkDispatcher(
            pending = pending,
            entryAllowed = {
                events += "guard"
                allowed
            },
            consented = {
                events += "consent"
                consented
            },
            guest = {
                events += "guest"
                guest
            },
            getMe = {
                events += "getMe"
                meReads += 1
                if (meFailure) throw java.io.IOException("fixture getMe failed")
                if (meReads == 1) snapshot else secondSnapshot
            },
            open = { route, params ->
                events += "open"
                opens += route to params
                codeAtOpen += pending.peek()
                openResult
            },
        )

    fun untouched() {
        assertEquals("负向断言在 dispatch 返回后执行", emptyList<Pair<String, Map<String, String>>>(), opens)
        assertEquals("AB23CD", pending.peek())
    }
}
