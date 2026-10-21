package com.kuguan.app.ui.home

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kuguan.app.ui.theme.KuguanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HoldToTalkCancelUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun upwardSlideCancelsAndShowsReleaseHint() {
        val recording = mutableStateOf(false)
        var ended = 0
        var cancelled = 0
        compose.setContent {
            KuguanTheme(darkTheme = false) {
                HoldToTalkButton(
                    recording = recording.value,
                    label = if (recording.value) "正在听，上滑取消" else "按住说话",
                    onPressStart = { recording.value = true },
                    onPressEnd = { ended++; recording.value = false },
                    onPressCancel = { cancelled++; recording.value = false },
                )
            }
        }
        val distance = with(compose.density) { 85.dp.toPx() }
        compose.onNodeWithTag("hold-to-talk-button").performTouchInput {
            down(Offset(center.x, height - 5f))
        }
        compose.onNodeWithText("正在听，上滑取消").assertIsDisplayed()
        compose.onNodeWithTag("hold-to-talk-button").performTouchInput {
            moveBy(Offset(0f, -distance))
        }
        compose.onNodeWithText("松开取消").assertIsDisplayed()
        compose.onNodeWithTag("hold-to-talk-button").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(0, ended)
            assertEquals(1, cancelled)
        }
    }

    @Test fun shortSlideStillEndsRecording() {
        var ended = 0
        var cancelled = 0
        compose.setContent {
            KuguanTheme(darkTheme = false) {
                HoldToTalkButton(
                    onPressEnd = { ended++ },
                    onPressCancel = { cancelled++ },
                )
            }
        }
        val distance = with(compose.density) { 30.dp.toPx() }
        compose.onNodeWithTag("hold-to-talk-button").performTouchInput {
            down(Offset(center.x, height - 5f))
            moveBy(Offset(0f, -distance))
            up()
        }
        compose.runOnIdle {
            assertEquals(1, ended)
            assertEquals(0, cancelled)
        }
    }

    @Test fun slidingBackBeforeReleaseKeepsRecording() {
        var ended = 0
        var cancelled = 0
        compose.setContent {
            KuguanTheme(darkTheme = false) {
                HoldToTalkButton(
                    onPressEnd = { ended++ },
                    onPressCancel = { cancelled++ },
                )
            }
        }
        val distance = with(compose.density) { 85.dp.toPx() }
        compose.onNodeWithTag("hold-to-talk-button").performTouchInput {
            down(Offset(center.x, height - 5f))
            moveBy(Offset(0f, -distance))
            moveBy(Offset(0f, distance))
            up()
        }
        compose.runOnIdle {
            assertEquals(1, ended)
            assertEquals(0, cancelled)
        }
    }
}
