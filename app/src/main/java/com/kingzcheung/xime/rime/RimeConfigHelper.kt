package com.kingzcheung.xime.rime

import com.kingzcheung.xime.util.FileLogger
import android.content.Context
import android.util.Log
import com.kingzcheung.xime.settings.PersonalDictManager
import com.kingzcheung.xime.settings.SchemaConfigHelper
import com.kingzcheung.xime.settings.MarketVersionStore
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.scheme.SchemeFiles
import com.kingzcheung.xime.scheme.SchemeInstaller
import com.kingzcheung.xime.scheme.SchemeStore
import com.kingzcheung.xime.settings.RimeDependencyResolver
import com.kingzcheung.xime.settings.RimeSharedFiles
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object RimeConfigHelper {
    private const val TAG = "RimeConfigHelper"
    private const val ASSETS_RIME_DIR = "rime"
    /** app 固定的 default.custom.yaml 模板（assets 根，与 xime.yaml 并列，不随 submodule 分发）。 */
    private const val ASSETS_DEFAULT_CUSTOM = "default.custom.yaml"

    /** 市场索引中随 app 发布的内置默认方案集条目 id（见 rimes/index.yaml 的 `builtin`）。 */
    private const val BUILTIN_MARKET_ID = "builtin"
    /** 内置方案集的初始占位版本：`0.0.0` 保证不等于任何真实 git tag，从而在市场提示「更新」。 */
    private const val BUILTIN_PLACEHOLDER_VERSION = "0.0.0"

    /** 部署互斥：Application 预初始化与输入法服务初始化可能并发触发部署，串行化避免重复/并发全量编译。 */
    private val deploymentLock = Any()
    
    suspend fun initializeRimeDataAsync(context: Context): Pair<String, String> {
        val rimeDir = File(context.filesDir, "rime")
        
        // 迁移旧目录结构 (rime/shared/ + rime/user/) → 单一 rime/ 目录
        migrateOldStructure(context, rimeDir)
        
        // 迁移旧版 market 目录（rime/market/ → market/）
        migrateOldMarketDir(context)
        
        if (!rimeDir.exists()) {
            rimeDir.mkdirs()
        }
        
        copyAssetsToRimeDir(context, rimeDir)
        // 状态对齐（迁移一次 + 过滤失效项），再把启用列表镜像进 default.yaml。
        SchemeStore.ensure(context)
        SchemaManager.applyEnabledSchemasToDefaultYaml(context)
        // 为所有启用方案打个人词库补丁
        PersonalDictManager.ensureSchemaPacks(context)
        // 不再在初始化阶段删 build：build 是否重建统一由 ensureDeployment()
        // 按增量优先策略决定，避免配置变化即全量重编译（60MB 词库持锁 30s+）。

        return Pair(rimeDir.absolutePath, rimeDir.absolutePath)
    }

    /**
     * 统一部署入口（进程内互斥）：
     * - 部署 hash 一致 → build 已是最新，对齐 deploymentDone 标记，跳过编译；
     * - hash 缺失/不一致 → 优先增量维护（librime 按文件时间戳只编译变更），
     *   仅当 build 缺失/为空或增量失败时才清空全量编译。
     *
     * 必须由调用方保证 engine 已 initialize（deploy() 未初始化时返回 false）。
     * 该入口被 Application 预初始化与输入法服务共享，配合 deploymentLock
     * 避免两者并发触发两次全量编译。
     */
    fun ensureDeployment(context: Context): Boolean {
        synchronized(deploymentLock) {
            // 刚补回基础文件：旧 hash 与产物已不可信，强制重编
            if (ensureBuiltinBaseFiles(context) > 0) SettingsPreferences.setDeploymentHash(context, "")
            val currentHash = computeDeploymentHash(context)
            // hash 一致不代表产物齐全：librime 部署失败只记日志、不改变返回码，
            // 旧逻辑因此会把「部分方案未编译」当成已部署永久跳过。这里要求产物
            // 也齐全，缺失时继续走维护流程重编。
            val missing = missingCompiledSchemas(context)
            if (currentHash.isNotEmpty() &&
                currentHash == SettingsPreferences.getDeploymentHash(context) &&
                missing.isEmpty()
            ) {
                SettingsPreferences.setDeploymentDone(context, true)
                return true
            }
            if (missing.isNotEmpty()) {
                FileLogger.w(TAG, "Missing build artifacts for enabled schemas: ${missing.joinToString()}")
            }
            Log.i(TAG, "Deployment hash mismatch or missing")
            val buildDir = File(context.filesDir, "rime/build")
            val buildExists = buildDir.exists() && buildDir.listFiles()?.isNotEmpty() == true
            val engine = RimeEngine.getInstance()
            val deployed: Boolean
            if (buildExists) {
                // build 已就位但配置有变化：增量维护，只编译变更的 schema/dict，
                // 避免 custom.yaml 补丁等小幅改动触发 60MB 词库全量重编译（持锁 30s+）。
                Log.i(TAG, "Build exists, running incremental maintenance")
                if (engine.deployIncremental()) {
                    deployed = true
                } else {
                    FileLogger.w(TAG, "Incremental maintenance failed, falling back to full deploy")
                    buildDir.deleteRecursively()
                    buildDir.mkdirs()
                    deployed = engine.deploy()
                }
            } else {
                Log.i(TAG, "Build directory missing or empty, running full deploy")
                buildDir.mkdirs()
                deployed = engine.deploy()
            }
            if (deployed) {
                storeDeploymentHash(context)
                val stillMissing = missingCompiledSchemas(context)
                SettingsPreferences.setDeploymentDone(context, stillMissing.isEmpty())
                if (stillMissing.isNotEmpty()) {
                    FileLogger.e(
                        TAG,
                        "Deployment finished but schemas are not compiled: ${stillMissing.joinToString()}"
                    )
                    return false
                }
                return true
            }
            return false
        }
    }

    /**
     * 已启用但缺少编译产物（build/<id>.prism.bin 或 build/<id>.schema.yaml）的方案。
     * librime 部署失败只写日志、部署任务本身仍返回成功，必须由宿主自行校验，
     * 否则「部署完成」会掩盖方案未编译（如 t9_pinyin 不在 schema_list 里）。
     */
    fun missingCompiledSchemas(context: Context): List<String> {
        val rimeDir = File(context.filesDir, "rime")
        // 只看源码还在的启用方案：源码已被卸载的残留项重编也补不出产物，
        // 不应因此让每次启动都触发一次维护。
        val enabled = SchemaManager.getEnabledSchemas(context)
            .filter { File(rimeDir, "$it.schema.yaml").isFile }
        return missingCompiledSchemas(
            enabled = enabled,
            buildDir = File(rimeDir, "build"),
        )
    }

    /** 纯文件校验（可单测）：启用方案是否都有至少一个编译产物。 */
    internal fun missingCompiledSchemas(
        enabled: List<String>,
        buildDir: File,
    ): List<String> = enabled.filter { schemaId ->
        !File(buildDir, "$schemaId.prism.bin").exists() &&
            !File(buildDir, "$schemaId.schema.yaml").exists()
    }

    /** [missingCompiledSchemas] 的显示名版本，用于部署失败提示。 */
    fun missingCompiledSchemaNames(context: Context): List<String> =
        missingCompiledSchemas(context).map { schemaId ->
            SchemaManager.getSchemaDisplayName(context, schemaId) ?: schemaId
        }
    
    fun initializeRimeData(context: Context): Pair<String, String> {
        val rimeDir = SchemeFiles.rimeDir(context)

        // 上次安装若在解压阶段被打断（EXTRACTING），先按压缩包重来一次，再读写 rime。
        SchemeInstaller.recoverIfNeeded(context)

        migrateOldStructure(context, rimeDir)
        
        if (!rimeDir.exists()) {
            rimeDir.mkdirs()
        }
        
        copyAssetsToRimeDir(context, rimeDir)
        // 同步初始化路径同样先对齐状态，再镜像 schema_list
        SchemeStore.ensure(context)
        SchemaManager.applyEnabledSchemasToDefaultYaml(context)
        runBlocking { PersonalDictManager.ensureSchemaPacks(context) }
        // build 重建统一由 ensureDeployment() 增量优先决策，此处不删 build
        
        return Pair(rimeDir.absolutePath, rimeDir.absolutePath)
    }
    
    fun storeDeploymentHash(context: Context) {
        val hash = computeDeploymentHash(context)
        if (hash.isNotEmpty()) {
            SettingsPreferences.setDeploymentHash(context, hash)
        }
    }

    /**
     * 部署产物完整性检查（半成品检测）：librime 的 table/prism 产物文件头是固定
     * magic（"Rime::Table/" / "Rime::Prism/"，Metadata.format 位于文件偏移 0）。
     * 部署进行中进程被杀会留下空文件或截断的半成品——其 mtime 比源文件新，
     * librime 增量维护会视为"已是最新"而跳过重编，导致该方案查询永远零命中
     * （症状：九键方案切换正常、按键进 buffer，但候选恒空、候选栏恒 IDLE）。
     */
    internal fun isBrokenBuildArtifact(file: File): Boolean {
        val expectedMagic = when {
            file.name.endsWith(".prism.bin") -> "Rime::Prism/"
            file.name.endsWith(".table.bin") -> "Rime::Table/"
            // 未知类型（如 reverse.bin、日志）不判损坏，避免误删触发部署循环
            else -> return false
        }
        if (!file.isFile || file.length() == 0L) return true
        if (file.length() < expectedMagic.length) return true
        return try {
            java.io.FileInputStream(file).use { input ->
                val head = ByteArray(expectedMagic.length)
                var read = 0
                while (read < head.size) {
                    val n = input.read(head, read, head.size - read)
                    if (n < 0) return true
                    read += n
                }
                !String(head, Charsets.US_ASCII).startsWith(expectedMagic)
            }
        } catch (_: IOException) {
            // 读不了不臆断损坏，交由 librime 运行时自检兜底
            false
        }
    }

    fun isDeploymentComplete(context: Context): Boolean {
        val rimeDir = File(context.filesDir, "rime")
        val buildDir = File(rimeDir, "build")
        if (!buildDir.exists()) return false

        val enabledSchemas = SchemaManager.getEnabledSchemas(context)
        if (enabledSchemas.isEmpty()) return false

        // 损坏产物统一扫描：*.prism.bin / *.table.bin 存在但 magic 不对（部署中断
        // 半成品）→ 删除并判定未完成。产物被删除后 librime 增量维护才会重建它；
        // 同时清 stored hash，避免 ensureDeployment 因 hash 一致而短路跳过重建。
        // 词典产物按 dictionary 名生成，可能与 schemaId 不同名（如 t9_pinyin 用
        // pinyin_simp 词典），故扫描整个 build 目录而非按方案枚举。
        buildDir.listFiles()?.forEach { artifact ->
            if (artifact.isFile && isBrokenBuildArtifact(artifact)) {
                artifact.delete()
                SettingsPreferences.setDeploymentHash(context, "")
                FileLogger.w(TAG, "Broken build artifact removed: ${artifact.name}")
                return false
            }
        }

        for (schemaId in enabledSchemas) {
            // 源文件已被卸载的残留启用项不参与部署（applyEnabledSchemasToDefaultYaml
            // 也会跳过），不能因为它把整次部署判成未完成。
            if (!File(rimeDir, "$schemaId.schema.yaml").isFile) continue
            if (!File(buildDir, "$schemaId.prism.bin").exists() &&
                !File(buildDir, "$schemaId.schema.yaml").exists()) {
                return false
            }
        }

        val currentHash = computeDeploymentHash(context)
        if (currentHash.isEmpty()) return false

        val storedHash = SettingsPreferences.getDeploymentHash(context)
        if (storedHash.isEmpty()) {
            SettingsPreferences.setDeploymentHash(context, currentHash)
            return true
        }

        if (currentHash != storedHash) {
            return false
        }

        return true
    }

    private fun fileUpdateDigest(digest: java.security.MessageDigest, file: File) {
        if (!file.exists()) return
        java.io.FileInputStream(file).use { input ->
            java.security.DigestInputStream(input, digest).use { dis ->
                val buffer = ByteArray(8192)
                while (dis.read(buffer) != -1) { }
            }
        }
    }

    private fun computeDeploymentHash(context: Context): String {
        val rimeDir = File(context.filesDir, "rime")
        val digest = java.security.MessageDigest.getInstance("SHA-256")

        val enabledSchemas = SchemaManager.getEnabledSchemas(context)
        for (schemaId in enabledSchemas.sorted()) {
            val schemaFile = File(rimeDir, "$schemaId.schema.yaml")
            if (schemaFile.exists()) {
                digest.update(schemaId.toByteArray())
                fileUpdateDigest(digest, schemaFile)
            }
            val customFile = File(rimeDir, "$schemaId.custom.yaml")
            if (customFile.exists()) {
                fileUpdateDigest(digest, customFile)
            }
            // merged dict 由 app 生成、librime 实际编译，计入 hash 以便其变更（如转发器展开修复）触发重编译
            val mergedDictFile = File(rimeDir, "${schemaId}_merged.dict.yaml")
            if (mergedDictFile.exists()) {
                digest.update("${schemaId}_merged".toByteArray())
                fileUpdateDigest(digest, mergedDictFile)
            }
        }

        // 所有词典文件（内置词典与个人词库）纳入 hash：
        // 否则词典新增/变更（如 pinyin_simp.dict.yaml）不会改变 hash，
        // build 目录不重建、不重新部署，导致 table.bin 缺失（运行时反复报错）。
        rimeDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".dict.yaml") }
            ?.sortedBy { it.name }
            ?.forEach { dictFile ->
                digest.update(dictFile.name.toByteArray())
                fileUpdateDigest(digest, dictFile)
            }

        val defaultYaml = File(rimeDir, "default.yaml")
        if (defaultYaml.exists()) {
            digest.update("default".toByteArray())
            fileUpdateDigest(digest, defaultYaml)
        }

        // default.custom.yaml 变化（用户手改/迁移剥离）也要触发重部署：
        // librime 编译各方案时经 DefaultConfigPlugin include default 的 menu 等节，
        // 其 patch 叠在 default.yaml 之上，hash 失配 → 增量维护 → 重编生效。
        val defaultCustomYaml = File(rimeDir, "default.custom.yaml")
        if (defaultCustomYaml.exists()) {
            digest.update("default.custom".toByteArray())
            fileUpdateDigest(digest, defaultCustomYaml)
        }

        // 方案普遍 __include: symbols:/，其变化也要触发重编
        val symbolsYaml = File(rimeDir, "symbols.yaml")
        if (symbolsYaml.exists()) {
            digest.update("symbols".toByteArray())
            fileUpdateDigest(digest, symbolsYaml)
        }

        return digest.digest().joinToString("") { String.format("%02x", it) }
    }

    /**
     * 内置方案集播种：只在 `rime` 里**一个方案都没有**时铺一份。
     *
     * 判据是内容而不是一次性标记：有内容说明用户已经有一整套方案（内置或第三方），
     * 不能覆盖；没有内容（全新安装、被清空、安装失败回退）才铺。
     */
    private fun copyAssetsToRimeDir(context: Context, targetDir: File): Boolean {
        if (SchemeFiles.installedSchemaIds(targetDir).isNotEmpty()) {
            // 已有方案集：只补用户模板与存量迁移，不碰方案文件
            seedAndMigrateDefaultCustom(context, targetDir)
            return false
        }

        val copied = try {
            copyAssetsRecursively(context, ASSETS_RIME_DIR, targetDir)
        } catch (e: IOException) {
            FileLogger.e(TAG, "Failed to copy assets", e)
            false
        }
        if (!copied) return false

        seedBuiltinPackageVersion(context)
        ensureDefaultYaml(context, targetDir)
        seedAndMigrateDefaultCustom(context, targetDir)
        FileLogger.i(TAG, "Seeded builtin schemas (rime was empty)")
        return true
    }

    /**
     * default.custom.yaml 种子复制 + 存量迁移。
     *
     * 该文件完全归用户：缺失时复制一次 app 模板（不含 schema_list，见模板头注释）
     * 作为起始基线，此后 app 零写入——不再对齐 page_size、不再清理 key_binder，
     * 用户修改经部署合并覆盖 default.yaml 同名键、计入部署 hash 自动生效。
     * 存量迁移（[SchemaManager.migrateDefaultCustomSchemaList]）把老版本写入的
     * schema_list 迁往 prefs；必须先于任何 [SchemaManager.getEnabledSchemas]
     * 消费者执行（copyAssetsToRimeDir 之后的 applyEnabledSchemasToDefaultYaml /
     * ensureDeployment 均在其后，时序安全）。
     */
    private fun seedAndMigrateDefaultCustom(context: Context, targetDir: File) {
        val target = File(targetDir, ASSETS_DEFAULT_CUSTOM)
        try {
            if (!target.exists()) {
                copyAssetFile(context, ASSETS_DEFAULT_CUSTOM, target)
            }
        } catch (e: IOException) {
            FileLogger.e(TAG, "Failed to seed $ASSETS_DEFAULT_CUSTOM", e)
        }
        SchemaManager.migrateDefaultCustomSchemaList(context)
    }

    /**
     * 全新安装复制内置默认方案集后，为市场条目 `builtin`（type=built-in 的默认方案包，
     * 一个压缩包里含多个 schema）写入一个初始占位版本号。
     *
     * 市场方案的版本号与具体 .schema.yaml 无关，是方案集所属 git 仓库的 tag（见
     * rimes/index.yaml 中 builtin 条目）。随 app 发布的内置方案集在运行时无法自报其
     * 对应的 git tag，因此写入一个不等于任何真实 tag 的占位值，使市场 `hasUpdate`
     * （installedVersion != currentVersion）成立，从而向用户提示「更新」，把默认方案
     * 从市场更新到带真实版本号的版本。仅当该条目尚无版本记录时写入。
     */
    private fun seedBuiltinPackageVersion(context: Context) {
        if (MarketVersionStore.getSchemeVersion(context, BUILTIN_MARKET_ID) == null) {
            MarketVersionStore.setSchemeVersion(context, BUILTIN_MARKET_ID, BUILTIN_PLACEHOLDER_VERSION)
        }
    }

    /** 兜底确保 default.yaml 存在（首次复制失败/旧结构迁移时可能缺失），缺失才补且不覆盖已有内容。 */
    private fun ensureDefaultYaml(context: Context, targetDir: File) {
        val defaultYaml = File(targetDir, "default.yaml")
        if (defaultYaml.exists()) return
        copyAssetFile(context, "$ASSETS_RIME_DIR/default.yaml", defaultYaml)
    }
    
    /** 补齐缺失/空的内置共享文件（不覆盖已有内容），返回补齐数。 */
    fun ensureBuiltinBaseFiles(context: Context): Int {
        val rimeDir = File(context.filesDir, "rime")
        if (!rimeDir.exists()) rimeDir.mkdirs()
        var restored = 0
        for (name in RimeSharedFiles.names(context)) {
            val target = File(rimeDir, name)
            if (target.isFile && target.length() > 0L) continue
            copyAssetFile(context, "$ASSETS_RIME_DIR/$name", target)
            if (target.isFile && target.length() > 0L) {
                restored++
                FileLogger.w(TAG, "Restored missing builtin file: $name")
            }
        }
        return restored
    }

    /**
     * 已启用方案引用、但缺 `build/<dict>.table.bin` 的反查词典：librime 只编译方案的
     * 主词典，反查词典要它自己的方案被部署才生成产物。返回 方案 id → 缺失词典名。
     */
    fun missingReverseLookupTables(context: Context): List<Pair<String, String>> {
        val rimeDir = File(context.filesDir, "rime")
        val buildDir = File(rimeDir, "build")
        val missing = mutableListOf<Pair<String, String>>()
        for (schemaId in SchemaManager.getEnabledSchemas(context)) {
            val schemaFile = File(rimeDir, "$schemaId.schema.yaml")
            if (!schemaFile.isFile) continue
            val refs = try {
                RimeDependencyResolver.scanSchemaRefs(schemaFile.readText(), null)
            } catch (e: IOException) {
                continue
            }
            for (dict in refs.reverseDicts) {
                if (!File(buildDir, "$dict.table.bin").exists()) missing.add(schemaId to dict)
            }
        }
        return missing
    }


    internal fun copyAssetsRecursively(context: Context, assetPath: String, targetDir: File): Boolean {
        val files = context.assets.list(assetPath)
        
        if (files.isNullOrEmpty()) {
            return false
        }
        
        var copiedAny = false
        
        for (fileName in files) {
            val fullAssetPath = "$assetPath/$fileName"
            val targetFile = File(targetDir, fileName)
            
            try {
                val subFiles = context.assets.list(fullAssetPath)
                if (!subFiles.isNullOrEmpty()) {
                    if (!targetFile.exists()) {
                        targetFile.mkdirs()
                    }
                    if (copyAssetsRecursively(context, fullAssetPath, targetFile)) {
                        copiedAny = true
                    }
                } else if (fileName.endsWith(".yaml") || fileName.endsWith(".lua")) {
                    val needsCopy = try {
                        if (targetFile.exists()) {
                            val fd = context.assets.openFd(fullAssetPath)
                            val sameSize = targetFile.length() == fd.length
                            fd.close()
                            !sameSize
                        } else true
                    } catch (_: Exception) {
                        true
                    }
                    if (needsCopy) {
                        copyAssetFile(context, fullAssetPath, targetFile)
                        copiedAny = true
                    }
                }
            } catch (e: IOException) {
                FileLogger.e(TAG, "Failed to process: $fullAssetPath", e)
            }
        }
        
        return copiedAny
    }
    
    private fun copyAssetFile(context: Context, assetPath: String, targetFile: File) {
        try {
            if (targetFile.exists() && targetFile.name.contains("custom")) {
                return
            }

            targetFile.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: IOException) {
            FileLogger.e(TAG, "Failed to copy: $assetPath", e)
        }
    }

    private fun migrateOldStructure(context: Context, rimeDir: File) {
        val oldSharedDir = File(context.filesDir, "rime/shared")
        val oldUserDir = File(context.filesDir, "rime/user")
        
        if (!oldSharedDir.exists() && !oldUserDir.exists()) return
        
        Log.i(TAG, "Migrating old rime directory structure to single rime/ dir...")
        
        if (!rimeDir.exists()) rimeDir.mkdirs()
        
        // 迁移 user 数据（用户配置、build 产物、userdb）
        if (oldUserDir.exists()) {
            oldUserDir.listFiles()?.forEach { file ->
                val target = File(rimeDir, file.name)
                if (!target.exists()) {
                    file.renameTo(target)
                }
            }
        }
        
        // 迁移 shared 数据（方案文件）
        if (oldSharedDir.exists()) {
            oldSharedDir.listFiles()?.forEach { file ->
                val target = File(rimeDir, file.name)
                if (!target.exists()) {
                    file.renameTo(target)
                }
            }
        }
        
        // 删除旧目录
        oldSharedDir.deleteRecursively()
        oldUserDir.deleteRecursively()
        
        Log.i(TAG, "Migration complete")
    }

    /** 迁移旧版 market 目录（rime/market/ → market/）。 */
    private fun migrateOldMarketDir(context: Context) {
        val oldMarket = File(context.filesDir, "rime/market")
        if (!oldMarket.exists()) return

        val newMarket = SchemaManager.getMarketDir(context)
        if (!newMarket.exists()) {
            // 新位置不存在，直接重命名
            if (oldMarket.renameTo(newMarket)) {
                Log.i(TAG, "Migrated rime/market/ -> market/")
            } else {
                FileLogger.w(TAG, "Failed to rename rime/market/ to market/")
            }
        } else {
            // 新位置已存在，逐项合并
            oldMarket.listFiles()?.forEach { sub ->
                val target = File(newMarket, sub.name)
                if (!target.exists()) {
                    sub.renameTo(target)
                }
            }
            oldMarket.deleteRecursively()
            Log.i(TAG, "Merged rime/market/ into market/")
        }
    }

}