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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 关于页那颗唯一按钮此刻按下去做什么。 */
enum class AboutAction { CHECK, DOWNLOAD, NONE }

/**
 * 关于页的状态机(spec §7.3):
 * `Idle → Checking → Latest | Failed(reason) | Found(info)`,
 * `Found → Downloading(p) → Verifying → Installing | VerifyFailed`,
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
 * 按钮接 [check] / [downloadAndInstall],返回键接 [cancelIfBusy],关页接 [reset]。
 *
 * **异步结果怎么不写错地方**:所有异步工作写回 [state] 之前,先比对启动时记下的 [session]。
 * 每次开始新动作、取消、关页都 `session++`,旧工作的结果自然作废——比对的是一个只增不减的
 * 计数,不是「谁负责清掉」的布尔闩(铁律 7),关页再开、连按取消都不会留下卡住的状态。
 * [session] 只在主线程上读写:协程续体跑在 `lifecycleScope` 的主线程,下载进度从 IO 线程
 * 经 [main] 投递回主线程后才比对;进度还额外要求「当前仍是 Downloading」——
 * 投递顺序与协程续体顺序不保证一致,晚到的进度不能把「校验中 / 已交给安装器」改回「下载中」。
 *
 * **两次下载不会同时碰 `update.apk`**:「下载 → 校验 → 交给安装器」整段持有进程级的
 * [Update.fileLock]。被取消的上一次下载要等它 `finally` 里的清理做完才放锁,新下载拿到锁时
 * 旧的已彻底结束;另一个 MainActivity 实例(见 fileLock 的 KDoc)的下载同样排在这把锁后面。
 * 检查更新不碰文件,不拿锁,取消后也不等它(阻塞中的 DNS / 连接可能要等到超时)。
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
     * 下载 → SHA-256 校验 → 交给系统安装器(复用 M6 的 [ApkInstaller],权限引导与
     * `RelaunchAfterUpdate` 一并沿用)。校验不符删文件、提示「校验失败」;
     * 被取消(返回键 / 关页)时,半截的临时文件由 [Update.download] 删,
     * 已改名但还没校验完的 `update.apk` 由 [downloadVerifyInstall] 的 `finally` 删——
     * 只有校验通过的文件才留给安装器。整段持有 [Update.fileLock](见类 KDoc);
     * 排队等锁期间按钮显示不带数字的「下载中…」,返回键照样能取消。
     *
     * 安装一步包在 `withStarted` 里:下载途中电视进了系统屏保(本 Activity 停在后台),
     * 此刻去 `startActivity` 可能被后台启动限制悄悄拦下;等用户回到前台再交给安装器。
     */
    fun downloadAndInstall() {
        val info = state.info ?: return
        if (state.action != AboutAction.DOWNLOAD) return
        val my = begin(AboutState.Downloading(info, null))
        downloadJob = activity.lifecycleScope.launch {
            Update.fileLock.withLock { downloadVerifyInstall(info, my) }
        }
    }

    /**
     * [downloadAndInstall] 持锁执行的那一整段。[my] 是启动时的会话号:每一步写回 [state] 之前
     * 都先比对,不等就说明已被取消 / 关页,直接收手(`finally` 照样清理)。
     */
    private suspend fun downloadVerifyInstall(info: LatestInfo, my: Int) {
        val file = Update.apkFile(activity)
        var verified = false
        try {
            val ok = Update.download(info.apkUrl, file) { p ->
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
            val actual = withContext(Dispatchers.IO) { runCatching { sha256Hex(file) }.getOrNull() }
            if (my != session) return
            if (actual != info.sha256) {
                // 当场删(不等 finally),日志里记的是删除的真实结果。
                val deleted = withContext(Dispatchers.IO) { file.delete() }
                Log.w(TAG, "update sha256 mismatch: expected ${info.sha256}, got $actual; deleted=$deleted")
                state = AboutState.VerifyFailed(info)
                return
            }
            verified = true
            // 仍在锁内:校验过的文件在交给安装器之前不会被另一个实例的下载覆盖。
            val result = activity.lifecycle.withStarted { ApkInstaller.install(activity, file) }
            Log.i(TAG, "update ${info.versionName} (${info.versionCode}) verified; installer: $result")
            if (my != session) return
            state = when (result) {
                ApkInstaller.Result.STARTED -> AboutState.Installing(info)
                ApkInstaller.Result.NEEDS_PERMISSION -> AboutState.NeedsPermission(info)
                ApkInstaller.Result.INVALID, ApkInstaller.Result.BACKGROUND -> AboutState.InstallFailed(info)
            }
        } finally {
            // 取消路径上这里的挂起调用必须 NonCancellable,否则删文件那一步本身会被取消掉。
            if (!verified) withContext(NonCancellable + Dispatchers.IO) { file.delete() }
        }
    }

    /**
     * 返回键:下载中 / 校验中 → 取消这次下载,回到「发现新版本」(文件由上面两处 `finally` 删),
     * 返回 true;其它状态什么都不做、返回 false,调用方照常关页。
     */
    fun cancelIfBusy(): Boolean {
        val s = state
        val info = s.info
        if (!s.cancellable || info == null) return false
        Log.i(TAG, "update download cancelled by user")
        begin(AboutState.Found(info))
        return true
    }

    /** 关页(返回 / MENU / HOME 任何一条路):取消进行中的一切,回到初始态,下次打开从头开始。 */
    fun reset() {
        begin(AboutState.Idle)
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
 * 关于页(spec §7.1):应用名 + 版本、「检查更新」按钮、许可声明短文、项目地址。
 * 一屏放下,**不滚动**(铁律 1);`notes` 最多四行,超出省略。
 *
 * 焦点账本(与 ImportScreen 同一手法):
 * - **唯一可聚焦节点是那颗按钮**,上下左右全锁 `Cancel`,没有任何方向能移出去;
 * - 焦点落没落下只信按钮自报的 `focused`(铁律 2/4);守卫 `focused` 同时是 key(铁律 6):
 *   焦点若因任何原因丢了,`focused` 变 false → 效果以新 key 重启 → 重新请求;
 *   `nonce` 变化(从别的应用回来)同理。本页自己负责自己的焦点恢复(铁律 3),
 *   首页看门狗此时因 `previewing` 让路;
 * - **按钮永远 `clickable(enabled = true)`**:忙碌态靠 [AboutState.action] = NONE 吞掉点击。
 *   若写成 `enabled = false`,`clickable` 会撤掉自己的可聚焦节点——它正是持有焦点的唯一节点,
 *   焦点当场被清掉(与 `MainActivity.dispatchKeyEvent` KDoc 里 `canFocus = !idle` 那次是
 *   同一个坑),而且清掉之后请求循环也落不下,遥控器全死。
 *
 * [onBack] 是返回键:由调用方决定「取消下载」还是「关页」(见 [AboutController.cancelIfBusy])。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AboutScreen(
    versionName: String,
    versionCode: Int,
    state: AboutState,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onBack: () -> Unit,
    nonce: Int,
) {
    val fr = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val highlight = LocalThemeColors.current.highlight

    androidx.activity.compose.BackHandler { onBack() }

    LaunchedEffect(nonce, focused) {
        if (focused) return@LaunchedEffect
        var frames = 0
        while (!focused && frames < 60) {
            withFrameNanos { }
            runCatching { fr.requestFocus() }
            frames++
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()
            .background(Color.Black.copy(alpha = 0.72f)),
        // **顶端对齐、固定上边距,不居中**:结果区(新版本 / notes / 失败原因)出现时面板会变高,
        // 居中的话整块上移,持有焦点的按钮跟着跳一截。顶端固定后只往下长,按钮纹丝不动。
        // 80dp 的余量按最高的状态算过:结局行 + 四行 notes + 三行英文许可声明,底边仍在 540dp 以内。
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .padding(top = 80.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Theme.DialogSurface)
                .width(560.dp)
                .padding(horizontal = 32.dp, vertical = 26.dp),
        ) {
            BasicText(
                text = stringResource(R.string.about_title),
                style = TextStyle(
                    fontFamily = Theme.Sans,
                    fontWeight = FontWeight.Medium,
                    color = highlight,
                    fontSize = 16.sp,
                    letterSpacing = 1.sp,
                ),
            )
            Spacer(Modifier.height(6.dp))
            BasicText(
                text = stringResource(R.string.about_version, versionName, versionCode),
                style = TextStyle(fontFamily = Theme.Sans, color = Theme.EmphasisText, fontSize = 15.sp),
            )

            Spacer(Modifier.height(20.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (focused) highlight.copy(alpha = 0.16f) else Theme.UnfocusedSurface)
                    .then(
                        if (focused) Modifier.border(
                            BorderStroke(1.dp, highlight.copy(alpha = 0.7f)),
                            RoundedCornerShape(10.dp),
                        ) else Modifier,
                    )
                    .focusRequester(fr)
                    .focusProperties {
                        up = FocusRequester.Cancel; down = FocusRequester.Cancel
                        left = FocusRequester.Cancel; right = FocusRequester.Cancel
                    }
                    .onFocusChanged { focused = it.isFocused }
                    .clickable {
                        when (state.action) {
                            AboutAction.CHECK -> onCheck()
                            AboutAction.DOWNLOAD -> onInstall()
                            AboutAction.NONE -> Unit
                        }
                    }
                    .padding(vertical = 14.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = buttonLabel(state),
                    style = TextStyle(
                        fontFamily = Theme.Sans,
                        fontWeight = FontWeight.Medium,
                        color = if (focused) highlight else Theme.ButtonText,
                        fontSize = 14.sp,
                    ),
                )
            }

            // 结果区。第一行始终占位(空白态也留一行高;22dp 盖得住中文回落字体的行高),
            // 下面的许可声明不会因为「检查中 → 已是最新」上下跳。
            Spacer(Modifier.height(12.dp))
            val info = state.info
            val headline = headline(state)
            BasicText(
                text = headline?.first ?: "",
                style = TextStyle(
                    fontFamily = Theme.Sans,
                    fontWeight = FontWeight.Medium,
                    color = headline?.second?.color(highlight) ?: Theme.EmphasisText,
                    fontSize = 14.sp,
                ),
                modifier = Modifier.heightIn(min = 22.dp),
            )
            val outcome = outcome(state)
            if (outcome != null) {
                Spacer(Modifier.height(4.dp))
                BasicText(
                    text = outcome.first,
                    style = TextStyle(fontFamily = Theme.Sans, color = outcome.second.color(highlight), fontSize = 13.sp),
                )
            }
            if (info != null && info.notes.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                BasicText(
                    text = info.notes,
                    style = TextStyle(
                        fontFamily = Theme.Sans,
                        color = Theme.SecondaryText,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    ),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(22.dp))
            BasicText(
                text = stringResource(R.string.about_license_title),
                style = TextStyle(
                    fontFamily = Theme.Sans,
                    fontWeight = FontWeight.Medium,
                    color = Theme.HintText,
                    fontSize = 11.sp,
                ),
            )
            Spacer(Modifier.height(4.dp))
            BasicText(
                text = stringResource(R.string.about_license),
                style = TextStyle(fontFamily = Theme.Sans, color = Theme.FootnoteText, fontSize = 11.sp, lineHeight = 16.sp),
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = stringResource(R.string.about_repo),
                style = TextStyle(fontFamily = Theme.Sans, color = Theme.FootnoteText, fontSize = 11.sp),
            )
            Spacer(Modifier.height(14.dp))
            BasicText(
                text = stringResource(R.string.menu_back_to_close),
                style = TextStyle(fontFamily = Theme.Sans, color = Theme.FooterHintText, fontSize = 10.sp),
            )
        }
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
        // CHECK;NONE 只出现在上面三个忙碌态里,这里不会走到,给它同一个文案兜底。
        else -> stringResource(R.string.about_check)
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
    // 与导入页同一句「已交给系统安装器」,两处说的是同一件事。
    is AboutState.Installing -> stringResource(R.string.import_apk_started) to Tone.GOOD
    else -> null
}
