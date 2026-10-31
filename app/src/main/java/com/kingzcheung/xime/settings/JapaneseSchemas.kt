package com.kingzcheung.xime.settings

import android.content.Context
import com.kingzcheung.xime.util.FileLogger
import java.io.File
import java.io.IOException

/**
 * 日语方案（japanese_kana / jaroomaji）的资源分发。
 *
 * 与上游 `assets/rime` 分开放在 `assets/rime_japanese`：那个目录是 rime 资源子模块，
 * 本仓库不跟踪它的内容，塞进去会在同步子模块时丢失。这里按「缺什么补什么」铺一次：
 *
 * - `*.schema.yaml` / `*.dict.yaml` → `<rime>/`
 * - `*.lua` → `<rime>/lua/`（librime-lua 按模块名加载）
 *
 * 词典由 rime-jaroomaji（Apache-2.0，作者 lazy fox chan）提供，见同目录 NOTICE.md。
 */
object JapaneseSchemas {

    private const val TAG = "JapaneseSchemas"
    private const val ASSETS_DIR = "rime_japanese"

    /** 内置的日语方案 id（与 schema_id 一致）。 */
    const val KANA_SCHEMA = "japanese_kana"
    const val ROMAJI_SCHEMA = "jaroomaji"

    /** 铺过一次的标记；删掉它可强制重新铺（方案文件本身仍是「已存在就不动」）。 */
    private const val MARKER = "japanese_schemas_v1"

    /** 「已把日语方案加进启用列表」的一次性标记。 */
    private const val ENABLED_MARKER = "japanese_schemas_enabled_v1"

    /** 要启用的方案：九宫格 + 26 键罗马字（共用 jaroomaji 词典）。 */
    private val enableIds = listOf(KANA_SCHEMA, ROMAJI_SCHEMA)

    /**
     * 一次性把日语方案**追加**进启用列表。
     *
     * 只追加不覆盖：内置方案（五笔/拼音/九键）的启用状态完全由上游逻辑决定，
     * 这里绝不重写整个列表——否则会把用户已启用的中文方案顶掉。
     * 必须在 [com.kingzcheung.xime.scheme.SchemeStore.ensure] 之后、写 `default.yaml`
     * 之前调用（ensure 会算出基线列表，这里在其上追加）。
     */
    fun enableOnce(context: Context) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        if (prefs.getBoolean(ENABLED_MARKER, false)) return
        val rimeDir = SchemaManager.getRimeDir(context)
        val available = enableIds.filter { File(rimeDir, "$it.schema.yaml").isFile }
        if (available.isEmpty()) return
        val current = SchemaManager.getEnabledSchemas(context)
        val merged = current + available.filterNot { it in current }
        if (merged != current) {
            SchemaManager.setEnabledSchemas(context, merged)
            FileLogger.i(TAG, "enabled japanese schemas: ${merged.joinToString()}")
        }
        prefs.edit().putBoolean(ENABLED_MARKER, true).apply()
    }

    /**
     * 确保日语方案在当前启用列表里（**每轮**都要调用，不只是首次）。
     *
     * 与 [enableOnce] 的分工：那个只在首次安装时追加一次并落标记；切到第三方方案包时
     * [com.kingzcheung.xime.scheme.SchemeInstaller] 会把启用列表整体替换成包声明的方案，
     * 日语方案就被挤掉了，所以切包/部署后必须再补一次。
     *
     * 当前方案（currentSchema）不动：它由 [com.kingzcheung.xime.scheme.SchemeStore] 保证
     * 落在启用列表里，这里只做追加。
     */
    fun ensureEnabled(context: Context) {
        val rimeDir = SchemaManager.getRimeDir(context)
        val available = enableIds.filter { File(rimeDir, "$it.schema.yaml").isFile }
        if (available.isEmpty()) return
        val current = SchemaManager.getEnabledSchemas(context)
        val merged = current + available.filterNot { it in current }
        if (merged == current) return
        com.kingzcheung.xime.scheme.SchemeStore.writeEnabled(context, merged)
        FileLogger.i(TAG, "ensure enabled japanese schemas: ${merged.joinToString()}")
    }

    /** 从 assets 铺到 rime 目录；已存在的文件不覆盖（用户可能改过）。 */    fun installAssets(context: Context, rimeDir: File) {
        val marker = File(rimeDir, MARKER)
        val assets = try {
            context.assets.list(ASSETS_DIR).orEmpty()
        } catch (e: IOException) {
            FileLogger.e(TAG, "list assets failed", e)
            return
        }
        if (assets.isEmpty()) return

        var copied = 0
        for (name in assets) {
            val isLua = name.endsWith(".lua")
            if (!name.endsWith(".yaml") && !isLua) continue
            val target = if (isLua) File(rimeDir, "lua/$name") else File(rimeDir, name)
            if (target.exists() && target.length() > 0) continue
            try {
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, "$name.installing")
                context.assets.open("$ASSETS_DIR/$name").use { input ->
                    temp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
                java.nio.file.Files.move(
                    temp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
                copied++
            } catch (e: IOException) {
                FileLogger.e(TAG, "install $name failed", e)
            }
        }
        if (!marker.exists()) {
            runCatching { marker.writeText("1") }
        }
        if (copied > 0) FileLogger.i(TAG, "installed $copied japanese asset(s) into ${rimeDir.name}")
    }
}
