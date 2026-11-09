@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.search.submit

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clipboard.ClipboardResult
import com.couli.core.network.ApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 测试 v4.1（D10-666 ①）：粘贴共用的商品卡弹层里，行内 30132 的【用商品名搜索】预填取 [com.couli.core.clipboard.ClipboardText.searchPrefill]
 * （这一行 hit.raw 的「」内或首行）。hit.raw 是链接本身时首行不是商品名，属「提取不到」：打开 Search 不带 q。原「把链接预填进去」按缺陷修。
 * 结果由搜索页真实提交产生（两条链接 → 多条结果），按钮动作走粘贴现有的 AppClipboard.searchByName(CardList, row)。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class SearchLinkPrefillSubmitTest {
    @Before fun useTestMain() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun resetMain() = Dispatchers.resetMain()

    @Test
    fun cardListRow30132WithBareLinkSearchesByNameWithoutPrefill() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                f.submit("$LINK $SECOND_LINK")
                runCurrent()
                assertEquals("前提：输入是候选，先只发识别", listOf("parseInput"), operations(f))
                f.api.parseReply.complete(mixedRows())
                advanceUntilIdle()
                assertEquals("前提：多条结果不回落关键词搜", listOf("parseInput"), operations(f))
                val list = f.clipboard.result.value
                assertTrue("前提：粘贴共用的 CardList，实际 $list", list is ClipboardResult.CardList)
                list as ClipboardResult.CardList
                val row = list.rows.single { it.errorCode == 30132 }
                assertEquals("前提：这一行 hit.raw 是链接本身", SECOND_LINK, row.hitRaw)
                assertTrue("前提：点按钮前未导航", f.navigations.isEmpty())

                f.clipboard.searchByName(list, row)
                advanceUntilIdle()

                assertEquals(
                    "行内 30132 + 纯链接：打开 Search 不带 q（预填留空，不拿原始链接当商品名）",
                    listOf("Search" to JsonObject(emptyMap())),
                    f.navigations,
                )
                assertEquals("点【用商品名搜索】不再识别、不在本页搜", listOf("parseInput"), operations(f))
            }
        }

    private fun operations(f: SearchSubmitFixture): List<String> = f.api.calls.map { it.operationId }

    private fun mixedRows(): ApiResult =
        ApiResult.Success(
            SearchSubmitFixture.json(
                """
                {
                  "results":[
                    {
                      "hit":{"platform":"taobao","kind":"url","raw":"$LINK"},
                      "card":{
                        "platform":"taobao","product_key":"tb:7Kq9LmN3pQ","item_ref":"v1.ref.sig",
                        "title":"示例商品","availability":"ok","rebate_basis":"normal",
                        "link_id":"0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a70",
                        "price_fen":3990,"coupon_fen":1000,"final_price_fen":2990,
                        "rebate_min_fen":269,"rebate_max_fen":269,"est_net_price_fen":2721,
                        "disclaimer_keys":["price_basis"],"cta":{"text_key":"btn.buy.coupon"}
                      }
                    },
                    {"hit":{"platform":"jd","kind":"url","raw":"$SECOND_LINK"},"error_code":30132}
                  ]
                }
                """.trimIndent(),
            ),
            "parse-submit-000123",
            null,
        )

    private companion object {
        const val LINK = "https://item.taobao.com/item.htm?id=123456"
        const val SECOND_LINK = "https://item.jd.com/100200.html"
    }
}
