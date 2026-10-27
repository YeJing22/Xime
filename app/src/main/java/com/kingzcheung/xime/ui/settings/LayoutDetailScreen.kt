package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kingzcheung.xime.settings.MarketLayoutItem
import com.kingzcheung.xime.viewmodel.LayoutMarketUiState
import com.kingzcheung.xime.viewmodel.LayoutMarketViewModel

/** 布局详情页：元信息、截图、版本列表与应用按钮。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutMarketDetailContent(
    layoutId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: LayoutMarketViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    val item = uiState.layouts.firstOrNull { it.layout.id == layoutId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr(R.string.s_xiangqing)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr(R.string.s_fanhui))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        when {
            uiState.isLoading && item == null -> MarketCenterBox {
                CircularProgressIndicator()
            }

            item == null -> MarketCenterBox {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        tr(R.string.s_weizhaodaogaineirong),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { viewModel.loadLayouts(manual = true) }) { Text(tr(R.string.s_zhongxinjiazai)) }
                }
            }

            else -> LayoutDetailBody(
                item = item,
                uiState = uiState,
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun LayoutDetailBody(
    item: MarketLayoutItem,
    uiState: LayoutMarketUiState,
    viewModel: LayoutMarketViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val layout = item.layout
    val applied = uiState.appliedLayoutId == layout.id
    val selectedVersion = uiState.selectedVersions[layout.id]
        ?: layout.resolvedVersion()?.version.orEmpty()
    val currentVersion = layout.versions.firstOrNull { it.version == selectedVersion }
        ?: layout.resolvedVersion()
    var previewUrl by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Keyboard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.size(12.dp))
                Column {
                    Text(
                        layout.name.ifEmpty { layout.id },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (layout.author.isNotEmpty()) {
                        Text(
                            tr(R.string.s_zuozhe_2, layout.author),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (layout.description.isNotEmpty()) {
            item {
                Text(layout.description, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (layout.tags.isNotEmpty()) {
            item {
                Text(
                    layout.tags.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (layout.requiresSchemes.isNotEmpty()) {
            item {
                Text(
                    if (item.schemeReady) tr(R.string.s_yilaifangan_yianzhuang, layout.requiresSchemes.joinToString("、"))
                    else tr(R.string.s_xuxian_anzhuangfangan, layout.requiresSchemes.joinToString("、")),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.schemeReady) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.applyLayout(item, selectedVersion) },
                    enabled = item.schemeReady && uiState.installingId != layout.id,
                ) {
                    Text(if (applied) tr(R.string.s_zhongxinyingyong) else tr(R.string.s_yingyong))
                }
                if (applied) {
                    OutlinedButton(
                        onClick = { viewModel.resetLayout() },
                        enabled = uiState.installingId != layout.id,
                    ) { Text(tr(R.string.s_huifumoren)) }
                }
            }
        }

        if (layout.license.isNotEmpty() || layout.repo.isNotEmpty()) {
            item {
                Text(
                    buildString {
                        if (layout.license.isNotEmpty()) append(tr(R.string.s_xuke, layout.license))
                        if (layout.repo.isNotEmpty()) {
                            if (isNotEmpty()) append("  ·  ")
                            append(layout.repo)
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        if (currentVersion != null) {
            item {
                Column {
                    Text(tr(R.string.s_banben_2, selectedVersion), style = MaterialTheme.typography.titleSmall)
                    if (currentVersion.changelog.isNotEmpty()) {
                        Text(
                            currentVersion.changelog,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (layout.versions.size > 1) {
            item {
                Column {
                    Text(tr(R.string.s_lishibanben), style = MaterialTheme.typography.titleSmall)
                    layout.versions.forEach { v ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.selectVersion(layout.id, v.version) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                v.version,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (v.version == selectedVersion) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (v.version == selectedVersion) FontWeight.SemiBold
                                else FontWeight.Normal,
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                v.date,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
        }

        if (layout.screenshots.isNotEmpty()) {
            item {
                // 截图缩略图横排：全宽渲染会把竖版键盘截图放大到整屏高，
                // 详情页被撑爆；点击仍进全屏预览（双指缩放）
                Text(tr(R.string.s_jietu), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(layout.screenshots) { url ->
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(url).crossfade(true).build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(120.dp)
                                .aspectRatio(9f / 19f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { previewUrl = url },
                        )
                    }
                }
            }
        }
    }

    previewUrl?.let { url ->
        ScreenshotPreviewDialog(url = url, onDismiss = { previewUrl = null })
    }
}

/** 全屏截图预览：点击/返回关闭，支持双指缩放与拖动。 */
@Composable
private fun ScreenshotPreviewDialog(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 5f)
            offset = if (scale > 1f) offset + panChange else Offset.Zero
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(url).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .transformable(transformState),
            )
        }
    }
}