package com.kingzcheung.xime.rime

import com.kingzcheung.xime.util.FileLogger
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 九键拼音输入控制器（薄包装）。
 *
 * T9 核心逻辑（数字缓冲、三态状态机、右选消费算法、撤销管理等）已整体迁移到
 * librime-t9（C++ 插件）中实现。本类仅通过 [RimeEngine] 的 JNI 接口与 C++ 层
 * 交互，并维护 UI 展示所需的 Compose 状态。
 *
 * 原 Kotlin 侧 T9Buffer / T9StateMachine / T9UndoManager / T9RightCommitHandler /
 * T9RimeBridge / T9PinyinMap 等类均已删除，由 C++ 同名组件替代。
 *
 * 异步执行模型（遗留问题 1 修复 + 方案 B 加固）：
 * - 按键处理（processKey → FlushRimeInput → 取结果）整体运行在**单线程后台队列**，
 *   引擎 compose（2-23ms，长输入可达 250ms）不阻塞 UI 线程；单线程 FIFO 保证连打
 *   按键的 C++ pending 标记顺序不乱。
 * - flush 后通过 [RimeEngine.getProcessResult] 一次 JNI 拿全量结果（input/preedit/
 *   committed/翻页），替代重复的 getComposition 调用（每键 6→5 次 JNI，全部后台）。
 * - UI 更新通过 [mainHandler] 投递到 Main 线程，**后台任务完成不依赖 Main**，
 *   避免 [awaitT9Queue]（runBlocking 等待队列）与 Main 派发之间死锁。
 * - **方案 B**：reset/clearAll 等清理入口不再在主线程 runBlocking 等待队列——
 *   本地 Compose 状态立即复位，C++ 清空排入同一队列（与后续按键保序，主线程零等待）。
 * - 唯一保留同步等待的入口是 onRightCandidateSelected（服务层在 keyProcessingDispatcher
 *   后台线程调用，需同步返回 full-commit 标志）：先 [awaitT9Queue] 等待队列排空，
 *   避免 pending 覆盖导致消费计算错乱——阻塞的是该后台线程，而非主线程。
 */
class T9InputController(
    private val rimeEngine: RimeEngine = RimeEngine.getInstance(),
    private val onCompositionRefresh: ((RimeComposition, List<com.kingzcheung.xime.service.T9CandidateInjection>) -> Unit)? = null,
    private val onRightCommitUndone: ((Int) -> Unit)? = null,
    /** 候选词变换（hotPath 插件能力）：后台取数后、post 主线程前同步调用
     *  （阻塞至多 15ms，主线程零等待）；返回带引擎锚点的插件候选注入列表
     *  （text 追加项），引擎结果原样使用（T9 不支持引擎引用替换）。null = 不干预。 */
    private val candidateTransform: ((RimeProcessResult) -> List<com.kingzcheung.xime.service.T9CandidateInjection>?)? = null,
) {
    companion object {
        private const val TAG = "T9InputController"
        const val CLEAR_COMPOSITION_ONLY = "clear_composition"
        const val CLEAR_ALL = "clear_all"

        /** 字母 → 九键数字位（ü/v 归 8，与 nv / lv 的读音一致）。 */
        private fun letterToDigit(c: Char): Char? = when (c.lowercaseChar()) {
            in 'a'..'c' -> '2'
            in 'd'..'f' -> '3'
            in 'g'..'i' -> '4'
            in 'j'..'l' -> '5'
            in 'm'..'o' -> '6'
            in 'p'..'s' -> '7'
            in 't'..'v', 'ü' -> '8'
            in 'w'..'z' -> '9'
            else -> null
        }

        /**
         * 把 RIME 输入串折算成纯数字编码。
         * 输入串形如 `liu'yu'7464`（已锁定音节是字母、未分配段是数字），
         * 折算后为 `548987464`，与 T9Buffer.digit_sequence 等长同位。
         */
        fun toDigitSequence(input: String): String = buildString {
            for (c in input) {
                when {
                    c.isDigit() -> append(c)
                    c == '\'' || c == ' ' -> Unit
                    else -> letterToDigit(c)?.let { append(it) }
                }
            }
        }
    }

    enum class LeftPanelState { IDLE, INPUT, SELECTION }

    enum class DeleteResult {
        DELETED, UNDO_CHOICE, UNDO_COMMIT, NOT_CONSUMED
    }

    /** 音节选项（原 T9PinyinMap.SyllableOption，已内联至此） */
    data class SyllableOption(
        val pinyin: String,
        val digitLength: Int
    )

    // ── 异步执行模型 ──
    private val t9Dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val t9Scope = CoroutineScope(t9Dispatcher)
    private var lastT9Job: Job? = null

    /** UI 更新投递目标（后台任务通过 post 派发，不阻塞任务完成）。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * UI 刷新代际号：每次发起刷新自增。异步 post 到达 Main 时若代际已过期则丢弃，
     * 防止「同步刷新（如右选）→ 旧的异步刷新 post 后执行」把 UI 回退到旧状态。
     */
    private var uiGeneration = 0

    /** 长按退格合并锁/状态：同一时刻至多一个退格 job，累积请求由 [pendingDeleteCount] 记录。 */
    private val deleteCoalesceLock = Any()
    private var deleteJobActive = false
    private var pendingDeleteCount = 0

    /**
     * 最近一次 flush 快照的 input 缓存（@Volatile，后台 fetchAll 写入、主线程读取）。
     * bufferString/inputBuffer 不再主线程调 [RimeEngine.getInput]（消除主线程 JNI），
     * 且与 preedit/候选/左栏同源于一次 getProcessResult → 保证 UI 元素同帧同步。
     */
    @Volatile
    private var cachedInput: String = ""

    /** 将 T9 处理任务排入单线程后台队列（FIFO 保序）。 */
    private fun enqueue(block: suspend CoroutineScope.() -> Unit) {
        val job = t9Scope.launch { block() }
        lastT9Job = job
    }

    /**
     * 同步等待后台队列排空（仅 onRightCandidateSelected 使用）。
     * 该方法必须且只会被**非主线程**调用（服务层 keyProcessingDispatcher），
     * 阻塞的是该后台线程而非 UI；队列空闲时立即返回。
     */
    private fun awaitT9Queue() {
        lastT9Job?.let { runBlocking { it.join() } }
    }

    /** UI 显示的缓冲区字符串 = 最近一次 flush 快照的 input（缓存，非主线程 JNI） */
    val bufferString: String get() {
        val input = cachedInput
        return when {
            input.isEmpty() && _committedText != null -> _committedText!!
            input.isEmpty() -> ""
            else -> input
        }
    }

    /** 当前输入缓冲区（供 KeyboardView 判断右选是否完整消费；缓存值，非主线程 JNI） */
    val inputBuffer: String get() = cachedInput

    var firstOptions: List<SyllableOption> by mutableStateOf(emptyList())
        private set

    var leftPanelState: LeftPanelState by mutableStateOf(LeftPanelState.IDLE)
        private set

    var selectedOption: SyllableOption? by mutableStateOf(null)
        private set

    var selectionCandidateDigits: String? by mutableStateOf(null)
        private set

    var leftColumnLocked: Boolean by mutableStateOf(false)
        private set

    val selectionHistory: List<SyllableOption> get() = _selectionHistory
    private var _selectionHistory: List<SyllableOption> = emptyList()

    private var _committedText: String? = null

    /**
     * 重置（输入会话开始/切换键盘时调用，主线程）。
     * 本地 Compose 状态立即复位；C++ 状态清空排入后台队列——与后续按键同队列保序，
     * 主线程零等待（方案 B）。
     */
    fun reset() {
        // 丢弃旧会话尚未执行的刷新 post，避免复位后被旧状态覆盖
        uiGeneration++
        endCodeEdit()
        _selectionHistory = emptyList()
        firstOptions = emptyList()
        leftPanelState = LeftPanelState.IDLE
        selectedOption = null
        selectionCandidateDigits = null
        leftColumnLocked = false
        _committedText = null
        cachedInput = ""
        enqueue { rimeEngine.t9ClearComposition(1) }
    }

    /**
     * 公共刷新入口（同步路径使用，低频）。
     * 一次 JNI 拿全量 composition 结果 + 左栏面板/候选，重建左栏状态。
     */
    fun updateCandidates(force: Boolean = false) {
        val data = fetchAll()
        applyCandidates(data.result, data.panel, data.options)
    }

    /**
     * 候选词变换（hotPath 插件能力）：引擎结果原样 + 插件候选注入列表。
     * t9Dispatcher 线程同步等插件至多 15ms；失败/不干预返回空注入。
     */
    private fun transformInjections(result: RimeProcessResult): Pair<RimeProcessResult, List<com.kingzcheung.xime.service.T9CandidateInjection>> {
        val injections = candidateTransform?.invoke(result) ?: emptyList()
        return result to injections
    }

    /**
     * 后台线程：flush 后一次取全量结果 + 左栏数据，经 [mainHandler] 投递到 Main
     * 更新 Compose 状态，并携带 composition 通知服务层刷新（避免其内部重复
     * getComposition）。post 为 fire-and-forget，任务完成不依赖 Main 线程；
     * 携带代际号，过期刷新在 Main 执行时丢弃。
     */
    private fun refreshOnBackground() {
        val data = fetchAll()
        val (finalResult, injections) = transformInjections(data.result)
        val composition = finalResult.toComposition()
        val gen = ++uiGeneration
        mainHandler.post {
            if (gen != uiGeneration) return@post
            onCompositionRefresh?.invoke(composition, injections)
            applyCandidates(finalResult, data.panel, data.options)
        }
    }

    /** 一次 flush 后取回的全部刷新数据（composition 全量 + 左栏面板 + 首音节候选）。 */
    private data class T9RefreshData(
        val result: RimeProcessResult,
        val panel: LeftPanelInfo,
        val options: List<Pair<String, Int>>,
    )

    /**
     * 后台取全量数据：一次 JNI（getProcessResult）拿 composition + T9 左栏面板 +
     * 首音节候选（C++ readCurrentState 填充），Main 线程只做解析与状态赋值。
     */
    private fun fetchAll(): T9RefreshData {
        val result = rimeEngine.getProcessResult(true)
        // 更新主线程可读的 input 快照（与 preedit/候选/左栏同源于本次 flush）
        cachedInput = result.inputText
        // 诊断（2026-10-23）：C++ 侧 buffer 真实状态。
        // panel 串 = STATE;SELECTED_PINYIN;SEL_DIGIT_LEN;SEL_DIGITS;PANEL_DIGITS;LEFT_LOCKED，
        // 比 Kotlin 的显示文本更能说明「右选后到底剩了哪几位数字/哪个音节没消费」。
        FileLogger.d(
            TAG,
            "T9 state: input='${result.inputText}' panel='${result.t9PanelState}' " +
                "remaining='${rimeEngine.t9GetRemainingDigits()}' committed='${result.committedText}'"
        )
        val panel = parseLeftPanelState(result.t9PanelState.ifEmpty { "IDLE;;;;;0" })
        val options = parseSyllableOptions(result.t9SyllableOptions)
        return T9RefreshData(result, panel, options)
    }

    /** 解析 JNI 返回的 "pinyin|digitLength,..." 首音节候选串。 */
    private fun parseSyllableOptions(raw: String): List<Pair<String, Int>> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(",").mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.size == 2) {
                Pair(parts[0], parts[1].toIntOrNull() ?: 1)
            } else null
        }
    }

    /**
     * 基于一次取回的全量结果重建左栏/面板状态（Main 线程执行）。
     * PANEL_DIGITS 已内含分词键锁定 + unassigned + selectionCandidateDigits +
     * separatorConsumedDigits 回退逻辑，确保混合输入（如 5'43）时左侧候选只显示
     * 当前数字段（5 → j/k/l）而非整段过滤结果（543 → jie/lie）。
     */
    private fun applyCandidates(
        result: RimeProcessResult,
        panel: LeftPanelInfo,
        options: List<Pair<String, Int>>,
    ) {
        val rawInput = result.inputText

        if (rawInput.isEmpty() && _committedText != result.committedText) {
            _committedText = result.committedText
        }

        // 僵尸 RC 态（如测试文档 bs6 场景：右选"策"后删完剩余数字，仅剩消费区 '23'）：
        // C++ SendToRime 有意清空 RIME input（kZombieClear，避免 preedit 拼接"策ce"），
        // 但 T9 buffer 仍处于 SELECTION（panel.state 非 IDLE，如 SELECTION/ce/23）。
        // 此时不能因 rawInput 为空误置左栏 IDLE——必须以 C++ 面板状态为准渲染。
        if (rawInput.isEmpty() && panel.state == LeftPanelState.IDLE) {
            if (leftPanelState != LeftPanelState.IDLE) {
                firstOptions = emptyList()
                leftPanelState = LeftPanelState.IDLE
                selectedOption = null
                selectionCandidateDigits = null
            }
            return
        }

        // 从数字段用 librime-t9 (C++) 计算左栏候选
        firstOptions = options.map { SyllableOption(it.first, it.second) }

        // 同步 C++ 状态机状态（分词键锁定 / 左选高亮 / 选择候选数字段）
        leftPanelState = panel.state
        selectedOption = if (panel.selectedPinyin.isNotEmpty()) {
            SyllableOption(panel.selectedPinyin, panel.selectedDigitLength)
        } else {
            null
        }
        selectionCandidateDigits = panel.selectionCandidateDigits.ifEmpty { null }
        leftColumnLocked = panel.leftLocked
    }

    /** C++ 左侧面板状态（t9GetLeftPanelState 解析结果） */
    private data class LeftPanelInfo(
        val state: LeftPanelState,
        val selectedPinyin: String,
        val selectedDigitLength: Int,
        val selectionCandidateDigits: String,
        val panelDigits: String,
        val leftLocked: Boolean,
    )

    /** 解析 "STATE;PINYIN;DIGIT_LEN;SEL_DIGITS;PANEL_DIGITS;LEFT_LOCKED" 格式 */
    private fun parseLeftPanelState(raw: String): LeftPanelInfo {
        val parts = raw.split(";")
        return LeftPanelInfo(
            state = when (parts.getOrNull(0)) {
                "INPUT" -> LeftPanelState.INPUT
                "SELECTION" -> LeftPanelState.SELECTION
                else -> LeftPanelState.IDLE
            },
            selectedPinyin = parts.getOrNull(1) ?: "",
            selectedDigitLength = parts.getOrNull(2)?.toIntOrNull() ?: 0,
            selectionCandidateDigits = parts.getOrNull(3) ?: "",
            panelDigits = parts.getOrNull(4) ?: "",
            leftLocked = parts.getOrNull(5) == "1",
        )
    }

    fun onDigitPressed(digit: String) {
        // 编码编辑态：数字键=在这条编码的光标处插一位，不另起新输入
        if (editCaretInternal >= 0) {
            enqueue { insertDigitAtEditCaret(digit) }
            return
        }
        val code = digit[0].code
        enqueue {
            rimeEngine.processKey(code, 0)
            // C++ T9Processor 采用异步 flush 模型：processKey 只标记 pending 动作，
            // 必须调用 FlushRimeInput 才能真正触发 set_input → compose。
            // 全程在后台线程执行，引擎 compose（2-23ms）不阻塞 UI 线程。
            rimeEngine.t9FlushRimeInput()
            refreshOnBackground()
        }
    }

    fun onChoiceSelected(option: SyllableOption) {
        // 左栏选音节＝重建音节边界，编辑态随之结束
        endCodeEdit()
        enqueue {
            rimeEngine.t9SelectPinyinDirect(option.pinyin, option.digitLength)
            rimeEngine.t9FlushRimeInput()
            refreshOnBackground()
        }
    }

    /**
     * 右选候选（保持同步返回值契约，供服务层判断 full/partial commit）。
     * 由服务层在 keyProcessingDispatcher（后台线程）调用。先等待后台队列排空再执行，
     * 避免 pending 覆盖导致消费计算错乱——阻塞的是该后台线程，不冻结主线程（方案 B）。
     * 调频不在此进行——由服务层在 full commit 上屏后经 rimeEngine.t9Memorize 单独调用。
     *
     * @param candidatePinyin 候选词拼音注释（comment），null 表示无注释候选（如 emoji）
     * @param candidateText 候选词文本，供 C++ (comment, text) 双条件定位（防同注释错码）
     * @param candidateTextLength 候选词字数
     */
    fun onRightCandidateSelected(
        candidatePinyin: String? = null,
        candidateText: String? = null,
        candidateTextLength: Int = 0,
    ): Boolean {
        // 点候选＝退出编码编辑
        endCodeEdit()
        awaitT9Queue()
        val isFullCommit = if (candidatePinyin != null) {
            val result = rimeEngine.t9SelectCandidate(candidatePinyin, candidateText, candidateTextLength)
            rimeEngine.t9FlushRimeInput()
            result
        } else {
            false
        }
        updateFromRime()
        // 诊断（2026-10-23）：右选后编码串残留排查——记录候选注释/文本、是否 full commit
        // 以及消费后的引擎输入串。用户侧只抓得到 Kotlin 日志，故打在 FileLogger。
        FileLogger.d(
            TAG,
            "T9 right-select: pinyin='${candidatePinyin ?: ""}' text='${candidateText ?: ""}' " +
                "textLen=$candidateTextLength fullCommit=$isFullCommit leftoverInput='$cachedInput'"
        )
        // workaround: C++ t9_processor 对 nv/lv 等两键单音节词误判为 partial commit，残留 1 位数字。
        // 选单字后如果只剩 1 位残留，说明是误判，强制清空。
        if (!isFullCommit && cachedInput.length == 1) {
            clearAll()
            return true
        }
        return isFullCommit
    }

    /**
     * 退格处理（异步，结果通过回调返回）。
     *
     * 长按退格以 ~30ms 固定频率重复派发，而每次退格含 processKey → flush → compose
     * 的 JNI 往返，耗时可能超过重复间隔。若每次都入队，t9Dispatcher 会堆积大量退格，
     * 抬手后洪水式多删。这里合并高频重复：同一时刻至多一个退格 job，累积的请求由
     * 执行中的 job 完成后顺带排空（[drainPendingDeletes]），退格速率被引擎吞吐自然限制。
     *
     * @param callback 在 Main 线程调用，参数为退格结果。
     */
    fun onDeleted(callback: (DeleteResult) -> Unit) {
        // 编码编辑态：退格删光标前一位，绝不转发去删上屏文字。
        // 组合已被别的路径结束（引擎输入为空）时先退出编辑，落回普通退格。
        if (editCaretInternal >= 0) {
            if (currentDigitSequence().isEmpty()) {
                endCodeEdit()
            } else {
                enqueue { deleteAtEditCaret(callback) }
                return
            }
        }
        val shouldLaunch = synchronized(deleteCoalesceLock) {
            if (deleteJobActive) {
                pendingDeleteCount++
                false
            } else {
                deleteJobActive = true
                true
            }
        }
        if (!shouldLaunch) return
        enqueue {
            try {
                processDelete(callback)
            } finally {
                drainPendingDeletes(callback)
            }
        }
    }

    /** 单次退格：processKey → flush → 撤销计数 → 取全量结果 → Main 刷新 + 回调。 */
    private suspend fun processDelete(callback: (DeleteResult) -> Unit) {
        val result = rimeEngine.processKey(0xff08, 0)
        rimeEngine.t9FlushRimeInput()
        val undoneCount = rimeEngine.t9GetAndConsumeUndoneRightCommitCount()
        val data = fetchAll()
        val (finalResult, injections) = transformInjections(data.result)
        val composition = finalResult.toComposition()
        val deleteResult = if (result) DeleteResult.DELETED else DeleteResult.NOT_CONSUMED
        val gen = ++uiGeneration
        mainHandler.post {
            // 撤销计数与退格结果始终回调；仅当刷新仍是最新代际时应用，
            // 避免后续更快的按键刷新被本退格的旧状态覆盖。
            if (undoneCount > 0) {
                onRightCommitUndone?.invoke(undoneCount)
            }
            if (gen == uiGeneration) {
                onCompositionRefresh?.invoke(composition, injections)
                applyCandidates(finalResult, data.panel, data.options)
            }
            callback(deleteResult)
        }
    }

    /** 消费长按退格期间累积的额外退格请求（t9Dispatcher 上顺序执行）。 */
    private suspend fun drainPendingDeletes(callback: (DeleteResult) -> Unit) {
        while (true) {
            val shouldDrain = synchronized(deleteCoalesceLock) {
                if (pendingDeleteCount == 0) {
                    deleteJobActive = false
                    false
                } else {
                    pendingDeleteCount--
                    true
                }
            }
            if (!shouldDrain) break
            try {
                processDelete(callback)
            } catch (t: Throwable) {
                FileLogger.e(TAG, "onDeleted drain failed", t)
            }
        }
    }

    fun forceSendToRime() {
        enqueue {
            val remaining = rimeEngine.t9GetRemainingDigits()
            if (remaining.isNotEmpty()) {
                // Directly set RIME input to remaining digits (bypass processKey/AppendDigit)
                rimeEngine.setInput(remaining)
            }
            refreshOnBackground()
        }
    }

    /**
     * 右侧候选直接提交上屏：不经过消耗算法，清空缓冲区并进入空闲状态。
     * 用于 emoji/符号等无拼音注释的候选词，RIME 引擎已匹配输入序列到候选词，
     * T9 控制器无需做音节级消费计算。
     */
    fun onRightCandidateSelectedByDirectCommit(): Boolean {
        if (inputBuffer.isEmpty()) return true
        clearAll()
        return true
    }

    fun clearRimeAndResend() {
        enqueue {
            rimeEngine.clearComposition()
            refreshOnBackground()
        }
    }

    /**
     * 清空（主线程 ResetKey/上滑手势调用）。
     * 本地 Compose 状态立即复位；C++ 状态清空排入后台队列——若队列中已有未完成的
     * 按键处理，清空在其后执行（与后续按键同队列保序），主线程零等待（方案 B）。
     */
    fun clearAll() {
        // 丢弃排队的过期刷新 post，避免清空后被旧状态覆盖
        uiGeneration++
        endCodeEdit()
        firstOptions = emptyList()
        leftPanelState = LeftPanelState.IDLE
        selectedOption = null
        selectionCandidateDigits = null
        leftColumnLocked = false
        _selectionHistory = emptyList()
        _committedText = null
        cachedInput = ""
        enqueue { rimeEngine.t9ClearComposition(1) }
    }

    fun onEnterCommit() { clearAll() }

    fun isSelectedOptionInCurrentCandidates(): Boolean = selectedOption in firstOptions

    // ── 编码编辑（点进编码改拼音串）──────────────────────────────────────────
    //
    // 九键的输入单位是数字，所以「字母级编辑」在九键上体现为：光标可以停在任意两个
    // 字母之间，按数字键就在该位置插入一位编码、退格就删掉一位。显示层的字母由 RIME
    // 用候选注释渲染，与数字位一一对应，因此「显示第几个字母」==「第几位数字」。
    //
    // 编辑期间不能用 processKey 逐位 replay（会触发 N 次 compose），改为整串重建：
    // 直接把改好的数字串交给 ReplaceDigitSequence，一次 set_input 完成。
    // 代价是已选音节/分隔符被清空——编码被改动后原音节边界本来就不再成立。

    /** 当前编码的纯数字序列（字母音节折算成数字、丢弃分隔符），编辑态定位用。 */
    fun currentDigitSequence(): String = toDigitSequence(cachedInput)

    /**
     * 编辑态光标（以编码位/数字位计，-1 = 非编辑态）。Compose 状态：气泡竖线按它重绘。
     *
     * 权威值在 [editCaretInternal]（队列内读写，保证连按不串位），这里只是给 UI 的镜像。
     */
    var codeEditCaret by mutableStateOf(-1)
        private set

    val isCodeEditing: Boolean get() = codeEditCaret >= 0

    /** 队列内权威光标值（@Volatile：主线程只读判断是否在编辑态）。 */
    @Volatile
    private var editCaretInternal: Int = -1

    /** 编辑中的整串数字（进入编辑态时快照，编辑期间以它为唯一真源，不回读引擎）。 */
    @Volatile
    private var editDigits: String? = null

    /**
     * 进入编码编辑态，光标停在第 [digitIndex] 位之前（0..位数）。
     *
     * 编码改过之后原音节边界不再成立，因此把当前输入串整体折算成数字序列作为编辑基准
     * （已锁定的字母音节同样折算），后续按位插入/删除后整串重建。
     */
    fun beginCodeEdit(digitIndex: Int) {
        val digits = currentDigitSequence()
        if (digits.isEmpty()) return
        val at = digitIndex.coerceIn(0, digits.length)
        editDigits = digits
        editCaretInternal = at
        codeEditCaret = at
        FileLogger.d(TAG, "code edit: begin caret=$at digits='$digits'")
    }

    /** 退出编码编辑态（上屏/选候选/上滑清空/换方案等一切非编辑操作）。 */
    fun endCodeEdit() {
        if (editCaretInternal < 0 && codeEditCaret < 0) return
        // 权威值同步清（可能从后台线程调用），Compose 镜像只在主线程写
        editCaretInternal = -1
        editDigits = null
        if (Looper.myLooper() == Looper.getMainLooper()) {
            codeEditCaret = -1
        } else {
            mainHandler.post { codeEditCaret = -1 }
        }
    }

    /**
     * 编辑态横向滑动移光标。返回 true = 已消费（调用方不再去移编辑器光标）。
     * 越界钳制而不退出编辑态：滑到头继续滑不会掉出编辑。
     */
    fun moveCodeEditCaret(delta: Int): Boolean {
        val at = editCaretInternal
        if (at < 0) return false
        val digits = editDigits ?: return false
        val target = (at + delta).coerceIn(0, digits.length)
        if (target == at) return true
        editCaretInternal = target
        codeEditCaret = target
        return true
    }

    /** 队列内：整串数字重建（一次 set_input，不逐位 replay）。 */
    private suspend fun pushEditedDigits(digits: String) {
        if (digits.isEmpty()) {
            rimeEngine.t9ClearComposition(1)
        } else {
            rimeEngine.t9SetDigitSequence(digits)
        }
    }

    /** 把编辑态光标同步到 UI 镜像（Compose 状态只在主线程写）。 */
    private fun postEditCaret() {
        mainHandler.post { codeEditCaret = editCaretInternal }
    }

    /** 编辑态插入一位数字（队列内执行，调用前已在主线程判定进入编辑分支）。 */
    private suspend fun insertDigitAtEditCaret(digit: String) {
        val expected = editDigits
        // 组合已被别的路径结束（空格上屏、切方案、宿主清空等）：退出编辑态，按新输入处理。
        // 只在「引擎输入已空」时判定，避免本队列内刷新滞后导致误退出。
        if (expected == null || (expected.isNotEmpty() && toDigitSequence(cachedInput).isEmpty())) {
            endCodeEdit()
            rimeEngine.processKey(digit[0].code, 0)
            rimeEngine.t9FlushRimeInput()
            refreshOnBackground()
            return
        }
        val pos = editCaretInternal.coerceIn(0, expected.length)
        val next = expected.substring(0, pos) + digit + expected.substring(pos)
        editDigits = next
        editCaretInternal = pos + 1
        pushEditedDigits(next)
        refreshOnBackground()
        postEditCaret()
        FileLogger.d(TAG, "code edit: insert $digit at $pos -> '$next'")
    }

    /** 编辑态退格：删光标前一位；删空则退出编辑态并清组合。 */
    private suspend fun deleteAtEditCaret(callback: (DeleteResult) -> Unit) {
        val digits = editDigits
        val pos = editCaretInternal
        if (digits == null || pos <= 0) {
            // 已在编码最左端：仍回报 DELETED，阻断「转发去删上屏文字」
            mainHandler.post { callback(DeleteResult.DELETED) }
            return
        }
        val next = digits.removeRange(pos - 1, pos)
        if (next.isEmpty()) {
            endCodeEdit()
            pushEditedDigits("")
        } else {
            editDigits = next
            editCaretInternal = pos - 1
            pushEditedDigits(next)
        }
        refreshOnBackground()
        postEditCaret()
        FileLogger.d(TAG, "code edit: delete at $pos -> '$next'")
        mainHandler.post { callback(DeleteResult.DELETED) }
    }

    private fun updateFromRime() {
        // 同步刷新（右选路径）：使后台排队的过期刷新失效，避免旧状态 post 后执行覆盖。
        uiGeneration++
        updateCandidates()
    }
}
