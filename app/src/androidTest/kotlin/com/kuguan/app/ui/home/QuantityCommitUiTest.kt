package com.kuguan.app.ui.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.kuguan.app.ui.theme.KuguanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class QuantityCommitUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun doneCommitsPendingQuantityExactlyOnce() {
        var commits = 0
        var saved = 0.0
        compose.setContent {
            KuguanTheme(darkTheme = false) {
                ConfirmPanel(
                    batch = ConfirmBatch(listOf(ConfirmRow("one", ConfirmAction.PUT,
                        "A-07", "电池", null))),
                    onRowEdited = { _, _, _, quantity -> commits++; saved = quantity },
                )
            }
        }
        compose.onNodeWithTag("edit-row-one").performClick()
        compose.onNodeWithTag("quantity-input").performClick()
        compose.onNodeWithTag("quantity-input").performTextInput("3")
        compose.onNodeWithTag("quantity-input").performImeAction()
        compose.runOnIdle {
            assertEquals(1, commits)
            assertEquals(3.0, saved, 0.0)
        }
    }

    @Test fun switchingFieldsKeepsDraftUntilSave() {
        var commits = 0
        compose.setContent {
            KuguanTheme(darkTheme = false) {
                ConfirmPanel(
                    batch = ConfirmBatch(listOf(ConfirmRow("one", ConfirmAction.PUT,
                        "A-07", "电池", null))),
                    onRowEdited = { _, _, _, _ -> commits++ },
                )
            }
        }
        compose.onNodeWithTag("edit-row-one").performClick()
        compose.onNodeWithTag("quantity-input").performClick()
        compose.onNodeWithTag("quantity-input").performTextInput("4")
        compose.onNodeWithText("电池").performClick()
        compose.runOnIdle { assertEquals(0, commits) }
        compose.onNodeWithTag("save-row-one").performClick()
        compose.runOnIdle { assertEquals(1, commits) }
    }

    @Test fun cancelEditDoesNotCommit() {
        var commits = 0
        compose.setContent {
            KuguanTheme(darkTheme = false) {
                ConfirmPanel(
                    batch = ConfirmBatch(listOf(ConfirmRow("one", ConfirmAction.PUT,
                        "A-07", "电池", 2.0))),
                    onRowEdited = { _, _, _, _ -> commits++ },
                )
            }
        }
        compose.onNodeWithTag("edit-row-one").performClick()
        compose.onNodeWithTag("quantity-input").performClick()
        compose.onNodeWithTag("quantity-input").performTextInput("3")
        compose.onNodeWithText("取消编辑").performClick()
        compose.runOnIdle { assertEquals(0, commits) }
        compose.onNodeWithTag("quantity-input").assertDoesNotExist()
    }

    @Test fun customDrawerCodeIsSavedExactlyAsTyped() {
        var savedCode: String? = null
        compose.setContent {
            KuguanTheme(darkTheme = false) {
                ConfirmPanel(
                    batch = ConfirmBatch(listOf(ConfirmRow("one", ConfirmAction.PUT,
                        "A-07", "电池", 2.0))),
                    onRowEdited = { _, location, _, _ -> savedCode = location },
                )
            }
        }
        compose.onNodeWithTag("edit-row-one").performClick()
        compose.onNodeWithText("A-07").performTextReplacement("a113")
        compose.onNodeWithTag("save-row-one").performClick()
        compose.runOnIdle { assertEquals("a113", savedCode) }
    }
}
