package com.kingzcheung.xime.rime

/**
 * 日语罗马音编码的显示与删除边界（移植自 CyIME / jaroomaji 的既有实现）。
 *
 * 为什么需要它：Rime 的退格按**字符**删除编码，而日语一个假名可能是 2~3 个罗马音
 * 字母（`te`→て、`kya`→きゃ、`nn`→ん）。直接退格会留下半截 `t`，屏幕上变成字母，
 * 接着再打 `a` 就拼成了 `ta`——用户体感就是"退格和后续输入都不对"。
 * 所以删除与显示都必须按「假名」这个单位来算边界，而不能交给引擎逐字符删。
 *
 * 纯字符串运算：不改引擎状态、不持锁，可在任意线程 / 任何 UI 出口调用。
 */

/** 罗马音里的元音：以元音结尾即已构成一个假名。 */
private const val ROMAJI_VOWELS = "aiueo"

/**
 * 末尾尚未成音的罗马音（需要按按下的原字母显示的部分）；null 表示末段已经拼完。
 *
 * 判定只依赖「未成音的尾部必然以辅音结尾」：末尾是元音/长音/分隔符就是完整假名
 * （ka→か、kya→きゃ、xtsu→っ、ka-→かー）；末尾是辅音才可能没拼完，再从末尾回溯
 * 出整段辅音串，扣掉串内已经成音的部分（`nn`→ん、双写辅音的第一个→促音 っ）。
 */
internal fun pendingRomajiTail(input: String): String? {
    val last = input.lastOrNull() ?: return null
    if (!last.isLetter() || last.lowercaseChar() in ROMAJI_VOWELS) return null
    var start = input.length
    while (start > 0) {
        val previous = input[start - 1]
        if (!previous.isLetter() || previous.lowercaseChar() in ROMAJI_VOWELS) break
        start--
    }
    return pendingTailInConsonants(input.substring(start))
}

/**
 * 从整段辅音里扣掉已经成音的部分，返回剩下的待拼尾部；null 表示整段都已成音。
 *
 * - `nn` → 撥音 ん；`n` + 非 y 的辅音 → n 已读成 ん（`nk` → んk）；
 * - 同一辅音双写 → 第一个是促音 っ（`kk` 表示 っ + 待拼 k）。
 * `n` + `y` 未定音（可能是 にゃ/にゅ/にょ 的前缀），与其他辅音一样算待拼尾部。
 */
private fun pendingTailInConsonants(consonants: String): String? {
    var index = 0
    while (index < consonants.length) {
        val current = consonants[index].lowercaseChar()
        val next = consonants.getOrNull(index + 1)?.lowercaseChar()
        when {
            current == 'n' && next == 'n' -> index += 2
            current == 'n' && next == 'y' -> break
            current == 'n' && next != null -> index += 1
            next == current -> index += 1
            else -> break
        }
    }
    return if (index >= consonants.length) null else consonants.substring(index)
}

/**
 * 退格一次要删到的位置（与显示同源，避免删掉屏幕上没显示的内容）。
 *
 * - 末尾还有未成音的尾部 → 只删尾部最后一个字母（`kak` → 删 k）；
 * - 末尾已经成音 → 删掉最后一个完整假名，拗音与撥音整体删
 *   （`kya`、`kann`），双写促音只删后一个假名（`kakka` → 删 ka，保留 かっ）。
 */
internal fun romajiDeleteStart(input: String): Int {
    if (input.isEmpty()) return 0
    if (pendingRomajiTail(input) != null) return input.length - 1
    val last = input.last()
    // 长音符 / 分隔符 / 标点：单个字符即一个删除单位。
    if (!last.isLetter()) return input.length - 1
    // 末尾 nn → 撥音 ん 一个假名。
    if (last.lowercaseChar() == 'n' && input.length >= 2 &&
        input[input.length - 2].lowercaseChar() == 'n'
    ) return input.length - 2
    // 末尾元音：连同它前面的辅音群一起删（拗音 ky、sh 等）。
    var start = input.length - 1
    while (start > 0) {
        val previous = input[start - 1]
        if (!previous.isLetter() || previous.lowercaseChar() in ROMAJI_VOWELS) break
        start--
    }
    // 双写促音（kka）：第一个辅音属于上一个假名 っ，留给下一次删除。
    val consonants = input.substring(start, input.length - 1)
    val pending = pendingTailInConsonants(consonants)
    start += consonants.length - (pending?.length ?: 0)
    return start
}

/** 保留已经成音的 ん/っ：删掉提供上下文的假名时不能把它们一起带走。 */
internal fun deleteJapaneseRomaji(input: String): String {
    val end = romajiDeleteStart(input)
    val completedEnd = input.length - (pendingRomajiTail(input)?.length ?: 0)
    val completedPrefix = input.take(minOf(end, completedEnd))
    val contextualTail = pendingRomajiTail(completedPrefix).orEmpty()
    val stablePrefix = completedPrefix.dropLast(contextualTail.length) + contextualTail.map {
        when (it) { 'n' -> "nn"; 'N' -> "NN"; else -> if (it.isUpperCase()) "XTSU" else "xtsu" }
    }.joinToString("")
    return stablePrefix + input.substring(minOf(end, completedEnd), end)
}

/**
 * 显示串：已拼完部分沿用引擎回显，末尾尚未拼完的罗马音按按下的原字母显示。
 *
 * [preedit] 是引擎对同一个 [input] 给出的回显。去掉尾部那一截再接上原字母，
 * 就是「已拼完的假名 + 按下的字母」——否则引擎会把半截编码兜底成 っ / ん。
 * 纯字符串运算，不改引擎状态，可在主线程 UI 刷新路径直接调用。
 */
internal fun japanesePreedit(input: String, preedit: String): String {
    val tail = pendingRomajiTail(input) ?: return preedit
    if (preedit.length < tail.length) return tail
    return preedit.dropLast(tail.length) + tail
}

// ─────────────────────────────────────────────────────────────
// 假名变换：小字 / 浊音 / 半浊音 / 片假名
//
// 全部靠「改写罗马音输入串」实现，不新增引擎能力：
//   · 小字  → 末尾假名换成 x 前缀拼写（つ→xtu、や→xya、あ→xa）
//   · 浊音  → 首辅音换行（k→g、s→z、t→d、h→b）
//   · 半浊音→ は行 h→p
//   · 片假名→ 整串罗马音转大写（方案里大写即片假名）
// 方案 jaroomaji.kana_kigou 词典已含这些音节（ぁ xa / っ xtu / ゃ xya / ゎ xwa /
// ゕ xka / ゖ xke 与对应大写、以及 ガ GA / ザ ZA / ダ DA / バ BA / パ PA），
// 所以改完 setInput 立刻就能出正确假名，不需要重编词典。
// ─────────────────────────────────────────────────────────────

/** 小字罗马音（键 = 原假名的罗马音，值 = 送进引擎的小字拼写）。 */
private val SMALL_KANA_ROMAJI = mapOf(
    "a" to "xa", "i" to "xi", "u" to "xu", "e" to "xe", "o" to "xo",
    "ya" to "xya", "yu" to "xyu", "yo" to "xyo",
    "wa" to "xwa", "tu" to "xtu", "ka" to "xka", "ke" to "xke",
)

/** 保持原串的大小写风格（片假名模式下输入串是大写）。 */
private fun applyRomajiCase(source: String, mapped: String): String =
    if (source.any { it.isUpperCase() }) mapped.uppercase() else mapped

/**
 * 末尾「已完成假名」的罗马音片段起点；null 表示没有可变换的假名。
 *
 * 末尾还挂着未拼完的辅音（`kak` 的 k）、长音符、标点时不参与变换——
 * 用户看到的还是半截字母，把它当假名去浊化/小字化只会让屏幕上出现意外字符。
 */
private fun completedKanaStart(input: String): Int? {
    if (input.isEmpty()) return null
    if (pendingRomajiTail(input) != null) return null
    if (!input.last().isLetter()) return null
    return romajiDeleteStart(input)
}

/** 把末尾假名变成小字（っ/ゃ/ぁ/ゎ/ゕ/ゖ…）；没有对应小字时返回 null。 */
internal fun smallKanaRomaji(input: String): String? {
    val start = completedKanaStart(input) ?: return null
    val segment = input.substring(start)
    val mapped = SMALL_KANA_ROMAJI[segment.lowercase()] ?: return null
    return input.substring(0, start) + applyRomajiCase(segment, mapped)
}

/**
 * 把末尾假名浊化（[semi] = false）或半浊化（true）；没有对应假名时返回 null。
 *
 * 只有 か/さ/た/は 四行（以及 う→ゔ）能变：あ/な/ま/や/ら/わ 行保持不变，
 * 半浊音只有 は 行有。
 */
internal fun voicedKanaRomaji(input: String, semi: Boolean): String? {
    val start = completedKanaStart(input) ?: return null
    val segment = input.substring(start)
    val lower = segment.lowercase()
    // ん（nn）没有浊音；「ゔ」已经是浊音，不重复处理
    if (lower == "nn" || lower.startsWith("v")) return null
    val head = lower[0]
    if (head == 'u') {
        if (semi) return null
        return input.substring(0, start) + applyRomajiCase(segment, "vu")
    }
    val voiced = when (head) {
        'k' -> if (semi) return null else 'g'
        's' -> if (semi) return null else 'z'
        't' -> if (semi) return null else 'd'
        'h' -> if (semi) 'p' else 'b'
        else -> return null
    }
    return input.substring(0, start) + applyRomajiCase(segment, voiced + lower.substring(1))
}

/**
 * 整串罗马音大小写互换：小写=平假名，大写=片假名（方案 preedit_format 两套 xform）。
 *
 * 只反转字母，长音符 / 分隔符 / 标点原样保留。
 */
internal fun swapRomajiCase(input: String): String = buildString(input.length) {
    for (ch in input) append(if (ch.isUpperCase()) ch.lowercaseChar() else ch.uppercaseChar())
}
