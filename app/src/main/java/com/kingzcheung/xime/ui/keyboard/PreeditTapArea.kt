package com.kingzcheung.xime.ui.keyboard

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 编码气泡的可点区域（**窗口坐标**）。
 *
 * 编码气泡由 [drawPreeditBubble] 用 drawBehind 以**负坐标**画在候选栏顶边之外的
 * inputArea 空白区里——那块区域按 IME 的 `visibleTopInsets` 属于「不可触摸区」，
 * 触摸会直接交给宿主应用。要让气泡可点，必须在服务层把这块矩形并进窗口触摸区
 * （[android.view.inputmethod.InputMethodManager] 的 `TOUCHABLE_INSETS_REGION`），
 * 再由原生层做命中判定并把落点换算成字符索引回传。
 *
 * 这里只做纯几何：矩形范围 + 每个字符边界的 x（命中时取最近边界）。
 */
class PreeditTapArea(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** 每个字符边界的窗口 x，长度 = 字符数 + 1（第 0 项是文本起点）。 */
    val charBoundsX: FloatArray,
) {
    val isEmpty: Boolean
        get() = right <= left || bottom <= top || charBoundsX.size < 2

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom

    /** 落点最近的字符边界索引（= 落点左侧的字符数，0..字符数）。 */
    fun indexAt(x: Float): Int {
        var best = 0
        var bestDist = Float.MAX_VALUE
        for (i in charBoundsX.indices) {
            val d = abs(charBoundsX[i] - x)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    fun sameAs(other: PreeditTapArea?): Boolean {
        if (other == null) return false
        if (left != other.left || top != other.top ||
            right != other.right || bottom != other.bottom
        ) return false
        if (charBoundsX.size != other.charBoundsX.size) return false
        for (i in charBoundsX.indices) {
            if (abs(charBoundsX[i] - other.charBoundsX[i]) > 0.5f) return false
        }
        return true
    }
}

/** 气泡几何（相对候选栏容器，绘制与命中判定共用同一份计算，避免两处漂移）。 */
internal class PreeditBubbleGeometry(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    /** 文本起点相对容器左缘（= left + 水平内边距）。 */
    val textLeft: Float,
    /** 每个字符边界相对**文本起点**的 x，长度 = 字符数 + 1。 */
    val charXs: FloatArray,
)

/**
 * 计算气泡几何。绘制（[drawPreeditBubble]）与可点区域上报共用，保证像素级一致。
 *
 * [containerWidth] 为候选栏宽度：气泡贴左缘、右向延伸，超出宿主右缘时整体左移钳制。
 */
internal fun computePreeditBubbleGeometry(
    text: String,
    containerWidth: Float,
    paint: android.graphics.Paint,
    horizontalPaddingPx: Float,
    verticalPaddingPx: Float,
    bottomGapPx: Float,
    screenMarginPx: Float,
): PreeditBubbleGeometry {
    val fontMetrics = paint.fontMetrics
    val textWidth = paint.measureText(text)
    val textHeight = fontMetrics.descent - fontMetrics.ascent
    val width = textWidth + horizontalPaddingPx * 2
    val height = textHeight + verticalPaddingPx * 2

    val left = max(
        screenMarginPx,
        min(0f, containerWidth - width - screenMarginPx).coerceAtLeast(screenMarginPx / 4f)
    )
    // 底部间隙：气泡底缘距候选栏顶缘 bottomGapPx
    val top = -bottomGapPx - height

    val charXs = FloatArray(text.length + 1)
    for (i in 1..text.length) {
        charXs[i] = paint.measureText(text, 0, i)
    }
    return PreeditBubbleGeometry(left, top, width, height, left + horizontalPaddingPx, charXs)
}

/** 编码显示串中的音节分隔符（不是编码位，光标与索引都要跳过）。 */
private fun isPreeditSeparator(ch: Char): Boolean = ch == '\'' || ch == ' '

/**
 * 「显示串字符索引」→「编码位索引（第几位数字/字母）」：数分隔符之前有几个非分隔符字符。
 */
fun preeditCharIndexToCodeIndex(displayText: String, charIndex: Int): Int {
    var count = 0
    val end = charIndex.coerceIn(0, displayText.length)
    for (i in 0 until end) {
        if (!isPreeditSeparator(displayText[i])) count++
    }
    return count
}

/**
 * 「编码位索引」→「显示串字符索引」（气泡竖线位置）：第 n 个非分隔符字符的位置。
 * 超出编码位数返回 [displayText] 长度（竖线画在末尾，编辑态可见）；编码为空返回 -1。
 */
fun codeIndexToPreeditCharIndex(displayText: String, codeIndex: Int): Int {
    if (codeIndex < 0 || displayText.isEmpty()) return -1
    var seen = 0
    for (i in displayText.indices) {
        if (isPreeditSeparator(displayText[i])) continue
        if (seen == codeIndex) return i
        seen++
    }
    return displayText.length
}
