package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * 日语假名九宫格（フリック入力）。
 *
 * 键位沿用日本手机十二键：点按出该键中心假名，向左/上/右/下滑动出对应段，**松手才提交**。
 * 每个假名对应的罗马音直接交给 Rime（japanese_kana 方案），
 * 假名显示与汉字转换由方案自己的 preedit_format / translator 完成，键盘不参与。
 *
 * 版面（5 列 × 4 行）：左功能列 / 3×4 假名区 / 右功能列。
 */

/** 滑动方向；[TAP] 表示位移未超过阈值（提交中心假名）。 */
internal enum class KanaDirection { TAP, LEFT, UP, RIGHT, DOWN }

/** 一个假名选项：[label] 是键面显示的假名，[code] 是送进 Rime 的罗马音。 */
internal data class KanaChoice(val label: String, val code: String)

/** 一个九宫格键：五个方向依次为 点按/左/上/右/下，null 表示该方向无假名。 */
internal data class KanaKey(val choices: List<KanaChoice?>) {
    val center: KanaChoice get() = choices[0]!!
    /** 取某方向的假名；只有中心假名的键（如 ー）不会越界，返回 null。 */
    fun choice(direction: KanaDirection): KanaChoice? = choices.getOrNull(direction.ordinal)
}

private fun kanaKey(labels: String, vararg codes: String?): KanaKey = KanaKey(
    labels.mapIndexed { index, label ->
        codes.getOrNull(index)?.let { KanaChoice(label.toString(), it) }
    }
)

// ── 假名键位：五个罗马音的顺序必须是 点按/左/上/右/下 ──
internal val kanaRowA = kanaKey("あいうえお", "a", "i", "u", "e", "o")
internal val kanaRowKa = kanaKey("かきくけこ", "ka", "ki", "ku", "ke", "ko")
internal val kanaRowSa = kanaKey("さしすせそ", "sa", "si", "su", "se", "so")
internal val kanaRowTa = kanaKey("たちつてと", "ta", "ti", "tu", "te", "to")
internal val kanaRowNa = kanaKey("なにぬねの", "na", "ni", "nu", "ne", "no")
internal val kanaRowHa = kanaKey("はひふへほ", "ha", "hi", "hu", "he", "ho")
internal val kanaRowMa = kanaKey("まみむめも", "ma", "mi", "mu", "me", "mo")
/** や行只有 や/ゆ/よ；左/右是括号，由方案 punctuator 转成全角 （ ）。 */
internal val kanaRowYa = kanaKey("や（ゆ）よ", "ya", "(", "yu", ")", "yo")
internal val kanaRowRa = kanaKey("らりるれろ", "ra", "ri", "ru", "re", "ro")
/** を/わ/ん/ー：中心 わ、左 を、上 ん、右 ー（长音符）。 */
internal val kanaRowWa = kanaKey("わをんー", "wa", "wo", "nn", "-")
/** 中心 、、左 。、上 ？、右 ！（标点键）。方案 punctuator 把半角转成全角。 */
internal val kanaRowPunct = kanaKey("、。？！", ",", ".", "?", "!")

/** 由位移量判定方向；阈值内视为点按。 */
internal fun kanaDirectionOf(dx: Float, dy: Float, threshold: Float): KanaDirection = when {
    max(abs(dx), abs(dy)) < threshold -> KanaDirection.TAP
    abs(dx) > abs(dy) -> if (dx < 0) KanaDirection.LEFT else KanaDirection.RIGHT
    dy < 0 -> KanaDirection.UP
    else -> KanaDirection.DOWN
}

@Composable
fun KanaKeyboardLayout(
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    specialKeyTextColor: Color = keyTextColor,
    keyboardBackgroundColor: Color = Color.Transparent,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
    /** 光标左右移动（← → 键）；null 时该键不出现 */
    onCursorMove: ((Int) -> Unit)? = null,
    /** 当前是否片假名模式（仅影响「カナ」键的显示状态） */
    katakanaMode: Boolean = false,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    keyCornerRadius: Dp = 8.dp,
    keySpacingX: Dp? = null,
    keySpacingY: Dp? = null,
) {
    CompositionLocalProvider(
        LocalKeyCornerRadius provides keyCornerRadius,
        LocalKeyVisualPadding provides PaddingValues(
            horizontal = keySpacingX ?: 2.dp,
            vertical = keySpacingY ?: 2.dp,
        ),
    ) {
        // 注意：这里**不能**自己画 keyboardBackgroundColor。
        // 与 T9KeyboardLayout 一致——背景（含主题透明/模糊/壁纸）由外层容器统一绘制，
        // 键面之间靠间隙透出。这里再 .background() 一层实色，就会把整块键盘区域
        // 盖成不透明色块（症状：工具栏以下一片死黑，主题的按键透明设置完全失效）。
        Row(
            modifier
                .fillMaxSize()
        ) {
            val special: @Composable (String, String?, () -> Unit, Modifier) -> Unit =
                { label, keycode, action, m ->
                    KanaSpecialKey(
                        text = label,
                        onClick = action,
                        modifier = m,
                        background = specialKeyBackgroundColor,
                        foreground = specialKeyTextColor,
                        onPress = keycode?.let { code -> { onKeyPressDown?.invoke(code) } },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )
                }

            // ── 左列：記号 / カナ / 123 / あA ──
            Column(Modifier.weight(1f).fillMaxHeight()) {
                special("記号", "symbol", { onKeyPress("symbol") }, Modifier.weight(1f))
                KanaSpecialKey(
                    text = "カナ",
                    onClick = { onKeyPress("kana_katakana") },
                    modifier = Modifier.weight(1f),
                    background = specialKeyBackgroundColor,
                    foreground = if (katakanaMode) keyTextColor else specialKeyTextColor,
                    onPress = { onKeyPressDown?.invoke("kana_katakana") },
                    highlighted = katakanaMode,
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
                special("123", "mode_change_number", { onKeyPress("mode_change_number") }, Modifier.weight(1f))
                special("あA", "ime_switch", { onKeyPress("ime_switch") }, Modifier.weight(1f))
            }

            // ── 中列：3×4 假名 ＋ 小字键 ──
            Column(Modifier.weight(3f).fillMaxHeight()) {
                val rows = listOf(
                    listOf(kanaRowA, kanaRowKa, kanaRowSa),
                    listOf(kanaRowTa, kanaRowNa, kanaRowHa),
                    listOf(kanaRowMa, kanaRowYa, kanaRowRa),
                )
                for (row in rows) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        for (key in row) {
                            KanaFlickKey(
                                key = key,
                                onCode = onKeyPress,
                                onPressDown = onKeyPressDown,
                                background = keyBackgroundColor,
                                foreground = keyTextColor,
                                modifier = Modifier.weight(1f),
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                            )
                        }
                    }
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    KanaModifierKey(
                        onAction = onKeyPress,
                        onPressDown = onKeyPressDown,
                        background = keyBackgroundColor,
                        foreground = keyTextColor,
                        modifier = Modifier.weight(1f),
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )
                    KanaFlickKey(
                        key = kanaRowWa,
                        onCode = onKeyPress,
                        onPressDown = onKeyPressDown,
                        background = keyBackgroundColor,
                        foreground = keyTextColor,
                        modifier = Modifier.weight(1f),
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )
                    KanaFlickKey(
                        key = kanaRowPunct,
                        onCode = onKeyPress,
                        onPressDown = onKeyPressDown,
                        background = keyBackgroundColor,
                        foreground = keyTextColor,
                        modifier = Modifier.weight(1f),
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )
                }
            }

            // ── 右列：退格 / → / 空格 / 改行 ──
            Column(Modifier.weight(1f).fillMaxHeight()) {
                KanaBackspaceKey(
                    onKeyPress = onKeyPress,
                    onPressDown = onKeyPressDown,
                    background = specialKeyBackgroundColor,
                    foreground = specialKeyTextColor,
                    modifier = Modifier.weight(1f),
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
                special("→", null, { onCursorMove?.invoke(1) }, Modifier.weight(1f))
                special("空格", "space", { onKeyPress("space") }, Modifier.weight(1f))
                special("⏎", "enter", { onKeyPress("enter") }, Modifier.weight(1f))
            }
        }
    }
}

/**
 * 假名键：点按提交中心假名，滑动超过阈值后**松手**提交该方向假名。
 *
 * 手势自行实现（awaitEachGesture），不读方案的 swipe 配置：
 * 假名键需要「五个方向各一个字符 + 松手才提交」，与 26 键的上/下滑语义不同。
 */
@Composable
private fun KanaFlickKey(
    key: KanaKey,
    onCode: (String) -> Unit,
    onPressDown: ((String) -> Unit)?,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { 18.dp.toPx() }
    val currentOnCode by rememberUpdatedState(onCode)
    val currentOnPressDown by rememberUpdatedState(onPressDown)
    val cornerRadius = LocalKeyCornerRadius.current
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val visualPadding = LocalKeyVisualPadding.current
    // 假名键靠横向滑动选段，必须把父层的「横滑移光标」压住，
    // 否则滑动时输入串里的插入点会跟着动（与 26 键符号滑动同一机制）。
    val suppressCursorMove = LocalSuppressCursorMove.current

    var pressed by remember { mutableStateOf(false) }
    var direction by remember { mutableStateOf(KanaDirection.TAP) }

    val shadowModifier = remember(shadowEnabled, background, shadowElevation, shadowShapeRadius, density) {
        if (!shadowEnabled) Modifier else Modifier.drawBehind {
            drawRoundRect(
                color = crispShadowColor(background),
                topLeft = Offset(0f, shadowElevation.toPx()),
                size = size,
                cornerRadius = CornerRadius(shadowShapeRadius.toPx()),
            )
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxHeight()
            .padding(visualPadding)
            .pointerInput(key) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    suppressCursorMove.value = true
                    direction = KanaDirection.TAP
                    currentOnPressDown?.invoke(key.center.code)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.pressed) {
                                val dx = change.position.x - down.position.x
                                val dy = change.position.y - down.position.y
                                val next = kanaDirectionOf(dx, dy, thresholdPx)
                                if (next != direction) direction = next
                                change.consume()
                            } else {
                                change.consume()
                                break
                            }
                        }
                    } finally {
                        // 抬手 / 手势被系统取消都要复位，否则整块键盘的光标手势会一直失效
                        pressed = false
                        suppressCursorMove.value = false
                    }
                    // 该方向没有假名时（如 ー 键）回落到中心假名，避免"滑了却什么都没出"
                    val choice = if (direction == KanaDirection.TAP) key.center
                        else key.choice(direction) ?: key.center
                    direction = KanaDirection.TAP
                    currentOnCode(choice.code)
                }
            }
            .then(shadowModifier)
            .clip(shape)
            .background(if (pressed) background.copy(alpha = 0.72f) else background)
            // 主题描边（key_background.border_color）：KeyButton / SwipeableIconKeyButton
            // 内部已自带这一层，自绘的假名键与小字键必须手动对齐，否则描边只出现在功能键上。
            .border(LocalKeyBorder.current ?: BorderStroke(0.dp, Color.Transparent), shape),
        contentAlignment = Alignment.Center,
    ) {
        val keyWidth = maxWidth.value
        val keyHeight = maxHeight.value
        val centerSize = minOf(keyHeight * 0.30f, keyWidth * 0.40f).coerceIn(13f, 24f).sp
        val hintSize = (centerSize.value * 0.46f).coerceIn(7f, 11f).sp
        val pending = if (direction == KanaDirection.TAP) key.center
            else key.choice(direction) ?: key.center
        val hintAlpha = 0.42f

        Text(
            text = pending.label,
            color = foreground,
            fontSize = centerSize,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        if (direction == KanaDirection.TAP) {
            key.choice(KanaDirection.LEFT)?.let {
                Text(it.label, Modifier.align(Alignment.CenterStart).padding(start = 5.dp),
                    color = foreground.copy(alpha = hintAlpha), fontSize = hintSize, maxLines = 1)
            }
            key.choice(KanaDirection.RIGHT)?.let {
                Text(it.label, Modifier.align(Alignment.CenterEnd).padding(end = 5.dp),
                    color = foreground.copy(alpha = hintAlpha), fontSize = hintSize, maxLines = 1)
            }
            key.choice(KanaDirection.UP)?.let {
                Text(it.label, Modifier.align(Alignment.TopCenter).padding(top = 2.dp),
                    color = foreground.copy(alpha = hintAlpha), fontSize = hintSize, maxLines = 1)
            }
            key.choice(KanaDirection.DOWN)?.let {
                Text(it.label, Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
                    color = foreground.copy(alpha = hintAlpha), fontSize = hintSize, maxLines = 1)
            }
        }
    }
}

/**
 * 假名变换键：点按 = 小字（っ/ゃ/ぁ…），左滑 = 浊音，右滑 = 半浊音。
 *
 * 三个动作都由服务层改写罗马音输入串完成，键盘只负责派发哪一个动作；
 * 当前没有可变换的假名时服务层静默返回（键面无反馈即"无效"）。
 */
@Composable
private fun KanaModifierKey(
    onAction: (String) -> Unit,
    onPressDown: ((String) -> Unit)?,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { 18.dp.toPx() }
    val currentOnAction by rememberUpdatedState(onAction)
    val currentOnPressDown by rememberUpdatedState(onPressDown)
    val cornerRadius = LocalKeyCornerRadius.current
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val visualPadding = LocalKeyVisualPadding.current
    val suppressCursorMove = LocalSuppressCursorMove.current

    var pressed by remember { mutableStateOf(false) }
    var direction by remember { mutableStateOf(KanaDirection.TAP) }

    val shadowModifier = remember(shadowEnabled, background, shadowElevation, shadowShapeRadius, density) {
        if (!shadowEnabled) Modifier else Modifier.drawBehind {
            drawRoundRect(
                color = crispShadowColor(background),
                topLeft = Offset(0f, shadowElevation.toPx()),
                size = size,
                cornerRadius = CornerRadius(shadowShapeRadius.toPx()),
            )
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxHeight()
            .padding(visualPadding)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    suppressCursorMove.value = true
                    direction = KanaDirection.TAP
                    currentOnPressDown?.invoke("kana_small")
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.pressed) {
                                val dx = change.position.x - down.position.x
                                val dy = change.position.y - down.position.y
                                if (max(abs(dx), abs(dy)) >= thresholdPx && abs(dx) > abs(dy)) {
                                    direction = if (dx < 0) KanaDirection.LEFT else KanaDirection.RIGHT
                                } else if (max(abs(dx), abs(dy)) < thresholdPx) {
                                    direction = KanaDirection.TAP
                                }
                                change.consume()
                            } else {
                                change.consume()
                                break
                            }
                        }
                    } finally {
                        pressed = false
                        suppressCursorMove.value = false
                    }
                    val action = when (direction) {
                        KanaDirection.LEFT -> "kana_voice"
                        KanaDirection.RIGHT -> "kana_semi"
                        else -> "kana_small"
                    }
                    direction = KanaDirection.TAP
                    currentOnAction(action)
                }
            }
            .then(shadowModifier)
            .clip(shape)
            .background(if (pressed) background.copy(alpha = 0.72f) else background)
            // 主题描边（key_background.border_color）：KeyButton / SwipeableIconKeyButton
            // 内部已自带这一层，自绘的假名键与小字键必须手动对齐，否则描边只出现在功能键上。
            .border(LocalKeyBorder.current ?: BorderStroke(0.dp, Color.Transparent), shape),
        contentAlignment = Alignment.Center,
    ) {
        val keyWidth = maxWidth.value
        val keyHeight = maxHeight.value
        val centerSize = minOf(keyHeight * 0.26f, keyWidth * 0.34f).coerceIn(12f, 21f).sp
        val hintSize = (centerSize.value * 0.5f).coerceIn(8f, 12f).sp
        val label = when (direction) {
            KanaDirection.LEFT -> "゛"
            KanaDirection.RIGHT -> "゜"
            else -> "小"
        }
        Text(
            text = label,
            color = foreground,
            fontSize = centerSize,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        if (direction == KanaDirection.TAP) {
            Text("゛", Modifier.align(Alignment.CenterStart).padding(start = 5.dp),
                color = foreground.copy(alpha = 0.42f), fontSize = hintSize, maxLines = 1)
            Text("゜", Modifier.align(Alignment.CenterEnd).padding(end = 5.dp),
                color = foreground.copy(alpha = 0.42f), fontSize = hintSize, maxLines = 1)
        }
    }
}

/** 退格键：点按删一次，**长按连删**（与中英文键盘同一套连发机制）。 */
@Composable
private fun KanaBackspaceKey(
    onKeyPress: (String) -> Unit,
    onPressDown: ((String) -> Unit)?,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {
    SwipeableIconKeyButton(
        icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
        onClick = { onKeyPress("delete") },
        onLongClick = { onKeyPress("delete") },
        backgroundColor = background,
        iconColor = foreground,
        modifier = modifier.padding(LocalKeyVisualPadding.current),
        onPress = { onPressDown?.invoke("delete") },
        a11yDescription = stringResource(R.string.keyboard_delete),
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
    )
}

/** 普通功能键：点按即提交，不做滑动。 */
@Composable
private fun KanaSpecialKey(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color,
    foreground: Color,
    onPress: (() -> Unit)? = null,
    highlighted: Boolean = false,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    fontSize: TextUnit = 15.sp,
) {
    KeyButton(
        text = text,
        onClick = onClick,
        backgroundColor = background,
        textColor = foreground,
        modifier = modifier.padding(LocalKeyVisualPadding.current),
        onPress = onPress,
        isHighlighted = highlighted,
        fontSize = fontSize,
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
    )
}
