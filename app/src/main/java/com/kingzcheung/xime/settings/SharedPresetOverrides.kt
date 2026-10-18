package com.kingzcheung.xime.settings

import android.content.Context
import com.kingzcheung.xime.util.FileLogger
import java.io.File
import java.security.MessageDigest

/**
 * 方案包对内置共享文件的覆盖：允许覆盖（全局生效），按包留档到 `.package-presets/<包>/`，
 * 卸载时回滚到其它包的最新版本或 app 基线，app 升级同步基线时跳过生效中的覆盖。
 */
internal object SharedPresetOverrides {
    private const val TAG = "SharedPresetOverrides"
    private const val STORE_DIR = ".package-presets"
    private const val ASSETS_RIME_DIR = "rime"

    private fun rimeDir(context: Context) = File(context.filesDir, ASSETS_RIME_DIR)
    private fun storeRoot(context: Context) = File(rimeDir(context), STORE_DIR)
    private fun packageStore(context: Context, packageId: String) = File(storeRoot(context), packageId)

    /** 安装后登记：与基线不同才留档，返回是否属于覆盖。 */
    fun recordIfOverrides(context: Context, packageId: String, fileName: String): Boolean {
        val installed = File(rimeDir(context), fileName)
        if (!installed.isFile) return false
        val installedSha = sha256(installed) ?: return false
        val baselineSha = assetSha256(context, fileName) ?: return false
        if (installedSha == baselineSha) return false
        val dir = packageStore(context, packageId)
        dir.mkdirs()
        installed.copyTo(File(dir, fileName), overwrite = true)
        FileLogger.i(TAG, "package $packageId overrides shared file $fileName")
        return true
    }

    /** 当前生效内容是否来自某个包的覆盖（升级同步基线时据此跳过）。 */
    fun hasActiveOverride(context: Context, fileName: String): Boolean {
        val flatSha = sha256(File(rimeDir(context), fileName)) ?: return false
        val stores = storeRoot(context).listFiles() ?: return false
        return stores.any { dir -> sha256(File(dir, fileName)) == flatSha }
    }

    /** 卸载后的回滚：生效版本属于该包时，切到其它包的最新覆盖，没有则回 app 基线。 */
    fun restoreAfterUninstall(context: Context, packageId: String, fileNames: Collection<String>) {
        val flatDir = rimeDir(context)
        val ownStore = packageStore(context, packageId)
        for (fileName in fileNames) {
            val flat = File(flatDir, fileName)
            val own = File(ownStore, fileName)
            val ownSha = sha256(own)
            if (ownSha != null && ownSha == sha256(flat)) {
                val fallback = storeRoot(context).listFiles()
                    ?.asSequence()
                    ?.filter { it.isDirectory && it.name != packageId }
                    ?.mapNotNull { dir -> File(dir, fileName).takeIf { it.isFile } }
                    ?.maxByOrNull { it.lastModified() }
                if (fallback != null) {
                    fallback.copyTo(flat, overwrite = true)
                    FileLogger.i(TAG, "restore $fileName from ${fallback.parentFile?.name}")
                } else {
                    writeAsset(context, fileName, flat)
                    FileLogger.i(TAG, "restore $fileName from app baseline")
                }
            }
        }
        ownStore.deleteRecursively()
    }

    /** 清掉某包的覆盖留档。 */
    fun removePackage(context: Context, packageId: String) {
        packageStore(context, packageId).deleteRecursively()
    }

    private fun writeAsset(context: Context, fileName: String, target: File) {
        runCatching {
            target.parentFile?.mkdirs()
            context.assets.open("$ASSETS_RIME_DIR/$fileName").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }.onFailure { FileLogger.e(TAG, "restore asset $fileName failed", it) }
    }

    private fun assetSha256(context: Context, fileName: String): String? = runCatching {
        context.assets.open("$ASSETS_RIME_DIR/$fileName").use { digest(it) }
    }.getOrNull()

    private fun sha256(file: File): String? = runCatching {
        if (!file.isFile) return null
        file.inputStream().use { digest(it) }
    }.getOrNull()

    private fun digest(input: java.io.InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            md.update(buffer, 0, n)
        }
        return md.digest().joinToString("") { String.format("%02x", it) }
    }
}
