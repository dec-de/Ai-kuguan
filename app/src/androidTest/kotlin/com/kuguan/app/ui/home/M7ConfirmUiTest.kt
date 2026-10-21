package com.kuguan.app.ui.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kuguan.app.ui.theme.KuguanTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M7ConfirmUiTest {
    @get:Rule val compose = createComposeRule()

    private fun row(key: String, action: ConfirmAction) = ConfirmRow(
        key = key, action = action, locationCode = "A-07", itemName = "电池$key",
        quantity = 2.0, committedSeq = key.length.toLong(),
    )

    @Test fun uniformActionsHaveDistinctWordsAndArrows() {
        compose.setContent {
            KuguanTheme(darkTheme = false) { ConfirmPanel(ConfirmBatch(listOf(row("put", ConfirmAction.PUT)))) }
        }
        compose.onNodeWithText("放入 · 1 项").assertExists()
        compose.onNodeWithText("取出 · 1 项").assertDoesNotExist()
    }

    @Test fun mixedBatchNamesEachActionAndHasNoMisleadingHeaderArrow() {
        compose.setContent {
            KuguanTheme(darkTheme = false) {
                ConfirmPanel(ConfirmBatch(listOf(row("put", ConfirmAction.PUT), row("take", ConfirmAction.TAKE))))
            }
        }
        compose.onNodeWithText("共 2 项").assertExists()
        compose.onNodeWithText("↓ 放入").assertExists()
        compose.onNodeWithText("↑ 取出").assertExists()
    }

    @Test fun quantityIsReadableBeforeAndAfterFocus() {
        compose.setContent {
            KuguanTheme(darkTheme = false) {
                ConfirmPanel(ConfirmBatch(listOf(row("upper", ConfirmAction.PUT).copy(quantity = 999.0))))
            }
        }
        compose.onNodeWithText("999").assertIsDisplayed()
        compose.onNodeWithTag("quantity-input").assertDoesNotExist()
        compose.onNodeWithTag("edit-row-upper").performClick()
        compose.onNodeWithTag("quantity-input").assertIsDisplayed().assertTextEquals("999")
        compose.onNodeWithTag("quantity-input").performClick()
        compose.onNodeWithTag("quantity-input").assertTextEquals("999")
    }

    @Test fun recordingButtonShowsObviousActiveState() {
        compose.setContent {
            KuguanTheme(darkTheme = false) {
                HoldToTalkButton(recording = true, label = "正在听，松开结束")
            }
        }
        compose.onNodeWithText("正在听，松开结束").assertIsDisplayed()
        compose.onNodeWithText("按住说话").assertDoesNotExist()
    }

}
