package com.kuguan.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kuguan.app.domain.query.ItemBrief
import com.kuguan.app.domain.query.ItemSnapshot
import com.kuguan.app.domain.query.LocationQty
import com.kuguan.app.domain.query.LookupResult
import com.kuguan.app.ui.theme.KuguanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeLookupCardUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun foundShowsEveryLocationFullCodeTotalAndDetailLink() {
        var opened: String? = null
        val longLocation = "厨房冰箱最上层右侧靠门的收纳盒-编号-2026"
        compose.setContent { KuguanTheme(darkTheme = false) {
            Column(Modifier.height(240.dp).verticalScroll(rememberScrollState())) {
                HomeLookupCard(LookupResult.Found(ItemSnapshot(
                    "milk", "牛奶", null,
                    listOf(LocationQty(longLocation, 2.0), LocationQty("储物柜-B-09", 3.0)),
                ))) { opened = it }
            }
        } }
        compose.onNodeWithText("找到 2 处 · 合计 5 个").assertExists()
        compose.onNodeWithText(longLocation).assertExists()
        compose.onNodeWithText("储物柜-B-09").assertExists()
        compose.onNodeWithText("查看「牛奶」详情").performScrollTo().performClick()
        assertEquals("milk", opened)
    }

    @Test fun multipleItemsShowSeparateNamesLocationsAndDetailLinks() {
        var opened: String? = null
        compose.setContent { KuguanTheme(darkTheme = false) {
            Column(Modifier.height(360.dp).verticalScroll(rememberScrollState())) {
                HomeLookupCard(LookupResult.Matches(listOf(
                    ItemSnapshot("battery", "电池", null, listOf(LocationQty("左侧储物架", 1.0))),
                    ItemSnapshot("seven", "7号电池", null,
                        listOf(LocationQty("A-07", 4.0), LocationQty("A-10", 2.0))),
                ))) { opened = it }
            }
        } }
        compose.onNodeWithText("找到 2 件相关物品").assertExists()
        compose.onNodeWithText("左侧储物架").assertExists()
        compose.onNodeWithText("A-07").assertExists()
        compose.onNodeWithText("A-10").assertExists()
        compose.onNodeWithText("查看「7号电池」详情").performScrollTo().performClick()
        assertEquals("seven", opened)
    }

    @Test fun emptyAndSuggestionStatesRemainReadable() {
        compose.setContent { KuguanTheme(darkTheme = false) {
            HomeLookupCard(LookupResult.NotFound("牛奶", listOf(ItemBrief("oat", "燕麦奶")))) {}
        } }
        compose.onNodeWithText("没有找到「牛奶」").assertExists()
        compose.onNodeWithText("燕麦奶").assertExists()
    }
}
