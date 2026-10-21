package com.kuguan.app.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kuguan.app.ui.theme.KuguanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeFeedbackUiTest {
    @get:Rule val compose = createComposeRule()

    private fun row(index: Int) = ConfirmRow(
        key = "row-$index",
        action = ConfirmAction.PUT,
        locationCode = "A-07",
        itemName = "物品${index.toString().padStart(3, '0')}",
        quantity = 2.0,
        committedSeq = index.toLong() + 1,
    )

    @Test fun transcriptDoesNotTakeSpaceInConfirmCard() {
        compose.setContent {
            KuguanTheme(darkTheme = false) {
                ConfirmPanel(ConfirmBatch(rows = listOf(row(0)), heardText = "这段识别文稿不应出现在卡片里"))
            }
        }
        compose.onNodeWithText("放入 · 1 项").assertIsDisplayed()
        compose.onNodeWithText("抽屉编号").assertIsDisplayed()
        compose.onNodeWithText("「这段识别文稿不应出现在卡片里」").assertDoesNotExist()
    }

    @Test fun waitingStateHasVisibleNativeProgressAnimation() {
        compose.setContent {
            KuguanTheme(darkTheme = false) {
                SpeechWaitingPanel("正在整理结果")
            }
        }
        compose.onNodeWithTag("speech-waiting-indicator").assertIsDisplayed()
        compose.onNodeWithText("正在整理结果").assertIsDisplayed()
    }

    @Test fun waitingReplacesPreviousCardUntilNewResultArrives() {
        val waiting = mutableStateOf<String?>(null)
        val batch = mutableStateOf<ConfirmBatch?>(ConfirmBatch(listOf(row(0))))
        compose.setContent {
            KuguanTheme(darkTheme = false) {
                ConfirmationResult(waiting.value, batch.value)
            }
        }
        compose.onNodeWithText("物品000").assertIsDisplayed()
        compose.runOnIdle {
            batch.value = null
            waiting.value = "正在识别语音"
        }
        compose.onNodeWithTag("speech-waiting-indicator").assertIsDisplayed()
        compose.onNodeWithText("物品000").assertDoesNotExist()
        compose.runOnIdle {
            batch.value = ConfirmBatch(listOf(row(1)))
            waiting.value = null
        }
        compose.onNodeWithText("物品001").assertIsDisplayed()
        compose.onNodeWithText("物品000").assertDoesNotExist()
    }

    @Test fun processingButtonShowsProgressEvenWhenInputDisabled() {
        compose.setContent {
            KuguanTheme(darkTheme = false) {
                HoldToTalkButton(enabled = false, processing = true, label = "请稍候…")
            }
        }
        compose.onNodeWithTag("voice-processing-indicator").assertIsDisplayed()
        compose.onNodeWithText("请稍候…").assertIsDisplayed()
        compose.onNodeWithText("语音不可用").assertDoesNotExist()
    }

    @Test fun failedLlmCardOffersOneTapRetryAndHidesItWhileProcessing() {
        val waiting = mutableStateOf<String?>(null)
        var clicks = 0
        compose.setContent {
            KuguanTheme(darkTheme = false) {
                ConfirmationResult(
                    waitingMessage = waiting.value,
                    batch = ConfirmBatch(emptyList(), heardText = "原文稿", unparsed = true,
                        notice = "LLM 超时，这条没记进去"),
                    retryAvailable = true,
                    onRetry = { clicks++; waiting.value = "正在重新整理文稿" },
                )
            }
        }
        compose.onNodeWithText("意图识别失败").assertIsDisplayed()
        compose.onNodeWithText("文稿已保留，可直接重试").assertIsDisplayed()
        compose.onNodeWithText("没听清").assertDoesNotExist()
        compose.onNodeWithTag("retry-llm-button").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
        compose.onNodeWithTag("retry-llm-button").assertDoesNotExist()
        compose.onNodeWithText("正在重新整理文稿").assertIsDisplayed()
    }

    @Test fun retryRequiresFailureCardAndNetwork() {
        val failed = ConfirmBatch(emptyList(), heardText = "文稿", unparsed = true)
        compose.setContent {
            KuguanTheme(darkTheme = false) {
                ConfirmationResult(null, failed, retryAvailable = true, retryEnabled = false)
            }
        }
        compose.onNodeWithTag("retry-llm-button").assertIsNotEnabled()
    }

    @Test fun largeBatchScrollsToLastStableRow() {
        var editedKey: String? = null
        compose.setContent {
            KuguanTheme(darkTheme = false) {
                ConfirmPanel(
                    batch = ConfirmBatch(rows = (0 until 60).map(::row)),
                    onRowEdited = { key, _, _, _ -> editedKey = key },
                )
            }
        }
        compose.onNodeWithTag("confirm-list").performScrollToIndex(59)
        compose.onNodeWithText("物品059").assertIsDisplayed()
        compose.onNodeWithTag("quantity-input").assertDoesNotExist()
        compose.onNodeWithTag("edit-row-row-59").performClick()
        compose.onNodeWithText("物品059").performTextReplacement("物品尾项")
        compose.onNodeWithText("物品尾项").performImeAction()
        compose.runOnIdle { assertEquals("row-59", editedKey) }
    }
}
