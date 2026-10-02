package com.uniteduone.launcher

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 关于页那颗唯一按钮此刻按下去做什么。INSTALL = 把已校验的文件交给安装器(不再下载)。 */
enum class AboutAction { CHECK, DOWNLOAD, INSTALL, NONE }

/**
 * 关于页的状态机(spec §7.3):
 * `Idle → Checking → Latest | Failed(reason) | Found(info)`,
 * `Found → Downloading(p) → Verifying → Installing | ReadyToInstall | VerifyFailed`,
 * `ReadyToInstall →(按键)→ Installing`,
 * 另有三个下载/安装结局 `DownloadFailed` / `NeedsPermission` / `InstallFailed`。
 *
 * 每个状态自带两件事,界面与返回键只读这两个量、不再自己 `when` 一遍:
 * - [action]:按钮按下去做什么。**忙碌态(检查中 / 下载中 / 校验中)是 NONE**——
 *   按钮在这些状态下仍然可聚焦(见 [AboutScreen] 的 KDoc:`clickable(enabled = false)`
 *   会让唯一的焦点节点失去可聚焦性,整棵树的焦点随之消失),点击只是被吞掉,
 *   不会叠出第二个下载;
 * - [cancellable]:返回键是「取消这次下载」还是「关页」(spec:下载中 BACK = 取消下载并删文件)。
 *
 * 带 [info] 的状态都表示「服务器上有这台设备能装的新版」,失败后按钮仍是「下载并安装」,
 * 再按一次就是重试。
 */
sealed class AboutState {
    abstract val action: AboutAction
    open val cancellable: Boolean get() = false
    open val info: LatestInfo? get() = null

    data object Idle : AboutState() {
        override val action get() = AboutAction.CHECK
    }

    data object Checking : AboutState() {
        override val action get() = AboutAction.NONE
    }

    data object Latest : AboutState() {
        override val action get() = AboutAction.CHECK
    }

    data class Failed(val reason: CheckFailure) : AboutState() {
        override val action get() = AboutAction.CHECK
    }

    data class Found(override val info: LatestInfo) : AboutState() {
        override val action get() = AboutAction.DOWNLOAD
    }

    /** [percent] = null:服务器没给 `Content-Length`,只显示「下载中…」。 */
    data class Downloading(override val info: LatestInfo, val percent: Int?) : AboutState() {
        override val action get() = AboutAction.NONE
        override val cancellable get() = true
    }

    data class Verifying(override val info: LatestInfo) : AboutState() {
        override val action get() = AboutAction.NONE
        override val cancellable get() = true
    }

    /**
     * 已下载并通过全部校验,但校验结束时 Activity 不在前台(review Important 2):
     * 停在这里等用户按「安装更新」,**绝不在回到前台时自动弹安装器**。[file] 在登记簿里登记着
     * (清扫不碰);关页([AboutController.reset])时删除。返回键 = 关页。
     */
    data class ReadyToInstall(
        override val info: LatestInfo,
        val file: File,
        /** R151:刚带用户去开「显示在其他应用上层」,回来按「安装更新」继续(提示见 [outcome])。 */
        val overlayHint: Boolean = false,
    ) : AboutState() {
        override val action get() = AboutAction.INSTALL
    }

    /** 哈希不符或更新包身份不对(见 `checkUpdateApk`),文件已删。 */
    data class VerifyFailed(override val info: LatestInfo) : AboutState() {
        override val action get() = AboutAction.DOWNLOAD
    }

    data class DownloadFailed(override val info: LatestInfo) : AboutState() {
        override val action get() = AboutAction.DOWNLOAD
    }

    data class NeedsPermission(override val info: LatestInfo) : AboutState() {
        override val action get() = AboutAction.DOWNLOAD
    }

    /** 已交给系统安装器。用户在安装器里取消回来,按钮仍可重试。 */
    data class Installing(override val info: LatestInfo) : AboutState() {
        override val action get() = AboutAction.DOWNLOAD
    }

    data class InstallFailed(override val info: LatestInfo) : AboutState() {
        override val action get() = AboutAction.DOWNLOAD
    }
}

/**
 * 关于页状态机的持有者。MainActivity 只做接线:把 [state] 喂给 [AboutScreen],
 * 按钮接 [check] / [downloadAndInstall] / [installReady],返回键接 [cancelIfBusy],关页接 [reset]。
 *
 * **异步结果怎么不写错地方**:所有异步工作写回 [state] 之前,先比对启动时记下的 [session]。
 * 每次开始新动作、取消、关页都 `session++`,旧工作的结果自然作废——比对的是一个只增不减的
 * 计数,不是「谁负责清掉」的布尔闩(铁律 7),关页再开、连按取消都不会留下卡住的状态。
 * [session] 只在主线程上读写:协程续体跑在 `lifecycleScope` 的主线程,下载进度从 IO 线程
 * 经 [main] 投递回主线程后才比对;进度还额外要求「当前仍是 Downloading」——
 * 投递顺序与协程续体顺序不保证一致,晚到的进度不能把「校验中 / 已交给安装器」改回「下载中」。
 *
 * **文件与并发(review Important 2)**:每次下载尝试预留一个独占文件([Update.reserveApk]),
 * 一路只用**这个文件**,直到交给 [SelfUpdate](字节拷进安装会话,文件随即删);登记簿([UpdateFiles])的锁只包住改名与清扫,下载、校验、
 * 等用户按键都不持锁——同进程里另一个 MainActivity 实例的更新流程与这里互不阻塞,
 * 它 `onCreate` 的清扫也碰不到这里登记着的文件。
 *
 * **绝不自动弹安装器**:校验结束的那一刻若 Activity 不在前台(STARTED 以下),停在
 * [AboutState.ReadyToInstall] 等用户按键;不存在任何「等回到前台再装」的挂起。于是关页(包括
 * HOME 经 `onNewIntent` 关页)之后再也不会冒出安装器。
 */
class AboutController(
    private val activity: ComponentActivity,
    private val currentVersionCode: Int,
    private val urls: List<String>,
) {
    var state: AboutState by mutableStateOf(AboutState.Idle)
        private set

    private var session = 0
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    /** R151 ②:这次打开关于页已经带用户去开过「显示在其他应用上层」(关页时清,下次打开再问一次)。 */
    private var overlayAsked = false
    private val main = Handler(Looper.getMainLooper())

    /** 手动检查(spec §7.3):结果一定落到 Latest / Found / Failed 之一,不静默。 */
    fun check() {
        if (state.action != AboutAction.CHECK) return
        val my = begin(AboutState.Checking)
        checkJob = activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { Update.check(urls) }
            if (my != session) return@launch
            state = result.fold(
                onSuccess = { info ->
                    val newer = isNewer(info, currentVersionCode, Build.VERSION.SDK_INT)
                    Log.i(TAG, "update check: server ${info.versionCode} (${info.versionName}), current $currentVersionCode, newer=$newer")
                    if (newer) AboutState.Found(info) else AboutState.Latest
                },
                onFailure = { e ->
                    val reason = (e as? UpdateCheckException)?.reason ?: CheckFailure.NETWORK
                    Log.w(TAG, "update check failed: $reason")
                    AboutState.Failed(reason)
                },
            )
        }
    }

    /**
     * 下载 → 校验(SHA-256 + 包名 / 版本 / 签名,见 [Update.verify])→ 交给系统安装器
     * (走 [SelfUpdate] 的会话安装,权限引导复用 [ApkInstaller.requestInstallPermission];
     * 装完由 `RelaunchAfterUpdate` 拉回桌面)。任何一项校验不过:删文件、
     * 提示「校验失败」。被取消(返回键 / 关页)时,半截的临时文件由 [Update.download] 删,
     * 目标文件由 [downloadVerifyInstall] 的 `finally` 删——只有全部校验通过的文件才会留下。
     */
    fun downloadAndInstall() {
        val info = state.info ?: return
        if (state.action != AboutAction.DOWNLOAD) return
        val my = begin(AboutState.Downloading(info, null))
        downloadJob = activity.lifecycleScope.launch { downloadVerifyInstall(info, my) }
    }

    /**
     * [downloadAndInstall] 的那一整段。[my] 是启动时的会话号:每一步写回 [state] 之前都先比对,
     * 不等就说明已被取消 / 关页,直接收手(`finally` 照样清理)。
     */
    private suspend fun downloadVerifyInstall(info: LatestInfo, my: Int) {
        // 只在内存里预留并登记名字(不碰磁盘),紧接着进 try:任何出口都经 finally 处理。
        val file = Update.reserveApk(activity)
        var keep = false
        try {
            val ok = Update.download(activity, info.apkUrl, file) { p ->
                main.post {
                    val s = state
                    if (my == session && s is AboutState.Downloading) state = s.copy(percent = p)
                }
            }
            if (my != session) return
            if (!ok) {
                state = AboutState.DownloadFailed(info)
                return
            }
            state = AboutState.Verifying(info)
            // 哈希 + 解析 APK + 读本应用签名,全在 IO 线程(解析不上主线程)。
            val rejection = withContext(Dispatchers.IO) { Update.verify(activity, file, info) }
            if (my != session) return
            if (rejection != null) {
                // 当场删(不等 finally),日志里记的是删除的真实结果。
                val gone = withContext(Dispatchers.IO) { Update.files(activity).release(file) }
                Log.w(TAG, "update ${info.versionName} (${info.versionCode}) rejected: $rejection; file deleted=$gone")
                state = AboutState.VerifyFailed(info)
                return
            }
            // 从这里到 handOver 结束没有挂起点,取消插不进来:文件的去留由 handOver 决定。
            keep = true
            handOver(info, file)
        } finally {
            // 取消路径上这里的挂起调用必须 NonCancellable,否则删文件那一步本身会被取消掉。
            if (!keep) withContext(NonCancellable + Dispatchers.IO) { Update.files(activity).release(file) }
        }
    }

    /**
     * 「安装更新」按钮([AboutState.ReadyToInstall]):先在 IO 线程确认文件还在(缓存可能被系统清掉),
     * 再交给安装器。按键期间 Activity 必在前台;万一此刻已不在,[handOver] 会让它继续停在 ReadyToInstall。
     */
    fun installReady() {
        val ready = state as? AboutState.ReadyToInstall ?: return
        val my = ++session
        downloadJob = activity.lifecycleScope.launch {
            val present = withContext(Dispatchers.IO) { ready.file.isFile }
            if (my != session) return@launch
            if (!present) {
                Log.w(TAG, "verified update file vanished before install: ${ready.file.name}")
                Update.discard(activity, ready.file)
                state = AboutState.DownloadFailed(ready.info)
                return@launch
            }
            handOver(ready.info, ready.file)
        }
    }

    /**
     * 主线程。前台判断与 `commit` 在同一个主线程回合里,中间插不进生命周期变化;
     * 确认页由系统稍后经 `SelfUpdateResult` 要我们打开。
     * 不在前台 → 停在 [AboutState.ReadyToInstall](文件留着、仍登记);在前台 → 交给安装器。
     * STARTED 时字节已拷进会话;需要授权 / 失败时也没别的用——文件一律删掉,重试重新下载。
     */
    private fun handOver(info: LatestInfo, file: File) {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            Log.i(TAG, "update ${info.versionName} (${info.versionCode}) verified while in background; waiting for the user")
            state = AboutState.ReadyToInstall(info, file)
            return
        }
        // R151 ②:更新会杀掉本应用、删掉首页任务;装好后要把桌面拉回来,索尼固件要「显示在其他应用上层」权限。
        // 没有就先带用户去开一次(每次打开关于页只问一次),停在 ReadyToInstall 等他回来按「安装更新」;
        // 不开也照装,只是装好后不会自动回到桌面。设置页打不开(个别固件没有)就直接装。
        if (!overlayAsked && !android.provider.Settings.canDrawOverlays(activity)) {
            overlayAsked = true
            val opened = runCatching {
                activity.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${activity.packageName}"),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            Log.i(TAG, "update ${info.versionName}: overlay not granted; settings page opened=$opened")
            if (opened) {
                state = AboutState.ReadyToInstall(info, file, overlayHint = true)
                return
            }
        }
        // R151 ①:记下「用户刚发起了这次更新」,新进程收到 MY_PACKAGE_REPLACED 时据此把桌面拉回来
        RelaunchMarks.markUpdatePending(activity)
        // R162 ⑥:自我更新走会话 API(ACTION_VIEW 会把本包标成受限、把主页键接管服务停掉)。字节已拷进会话,文件不必再留。
        val result = SelfUpdate.install(activity, file)
        Log.i(TAG, "update ${info.versionName} (${info.versionCode}) verified; installer: $result (${file.name})")
        state = when (result) {
            ApkInstaller.Result.STARTED -> AboutState.Installing(info)
            ApkInstaller.Result.NEEDS_PERMISSION -> AboutState.NeedsPermission(info)
            ApkInstaller.Result.INVALID, ApkInstaller.Result.BACKGROUND -> AboutState.InstallFailed(info)
        }
        Update.discard(activity, file)
    }

    /**
     * 返回键:下载中 / 校验中 → 取消这次下载,回到「发现新版本」(文件由上面的 `finally` 删),
     * 返回 true;其它状态(包括 ReadyToInstall)什么都不做、返回 false,调用方照常关页。
     */
    fun cancelIfBusy(): Boolean {
        val s = state
        val info = s.info
        if (!s.cancellable || info == null) return false
        Log.i(TAG, "update download cancelled by user")
        begin(AboutState.Found(info))
        return true
    }

    /**
     * 关页(返回 / MENU / HOME 任何一条路):取消进行中的一切(包括等着用户按「安装」的那一份——
     * 它的文件在这里删掉),回到初始态,下次打开从头开始。
     */
    fun reset() {
        val ready = (state as? AboutState.ReadyToInstall)?.file
        overlayAsked = false
        begin(AboutState.Idle)
        if (ready != null) {
            Log.i(TAG, "about closed; discarding verified update ${ready.name} (not installed)")
            Update.discard(activity, ready)
        }
    }

    /** 新会话:作废旧工作的结果、取消旧工作(不等它结束)、切到 [next]。返回新会话号。 */
    private fun begin(next: AboutState): Int {
        session++
        checkJob?.cancel()
        downloadJob?.cancel()
        state = next
        return session
    }

    private companion object {
        const val TAG = "UnitedU"
    }
}

/**
 * 关于页(spec §7.1;R74 起换成设置页外壳的样子):左边标题 + 版本 + 检查结果 + 许可声明 + 项目地址,
 * 右边两颗胶囊(R128):检查更新(下载并安装 / 安装更新 / 忙碌态的进度文字)、恢复默认 ›。一屏放下,**不滚动**(铁律 1);
 * `notes` 最多四行,超出省略。
 *
 * 焦点账本 = 外壳同一个 [CapsuleColumn]:初始焦点循环只信自报、`nonce` 变化(从别的应用回来)重来一轮、
 * `holder == null` 看门狗兜底、`ON_PAUSE` 冻结;上下到头、左右都锁 `Cancel`。目标 [target] 住在 MainActivity
 * (与输入源页的 `inputsFocus` 同一写法,铁律 5:目标与当前分开)。本页自己负责自己的焦点(铁律 3),
 * 底下的外壳因 `covered` 让路,关掉后外壳把焦点接回第一层「关于」那颗胶囊。
 * 「恢复默认」进的确认层是外壳栈上的一层:那时本页让开(MainActivity 不画它、外壳不再 covered),确认层弹栈后本页
 * 重新组合,按 [target] 落回「恢复默认」。
 * **胶囊永远可点**:忙碌态靠 [AboutState.action] = NONE 吞掉点击。若撤掉可聚焦性,它正是持有焦点的唯一节点,
 * 焦点当场被清掉(与 `MainActivity.dispatchKeyEvent` KDoc 里 `canFocus = !idle` 那次是同一个坑)。
 *
 * 胶囊按 [AboutState.action] 分派:CHECK → [onCheck],DOWNLOAD → [onDownload](下载 + 校验 + 安装),
 * INSTALL → [onInstall](把已校验的文件交给安装器)。
 * [onBack] 是返回键:由调用方决定「取消下载」还是「关页」(见 [AboutController.cancelIfBusy])。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AboutScreen(
    versionName: String,
    versionCode: Int,
    state: AboutState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onBack: () -> Unit,
    nonce: Int,
    /**
     * 焦点目标(胶囊 id,R128 起两颗:检查更新 / 恢复默认);住在 MainActivity(`aboutFocus`),每次打开写 null =
     * 落「检查更新」。从恢复默认确认层回来时关于页是一次全新的组合,靠它落回「恢复默认」。
     */
    target: String? = null,
    onTarget: (String) -> Unit = {},
    /** R128:第二颗「恢复默认」——推外壳的恢复默认确认层(关于页随之让开,见 [aboutPageShown])。 */
    onRestoreDefaults: () -> Unit = {},
) {
    val highlight = LocalThemeColors.current.highlight

    // 淡出中的残影(R108)不收返回键;胶囊列自己读 LocalPageGhost 让路、不可聚焦。
    androidx.activity.compose.BackHandler(enabled = !LocalPageGhost.current) { onBack() }

    // 按 ABOUT_CAPSULES 的顺序画(R128 的单测按同一张表数胶囊)。
    val items = ABOUT_CAPSULES.map { id ->
        if (id == ABOUT_RESTORE) {
            // R128(Gordon 2026-09-29):「恢复默认」从「通用」组挪来(Google TV 也把重置放在 系统 → 关于)。右端 › = 进确认层。
            Capsule(
                id = id,
                label = stringResource(R.string.settings_action_restore_defaults),
                trailing = Trailing.Chevron,
                onClick = onRestoreDefaults,
            )
        } else {
            Capsule(
                id = id,
                label = buttonLabel(state),
                onClick = {
                    when (state.action) {
                        AboutAction.CHECK -> onCheck()
                        AboutAction.DOWNLOAD -> onDownload()
                        AboutAction.INSTALL -> onInstall()
                        AboutAction.NONE -> Unit
                    }
                },
            )
        }
    }
    // 本页叠在外壳之上:自己铺一层不透明的 MenuBg,否则底下外壳的胶囊会透出来。
    Box(Modifier.fillMaxSize().pageBackdrop()) {
        ShellScaffold(
            left = {
                ShellTitle(
                    path = stringResource(R.string.menu_settings_title),
                    title = stringResource(R.string.about_title),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        BasicText(
                            text = stringResource(R.string.about_version, versionName, versionCode),
                            style = Type.body.copy(color = Ink.Primary),
                        )
                        // 结果区。第一行始终占位(空白态也留一行高),下面的许可声明不会因为「检查中 → 已是最新」上下跳。
                        Spacer(Modifier.height(10.dp))
                        val info = state.info
                        val headline = headline(state)
                        BasicText(
                            text = headline?.first ?: "",
                            style = Type.body.copy(
                                fontWeight = FontWeight.Medium,
                                color = headline?.second?.color(highlight) ?: Ink.Primary,
                                textAlign = TextAlign.Center,
                            ),
                            modifier = Modifier.heightIn(min = 22.dp),
                        )
                        val outcome = outcome(state)
                        if (outcome != null) {
                            Spacer(Modifier.height(4.dp))
                            BasicText(
                                text = outcome.first,
                                style = Type.body.copy(color = outcome.second.color(highlight), textAlign = TextAlign.Center, lineBreak = Type.Balanced),
                            )
                        }
                        if (info != null && info.notes.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            BasicText(
                                text = info.notes,
                                style = Type.caption.copy(color = Ink.Secondary, textAlign = TextAlign.Center),
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        BasicText(
                            text = stringResource(R.string.about_license_title),
                            style = Type.micro.copy(fontWeight = FontWeight.Medium),
                        )
                        Spacer(Modifier.height(4.dp))
                        BasicText(
                            text = stringResource(R.string.about_license),
                            style = Type.micro.copy(textAlign = TextAlign.Center, lineBreak = Type.Balanced),
                        )
                        Spacer(Modifier.height(8.dp))
                        BasicText(
                            text = stringResource(R.string.about_repo),
                            style = Type.micro,
                        )
                    }
                }
            },
            right = {
                CapsuleColumn(items, target = target ?: ShellPages.ABOUT, onTarget = onTarget, nonce = nonce, covered = false)
            },
        )
    }
}

/** 结果文字的语气:好消息 / 引导用主题 highlight,失败用 [Theme.StatusErrorText]。 */
private enum class Tone {
    GOOD, BAD;

    fun color(highlight: Color): Color = if (this == GOOD) highlight else Theme.StatusErrorText
}

@Composable
private fun buttonLabel(state: AboutState): String = when (state) {
    AboutState.Checking -> stringResource(R.string.about_checking)
    is AboutState.Downloading -> state.percent
        ?.let { stringResource(R.string.about_downloading, it) }
        ?: stringResource(R.string.about_downloading_unknown)
    is AboutState.Verifying -> stringResource(R.string.about_verifying)
    else -> when (state.action) {
        AboutAction.DOWNLOAD -> stringResource(R.string.about_download)
        AboutAction.INSTALL -> stringResource(R.string.about_ready_install)
        // CHECK;NONE 只出现在上面三个忙碌态里,这里不会走到,给它同一个文案兜底。
        AboutAction.CHECK, AboutAction.NONE -> stringResource(R.string.about_check)
    }
}

/** 按钮下方第一行:有新版就是「发现新版本 x」,否则是检查结果(已是最新 / 失败原因),空白态为 null。 */
@Composable
private fun headline(state: AboutState): Pair<String, Tone>? {
    val info = state.info
    if (info != null) return stringResource(R.string.about_found, info.versionName) to Tone.GOOD
    return when (state) {
        AboutState.Latest -> stringResource(R.string.about_latest) to Tone.GOOD
        is AboutState.Failed -> {
            val text = when (state.reason) {
                CheckFailure.NETWORK -> stringResource(R.string.about_net_failed)
                CheckFailure.BAD_JSON -> stringResource(R.string.about_bad_json)
            }
            text to Tone.BAD
        }
        else -> null
    }
}

/** 第二行:上一次下载 / 安装尝试的结局;还没尝试或正在进行时为 null。 */
@Composable
private fun outcome(state: AboutState): Pair<String, Tone>? = when (state) {
    is AboutState.VerifyFailed -> stringResource(R.string.about_verify_failed) to Tone.BAD
    is AboutState.DownloadFailed -> stringResource(R.string.about_download_failed) to Tone.BAD
    is AboutState.InstallFailed -> stringResource(R.string.about_install_failed) to Tone.BAD
    is AboutState.NeedsPermission -> stringResource(R.string.about_needs_permission) to Tone.GOOD
    is AboutState.ReadyToInstall -> if (state.overlayHint) stringResource(R.string.about_overlay_hint) to Tone.GOOD else null
    // 与导入页同一句「已交给系统安装器」,两处说的是同一件事。
    is AboutState.Installing -> stringResource(R.string.import_apk_started) to Tone.GOOD
    else -> null
}
