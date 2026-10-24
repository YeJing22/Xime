package com.kingzcheung.xime.ui.keyboard

import kotlin.math.abs
import kotlin.math.max

/** 编码气泡的基础字号（sp）。编辑态按 [PREEDIT_EDIT_SCALE] 放大。 */
internal const val PREEDIT_BASE_TEXT_SP = 12f

/** 编辑态放大倍数：约等于商业输入法「点进编码后放大编辑」的观感。 */
internal const val PREEDIT_EDIT_SCALE = 1.8f

/**
 * 编码气泡的可点区域（**窗口坐标**）。
 *
 * 编码气泡由 [drawPreeditBubble] 用 drawBehind 以**负坐标**画在候选栏顶边之外的
 * inputArea 空白区里——那块区域按 IME 的 `visibleTopInsets` 属于「不可触摸区」，
 * 触摸会直接交给宿主应用。要让气泡可点，必须在服务层把这块矩形并进窗口触摸区
 * （`TOUCHABLE_INSETS_REGION`），再由原生层做命中判定并把落点换算成字符索引回传。
 *
 * 编辑态气泡会放大并按音节换行，所以命中判定是**二维**的：先按 y 选行，再按 x 取
 * 该行最近的字符边界。
 */
class PreeditTapArea(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** 每行首个字符在显示串中的索引。 */
    private val lineStarts: IntArray,
    /** 每行字符边界的窗口 x，长度 = 该行字符数 + 1（第 0 项是该行文本起点）。 */
    private val lineCharBoundsX: Array<FloatArray>,
    /** 每行垂直中心（窗口 y），用于「先选行」。 */
    private val lineCenterY: FloatArray,
) {
    val isEmpty: Boolean
        get() = right <= left || bottom <= top || lineCharBoundsX.isEmpty()

    /** 参与命中的字符数（不含音节分隔符），仅用于诊断日志。 */
    val charCount: Int
        get() = lineCharBoundsX.sumOf { max(0, it.size - 1) }

    val lineCount: Int
        get() = lineCharBoundsX.size

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom

    /** 落点最近的字符边界 → 显示串字符索引（0..文本长度）。 */
    fun indexAt(x: Float, y: Float): Int {
        if (lineCharBoundsX.isEmpty()) return 0
        var li = 0
        var bestLine = Float.MAX_VALUE
        for (i in lineCenterY.indices) {
            val d = abs(lineCenterY[i] - y)
            if (d < bestLine) {
                bestLine = d
                li = i
            }
        }
        val bounds = lineCharBoundsX[li]
        var bi = 0
        var bestBound = Float.MAX_VALUE
        for (i in bounds.indices) {
            val d = abs(bounds[i] - x)
            if (d < bestBound) {
                bestBound = d
                bi = i
            }
        }
        return lineStarts[li] + bi
    }

    fun sameAs(other: PreeditTapArea?): Boolean {
        if (other == null) return false
        if (left != other.left || top != other.top ||
            right != other.right || bottom != other.bottom
        ) return false
        if (lineCharBoundsX.size != other.lineCharBoundsX.size) return false
        for (i in lineCharBoundsX.indices) {
            if (lineStarts[i] != other.lineStarts[i]) return false
            if (abs(lineCenterY[i] - other.lineCenterY[i]) > 0.5f) return false
            val a = lineCharBoundsX[i]
            val b = other.lineCharBoundsX[i]
            if (a.size != b.size) return false
            for (k in a.indices) {
                if (abs(a[k] - b[k]) > 0.5f) return false
            }
        }
        return true
    }
}

/** 编码气泡的一行（相对候选栏容器的坐标）。 */
internal class PreeditBubbleLine(
    /** 该行首个字符在显示串中的索引。 */
    val startCharIndex: Int,
    /** 该行实际绘制的文本（行尾分隔符已裁掉）。 */
    val text: String,
    /** 行顶相对**气泡顶**（已含上内边距）。 */
    val top: Float,
    /** 字形高度（ascent..descent）。 */
    val height: Float,
    /** 每个字符边界相对该行文本起点的 x，长度 = 字符数 + 1。 */
    val charXs: FloatArray,
)

/** 气泡几何（相对候选栏容器，绘制与命中判定共用同一份计算，避免两处漂移）。 */
internal class PreeditBubbleGeometry(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    /** 文本起点相对容器左缘（= left + 水平内边距）。 */
    val textLeft: Float,
    val lines: List<PreeditBubbleLine>,
    /** 首行基线相对气泡顶的偏移（绘制用）。 */
    val firstBaseline: Float,
    /** 行距（相邻两行基线之差）。 */
    val lineSpacing: Float,
) {
    /** 给定显示串字符索引，返回 (行下标, 行内字符偏移)。 */
    fun locate(caretCharIndex: Int): Pair<PreeditBubbleLine, Int> {
        var line = lines.first()
        for (l in lines) {
            if (caretCharIndex >= l.startCharIndex) line = l
        }
        val local = (caretCharIndex - line.startCharIndex).coerceIn(0, line.text.length)
        return line to local
    }
}

/** 行距系数：1.0 = 字形紧贴，放大后行间需要一点余量才不挤。 */
private const val LINE_SPACING = 1.16f

/**
 * 计算气泡几何。绘制（[drawPreeditBubble]）与可点区域上报共用，保证像素级一致。
 *
 * [scale] 为放大倍数（编辑态 1.8）：文字按比例放大，内边距只放大 40%——纯等比会让
 * 气泡显得过于臃肿。宽度超出可用宽度时按**音节**换行（不切断单个音节），
 * 因此气泡最宽不会超过「容器宽 - 两侧安全边距」，长编码不会画到屏幕外。
 */
internal fun computePreeditBubbleGeometry(
    text: String,
    containerWidth: Float,
    paint: android.graphics.Paint,
    baseTextSizePx: Float,
    baseHorizontalPaddingPx: Float,
    baseVerticalPaddingPx: Float,
    bottomGapPx: Float,
    screenMarginPx: Float,
    scale: Float = 1f,
): PreeditBubbleGeometry {
    // 注意：textSize 是画笔状态。绘制（动画中的 scale）与上报（目标 scale）都先设再量，
    // 二者都在主线程串行执行，共用一个实例不会互相干扰。
    paint.textSize = baseTextSizePx * scale
    val padScale = 1f + (scale - 1f) * 0.4f
    val hPad = baseHorizontalPaddingPx * padScale
    val vPad = baseVerticalPaddingPx * padScale

    val fontMetrics = paint.fontMetrics
    val textHeight = fontMetrics.descent - fontMetrics.ascent
    val lineSpacing = textHeight * LINE_SPACING

    // 可用文本宽：两侧各留安全边距 + 内边距，保证气泡整体不会越出容器
    val maxTextWidth = max(1f, containerWidth - screenMarginPx * 2 - hPad * 2)
    val ranges = wrapPreeditLines(text, paint, maxTextWidth)

    val lines = ranges.mapIndexed { index, range ->
        val lineText = text.substring(range.first, range.last + 1)
        val charXs = FloatArray(lineText.length + 1)
        for (i in 1..lineText.length) {
            charXs[i] = paint.measureText(lineText, 0, i)
        }
        PreeditBubbleLine(
            startCharIndex = range.first,
            text = lineText,
            top = vPad + index * lineSpacing,
            height = textHeight,
            charXs = charXs,
        )
    }

    val maxLineWidth = lines.maxOfOrNull { paint.measureText(it.text) } ?: 0f
    val width = maxLineWidth + hPad * 2
    val contentHeight = textHeight + (lines.size - 1).coerceAtLeast(0) * lineSpacing
    val height = contentHeight + vPad * 2

    return PreeditBubbleGeometry(
        // 气泡贴候选栏左缘（原实现的 clamp 计算恒等于安全边距，这里显式写清）
        left = screenMarginPx,
        // 底部间隙：气泡底缘距候选栏顶缘 bottomGapPx
        top = -bottomGapPx - height,
        width = width,
        height = height,
        textLeft = screenMarginPx + hPad,
        lines = lines,
        firstBaseline = vPad - fontMetrics.ascent,
        lineSpacing = lineSpacing,
    )
}

/**
 * 按音节换行：分隔符（' 或空格）是天然断点，绝不切断单个音节。
 * 单个音节块本身超宽（几乎不可能，拼音音节最长 6 字母）时才退化为按字符切。
 */
private fun wrapPreeditLines(
    text: String,
    paint: android.graphics.Paint,
    maxWidth: Float,
): List<IntRange> {
    if (text.isEmpty()) return listOf(0..0)
    val out = ArrayList<IntRange>()
    var start = 0
    while (start < text.length) {
        // 行首跳过音节分隔符（换行后不该以空格起头）
        while (start < text.length && isPreeditSeparator(text[start])) start++
        if (start >= text.length) break

        var end = start
        var lastBreak = start
        while (end < text.length) {
            var j = end
            while (j < text.length && !isPreeditSeparator(text[j])) j++
            while (j < text.length && isPreeditSeparator(text[j])) j++
            if (paint.measureText(text, start, j) > maxWidth) break
            lastBreak = j
            end = j
        }

        var lineEnd = lastBreak
        if (lineEnd <= start) {
            // 单个音节块就超宽：按字符切，至少吃进一个字符，避免死循环
            var k = start + 1
            var fit = start + 1
            while (k < text.length) {
                if (paint.measureText(text, start, k) > maxWidth) break
                fit = k
                k++
            }
            lineEnd = fit
        }

        // 行尾不留分隔符
        var trimmedEnd = lineEnd
        while (trimmedEnd > start && isPreeditSeparator(text[trimmedEnd - 1])) trimmedEnd--
        if (trimmedEnd > start) out.add(start until trimmedEnd)
        start = lineEnd
    }
    return if (out.isEmpty()) listOf(0..0) else out
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
