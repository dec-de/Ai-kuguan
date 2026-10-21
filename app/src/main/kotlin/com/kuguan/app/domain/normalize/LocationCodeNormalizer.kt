package com.kuguan.app.domain.normalize

/**
 * 本地编号识别与旧两位编号兼容层。
 *
 * 用户自行定义位置，三位及以上的字母数字编号保留全部数字；
 * [looksLikeLocationCode] 仅检查是否非空，不作为格式白名单。
 * 详见 `doc/2026-10-19-抽屉编号自由录入开发说明.md`。
 */
object LocationCodeNormalizer {

    /** 抽取结果中的单个编号。 */
    sealed interface ParsedLocationCode {
        /** 用于 UI 展示的代表值（歧义时取第一个候选）。 */
        val display: String

        /** 唯一可信，可直接写库（零确认）。 */
        data class Confirmed(val code: String) : ParsedLocationCode {
            override val display: String get() = code
        }

        /**
         * 有歧义，**必须**让用户二次确认。
         *
         * @param letter 字母前缀，无则 null
         * @param digits ASR 实际听到的数字串（已做军事读法替换）
         * @param candidates 供用户选择的候选编号
         */
        data class Ambiguous(
            val letter: String?,
            val digits: String,
            val candidates: List<String>,
        ) : ParsedLocationCode {
            override val display: String get() = candidates.firstOrNull() ?: digits
        }
    }

    /** 一句话里可能抽出多个编号（语料 #17 一句两个编号），逐个标注可信度。 */
    data class LocationParseResult(val codes: List<ParsedLocationCode>) {
        val confirmed: List<String> get() = codes.filterIsInstance<ParsedLocationCode.Confirmed>().map { it.code }
        val ambiguities: List<ParsedLocationCode.Ambiguous> get() = codes.filterIsInstance<ParsedLocationCode.Ambiguous>()
        val hasAmbiguity: Boolean get() = ambiguities.isNotEmpty()
        val isEmpty: Boolean get() = codes.isEmpty()
    }

    /**
     * 数字表 —— **普通读法 + 军事读法两套都认**。
     *
     * 普通读法（2026-10-18 定案，主力）：`零一二三四五六七八九`。
     * 军事读法（兼容保留）：0=洞，1=幺，2=两，7=拐，9=勾/弯。
     *
     * ⚠️ `一` / `二` 是 2026-10-18 补的，此前缺失会让 `A一七` 被解析成 `A-07`：
     * 没有「一」就没有十位，只剩个位「七」，再补零 → 两个都合法的编号，界面一切正常，
     * 东西记到隔壁抽屉。这类静默改写比听错危险一个量级。
     *
     * `十` 不在这张表里 —— 它要展开成**两位** `10`，在 [preprocess] 里单独处理。
     */
    private val DIGITS: Map<Char, Char> = mapOf(
        // ── 普通读法 ──
        '零' to '0', '〇' to '0',
        '一' to '1',
        '二' to '2',
        '三' to '3',
        '四' to '4',
        '五' to '5',
        '六' to '6',
        '七' to '7',
        '八' to '8',
        '九' to '9',
        // ── 军事读法（兼容）──
        '洞' to '0', '咚' to '0', '栋' to '0',
        '幺' to '1', '腰' to '1',
        '两' to '2',
        '拐' to '7',
        '勾' to '9', '弯' to '9',
        // ── 大写数字（手写/口语混用）──
        '壹' to '1',
        '贰' to '2',
        '叁' to '3',
        '肆' to '4',
        '伍' to '5',
        '陆' to '6',
        '柒' to '7',
        '捌' to '8',
        '玖' to '9',
    )

    /**
     * 「十」单独一位时的展开形态。
     *
     * 普通读法里 `A-10` 应该念「A 一零」，但确实有人说「A 十」。
     * 按「单独一位拆 1+0」的约定展开成两位数字；`十七` 这类数值读法不在支持范围，
     * 它会展开成 `107` → 三位 → 判歧义标红，**不会**静默变成 `A-07`。
     */
    private const val TEN_EXPANSION = "10"

    /** 内部标点：ASR 会把编号念成 `A1，0` / `A、07`，这些必须**先删再解析**。 */
    private val INNER_PUNCTUATION = Regex("[，,。.、·・\\-－—－_~～'\"“”‘’]")

    /** 单字符字母读法。 */
    private val LETTER_ALIASES: Map<Char, Char> = mapOf(
        '诶' to 'A', '艾' to 'A', '埃' to 'A', '唉' to 'A',
        '比' to 'B',
        '西' to 'C', '希' to 'C',
        '弟' to 'D', '地' to 'D',
        '衣' to 'E',
    )

    /**
     * 多字符字母别名，必须在单字符替换**之前**处理
     * （否则「艾弗」会先被拆成「艾」→A、「弗」残留，变成「AF」）。
     */
    private val MULTI_CHAR_ALIASES: Map<String, Char> = mapOf(
        "艾弗" to 'F',
    )

    /** 分隔符词 → 统一成 `-`。连读时 ASR 常把分隔读成「横杠」「减号」，也可能直接吞掉。 */
    private val SEPARATOR_WORDS: List<String> = listOf(
        "横杠", "减号", "破折号", "杠", "横线", "连接号", "dash", "-", "－", "—",
    )

    /** 编号里不该出现的噪声词，先剔除（「A 洞零拐 的 抽屉」→ 「A 洞零拐」）。 */
    private val NOISE_TOKENS: List<String> = listOf(
        "抽屉", "柜子", "外面", "里面", "柜", "格", "层", "里", "中", "号", "的",
    )

    /**
     * **数量短语**：`两三节` / `五个` / `一瓶`。
     *
     * 必须在数字表替换**之前**删掉，且分类词是必需的。理由：
     * 中文里「数量」用日常读法（两三五个），「编号」才用逐位读法（零七）。
     * 所以「两格」是数量不是编号 —— 这条语言学规律比任何长度启发式都可靠。
     *
     * 分类词必需这一点很重要：若把光秃秃的「两」也删掉，
     * 「洞两拐」（=027）就会被误伤。
     *
     * ⚠️ **中文数字与 ASCII 数字必须是两个互斥分支**（2026-10-18 B0 实测的坑）：
     * 写成 `[0-9一两二三…]+` 会让 `A07B07两格` 里的「7两」被整体当成数字串吃掉，
     * 结果 `B07` 被削成 `B0` → 补零成 `00` → 第二条编号直接消失。
     * 分成两支后，正则引擎在 ASCII 分支上找不到分类词就回退，只会删掉「两格」。
     */
    private val QUANTITY_PHRASE = Regex(
        "(?:[0-9]+|[零〇一两二三四五六七八九十百千几半多少数]+)" +
            "[个只支瓶节张把块根条本包袋盒台部辆米克斤双对副片颗粒枚团卷筒罐箱排串沓桶格层叠摞扎板贴打套捆]"
    )

    /**
     * **带序号的物品名**：`七号电池` / `两号螺丝`。
     *
     * 同样只用日常读法（「拐」不在字符集里），所以 `A 洞零拐 号抽屉` 不会被误删，
     * 而 `七号电池` 会被正确剔除。
     *
     * 与 [QUANTITY_PHRASE] 同样的坑：中文数字与 ASCII 数字必须分开成两支，
     * 否则 `A07七号电池` 里的「7七」会被连成一个数字串。
     */
    private val SIZED_NOUN = Regex(
        "(?:[0-9]+|[零〇一两二三四五六七八九十百千几半多少数]+)号"
    )

    // 位置是用户定义的自由文本，不建立格式或存在性白名单。

    /**
     * 字母 + 其后**直到下一个字母之前**的所有非字母内容。
     *
     * 为什么不直接用「字母 + 紧邻数字」的正则：数字替换后空格会留在中间，
     * `B 洞洞拐` → `B 0 07`，而 `B 洞拐 两格` 里的「两格」已被数量短语规则删掉，
     * 两者都变成「字母 + 若干数字组」。紧邻正则会把 `B 0 07` 截成 `B-0`，
     * 再补零就得到假编号 `B-00` —— 正是本层要极力避免的错误类型。
     */
    private val LETTER_WINDOW = Regex("([A-Za-z])([^A-Za-z]*)")

    /** 窗口内的数字组。 */
    private val DIGIT_GROUP = Regex("\\d+")

    /**
     * 纯数字候选：至少 2 位，可连续书写或逐位空格分隔（「0 7」→ `07`）。
     *
     * 单数字不匹配 ——「两瓶」「三个」已在预处理删掉，剩下的孤立单数字是数量而非编号。
     */
    private val BARE_NUMBER_CANDIDATE = Regex("(?<![\\dA-Za-z])(\\d(?:\\s*\\d)+)(?![\\dA-Za-z])")

    /** 旧短编号的显示宽度，用于兼容已有 `A-07` 数据，不限制新编号。 */
    private const val CANONICAL_WIDTH = 2

    /**
     * 便捷入口：只在**无歧义**时返回编号，有歧义返回 null。
     *
     * 调用方若要区分「没找到」与「有歧义」，必须改用 [parse]。
     */
    fun normalize(raw: String): String? = parse(raw).confirmed.firstOrNull()

    /**
     * 抽取文本中的**全部**编号，逐个标注可信度。
     *
     * 语料 #17「A 洞零拐 B 洞拐 两格 都放了 电池」一句里有两个编号，
     * 只取第一个会丢掉后半句 —— 所以这一步必须全量抽取而非单值匹配。
     */
    fun parse(raw: String): LocationParseResult {
        if (raw.isBlank()) return LocationParseResult(emptyList())

        val prepared = preprocess(raw)

        val fromLetters = LETTER_WINDOW.findAll(prepared)
            .mapNotNull { match ->
                val letter = match.groupValues[1]
                extractWindowDigits(match.groupValues[2])?.let { interpret(letter, it) }
            }
            .distinctBy { it.display }
            .toList()
        if (fromLetters.isNotEmpty()) return LocationParseResult(fromLetters)

        val bare = BARE_NUMBER_CANDIDATE.findAll(prepared)
            .map { interpret(null, it.groupValues[1].filter(Char::isDigit)) }
            .filterNotNull()
            .distinctBy { it.display }
            .toList()
        return LocationParseResult(bare)
    }

    /** 写库入口只检查非空白；用户自行决定位置名称与编号格式。 */
    fun looksLikeLocationCode(text: String): Boolean = text.isNotBlank()

    /**
     * 从字母窗口里取出**属于编号本身**的数字串。
     *
     * 窗口里可能有两种数字：编号，以及紧随其后的数量（「B 洞拐 两格」里的 2）。
     * 两者结构相同，靠位置区分：
     * - **单个**数字组 → 就是它（`A 007`、`A 07`）
     * - **多个**数字组时，只在「前面的组都是单个数字」时才拼接。
     *   因为逐字念编号时 ASR 会把每位拆开（`B 0 07` → 三个单字组合并成 `007`），
     *   而「编号 + 数量」时数量必然是完整的一组（`[07][2]`），
     *   前组长度 ≥2 时说明编号已经念完整了，后面的数字属于数量，丢弃。
     *
     * 这样 `B 0 07` → `007`（歧义，需确认）而 `B 07 2` → `07`（确认），
     * 两条用例同时正确，不会再靠「碰巧取最长组」蒙对。
     */
    private fun extractWindowDigits(window: String): String? {
        val groups = DIGIT_GROUP.findAll(window).map { it.value }.toList()
        if (groups.isEmpty()) return null
        if (groups.size == 1) return groups.single()

        // 关键在**顺序**：编号念在前面，数量跟在后面。
        //  - 前导的单个数字 = 逐字念出的编号首位，要继续往后并
        //  - 尾部孤立的单个数字 = 数量（如「B 洞拐 两格」），丢掉
        val leadingSingles = groups.takeWhile { it.length == 1 }
        val acc = leadingSingles.joinToString("")

        // 并到长度 ≥2 即可认定编号已念完整；不足 2 位则再吃下一组
        return if (acc.length < CANONICAL_WIDTH && leadingSingles.size < groups.size) {
            acc + groups[leadingSingles.size]
        } else {
            acc
        }
    }

    /** 旧一至两位编号继续用两位写法；更长的编号保留全部数字。 */
    private fun interpret(letter: String?, digits: String): ParsedLocationCode? {
        if (digits.isEmpty()) return null

        val l = letter?.uppercase()?.take(2).orEmpty()

        return when {
            digits.length == CANONICAL_WIDTH -> confirmed(l, digits)

            // 一位沿用旧版补零规则，以便与已有位置一致。
            digits.length == 1 -> confirmed(l, digits.padStart(CANONICAL_WIDTH, '0'))

            // 三位及以上同样是用户可能使用的编号，原样保留数字串，不截断或折叠。
            else -> confirmed(l, digits, legacyTwoDigit = false)
        }
    }

    private fun confirmed(letter: String, digits: String, legacyTwoDigit: Boolean = true): ParsedLocationCode? {
        val code = if (letter.isEmpty()) digits else if (legacyTwoDigit) "$letter-$digits" else "$letter$digits"
        // 写库前仍检查空白，其他形状均由用户决定。
        return if (looksLikeLocationCode(code)) {
            ParsedLocationCode.Confirmed(code)
        } else {
            null
        }
    }

    /**
     * 为歧义情形构造候选。
     *
     * 对 `007` 这类多念零的情形，给出「去掉多余前导零」和「保持原样」两个选项，
     * 让用户一眼就能选出对的那个 —— 而不是让 App 替他决定。
     */
    private fun candidates(letter: String, digits: String): List<String> {
        val options = mutableListOf<String>()

        val trimmed = digits.trimStart('0').ifEmpty { "0" }
        if (trimmed.length in 1..3) {
            options += if (letter.isEmpty()) trimmed.padStart(CANONICAL_WIDTH, '0')
            else "$letter-${trimmed.padStart(CANONICAL_WIDTH, '0')}"
        }
        if (digits.length in 1..3) {
            options += if (letter.isEmpty()) digits else "$letter-$digits"
        }

        return options.distinct().filter { looksLikeLocationCode(it) }.ifEmpty { listOf(digits) }
    }

    /**
     * 预处理顺序**不能调换**：
     * 数量/序号短语（中文日常读法）→ 十展开 → 标点 → 噪声词 → 分隔符 → 数字表 → 字母归一。
     *
     * 顺序的关键在于：短语删除必须在数字表替换**之前**，
     * 因为它靠「日常读法字符」来识别数量，一旦替换成 ASCII 数字就再也认不出了。
     *
     * 标点删除也必须在数字表替换之前 —— `A1，0` 里的全角逗号要在「1」和「0」之间被拿掉，
     * 否则两者永远不会拼成 `10`（这是腾讯/阿里都会吐的形态，M0.6 里 3 条错例都死在这上面）。
     */
    private fun preprocess(raw: String): String {
        var s = raw.trim()

        // 1) 先删数量短语与「七号电池」这类物品名（依赖中文字符，替换后会失效）
        s = QUANTITY_PHRASE.replace(s, " ")
        s = SIZED_NOUN.replace(s, " ")

        // 2) 「十」单独成位 → 两位数字 10（「A 十」= A-10）。
        //    注意「十七」会变成 `107` → 三位 → 判歧义标红，不会静默变成 A-07。
        s = s.replace("十", TEN_EXPANSION)

        // 3) 编号内部的标点先删（全角逗号、顿号、点、短横……）
        s = INNER_PUNCTUATION.replace(s, "")

        // 4) 噪声词与分隔符
        for (token in NOISE_TOKENS) s = s.replace(token, " ")
        for (word in SEPARATOR_WORDS) s = s.replace(word, "-")

        // 5) 数字表 → ASCII（普通读法 + 军事读法都认）
        s = s.map { DIGITS[it] ?: it }.joinToString("")

        // 6) 字母归一（多字符先于单字符，否则「艾弗」会被拆坏）
        for ((alias, letter) in MULTI_CHAR_ALIASES) s = s.replace(alias, letter.toString())
        s = s.map { LETTER_ALIASES[it] ?: it }.joinToString("")

        return s.uppercase().replace(Regex("\\s+"), " ").trim()
    }
}
