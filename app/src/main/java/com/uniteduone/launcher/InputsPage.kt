package com.uniteduone.launcher

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **输入源页**(R91,Gordon 2026-09-27 定):顶栏「输入源」胶囊打开,取代首页的输入源行(R92 删掉)。
 * 设置外壳同款:左边标题「输入源」+ 当前输入源说明,右边一列胶囊,每个输入源一颗,当前在用的右端 ✓;
 * 列表末尾「恢复隐藏的输入源 N 个」(有隐藏才出现)。确定 = 切换;长按 / MENU = 改名 / 隐藏(MainActivity 识别)。
 *
 * 焦点账本整套借 [CapsuleColumn](外壳每一层用的那一份):逐项 requester、目标 id 与持有者分开、ON_PAUSE 冻结、
 * 定位效果 + `holder == null` 看门狗、[covered] 让路(胶囊菜单 / 改名对话框盖在上面时)。**目标 id 住在 MainActivity**
 * ([target] / [onTarget]),与外壳栈帧的 `focus` 同一个道理:条件行(恢复隐藏)出现 / 消失、某一颗被隐藏时,
 * 目标按 id 跟着那一颗走,那一颗没了退回上一颗。
 */

/** 列表末尾「恢复隐藏的输入源」那颗胶囊的 id(输入 id 形如 `com.xxx/.Service/HW1`,不会撞上)。 */
const val INPUTS_RESTORE_ID = "__restoreHiddenInputs__"

/** 输入源页读一次盘得到的全部内容。[visible] 已去重 / 合并调谐器 / 去掉隐藏 / 换上改过的名字。 */
data class InputsPageData(val visible: List<InputEntry>, val hiddenCount: Int)

/**
 * 输入源页的列表(纯函数,JVM 可测):按电视输入菜单的顺序排(R97)→ HDMI-CEC 父子去重 → 多调谐器合并 → 去掉隐藏的、换上改过的名字(同 R92 前的首页输入源行)。
 * [hiddenCount] 只数**这台电视现在还列得出来**的输入里被隐藏的——hidden-inputs.json 里残留的旧 id 不算,
 * 否则「恢复隐藏的输入源 2 个」按下去什么都没回来。
 */
internal fun inputsPageData(all: List<InputEntry>, hidden: Set<String>, names: Map<String, String>): InputsPageData {
    val merged = mergeTuners(dedupeCec(orderInputs(all)))
    return InputsPageData(applyInputPrefs(merged, hidden, names), merged.count { it.id in hidden })
}

/** 胶囊 id 清单:每个可见输入一颗,有隐藏时末尾加「恢复隐藏的输入源」。 */
internal fun inputsCapsuleIds(data: InputsPageData): List<String> =
    data.visible.map { it.id } + if (data.hiddenCount > 0) listOf(INPUTS_RESTORE_ID) else emptyList()

/** 进页时焦点落哪:当前在用的那一颗(还在的话),否则第一颗。 */
internal fun inputsInitialFocus(ids: List<String>, current: String?): String? =
    current?.takeIf { it in ids } ?: ids.firstOrNull()

/** IO 线程:读系统输入源 + hidden-inputs.json + titles.json。 */
internal fun loadInputsPage(ctx: Context): InputsPageData =
    inputsPageData(
        runCatching { Inputs.load(ctx) }.getOrDefault(emptyList()),
        runCatching { HiddenInputs.read(ctx) }.getOrDefault(emptySet()),
        runCatching { Titles.read(ctx) }.getOrDefault(emptyMap()),
    )

/**
 * @param current 「当前在用」的输入 id:本次进程里经 UnitedU 最后切过去的那个(系统没有给第三方桌面读当前输入源的
 *   公开 API);null = 不知道,不画 ✓,左边写一句说明。
 * @param onLoaded 每次读完盘把可见列表交给 MainActivity(长按 / MENU 据此找焦点那一颗),不进任何组合。
 */
@Composable
fun InputsPage(
    nonce: Int,
    covered: Boolean,
    revision: Int,
    target: String?,
    onTarget: (String) -> Unit,
    current: String?,
    onSwitch: (InputEntry) -> Unit,
    onRestoreHidden: () -> Unit,
    onLoaded: (List<InputEntry>) -> Unit,
    onBack: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // revision:改名 / 隐藏 / 恢复写完盘 MainActivity 都 revision++,这里跟着重读;新数据到之前旧列表原样留着。
    val data by produceState<InputsPageData?>(null, revision) {
        value = withContext(Dispatchers.IO) { loadInputsPage(ctx) }
    }
    androidx.activity.compose.BackHandler { onBack() }
    val d = data
    androidx.compose.runtime.SideEffect { d?.let { onLoaded(it.visible) } }
    val currentLabel = d?.visible?.firstOrNull { it.id == current }?.label
    Box(Modifier.fillMaxSize().background(GtvTokens.MenuBg)) {
        ShellScaffold(
            left = {
                ShellTitle(path = null, title = stringResource(R.string.inputs_page_title)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val lead = when {
                            d != null && d.visible.isEmpty() && d.hiddenCount == 0 -> stringResource(R.string.inputs_page_empty)
                            currentLabel != null -> stringResource(R.string.inputs_page_current, currentLabel)
                            else -> stringResource(R.string.inputs_page_intro)
                        }
                        BasicText(lead, style = shellBodyStyle.copy(textAlign = TextAlign.Center))
                        if (d != null && d.visible.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            BasicText(
                                stringResource(R.string.inputs_page_hint),
                                style = shellBodyStyle.copy(textAlign = TextAlign.Center),
                            )
                        }
                    }
                }
            },
            right = {
                if (d == null) return@ShellScaffold
                val items = d.visible.map { e ->
                    Capsule(
                        id = e.id,
                        label = e.label,
                        trailing = if (e.id == current) Trailing.Check else Trailing.None,
                        onClick = { onSwitch(e) },
                    )
                } + if (d.hiddenCount > 0) {
                    listOf(
                        Capsule(
                            id = INPUTS_RESTORE_ID,
                            label = stringResource(R.string.settings_restore_hidden_inputs),
                            // 按下去当场生效、不打开别的界面:只写个数,不画 ›(同外壳 JUMP_ROWS 的规则)。
                            trailing = Trailing.Value(stringResource(R.string.settings_hidden_inputs_count, d.hiddenCount)),
                            onClick = onRestoreHidden,
                        ),
                    )
                } else emptyList()
                // 一个输入都没有(非电视 / 模拟器):给一颗「返回」,焦点总有地方落,不留一个没有焦点的整屏浮层。
                val shown = items.ifEmpty {
                    listOf(Capsule(id = "__back__", label = stringResource(R.string.inputs_page_back), onClick = onBack))
                }
                val want = target ?: inputsInitialFocus(shown.map { it.id }, current)
                CapsuleColumn(shown, want, onTarget, nonce, covered)
            },
        )
    }
}
