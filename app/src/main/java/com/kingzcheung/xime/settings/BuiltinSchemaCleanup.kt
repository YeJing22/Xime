package com.kingzcheung.xime.settings

import android.content.Context
import com.kingzcheung.xime.util.FileLogger
import java.io.File

/**
 * 清理已经从内置基线里移除的方案文件。
 *
 * 背景：`copyAssetsToRimeDir` 只做「覆盖 + 补齐」，从不删除用户目录里的多余文件。
 * 所以把五笔/拼音从 `assets/rime` 删掉之后，**覆盖安装的老用户**仍然会在
 * 「输入方案」里看到它们（源文件还在 `files/rime/`），并且照样参与编译。
 *
 * 这里按硬编码清单做一次清理（一次性标记，之后不再扫）。只删本 App 曾经内置过、
 * 且已经从 `assets/rime` 移除的 id，不碰用户自己导入的方案。
 *
 * `wubi86.dict.yaml` / `wubi86_extra.dict.yaml` 是**故意保留**的：手写方案
 * （`handwriting.schema.yaml`）的 translator 用 `dictionary: wubi86` 做反查，
 * 删了手写就查不出字。
 */
internal object BuiltinSchemaCleanup {

    private const val TAG = "BuiltinSchemaCleanup"
    // v2：清理范围扩大到 numbers / handwriting / 五笔词典；改版本号让已经跑过 v1 的
    // 设备再执行一次（否则旧标记会让新增的清理项永远不生效）。
    private const val MARKER = "removed_builtin_schemas_v2"

    /** id → 是否连词典一起删。 */
    private val REMOVED_SCHEMAS = mapOf(
        "wubi86" to true,
        "wubi86_pinyin" to true,
        "wubi86_trad" to true,
        "wubi86_trad_pinyin" to true,
        "pinyin_simp" to true,
        "t9_pinyin" to true,
        "wubi98" to true,
        // 辅助方案：大写数字（数字键盘候选）与手写反查。
        // 手写键盘本身是代码布局，识别照常；只是不再有 rime 方案做候选反查。
        "numbers" to true,
        "handwriting" to true,
    )

    /** 补充词库（不属于任何一个 schema id，但随方案一起废弃）。 */
    private val REMOVED_DICTS = listOf(
        "pinyin_simp_ext",
        "wubi86_extra",
        "wubi86_user",
    )

    /** 编译产物后缀；不删会在 build 目录留下几十 MB 的死文件。 */
    private val BUILD_SUFFIXES = listOf(".prism.bin", ".table.bin", ".reverse.bin")

    fun purgeOnce(context: Context, rimeDir: File) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        if (prefs.getBoolean(MARKER, false)) return
        if (!rimeDir.isDirectory) return

        var removed = 0
        val buildDir = File(rimeDir, "build")
        for ((id, dropDict) in REMOVED_SCHEMAS) {
            removed += deleteIfExists(File(rimeDir, "$id.schema.yaml"))
            // librime 部署时会把这些 yaml 拷进 build/ 再编译，残留在那里同样会被扫描到
            removed += deleteIfExists(File(buildDir, "$id.schema.yaml"))
            if (dropDict) {
                removed += deleteIfExists(File(rimeDir, "$id.dict.yaml"))
            }
            for (suffix in BUILD_SUFFIXES) {
                removed += deleteIfExists(File(buildDir, "$id$suffix"))
            }
        }
        for (name in REMOVED_DICTS) {
            removed += deleteIfExists(File(rimeDir, "$name.dict.yaml"))
            removed += deleteIfExists(File(buildDir, "$name.dict.yaml"))
        }

        prefs.edit().putBoolean(MARKER, true).apply()
        FileLogger.i(TAG, "purged $removed deprecated builtin schema file(s)")
    }

    private fun deleteIfExists(file: File): Int =
        try {
            if (file.isFile && file.delete()) 1 else 0
        } catch (e: Exception) {
            FileLogger.w(TAG, "delete ${file.name} failed: ${e.message}")
            0
        }
}
