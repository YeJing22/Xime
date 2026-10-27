package com.kingzcheung.xime.viewmodel

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.util.FileLogger
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.MarketUpdateChecker
import com.kingzcheung.xime.settings.FileConflictInfo
import com.kingzcheung.xime.settings.ImportManager
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.PersonalDictManager
import com.kingzcheung.xime.settings.SchemaManifestManager
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.settings.SchemaMeta
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SchemaUiState(
    val allSchemas: List<SchemaMeta> = emptyList(),
    val enabledSchemas: List<String> = emptyList(),
    val currentSchema: String = "wubi86",
    val isDeploying: Boolean = false,
    val isDownloading: Boolean = false,
    val toastMessage: String? = null,
    val marketPackages: List<SchemaManifestManager.MarketPackageInfo> = emptyList(),
    val showUninstallDialog: Boolean = false,
)

class SchemaSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(SchemaUiState())
    val uiState: StateFlow<SchemaUiState> = _uiState.asStateFlow()

    private val _importCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val importCompleted: SharedFlow<Unit> = _importCompleted.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        // 在 IO 线程读盘（discoverSchemas/getEnabledSchemas 扫描文件），避免 ON_RESUME 在主线程卡顿
        viewModelScope.launch {
            val (allSchemas, enabledSchemas, currentSchema) = withContext(Dispatchers.IO) {
                Triple(
                    SchemaManager.discoverSchemas(context),
                    SchemaManager.getEnabledSchemas(context),
                    SettingsPreferences.getCurrentSchema(context),
                )
            }
            val sorted = allSchemas.sortedByDescending { it.schemaId in enabledSchemas }
            _uiState.update {
                it.copy(
                    allSchemas = sorted,
                    enabledSchemas = enabledSchemas,
                    currentSchema = currentSchema
                )
            }
        }
    }

    fun toggleSchema(schema: SchemaMeta) {
        val enabled = _uiState.value.enabledSchemas.toMutableList()
        if (schema.schemaId in enabled) {
            if (enabled.size <= 1) {
                showToast(tr(R.string.s_zhishaoqiyongyigefangan))
                return
            }
            enabled.remove(schema.schemaId)
        } else {
            enabled.add(schema.schemaId)
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                SchemaManager.setEnabledSchemas(context, enabled)
            }
            _uiState.update { it.copy(enabledSchemas = enabled) }
        }
    }

    fun selectSchema(schema: SchemaMeta) {
        if (_uiState.value.currentSchema == schema.schemaId) return
        val previous = _uiState.value.currentSchema
        SettingsPreferences.setCurrentSchema(context, schema.schemaId)
        _uiState.update { it.copy(currentSchema = schema.schemaId) }
        if (RimeEngine.isInitialized()) {
            val available = RimeEngine.getInstance().getAvailableSchemas()
            if (schema.schemaId in available) {
                // 部署/编译进行中 switchSchema 不阻塞返回 false，避免主线程等待
                val switched = RimeEngine.getInstance().switchSchema(schema.schemaId)
                if (switched) {
                    showToast(tr(R.string.s_yiqiehuandao, schema.name))
                } else {
                    rollbackSchemaSelection(previous)
                    showToast(tr(R.string.s_cikubushuzhong_qingshaohouzaiq_7d5ce))
                }
            } else {
                rollbackSchemaSelection(previous)
                showToast(tr(R.string.s_qingdianji_bushu_anniu))
            }
        }
    }

    /**
     * 切换失败时回滚偏好与 UI 状态。不回滚有两个后果：再次点击会被
     * [selectSchema] 开头的同值短路拦住（用户永远无法重试）；偏好与
     * 引擎实际方案脱节后，IME 重启的方案恢复逻辑会拿到偏差好。
     */
    private fun rollbackSchemaSelection(previous: String) {
        SettingsPreferences.setCurrentSchema(context, previous)
        _uiState.update { it.copy(currentSchema = previous) }
    }

    fun importSchemaFile(uri: Uri) {
        viewModelScope.launch {
            when (val result = ImportManager.import(context, uri)) {
                is ImportManager.ImportResult.Content -> {
                    refresh()
                    _importCompleted.tryEmit(Unit)
                    showToast(
                        when {
                            result.conflicts.isNotEmpty() -> conflictMessage(result.conflicts)
                            !result.success -> tr(R.string.s_daorushibai)
                            result.installedDirect -> tr(R.string.s_daoruchenggong_yifangru_rime_mulu)
                            else -> tr(R.string.s_daoruchenggong_kezailiebiaozho_f4ac5)
                        }
                    )
                }
                is ImportManager.ImportResult.Plugin -> {
                    withContext(Dispatchers.IO) { PluginManager.loadEnabledPlugins() }
                    refresh()
                    _importCompleted.tryEmit(Unit)
                    showToast(tr(R.string.s_chajian_anzhuangchenggong, result.pluginInfo?.name))
                }
                is ImportManager.ImportResult.Failed -> showToast(tr(R.string.s_daorushibai_2, result.reason))
                is ImportManager.ImportResult.Unsupported -> showToast(tr(R.string.s_buzhichidewenjianleixing))
            }
        }
    }

    fun loadMarketPackages() {
        viewModelScope.launch {
            val packages = withContext(Dispatchers.IO) {
                SchemaManifestManager.getInstalledPackages(context)
            }
            _uiState.update { it.copy(marketPackages = packages) }
        }
    }

    fun showUninstallDialog() {
        loadMarketPackages()
        _uiState.update { it.copy(showUninstallDialog = true) }
    }

    fun dismissUninstallDialog() {
        _uiState.update { it.copy(showUninstallDialog = false) }
    }

    fun uninstallPackage(packageId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                SchemaManager.deleteSchemaFiles(context, packageId)
                SchemaManager.deleteSchemeArchive(context, packageId)
            }
            _uiState.update { it.copy(showUninstallDialog = false) }
            refresh()
            loadMarketPackages()
            showToast(tr(R.string.s_yixiezai))
            // 卸载后该项不再计入可更新，立即重算角标
            MarketUpdateChecker.refreshNow(context)
        }
    }

    fun importFromUrl(url: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true) }
            val result = withContext(Dispatchers.IO) {
                SchemaManager.importFromUrl(getApplication(), url)
            }
            _uiState.update { it.copy(isDownloading = false) }
            refresh()
            _importCompleted.tryEmit(Unit)
            showToast(
                when {
                    result.conflicts.isNotEmpty() -> conflictMessage(result.conflicts)
                    result.success -> tr(R.string.s_daoruchenggong)
                    else -> tr(R.string.s_xiazaihuojieyashibai_qingjianc_5e8b6)
                }
            )
        }
    }

    /** 冲突提示：同名文件已被其他方案以不同内容安装，拒绝覆盖。 */
    private fun conflictMessage(conflicts: List<FileConflictInfo>): String {
        val first = conflicts.first()
        val owner = first.claimedBy.firstOrNull() ?: tr(R.string.s_qitafangan)
        return tr(R.string.s_daorushibai_yiyou_yibutongneir_887fd, first.fileName, owner)
    }

    fun deploySchema() {
        if (_uiState.value.isDeploying) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDeploying = true) }
            val (success, missingReverseDicts) = withContext(Dispatchers.IO) {
                // 先补齐内置基础文件（symbols/default 等），避免方案 __include 失败导致编译报错
                RimeConfigHelper.ensureBuiltinBaseFiles(context)
                PersonalDictManager.ensureSchemaPacks(context)
                KeysConfigHelper.loadConfig(context)
                KeyboardThemes.reload(context)
                val engine = RimeEngine.getInstance()
                val deployed = engine.deploy()
                if (deployed) {
                    RimeConfigHelper.storeDeploymentHash(context)
                }
                deployed to RimeConfigHelper.missingReverseLookupTables(context)
            }
            _uiState.update { it.copy(isDeploying = false) }
            val missing = missingReverseDicts.map { it.second }.distinct()
            if (missing.isNotEmpty()) {
                FileLogger.w(
                    "SchemaSettings", "部署后反查词典仍缺编译产物: $missing（对应方案: ${missingReverseDicts.map { it.first }.distinct()}）"
                )
            }
            showToast(
                when {
                    !success -> tr(R.string.s_bushushibai)
                    missing.isEmpty() -> tr(R.string.s_bushuwancheng)
                    // 反查词典由它自己的方案编译：缺产物时运行期会报 table.bin 不存在
                    else -> tr(R.string.s_bushuwancheng_fanchacidian, missing.joinToString("、"))
                }
            )
            refresh()
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private fun showToast(message: String) {
        _uiState.update { it.copy(toastMessage = message) }
    }
}
