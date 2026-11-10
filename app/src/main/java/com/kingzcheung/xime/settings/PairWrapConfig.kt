package com.kingzcheung.xime.settings

import android.content.Context
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import java.io.File

/**
 * 成对符号包裹（九键组合态）的符号表与调频。
 *
 * 交互（详见 xime.yaml `pair_wrap` 段）：
 * 组合态下手势 send_rime 送出触发符 → 候选栏换成下面的符号对 → 点选把「当前首选候选」
 * 包成 `左 + 候选 + 右` 上屏；没有候选时可退化为「上屏一对、光标居中」。
 *
 * 符号表来源：`assets/xime.yaml` 的 `pair_wrap` 段，`files/rime/xime.custom.yaml` 同段可覆盖
 * （trigger / symbols 分别覆盖）。配置解析结果按进程缓存，配置重载时由
 * [KeysConfigHelper] 调 [invalidate] 失效。
 */
internal object PairWrap {

    /** 一个符号对。左右分开存，上屏时拼成 左+目标+右。 */
    data class Symbol(val left: String, val right: String) {
        /** 候选栏显示文本（两字符，不插分隔）。 */
        val display: String get() = left + right

        /** 调频统计与去重用的稳定 id。 */
        val id: String get() = "$left|$right"
    }

    /** 解析结果：触发符 + 符号表（表内顺序 = 默认排序）。 */
    data class Config(val trigger: String, val symbols: List<Symbol>)

    private const val CONFIG_FILE = "xime.yaml"
    private const val CUSTOM_FILE = "xime.custom.yaml"
    private const val DEFAULT_TRIGGER = "\\"

    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    @Volatile
    private var cached: Config? = null

    /** 配置重载时调用（KeysConfigHelper.loadXimeConfig）。 */
    fun invalidate() {
        cached = null
    }

    fun config(context: Context): Config {
        cached?.let { return it }
        val builtIn = runCatching {
            context.assets.open(CONFIG_FILE).bufferedReader().use { it.readText() }
        }.getOrNull()
        val custom = File(context.filesDir, "rime/$CUSTOM_FILE")
            .takeIf { it.isFile }
            ?.let { file -> runCatching { file.readText() }.getOrNull() }
        val base = builtIn?.let(::parseSection)
        val override = custom?.let(::parseSection)
        val merged = when {
            base == null && override == null -> Config(DEFAULT_TRIGGER, DEFAULT_SYMBOLS)
            base == null -> override!!
            override == null -> base
            else -> Config(
                trigger = override.trigger.takeIf { it.isNotEmpty() } ?: base.trigger,
                symbols = override.symbols.takeIf { it.isNotEmpty() } ?: base.symbols,
            )
        }
        cached = merged
        return merged
    }

    /** 按使用次数降序（次数相同保持表内原序）排列的符号表。 */
    fun sortedSymbols(context: Context, config: Config = config(context)): List<Symbol> {
        val counts = SettingsPreferences.getPairWrapCounts(context)
        val order = config.symbols.withIndex().associate { (index, symbol) -> symbol.id to index }
        return config.symbols.sortedWith(
            compareByDescending<Symbol> { counts[it.id] ?: 0 }
                .thenBy { order[it.id] ?: 0 }
        )
    }

    /** 记一次使用（调频）。 */
    fun bumpCount(context: Context, symbol: Symbol) {
        SettingsPreferences.bumpPairWrapCount(context, symbol.id)
    }

    private fun parseSection(text: String): Config? = runCatching {
        val root = yaml.parseToYamlNode(text) as? YamlMap ?: return null
        val node = root.entries.entries
            .firstOrNull { (key, _) -> (key as? YamlScalar)?.content == "pair_wrap" }
            ?.value as? YamlMap ?: return null
        val trigger = node.entries.entries
            .firstOrNull { (key, _) -> (key as? YamlScalar)?.content == "trigger" }
            ?.value?.let { (it as? YamlScalar)?.content }
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_TRIGGER
        val symbols = (node.entries.entries
            .firstOrNull { (key, _) -> (key as? YamlScalar)?.content == "symbols" }
            ?.value as? YamlList)
            ?.items.orEmpty()
            .mapNotNull { (it as? YamlScalar)?.content }
            .mapNotNull(::parseSymbol)
            .distinctBy { it.id }
        Config(trigger, symbols.ifEmpty { DEFAULT_SYMBOLS })
    }.getOrNull()

    /**
     * 表项解析：
     * - `"左|右"`（显式分隔，适合两侧不等长，如 `"“|”"`、`"``|``"`）；
     * - 否则按字符对半（长度必须为偶数，如 `"《》"` → 左`《` 右`》`）。
     */
    private fun parseSymbol(raw: String): Symbol? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val bar = text.indexOf('|')
        if (bar > 0 && bar < text.length - 1) {
            return Symbol(text.substring(0, bar), text.substring(bar + 1))
        }
        if (text.length < 2 || text.length % 2 != 0) return null
        val half = text.length / 2
        return Symbol(text.substring(0, half), text.substring(half))
    }

    /** 内置兜底表（xime.yaml 缺失/解析失败时用），与配置里的表保持同一顺序。 */
    private val DEFAULT_SYMBOLS: List<Symbol> = listOf(
        "[" to "]", "【" to "】", "「" to "」", "『" to "』", "《" to "》", "〈" to "〉",
        "（" to "）", "(" to ")", "｛" to "｝", "{" to "}", "［" to "］", "〔" to "〕",
        "〖" to "〗", "“" to "”", "‘" to "’", "\"" to "\"", "'" to "'", "`" to "`",
        "``" to "``", "«" to "»", "‹" to "›", "＜" to "＞", "❲" to "❳", "⟮" to "⟯",
        "⟦" to "⟧", "⦅" to "⦆", "⦇" to "⦈", "⟨" to "⟩", "〈" to "〉", "❰" to "❱",
        "⟪" to "⟫", "〚" to "〛", "〘" to "〙",
    ).map { (left, right) -> Symbol(left, right) }
}
