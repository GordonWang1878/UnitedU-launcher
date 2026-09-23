package com.uniteduone.launcher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * **Ruling R28(2026-09-21,owner 真机反馈 Round 7)**:焦点柔光——在描边**外缘**之外再铺一层
 * 按指数衰减的大面积辉光,是 owner 说「从沙发上完全感觉不到动效」的正解(2dp 细环在 150ms 内
 * 淡入,那个距离上肉眼捕捉不到;[GtvLayout.APP_FOCUS_GLOW_DP] 的柔光捕捉得到)。数值出处、实测剖面表、为什么不用
 * `BlurMaskFilter`,全部见 [GtvLayout.APP_FOCUS_GLOW_DP] 一族常量的 KDoc。
 *
 * 画法:一串首尾相接的同心圆角矩形描边,每圈宽 [GtvLayout.APP_FOCUS_GLOW_RING_DP],
 * alpha 走 [GtvLayout.focusGlowAlpha];圆角半径 = 描边外缘的圆角半径 + 该圈的偏移量——
 * 与卡片同心(R31,同一条不变量见 [GtvLayout.focusRingRadius])。
 *
 * **柔光会铺出卡片间距之外**:[GtvLayout.APP_FOCUS_GLOW_DP](60dp)> `GtvLayout.CARD_GAP`(20dp),
 * 也大于上下两行卡片之间的 54dp(本行 `rowVerticalPad` 7 + `ROW_GAP` 40 + 邻行 `rowVerticalPad` 7,R51 起;
 * 卡片标题关着时;R48 之前上方还隔着本行的行标题带),所以它必然会淡淡地盖到左右邻居卡与上一行卡片的
 * 底部——Google 那份实测剖面本身就是这样(它的行距比我们还紧),不是 bug,别为此砍短柔光。
 *
 * **绘制顺序上真正的不对称**(整枝审查 F,2026-09-22 更正:此前这里写「要对称得给焦点卡加
 * zIndex」是错的——首页 `AppCard` 的外层 `Column` 早有 `zIndex(if (focused) 1f else 0f)`,
 * 同一行内焦点卡本来就浮在左右邻居之上,左右是对称的):
 * (a) 编辑页的 `AddCard`/`MissingCard` 与 `RowIconPicker` 的格子**没有** zIndex,那里同一行内
 *     左邻先画被柔光盖住、右邻后画盖住柔光,左右不对称;
 * (b) **跨行**:zIndex 只在同一个父容器的兄弟之间生效,首页各行是 `Column` 的兄弟,后面的行
 *     后画——焦点卡 60dp 的柔光向下探到下一行会被下一行的卡切掉,向上探到上一行则盖在上一行
 *     的卡上,上下不对称。R48 之前行间还隔着行标题带,模拟器实测(`docs/screenshots/gtv-review-F-glow-cross-row.jpg`)
 *     柔光到下一行卡片处已进收尾段、≤ 2/255,看不出被切,当时只改注释、不给行容器加 zIndex。
 *     **R48 行距收紧 32.5dp 后这条不再成立**(整枝评审 2026-09-23 模拟器复测,中档无卡片标题、默认主题色,
 *     焦点在第 0 张与第 1 张两帧相减):描边外缘到邻行卡片只剩 13.7dp,还在数据区。焦点卡与下一行之间的
 *     暗色间隙被抬高约 +27~44/255,到下一行卡片上边缘一步掉回 0(后画的卡把柔光盖住);上一行浅色卡片的
 *     底部只亮 +1~2(卡本身接近 accent 的亮度),间隙同样 +27~29。owner 真机试过 R48/R49 说「可以了」,
 *     这里只如实记录,没有改。
 *
 * **这是绘制、不是布局**:整段画在 `drawBehind` 里,不改变任何测量尺寸。
 * `GtvLayout.appFocusOverflow`、`rowPitch`、`Theme.gtvCardMetrics.rowVerticalPad` 里**都没有**
 * 柔光这一项,**也不要"顺手补全"**——加进去会把行间距撑开 [GtvLayout.APP_FOCUS_GLOW_DP](60dp),破坏已经与 Google 对齐的
 * 纵向节奏(理由与实测依据见 [GtvLayout.appFocusOverflow] 与 [GtvLayout.APP_FOCUS_GLOW_DP])。
 *
 * @param edgeX 布局框左/右边到**描边外缘**的距离(px)。注意是外缘,不是 `drawRoundRect` 那个
 *   走中心线的偏移量——调用方要自己加上半个描边宽。
 * @param edgeY 同上,上/下方向。
 * @param cornerAtEdge 描边外缘处的圆角半径(px)。
 * @param alpha 焦点动画的整体可见度(与描边共用同一份 `motionSpec`:柔光是焦点处理的一部分,
 *   不另起时长);调用方已确保 > 0 才调进来。
 */
private fun DrawScope.drawFocusGlow(
    edgeX: Float,
    edgeY: Float,
    cornerAtEdge: Float,
    color: Color,
    alpha: Float,
) {
    if (alpha <= 0f) return
    // 伽马编码空间的加权和,与实测剖面、与画布混合同一套口径(不用 Color.luminance(),它会先线性化)。
    val fgLuma = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
    val ringDp = GtvLayout.APP_FOCUS_GLOW_RING_DP
    val ringPx = ringDp.dp.toPx()
    val rings = (GtvLayout.APP_FOCUS_GLOW_DP / ringDp).toInt()
    for (i in 0 until rings) {
        // 第 i 圈的中心线落在距描边外缘 (i + 0.5) × ringDp 处:圈与圈首尾相接,合起来正好铺满
        // 0 → APP_FOCUS_GLOW_DP,不重叠也不留缝。
        // 按 accent 的实际亮度把"目标亮度增量"反推成 alpha(见 GtvLayout.focusGlowAlphaFor 的
        // KDoc:画布的 srcOver 在伽马编码空间混合,所以增量 ≈ alpha × 前景亮度,不能拿增量当 alpha)。
        val ringAlpha = GtvLayout.focusGlowAlphaFor((i + 0.5f) * ringDp, fgLuma) * alpha
        if (ringAlpha <= 0f) continue
        val off = (i + 0.5f) * ringPx
        val r = cornerAtEdge + off
        drawRoundRect(
            color = color.copy(alpha = color.alpha * ringAlpha),
            topLeft = Offset(-(edgeX + off), -(edgeY + off)),
            size = Size(size.width + 2 * (edgeX + off), size.height + 2 * (edgeY + off)),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(width = ringPx),
        )
    }
}

/**
 * **内容卡**(content card)焦点画法(实测):不缩放,在布局框外扩 [GtvLayout.FOCUS_OUTSET] dp 处
 * 画一圈 [GtvLayout.FOCUS_STROKE] dp 的描边。owner 反馈 Round 4(2026-09-21)之后,gtv 线首页的
 * 应用卡片已经全部改用 [gtvAppFocusFrame](app tile 的缩放 + 描边处理,见该函数 KDoc);这个
 * 函数继续被 `RowIconPicker`(小网格图标,不是 app)使用,不是死代码;`gtvAppFocusFrame` 的
 * `moving` 分支(首页原地移动态的高亮描边,Google 没有对应物)只借用这里的 `FOCUS_OUTSET` /
 * `FOCUS_STROKE` 两个常量,几何自 2026-09-22 起跟着缩放后边缘走(见该函数 `moving` 参数说明)。
 *
 * 为什么不用 `Modifier.border`:border 画在布局框**上**,画不到框外。`drawBehind` 的画布不受
 * 布局框限制(只要父链上没有 clip),所以用负偏移把矩形撑出去。
 * **父容器不能 clip**:行容器已经有 `wrapContentWidth(unbounded)`,别再加 `clipToBounds`。
 *
 * **圆角(R31)**:`r = corner + out`——描边中心线离卡边 `out`,半径就是卡片圆角 + `out`,
 * 与卡片同心。这里的卡不缩放,所以本来就是同心几何,R31 没有改它;[gtvAppFocusFrame]
 * 那边缩放后的版本是同一条原则(`corner × scale + gap + stroke/2`,见 [GtvLayout.focusRingRadius])。
 *
 * Fix 3(owner 反馈 R2,2026-09-20):`focused` 曾经直接门控这条 `drawBehind`——瞬间出现 / 瞬间
 * 消失,是「焦点一下子跳到这、一下子跳到那」的卡顿感来源之一。改成 `composed {}`:每个调用点
 * 各自带一份独立的 `animateFloatAsState`,只 animate 这一圈描边的**可见度**——`focused` 参数
 * 本身的语义、以及调用方各自 `onFocusChanged` 里上报给焦点账本的时机,都不受影响,保持即时
 * (动画不门控焦点逻辑,焦点铁律)。
 * 时长/曲线见 [GtvLayout.FOCUS_FADE_IN_MS]/[GtvLayout.FOCUS_FADE_OUT_MS]/[Theme.AppFocusEasing]
 * 的 KDoc(owner 反馈 Round 4 起已是 Google 实测值,不再是占位)。
 *
 * **Ruling R28(owner 反馈 Round 7)**:描边外缘之外再铺一层柔光([drawFocusGlow]),几何基准
 * 换成这个函数自己的 `FOCUS_OUTSET`/`FOCUS_STROKE`,alpha 与描边共用同一个动画量。
 */
fun Modifier.gtvFocusStroke(focused: Boolean, color: Color, corner: Dp): Modifier = composed {
    val alpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (focused) GtvLayout.FOCUS_FADE_IN_MS else GtvLayout.FOCUS_FADE_OUT_MS,
            easing = Theme.AppFocusEasing,
        ),
        label = "gtvFocusStrokeAlpha",
    )
    this.drawBehind {
        if (alpha <= 0f) return@drawBehind
        val out = GtvLayout.FOCUS_OUTSET.dp.toPx()
        val w = GtvLayout.FOCUS_STROKE.dp.toPx()
        val r = (corner.toPx() + out)
        // R28 柔光(先画,描边盖在上面才保持清脆):几何基准是这个函数自己的
        // FOCUS_OUTSET/FOCUS_STROKE——描边走中心线,外缘在 out + w/2 处。柔光的三个数值
        // (总距离/峰值/半衰期)是从 app tile 那份实测剖面**借用**的,不是又给内容卡单独量了
        // 一份;与 FOCUS_FADE_IN_MS/OUT_MS 同一种借用关系(见那两个常量的 KDoc),如实记录。
        drawFocusGlow(
            edgeX = out + w / 2f,
            edgeY = out + w / 2f,
            cornerAtEdge = r + w / 2f,
            color = color,
            alpha = alpha,
        )
        drawRoundRect(
            color = color.copy(alpha = color.alpha * alpha),
            topLeft = Offset(-out, -out),
            size = Size(size.width + 2 * out, size.height + 2 * out),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(width = w),
        )
    }
}

/**
 * **app tile**(应用图块)聚焦画法——owner 反馈 Round 4(2026-09-21):controller 指出这条线的
 * 首页 100% 是 app,不是 Google 无边界推荐流里的 content card;之前套用 content card 的静态
 * 外扩描边(只因为我们的卡片形状恰好也是 16:9),是错认了 Google 的分类。Google 对 app tile
 * 的真实处理是**聚焦放大 [GtvLayout.APP_FOCUS_SCALE] 倍**(旧版 launcherx APK
 * `animator/card_focus`/`card_unfocus`,`duration` 引用
 * `@integer/default_focused_animation_duration_ms`,`interpolator` 属性缺失 → 平台默认
 * `AccelerateDecelerateInterpolator`,即 [Theme.AppFocusEasing]),描边贴着**缩放后**的边缘
 * 外扩 [GtvLayout.APP_FOCUS_GAP] + [GtvLayout.APP_FOCUS_STROKE]。
 *
 * **Ruling R34(owner 反馈 Round 9,2026-09-22)**:上一段说的 `card_focus` 150 ms 只剩**失焦**
 * 一半还成立——真机上看到的进焦放大慢得多(`focused_frame_animator_duration_ms = 1200`,减速型),
 * 进焦走 [GtvLayout.FOCUS_SCALE_IN_MS] + [Theme.AppFocusScaleInEasing],失焦走
 * [GtvLayout.FOCUS_FADE_OUT_MS] + [Theme.AppFocusEasing],不对称;缩放 / 描边 / 柔光三者同一份 spec。
 *
 * **缩放不能影响布局**(owner 反馈原话「no layout change」):用 `graphicsLayer(scaleX/scaleY)`
 * 而不是 `Modifier.scale()` 或改 `.size()`——前者只在绘制阶段变换像素,父级看到的测量尺寸
 * 始终是未缩放的 `cardWidth × cardHeight`,卡片下方的标题文字(`AppCard` 里的兄弟节点,按
 * 未缩放的布局尺寸定位)不会跟着跳动或错位。
 *
 * **描边为什么不能简单套一层 `graphicsLayer` 就跟着放大**:Google 的间隙/描边宽度是固定 dp
 * 值,不随缩放倍数变粗——如果描边跟着卡片一起进同一个 `graphicsLayer`,描边本身的粗细也会被
 * 放大 1.10 倍,不符实测。所以这里的画法是:描边在 `drawBehind` 里**手动**按「未缩放尺寸 ×
 * 当前动画中的 scale 值」算出缩放后边缘的位置,再往外加固定的 gap/stroke,画完之后才对
 * **后续**的实际内容(卡片背景/图片/圆角裁剪)应用 `graphicsLayer` 缩放——`drawBehind` 在
 * `graphicsLayer` 之前(链上更外层),不受它影响,数值计算与视觉缩放各管一段。
 *
 * **Ruling R28(owner 反馈 Round 7)**:描边只是焦点处理的一半,另一半是描边外面那层大面积
 * 柔光([drawFocusGlow]);Google 有、我们此前一点没画,这才是 owner 说「从沙发上完全感觉不到
 * 动效」的主因。柔光的几何与描边同源(同样跟着 `scale` 长大)、alpha 与描边共用同一个
 * `ringAlpha`,**但只是绘制,不进任何布局量**(见 [GtvLayout.appFocusOverflow] 的 KDoc)。
 *
 * **绘制顺序**:柔光 → 聚焦描边 → 移动描边,由外向内、后画的盖在先画的上面。
 *
 * **R49 边界(整枝评审 2026-09-23 补)**:本函数必须挂在 `gtvCardFade`(`AppCard.kt`,卡片淡化)的
 * **外层**,即 modifier 链上更靠前。这里的 `drawBehind`(柔光 → 聚焦描边 → 移动描边)画在淡化层的
 * 离屏图层之外,主题色原样;挂反了,三者全画进淡化层——被去饱和、亮度 × 0.75,而且那个离屏图层以
 * 卡片自己的布局框为界,画在框外的描边与柔光会被整个裁掉。`AppCard` 经 `gtvFocusFrameOverFade` 一处
 * 组合两者,`GtvGlowTest` 按 modifier 链的元素顺序钉住;新卡片要同时用两者时也走那个函数,别自己拼链。
 *
 * **Ruling R30(owner 反馈 Round 8)**:[afterShift] 为 true 且正在进焦时,缩放 + 描边 + 柔光
 * 的淡入整体推迟 [GtvLayout.FOCUS_AFTER_SHIFT_DELAY_MS](`tween` 的 `delayMillis`),让行位移
 * 先走、放大后到;失焦分支永远不延迟。判定「这次焦点变化有没有触发行位移」是调用方的事
 * (首页在 `CategoryRow` 的焦点回调里判,见那里),这里只认这个布尔。`animateFloatAsState`
 * 在目标值变化那一刻读取 spec,所以位移结束后 `afterShift` 翻回 false 不会打断已经在跑的动画。
 *
 * @param afterShift 这次进焦是否伴随行位移(纵向切行或横向滑行)。默认 `false`(`RowIconPicker`
 *   不传,立即放大)。编辑页 2026-09-23 起也传(ui-pending #10):`AppCard`/`AddCard`/`MissingCard`
 *   在编辑页的焦点回调里按「纵向首行会不会变」判定(只算纵向,见 `EditScreen` 的 `landedWithShift`)。
 * @param moving 首页原地移动态(M4b)与编辑页搬运态:被搬的那张卡的高亮描边,它标的是
 *   「正在搬哪张」,Google 没有对应物。**几何跟着缩放后的边缘走**(整枝审查 A,2026-09-22):
 *   外扩 = 当前 `scale` 的溢出 + [GtvLayout.FOCUS_OUTSET],与聚焦描边同一算法,只是外扩量换成
 *   FOCUS_OUTSET(5dp)、落在聚焦描边外缘(4dp)之外。此前写成固定的布局框外 5dp,被缩放后的
 *   卡片(横向外扩 7.65dp)整条盖住——搬运中焦点恒在被搬的卡上,等于这条描边从没露出过。
 *   未聚焦时 `scale` = 1,退化为固定几何。默认 `false`(`AddCard` 没有搬运概念,不传)。
 *   `AppCard` 与 `EditScreen.MissingCard` 都经这里画,两处同修。
 *   **绘制顺序**:聚焦描边先画、移动描边后画、盖在上面——两者都在缩放之外(同一个
 *   `drawBehind`,不是分成两次 `gtvFocusStroke`/`gtvAppFocusFrame` 调用叠链),不会因为
 *   `graphicsLayer` 在中间插了一刀而让后画的移动描边被意外裹进缩放里。
 */
fun Modifier.gtvAppFocusFrame(
    focused: Boolean,
    accentColor: Color,
    corner: Dp,
    moving: Boolean = false,
    movingColor: Color = Color.Unspecified,
    afterShift: Boolean = false,
): Modifier = composed {
    // R34:进焦 / 失焦不对称——进焦 FOCUS_SCALE_IN_MS 减速曲线(R34 1200 = focused_frame_animator_duration_ms,R37 起 600),
    // 失焦 150 ms AccelerateDecelerate(card_unfocus)。缩放、描边、柔光三者共用这一份 spec。
    val motionSpec = if (focused) {
        tween<Float>(
            durationMillis = GtvLayout.FOCUS_SCALE_IN_MS,
            // R30:这次焦点变化带着行位移 → 等位移走到约 80% 再开始放大。
            delayMillis = if (afterShift) GtvLayout.FOCUS_AFTER_SHIFT_DELAY_MS else 0,
            easing = Theme.AppFocusScaleInEasing,
        )
    } else {
        tween(durationMillis = GtvLayout.FOCUS_FADE_OUT_MS, easing = Theme.AppFocusEasing)
    }
    val scale by animateFloatAsState(
        targetValue = if (focused) GtvLayout.APP_FOCUS_SCALE else 1f,
        animationSpec = motionSpec,
        label = "gtvAppFocusScale",
    )
    val ringAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = motionSpec,
        label = "gtvAppFocusRingAlpha",
    )
    this
        .drawBehind {
            if (ringAlpha > 0f) {
                val growX = size.width * (scale - 1f) / 2f
                val growY = size.height * (scale - 1f) / 2f
                val gap = GtvLayout.APP_FOCUS_GAP.dp.toPx()
                val stroke = GtvLayout.APP_FOCUS_STROKE.dp.toPx()
                val outX = growX + gap + stroke / 2f
                val outY = growY + gap + stroke / 2f
                // R31:描边与缩放后的卡片同心——半径 = corner × scale + gap + stroke/2,不再把
                // growX/growY(卡片变大的量,不是描边离卡边的距离)混进半径(见 focusRingRadius 的 KDoc)。
                val r = GtvLayout.focusRingRadius(corner.toPx(), scale, gap, stroke)
                // R28 柔光:几何与描边同源(outX/outY 里已经含了当前动画中的 scale,所以柔光
                // 跟着卡片一起长大),透明度与描边共用同一个 ringAlpha —— 同一份 motionSpec
                // (R34/R37:进焦 FOCUS_SCALE_IN_MS 减速 / 失焦 150 ms),柔光是焦点处理的一部分,不另起时长。
                // 描边走中心线,外缘在 outX + stroke/2 处;柔光各圈的圆角 = 描边外缘半径 + 该圈偏移,
                // 同样同心;先画柔光、描边盖在上面。
                drawFocusGlow(
                    edgeX = outX + stroke / 2f,
                    edgeY = outY + stroke / 2f,
                    cornerAtEdge = r + stroke / 2f,
                    color = accentColor,
                    alpha = ringAlpha,
                )
                drawRoundRect(
                    color = accentColor.copy(alpha = accentColor.alpha * ringAlpha),
                    topLeft = Offset(-outX, -outY),
                    size = Size(size.width + 2 * outX, size.height + 2 * outY),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = stroke),
                )
            }
            if (moving) {
                // 整枝审查 A(2026-09-22):外扩必须**跟着缩放后的边缘走**,与上面聚焦描边同一算法
                // (growX/growY + 固定 dp),不能再是固定的布局框外 FOCUS_OUTSET——搬运中焦点恒在
                // 被搬的卡上,scale 恒为 APP_FOCUS_SCALE,当时的 MEDIUM 卡横向外扩 153×0.05=7.65dp 已经
                // 大于原来描边外缘的 6dp,左右整条被缩放后的卡片盖住,纵向也只露 1.5dp——这条描边
                // 在它唯一该出现的场景里几乎不可见。现在描边中心线在缩放后边缘外 FOCUS_OUTSET 处,
                // 正好贴在聚焦描边(外缘 = 缩放后边缘 + APP_FOCUS_GAP + APP_FOCUS_STROKE = 4dp)
                // 的外侧、不重叠。未聚焦时 scale = 1,退化为原来的固定几何。
                val growX = size.width * (scale - 1f) / 2f
                val growY = size.height * (scale - 1f) / 2f
                val outset = GtvLayout.FOCUS_OUTSET.dp.toPx()
                val w = GtvLayout.FOCUS_STROKE.dp.toPx()
                val outX = growX + outset
                val outY = growY + outset
                // R31:同一条同心不变量——缩放后卡片的圆角 corner × scale,加上这条描边中心线
                // 离缩放后边缘的距离 outset。
                val r = corner.toPx() * scale + outset
                drawRoundRect(
                    color = movingColor,
                    topLeft = Offset(-outX, -outY),
                    size = Size(size.width + 2 * outX, size.height + 2 * outY),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = w),
                )
            }
        }
        .graphicsLayer(scaleX = scale, scaleY = scale)
}
