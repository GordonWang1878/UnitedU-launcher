package com.uniteduone.launcher

/**
 * 音量键按住连发(issue #1)。
 *
 * `HomeKeyService` 带 `flagRequestFilterKeyEvents`。这个标志是整包按键一起接管:系统不再替音量键自动连发,
 * 海信等电视上按住音量只走一格。连发必须由我们自己补,而且只能补在音量键本身上——不另设组合键、不设模式开关。
 *
 * **松手之后音量绝不能继续走**,包括那些从不送 `ACTION_UP` 的电视。所以这里**不**在按下之后用纯计时器连发。
 * 只有系统送来的重复按下(`repeatCount > 0`)才算「键还按着」的心跳:
 * - [onKeyDown](repeatCount = 0)只记状态,**自己不走步**。那一格由系统处理(厂商音量条、步长、CEC),服务对这一下返回 false;
 * - 心跳在按下之后满 [VOLUME_REPEAT_INITIAL_DELAY_MS] 才由我们加点,且两次加点至少隔 [VOLUME_REPEAT_INTERVAL_MS]
 *   (心跳来得更密也不会一步走两次)。这些重复按下由服务吃掉;
 * - 还没收到过任何心跳时,静默窗口是初始延迟 + [VOLUME_REPEAT_HEARTBEAT_TIMEOUT_MS]。系统连发通常要到初始延迟才开始,
 *   若从按下起 180 ms 就拆掉,第一次心跳永远赶不上;
 * - 已经收到过心跳之后,超过 [VOLUME_REPEAT_HEARTBEAT_TIMEOUT_MS] 没有新的,[onTimerTick] 立刻停。计时器本身不再加点;
 * - 既没有心跳也没有松开:静默窗口到点停掉,我们一步都没走(系统那一格已经走过);
 * - 心跳对得上这次按下时,**不用** [VOLUME_REPEAT_HARD_CAP_MS] 截断,一直走到松开、心跳超时或到顶。
 *   3 秒只兜「心跳对不上任何还有效的按下」:不把它们开成新的一次按下,记满 3 秒就丢掉。
 *
 * 时钟与调度器都从外面注进来,单测用 [ManualVolumeClock] / [ManualVolumeScheduler],不等真实时间。
 * 生产时钟用 `SystemClock.uptimeMillis()`:与 `Handler.postDelayed` 同一条时间轴。
 */

/** 单调时钟,毫秒。必须和 [VolumeScheduler] 用同一条时间轴。 */
fun interface VolumeClock {
    fun nowMs(): Long
}

/** 一次可取消的延时。 */
interface VolumeTask

/** 到点调用 [run]。实现不得自己调音量。 */
interface VolumeScheduler {
    fun postDelayed(delayMs: Long, run: () -> Unit): VolumeTask
    fun cancel(task: VolumeTask)
}

enum class VolumeDirection { UP, DOWN }

/** [MOVED] 音量变了;[AT_BOUND] 已经在最大或最小,这一步没有变化,调用方不再继续点。 */
enum class VolumeStepResult { MOVED, AT_BOUND }

fun interface VolumeStepper {
    fun step(direction: VolumeDirection): VolumeStepResult
}

/** 按下之后,要等多久才允许按心跳加点。短按在这之前松开 = 我们一步都不走。 */
const val VOLUME_REPEAT_INITIAL_DELAY_MS = 400L

/** 按住之后两次加点的最短间隔。心跳更密时多出来的次被丢掉,避免一步走两次。 */
const val VOLUME_REPEAT_INTERVAL_MS = 100L

/**
 * 多久没有心跳就认为键已经松开。到点只停、不再加点。
 * 取 180:落在 150–200 ms 里,短于一格连发间隔,松手后赶不上再走一格。
 */
const val VOLUME_REPEAT_HEARTBEAT_TIMEOUT_MS = 180L

/**
 * 不用于正常按住。心跳一直对得上这次按下时,一直走到松开、心跳超时或到顶,没有 3 秒截断。
 * 只兜异常:心跳的 downTime 对不上任何还有效的按下时,不把它开成新的一次按下、也不走步;
 * 这种对不上的心跳最多记 [VOLUME_REPEAT_HARD_CAP_MS],过了就丢掉这段,免得跟后面真正的新按下粘在一起。
 */
const val VOLUME_REPEAT_HARD_CAP_MS = 3_000L

/**
 * 多久没有「键还按着」的证据就停,从锚点算起,不是从现在起的剩余时间。
 * 还没有任何心跳:锚点是按下,窗口 = 初始延迟 + 心跳超时(给系统连发留出开始的时间)。
 * 已经有过心跳:锚点是最后一次心跳,窗口 = 心跳超时。到点只停,不走步。
 */
fun volumeSilenceWindowMs(seenHeartbeat: Boolean): Long =
    if (seenHeartbeat) VOLUME_REPEAT_HEARTBEAT_TIMEOUT_MS
    else VOLUME_REPEAT_INITIAL_DELAY_MS + VOLUME_REPEAT_HEARTBEAT_TIMEOUT_MS

/** 与 `KeyEvent.KEYCODE_VOLUME_UP` 相同,这里不引用 Android 类型。 */
const val VOLUME_KEYCODE_UP = 24

/** 与 `KeyEvent.KEYCODE_VOLUME_DOWN` 相同。 */
const val VOLUME_KEYCODE_DOWN = 25

/** 与 `KeyEvent.KEYCODE_VOLUME_MUTE` 相同。静音不接管。 */
const val VOLUME_KEYCODE_MUTE = 164

const val VOLUME_LOG_DOWN = "down"
const val VOLUME_LOG_UP = "up"
const val VOLUME_LOG_AUTOSTOP = "autostop"

fun volumeDirection(keyCode: Int): VolumeDirection? = when (keyCode) {
    VOLUME_KEYCODE_UP -> VolumeDirection.UP
    VOLUME_KEYCODE_DOWN -> VolumeDirection.DOWN
    else -> null
}

/**
 * 测试用时钟。生产不用它。
 * [now] 只有测试改;调度器要和它是同一个对象,见 [ManualVolumeScheduler]。
 */
class ManualVolumeClock(var now: Long = 0L) : VolumeClock {
    override fun nowMs(): Long = now
}

/**
 * 测试用调度器:不跑线程。[advanceTo] 先把 [clock] 拨到目标时刻,再按到期顺序执行回调。
 * 回调里新登记的、已经到期的任务会在同一次 [advanceTo] 里接着跑。
 */
class ManualVolumeScheduler(private val clock: ManualVolumeClock) : VolumeScheduler {
    private class Task(val due: Long, val run: () -> Unit) : VolumeTask

    private val queue = ArrayList<Task>()

    override fun postDelayed(delayMs: Long, run: () -> Unit): VolumeTask {
        val task = Task(clock.now + delayMs.coerceAtLeast(0L), run)
        queue.add(task)
        return task
    }

    override fun cancel(task: VolumeTask) {
        queue.remove(task)
    }

    fun advanceTo(timeMs: Long) {
        require(timeMs >= clock.now) { "time goes backwards: $timeMs < ${clock.now}" }
        clock.now = timeMs
        while (true) {
            val next = queue.filter { it.due <= clock.now }.minByOrNull { it.due } ?: return
            queue.remove(next)
            next.run()
        }
    }
}

/**
 * 音量加/减的第一下按下(repeatCount = 0)要不要由我们吃掉。
 * [openedNewPress] 为真:新的一次按下,不吃,系统按原来的方式走一格。
 * 为假:同一 downTime 的重发,或已经结束的那次又来了,吃掉,避免系统再走一格。
 */
fun consumeVolumeDown(openedNewPress: Boolean): Boolean = !openedNewPress

private class VolumeHold(
    val keyCode: Int,
    val direction: VolumeDirection,
    val downTimeMs: Long,
    val downAt: Long,
    var lastBeat: Long,
    var lastStepAt: Long,
    var steps: Int,
    var clamped: Boolean,
    var seenHeartbeat: Boolean,
)

/**
 * 音量连发状态机。所有方法都在调用方的线程上跑(服务里是主线程),自己不加锁。
 *
 * @param onRecord 每次按下 / 松开 / 自动停下写一条;默认丢掉。生产写到本机环形日志。
 */
class VolumeRepeatController(
    private val clock: VolumeClock,
    private val scheduler: VolumeScheduler,
    private val stepper: VolumeStepper,
    private val onRecord: (VolumeKeyRecord) -> Unit = {},
) {
    private var hold: VolumeHold? = null
    private var heartbeatTask: VolumeTask? = null
    /** 最近一次已经结束(松开或自动停)的按下的 downTime。迟到的同一 downTime 不能再开新的一次。 */
    private var closedDownTime = 0L
    /** 对不上任何有效按下的心跳是从何时开始的。满 [VOLUME_REPEAT_HARD_CAP_MS] 就丢掉,仍然不走步。 */
    private var orphanSince: Long? = null

    /**
     * 最近一次 [onKeyDown] 有没有打开一次新的按下。
     * 服务用它决定吃不吃这下 `repeatCount == 0`:新的一次不吃,同一 downTime 的迟到重发吃掉。
     */
    var openedNewPress: Boolean = false
        private set

    /** 当前这一下已经走出的步数;没有按下时是上一回结束时的步数。 */
    var stepsTaken: Int = 0
        private set

    /** 上一回结束是不是因为心跳超时、3 秒上限或 [stop],而不是收到了松开。 */
    var lastAutoStop: Boolean = false
        private set

    val activeKeyCode: Int? get() = hold?.keyCode

    /**
     * 音量加 / 减的第一下按下(`repeatCount == 0`)。只记按下,**不调用** [VolumeStepper]:
     * 这一格由系统走(短按必须和没接管时一样)。
     * [downTimeMs] > 0 且与当前这一下、或刚刚结束的那一下相同:不再开新的一次,也不走步。
     * 反方向,或另一个 downTime:先停掉当前方向,再记下新的一次(仍然不走步)。
     * 静音和其它键返回 false。
     */
    fun onKeyDown(keyCode: Int, downTimeMs: Long = 0L): Boolean {
        openedNewPress = false
        val direction = volumeDirection(keyCode) ?: return false
        if (isClosedDown(downTimeMs) || isSameOpenDown(keyCode, downTimeMs)) return true
        if (hold != null) finish(autoStop = false)
        val now = clock.nowMs()
        orphanSince = null
        hold = VolumeHold(
            keyCode = keyCode,
            direction = direction,
            downTimeMs = downTimeMs,
            downAt = now,
            lastBeat = now,
            lastStepAt = now,
            steps = 0,
            clamped = false,
            seenHeartbeat = false,
        )
        openedNewPress = true
        stepsTaken = 0
        lastAutoStop = false
        emit(keyCode, VOLUME_LOG_DOWN, steps = 0, autoStop = false)
        armHeartbeat()
        return true
    }

    /**
     * 系统连发的按下(`repeatCount > 0`),只证明键还按着。
     * [downTimeMs] 与已经结束的那次相同,或对不上当前这次:不走开新的一次,也不走步。
     * 没有进行中的按下、或不是当前这一侧:记入异常窗口([VOLUME_REPEAT_HARD_CAP_MS]),不走步。
     * 未满初始延迟、或距上次加点不足一格:只刷新心跳,不走步。
     * 对得上的心跳不受 3 秒截断。
     */
    fun onHeartbeat(keyCode: Int, downTimeMs: Long = 0L): Boolean {
        if (volumeDirection(keyCode) == null) return false
        if (isClosedDown(downTimeMs)) return true
        val current = hold
        if (current == null || current.keyCode != keyCode || mismatchedDown(current, downTimeMs)) {
            noteOrphan(clock.nowMs())
            return false
        }
        orphanSince = null
        val now = clock.nowMs()
        current.lastBeat = now
        current.seenHeartbeat = true
        armHeartbeat()
        val sinceDown = now - current.downAt
        val sinceStep = now - current.lastStepAt
        if (!current.clamped &&
            sinceDown >= VOLUME_REPEAT_INITIAL_DELAY_MS &&
            sinceStep >= VOLUME_REPEAT_INTERVAL_MS
        ) {
            if (stepper.step(current.direction) == VolumeStepResult.MOVED) {
                current.steps += 1
                current.lastStepAt = now
                stepsTaken = current.steps
            } else {
                current.clamped = true
            }
        }
        return true
    }

    /** 松开。立刻停,不再走步。没有配对的按下则返回 false。 */
    fun onKeyUp(keyCode: Int): Boolean {
        val current = hold ?: return false
        if (current.keyCode != keyCode) return false
        finish(autoStop = false)
        return true
    }

    /**
     * 调度器到点时调用。本身不走步,只在静默窗口([volumeSilenceWindowMs])到了的时候停。
     * 正常按住没有 3 秒截断。测试也可以不经过调度器,把时钟拨过去之后直接调。
     */
    fun onTimerTick() {
        val current = hold ?: return
        val now = clock.nowMs()
        if (now >= silenceDeadline(current)) finish(autoStop = true)
    }

    private fun isClosedDown(downTimeMs: Long): Boolean =
        downTimeMs > 0L && downTimeMs == closedDownTime && (hold == null || hold?.downTimeMs != downTimeMs)

    private fun isSameOpenDown(keyCode: Int, downTimeMs: Long): Boolean {
        val current = hold ?: return false
        return current.keyCode == keyCode && downTimeMs > 0L && downTimeMs == current.downTimeMs
    }

    private fun mismatchedDown(current: VolumeHold, downTimeMs: Long): Boolean =
        downTimeMs > 0L && current.downTimeMs > 0L && downTimeMs != current.downTimeMs

    /** 对不上的心跳不走步。记满 [VOLUME_REPEAT_HARD_CAP_MS] 就丢掉这段,避免被当成一次新的按下。 */
    private fun noteOrphan(now: Long) {
        val start = orphanSince
        if (start == null) orphanSince = now
        else if (now - start >= VOLUME_REPEAT_HARD_CAP_MS) orphanSince = null
    }

    private fun silenceDeadline(current: VolumeHold): Long =
        if (current.seenHeartbeat) current.lastBeat + VOLUME_REPEAT_HEARTBEAT_TIMEOUT_MS
        else current.downAt + volumeSilenceWindowMs(seenHeartbeat = false)

    /** 服务中断、解绑或销毁:立刻停。没有按下时什么都不记。 */
    fun stop() {
        if (hold != null) finish(autoStop = true) else cancelTimers()
    }

    private fun finish(autoStop: Boolean) {
        val current = hold ?: return
        hold = null
        cancelTimers()
        stepsTaken = current.steps
        lastAutoStop = autoStop
        if (current.downTimeMs > 0L) closedDownTime = current.downTimeMs
        emit(
            current.keyCode,
            if (autoStop) VOLUME_LOG_AUTOSTOP else VOLUME_LOG_UP,
            current.steps,
            autoStop,
        )
    }

    /** 日志写失败不能把音量键变成死键。 */
    private fun emit(keyCode: Int, action: String, steps: Int, autoStop: Boolean) {
        runCatching {
            onRecord(
                VolumeKeyRecord(
                    timeMs = clock.nowMs(),
                    keyCode = keyCode,
                    action = action,
                    steps = steps,
                    autoStop = autoStop,
                    wallTimeMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun armHeartbeat() {
        heartbeatTask?.let { scheduler.cancel(it) }
        val current = hold ?: return
        val delay = (silenceDeadline(current) - clock.nowMs()).coerceAtLeast(0L)
        heartbeatTask = scheduler.postDelayed(delay) { onTimerTick() }
    }

    private fun cancelTimers() {
        heartbeatTask?.let { scheduler.cancel(it) }
        heartbeatTask = null
    }
}
