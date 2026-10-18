package com.kingzcheung.xime.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SchemaMeta
import com.kingzcheung.xime.viewmodel.SchemaSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemaSettingsContent(
    onBack: () -> Unit,
    onNavigateToMarket: () -> Unit = {},
    onNavigateToRimeFileBrowser: () -> Unit = {},
    onNavigateToSchemaDictBrowser: () -> Unit = {},
    onNavigateToImported: () -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: SchemaSettingsViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // F6: 从方案市场/导入返回时自动重扫描，新装方案立即出现
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    var showMenu by remember { mutableStateOf(false) }

    if (uiState.showUninstallDialog) {
        val packages = uiState.marketPackages
        val selectedIds = remember { mutableStateListOf<String>() }
        AlertDialog(
            onDismissRequest = { viewModel.dismissUninstallDialog() },
            title = { Text("卸载方案") },
            text = {
                if (packages.isEmpty()) {
                    Text(
                        "没有可卸载的方案",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column {
                        Text(
                            "选择要卸载的方案：",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        packages.forEach { pkg ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (pkg.packageId in selectedIds) {
                                            selectedIds.remove(pkg.packageId)
                                        } else {
                                            selectedIds.add(pkg.packageId)
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                            ) {
                                Checkbox(
                                    checked = pkg.packageId in selectedIds,
                                    onCheckedChange = { checked ->
                                        if (checked) selectedIds.add(pkg.packageId)
                                        else selectedIds.remove(pkg.packageId)
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(pkg.displayName, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${pkg.schemaCount} 个输入方案 · ${pkg.fileCount} 个文件" +
                                            if (pkg.version.isNotEmpty()) " · v${pkg.version}" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedIds.forEach { viewModel.uninstallPackage(it) }
                    },
                    enabled = selectedIds.isNotEmpty(),
                ) {
                    Text(
                        "卸载 (${selectedIds.size})",
                        color = if (selectedIds.isNotEmpty()) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUninstallDialog() }) {
                    Text("取消")
                }
            }
        )
    }

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("输入方案") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            offset = DpOffset(0.dp, 4.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text("方案市场") },
                                onClick = {
                                    showMenu = false
                                    onNavigateToMarket()
                                },
                                leadingIcon = {
                                    Icon(Icons.TwoTone.Storefront, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp))
                                }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                            DropdownMenuItem(
                                text = { Text("方案词表") },
                                onClick = {
                                    showMenu = false
                                    onNavigateToSchemaDictBrowser()
                                },
                                leadingIcon = {
                                    Icon(Icons.TwoTone.Description, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp))
                                }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                            DropdownMenuItem(
                                text = { Text("文件管理器") },
                                onClick = {
                                    showMenu = false
                                    onNavigateToRimeFileBrowser()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.FolderOpen, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp))
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    ImportedSchemesEntry(
                        onClick = onNavigateToImported,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                item {
                    Text(
                        text = "已启用",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                    )
                }

                val enabledSchemas = uiState.allSchemas.filter { it.schemaId in uiState.enabledSchemas }

                if (enabledSchemas.isEmpty()) {
                    item {
                        Text(
                            text = "暂未启用任何方案",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }

                items(enabledSchemas, key = { it.schemaId }) { schema ->
                    SchemaToggleItem(
                        schema = schema,
                        enabled = true,
                        isCompiled = SchemaManager.isSchemaCompiled(context, schema.schemaId),
                        isCurrent = schema.schemaId == uiState.currentSchema,
                        onToggle = { viewModel.toggleSchema(schema) },
                        onSelect = { viewModel.selectSchema(schema) },
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "添加输入方案后，需要点击一次「部署方案」进行部署",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                val disabledSchemas = uiState.allSchemas.filter { it.schemaId !in uiState.enabledSchemas }

                if (disabledSchemas.isEmpty()) {
                    item {
                        Text(
                            text = "没有其他可用方案",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }

                items(disabledSchemas, key = { it.schemaId }) { schema ->
                    SchemaToggleItem(
                        schema = schema,
                        enabled = false,
                        isCompiled = SchemaManager.isSchemaCompiled(context, schema.schemaId),
                        isCurrent = false,
                        onToggle = { viewModel.toggleSchema(schema) },
                        onSelect = { viewModel.selectSchema(schema) },
                    )
                }
            }

            Button(
                onClick = { viewModel.deploySchema() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (uiState.isDeploying) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text("部署方案")
            }
        }
    }
}

/**
 * 「已导入」入口：作为独立列表项放在已启用方案上方，点击进入已导入方案页。
 */
@Composable
private fun ImportedSchemesEntry(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "已导入",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "查看已下载或导入的方案包",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SchemaToggleItem(
    schema: SchemaMeta,
    enabled: Boolean,
    isCompiled: Boolean,
    isCurrent: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (enabled && isCompiled) onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else
                MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = schema.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.outline
                    )
                    if (isCurrent && isCompiled) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "当前方案",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (schema.version.isNotEmpty()) {
                        Text(
                            text = "${schema.version}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    if (schema.author.isNotEmpty()) {
                        Text(
                            text = schema.author,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    if (isCompiled) {
                        Text(
                            text = "已编译",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Switch(
                checked = enabled,
                onCheckedChange = { onToggle() }
            )
        }
    }
}

