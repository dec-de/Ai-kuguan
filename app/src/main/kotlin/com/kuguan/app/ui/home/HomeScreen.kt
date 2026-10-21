package com.kuguan.app.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kuguan.app.BuildConfig
import com.kuguan.app.data.repo.BatchRow
import com.kuguan.app.data.home.HomeResult
import com.kuguan.app.data.home.HomeResultStore
import com.kuguan.app.data.home.HomeResultSafety
import com.kuguan.app.data.repo.RevokeScope
import com.kuguan.app.data.asr.AsrReplay
import com.kuguan.app.data.repo.StorageRepository
import com.kuguan.app.data.voice.IntentTrace
import com.kuguan.app.data.voice.RetryableSpeech
import com.kuguan.app.data.voice.VoiceState
import com.kuguan.app.data.voice.VoiceTrace
import com.kuguan.app.data.voice.recordSafely
import com.kuguan.app.di.AppContainer
import com.kuguan.app.domain.voice.IntentGuards
import com.kuguan.app.domain.voice.IntentKind
import com.kuguan.app.domain.voice.IntentPlan
import com.kuguan.app.domain.voice.IntentRowPolicy
import com.kuguan.app.domain.voice.IntentSource
import com.kuguan.app.domain.voice.PlannedRow
import com.kuguan.app.domain.query.LookupResult
import com.kuguan.app.domain.query.LookupText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.text.DateFormat
import java.util.Date

/**
 * 主屏 —— 上下分栏（Owen 2026-10-17 定的布局）。
 *
 * ```
 * ┌───────────────────────────┐
 * │  收纳助手          🔍      │  标题栏
 * ├───────────────────────────┤
 * │  核对 / 确认信息区        │  上半：批头（动作+条数+编号）
 * │                           │        + 行列表（可滚）
 * ├───────────────────────────┤
 * │  ┌─────────────────────┐  │  下半：大圆角矩形长按按钮
 * │  │    按住说话        │  │       压到屏幕下部，大拇指自然够到
 * │  └─────────────────────┘  │
 * ├───────────────────────────┤
 * │  说话    搜索    历史     │  底部导航
 * └───────────────────────────┘
 * ```
 *
 * ## 2026-10-18：接上真实语音链路（M1）
 *
 * 此前核对屏的数据来自 debug 预览和「端到端演练」按钮 —— 不是来自用户的嘴。
 * 现在这条链路是真的：
 *
 * ```
 * 按下 → AudioRecord(16k/mono) ─┬→ 音频帧 → 阿里 WS ─→ 终态文本
 *                               └→ 同时建连（握手被开口的时间吃掉）
 * 松手 → finish-task → 文本 → L1 归一 + 闸门 → 写库 → 核对屏
 * ```
 *
 * 落库仍然是**零确认**：写库发生在解析完成的瞬间，核对屏是事后的展示与纠错入口。
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    bottomPadding: PaddingValues,
    onOpenSearch: () -> Unit,
    onOpenItem: (String) -> Unit,
) {
    val repository = container.repository
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resultStore = remember(context) { HomeResultStore(context) }

    val itemCount by repository.observeItemCount().collectAsState(initial = 0)

    // 语音编排器随页面创建/释放；离开首页必须 release，否则录音线程会一直挂着
    val voice = remember { container.newVoiceController() }
    val voiceState by voice.state.collectAsState()
    DisposableEffect(voice) { onDispose { voice.release() } }

    var confirmBatch by remember { mutableStateOf<ConfirmBatch?>(null) }
    var lastTrace by remember { mutableStateOf<VoiceTrace?>(null) }

    /**
     * query 的回答 / 纠错删除的反馈。与核对屏互斥：有它就说明这次没写库。
     *
     * 从 M3 起不再是裸字符串：查到的物品、以及「最接近的几条」都带 id，
     * 可以点进详情页（[answer] 的链接由 [AnswerLink] 描述）。
     */
    var answer by remember { mutableStateOf<HomeAnswer?>(null) }

    /** 上一次 LLM 抽取的诊断（仅 debug 面板展示）。 */
    var lastIntentTrace by remember { mutableStateOf<IntentTrace?>(null) }

    var replayStatus by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    var waitingMessage by remember { mutableStateOf<String?>(null) }
    var showSpeechDetails by remember { mutableStateOf(false) }
    var retryCandidate by remember { mutableStateOf<RetryableSpeech?>(null) }
    var resultAt by remember { mutableStateOf<Long?>(null) }
    var previousResult by remember { mutableStateOf(false) }
    var lookupChanged by remember { mutableStateOf(false) }
    var validLookupIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    /** Persist before the user can switch tabs; a composition effect can be cancelled too early. */
    fun persistVisibleResult() {
        val at = resultAt ?: return
        val card = when {
            confirmBatch != null && !(confirmBatch!!.unparsed &&
                confirmBatch!!.heardText == retryCandidate?.text) ->
                HomeResult.Confirmation(confirmBatch!!, at)
            answer?.lookup != null -> HomeResult.Query(answer!!.lookup!!, at)
            else -> null
        }
        if (card != null) runCatching { resultStore.save(card) }
            .onFailure { hint = "结果卡保存失败，重启后可能无法恢复" }
    }

    // Restore only display state. No query, ASR request or inventory mutation occurs here.
    LaunchedEffect(Unit) {
        val saved = withContext(Dispatchers.IO) { resultStore.read() }
        if (saved != null && confirmBatch == null && answer == null && waitingMessage == null &&
            voice.state.value == VoiceState.IDLE) {
            resultAt = saved.createdAt
            previousResult = true
            when (saved) {
                is HomeResult.Confirmation -> {
                    val validRows = saved.batch.rows.filter { row ->
                        val seq = row.committedSeq ?: return@filter true
                        val op = withContext(Dispatchers.IO) { repository.findOperation(seq) }
                        HomeResultSafety.operationStillMatches(row, saved.batch.batchId, op) &&
                            op?.itemId?.let { withContext(Dispatchers.IO) { repository.findItem(it) } } != null &&
                            withContext(Dispatchers.IO) { repository.canRevokeForHome(seq) }
                    }
                    confirmBatch = saved.batch.copy(
                        rows = validRows,
                        notice = if (validRows.size != saved.batch.rows.size)
                            "库存已变化，失效操作已移除；请到历史查看" else saved.batch.notice,
                    )
                    persistVisibleResult()
                }
                is HomeResult.Query -> {
                    val ids = lookupIds(saved.result)
                    validLookupIds = ids.filterTo(mutableSetOf()) { id ->
                        withContext(Dispatchers.IO) { repository.findItem(id) } != null
                    }
                    lookupChanged = validLookupIds != ids || lookupItems(saved.result).any { item ->
                        val currentItem = withContext(Dispatchers.IO) { repository.findItem(item.id) }
                        val currentLocations = withContext(Dispatchers.IO) { repository.liveLocations(item.id) }
                        HomeResultSafety.queryItemChanged(item, currentItem?.name, currentItem?.category,
                            currentLocations.map { com.kuguan.app.domain.query.LocationQty(it.locationCode, it.quantity) })
                    }
                    answer = HomeAnswer(LookupText.render(saved.result), saved.result)
                }
            }
        }
    }

    val online = rememberIsOnline()

    LaunchedEffect(Unit) {
        val previous = withContext(Dispatchers.IO) {
            runCatching { container.speechCases.latestRetryable() }.getOrNull()
        }
        if (previous != null && lastTrace == null &&
            waitingMessage == null && voice.state.value == VoiceState.IDLE) {
            retryCandidate = previous
            if (resultStore.read() == null && confirmBatch == null && answer == null) {
                confirmBatch = ConfirmBatch(
                    rows = emptyList(),
                    heardText = previous.text,
                    unparsed = true,
                    notice = "上次语音意图识别失败，这条没记进去",
                )
            }
        }
    }

    // ── 麦克风权限（A7）──
    // 第一次按下时申请；被拒之后不再反复弹（Android 会自动忽略重复请求），
    // 按钮下方给一句人话，让用户知道要自己去设置里开。
    var hasMic by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMic = granted
        hint = if (granted) "麦克风好了，按住说话" else "没给麦克风权限，语音录不进来（去系统设置里开）"
    }

    /**
     * 换掉某一行。**按键找，不按下标找** —— 撤掉一行后下标会整体前移，
     * 用户第二次点就点到了别的行上。
     */
    fun updateRow(key: String, transform: (ConfirmRow) -> ConfirmRow) {
        confirmBatch = confirmBatch?.copy(
            rows = confirmBatch!!.rows.map { if (it.key == key) transform(it) else it },
            notice = null,
        )
        persistVisibleResult()
    }

    fun fail(message: String) {
        confirmBatch = confirmBatch?.copy(notice = message)
        persistVisibleResult()
    }

    // ── 写库 / 改库 ──
    //
    // 「零确认」下写库发生在用户看到屏幕之前，所以这里的每个入口都必须是
    // 「要么做成、要么说清为什么没做成」—— 静默失败等于骗人。
    //
    // 2026-10-18 起核对屏只有一条出口：**就地编辑**。所以这里也只剩一个回调，
    // 用户改的是哪一格不重要 —— 三个值一起交出来，整行重写。

    /**
     * 某一行进入编辑模式后，点击保存或按输入法完成键。
     *
     * 两条路径，用户不需要知道区别：
     * - **已写库** → [StorageRepository.restateRow]：撤销旧行 + 重写新行，新行继承批次号。
     *   走「撤销 + 重放」而不是 UPDATE，是为了保住 append-only：历史页能看出这一行改过。
     * - **还没写库** → 直接补写一条。
     */
    val onRowEdited: (String, String, String, Double) -> Unit =
        { key, locationCode, itemName, quantity ->
            scope.launch {
                val batch = confirmBatch ?: return@launch
                val row = batch.rows.firstOrNull { it.key == key } ?: return@launch
                val wasCommitted = row.committed

                val newOp = if (wasCommitted) {
                    repository.restateRow(
                        seq = row.committedSeq!!,
                        itemName = itemName,
                        locationCode = locationCode,
                        quantity = quantity,
                    )
                } else when (row.action) {
                    ConfirmAction.PUT -> repository.put(
                        itemName = itemName,
                        locationCode = locationCode,
                        quantity = quantity,
                        asrText = batch.heardText,
                        batchId = batch.batchId,
                    )

                    ConfirmAction.TAKE -> repository.take(
                        itemName = itemName,
                        locationCode = locationCode,
                        quantity = quantity,
                        asrText = batch.heardText,
                        batchId = batch.batchId,
                    )
                }

                if (newOp == null) {
                    // 改失败必须说清是哪一种失败：撤销被闸门拦下，和「库里本来就没这条」
                    // 是两件完全不同的事，用户据此决定下一步（等会儿再改 / 重新录入）
                    fail(
                        when {
                            wasCommitted -> "这条已经被后面的操作用掉了，改不了"
                            row.action == ConfirmAction.TAKE -> "库里没有这一条，取不出来"
                            else -> "这条没写进去"
                        }
                    )
                    return@launch
                }

                updateRow(key) {
                    it.copy(
                        locationCode = locationCode,
                        itemName = itemName,
                        quantity = quantity,
                        uncertainFields = emptySet(),
                        committedSeq = newOp.seq,
                        corrected = wasCommitted,
                    )
                }
            }
        }

    // 唯一的出口：一条都没写库时，允许整张卡片丢掉（纯丢弃，库里不留东西）。
    //
    // 已写库的批次**没有**这个按钮 —— 那时「取消」是骗人的（库里那条还在），
    // 该用底部的「全部撤销」（它会真的把库里那批撤掉）。
    val onCancel: () -> Unit = {
        retryCandidate?.let {
            container.speechDiagnostics.recordSafely(it.sessionId, "RETRY_DISMISSED", "user_cancelled")
        }
        retryCandidate = null
        confirmBatch = null
        resultAt = null
        resultStore.clear()
    }

    /**
     * 删掉一行（行尾的红色垃圾桶）。
     *
     * 两条路径：
     * - **已写库** → [StorageRepository.revokeOperation]，范围用 `BatchSibling`：
     *   同批的行是兄弟可以撤，批外已有后续操作（尤其是取出）就拒绝 ——
     *   否则库存会被算成负数。
     * - **还没写库** → 库里本来就没有它，从列表里去掉即可。
     *
     * 删空了就把卡片收起来：留一个「共 0 项」的空壳没有任何意义。
     */
    val onRowDeleted: (String) -> Unit = { key ->
        scope.launch {
            val batch = confirmBatch ?: return@launch
            val row = batch.rows.firstOrNull { it.key == key } ?: return@launch

            if (row.committed) {
                val revoked = repository.revokeOperation(
                    seq = row.committedSeq!!,
                    scope = RevokeScope.BatchSibling,
                )
                if (revoked == null) {
                    fail("这一条已经被后面的操作用掉了，删不了")
                    return@launch
                }
            }

            val left = batch.rows.filterNot { it.key == key }
            confirmBatch = if (left.isEmpty()) null else batch.copy(rows = left, notice = null)
            if (left.isEmpty()) { resultAt = null; resultStore.clear() }
            else persistVisibleResult()
        }
    }

    /**
     * 全部撤销：已写库的整批回滚，没写库的直接丢，然后把卡片收起来。
     *
     * 整批走 [StorageRepository.revokeBatch] —— 它内部先对全批做一次预检，
     * 任一条撤不掉就一条都不撤。半批撤销是全 App 最难解释的状态：
     * 界面上剩几行、用户不知道到底撤没撤。
     */
    val onDeleteAll: () -> Unit = {
        scope.launch {
            val batch = confirmBatch ?: return@launch
            val written = batch.rows.filter { it.committed }

            if (written.isNotEmpty()) {
                val batchId = batch.batchId
                val ok = if (batchId != null) {
                    repository.revokeBatch(batchId) != null
                } else {
                    // 单条录入没有批次号，逐条撤（一般也就一条）
                    written.all {
                        repository.revokeOperation(it.committedSeq!!, RevokeScope.BatchSibling) != null
                    }
                }
                if (!ok) {
                    fail("这批里有几条已经被后面的操作用掉了，撤不了")
                    return@launch
                }
            }

            confirmBatch = null
            resultAt = null
            resultStore.clear()
        }
    }

    // ── 语音结果 → 每行一条操作（M1-B 的 B4/B5；M2 换成 IntentGateway）──
    //
    // 这一段是「零确认」真正落地的地方：解析完立刻写库，不等用户点任何东西。
    // 写不进去的（数量没听出来、编号有歧义）留给用户在核对屏上改 ——
    // 改完就走普通的 put/take，不再区分「语音来的」和「手输的」。
    //
    // M2 之后入口换成了 container.intentGateway：LLM 路由在那一层，
    // 这里只负责「把 IntentPlan 变成库里的行 + 屏幕上的行」。

    /**
     * 没写库的行把「哪一格需要看一眼」标出来。只标缺口，不标正常值。
     *
     * 判定在 [IntentRowPolicy.missingFields] 里（纯 Domain，可单测）；
     * 这里只做「Domain 字段 → UI 字段」的映射，避免两处各写一套规则。
     */
    fun flagsFor(row: PlannedRow): Set<Field> =
        IntentRowPolicy.missingFields(row).mapTo(mutableSetOf()) { missing ->
            when (missing) {
                IntentRowPolicy.MissingField.ITEM -> Field.Item
                IntentRowPolicy.MissingField.LOCATION -> Field.Location
                IntentRowPolicy.MissingField.QUANTITY -> Field.Quantity
            }
        }

    fun toConfirmRow(
        row: PlannedRow,
        action: ConfirmAction,
        index: Int,
        committedSeq: Long? = null,
        corrected: Boolean = false,
    ): ConfirmRow = ConfirmRow(
        key = "sp${index}-${committedSeq ?: "x"}-${System.nanoTime()}",
        action = action,
        locationCode = row.locationCode.orEmpty(),
        itemName = row.itemName.orEmpty(),
        quantity = row.quantity,
        uncertainFields = if (committedSeq == null) flagsFor(row) else emptySet(),
        committedSeq = committedSeq,
        corrected = corrected,
    )

    /** 拒绝/提示必须让人看见：有卡片就写在批尾，没卡片就写在顶部。 */
    fun report(message: String) {
        val batch = confirmBatch
        if (batch != null) {
            confirmBatch = batch.copy(notice = message)
            persistVisibleResult()
        } else answer = HomeAnswer(message)
    }

    /**
     * PUT / TAKE：把计划里三要素齐全的行写库，缺字段的行照旧摆出来等用户填。
     *
     * PUT 走 [StorageRepository.putBatch]（原子：任一行非法就整批不写）；
     * TAKE 逐条取（每条的库存前提不同，一条取不到不该拖累其它条）。
     * 同一句产生的行共用一个 `batch_id`，后面「全部撤销」才有得撤。
     */
    suspend fun applyRecords(raw: String, plan: IntentPlan, action: ConfirmAction, sessionId: String) {
        val writable = plan.rows.withIndex().filter { IntentRowPolicy.isWritable(it.value) }

        // index → 写库得到的 seq。用下标映射而不是「先写后拼列表」：
        // 核对屏的行必须保持说话顺序，否则用户数到第 3 行发现数量和刚才说的对不上。
        val committedSeqs = mutableMapOf<Int, Long>()
        var batchId: String? = null
        var notice: String? = plan.note

        if (writable.isNotEmpty()) {
            if (action == ConfirmAction.PUT) {
                val ops = repository.putBatch(
                    rows = writable.map { BatchRow(it.value.itemName!!, it.value.locationCode!!, it.value.quantity!!) },
                    asrText = raw,
                )
                if (ops != null && ops.size == writable.size) {
                    batchId = ops.firstOrNull()?.batchId
                    writable.forEachIndexed { i, indexed -> committedSeqs[indexed.index] = ops[i].seq }
                } else {
                    // putBatch 的预检没过（编号非法/数量越界）→ 整批转成「等用户改」
                    notice = "这几条没写进去（编号或数量不合法）"
                }
            } else {
                var failed = 0
                // 同一句产生的行共用一个 batch_id —— 否则「全部撤销」撤不到它们
                val takeBatchId = UUID.randomUUID().toString()
                writable.forEach { indexed ->
                    val row = indexed.value
                    val op = repository.take(
                        itemName = row.itemName!!,
                        locationCode = row.locationCode!!,
                        quantity = row.quantity!!,
                        asrText = raw,
                        batchId = takeBatchId,
                    )
                    if (op == null) {
                        failed++
                    } else {
                        batchId = op.batchId
                        committedSeqs[indexed.index] = op.seq
                    }
                }
                if (failed > 0) notice = "有 $failed 条取不出来（库里没有这个物品或位置）"
            }
        }

        val rows = plan.rows.withIndex().map { (index, row) ->
            toConfirmRow(row, action, index, committedSeqs[index])
        }
        container.speechDiagnostics.recordSafely(
            sessionId, "WRITE_RESULT",
            buildString {
                append("action=$action batchId=$batchId notice=$notice written=${committedSeqs.size} pending=${rows.size - committedSeqs.size}")
                plan.rows.forEachIndexed { index, row ->
                    append("\n[$index] name=${row.itemName} quantity=${row.quantity} location=${row.locationCode} ")
                    append("seq=${committedSeqs[index]} missing=${IntentRowPolicy.missingFields(row)}")
                }
            },
        )

        if (rows.isEmpty()) {
            resultAt = System.currentTimeMillis()
            previousResult = false
            confirmBatch = ConfirmBatch(
                rows = emptyList(),
                heardText = raw,
                unparsed = true,
                notice = plan.note ?: "没听出要记什么",
            )
            persistVisibleResult()
            return
        }

        resultAt = System.currentTimeMillis()
        previousResult = false
        confirmBatch = ConfirmBatch(
            rows = rows,
            batchId = batchId,
            heardText = raw,
            notice = notice,
        )
        persistVisibleResult()
    }

    /**
     * query：只回显，不写库。
     *
     * M3 起回答由 [StorageRepository.lookupItem] 统一生成 —— 和搜索页同一个数据源：
     * - 多位置**一次报全**（位置 + 各自数量 + 派生合计）；
     * - 三种「查不到」分开说：没这个物品 / 有档案但位置全空 / 近似名；
     * - 近似结果带 id，界面上可以点进详情。
     *
     * 仍然**不**把 query 结果塞进核对屏：那会让用户以为「系统记了一条」。
     */
    suspend fun answerQuery(plan: IntentPlan) {
        confirmBatch = null
        val names = plan.rows.mapNotNull { it.itemName?.takeIf(String::isNotBlank) }
        val result = repository.lookupCandidates(names)
        resultAt = System.currentTimeMillis()
        previousResult = false
        lookupChanged = false
        validLookupIds = lookupIds(result)
        answer = HomeAnswer(LookupText.render(result), lookup = result)
        persistVisibleResult()
    }

    /** 一条历史操作 → 校验用快照。物品可能已进回收站，所以用 `fetchItem` 而不是 `findItem`。 */
    suspend fun snapshotOf(seq: Long): com.kuguan.app.domain.voice.OperationSnapshot? {
        val op = repository.findOperation(seq) ?: return null
        return com.kuguan.app.domain.voice.OperationSnapshot(
            seq = op.seq,
            type = op.type,
            itemName = op.itemId?.let { repository.fetchItem(it)?.name },
            locationCode = op.locationCode,
            quantity = op.quantity,
            revoked = op.revokedAt != null,
        )
    }

    /**
     * correct：改一条已有记录（撤销 + 重写，继承 batch_id）。
     *
     * 所有校验都在 [IntentGuards.checkCorrect] 里，**校验不过就拒绝并说原因** ——
     * 语音纠错最怕的是「模型指错了一条，用户不知道，数据少了一行」。
     */
    suspend fun applyCorrection(raw: String, plan: IntentPlan) {
        val targetSeq = plan.targetSeq
        val snapshot = targetSeq?.let { snapshotOf(it) }
        val reason = IntentGuards.checkCorrect(plan, snapshot, raw)
        if (reason != null) {
            report(reason)
            return
        }

        val row = plan.rows.first()
        val written = repository.restateRow(
            seq = targetSeq!!,
            itemName = row.itemName!!,
            locationCode = row.locationCode!!,
            quantity = row.quantity!!,
        )
        if (written == null) {
            report("第 $targetSeq 条已经被后面的操作用掉了，改不了")
            return
        }

        answer = null
        resultAt = System.currentTimeMillis()
        previousResult = false
        confirmBatch = ConfirmBatch(
            rows = listOf(
                toConfirmRow(
                    row = row,
                    action = if (written.type == "TAKE") ConfirmAction.TAKE else ConfirmAction.PUT,
                    index = 0,
                    committedSeq = written.seq,
                    corrected = true,
                )
            ),
            batchId = written.batchId,
            heardText = raw,
            notice = "已改成 ${row.locationCode} · ${row.itemName} · ${IntentGuards.formatQuantity(row.quantity!!)}",
        )
        persistVisibleResult()
    }

    /**
     * delete：撤销一条记录。
     *
     * `targetSeq` 由 LLM 给出；**LLM 没给时才**用「上一条/最后一条/刚才」的语义兜底
     * 取最近一条 —— 这不是猜编号，是这句话唯一可能的含义。
     *
     * 撤销范围：
     * - 目标就是全局最新 → `LatestOnly`（最严）；
     * - 否则 → `BatchSibling`（同批兄弟可撤，批外后续操作会拦）。
     *   **不用 `Any`**：那会允许撤掉被后续 TAKE 依赖的记录，库存会算成负数。
     */
    suspend fun applyDelete(raw: String, plan: IntentPlan) {
        var targetSeq = plan.targetSeq
        if (targetSeq == null && IntentGuards.refersToLatest(raw)) {
            targetSeq = repository.latestActiveOperation()?.seq
        }
        if (targetSeq == null) {
            report("不知道要删哪一条 —— 说「删掉上一条」或者告诉我是哪件东西")
            return
        }

        val snapshot = snapshotOf(targetSeq)
        val reason = IntentGuards.checkDelete(plan, snapshot)
        if (reason != null) {
            report(reason)
            return
        }

        val isLatest = repository.latestActiveOperation()?.seq == targetSeq
        val revoked = repository.revokeOperation(
            seq = targetSeq,
            scope = if (isLatest) RevokeScope.LatestOnly else RevokeScope.BatchSibling,
        )
        if (revoked == null) {
            report("第 $targetSeq 条已经被后面的操作用掉了，撤不了")
            return
        }

        confirmBatch = null
        answer = HomeAnswer(
            buildString {
                append("已删掉第 ${revoked.seq} 条")
                snapshot?.let {
                    append("：").append(it.itemName ?: "未知物品")
                    append(" · ").append(it.locationCode ?: "-")
                    it.quantity?.let { q -> append(" · ").append(IntentGuards.formatQuantity(q)) }
                }
            }
        )
    }

    /**
     * 松手后的总入口。
     *
     * 顺序：请求 LLM（当前单线路 2200ms 总超时）→ 按意图执行。
     * 失败时显示原因，不生成可能错误的记录。
     */
    suspend fun applySpeech(raw: String, sessionId: String = UUID.randomUUID().toString()) {
        answer = null
        val resolved = container.intentGateway.resolve(raw, allowNetwork = online, sessionId = sessionId)
        val plan = resolved.plan
        lastIntentTrace = resolved.trace

        android.util.Log.i(
            "HomeScreen",
            "意图 session=$sessionId kind=${plan.kind} source=${plan.source} rows=${plan.rows.size} " +
                "target=${plan.targetSeq} · ${resolved.trace.label}",
        )

        when (plan.kind) {
            IntentKind.PUT -> applyRecords(raw, plan, ConfirmAction.PUT, sessionId)
            IntentKind.TAKE -> applyRecords(raw, plan, ConfirmAction.TAKE, sessionId)
            IntentKind.QUERY -> answerQuery(plan)
            IntentKind.CORRECT -> applyCorrection(raw, plan)
            IntentKind.DELETE -> applyDelete(raw, plan)
            IntentKind.UNKNOWN -> {
                retryCandidate = if (resolved.trace.attempted && !resolved.trace.hit && raw.isNotBlank()) {
                    RetryableSpeech(sessionId, raw)
                } else null
                resultAt = System.currentTimeMillis()
                previousResult = false
                confirmBatch = ConfirmBatch(
                    rows = emptyList(),
                    heardText = raw,
                    unparsed = true,
                    notice = plan.note ?: "没听清，再说一次",
                )
                persistVisibleResult()
            }
        }
    }

    val onRetrySpeech: () -> Unit = retry@{
        val previous = retryCandidate ?: return@retry
        val batch = confirmBatch ?: return@retry
        if (!online || waitingMessage != null || !batch.unparsed || batch.committedCount != 0 ||
            batch.heardText != previous.text) return@retry
        retryCandidate = null
        waitingMessage = "正在重新整理文稿"
        val newSessionId = UUID.randomUUID().toString()
        container.speechDiagnostics.recordSafely(newSessionId, "RETRY_OF", previous.sessionId)
        container.speechDiagnostics.recordSafely(newSessionId, "RETRY_TEXT", previous.text)
        scope.launch {
            try {
                applySpeech(previous.text, newSessionId)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                android.util.Log.e("HomeScreen", "重试语音意图失败", error)
                retryCandidate = previous
                confirmBatch = batch.copy(notice = "重试失败，请再点一次")
            } finally {
                waitingMessage = null
            }
        }
    }

    val onRetrySavedSpeech: () -> Unit = retry@{
        val previous = retryCandidate ?: return@retry
        if (!online || waitingMessage != null) return@retry
        val oldBatch = confirmBatch
        val oldAnswer = answer
        retryCandidate = null
        waitingMessage = "正在重新整理上次文稿"
        val newSessionId = UUID.randomUUID().toString()
        container.speechDiagnostics.recordSafely(newSessionId, "RETRY_OF", previous.sessionId)
        container.speechDiagnostics.recordSafely(newSessionId, "RETRY_TEXT", previous.text)
        scope.launch {
            try {
                applySpeech(previous.text, newSessionId)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                retryCandidate = previous
                confirmBatch = oldBatch
                answer = oldAnswer
                previousResult = true
                hint = "重试失败，原卡片仍在"
            } finally {
                waitingMessage = null
            }
        }
    }

    // ── 按下 / 松手 ──
    val onPressStart: () -> Unit = {
        hint = null
        when {
            !hasMic -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
            !online -> hint = "没有网络，语音用不了（搜索仍然可用）"
            else -> if (!voice.start()) hint = voice.lastTrace.error else retryCandidate = null
        }
    }

    val onPressEnd: () -> Unit = {
        // 用 .value 而不是组合时的快照：手势回调只在 pointerInput 重建时更新，
        // 捕获旧值会让「松手」这件事实时判断错状态。
        if (voice.state.value == VoiceState.RECORDING) {
            // 松手的同一帧就进入等待；VoiceController 的 PROCESSING 只覆盖 ASR finish，
            // 建连收尾和后续意图整理也可能耗时，不能让界面先退回“按住说话”。
            // 本次识别期间暂时隐藏旧卡；失败后作为带时间标记的上次结果恢复。
            val oldBatch = confirmBatch
            val oldAnswer = answer
            confirmBatch = null
            retryCandidate = null
            answer = null
            lastTrace = null
            showSpeechDetails = false
            waitingMessage = "正在识别语音"
            scope.launch {
                try {
                    val trace = voice.stop()
                    lastTrace = trace
                    when {
                        trace.error != null -> {
                            hint = trace.error
                            confirmBatch = oldBatch
                            answer = oldAnswer
                            previousResult = true
                        }
                        // 空识别不生成一张假的核对卡片。
                        trace.text.isNullOrBlank() -> {
                            hint = "没听清，再说一次"
                            confirmBatch = oldBatch
                            answer = oldAnswer
                            previousResult = true
                        }
                        else -> {
                            waitingMessage = "正在整理结果"
                            applySpeech(trace.text!!, trace.sessionId ?: UUID.randomUUID().toString())
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    android.util.Log.e("HomeScreen", "语音处理失败", error)
                    hint = "处理失败，请再说一次"
                    confirmBatch = oldBatch
                    answer = oldAnswer
                    previousResult = true
                } finally {
                    waitingMessage = null
                }
            }
        }
    }

    val onPressCancel: () -> Unit = {
        if (voice.state.value == VoiceState.RECORDING) {
            voice.cancel()
            hint = "已取消，本次语音未记录"
        }
    }

    // debug 包提供预览入口，方便在无语音链路时验收核对屏的各种形态；
    // release 包不含这段代码（BuildConfig.DEBUG 为 false）。
    //
    // ⚠️ 预览里的 committedSeq 一律用**负数**。曾经写成 1L/2L，结果预览卡片上的
    // 「撤销」真的去撤库里 seq=1 的那条真实记录 —— 预览数据撞上真数据，
    // 界面上看不出任何异常，只是数据少了一条。负数保证 find 不到、必定失败，
    // 失败时走「撤不了」的提示，不会误删。
    val previews = remember {
        listOf(
            // 单条：一批只有一行的退化情形
            ConfirmBatch(
                heardText = "A 洞拐 里放了两节七号电池",
                rows = listOf(
                    ConfirmRow(
                        key = "r1",
                        action = ConfirmAction.PUT,
                        locationCode = "A-07",
                        itemName = "七号电池",
                        quantity = 2.0,
                        committedSeq = -1L,
                    )
                ),
            ),
            ConfirmBatch(
                heardText = "登山杖 在 C 洞拐",
                rows = listOf(
                    ConfirmRow(
                        key = "r1",
                        action = ConfirmAction.TAKE,
                        locationCode = "C-03",
                        itemName = "登山杖",
                        quantity = 1.0,
                        committedSeq = -2L,
                    )
                ),
            ),
            // 批量：同一个抽屉放了很多样东西（Owen 描述的主力场景）
            ConfirmBatch(
                batchId = "preview-batch-1",
                heardText = "往 A 洞拐放了两节七号电池、三节五号电池、手电筒、螺丝刀、电工胶带",
                rows = listOf(
                    ConfirmRow("r1", ConfirmAction.PUT, "A-07", "七号电池", 2.0, committedSeq = -1L),
                    ConfirmRow("r2", ConfirmAction.PUT, "A-07", "五号电池", 3.0, committedSeq = -2L),
                    ConfirmRow("r3", ConfirmAction.PUT, "A-07", "手电筒", 1.0, committedSeq = -3L),
                    ConfirmRow("r4", ConfirmAction.PUT, "A-07", "螺丝刀", 1.0, committedSeq = -4L),
                    ConfirmRow(
                        "r5",
                        ConfirmAction.PUT,
                        "A-07",
                        "电工胶带",
                        2.0,
                        committedSeq = -5L,
                        corrected = true,
                    ),
                ),
            ),
            // 大模型没把握：只红那一格，其余照常；最后一行数量没听出来，等用户填
            //
            // 红色在这里表示识别结果存疑；编号位数本身不能成为拒绝理由。
            ConfirmBatch(
                batchId = "preview-batch-2",
                heardText = "往 A 洞零拐放了两节七号电池、三节五号电池、手电筒",
                rows = listOf(
                    ConfirmRow(
                        "r1",
                        ConfirmAction.PUT,
                        "A-07",
                        "七号电池",
                        2.0,
                        uncertainFields = setOf(Field.Location),
                        committedSeq = -1L,
                    ),
                    ConfirmRow("r2", ConfirmAction.PUT, "A-07", "五号电池", 3.0, committedSeq = -2L),
                    ConfirmRow(
                        "r3",
                        ConfirmAction.PUT,
                        "A-07",
                        "手电筒",
                        null,
                        uncertainFields = setOf(Field.Quantity),
                    ),
                ),
            ),
            // 多编号：一行一个柜子，横着对齐看
            ConfirmBatch(
                batchId = "preview-batch-3",
                heardText = "电池放 A 洞拐，胶带放 B 洞贰",
                rows = listOf(
                    ConfirmRow("r1", ConfirmAction.PUT, "A-07", "电池", 2.0, committedSeq = -1L),
                    ConfirmRow("r2", ConfirmAction.PUT, "B-02", "电工胶带", 3.0, committedSeq = -2L),
                ),
            ),
            // 整句没拆出条目
            ConfirmBatch(
                heardText = "呃……那个……",
                unparsed = true,
                rows = emptyList(),
            ),
        )
    }
    var previewIndex by remember { mutableStateOf(0) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    // 顶部内边距必须消费：Scaffold 只保证 bottomBar 不被遮，
                    // 内容区不会自动避开状态栏。此前完全没加 top，
                    // 导致标题栏顶到 y=42 —— 搜索图标上半部分落在状态栏（高 138px）里，
                    // 点击被系统吞掉、真机表现为「图标点了没反应」（K70 实测）。
                    top = bottomPadding.calculateTopPadding(),
                    bottom = bottomPadding.calculateBottomPadding(),
                ),
        ) {
            // 标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "收纳助手",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "已记 $itemCount 件物品",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpenSearch) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = "搜索",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── 上半部分：核对 / 确认信息区 ──
            // 权重放在**上半区**，让它吃掉剩余空间；下半的按钮按内容高度自适应。
            // 反过来写（按钮吃权重）会让按钮撑满整屏、把确认区挤没。
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (previousResult && waitingMessage == null &&
                    (confirmBatch != null || answer?.lookup != null)) {
                    Text(
                        text = "上次${if (confirmBatch != null) "核对" else "查询"} · " +
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(resultAt ?: 0L)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    )
                }
                if (previousResult && retryCandidate != null && waitingMessage == null &&
                    (confirmBatch != null || answer?.lookup != null)) {
                    AssistChip(
                        onClick = onRetrySavedSpeech,
                        enabled = online,
                        label = { Text("上次失败的文稿可重试") },
                    )
                }
                ConfirmationResult(
                    waitingMessage = waitingMessage,
                    batch = confirmBatch,
                    onRowEdited = onRowEdited,
                    onRowDeleted = onRowDeleted,
                    onDeleteAll = onDeleteAll,
                    onCancel = onCancel,
                    retryAvailable = retryCandidate?.let { candidate ->
                        confirmBatch?.let { it.unparsed && it.committedCount == 0 &&
                            it.heardText == candidate.text } ?: false
                    } ?: false,
                    retryEnabled = online && waitingMessage == null,
                    onRetry = onRetrySpeech,
                ) {
                    val currentAnswer = answer
                    if (currentAnswer?.lookup != null) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            HomeLookupCard(currentAnswer.lookup,
                                enabledIds = validLookupIds,
                                stale = previousResult && lookupChanged,
                                onOpenItem = onOpenItem)
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = currentAnswer?.text ?: "说完一句话，这里会显示\n抽屉编号 · 物品 · 数量",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                    }
                }
            }

            // ── 下半部分：长按说话 ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (confirmBatch != null || lastTrace != null || BuildConfig.DEBUG) {
                    TextButton(onClick = { showSpeechDetails = true }) {
                        Text("查看文稿与详细信息")
                    }
                }
                if (showSpeechDetails) {
                    AlertDialog(
                        onDismissRequest = { showSpeechDetails = false },
                        title = { Text("本次语音详情") },
                        text = {
                            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                                Text("识别文稿", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    confirmBatch?.heardText?.takeIf { it.isNotBlank() }
                                        ?: lastTrace?.text?.takeIf { it.isNotBlank() }
                                        ?: "暂无文稿",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                                )
                                // A9 调试操作仍可用，但不再挤占核对区域。
                                if (BuildConfig.DEBUG) {
                    DebugPanel(
                        trace = lastTrace,
                        intent = lastIntentTrace,
                        state = voiceState,
                        replayStatus = replayStatus,
                        onReplay = {
                            replayStatus = "回放准备中…"
                            scope.launch {
                                AsrReplay.run(
                                    assets = context.assets,
                                    clientFactory = container.asrClientFactory,
                                    onLine = { line ->
                                        // 同时进 logcat：UI 只能显示最后一行，批量结果要靠日志
                                        replayStatus = line
                                        android.util.Log.i("AsrReplay", line)
                                    },
                                )
                            }
                        },
                        previewCount = previews.size,
                        onQueryDemo = {
                            // 仅 debug：直接调用 M3 的反查层。
                            //
                            // 为什么不走 applySpeech：那一层要先过 LLM 意图分类，
                            // 中转站超时时会退化成 UNKNOWN —— 验到的是降级路径，
                            // 不是 M3 改动的反查/版式。ASR 与意图分类已由 M1/M2 门禁覆盖。
                            scope.launch {
                                val name = repository.observeAllItems().first()
                                    .firstOrNull()?.name ?: "电池"
                                answerQuery(
                                    IntentPlan(
                                        kind = IntentKind.QUERY,
                                        rows = listOf(PlannedRow(name, null, null)),
                                        source = IntentSource.UNRESOLVED,
                                    )
                                )
                            }
                        },
                        onFullChain = { replayStatus = "全链路回放中…" ; scope.launch {
                            AsrReplay.run(
                                assets = context.assets,
                                clientFactory = container.asrClientFactory,
                                onLine = { line ->
                                    replayStatus = line
                                    android.util.Log.i("AsrReplay", line)
                                },
                                onText = { text -> applySpeech(text) },
                            )
                        } },
                        onPreview = {
                            confirmBatch = previews[previewIndex % previews.size]
                            previewIndex++
                        },
                        onClear = { confirmBatch = null },
                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showSpeechDetails = false }) { Text("关闭") }
                        },
                    )
                }

                HoldToTalkButton(
                    enabled = online && waitingMessage == null && voiceState != VoiceState.PROCESSING,
                    recording = voiceState == VoiceState.RECORDING,
                    processing = waitingMessage != null,
                    label = when {
                        waitingMessage != null -> "请稍候…"
                        !online -> "无网络"
                        !hasMic -> "按住说话（首次要授权）"
                        voiceState == VoiceState.RECORDING -> "正在听，上滑取消"
                        voiceState == VoiceState.PROCESSING -> "识别中…"
                        else -> "按住说话"
                    },
                    onPressStart = onPressStart,
                    onPressEnd = onPressEnd,
                    onPressCancel = onPressCancel,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    // hint 优先：出错时用户最需要看到的是「为什么没记上」
                    text = waitingMessage ?: hint ?: when (voiceState) {
                        VoiceState.RECORDING -> "在听，说完松手；上滑取消"
                        VoiceState.PROCESSING -> "别急，正在出字"
                        else -> "松开即记录，不用点确认"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
    }
}

/**
 * 首页答案区的一条可点链接。
 *
 * 「点位置」在 M3 之前是不可能的：位置不是实体、不可编辑、也没有页面。
 * 让用户点「打开「登山杖」详情」比让位置本身可点更诚实 ——
 * 他到了详情页能看见全部位置，但改不了任何一个（位置只能语音改）。
 */
@Composable
internal fun ConfirmationResult(
    waitingMessage: String?,
    batch: ConfirmBatch?,
    onRowEdited: (String, String, String, Double) -> Unit = { _, _, _, _ -> },
    onRowDeleted: (String) -> Unit = {},
    onDeleteAll: () -> Unit = {},
    onCancel: () -> Unit = {},
    retryAvailable: Boolean = false,
    retryEnabled: Boolean = true,
    onRetry: () -> Unit = {},
    emptyContent: @Composable () -> Unit = {},
) {
    when {
        waitingMessage != null -> SpeechWaitingPanel(waitingMessage)
        batch != null -> ConfirmPanel(
            batch = batch,
            modifier = Modifier.fillMaxWidth(),
            onRowEdited = onRowEdited,
            onRowDeleted = onRowDeleted,
            onDeleteAll = onDeleteAll,
            onCancel = onCancel,
            retryAvailable = retryAvailable,
            retryEnabled = retryEnabled,
            onRetry = onRetry,
        )
        else -> emptyContent()
    }
}

@Composable
internal fun SpeechWaitingPanel(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().testTag("speech-waiting-panel"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(modifier = Modifier.testTag("speech-waiting-indicator"))
        Spacer(Modifier.height(18.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "完成后会显示需要核对的物品",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 查询保留结构化结果供卡片排版；其他语音反馈仍可使用普通文案。 */
private data class HomeAnswer(
    val text: String,
    val lookup: LookupResult? = null,
)

private fun lookupItems(result: LookupResult): List<com.kuguan.app.domain.query.ItemSnapshot> = when (result) {
    is LookupResult.Found -> listOf(result.item)
    is LookupResult.Matches -> result.items
    is LookupResult.NoLocations -> listOf(result.item)
    else -> emptyList()
}

private fun lookupIds(result: LookupResult): Set<String> =
    (lookupItems(result).map { it.id } +
        (result as? LookupResult.NotFound)?.suggestions.orEmpty().map { it.id }).toSet()

/**
 * A9 调试面板（**仅 debug 包**）。
 *
 * 为什么要在主屏显示这些数字：2.0s 门禁是端到端指标，出问题时必须一眼看出瓶颈在哪 ——
 * 「录音 1.2s / 建连 300ms / 出字 800ms」和「录音 200ms / 出字 2.4s」是两种完全不同的病。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugPanel(
    trace: VoiceTrace?,
    intent: IntentTrace?,
    state: VoiceState,
    replayStatus: String?,
    previewCount: Int,
    onReplay: () -> Unit,
    onFullChain: () -> Unit,
    onPreview: () -> Unit,
    onQueryDemo: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (trace != null) {
            Text(
                text = buildString {
                    append("调试 · ")
                    append("录 ${trace.durationMs}ms")
                    append(" · 连 ${trace.connectMs}ms")
                    append(" · 出字 ${trace.finishMs}ms")
                    append(" · 端到端 ${trace.totalMs}ms")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "ASR：${trace.text?.takeIf { it.isNotBlank() } ?: "（空）"}" +
                    (trace.error?.let { " ｜ 错误：$it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = if (trace.error != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
        } else {
            Text(
                text = "调试 · 还没有录音（状态 ${state.name}）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 意图解析行：LLM 命中 / 耗时 / 原始输出前 80 字。release 包里整块都不存在。
        Text(
            text = if (intent == null) {
                "意图解析 · 还没解析过"
            } else {
                buildString {
                    append("意图解析 · ").append(intent.label)
                    intent.snippet?.takeIf { it.isNotBlank() }?.let {
                        append(" · ").append(it.take(80).replace('\n', ' '))
                    }
                }
            },
            style = MaterialTheme.typography.labelSmall,
            // 只有「真的调了 LLM 却没用上」才算异常（红）。快速路径和未配置都不是错误。
            color = if (intent != null && intent.attempted && !intent.hit) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = 2.dp),
        )

        if (replayStatus != null) {
            Text(
                text = replayStatus,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()),
        ) {
            AssistChip(
                onClick = onReplay,
                label = { Text("ASR 回放", style = MaterialTheme.typography.labelSmall) },
            )
            AssistChip(
                onClick = onFullChain,
                label = { Text("全链路", style = MaterialTheme.typography.labelSmall) },
            )
            AssistChip(
                onClick = onPreview,
                label = { Text("预览 ×$previewCount", style = MaterialTheme.typography.labelSmall) },
            )
            AssistChip(
                // 只在 debug 出现：直接调反查层，不经过 ASR / LLM（见调用点注释）
                onClick = onQueryDemo,
                label = { Text("反查演示", style = MaterialTheme.typography.labelSmall) },
            )
            AssistChip(
                onClick = onClear,
                label = { Text("清空", style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

/** 网络状态。语音必须在线；搜索/历史离线照常可用（A8）。 */
@Composable
private fun rememberIsOnline(): Boolean {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager }
    var online by remember { mutableStateOf(manager.isOnlineNow()) }

    DisposableEffect(manager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online = true
            }

            override fun onLost(network: Network) {
                online = manager.isOnlineNow()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }
        // 没有网络权限之外的风险；注册失败（极少见）就当在线，让用户看到真实的网络错误
        runCatching { manager?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { manager?.unregisterNetworkCallback(callback) } }
    }
    return online
}

private fun ConnectivityManager?.isOnlineNow(): Boolean {
    // 没有活动网络是明确的离线状态。旧逻辑把 null 当成 true，
    // 飞行模式下冷启动会短暂放行语音按钮，用户按下才得到网络错误。
    if (this == null) return true
    val caps = getNetworkCapabilities(activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
