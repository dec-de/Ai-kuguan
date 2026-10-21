package com.kuguan.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 全 App 只有三个语义色，含义互斥、不可互换（Owen 2026-10-17 定）：
 *
 * - 🟢 **绿 = 放入**
 * - 🔵 **蓝 = 取出**
 * - 🔴 **红 = 有信息需要确认**（数量 / 物品 / 抽屉编号 / 动作任一存疑）
 *
 * ## 为什么不把「取出」做成红色（2026-10-17 的一个额外发现）
 *
 * 绿/红是**最典型的色觉障碍失败组合** —— 红绿色盲（约 8% 男性）会把两者看成一模一样的颜色，
 * 而这正是核对屏最需要区分的两个状态。改成绿/蓝之后：
 * - 蓝在红绿色盲视角下仍与绿可区分（蓝通道保留）
 * - 再叠加已有的**方向箭头**（↓放入 / ↑取出）作为形状冗余，双保险
 *
 * 红色腾出来后专表「需确认」，语义更清晰：红 = 「我没把握，你来看一眼」，
 * 而不是「取出」。这样红色变成一个**需要注意力**的信号，与告警语义一致。
 */
/**
 * 配色层面的语义动作。与业务层的 `ConfirmAction` 分开：
 * 业务只管「放入还是取出」，配色只管「显示成什么颜色」。
 *
 * [NeedsConfirm] 不是一个业务动作，而是**覆盖状态** —— 当任一字段存疑时，
 * 它会盖过业务动作的颜色（见 `ConfirmRow.semanticAction`）。
 * 注：批量录入后这是**行级**判定 —— 一批里某一行存疑时只红那一行。
 */
enum class SemanticAction {
    Put,
    Take,
    NeedsConfirm,

    /**
     * 已撤销 —— 卡片留着告诉用户「刚才那条没了」。
     *
     * 用中性灰而不是绿/蓝：那两个色都意味着「库里现在有这么一条」，
     * 而撤销过的记录已经不在库里了，继续用动作色等于撒谎。
     */
    Revoked,
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6F5C),
    onPrimary = Color.White,
    secondary = Color(0xFF4A635C),
    background = Color(0xFFF7F9F8),
    surface = Color.White,
    // Material 的 error 色保持中性红，仅用于真正的异常态（如数据库错误）。
    // 业务上的「需确认」红色走 [SemanticAction.NeedsConfirm]，两者刻意区分开。
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD3BC),
    onPrimary = Color(0xFF00382C),
    secondary = Color(0xFFB2CCC3),
    background = Color(0xFF101413),
    surface = Color(0xFF1A201E),
    error = Color(0xFFF2B8B5),
)

/**
 * 语义色集合。深浅两套，靠 [KuguanTheme] 按当前明暗主题注入。
 *
 * 单独抽成 CompositionLocal 而不是在代码里写死 `Color(...)`，是为了：
 * 1. 深色模式自动切换，调用点不用关心主题
 * 2. 三个语义色的定义**只有这一处**，避免散落到各文件后逐渐不一致
 */
data class SemanticColors(
    val put: Color,
    val take: Color,
    val needsConfirm: Color,
    val revoked: Color,
)

private val LightSemanticColors = SemanticColors(
    put = Color(0xFF1B7F3B),          // 绿：放入
    take = Color(0xFF1B5FD9),         // 蓝：取出（对比度 5.9:1，够大字号用）
    needsConfirm = Color(0xFFC5221F), // 红：需确认
    revoked = Color(0xFF5F6368),      // 中性灰：已撤销（对比度 5.9:1）
)

private val DarkSemanticColors = SemanticColors(
    put = Color(0xFF5BD07C),
    take = Color(0xFF8AB4F8),
    needsConfirm = Color(0xFFFF8A80),
    revoked = Color(0xFF9AA0A6),      // 中性灰：已撤销
)

private val LocalSemanticColors = staticCompositionLocalOf { LightSemanticColors }

/**
 * 读取当前主题下的语义色。
 *
 * 写成普通函数而不是扩展属性：扩展属性 `SemanticColors.current` 在同文件里
 * 与 `staticCompositionLocalOf` 的 `current` 容易混淆，函数形式更直白。
 */
@Composable
@ReadOnlyComposable
fun semanticColors(): SemanticColors = LocalSemanticColors.current

/** 按动作取语义色。核对屏、历史页共用同一套判定，避免两处逻辑漂移。 */
@Composable
fun colorFor(action: SemanticAction): Color {
    val colors = semanticColors()
    return when (action) {
        SemanticAction.Put -> colors.put
        SemanticAction.Take -> colors.take
        SemanticAction.NeedsConfirm -> colors.needsConfirm
        SemanticAction.Revoked -> colors.revoked
    }
}

@Composable
fun KuguanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalSemanticColors provides if (darkTheme) DarkSemanticColors else LightSemanticColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = KuguanTypography,
            content = content,
        )
    }
}