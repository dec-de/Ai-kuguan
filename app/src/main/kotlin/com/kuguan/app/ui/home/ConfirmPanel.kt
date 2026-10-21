package com.kuguan.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kuguan.app.domain.normalize.LocationCodeNormalizer
import com.kuguan.app.ui.theme.SemanticAction
import com.kuguan.app.ui.theme.colorFor

/**
 * 动作语义（业务层）。与 [SemanticAction]（配色层）刻意分开：
 * 业务只知道「用户说了放入还是取出」，配色才知道要显示什么颜色。
 */
enum class ConfirmAction {
    PUT,
    TAKE,
}

/**
 * 一行里可以被用户直接改的三个字段。
 *
 * [Action] 没有输入框（放入/取出不适合打字），它只参与「把这一行标红」——
 * 动作存疑时整行转红并附一句说明，这一点在 M1 之前不会被真正触发。
 */
enum class Field(val label: String) {
    Location("编号"),
    Item("物品"),
    Quantity("数量"),
    Action("动作"),
}

/**
 * 核对屏的一行 = 库里的一条操作。单条录入就是「一批只有一行」的退化情形 ——
 * 刻意**不**保留两套状态模型，否则早晚出现「改了批量忘了改单条」的回归。
 *
 * ## 2026-10-18 改版：从「撤销/重录」改为「就地编辑 + 覆盖」
 *
 * 旧模型给了三个出口（撤销 / 全部撤销 / 重新录入），每个都要用户先判断
 * 「我这是要撤还是要改」，而绝大多数情况答案就是「改一下」。选项越多越犹豫，
 * 现在点每行的编辑按钮再修改，保存后覆盖库里那条。
 *
 * 字段全部可编辑，与「大模型有没有把握」无关 —— 有把握时用户可能只是想换个说法，
 * 没把握时那是唯一的纠正入口，不该出现两套界面。
 */
data class ConfirmRow(
    /**
     * UI 稳定标识。修改靠它指名是哪一行，**不能用列表下标** ——
     * 行顺序一旦变化，用户第二次点就点到了别的行。
     */
    val key: String,
    val action: ConfirmAction,
    val locationCode: String,
    val itemName: String,
    val quantity: Double?,
    /**
     * 大模型没把握的字段。只影响**那一格的边框颜色**，不影响能不能编辑 ——
     * 能编辑是常态，转红是「多看一眼」的提示。
     */
    val uncertainFields: Set<Field> = emptySet(),
    /**
     * 已落库那条操作的 seq。**非空 = 已经写进库了**，
     * 此时编辑走「撤销旧行 + 重写新行」（[com.kuguan.app.data.repo.StorageRepository.restateRow]），
     * 为空则表示这条还没写，编辑等于补写。
     */
    val committedSeq: Long? = null,
    /** 这一行被就地改过。用于批尾「N 条刚改过」的提示，让用户确认覆盖真的发生了。 */
    val corrected: Boolean = false,
) {
    /** 这一行有字段需要用户多看一眼。 */
    val needsConfirmation: Boolean get() = uncertainFields.isNotEmpty()

    /** 该字段要不要转红。 */
    fun isFlagged(field: Field): Boolean = field in uncertainFields

    /** 这一行是不是已经写进库了。 */
    val committed: Boolean get() = committedSeq != null

    /**
     * 这一行该用哪种语义色。**行级判定** —— 8 行里 1 行存疑时只红那一行，
     * 因为其余 7 行确实已经写进库了，整卡转红等于撒谎。
     */
    val semanticAction: SemanticAction
        get() = when {
            needsConfirmation -> SemanticAction.NeedsConfirm
            action == ConfirmAction.PUT -> SemanticAction.Put
            else -> SemanticAction.Take
        }
}

/**
 * 核对屏数据 —— 一次语音录入的结果，可能是 1 行，也可能是 N 行。
 *
 * 它承担「零确认」带来的全部风险 —— 用视觉替代交互来兜错。
 */
data class ConfirmBatch(
    val rows: List<ConfirmRow>,
    /** 批量录入的批次号。单条为 null。 */
    val batchId: String? = null,
    /** 触发本次显示的原始语音文本，用于「听到了什么」的回显。整批共享一句。 */
    val heardText: String? = null,
    /** 整批都没听出结构（一条都没拆出来）。 */
    val unparsed: Boolean = false,
    /**
     * 操作被拒时的说明。非空时在批尾显示。
     *
     * 「点了却什么都没发生」是这类界面最伤人的失败方式 ——
     * 用户会以为 App 坏了，或者以为自己点错了。被拒必须给出**原因**。
     */
    val notice: String? = null,
) {
    /** 已经写进库的行数。 */
    val committedCount: Int get() = rows.count { it.committed }

    /** 还没写进库的行数（缺数量、或被写库闸门拒了）。 */
    val pendingCount: Int get() = rows.count { !it.committed }

    /** 被就地改过的行数。 */
    val correctedCount: Int get() = rows.count { it.corrected }

    /** 有没有任意一行需要用户多看一眼。 */
    val anyFlagged: Boolean get() = rows.any { it.needsConfirmation }

    /** 一条都没写库 —— 此时「取消这次」才是纯丢弃，点了不会留下半条记录。 */
    val canDiscard: Boolean get() = committedCount == 0

    /** 批内动作是否一致（一致才在批头写「放入 / 取出」）。 */
    val uniformAction: ConfirmAction?
        get() = rows.map { it.action }.distinct().singleOrNull()

    /**
     * 批头的语义色。
     *
     * 故意**不看单行的存疑**：行级存疑时其余行已写库，批头转红会让人以为整批都没记上。
     * 只有「整批都没写库且确实有存疑」才让批头转红。
     */
    val headerAction: SemanticAction
        get() = when {
            unparsed || rows.isEmpty() -> SemanticAction.NeedsConfirm
            committedCount == 0 && anyFlagged -> SemanticAction.NeedsConfirm
            else -> when (rows.firstOrNull { it.committed }?.action ?: rows.first().action) {
                ConfirmAction.PUT -> SemanticAction.Put
                ConfirmAction.TAKE -> SemanticAction.Take
            }
        }

    /** 批头标题，如「放入 · 6 项」。条数是批量场景最有效的核对信号。 */
    fun headerTitle(): String {
        val count = " ${rows.size} 项"
        return when (uniformAction) {
            ConfirmAction.PUT -> "放入 ·$count"
            ConfirmAction.TAKE -> "取出 ·$count"
            null -> "共$count"
        }
    }
}

/**
 * 数量输入的校验与清洗规则。
 *
 * 抽成独立对象而不是写在 Composable 里，有两个原因：
 * 1. 这些规则**决定写不进库**，属于安全边界，必须能用普通单测锁死；
 * 2. 写库路径（[com.kuguan.app.data.repo.StorageRepository.put]）不认 UI，
 *    将来若有别的入口（比如 M2 的 LLM 层或 Wear OS）也要走同一套校验。
 *
 * ⚠️ **两个函数的契约刻意不同，这是最容易写错的地方**：
 * - [sanitize] 管**显示**，宽容：输入「1.5」显示为「15」（用户还在编辑，不该打断他）
 * - [parse] 管**写库**，严格：输入「1.5」直接判非法，绝不悄悄变成「15」
 *
 * 若让 parse 复用 sanitize，「1.5」会被静默写成 15 —— 那是**替用户猜了一个他没说的数字**，
 * 正是本项目「零确认」原则最不能容忍的事。
 */
object QuantityInputRule {

    /**
     * 只收 1~99 的整数。
     *
     * 为什么卡得这么紧：
     * - **0 无意义** —— 存了 0 节电池会让「取出」的逻辑失去参照（取出量恒 ≥0，
     *   0 意味着这个位置没东西，那就不该有这条记录）。
     * - **小数超出当前阶段** —— 「1.5 个」在物品世界里少见，
     *   且会与「位置级数量」的整数语义打架（累加后可能出现 0.5 这种值）。
     * - **三位数以上几乎不出现** —— 收纳场景里「一百节电池」不真实。
     *
     * 宁可让用户重说，也不要在库里留下一个语义暧昧的数量。
     *
     * ⚠️ 这个上限只管**手输**。语音/LLM 路径的上限是 `MAX_QUANTITY`（999）——
     * 批量首录会出现「200 颗螺丝」。两处不等是刻意的：手输的截断发生在**可见的输入框里**，
     * 用户按确认前一定看得到自己输了什么，不构成静默改写。
     */
    const val MIN = 1
    const val MAX = 99

    /**
     * 显示层清洗：全角转半角、剔除非数字、限长 2 位。
     *
     * 全角转换是必需的：`Char.isDigit()` 对全角「１」返回 true，
     * 不转换的话「１２」能过 filter 却让 `toIntOrNull()` 解析失败，
     * 用户表现为「输了数字但不生效」。用户用全角键盘就会踩到。
     */
    fun sanitize(input: String): String =
        input
            .map { toHalfWidth(it) }
            .filter { it.isDigit() }
            .joinToString("")
            .take(2)

    private fun toHalfWidth(c: Char): Char =
        if (c.code in 0xFF10..0xFF19) (c.code - 0xFEE0).toChar() else c

    /**
     * 严格判定：**不做任何静默清洗**。
     *
     * 一旦放过「100」，用户改完就会写进 10（三位数的 100 被截成 10），
     * 那也是替用户猜了个他没说的数字。
     * 全角数字在这里放行（属于纯输入形式差异），但**长度与小数点一律按原样判**。
     */
    fun isConfirmable(input: String): Boolean = strictInt(input) != null

    /**
     * 写库入口的最终闸门。校验不通过返回 null —— 调用方据此**不写库**。
     *
     * 刻意**不做静默纠正**：「1.5」绝不能变成「15」。
     * 全角数字是唯一允许的归一（纯输入形式差异，不是数值差异）。
     */
    fun parse(input: String): Double? = strictInt(input)?.toDouble()

    /**
     * 严格解析的共同实现。
     *
     * 「严格」指：只允许半角化后的纯数字串，**长度与数值都必须落在 [MIN]..[MAX]**。
     * 一旦这里放水，[isConfirmable] 与 [parse] 会同时失效 ——
     * 它们是同一个安全边界的前后两道门，必须共用同一套判定。
     */
    private fun strictInt(input: String): Int? {
        val normalized = input.map { toHalfWidth(it) }.joinToString("")
        if (normalized.isEmpty()) return null
        if (!normalized.all { it in '0'..'9' }) return null
        // 长度先卡：三「100」不该走到数值比较（否则 100 会被截断解析）
        if (normalized.length > MAX.toString().length) return null
        val n = normalized.toIntOrNull() ?: return null
        return if (n in MIN..MAX) n else null
    }
}

/**
 * 三列宽度 + 右侧删除位。
 *
 * 这台机器是 1440px / density 3.5 → **可用宽度只有 411dp**（比常见的 480dp 窄一截），
 * 卡片再去掉 16+16 的外边距和 12+12 的内边距，一行只有 355dp 可分配。
 * 因此编号和数量都按「刚好放得下最长的值」给，剩下的全给物品名。
 *
 * ## 编号格为什么是 96dp + 18sp（2026-10-18 调）
 *
 * 原来 88dp + `titleLarge`（20sp）**放不下 `A-007`**：OutlinedTextField 左右各有
 * 16dp 内边距，88dp 只剩 56dp 可写，而 `A-007` 在 20sp 加粗下要 57dp 上下 ——
 * 末位数字被裁掉，用户看到的是 `A-00`，那正好是一个**看起来合法但错的编号**，
 * 比看不见更危险。现在：
 * - 宽度 96dp → 内容区 64dp
 * - 字号降到 18sp（[LOCATION_TEXT_STYLE]），`A-007` 约 45dp、`AA-123` 约 54dp，都留有余量
 * - 字母间距归零，数字不占两边的「字母侧边距」
 *
 * 数量格保持 56dp，但使用紧凑输入框；标准 OutlinedTextField 两侧各留 16dp，
 * 三位语音数量会被遮住。紧凑输入框两侧只留 4dp，999 也能直接看全。
 *
 * - 数量 56dp：内容区 48dp，三位数也有余量
 * - 删除位 48dp：IconButton 受 Material 最小可点尺寸约束，给了 44 也会被撑到 48，
 *   索性按 48 算 —— 销毁性按钮的点击区不该缩水
 */
private val LOCATION_CELL_WIDTH = 96.dp
private val QUANTITY_CELL_WIDTH = 56.dp
private val DELETE_CELL_WIDTH = 48.dp
private val CELL_GAP = 6.dp

/**
 * 编号格的专用字体。
 *
 * 比同排的文字小一号（18sp vs 20sp），换来「五到六字符编号完整显示」。
 * 编号是**核对时最需要一眼读全**的字段 —— 裁掉末位等于把一个错编号
 * 伪装成一个对编号，所以宁可牺牲一点字号，也不让它被截断。
 *
 * `lineHeight` 刻意与 [MaterialTheme.typography.titleLarge] 保持一致（28sp）：
 * 行高决定输入框高度，改成 24sp 会让编号格比旁边两格矮 3dp，
 * 三个框在一条线上却不一样高 —— 那是排版上最显眼的「不对劲」。
 */
private val LOCATION_TEXT_STYLE = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Bold,
    fontSize = 18.sp,
    lineHeight = 28.sp,
    letterSpacing = 0.sp,
)

/**
 * 核对屏。
 *
 * 结构：**批头（固定不滚）+ 三列表格（可滚）+ 批尾状态**。
 *
 * ## 需要修改时按需进入编辑
 *
 * 每一行的编号 / 物品 / 数量默认是文本，点编辑后才变成输入框，
 * 与大模型有没有把握无关。用户核对完觉得没问题，什么都不用做；
 * 觉得有问题，就在那一格里打几个字。
 * 改完立刻覆盖库里那条（已写库的走「撤销旧行 + 重写新行」，没写库的直接补写）。
 *
 * 另外两条**删除**入口，专门处理「这一行根本不该存在」——
 * 那是编辑做不到的（没法把一个不存在的东西改成正确的东西）：
 * - 每行最右侧的红色垃圾桶：删这一条
 * - 卡片最底部的红色「全部撤销」：清掉整句
 *
 * 两条入口都**先弹确认框**（2026-10-18 起）：垃圾桶确认「删哪一条」（带上名字和编号），
 * 「全部撤销」确认「撤几条、丢几条」。删除是唯一不可用输入框补救的动作，
 * 值不值得多一次点击，这一处值得。
 *
 * 为什么没有「撤销 / 重新录入」：那是两个需要先做判断才能用的按钮 ——
 * 「我这是要撤还是要改」。改错内容进入编辑模式；要删则在编辑模式点垃圾桶。
 *
 * ## 红色只圈那一格
 *
 * 大模型没把握的字段，那一格的**边框转红**。这比整卡转红精确：
 * 8 行里只有 1 行数量没听清时，整卡转红等于说「这 8 条都没记上」。
 *
 * ## 为什么不做自动淡出（这是刻意的，不是漏了）
 *
 * 自动淡出会把「用户还没来得及发现的错误」一起抹掉。
 * 核对屏是零确认唯一的兜错手段，多停留一会儿没有代价，早消失一秒就可能让错记录留在库里。
 *
 * **方向箭头是色觉冗余**（决策 #6）：绿/蓝之外再加「↓放入 / ↑取出」，
 * 红绿色觉障碍者靠箭头也能分辨。
 *
 * @param onRowEdited 某一行被改完并校验通过：编号 / 物品 / 数量三个值一起交出去
 * @param onRowDeleted 某一行的垃圾桶被点：删掉这一条（已写库的连带撤销）
 * @param onDeleteAll 底部的「全部撤销」被点：清掉整批
 * @param onCancel 取消这次（只在一条都没写库时出现，与「全部撤销」不同：它不涉及库里数据）
 */
@Composable
fun ConfirmPanel(
    batch: ConfirmBatch?,
    modifier: Modifier = Modifier,
    onRowEdited: (key: String, locationCode: String, itemName: String, quantity: Double) -> Unit =
        { _, _, _, _ -> },
    onRowDeleted: (key: String) -> Unit = {},
    onDeleteAll: () -> Unit = {},
    onCancel: () -> Unit = {},
    retryAvailable: Boolean = false,
    retryEnabled: Boolean = true,
    onRetry: () -> Unit = {},
) {
    AnimatedVisibility(
        visible = batch != null,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(300)),
        modifier = modifier,
    ) {
        batch?.let {
            ConfirmCard(
                batch = it,
                onRowEdited = onRowEdited,
                onRowDeleted = onRowDeleted,
                onDeleteAll = onDeleteAll,
                onCancel = onCancel,
                retryAvailable = retryAvailable,
                retryEnabled = retryEnabled,
                onRetry = onRetry,
            )
        }
    }
}

@Composable
private fun ConfirmCard(
    batch: ConfirmBatch,
    onRowEdited: (String, String, String, Double) -> Unit,
    onRowDeleted: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onCancel: () -> Unit,
    retryAvailable: Boolean,
    retryEnabled: Boolean,
    onRetry: () -> Unit,
) {
    val accent = batchAccent(batch)
    val bg = accent.copy(alpha = 0.08f)
    val showRowAction = batch.uniformAction == null
    val listState = rememberLazyListState()

    // ── 两个销毁性动作的「待确认」状态（2026-10-18 加）──
    //
    // 只存 key / flag，不存行对象本身：行数据在确认期间可能已经变了
    // （比如被编辑过），确认框每次都按 key 从最新的 batch 里重新取，
    // 取不到就自动不显示 —— 避免拿一个已经不存在的行去弹框。
    var pendingDeleteKey by remember(batch.batchId, batch.rows.size) { mutableStateOf<String?>(null) }
    var askingDeleteAll by remember(batch.batchId, batch.rows.size) { mutableStateOf(false) }
    var editingKey by remember(batch.batchId) { mutableStateOf<String?>(null) }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 横向内边距 12 而不是 16：这台机只有 411dp 宽，四个格子在 355dp 里排，
                // 8dp 的空间直接变成物品名的可用宽度
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BatchHeader(batch = batch, retryAvailable = retryAvailable)

            if (batch.rows.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                ColumnCaptions()
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    // fill=false：行少时不要被拉成一整片空白
                    modifier = Modifier.weight(1f, fill = false).testTag("confirm-list"),
                    state = listState,
                ) {
                    items(batch.rows, key = { it.key }, contentType = { "confirm-row" }) { row ->
                        EditRow(
                            row = row,
                            showAction = showRowAction,
                            editing = editingKey == row.key,
                            onEdit = { editingKey = row.key },
                            onFinishEditing = { editingKey = null },
                            onCommit = { location, itemName, quantity ->
                                onRowEdited(row.key, location, itemName, quantity)
                            },
                            // 垃圾桶不再直接删 —— 先挂起，等确认框点了「删除」才真删
                            onDelete = { pendingDeleteKey = row.key },
                        )
                    }
                }
            }

            BatchFooter(
                batch = batch,
                onDeleteAll = { askingDeleteAll = true },
                onCancel = onCancel,
                retryAvailable = retryAvailable,
                retryEnabled = retryEnabled,
                onRetry = onRetry,
            )
        }
    }

    // ── 二次确认（2026-10-18 Owen 要求）──
    //
    // 之前靠「实心红 + 压最底部」防误触，但那只能防「手滑点到」，
    // 防不住「点对了但点早了」：说完一句话屏幕刚出来，用户还在看第一条，
    // 手指已经在底部的红按钮上了。删掉的是整句 8 条，代价和一次点击不成比例。
    // 现在两个销毁入口都要过一次确认，且确认框里写清**具体会删几条、撤几条**。

    pendingDeleteKey?.let { key ->
        val row = batch.rows.firstOrNull { it.key == key }
        if (row != null) {
            DestructiveConfirmDialog(
                title = "删掉这一条？",
                message = buildString {
                    append("「${row.itemName}」")
                    if (row.locationCode.isNotBlank()) append(" · ${row.locationCode}")
                    row.quantity?.let { append(" · ${quantityToText(it)} 个") }
                    append("\n\n")
                    append(
                        if (row.committed) {
                            "已写进库，删掉会连带撤销（历史页仍能查到原始记录）。"
                        } else {
                            "这条还没写进库，删掉就是丢掉这次识别结果。"
                        }
                    )
                },
                confirmLabel = "删除",
                onConfirm = {
                    pendingDeleteKey = null
                    onRowDeleted(row.key)
                },
                onDismiss = { pendingDeleteKey = null },
            )
        }
    }

    if (askingDeleteAll) {
        val written = batch.committedCount
        val pending = batch.pendingCount
        DestructiveConfirmDialog(
            title = "撤销这一整批？",
            message = buildString {
                if (written > 0) append("已记录的 $written 条会全部撤销，库里不再计入。")
                if (written > 0 && pending > 0) append("\n")
                if (pending > 0) append("还没写的 $pending 条直接丢掉。")
                append("\n\n撤销不销毁数据，历史页仍能查到。")
            },
            confirmLabel = "全部撤销",
            onConfirm = {
                askingDeleteAll = false
                onDeleteAll()
            },
            onDismiss = { askingDeleteAll = false },
        )
    }
}

/**
 * 销毁性动作的确认框。
 *
 * 刻意**不使用**「确定 / 取消」这种对称按钮：确认键写具体动作（「删除」/「全部撤销」），
 * 让用户读的是「我要做什么」而不是「我点左边还是右边」。
 * 取消放在次要位置（TextButton、无填充），且不做成默认焦点样式 ——
 * 误点确认的代价远大于误点取消。
 *
 * 确认键用 error 实心色，和卡片上那两个红色入口是同一个颜色，
 * 视觉上延续「这是同一类动作」的线索。
 */
@Composable
private fun DestructiveConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp),
            )
        },
        title = {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(text = confirmLabel, style = MaterialTheme.typography.bodyLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "取消",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

/**
 * 列标题 + 一句操作提示。
 *
 * 提示只有一行，但它决定了用户会不会去点那些框 ——
 * 「看起来像输入框」和「知道点进去能改」是两件事，后者必须说出来。
 */
@Composable
private fun ColumnCaptions() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
        verticalAlignment = Alignment.Bottom,
    ) {
        Caption("抽屉编号", Modifier.width(LOCATION_CELL_WIDTH))
        Caption("物品名称", Modifier.weight(1f))
        Caption("数量", Modifier.width(QUANTITY_CELL_WIDTH), TextAlign.Center)
        // 删除位留空，只为了和下面的行左对齐
        Spacer(Modifier.width(DELETE_CELL_WIDTH))
    }
}

/**
 * 行尾的红色垃圾桶。
 *
 * 它删的是**整条记录**，不是某一格 —— 所以它和「把这一格改对」是两个不同的动作：
 * 编辑负责「听错了」，删除负责「根本没这个东西」。
 *
 * 一直显示、一直红：可以删这件事本身不需要提示，
 * 需要提示的是「删了会怎样」—— 已写库的会连带撤销，未写库的直接消失。
 * 那句提示现在放在**点它之后**的确认框里，而不是靠按钮颜色传达：
 * 颜色只能表达「危险」，说不清「删的是哪一条」。
 */
@Composable
private fun DeleteCell(onDelete: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onDelete, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = "删掉这一条（会先确认）",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun Caption(text: String, modifier: Modifier, align: TextAlign = TextAlign.Start) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        modifier = modifier,
    )
}

/**
 * 批头：动作 + **条数** + 回显。
 *
 * 条数是批量场景最有效的核对信号：用户说了 8 样，屏幕上写「6 项」，
 * 漏拆、多拆立刻暴露 —— 这比逐行看名字快得多，也是站着瞥一眼唯一来得及读的信息。
 */
@Composable
private fun BatchHeader(batch: ConfirmBatch, retryAvailable: Boolean) {
    val accent = batchAccent(batch)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            batch.uniformAction?.let { action ->
                Icon(
                    imageVector = if (action == ConfirmAction.TAKE) Icons.Filled.ArrowUpward
                        else Icons.Filled.ArrowDownward,
                    contentDescription = if (action == ConfirmAction.TAKE) "取出" else "放入",
                    tint = accent,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.size(6.dp))
            }
            Text(
                text = when {
                    batch.unparsed && retryAvailable -> "意图识别失败"
                    batch.unparsed -> "没听清"
                    batch.rows.isEmpty() -> "共 0 项"
                    else -> batch.headerTitle()
                },
                style = MaterialTheme.typography.headlineMedium,
                color = accent,
            )
        }

        if (batch.unparsed) {
            Text(
                text = if (retryAvailable) "文稿已保留，可直接重试" else "没拆出条目，重说一次更省事",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun batchAccent(batch: ConfirmBatch): Color =
    if (batch.rows.isNotEmpty() && batch.uniformAction == null) {
        // 混合批次既有放入又有取出；批头不能冒充单一动作色。
        MaterialTheme.colorScheme.onSurface
    } else {
        colorFor(batch.headerAction)
    }

/**
 * 一行物品：默认是轻量文本，点编辑后才创建三个输入框。
 *
 * ## 为什么三列而不是「名字 + 数量」
 *
 * 用户核对的顺序就是「哪个柜子、什么东西、几个」，
 * 三列左对齐之后，**同列的值上下对比**比读句子快得多 ——
 * 8 行里找「哪个数量不对」，竖着扫一眼数量列就够。
 *
 * ## 提交时机：保存按钮或输入法完成键
 *
 * 不做「改一个字就写一次库」：那样每敲一下都产生一条撤销+重写的历史记录。
 * 所以本地先存着，编辑完成时校验一次、整行提交一次。
 * 校验不过（编号不像编号 / 物品名空 / 数量不是 1~99）**不提交**，
 * 边框转红并给出原因，输入的内容原样留着等人改 —— 绝不替用户修正。
 */
@Composable
private fun EditRow(
    row: ConfirmRow,
    showAction: Boolean,
    editing: Boolean,
    onEdit: () -> Unit,
    onFinishEditing: () -> Unit,
    onCommit: (locationCode: String, itemName: String, quantity: Double) -> Unit,
    onDelete: () -> Unit,
) {
    val accent = colorFor(row.semanticAction)
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // 三个字段各自的编辑态。key 里带上库里当前值：一旦提交成功、行数据变了，
    // 这里会自动重置成新值，不需要额外的同步逻辑。
    var location by remember(row.key, row.locationCode) { mutableStateOf(row.locationCode) }
    var itemName by remember(row.key, row.itemName) { mutableStateOf(row.itemName) }
    var quantityText by remember(row.key, row.quantity) { mutableStateOf(quantityToText(row.quantity)) }

    // 上一次提交被哪一格挡下了。为空 = 没错。
    var errorField by remember(row.key) { mutableStateOf<Field?>(null) }

    fun commit() {
        val code = location.trim()
        if (!LocationCodeNormalizer.looksLikeLocationCode(code)) {
            errorField = Field.Location
            return
        }
        val name = itemName.trim()
        if (name.isEmpty()) {
            errorField = Field.Item
            return
        }
        val quantity = QuantityInputRule.parse(quantityText)
        if (quantity == null) {
            errorField = Field.Quantity
            return
        }
        errorField = null
        location = code

        // 原值不变时直接退出，不为一次查看生成撤销+重写的历史。
        if (code != row.locationCode || name != row.itemName || quantity != row.quantity) {
            onCommit(code, name, quantity)
        }
        keyboard?.hide()
        focusManager.clearFocus()
        onFinishEditing()
    }

    val hasFieldFlag =
        row.isFlagged(Field.Location) || row.isFlagged(Field.Item) || row.isFlagged(Field.Quantity)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (row.needsConfirmation) {
                    Modifier.background(accent.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 2.dp, vertical = 2.dp),
    ) {
        if (showAction) {
            Text(
                text = if (row.action == ConfirmAction.PUT) "↓ 放入" else "↑ 取出",
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
        ) {
            if (editing) {
                EditableCell(
                    value = location,
                    onValueChange = { location = it },
                    onCommit = ::commit,
                    modifier = Modifier.width(LOCATION_CELL_WIDTH),
                    flagged = row.isFlagged(Field.Location),
                    invalid = errorField == Field.Location,
                    keyboardType = KeyboardType.Text,
                    textStyle = LOCATION_TEXT_STYLE,
                )
                EditableCell(
                    value = itemName,
                    onValueChange = { itemName = it },
                    onCommit = ::commit,
                    modifier = Modifier.weight(1f),
                    flagged = row.isFlagged(Field.Item),
                    invalid = errorField == Field.Item,
                )
                EditableCell(
                    value = quantityText,
                    onValueChange = { quantityText = QuantityInputRule.sanitize(it) },
                    onCommit = ::commit,
                    modifier = Modifier.width(QUANTITY_CELL_WIDTH),
                    flagged = row.isFlagged(Field.Quantity) || row.quantity == null,
                    invalid = errorField == Field.Quantity,
                    keyboardType = KeyboardType.Number,
                    textAlign = TextAlign.Center,
                    compact = true,
                )
                DeleteCell(onDelete = onDelete, modifier = Modifier.width(DELETE_CELL_WIDTH))
            } else {
                ReadOnlyCell(
                    value = row.locationCode,
                    modifier = Modifier.width(LOCATION_CELL_WIDTH),
                    flagged = row.isFlagged(Field.Location),
                    textStyle = LOCATION_TEXT_STYLE,
                )
                ReadOnlyCell(
                    value = row.itemName,
                    modifier = Modifier.weight(1f),
                    flagged = row.isFlagged(Field.Item),
                )
                ReadOnlyCell(
                    value = quantityToText(row.quantity),
                    modifier = Modifier.width(QUANTITY_CELL_WIDTH),
                    flagged = row.isFlagged(Field.Quantity) || row.quantity == null,
                    textAlign = TextAlign.Center,
                )
                IconButton(
                    onClick = {
                        location = row.locationCode
                        itemName = row.itemName
                        quantityText = quantityToText(row.quantity)
                        errorField = null
                        onEdit()
                    },
                    modifier = Modifier.width(DELETE_CELL_WIDTH).testTag("edit-row-${row.key}"),
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑${row.itemName}")
                }
            }
        }

        if (editing) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    errorField = null
                    keyboard?.hide()
                    focusManager.clearFocus()
                    onFinishEditing()
                }) { Text("取消编辑") }
                Button(onClick = ::commit, modifier = Modifier.testTag("save-row-${row.key}")) {
                    Text("保存")
                }
            }
        }

        errorField?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                text = errorText(it),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        // 动作没有输入框 —— 但它存在疑时必须说清楚疑的是什么，
        // 否则用户只看到一行红的、却不知道哪儿不对。
        if (row.isFlagged(Field.Action) && !hasFieldFlag) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "动作没听清：是放入还是取出？",
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

private fun errorText(field: Field): String = when (field) {
    Field.Location -> "编号像 A-07 或 A07（字母 + 数字）"
    Field.Item -> "物品名不能空着"
    Field.Quantity -> "数量填 1~99 的数字"
    Field.Action -> ""
}

/** 数量显示成整数，除非确实带小数（避免「2.0」这种别扭写法）。 */
private fun quantityToText(quantity: Double?): String = quantity?.let {
    if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString()
} ?: ""

@Composable
private fun ReadOnlyCell(
    value: String,
    modifier: Modifier,
    flagged: Boolean,
    textAlign: TextAlign = TextAlign.Start,
    textStyle: TextStyle = MaterialTheme.typography.titleLarge,
) {
    val warning = colorFor(SemanticAction.NeedsConfirm)
    Box(
        modifier = modifier
            .height(56.dp)
            .background(
                if (flagged) warning.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(12.dp),
            )
            .then(if (flagged) Modifier.border(1.dp, warning, RoundedCornerShape(12.dp)) else Modifier)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = value.ifBlank { "待填" },
            style = textStyle,
            color = if (flagged) warning else MaterialTheme.colorScheme.onSurface,
            textAlign = textAlign,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 编辑模式下的输入格。普通滚动只组合 [ReadOnlyCell]，避免每行三个输入控件。
 *
 * 视觉上必须一眼看出「这里能打字」，所以用了 OutlinedTextField 的实心底色 +
 * 可见边框，而不是一段带下划线的文字 —— 后者在核对屏的大字号里会被当成纯展示。
 *
 * 边框颜色三种状态，优先级从高到低：
 * - **错误红**（刚提交失败）— 说明你输的东西不合规，必须改
 * - **存疑红**（大模型没把握）— 说明这一格值得多看一眼，内容本身是合法的
 * - **普通描边** — 正常态
 *
 * 存疑与错误的红刻意用不同颜色（语义红 vs Material error 红）：
 * 前者是「请核实」，后者是「这个值不能存」。混成一个颜色会让用户
 * 以为合法的值也不合法，反复重输。
 *
 * 回车可保存整行；切换焦点只修改本地草稿，须点保存或按完成键才落库。
 */
@Composable
private fun EditableCell(
    value: String,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    flagged: Boolean = false,
    invalid: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    textAlign: TextAlign = TextAlign.Start,
    textStyle: TextStyle = MaterialTheme.typography.titleLarge,
    compact: Boolean = false,
) {
    val checkColor = colorFor(SemanticAction.NeedsConfirm)
    val border = when {
        invalid -> MaterialTheme.colorScheme.error
        flagged -> checkColor
        else -> MaterialTheme.colorScheme.outline
    }
    val container = when {
        invalid -> MaterialTheme.colorScheme.errorContainer
        flagged -> checkColor.copy(alpha = 0.10f)
        else -> MaterialTheme.colorScheme.surface
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val inputStyle = textStyle.copy(
        color = if (flagged) checkColor else MaterialTheme.colorScheme.onSurface,
        textAlign = textAlign,
    )
    val inputOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done)
    val inputActions = KeyboardActions(onDone = {
        keyboard?.hide()
        focusManager.clearFocus()
        onCommit()
    })

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.height(56.dp).then(if (compact) Modifier.testTag("quantity-input") else Modifier),
        singleLine = true,
        textStyle = inputStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = inputOptions,
        keyboardActions = inputActions,
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(container, RoundedCornerShape(12.dp))
                    .border(1.dp, border, RoundedCornerShape(12.dp))
                    .padding(horizontal = if (compact) 4.dp else 8.dp),
                contentAlignment = Alignment.Center,
            ) { innerTextField() }
        },
    )
}

/**
 * 批尾：一句状态 + 一个出口。
 *
 * 「已记录 N 条」这句不能省：它同时回答两个问题 — 数据进库了吗（进了）、
 * 我在框里改的东西会去哪（覆盖它）。用户不确认这一句就不敢走开。
 */
@Composable
private fun BatchFooter(
    batch: ConfirmBatch,
    onDeleteAll: () -> Unit,
    onCancel: () -> Unit,
    retryAvailable: Boolean,
    retryEnabled: Boolean,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = statusText(batch),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (batch.rows.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "点每条右侧的编辑按钮修改",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        batch.notice?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge,
                color = colorFor(SemanticAction.NeedsConfirm),
                textAlign = TextAlign.Center,
            )
        }

        if (retryAvailable && batch.unparsed && batch.committedCount == 0 &&
            !batch.heardText.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Button(onClick = onRetry, enabled = retryEnabled,
                modifier = Modifier.testTag("retry-llm-button")) {
                Text("用这份文稿重试识别")
            }
        }

        if (batch.canDiscard) {
            // 一条都没写库时，「撤销」这个词是假的（库里没有可撤销的东西），
            // 所以这里只给一个不显眼的「取消这次」；存在已写库的行才出现红色按钮。
            TextButton(onClick = onCancel, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    text = "取消这次",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Spacer(Modifier.height(10.dp))
            DeleteAllButton(onClick = onDeleteAll)
        }
    }
}

/**
 * 卡片最底部的「全部撤销」。
 *
 * ## 为什么它是唯一一个实心红按钮
 *
 * 全 App 只有这一处是**销毁性**操作：一次点掉整句 8 条。
 * 别的按钮改的是「值」，它改的是「有没有」。所以它必须长得和别人不一样 ——
 * 实心红、带垃圾桶、占满整行、压在卡片最底部（离输入框最远，不会被顺手点到）。
 *
 * ## 为什么现在**要**二次确认（2026-10-18 改）
 *
 * 早先的取舍是不做确认：确认框会把「我确定」变成一个可以条件反射点掉的按钮，
 * 却让每次真心的撤销都多一步。真机上跑下来这条判错了 ——
 * 卡片刚出现的那一秒，用户眼睛还在第一行，手指已经能落到底部的红条上；
 * 「位置远」防的是手滑，防不住**时机**。而一次误点的代价是整句 8 条被撤。
 *
 * 现在的做法是两者都要：按钮仍然醒目且远，另加一道确认框，
 * 框里写清「撤几条、丢几条」—— 确认框的价值不在那一次点击，
 * 而在于它逼用户读一眼数量。数量正是这类操作里唯一需要核对的东西。
 *
 * 撤销本身不销毁数据（只打 `revoked_at`），所以确认框的语气是「说明」而非「恐吓」。
 */
@Composable
private fun DeleteAllButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = "全部撤销",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun statusText(batch: ConfirmBatch): String {
    if (batch.unparsed) return "这条没记进去"
    val parts = mutableListOf<String>()
    if (batch.committedCount > 0) parts += "已记录 ${batch.committedCount} 条"
    if (batch.pendingCount > 0) parts += "${batch.pendingCount} 条待填"
    if (batch.correctedCount > 0) parts += "${batch.correctedCount} 条刚改过"
    return if (parts.isEmpty()) "这条没记进去" else parts.joinToString(" · ")
}

/**
 * 下半部分：长按说话按钮。
 *
 * 刻意做成**大圆角矩形**而非圆形（Owen 2026-10-17 意见）：
 * - 横向可点区域大，误触/漏触都更少
 * - 位置压到屏幕下部，大拇指自然下垂即可够到，不用改变握持姿势
 *
 * ## 2026-10-18：从 `Card(onClick)` 改成真正的**按下 / 抬起**
 *
 * 老版本是点击触发，语音链路接进来之后这个交互就不成立了 ——
 * 录音必须知道「什么时候开始」和「什么时候结束」，点击只能给出一个时刻。
 * 按下开始录音，上滑达到阈值后松手取消；手势中断也取消。
 */
@Composable
fun HoldToTalkButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    recording: Boolean = false,
    processing: Boolean = false,
    label: String = "按住说话",
    onPressStart: () -> Unit = {},
    onPressEnd: () -> Unit = {},
    onPressCancel: () -> Unit = {},
) {
    var cancelArmed by remember { mutableStateOf(false) }
    val currentStart by rememberUpdatedState(onPressStart)
    val currentEnd by rememberUpdatedState(onPressEnd)
    val currentCancel by rememberUpdatedState(onPressCancel)
    val container = when {
        processing -> MaterialTheme.colorScheme.primary
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        cancelArmed -> MaterialTheme.colorScheme.error
        recording -> Color(0xFFC54B11)
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        border = if (recording && !cancelArmed) BorderStroke(3.dp, Color(0xFFFFD18A)) else null,
        modifier = modifier
            .fillMaxWidth()
            // 用 height 而非 heightIn：Card 默认会把子内容的 Box 撑满，
            // 结果按钮变成半屏高。这里钉死高度才是想要的大横条。
            .height(120.dp)
            .testTag("hold-to-talk-button")
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val cancelDistancePx = 72.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    cancelArmed = false
                    var finished = false
                    currentStart()
                    try {
                        while (!finished) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                finished = true
                                currentCancel()
                            } else if (!change.pressed) {
                                finished = true
                                if (cancelArmed) currentCancel() else currentEnd()
                                change.consume()
                            } else {
                                cancelArmed = down.position.y - change.position.y >= cancelDistancePx
                                change.consume()
                            }
                        }
                    } finally {
                        if (!finished) currentCancel()
                        cancelArmed = false
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (recording && !processing && !cancelArmed) {
                    Box(Modifier.size(14.dp).background(Color.White, CircleShape))
                    Spacer(Modifier.size(12.dp))
                }
                if (processing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp).testTag("voice-processing-indicator"),
                        color = Color.White,
                        strokeWidth = 3.dp,
                    )
                } else {
                    Icon(
                        imageVector = when {
                            cancelArmed -> Icons.Filled.Delete
                            recording -> Icons.Filled.GraphicEq
                            else -> Icons.Filled.Mic
                        },
                        contentDescription = if (cancelArmed) "松开取消" else label,
                        tint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(Modifier.size(12.dp))
                Text(
                    text = if (cancelArmed) "松开取消" else if (enabled || processing) label else "语音不可用",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = if (recording) FontWeight.Bold else FontWeight.Medium,
                    color = if (enabled || processing) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 语义色：绿=放入 / 蓝=取出 / 红=需确认。
 *
 * 定义在 `ui/theme/Theme.kt` 并通过 CompositionLocal 注入，这里不再重复定义 ——
 * 之前散落两处导致改色要动多个文件，是典型的「颜色逐渐不一致」隐患。
 */
