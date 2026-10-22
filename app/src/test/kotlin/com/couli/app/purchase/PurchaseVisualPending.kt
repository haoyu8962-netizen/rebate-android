package com.couli.app.purchase

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.couli.core.orders.PendingTrackEnv
import com.couli.core.orders.PendingTrackService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal class PurchaseVisualPending(
    private val host: PurchaseVisualTestHost,
    private val rule: ComposeContentTestRule,
) : PendingTrackEnv {
    var permission = "not_determined"
    var notifications = 0
    var claims = 0
    var epoch = 1L
    val service = PendingTrackService(host.api, this, host.scope)

    override fun session(): String = "member"

    override fun notification(): String = permission

    override fun requestedBefore(): Boolean = notifications > 0

    override fun sessionEpoch(): Long = epoch

    override suspend fun requestNotification(stillValid: () -> Boolean) {
        if (stillValid()) notifications += 1
    }

    override fun forceUpdate() = Unit

    fun show(claim: Boolean = false) {
        host.buy()
        host.answer("unchanged")
        rule.runOnIdle { service.refresh(epoch, "taobao") }
        rule.waitForIdle()
        val item =
            PurchaseVisualFixtures.pending
                .getValue("items")
                .jsonArray
                .single()
                .jsonObject
        val data = JsonObject(mapOf("items" to JsonArray(listOf(JsonObject(item + ("show_claim_entry" to JsonPrimitive(claim)))))))
        rule.runOnIdle { host.api.succeed(data, PendingTrackService.LIST_OPERATION) }
        rule.waitForIdle()
        check(service.state.value.cards.size == 1)
    }
}
