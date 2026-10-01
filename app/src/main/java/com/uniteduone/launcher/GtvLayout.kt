package com.uniteduone.launcher

/** 卡片大小三档(B5-a:三个固定尺寸,不再是「每行几张」)。 */
enum class GtvCardSize { SMALL, MEDIUM, LARGE }

/**
 * gtv 线的全部几何,单位一律 dp(Float),**不含任何 Compose 类型**,便于 JVM 单测。
 * 数值出处:docs/research/2026-09-20-google-tv-launcherx-measurements.md(实测 launcherx 1.0.976298245)。
 * 与 [HomeLayout] 同形并存:main 线仍读 HomeLayout,本线只读这里。
 */
object GtvLayout {
    const val CONTENT_KEYLINE = 58f
    const val CARD_GAP = 20f
    const val HERO_HEIGHT = 192f
    const val TOP_BAR_TOP = 34f
    const val TOP_BAR_HEIGHT = 36f
    /** **owner 反馈 Round 6(2026-09-21)拆分**:这个常量原来同时喂给药丸按钮的触控/焦点框**和**
     *  里面绘制的图标本身(`TopBarIconButton` 曾经对 `IconButton` 和内层 `Icon` 用同一个
     *  `.size()`)——32 dp 的出处是研究文档 §2 的 a11y bounds「屏保图标 (656,76)-(720,140)」
     *  「快捷设置图标 (736,76)-(800,140)」,量的都是 64×64 px = 32×32 dp 的**可点击范围**,
     *  不是里面那个图形的墨迹大小。Round 5 的 sweep 表「顶栏图标 | 32dp | 32dp(不变)」一行
     *  拿这个 32dp 去对照 Google 的 32dp,两边比的都是框、不是图,核对「一致」比较的是错误的量,
     *  图形本身的大小从没被量过——这就是本轮 owner 反馈「齿轮和屏保按钮显得过大」的根因。
     *
     *  现在这个常量**只管触控/焦点框**(`IconButton` 的 `.size()`,决定可点击/可聚焦范围与
     *  药丸内的居中留白),图形本身画多大改由 [TOP_BAR_GEAR_GLYPH](R89 起还有 [TOP_BAR_APPS_GLYPH]/[TOP_BAR_INPUTS_GLYPH];原来的屏保那颗随按钮删掉)
     *  分别决定——**不要把这两件事重新合并成一个常量**。**32 dp 这个数字本身不变**(两个图标的
     *  a11y bounds 都是 64×64px,来自目标版本 1.0.976298245 本身的实测,不是旧版臆测,继续
     *  可信);药丸轨道高 [TOP_BAR_HEIGHT](36dp)、图标框 32dp,上下各留 2dp,owner 对轨道高度
     *  和这部分留白都满意,改图形大小不牵动这个框。 */
    const val TOP_BAR_ICON_BOX = 32f
    /** 设置(齿轮)图标的**图形绘制大小**(`TopBarIconButton` 内层 `Icon` 的 `.size()`),
     *  区别于上面 [TOP_BAR_ICON_BOX] 那个触控框——两者本轮(Round 6)起才第一次分开。
     *
     *  **Google 的量法**(`docs/screenshots/gtv/01-home-default.jpg`,1920×1080,density 2.0,
     *  齿轮图标框 a11y bounds (736,76)-(800,140)):齿轮图标本身带一枚红色提醒角标(数字「1」,
     *  贴在右上角),角标不是图标常态的一部分,直接量整块墨迹会把角标也框进去——**必须先把
     *  角标从图标本体分离出来,再量图标本体**。分离依据:角标满足 `r>130 且 r−g>35 且
     *  r−b>30`(红色通道显著高出绿蓝通道),图标本体是灰阶(三通道接近),色度上截然不同,
     *  逐像素判色即可分离,两者在图上不重叠(可视化存档见报告「Round 6」的 `gear-mask-viz.png`,
     *  绿色是图标本体、蓝色是角标)。阈值取药丸底色均值(≈44.5)与图标核心亮度峰值(≈249.9)的
     *  中点(≈147.2)——量边缘的标准做法,在 90–150 整个阈值区间内包围盒完全稳定不变(32×36px)。
     *  量得图标本体(**不含角标**)= 32×36px = **16×18 dp**。
     *
     *  **这个数字与任务原始表格给的「22.5×23dp」不同,以这次测量为准**:反向验证——若不排除
     *  角标、直接量整块墨迹(角标+图标本体),量出来是 43×45px = 21.5×22.5dp,与原表的
     *  22.5×23dp 几乎完全吻合,说明原表大概率是把角标一起量了进去,这里改正,不是分歧。
     *
     *  **框到图的换算**:Compose 的 `Icon.size()` 只吃一个正方形值,Google 的图标本体本身也不是
     *  正方形(16×18,arrow 部分让它比宽略高),取不到一个正方形尺寸能同时精确复现两边。
     *  `Icons.Filled.Settings` 这颗 Material 矢量图标自带内边距,不是边到边取满整个 `.size()`
     *  方框——Round 5 sweep 表已经量过改动前(单一 `TOP_BAR_ICON` 常量 = 32dp 时)这颗图标实际
     *  墨迹是 25×26dp,即墨迹只占框的 78–81%(25/32、26/32)。按这个内在比例反推:要让墨迹落在
     *  16×18dp 附近,框要取 16/0.78≈20.5 到 18/0.81≈22.2dp 之间,按最小二乘折中 ≈21.4dp,取
     *  **21.5 dp**。这是按比例换算的估算值,**已装机复核**:同一套连通域 + 中点阈值量法,量出
     *  改动后实际墨迹 33×35px = 16.5×17.5dp(bg 均值≈41.6,峰值≈201.6,中点≈121.6),与目标
     *  16×18dp 相差都在 0.5dp(1px)以内,落在测量噪声范围,不再二次迭代——复核过程见报告
     *  「Round 6」`gear-mask-viz.png`。 */
    const val TOP_BAR_GEAR_GLYPH = 21.5f
    // ~~TOP_BAR_SCREENSAVER_GLYPH = 17.5f~~(屏保 Slideshow 图标,Round 6 量 Google 相册图标墨迹 13×13 dp 反推):
    // **R89(2026-09-27 Gordon)屏保按钮从顶栏拿掉**(挪到「设置 → 屏保 → 立即开始屏保」),常量随之删掉。
    /**
     * **R89** 顶栏「应用」胶囊(`Icons.Filled.Apps`,3×3 方块)的图形绘制大小。量法同 [TOP_BAR_GEAR_GLYPH]:
     * 目标墨迹 ≈ Google 顶栏同一排图标(相册 13×13、齿轮 16×18 dp)之间,取约 13.5 dp;这颗矢量图的墨迹占框
     * 16/24 = 0.67(方块从 4 到 20),13.5 / 0.67 ≈ 20 dp。
     */
    const val TOP_BAR_APPS_GLYPH = 20f
    /**
     * **R89** 顶栏「输入源」胶囊(`Icons.Filled.Input`,方框 + 进入箭头)的图形绘制大小。这颗矢量图横向墨迹占框
     * 约 20/24 = 0.83、纵向 18/24 = 0.75,取 18 dp:墨迹约 15×13.5 dp,与「应用」那颗视觉上等重。
     */
    const val TOP_BAR_INPUTS_GLYPH = 18f
    const val TOP_BAR_ICON_GAP = 8f
    /** Fix round 1(R15,2026-09-20):**15 dp 是 Google 用 Latin 文本(`Top picks for you`)量出来的
     *  值,对中文不成立,不要改回去。** 原始推导是 a11y (116,600)-(302,630) = 30 px = 15 dp——但那是
     *  英文单行的紧凑行高;CJK 字形在同样字号下需要明显更高的行盒,只能装机测,不能按字号比例算
     *  (这条原则本身不随下面的字号变动而变)。
     *
     *  **Ruling R25(2026-09-21,owner 真机反馈 Round 5 推翻 R19):行标题字号从 16sp 改回 14sp。**
     *  owner 拿真机与 Google TV 并排对比,判定这条产品线的字号/图标普遍偏大,行标题是第一个点名的
     *  例子。R19 曾以「14sp 会让 CJK 字形更小、还会把 rowPitch 再往下推一次」为由维持 16sp、
     *  只改规格表(spec §2.3 footnote);owner 的取舍标准是「像素级贴近 Google」优先于这份
     *  legibility 顾虑,裁决改回 14sp——不是没考虑过 R19 的顾虑,是明确认为不成立。
     *
     *  **20 dp 是在 14sp 下重新装机实测的行盒,不是把 23dp 按 14/16 的字号比例折算出来的**——
     *  折算会漏算字体在不同字号下 hinting/行距表的非线性变化,R15 踩过这个坑,这里同样只能重测。
     *  测法与 R15 相同(`onTextLayout` 探针,`lineHeight` 留 `Unspecified` 让 Compose 按实际渲染
     *  字体——中文回落到系统 CJK 字体——算自然行高,不是量一个已经被 `.height()` 约束夹过的值):
     *  `unitedu-gtv` AVD(1920×1080/320dpi,density 2.0)上「视频」「直播」「更多应用」三个标题在
     *  14sp 下全部量出 `naturalHeightPx = 40`(= 20 dp),零方差(第四行「音乐与播客」唯一的应用
     *  `com.google.android.tvrecommendations` 没有 LAUNCHER 活动、被过滤成空行,没有渲染,不影响
     *  另外三个的置信度——`cmd package query-activities` 核实过,不是猜测)。
     *
     *  字号本身是 14sp(R25);这个行盒高只对那个字号成立,两者要一起改一起测。
     *
     *  **Ruling R48(2026-09-22)起首页不再画行标题**(见 [ROW_ICON_SIZE]),`rowPitch` 不再含这一项;
     *  这个常量只剩编辑页的行标题行盒(`EditScreen`,13sp)与 `RowIcon` 固定尺寸那个重载(编辑页、
     *  首次引导用)的图标框在读。
     *  R48 同时删掉了只有首页行标题在用的 `ROW_TITLE_TEXT`(14sp)、`ROW_TITLE_TO_CARD`(12.5dp)、
     *  `ROW_TITLE_ICON_GAP`(8dp)、`ROW_TITLE_FOCUS_SCALE`(1.78)、`ROW_TITLE_CAP_EM`(0.711)。 */
    const val ROW_TITLE_LINE = 20f
    /**
     * **Ruling R48(2026-09-22)**:首页行图标的方框边长(dp)。owner 原话:「可不可以不要行标题?……
     * 把行图标留着:1. 图标和应用卡片放在同一行内,纵向居中,放在最左边;2. 不再以标题的形式呈现,
     * 只要一个图标,不要文字。」owner 看过效果图后选 **A2**(`docs/screenshots/mockups/A2-no-title-icon-in-margin.jpg`):
     * 图标画在左侧边距里,水平中心 x = [CONTENT_KEYLINE] / 2(29 dp),纵向与本行卡片中心对齐。
     *
     * 尺寸来源:效果图里的图标是 R48 之前行标题图标(方框 [ROW_TITLE_LINE] = 20 dp,Material 24 格
     * 视口)的 **×1.3** = 26 dp 方框。核对:效果图(1920 px 宽,2 px/dp)里胶片图标墨迹高 ≈ 40 px =
     * 20 dp;`Icons.Filled.Theaters` 墨迹占视口 18/24,26 dp 方框的墨迹 = 19.5 dp,与效果图一致。
     * 焦点行近白、其余行灰(R46 的两色,[GtvTokens.RowIconFocused]/[GtvTokens.RowIconIdle]),
     * 走 `Theme.rowIconFocusSpec()` 那根弹簧过渡,**不缩放**。
     *
     * **Ruling R50(2026-09-23)**:26 → **22** dp。owner 原话:「每一行的行图标:位置没问题(每行左侧纵向居中),
     * 但偏大了,改小一点点。」位置规则(水平中心 = [CONTENT_KEYLINE] / 2、与卡片纵向居中)不变,只改方框边长;
     * 墨迹随之 19.5 → 16.5 dp。
     */
    const val ROW_ICON_SIZE = 22f
    /** Fix round 1(R15,2026-09-20):**125.5 dp 同样是 Google 用 Latin 量出来的行距,对中文标题不
     *  成立,不要试图凑回这个数。** 沿用它会把 `ROW_GAP` 推到约 −10dp(23+12.5+14+86.06−125.5≈−10),
     *  而 `rowVerticalPad`(上下各 7dp)是货真价实要留给外扩焦点描边的空间——那么负的 `ROW_GAP` 会让
     *  行 N 的焦点描边直接压在行 N+1 的标题字形上。**改为放弃凑 125.5,`ROW_GAP` 改取一个有真实、非负
     *  余量的值。** 这里的「余量」定义是:`ROW_GAP` 的值本身,就是「行 N 的卡片行(含它自己下方预留
     *  给描边的 7dp)结束」到「行 N+1 标题行盒开始」之间的物理间距——因为 `CategoryRow` 的标题行盒
     *  紧贴在 `Column` 顶部、前面不再有别的 padding。取 **8 dp**:比行内自己的 `ROW_TITLE_TO_CARD`
     *  (12.5dp)略窄,让一行标题在视觉上更贴近它自己那一行的卡片、不与上一行混淆,同时明显大于
     *  `FOCUS_STROKE`(2dp),保证聚焦描边与下一行标题之间总有可见的黑色间隙,不会贴到一起。
     *  装机验证见 `task-9b-report.md` Fix round 1 一节。
     *
     *  **R48 起**首页没有行标题,「行 N+1 标题行盒开始」变成「行 N+1 卡片行(含它上方 7dp 描边留白)
     *  开始」:相邻两行卡片布局框之间 = 7 + 8 + 7 = 22 dp(卡片标题开着时再加标题高),焦点卡的纵向
     *  溢出(当时 LARGE 9.4 dp;R59 起最大的是 LARGE 8.30,R121 起 LARGE 8.22)仍小于 7 + 8,不碰下一行的留白带(`GtvLayoutTest`)。R48 当时数值不变。
     *
     *  **Ruling R51(2026-09-23)**:8 → **40** dp。owner 原话:「行与行之间的间隔太短了,不同行的应用都挤在
     *  一块,上下移动的时候看不出动效。」中档无标题 [rowPitch] 108.0625 → **140.0625**,回到 R48 之前
     *  (140.5625)的行距量级——R48 收掉的是行标题带,owner 要回来的是行与行之间的空。相邻两行卡片布局框
     *  之间 = 7 + 40 + 7 = 54 dp(卡片标题开着时再加标题高)。 */
    const val ROW_GAP = 40f
    const val CARD_CORNER = 8f
    /** **内容卡**(content card,16:9 无边界推荐流那种)专用的焦点描边几何:画在布局框**外**
     *  FOCUS_OUTSET 处,粗 FOCUS_STROKE,不缩放。owner 反馈 Round 4(2026-09-21)裁定「我们的
     *  应用行是 app,不是 content——Google 对 app tile 的处理是缩放,不是这种静态外扩描边」之后,
     *  gtv 线首页已经没有任何卡片走这套画法(`AppCard`/`AddCard`/`MissingCard` 全部改用
     *  [APP_FOCUS_SCALE] 一族的 app 处理,见 `GtvFocusStroke.gtvAppFocusFrame`)——但这两个常量
     *  **没有变成死代码**,仍在两处活着:①`RowIconPicker` 的行图标格子(小网格图标,不是
     *  app,继续用这套画法,理由见该文件);②`gtvAppFocusFrame` 里 `moving`(首页原地移动态 /
     *  编辑页搬运态)分支——被搬的那张卡的高亮描边是 UnitedU 自己的交互反馈,Google 没有对应物;
     *  它借用这两个常量当外扩量与线宽,但几何**跟着缩放后的边缘走**(整枝审查 A,2026-09-22:
     *  固定在布局框外 5dp 会被缩放后的卡片整条盖住)。`rowVerticalPad`
     *  (`Theme.gtvCardMetrics`)的留白量也仍然读这两个常量,`rowPitch` 因此不受本轮影响
     *  (owner 反馈 Round 4 明确要求不改 rowPitch)。 */
    const val FOCUS_STROKE = 2f
    const val FOCUS_OUTSET = 5f
    /**
     * 卡片标题行盒顶边离卡片布局框底边的距离(dp)——**随档位变,= [appFocusOverflow](卡高)**。
     *
     * **ui-pending #9(2026-09-23)**:此前是常量 `CARD_TITLE_GAP` = 4 dp。标题不随卡片缩放(`AppCard` 的标题是
     * Card 的兄弟节点),聚焦描边外缘却在卡底下方 [appFocusOverflow](R126 描边 1.5 起 SMALL 5.92 / MEDIUM 6.93 / LARGE 7.72;
     * R121 时 SMALL 6.42 / MEDIUM 7.43 / LARGE 8.22;
     * R59 时 SMALL 7.43 / MEDIUM 7.85 / LARGE 8.30,再之前 MEDIUM 8.30 / LARGE 9.40)处;
     * 14sp 标题的 cap 顶在行盒顶下约 3.5 dp(模拟器实测:三档都是卡底下 7.5 dp),于是描边压在标题字顶上——
     * 中档压 1 px、大档压 4 px、小档正好贴住(docs/screenshots/minor-3-title-stroke-before-*.jpg)。
     * 现在行盒顶让到描边外缘:cap 顶在描边外缘下约 3.5 dp(7 px)。代价:开标题时 [titleHeight] / [rowPitch]
     * 各档多 3.4–5.4 dp;R52「静止只露一行」与标题高无关(行 1 卡顶 = H + 22 − overflow),不受影响。
     */
    fun cardTitleGap(size: GtvCardSize): Float = appFocusOverflow(cardHeight(size))
    /** Fix 1(owner 反馈 R2,2026-09-20,R15 的同一种病第二次发作):**16 dp 是 `Theme.gtvCardMetrics`
     *  把卡片标题字号从 Google 的 Latin 量测抬到 14sp 时沿用的旧容器高,对 CJK 不成立,不要改回去。**
     *  真机(owner 的「云视听极光」「银河奇异果」)上逐行像素扫描:标题墨迹在 y=470→493 之间从
     *  47→46→**0**、没有渐变收尾——硬裁,不是渐变到底。
     *
     *  装机实测(同 R15 的 `onTextLayout` 探针,`lineHeight` 留 `Unspecified` 让 Compose 按实际渲染
     *  字体——中文回落系统 CJK 字体——算自然行高;探针必须放在**没有** `.height()` 约束的节点上,
     *  放在 `AppCard` 原来那个已经带 `.height(metrics.titleLine)` 的 `BasicText` 上量到的只是约束值
     *  本身,不是自然行高——这是第一次量到 32px≈16dp「零裁切」假象的原因,松开约束后才量到真值):
     *  `unitedu-gtv` AVD(1920×1080/320dpi,density 2.0)上「云视听极光」「银河奇异果」两个标题在
     *  14sp 下全部量出 `naturalHeightPx = 40`(= 20 dp,零方差,连量两轮一致);对照组「Play Store」
     *  (Latin)同字号量出 35px——与 R15 同一个结论:CJK 在同样字号下需要比 Latin 更高的行盒。
     *  现改为 20 dp,`AppCard` 的卡片标题 `TextStyle` 也显式把 `lineHeight` 设成
     *  `metrics.titleLine`(不是这个常量本身——`AppCard.kt` 对 main 线 / gtv 线都通用,只读
     *  `CardMetrics`,不直接读 `GtvLayout`,详见该文件),消除「容器高度」与「文字行高」分别改动
     *  导致再次漂移的可能。当时 `titleHeight(showTitles = true)` 与依赖它的 `rowPitch(size, true)` 跟着
     *  这次 16 → 20 变了 4dp——这是显示标题时行间距该有的样子,不是需要另外吸收的偏差(现签名是
     *  `titleHeight(size, showTitles)`,ui-pending #9 起标题间距改读 [cardTitleGap](size),见 `GtvLayoutTest`)。装机复核见
     *  `.superpowers/sdd/2026-09-20-gtv-line/owner-feedback-fix-report.md`「Round 2 · Fix 1」与
     *  `docs/screenshots/gtv-owner-fix1-card-title-{clipped,fixed}-*.png` 的裁切前后对照。 */
    const val CARD_TITLE_LINE = 20f

    /**
     * **Ruling R49(2026-09-22)**:卡片淡化。owner 原话:「卡片颜色太鲜艳了……让卡片在整个 UI 中不那么
     * 鲜艳、突兀,变淡一些,不破坏整个壁纸的感觉。」owner 看过效果图后选 **B4**
     * (`docs/screenshots/mockups/B4-cards-desat70-dim25.jpg`,本体,不是「焦点卡恢复原色」变体):
     * `gray = (R+G+B)/3`;`out = (gray + (c − gray) × 0.30) × 0.75`——饱和度保留 30%、亮度乘 0.75。
     * 灰度是**三通道等权平均**(照效果图,不是 Rec.709 亮度,以免观感漂移)。矩阵见 [cardFadeMatrix]。
     *
     * 作用范围:所有卡片**内容**(banner、图标、边缘色底、无 banner 的纯色底、卡片标题),含焦点卡;
     * 不作用于聚焦描边、柔光、搬运态描边(主题色,淡化会让焦点不清楚)。首页、编辑页、长按菜单左侧
     * banner 淡化;「添加应用」列表与换卡片图的图片选择器不淡化(那是找东西用的,要认得清)。
     */
    const val CARD_FADE_SATURATION = 0.30f
    /** R49:见 [CARD_FADE_SATURATION]。 */
    const val CARD_FADE_BRIGHTNESS = 0.75f

    /** R49:B4 算法的 4×5 颜色矩阵(Compose `ColorMatrix` 的行优先布局,alpha 不变、无偏移)。
     *  `out_c = b × (s × c + (1 − s) × (R+G+B)/3)`,展开后对角 `b(s + (1−s)/3)`、非对角 `b(1−s)/3`。 */
    fun cardFadeMatrix(
        saturation: Float = CARD_FADE_SATURATION,
        brightness: Float = CARD_FADE_BRIGHTNESS,
    ): FloatArray {
        val off = brightness * (1f - saturation) / 3f
        val diag = brightness * saturation + off
        return floatArrayOf(
            diag, off, off, 0f, 0f,
            off, diag, off, 0f, 0f,
            off, off, diag, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /**
     * **Ruling R86**:卡片整体(图 + 底色)的不透明度。[restAlpha] = 设置里的「卡片不透明度」(0.4–1.0),
     * [focusProgress] = 焦点动画进度(0 = 未聚焦,1 = 完全聚焦;即 `gtvAppFocusFrame` 里与描边 / 柔光
     * 共用同一份 motionSpec 的 `ringAlpha`)。线性插值:未聚焦 = 设置值,聚焦 = 1,进焦 / 失焦随同一条
     * 曲线过渡,不瞬切。进度越界夹到 [0, 1];restAlpha ≥ 1 恒为 1(缺省值,与加这一项之前逐像素相同)。
     */
    fun cardFocusAlpha(restAlpha: Float, focusProgress: Float): Float {
        val rest = restAlpha.coerceIn(0f, 1f)
        return rest + (1f - rest) * focusProgress.coerceIn(0f, 1f)
    }

    /** 长按 / 齿轮菜单(GearMenu,Task 8):药丸尺寸,实测报告 §7,268×55 dp,全圆角(h/2)。 */
    const val MENU_ITEM_WIDTH = 268f
    /**
     * 胶囊聚焦时放大到的倍数(R141,2026-09-30「视觉高级感」)。Google 实测(`docs/screenshots/gtv/16-app-longpress-menu.png`,
     * 1080p 逐行扫):聚焦药丸 564 × 112 px、未聚焦 536 × 104 px,即 282 × 56 dp 对 268 × 52 dp,宽高都约 1.05×;
     * 聚焦那颗外缘 2–6 px 还有一圈比底色暗 3 级的影子([MENU_ITEM_FOCUS_SHADOW_DP])。此前我们只换填色、不放大。
     */
    const val MENU_ITEM_FOCUS_SCALE = 1.05f
    /** 聚焦胶囊的投影高度(dp,R141):Google 那圈影子很淡(外缘比底色暗 3/255),画在深底上几乎看不见,在亮一点的底上才显。 */
    const val MENU_ITEM_FOCUS_SHADOW_DP = 3f
    /** 卡片边缘亮边(R141):宽 1 dp、白 16%(卡片淡化后约 11%),画在卡片圆角里面。只为勾出深色横幅的轮廓,亮色卡上看不见。 */
    const val CARD_HAIRLINE_DP = 1f
    /**
     * 设置外壳预览框里那份缩小的首页的投影(dp,R143):氛围底(R142)上预览框像一扇浮起来的小窗。随缩放进度 z 从 0 长到这个值。
     */
    const val PREVIEW_SHADOW_DP = 18f
    const val CARD_HAIRLINE_ALPHA = 0.16f
    const val MENU_ITEM_HEIGHT = 55f
    /** 药丸之间的纵向间距。报告没给这一项,按参考图 docs/screenshots/gtv/16-app-longpress-menu.png
     *  像素量测(两药丸间隙 y 355→384 px,该图 1:1 对应 320dpi 实机,/2 得 dp)≈ 14.5 dp,取整 16。 */
    const val MENU_ITEM_GAP = 16f
    /** R161:空桌面那一行里,提示文字、箭头、「立即前往」小胶囊之间的间距。 */
    const val EMPTY_HOME_ARROW_GAP = 12f
    /** R161:那一行里箭头的大小。 */
    const val EMPTY_HOME_ARROW_SIZE = 18f
    /** R161:宽度随文字的小胶囊(空桌面「立即前往」)的高度、左右内边距、字号;填色 / 放大同 MenuPill。
     *  Gordon 电视上看过 40 dp / 15 sp 的一版:「高度太高了,字号也偏大了」→ 28 dp / 12 sp(Type.CAPTION)。 */
    const val COMPACT_PILL_HEIGHT = 28f
    const val COMPACT_PILL_PADDING_H = 14f
    const val COMPACT_PILL_TEXT = Type.CAPTION
    /** 小胶囊文字的视觉中心在基线上方多少个字号(汉字字面中心与西文大写 / x 高的中间都在 0.35–0.40 之间);按电视 / 模拟器截图量定。 */
    const val COMPACT_PILL_INK_CENTER_EM = 0.37f
    /** R161:空桌面那一行离屏幕底边的距离(Gordon:「文案本来就应该出现在底部」,焦点线那个高度不算底部)。 */
    const val EMPTY_HOME_BOTTOM_MARGIN = 40f
    /** 左侧 banner 与应用名之间的间距。同一张参考图量测(banner 底 y 469 → 名字顶 ≈508 px)≈ 19.5 dp,取整 20。 */
    const val MENU_BANNER_NAME_GAP = 20f
    /** 药丸左右内边距(spec §2.3「菜单项 16sp」附近)。Fix 4 从字面量搬进来,数值不变。 */
    const val MENU_ITEM_PADDING_H = 24f
    /** 菜单项文字字号(spec §2.3)。 */
    const val MENU_ITEM_TEXT = 16f
    /** Ruling R17(终审 2026-09-20):齿轮菜单(不含长按卡片菜单)恢复第二行说明文字,字号比标题小一档、
     *  颜色更淡(见 GearMenu.MenuPill 的 showHint 分支),视觉上明确从属于标题。 */
    const val MENU_ITEM_HINT_TEXT = 12f
    /** 标题行与说明行之间的间距。 */
    const val MENU_ITEM_HINT_GAP = 2f
    /** 两行文字时药丸的上下内边距(单行时数学上不改变居中位置,见 GearMenu.MenuPill 的推导注释)。 */
    const val MENU_ITEM_PADDING_V = 10f
    /** 齿轮菜单左半 banner 应用名的字号 + 字距(MenuBanner)。
     *
     *  **owner 反馈 Round 5(2026-09-21)16→12**:这是「菜单」这张表里唯一一个 sweep 时才发现、
     *  不在任务点名清单上的项——点名的只有「菜单项文字」(`MENU_ITEM_TEXT`,已核对与 Google 一致,
     *  见下方),但 banner 应用名和菜单项文字同属长按菜单这一屏,量出来的差距足够大、
     *  参照物也足够干净,一并改了并在报告里如实标出「非点名项」。
     *
     *  参考图 `docs/screenshots/gtv/16-app-longpress-menu.png`(1920×1080,density 2.0)上量
     *  Google 的应用名字标「Live TV」:`L`(y 502→518)、`T`/`V`(y 501→518)三个 flat-top 字母
     *  一致收敛到 cap height ≈17px = 8.5dp,按 cap≈0.71em 换算 8.5/0.71≈12sp。Google 这里的
     *  banner 与我们同样宽绰(参考图目测是完整 16:9 banner,量级与我们的 `GtvCardSize.LARGE`
     *  相当),不是「小 banner 配小字」的比例假象。 */
    const val MENU_BANNER_NAME_TEXT = 12f
    const val MENU_BANNER_NAME_LETTER_SPACING = 1f
    /** 顶栏时钟 + 字标的字号(spec §2.3)。
     *
     *  **owner 反馈 Round 5(2026-09-21)改 20→16**:原 20sp 借用的是研究文档 §10「快捷设置时钟
     *  (`11:11 AM`)」那一行的 cap-height 反推值——但那是 Google 快捷设置面板里刻意放大的
     *  「大字」时钟(该文档原文明确写「`Sun, Sep 20` + `11:11 AM` **大字**」),不是常驻顶栏右上角
     *  那个小时钟,两者是同一个 app 里不同层级的两处时钟,没有理由同字号。装机取的参照物本身就错了。
     *
     *  重新在 `docs/screenshots/gtv/01-home-default.jpg`(1920×1080,density 2.0)上量常驻顶栏
     *  「10:48 | Google TV」这一行:避开圆形字母的 overshoot(圆形字形为了视觉等大会比 flat-top
     *  字形多凸出 1–3 px,这里的「T」「V」「M」都是 flat-top,「0」「G」是圆形,量出来的高度分别是
     *  23–24px 与 26–27px,印证了这个已知的排印现象——取更准的 flat-top 一组):
     *  `Google` 的「T」y=98→121(24px)、`TV` 的「V」同为 23–24px、`Move`(长按菜单参照图
     *  `16-app-longpress-menu.png`同一方法量的「M」)y=288→311(24px)。三组独立测量一致收敛到
     *  cap height ≈ 24px = 12dp,按 §10 的 cap≈0.71em 换算 12/0.71≈16.9sp,取整 **16sp**——
     *  与长按菜单项字号（同一张参考图量出的「M」）巧合地是同一个数字,但这是两次独立测量各自收敛
     *  到的结果,不是复用同一个数。 */
    const val TOP_BAR_CLOCK_TEXT = 16f
    /** R146:焦点所在那颗的名字写在它正下方,这是药丸组下缘到名字的间距(dp)。字号 `Type.body`,比时钟小一档
     *  (R133 时名字在药丸组右边一颗小胶囊里:间距 10 dp、内边距 16 dp、字号同时钟)。 */
    const val TOP_BAR_LABEL_GAP = 8f

    /**
     * **Ruling R41(2026-09-22,owner 真机反馈 Round 10)**:UnitedU 设置页(当时的两栏 `SettingsScreen`,R69 起是外壳 `SettingsShell` 每一层的页名)左上角
     * 大标题(「UnitedU 设置」/「UnitedU Settings」)的字号——**32 sp**。owner 原话:「左侧中文'设置'
     * 过于小了,不需要很大。」此前是 22 sp(硬写在 SettingsScreen 里,没有常量)。
     *
     * 出处:`docs/research/2026-09-20-gtv-vs-unitedu-comparison.md` §C「二级页大标题 ≈ 32 sp」,
     * 由 `2026-09-20-google-tv-launcherx-measurements.md` 里 Apps 页大标题「Your apps」的 cap height
     * 46 px = 23 dp 按 cap ≈ 0.71 em 反推(23 / 0.71 ≈ 32.4)。同页其它字号(分组 15、行标签 15、
     * 说明 12/13、提示 11)不动。
     */
    const val SETTINGS_TITLE_TEXT = 32f

    /**
     * **Ruling R109(2026-09-27 Gordon:设置类页面的字「小一号」,先看效果)**:我们自己的设置类页面——设置外壳每一层、
     * 关于页、默认桌面 / 恢复默认确认页、顶栏打开的「应用」页与「输入源」页——**所有文字**按「基准字号 + 这一步」画。
     * 基准就是 R109 之前的字号(胶囊 16 / 说明 12 / 左栏路径 16 / 大标题 32 / 左栏说明 14 / 关于页 15–11 / 默认桌面卡 15、11 /
     * 应用页卡片名 14 / 「系统工具」18),要改只改这一个数:「再小一点」改 -2f,「改回去」改 0f。
     * 胶囊高度、行高常量、卡片尺寸等**布局尺寸不跟着变**;文字的行距(lineHeight)是排版的一部分,同样加这一步。
     * 长按卡片菜单、编辑页菜单、首次引导、首页不在范围内(它们读的仍是基准常量,与这一步无关)。
     */
    const val SETTINGS_TYPE_STEP = -1f

    /** 设置类页面的字号 = 基准 + [SETTINGS_TYPE_STEP](R109)。 */
    fun settingsSp(base: Float): Float = base + SETTINGS_TYPE_STEP

    /**
     * 胶囊右端 › 的字号相对标签字号的比例。R71 起是「标签 + 4」(16 + 4 = 20);R109 改成按比例,
     * 字号整体缩放时 › 跟着等比缩。基准下 16 × 1.25 = 20,与原值逐位相同。
     */
    const val CHEVRON_SCALE = 1.25f

    /**
     * **Ruling R108(2026-09-27 Gordon:设置类页面打开淡入、关闭淡出)**:打开 200 ms、关闭 150 ms;设置外壳里层与层之间
     * (进下一层 / 返回)150 ms 交叉淡化;曲线一律 `FastOutSlowInEasing`,没有位移。范围与 R109 相同(不含安卓原生设置页)。
     * 实现与焦点处理见 `SettingsFade.kt`。
     */
    const val SETTINGS_FADE_IN_MS = 500  // R113(2026-09-28):淡入 + 轻微放大,对称缓入缓出;R112 为 450
    const val SETTINGS_FADE_OUT_MS = 400  // R113;R112 为 350
    const val SETTINGS_LAYER_FADE_MS = 300  // R112 起;R113 层间另加轻微放大
    /** R147:首页 ↔ 编辑页交叉淡化的时长(ms)——编辑页淡入 / 首页残影淡出 / 编辑页残影淡出;与打开编辑页时预览框放大到整屏
     *  (外壳关掉那一轮的 z,[SETTINGS_FADE_OUT_MS])同一个数,放大走完时编辑页也正好完全不透明。 */
    const val EDIT_SWAP_MS = SETTINGS_FADE_OUT_MS
    /** R113:设置类页面打开时从这个比例放大到 1(关闭反过来)。只作用于页面内容,不作用于全屏底色与首页 / 预览那一层。 */
    const val SETTINGS_ENTER_SCALE = 0.96f
    /** R113:外壳层与层切换(交叉淡化)时的起始比例,比整页打开更轻。 */
    const val SETTINGS_LAYER_SCALE = 0.98f

    /**
     * Fix 3(owner 反馈 R2,2026-09-20):「目前 UI 交互没有任何动画……焦点一下子跳到这、一下子跳到
     * 那」。根因是 decision B1 去掉聚焦缩放之后,`gtvFocusStroke` 的描边与 `GearMenu`/`GtvTopBar`
     * 的填色焦点都是瞬间切换(布尔值直接门控 `drawBehind`/`background`,零动画),丢了缩放曾经
     * 提供的唯一连续性提示。
     *
     * **owner 反馈 Round 4(2026-09-21)替换了这两个数字的来源**:Round 2 写的 150/120ms 是
     * "未测量占位值"(见本文件历史版本/report),真值已从旧版 launcherx APK
     * (1.0.595789376,资源名未混淆)读出——`animator/card_focus`、`animator/card_unfocus`
     * 两个 `ObjectAnimator` 的 `duration` 都引用 `@integer/default_focused_animation_duration_ms
     * = 150`,进焦出焦**对称同为 150ms**,不是 150/120 的非对称值。**这不再是占位值,不要再加
     * "未测量"字样**——真的又量到更精确的数字才改,不要凭直觉往回调。
     *
     * 用在:`gtvFocusStroke` 覆盖的内容卡式描边(`RowIconPicker`、`gtvAppFocusFrame` 的
     * `moving` 分支)——`card_focus`/`_unfocus` 是 Google 对「app 卡片」的量测,这两处不是严格
     * 意义上的 app 卡片聚焦,只是为了整条线的焦点淡入淡出手感统一而借用同一个数字,不是又找到了
     * 各自的独立测量,如实记录不夸大。`GearMenu.MenuPill` 同理复用(见该文件调用点的注释)。
     * 曲线用 `Theme.AppFocusEasing`(同一份 APK 引用的 `AccelerateDecelerateInterpolator`,
     * 不是 `Theme.MotionEasing` 那条给「位置动画」用的减速曲线)。
     *
     * **Ruling R34(2026-09-22,owner 真机反馈 Round 9)起,app 卡片的进焦放大不再读
     * [FOCUS_FADE_IN_MS]**——那条 150 ms 的 `card_focus` 是旧路径,真机上看到的慢放大走的是另一条
     * animator,见 [FOCUS_SCALE_IN_MS];失焦缩回仍读 [FOCUS_FADE_OUT_MS](`card_unfocus` 150),
     * **进焦 / 失焦不对称**。[FOCUS_FADE_IN_MS] 本身继续给上面列的几处内容卡描边 / 菜单药丸用。
     */
    const val FOCUS_FADE_IN_MS = 150
    const val FOCUS_FADE_OUT_MS = 150

    /**
     * R79(2026-09-24 owner 手感):**应用卡片**失焦的缩回 + 描边 + 柔光淡出时长。Google 原值是 `card_unfocus` 150 ms
     * ([FOCUS_FADE_OUT_MS]),owner 实测:新卡慢慢放大、柔光慢慢淡入,离开的那张却「突然、快速地回归原位」,
     * 一快一慢是不平滑感的来源。改成 400 ms,仍走 [Theme.AppFocusEasing](先加速后减速),进焦 [FOCUS_SCALE_IN_MS] 不动。
     * [FOCUS_FADE_OUT_MS] 仍给内容卡描边、菜单胶囊用(那些没有放大,150 ms 看不出突兀)。
     */
    const val APP_FOCUS_OUT_MS = 400

    /** R80:非焦点行的行图标 = 主题色 accent 乘这个透明度(焦点行 1.0)。 */
    const val ROW_ICON_IDLE_ALPHA = 0.55f

    /**
     * **Ruling R34(2026-09-22,owner 真机反馈 Round 9)**:app 卡片**进焦**放大(缩放 + 描边 +
     * 柔光一起淡入)的时长——**1200 ms**,出处目标版资源表 `focused_frame_animator_duration_ms
     * = 1200`(`docs/research/launcherx-1.0.976298245-named-resources.md`)。owner 原话「走到停下来
     * 的时候,焦点所在的位置再慢慢放大」;模拟器 pts 实测放大在位移到 ~80% 时起步、**1.2 s 量级
     * 才到顶**,曲线是减速型(前段快后段慢)——与 `card_focus` 那条 150 ms 对称路径不是同一个
     * animator,不要把两者合并。
     *
     * **失焦仍是 150 ms**([FOCUS_FADE_OUT_MS],`card_unfocus`),**不对称**:进焦 1200 / 失焦 150。
     * 录像里确认失焦缩回是瞬间量级,不随进焦一起变慢。
     *
     * 曲线用 [Theme.AppFocusScaleInEasing](`CubicBezierEasing(0, 0, 0.2, 1)`,Material 标准减速),
     * **不再用 AccelerateDecelerate**——那是 `card_focus` 150 ms 旧路径的平台默认插值器,慢放大
     * 的减速形态对不上它「两头慢中间快」的对称曲线。失焦保持 AccelerateDecelerate 150。
     *
     * 终值倍率仍是 [APP_FOCUS_SCALE] 1.10(静态 PNG 实测;录像里因运动模糊面积被低估读到 1.135,
     * 不可信,不要改倍率)。柔光与描边的淡入与缩放同一份 spec(一起慢慢显出来),失焦一起 150 收。
     *
     * **Ruling R37(2026-09-22,owner 真机反馈 Round 10)**:**1200 → 600 ms**。Google 原值 ~1200
     * (上面那份资源表),owner 真机原话「焦点刚移到卡片上的时候,要么停顿时间过长,要么慢慢变大得太慢,
     * 矫枉过正」——按他手感缩短一半,曲线仍是减速型([Theme.AppFocusScaleInEasing]),失焦 150 不变
     * (仍不对称:600 / 150)。这是手感裁定,不是量出来的;与 [FOCUS_AFTER_SHIFT_DELAY_MS] 的 160 → 80
     * 同一轮改,两者一起决定「刚落焦那一下」的迟滞感。
     */
    const val FOCUS_SCALE_IN_MS = 600

    /**
     * **Ruling R30(2026-09-22,owner 真机反馈 Round 8)**:焦点放大(缩放 + 描边 + 柔光的淡入)
     * 在**行位移之后**才开始——owner 原话「移动到某一个焦点之后,那个焦点会自然而然地放大」;
     * 我们此前是位移与放大同时起步。只有**这次焦点变化触发了行位移**(纵向切行,或横向
     * `rowShiftX` 的目标值变了)时才加这段延迟;同一行内左右移、行没滑时不加,立即放大——
     * Google 横向不滑时也是立即的。失焦(缩回)永远不加延迟。
     *
     * **拟合值,不是资源原值**:按 [BROWSE_SPRING_STIFFNESS] 那根弹簧走到约 80% 的时刻估的——
     * 临界阻尼 `1 − (1 + ωt)e^(−ωt) = 0.8` 解得 ωt ≈ 2.99。R30 时 stiffness 700(ω ≈ 26.5)
     * → 113 ms,取 120;**R33/R34(2026-09-22)stiffness 改 350(ω ≈ 18.7)后重算 → 160 ms**。
     * Google 模拟器 pts 实测放大起步在按键后 ~200 ms(那时 Google 自己的位移已到 ~90%,不是 80%);
     * 这里按「我们这根弹簧到 80%」的定义取 160,与 Google 的 200 差 40 ms(约两帧),
     * owner 真机觉得放大起得太早就往 200 调——它是 ω 的函数,调 stiffness 时要跟着重估。
     *
     * 用在 `GtvFocusStroke.gtvAppFocusFrame` 的 `afterShift` 分支(`tween` 的 `delayMillis`);
     * 「这次触发了位移」的判定在 `HomeScreen.CategoryRow` 的焦点回调里做,不动任何焦点效果 /
     * 看门狗 / `FocusRequester` 链(铁律 3–7)。
     *
     * **Ruling R37(2026-09-22,owner 真机反馈 Round 10)**:**160 → 80 ms**。Google 原值 ~200
     * (模拟器 pts 实测放大起步在按键后 ~200 ms),owner 真机原话「焦点刚移到卡片上的时候,要么停顿
     * 时间过长,要么慢慢变大得太慢,矫枉过正」——按他手感缩短。**80 ms 不再对应「弹簧到 80%」**:
     * 按 [BROWSE_SPRING_STIFFNESS] 350(ω ≈ 18.7)算,80 ms 时位移只到 **44%**(R38 改 220 后
     * ω ≈ 14.8,**33%**),放大在位移进行到三分之一时就起步、两者大部分时间是叠着走的;这是 owner 要的手感,不再追 Google 的「先滑完再放大」。
     * 上面「ω 的函数」那句从此只是历史推导:改 stiffness 时把这里的百分比重算写进 KDoc 即可,数值本身
     * 按手感定。
     */
    const val FOCUS_AFTER_SHIFT_DELAY_MS = 80

    /**
     * **Ruling R27(2026-09-21,owner 真机反馈 Round 7;R29 已取代,见下)**:浏览位移(**四处**:首页行 x/y 平移、
     * 编辑页纵向平移与行内横向平移——最后一处 2026-09-22 整枝审查 B 才补上,此前漏在默认 spring)
     * 的时长,配 [Theme.BrowseEasing] 一起用,取代此前的 `Theme.MotionInMs`(300ms,等同
     * Material 的 `material_motion_duration_long_1`,一个与 browse 无关的通用值)。
     *
     * **证据强度必须如实说清,这个数字没有像曲线那样拿到逐字证据**:
     * - 曲线有逐字证据——`anim/tv_easing_browse` 是一份具名资源,直接读出四个控制点。
     * - 时长没有。同一份 APK 里**唯一**以 browse 命名的时长是 `integer
     *   /lb_browse_rows_anim_duration = 250`(leanback 的行动画),设计 token 里的
     *   `gtvm3_sys_motion_duration_medium1` 同样是 250;但 `tv_easing_browse` **只被代码引用、
     *   没有任何 XML 引用它**,所以拿不到「这条曲线配这个时长」的对应关系,
     *   250 是这两个同源候选值收敛到的同一个数,不是从一条 animator 上读下来的。
     * - 模拟器逐帧实测**不作为证据**:`unitedu-gtv` 这台 AVD 的帧率在 30–40 fps 之间漂移,
     *   250ms 与 300ms 的差别落在单帧间隔量级以内,分辨不出来,不要拿它当佐证。
     *
     * 真的又量到更可靠的数字才改;不要凭手感往回调到 300。
     *
     * **Ruling R29(2026-09-22,owner 真机反馈 Round 8)起,行位移不再读这个常量**——改走
     * [BROWSE_SPRING_STIFFNESS] 的临界阻尼弹簧(见那里)。保留这个常量只为记录 R27 的历史与
     * 证据链,以及将来可能出现的其它 browse 场景(tween 形态的);gtv 线目前没有任何调用点。
     */
    const val BROWSE_SHIFT_MS = 250

    /**
     * **Ruling R29(2026-09-22,owner 真机反馈 Round 8)**:浏览位移(四处:首页行 x/y 平移、
     * 编辑页纵向平移与行内横向平移)改用**临界阻尼弹簧**,取代 R27 的
     * `tween(BROWSE_SHIFT_MS, BrowseEasing)`。
     *
     * **为什么 R27 用错了曲线**:owner 真机原话「Google 是带着一种加快又减慢的阻尼感去移动;
     * 我们的是一下一下的、每次都很快」。对 Google TV 纵向位移逐帧实测的归一化轨迹(12 帧):
     * 0.18, 0.28, 0.47, 0.72, 0.84, 0.91, 0.94, 0.97, 0.98, 0.995, 0.997, 1.0——**先加速后减速**。
     * `tv_easing_browse`(0.18, 1, 0.22, 1)是纯硬减速(1/4 进度已走 85%),对不上这条轨迹;
     * 拟合结果是临界阻尼弹簧误差 6%(最佳)。原因在于 Google 的行滚动走的是 RecyclerView 的
     * smooth scroller,根本不经过那条插值器资源(它不吃 `animator_duration_scale` 已实证)——
     * R27 拿到的曲线是真的,只是不是行位移用的那条。
     *
     * **R29 的 700 是按「12 帧 ≈ 200–300 ms」折算的拟合值**(帧率不稳、没有时间戳),
     * 区间 550–1200。**Ruling R33(2026-09-22,owner 真机反馈 Round 9)改按真实时间戳重估**:
     * 模拟器上 `screenrecord` 一次「Top picks → Your apps」下键,`ffprobe` 取每帧 pts,整页位移
     * ≈ 382 dp 的归一化进度是——按键后 **120 ms 79%、213 ms 93%、285 ms 98%、~430 ms 完全
     * 停稳**。700 那根弹簧(ω ≈ 26.5)250 ms 就停了,比 Google 硬一截,owner 真机原话
     * 「慢慢往上走」对不上。临界阻尼 `1 − (1 + ωt)e^(−ωt)` 对这四个点拟合:后三点(93/98/停稳)
     * 要 ω ≈ 17–20(stiffness 290–420),第一点(120 ms 79%)单独看要 ω ≈ 24(≈ 580)——
     * 单根临界阻尼弹簧压不住前段又拖住尾巴,以停稳时刻为准取 stiffness ≈ **300–400**,
     * 取 **350**(ω ≈ 18.7 rad/s:120 ms 66%、213 ms 91%、285 ms 97%、430 ms 99.7%——前段比
     * Google 慢约 13 个百分点,尾巴一致)。仍是拟合值,不是资源原值;Compose 的
     * `Spring.StiffnessMediumLow` 是 400。阻尼比取 `Spring.DampingRatioNoBouncy`(1.0,临界
     * 阻尼,不过冲)。**owner 真机手感是最终判据**,允许在 300–400 之间按手感调;超出这个
     * 区间就不再是那条 pts 轨迹了。[FOCUS_AFTER_SHIFT_DELAY_MS] 是 ω 的函数,改这里要一起重算。
     *
     * 曲线本身由 Compose 的 `spring()` 生成(`Theme.browseShiftSpec`),`animateDpAsState` 走
     * spring 需要 `visibilityThreshold`,取 0.5 dp(半个 dp 以内视为到位,一像素以下肉眼不可辨)。
     *
     * **Ruling R38(2026-09-22,owner 真机反馈 Round 10)**:**350 → 220**。owner 真机原话「整体向上
     * 滚动时稍微慢一点,让速度带一点阻尼感」。R33 的 350 是照 Google pts 轨迹(~430 ms 停稳)拟合的,
     * 这次是 owner 手感值、**主动偏离 Google**,出了 R33 写的 300–400 拟合区间——从此不再钉 Google 的
     * 430 ms,改钉「停稳 500–700 ms」(`GtvMotionTest`)。220(ω ≈ 14.8 rad/s)的临界阻尼轨迹:
     * 80 ms 33%、213 ms 82%、430 ms 98.8%、**到 99.7%(≈ 1 dp / 322 dp)约 540 ms**。阻尼比仍是
     * 临界(`DampingRatioNoBouncy`),不过冲——「阻尼感」来自更长的减速尾巴,不是欠阻尼的回弹。
     * 配套 [FOCUS_AFTER_SHIFT_DELAY_MS] 80 ms 在这根弹簧上对应位移 **33%**(R37 时按 350 算是 44%)。
     */
    const val BROWSE_SPRING_STIFFNESS = 220f
    /** [BROWSE_SPRING_STIFFNESS] 弹簧的收敛阈值(dp),见那里。 */
    const val BROWSE_SPRING_THRESHOLD_DP = 0.5f

    /**
     * **vertical-motion 研究(2026-09-27,分支 `try/vertical-motion`,未经 owner 裁定)**:首页**上下换行**
     * 整页位移(连同行图标的焦点色,两者同起同止)用哪条曲线。左右位移、编辑页不受影响,仍读
     * [Theme.browseShiftSpec]。测量数据、录像与推荐见 `docs/design/vertical-motion/README.md`。
     *
     * - [SPRING_220]:现状(R38),与左右同一根临界阻尼弹簧。t = 0 加速度最大,
     *   80 ms 已走 33%、峰速 ≈ 715 dp/s 出现在 67 ms(一个中档行距 131 dp)。
     * - [TWEEN_450]:方案 A,`tween(450, FastOutSlowInEasing)`。起步柔和(80 ms 10%),到 95% 用时与现状
     *   几乎相同(327 vs 320 ms),峰速略高(≈ 796 dp/s,在 136 ms)。
     * - [TWEEN_550_SOFT]:方案 B,`tween(550, CubicBezier(0.35, 0, 0.15, 1))`。起步更慢,95% 在 390 ms。
     * - [SPRING_110]:方案 C,刚度减半的临界阻尼弹簧。起步加速度减半,但尾巴拖到 ~760 ms 才停稳。
     *
     * **tween 的已知代价**:`animateDpAsState` 在动画中途换目标(连按两下)时,tween 从当前位置以
     * **零速度**重新起步,弹簧会带着当前速度接着走——A/B 连按时有一下「刹停再起步」。
     */
    enum class HomeVerticalMotion { SPRING_220, TWEEN_450, TWEEN_550_SOFT, SPRING_110 }

    /** 首页上下换行用哪条曲线,见 [HomeVerticalMotion]。**R96 起默认 = 方案 A(TWEEN_450)**,原现状为 SPRING_220;改这一行即可切换方案
     *  (推荐 [HomeVerticalMotion.TWEEN_450],[WALLPAPER_DIM_PER_ROW] 随之打开)。 */
    val HOME_VERTICAL_MOTION: HomeVerticalMotion = HomeVerticalMotion.TWEEN_450

    /** 方案 A 的时长(ms),曲线 `FastOutSlowInEasing`。 */
    const val VMOTION_A_MS = 450
    /** 方案 B 的时长(ms),曲线 [Theme.HomeVerticalSoftEasing]。 */
    const val VMOTION_B_MS = 550
    /** 方案 C 的刚度(临界阻尼,ω ≈ 10.5 rad/s)。 */
    const val VMOTION_C_STIFFNESS = 110f

    /**
     * **vertical-motion 研究(2026-09-27)发现的「不同步」**:壁纸压暗按 [WALLPAPER_FADE_OVER_DP](192)
     * 线性、到头夹住,而每换一行走 [rowPitch](中档无标题 131)。行 0 → 1 时两者同步;**行 1 → 2** 时
     * 壁纸在位移走到 47% 就暗到底(弹簧上 ≈ 110 ms),卡片还要再走 400 ms;**行 2 → 1** 反过来,壁纸
     * 前 53% 一动不动、在位移尾巴里才亮起来。打开后改按「行进度」逐行线性插值([wallpaperAlphaPerRow]),
     * 每次换行壁纸的变化都与这次位移同进度;各行静止时的 alpha 与现状逐值相同。
     *
     * **跟着 [HOME_VERTICAL_MOTION] 走**:现状([HomeVerticalMotion.SPRING_220])时关 = 行为不变;选了任何新曲线就一并
     * 打开——所以切到推荐方案只改 [HOME_VERTICAL_MOTION] 那一行。想单独对比时把这里写成字面量。
     */
    val WALLPAPER_DIM_PER_ROW: Boolean = HOME_VERTICAL_MOTION != HomeVerticalMotion.SPRING_220

    /**
     * owner 反馈 Round 4:Google 对 **app tile**(不是 content card)的聚焦处理——放大,不是外扩
     * 静态描边。
     *
     * **1.10 的出处(整枝审查 C,2026-09-22)**:目标版本(1.0.976298245)的资源表里
     * fraction `0x7f0a0081` = **1.099976**(`docs/research/launcherx-1.0.976298245-named-resources.md`
     * 「fraction · 新增、对不上名字」一节,位于 `spotlight_shadow_alpha_min … topic_banner_focused_scale`
     * 之间,是目标版新增、旧版没有的条目),这是 Google 自己写在资源里的值;同表里
     * `*_card_focused_scale` 一族(`card_focused_scale`/`recommended_app_card_focused_scale`/
     * `kid_app_card_focused_scale` 等十余条)也都是 1.099976。此前的 1.105 是 controller 在同一
     * 版本上装机像素反推(聚焦态应用图块 152 → 168 px),像素量测的分辨率是 ±1px ≈ ±0.007 倍,
     * 1.10(→167.2px)与 1.105 都落在这个误差带内,取资源里的原值、不取反推值。
     *
     * 旧版本(1.0.595789376)`fraction/app_card_focused_scale = 1.14` 在目标版里同名同值仍在,
     * 但它对不上像素实测(1.14 → 173px,与 168 差 5px,远超误差带),说明目标版的 app 行不走
     * 这条资源;1.14 只作为旧版记录,不要把它当成现在该用的数字。 */
    const val APP_FOCUS_SCALE = 1.10f
    /** app tile 聚焦描边与**缩放后**边缘之间的间隙(dp)。controller 在目标版本(1.0.976298245)
     *  上装机像素量测得出,不是命名资源(Google 没有给这段间隙单独取名字)。 */
    const val APP_FOCUS_GAP = 2f
    /** app tile 聚焦描边本身的宽度(dp)。`dimen/card_focused_frame_outer_stroke_width = 2dp`——
     *  与内容卡的 [FOCUS_STROKE] 数值恰好相同,但这是两个分别命名的 Google 资源(content card
     *  与 app tile 各自的边框宽度只是刚好都是 2dp),不合并成一个常量,避免以后其中一个改了
     *  而误伤另一个。**R126(2026-09-29 Gordon「描边线条的厚度改小一点」)起 1.5dp**,比 Google 的 2dp 细一档;
     *  间隙 [APP_FOCUS_GAP] 不动,描边外缘随之内收 0.5dp([appFocusOverflow] 跟着少 0.5)。 */
    const val APP_FOCUS_STROKE = 1.5f

    /** app tile 聚焦时的视觉溢出量(缩放增量的一半 + 描边间隙 + 描边本身),给定卡片某一边的
     *  未缩放长度。纯几何,不含 Compose 类型,方便单测验证「聚焦时会不会碰到下一行卡片的描边留白带」
     *  (R48 前是下一行标题)「行尾右缘会不会被屏幕边缘裁描边」这类不变量(owner 反馈 Round 4 §5)。
     *
     *  **[APP_FOCUS_GLOW_DP](R28 的柔光)刻意不在这条公式里,不要"顺手补全"**:这个函数是
     *  **布局约定**(`rowShiftX` 拿它决定行要不要左移、`GtvLayoutTest` 拿它验证不碰下一行卡片的描边留白带),
     *  而柔光是纯视觉溢出,画在 `drawBehind` 里、不参与测量。把 [APP_FOCUS_GLOW_DP](60dp)柔光
     *  加进来会让每行凭空多出这么多间距预算,破坏已经与 Google 对齐的纵向节奏。详见 [APP_FOCUS_GLOW_DP]。 */
    fun appFocusOverflow(dimension: Float): Float =
        dimension * (APP_FOCUS_SCALE - 1f) / 2f + APP_FOCUS_GAP + APP_FOCUS_STROKE

    /**
     * **Ruling R31(2026-09-22,owner 真机反馈 Round 8)**:聚焦描边**中心线**的圆角半径——与缩放后
     * 的卡片**同心**:`corner × scale + gap + stroke / 2`。owner 原话「描边形状和卡片本身并不是
     * 等比例的,显得有点膈应;外围的框应该和卡片完全等比例,外围的框和卡片边缘完全不交接」。
     *
     * **此前的算式错在哪**:`r = corner + (outX + outY) / 2`,其中 `outX`/`outY` 含缩放长出的
     * `growX`/`growY`(当时的 MEDIUM 卡横向 7.65dp、纵向 4.3dp)——那是卡片**变大**的量,不是描边离卡
     * 边的距离,却被当成外扩距离加进了半径:算出来约 19dp,而缩放后的卡片圆角只有 8 × 1.10 =
     * 8.8dp,描边比卡片圆得多,四角处描边离卡边的距离比直边处宽出一截。Google 的资源里
     * `*_card_corner_correction`(1.05–1.27)一族是「卡片圆角 × 系数」的写法,是等比例思路。
     *
     * **同心几何的不变量**:平行于圆角矩形边缘、向外偏移 `d` 的曲线,圆角半径恰好是
     * `原半径 + d`。缩放后卡片的圆角是 `corner × scale`;描边中心线离缩放后边缘 `gap + stroke/2`;
     * 所以半径就是这三项之和。柔光每一圈同样按「描边外缘半径 + 该圈偏移」取,
     * `moving` 描边按「`corner × scale` + 它自己的外扩量」取——同一条不变量,三处共用。
     * 纯函数、单位无关(调用方传 px 得 px),`GtvLayoutTest` 钉 8×1.10+2+0.75 = 11.55(R126 描边 1.5;此前 +1 = 11.8)。
     */
    fun focusRingRadius(corner: Float, scale: Float, gap: Float, stroke: Float): Float =
        corner * scale + gap + stroke / 2f

    /**
     * **Ruling R28(2026-09-21,owner 真机反馈 Round 7)**:焦点柔光向外铺开的总距离(dp),
     * 从**描边外缘**起算。此前 gtv 线只画了一圈 2dp 描边、柔光一点没有——这正是 owner
     * 「从沙发上完全感觉不到动效」的主因:2dp 细环在 150ms 内淡入,那个距离上肉眼捕捉不到;
     * 大面积柔光才捕捉得到。
     *
     * **模拟器实测剖面**(`unitedu-gtv` AVD,1920×1080 @ density 2.0;Google 的 76dp 圆形 app
     * 磁贴,聚焦后 84dp,描边在半径 43dp 处、外缘约 44dp)。亮度单位 /255,是"高出未聚焦背景"
     * 的增量,`d` = 超出描边外缘的距离(dp):
     *
     * | d(dp) |  2  |  5  |  8  | 11 | 14 | 17 | 20 | 23 | 26 | 29 |
     * |---|---|---|---|---|---|---|---|---|---|---|
     * | 高出 /255 | 44 | 36.5 | 33 | 30 | 26.6 | 23.6 | 20.7 | 18 | 15.9 | 13.7 |
     *
     * 上方与左方两个方向的剖面几乎重合(不是文字或邻居干扰),近似指数衰减,半衰期
     * ≈[APP_FOCUS_GLOW_HALF_LIFE_DP]。实测表最远只到 d=29dp,**那里仍未归零**(+13.7/255)。
     *
     * **[APP_FOCUS_GLOW_DATA_DP] 与本常量为什么是两个数(2026-09-21 实测修正)**:最初两者合一、
     * 都取 30dp,理由写的是"末圈 alpha 只剩 0.048,是渐隐到看不见"。**在模拟器上实拍打脸了**:
     * 沿焦点卡左缘向外扫,x=36 处亮度 24.5、到 x=28 直接掉回底色 14——一道约 **10/255 的硬边**,
     * 肉眼看上去柔光像一块圆角底板,不像光。0.048 的 alpha 在近黑背景上不是"看不见",是"看得见"。
     * 所以拆成两段:**0→[APP_FOCUS_GLOW_DATA_DP] 是实测数据区,指数形状一点不动**;
     * 之后到本常量为止是收尾段,乘一条 smoothstep 把残留平滑收到 0。Google 的剖面在我们量到的
     * 最远处仍在平滑下降、没有这道边,所以"收到 0"比"切断"更接近它。
     *
     * **判据留给后人**:凡是"衰减到某个小值就截断"的画法,都要回答一句——截断处的残留在**目标
     * 背景**上是否还看得见。近黑背景上 10/255 的台阶是看得见的;别拿 alpha 小当作看不见的证据。
     *
     * **这是视觉溢出,不是布局量**:柔光在 `GtvFocusStroke` 的 `drawBehind` 里画,不参与任何
     * 测量;[appFocusOverflow]、`rowPitch`、`Theme.gtvCardMetrics.rowVerticalPad` 都**不加**
     * 这一项(加进去会把行间距撑开 60dp)。代价是柔光会盖到相邻卡片与上一行卡片的底部(R48 前是
     * 上一行标题区;卡片标题关着时,上下两行卡片之间隔 22dp(R48)、R51 起 54dp)——
     * 这是 Google 那张剖面本身就有的样子(它的柔光同样铺出数据区的 30dp 之外,行距比这还紧),不是 bug。
     */
    const val APP_FOCUS_GLOW_DP = 60f

    /** 实测数据区的外边界(dp):0→30dp 这一段的 alpha 完全由实测剖面的指数拟合决定,不施加任何
     *  收尾衰减——实测表覆盖到 d=29,这里取整到 30。30dp 之外没有实测数据,由收尾段接管,见
     *  [APP_FOCUS_GLOW_DP] 的 KDoc。 */
    const val APP_FOCUS_GLOW_DATA_DP = 30f

    /**
     * 柔光紧贴描边外缘处(d = 0)的**目标亮度增量**(0–1,相对满量程)。**48/255 ≈ 0.188**,
     * 由实测剖面外推得到。
     *
     * **它是亮度增量,不是 alpha —— 这是本常量最容易被误用的地方。** alpha 只是混合系数:
     * 画布的 srcOver 在**伽马编码空间**直接线性混合,所以
     * `亮度增量 ≈ alpha × (前景亮度 − 背景亮度)`。只有前景是纯白(255)、背景是纯黑(0)时
     * 才有 `alpha ≈ 增量/255`。B6 裁定"画法照 Google、颜色用用户主题色",前景是 accent 色
     * (默认那套亮度约 200,9 个预设与"跟随壁纸主色"差异更大),**直接把 0.188 当 alpha 用,
     * 画出来只有 Google 的约 70%;主题色越暗差得越多。** 所以真正的 alpha 由
     * [focusGlowAlphaFor] 在绘制时按 accent 的实际亮度反推。
     *
     * **为什么是外推值**:实测表最靠内的采样点是 d=2 而非 d=0(d<2 被描边本身占着,量不到),
     * 那一格是 +44/255。半衰期 16dp、只往回推 2dp,外推幅度 9%,落在这条指数拟合自身的残差内
     * (全表最大偏差 6%),所以 48 比 44 更接近 d=0 的真值。
     *
     * **一次被推翻的保守取值(2026-09-21,值得记着)**:本常量最初取 0.17f 并直接当 alpha 用,
     * 理由是"宁可比 Google 淡 8%,也不要画得比实测更亮"。这个取舍方向错了——柔光存在的**唯一**
     * 目的就是解决 owner 报的「从沙发上完全感觉不到动效」,而当时同时叠了三层都朝"更看不见"推的
     * 保守:①峰值按 d=2 而不是 d=0 取,低 9%;②把增量当 alpha 用,又低 22%;③外缘硬截断(那个
     * 是反方向的 bug,见 [APP_FOCUS_GLOW_DP])。三层叠起来实测只有 Google 的约 68%。
     * **判据:取舍的方向要对着这个特性想解决的问题,不能只看"哪个数更小更安全"。**
     */
    const val APP_FOCUS_GLOW_PEAK_INCREMENT = 0.188f

    /**
     * [focusGlowAlphaFor] 反推 alpha 时假定的背景亮度(0–1)。混合式是
     * `增量 = alpha × (前景 − 背景)`,**不是** `alpha × 前景`——把背景当 0 会系统性少给
     * `背景/(前景−背景)`,在默认主题下实测正好少 10%(见下)。
     *
     * 取 **0.07**(≈18/255):Google 那份剖面的底色是 14/255,我们这台模拟器上实测 17.7/255,
     * 都是"深色桌面上压暗过的壁纸"这一档。真实背景是用户的壁纸、绘制时不可知,所以只能取一个
     * 代表值;取偏小一点(而不是按最亮的壁纸取)是因为这一项估高了会让亮壁纸上过曝,估低了只是
     * 回到原来那个已知的 10% 欠量。
     *
     * **实测定标(2026-09-21,模拟器,accent = (208,188,255) → 伽马域亮度 197/255)**:
     * 补上本项后逐点对比 Google 的剖面,d = 2/5/8/11/14/17/20/23/26/29 dp 十档的比值
     * 依次是 0.98/1.05/0.99/0.99/0.98/0.96/0.95/0.97/0.93/0.97,**平均 0.98**。
     * 不补这一项时 d=2 处只有 0.89 —— 与本项预测的「少给 bg/(fg−bg) ≈ 10%」对得上。
     *
     * **量这个剖面必须用「换焦点拍两张、逐点相减」,不能减一个远处采到的底色**:柔光铺到
     * [APP_FOCUS_GLOW_DP] = 60dp,在 1920 宽的屏上足以覆盖到屏幕边缘,**随手取的"底色"
     * 本身就在柔光里**。我第一次就是这么量的,得到"只有 Google 的 79%、而且比值从 0.89
     * 一路滑到 0.67"的假象,差点据此又去改半衰期。Google 那份剖面当初也是用相减法量的
     * (聚焦帧 − 未聚焦帧),两边口径本来就该一致。
     */
    const val APP_FOCUS_GLOW_ASSUMED_BG = 0.07f

    /** [focusGlowAlphaFor] 反推 alpha 时的上限。主题色很暗时按增量反推会要到很大的 alpha,
     *  在亮壁纸上会过曝;0.5 是"够亮但不至于糊成一片"的闸。 */
    const val APP_FOCUS_GLOW_MAX_ALPHA = 0.5f

    /** 柔光的指数衰减半衰期(dp)。实测表两端定标:44/13.7 = 3.212 倍、跨 27dp →
     *  27/log2(3.212) = **16.04dp**,取 16。全表最大偏差在 d=5 处约 6%,其余各点 ≤3%。 */
    const val APP_FOCUS_GLOW_HALF_LIFE_DP = 16f

    /** 柔光的画法粒度:一圈同心圆角矩形描边的宽度(dp),同时也是圈与圈之间的步距——
     *  相邻两圈首尾相接、不重叠。[APP_FOCUS_GLOW_DP] / 这个值 = 30 圈(数据区 15 圈 + 收尾段 15 圈)。
     *
     *  **为什么是画一串圆环而不是 `BlurMaskFilter`**:后者在硬件加速画布上行为不稳(各家 GPU
     *  实现不一致、Compose 还要另开 layer),而柔光每帧都跟着 `scale` 变几何;一串按指数衰减
     *  的细环是纯几何,逐帧重算的代价是 `APP_FOCUS_GLOW_DP / APP_FOCUS_GLOW_RING_DP` = 30 次 `drawRoundRect`
     *  (只有聚焦中的那张卡才画,而且收尾段那十几圈 alpha 已经接近 0),
     *  而且圆角半径随外扩距离同步增大这件事直接套用同心几何([focusRingRadius]:描边外缘半径
     *  + 该圈的偏移,R31 起;此前的 `r = corner + (outX + outY) / 2` 把缩放长出的量也算进了半径,
     *  描边比卡片圆得多)。
     *  相邻圈之间 alpha 只差 2^(−2/16) ≈ 8.3%,在这个 alpha 量级上看不出分层。 */
    const val APP_FOCUS_GLOW_RING_DP = 2f

    /**
     * 柔光在距描边外缘 [distanceDp] 处的**目标亮度增量**(换成 alpha 走 [focusGlowAlphaFor]),分两段:
     * - `d ≤ `[APP_FOCUS_GLOW_DATA_DP]:纯指数 `PEAK × 2^(−d / HALF_LIFE)`,**实测数据区,
     *   不加任何修饰**——`GtvGlowTest` 就是拿这一段逐点对着实测剖面验的。
     *   `PEAK` = [APP_FOCUS_GLOW_PEAK_INCREMENT]。
     * - 之后到 [APP_FOCUS_GLOW_DP]:同一条指数再乘一条 smoothstep(`1 − (3t² − 2t³)`,
     *   `t` 是在收尾段里的归一化位置),把残留平滑收到 0。smoothstep 在两端一阶导都是 0,
     *   所以数据区边界上既不跳值也不折角,d=[APP_FOCUS_GLOW_DATA_DP] 处取值与纯指数完全相同。
     *
     * 超出 [APP_FOCUS_GLOW_DP] 归零(圆环画到那里为止)。纯函数、不含 Compose 类型。
     */
    fun focusGlowIncrement(distanceDp: Float): Float {
        if (distanceDp < 0f || distanceDp > APP_FOCUS_GLOW_DP) return 0f
        val exp = APP_FOCUS_GLOW_PEAK_INCREMENT *
            Math.pow(2.0, -(distanceDp / APP_FOCUS_GLOW_HALF_LIFE_DP).toDouble()).toFloat()
        if (distanceDp <= APP_FOCUS_GLOW_DATA_DP) return exp
        val t = (distanceDp - APP_FOCUS_GLOW_DATA_DP) / (APP_FOCUS_GLOW_DP - APP_FOCUS_GLOW_DATA_DP)
        return exp * (1f - (3f * t * t - 2f * t * t * t))
    }

    /**
     * 把 [focusGlowIncrement] 的目标亮度增量换算成实际要用的 alpha:
     * `alpha = 增量 / (前景亮度 − 背景亮度)`(背景 = [APP_FOCUS_GLOW_ASSUMED_BG]),再夹到
     * [APP_FOCUS_GLOW_MAX_ALPHA]。
     *
     * [foregroundLuminance] 取 **伽马编码空间**的加权和 `0.2126R + 0.7152G + 0.0722B`
     * (R/G/B 是 0–1 的 sRGB 分量),**不是** Compose 的 `Color.luminance()`——后者会先线性化,
     * 而 Android 画布的 srcOver 混合本身就发生在伽马编码空间,实测剖面也是按编码值量的。
     * 三者必须同一套口径,换成线性化的会系统性偏小。
     *
     * 背景按 [APP_FOCUS_GLOW_ASSUMED_BG] 计入(不是当 0——当 0 会系统性少给约 10%,
     * 2026-09-21 在模拟器上实测定标过,见那个常量的 KDoc)。
     */
    fun focusGlowAlphaFor(distanceDp: Float, foregroundLuminance: Float): Float {
        val inc = focusGlowIncrement(distanceDp)
        if (inc <= 0f) return 0f
        val contrast = foregroundLuminance - APP_FOCUS_GLOW_ASSUMED_BG
        if (contrast <= 0.01f) return APP_FOCUS_GLOW_MAX_ALPHA
        return minOf(inc / contrast, APP_FOCUS_GLOW_MAX_ALPHA)
    }

    /** 顶栏图标按钮的填色淡入 / 淡出时长(owner 反馈 Round 4):`integer
     *  /top_nav_animation_duration_focus = 100`、`_unfocus = 200`,与 app 卡片的
     *  [FOCUS_FADE_IN_MS]/[FOCUS_FADE_OUT_MS] 是两组不同的 Google 资源,进出也不对称
     *  (先快进、后慢出),不要合并成一组常量。 */
    const val TOP_NAV_FADE_IN_MS = 100
    const val TOP_NAV_FADE_OUT_MS = 200

    /**
     * 三档卡宽(dp)。**Ruling R121(2026-09-28,Gordon 定)**:三档改为「一行正好完整显示 N 张」——
     * **大 5 张 150 / 中 6 张 122 / 小 8 张 86**(R59 是 153 / 137 / 122)。存盘的 `cardsPerRow` 5 / 6 / 8
     * 从此字面上就是「一行几张」,不迁移;默认档仍是中。
     *
     * **推导**(1920×1080 窗口 = 960 × 540 dp):「正好完整显示 N 张」取的是比「布局右缘 ≤ 960」更严的一条——
     * **聚焦第 N 张时 [rowShiftX] 不平移**(否则光标走到第 N 张整行就挪,等于这一行只「稳稳」放得下 N − 1 张)。
     * [rowShiftX] 的判据是 `58 + N·w + (N−1)·20 + appFocusOverflow(w) + 58 ≤ 960`,即
     * `N·w + (N−1)·20 + (0.05·w + 4) ≤ 844`(844 = 960 − 2 × [CONTENT_KEYLINE];行 `Row` 的 `padding(start)`
     * 就是 58,前面没有别的留白),解得 `w ≤ (840 − 20·(N−1)) / (N + 0.05)`:
     * N = 5 → 150.50,N = 6 → 122.31,N = 8 → 86.96,各取整数 **150 / 122 / 86**。
     * 核对(聚焦第 N 张的视觉右缘,上限 902):大 58 + 750 + 80 + 11.5 = 899.5、中 58 + 732 + 100 + 10.1 = 900.1、
     * 小 58 + 688 + 140 + 8.3 = 894.3;第 N + 1 张从 908 / 910 / 906 dp 起露出 52 / 150、50 / 122、54 / 86。
     * 以上按 R121 当时的 2 dp 描边推导;**R126 描边 1.5 后不重推卡宽**(小档理论上限变 87.02,刻意保持 86,
     * Gordon 电视上确认的是这三个数),三档聚焦第 N 张照样不平移(视觉右缘各内收 0.5)。
     * 中档 122 恰好是 R59 的小档,所以「所有应用页」(一行 6 张)R121 起改借中档,观感逐像素不变(见 `AppsPageLayout`)。
     * 长按菜单左侧的 banner 自 R59 起读自己的 [MENU_BANNER_WIDTH](192),不跟档位。
     */
    fun cardWidth(size: GtvCardSize): Float = when (size) {
        GtvCardSize.SMALL -> 86f
        GtvCardSize.MEDIUM -> 122f
        GtvCardSize.LARGE -> 150f
    }

    /**
     * 长按卡片菜单左侧 banner 的宽(dp),高按 16:9。参考图像素量测约 196×110 dp,取 192×108——这就是 R59 之前
     * 大档卡片的尺寸,当时 `GearMenu` 直接借 `cardWidth(LARGE)`;R59 把大档改成 153 之后单独立一个常量,
     * 菜单观感不随首页卡片档位变。
     */
    const val MENU_BANNER_WIDTH = 192f

    /**
     * **Ruling R83(2026-09-24)**:「添加应用」列表每项左边小卡片的宽(dp),高按 16:9 = 54 dp。
     * 取当时小档 [cardWidth] SMALL 122(R121 起 122 是中档)的约 0.8 倍,是独立常量、不跟档位:列表面板高 [PICKER_PANEL_MAX_HEIGHT] 480 dp 时,减去标题行,
     * 每项 = 卡高 54 + 上下各一个聚焦溢出 [appFocusOverflow](54) ≈ 6.7 dp(取整到 7)= 68 dp,
     * 一屏放得下约 6 项;用 122 整档时每项 83.5 dp、一屏只有 5 项,A95L 约 38 个候选要多翻近一屏。
     * 再小(< 90)横幅上的中文字标开始糊,认不出是哪个应用——卡片存在的意义就没了。
     */
    const val PICKER_CARD_WIDTH = 96f
    /** 「添加应用」面板内容区的宽(dp),沿用改版前的 460:卡 96 + 两侧聚焦溢出各 9 + 间距 16 之后,
     *  名字 + 「新」标还有约 330 dp(模拟器实测 520 时右半截整块空着)。 */
    const val PICKER_PANEL_WIDTH = 460f
    /** 「添加应用」面板的最大高(dp):屏高 540 dp 上下各留 30。 */
    const val PICKER_PANEL_MAX_HEIGHT = 480f
    /** 小卡片与右侧名字的间距(dp)。 */
    const val PICKER_CARD_NAME_GAP = 16f
    /** R135:「添加应用」整屏页右半那一列的宽度(dp)与上下内边距。300 居中 = 570–870,卡片左缘与别的页的胶囊左缘(586)大致对齐。 */
    const val PICKER_LIST_WIDTH = 300f
    const val PICKER_LIST_PAD_V = 36f

    fun cardHeight(size: GtvCardSize): Float = cardWidth(size) * 9f / 16f

    fun cardPitch(size: GtvCardSize): Float = cardWidth(size) + CARD_GAP

    /**
     * Ruling R20(终审 2026-09-20,owner 真机走查后推翻):**"焦点卡永远钉在左基准线,整行按
     * 索引 × pitch 平移"这条规则本身的测量没有错(实测 launcherx:沿 `Top picks for you` 行
     * 按右键 7 次,焦点卡左缘恒为 x=116px=58dp,见
     * `docs/research/2026-09-20-google-tv-launcherx-measurements.md` §8),错的是照搬它的前提——
     * **不要因为这个函数曾经就是这么写的,就把公式改回去。**
     *
     * Google 的内容行是无边界的推荐流(`Top picks for you`),行天然比屏幕宽,"焦点卡永远最左、
     * 右边永远还有更多"这个假设对它成立。我们的行是有限的应用列表,常见 5 张卡:当时的 MEDIUM 档
     * 5 张卡只占 845dp(5×153 + 4×20;R59 起 153 是 LARGE,R121 起没有这一档),这台机型屏宽 960dp,连左右两条 58dp 基准线一起量都
     * 刚好放得下——一整行本来就不需要移动。按 Google 规则从第一次按右键起就整行左移一个
     * pitch(173dp),会把第 1 张卡推出屏幕左侧,右边空出约 230dp 的死白,真机走查看到的就是
     * 这个样子。
     *
     * 现在的规则改回 pre-Task-7(commit 7abf015 之前,`git show 7abf015` 可见原型)的做法:
     * **焦点卡完全可见时行不动;只在焦点卡的右缘会超出屏幕右侧可视区域时,才左移刚好这么多、
     * 一点不多。** 右侧可视区域同样以 [CONTENT_KEYLINE] 为界(与左基准线对称)。超出屏幕右缘的
     * 卡仍然不砍宽度——见 `HomeScreen.kt` 里 `CategoryRow` 的 `Row` 上那条
     * `wrapContentWidth(unbounded)` 的注释,量出 0 宽的卡永远聚焦不到——继续靠它 + 屏幕本身的
     * 绘制裁切自然露出一截,行尾 peeking 效果不受影响。
     *
     * **R121 的三档卡宽就是按本函数反推的**:960 dp 屏宽下聚焦每档第 N 张(大 5 / 中 6 / 小 8)时返回 0,
     * 第 N + 1 张起才平移,见 [cardWidth]。
     *
     * [screenWidthDp] 由调用方传入(`CategoryRow` 读 `LocalConfiguration.current.screenWidthDp`)——
     * 这个函数本身依然不含任何 Compose 类型,继续可以纯 JVM 单测(见 `GtvLayoutTest`)。
     *
     * **owner 反馈 Round 4(2026-09-21)补丁,§5**:`focusRight` 现在加了一份 [appFocusOverflow]——
     * app 卡片聚焦时会缩放 [APP_FOCUS_SCALE] 倍并外扩描边(`gtvAppFocusFrame`),视觉右缘比
     * 布局右缘更靠右;这个判断原本只看布局右缘,会在「布局右缘刚好没超、但缩放 + 描边之后的
     * 视觉右缘已经超出屏幕」时误判成不需要挪行,结果最右那张完全可见的卡的描边被屏幕边缘裁掉。
     * 不改 `rowPitch`——溢出预算的验证见 `GtvLayoutTest`「app 卡片聚焦溢出」一节与
     * owner-feedback-fix-report.md「Round 4 §5」。
     */
    fun rowShiftX(focusedIndex: Int, size: GtvCardSize, screenWidthDp: Float): Float {
        val focused = focusedIndex.coerceAtLeast(0)
        // 焦点卡右缘的位置,按行尚未平移时的自然布局算(与 pre-Task-7 的 focusRight 同一推导,
        // 只是把 Theme.SidePadding / metrics.cardWidth / metrics.cardSpacing 换成这里的
        // CONTENT_KEYLINE / cardWidth(size) / CARD_GAP),再加上 Round 4 的缩放 + 描边视觉溢出。
        val focusRight = CONTENT_KEYLINE + cardWidth(size) * (focused + 1) + CARD_GAP * focused +
            appFocusOverflow(cardWidth(size))
        // 期望的右侧留白与左基准线对称,同样取 CONTENT_KEYLINE;超出这条线才移动。
        val overRight = focusRight + CONTENT_KEYLINE - screenWidthDp
        return if (overRight > 0f) -overRight else 0f
    }

    /** 开卡片标题时每行多出的高度(dp)= [cardTitleGap](size) + [CARD_TITLE_LINE];关掉时 0。 */
    fun titleHeight(size: GtvCardSize, showTitles: Boolean): Float =
        if (showTitles) cardTitleGap(size) + CARD_TITLE_LINE else 0f

    /** Task 9b:补上焦点描边留白项(`CategoryRow` 的卡片行上下各留 `FOCUS_OUTSET + FOCUS_STROKE`,
     *  见 `Theme.gtvCardMetrics.rowVerticalPad`),此前公式没有这一项,是每行 26.5dp 纵向漂移的
     *  四个来源之一。
     *
     *  **Fix round 1(R15):中档、不显示标题时的返回值不再是 125.5——那是 Google 用 Latin 标题量出来的
     *  行距,`ROW_TITLE_LINE`/`ROW_GAP` 已经为了不裁切中文字形改成 CJK 实测值,R15 当时算出 143.5625
     *  (R25 行标题行盒 23 → 20 后是 140.5625,R48 去掉行标题后是 108.0625,见下一段)。
     *  这不是需要修的偏差,是同一个公式在换了正确输入之后的正确结果;不要为了凑回 125.5 而改动
     *  `ROW_GAP`(R48 之前还有 `ROW_TITLE_LINE`),见两个常量各自的 KDoc。**
     *
     *  **Ruling R48(2026-09-22)**:首页取消行标题,去掉标题行盒 20 + 标题到卡 12.5 = 32.5 dp;
     *  中档不显示标题 140.5625 → **108.0625**(效果图 A2「行距收紧 32dp」)。 */
    fun rowPitch(size: GtvCardSize, showTitles: Boolean): Float =
        2f * (FOCUS_OUTSET + FOCUS_STROKE) + cardHeight(size) + titleHeight(size, showTitles) + ROW_GAP

    /** 首页整页纵向位移(dp,≤ 0):焦点在行 [activeRow] 时,装着全部行的那根 Column 的 `offset(y)`。
     *  = −activeRow × [rowPitch],负值(顶栏)夹到 0。
     *
     *  **Ruling R52(2026-09-23)起首页就读它**(`HomeScreen` 的 `shiftTarget`):焦点行的卡顶恒在
     *  [focusLineCardTop] 那条焦点线上,每换一行整页正好走一个 pitch,上移下移对称,没有粘性、不看
     *  「放不放得下」——详见 [focusLineCardTop]。调用方先把 activeRow 夹到 `rows.size − 1` 以内。
     *  (R32 之前它也是首页的位移,那时行 0 静止在 hero 下方;R32/R42 期间只剩单测在读。) */
    fun rowShiftY(activeRow: Int, size: GtvCardSize, showTitles: Boolean): Float =
        -activeRow.coerceAtLeast(0) * rowPitch(size, showTitles)

    /**
     * **Ruling R32(2026-09-22,owner 真机反馈 Round 9)**:浏览态焦点行钉住的屏幕 y(dp),量的是焦点卡
     * 布局框顶边。出处 `docs/research/2026-09-20-google-tv-launcherx-measurements.md` §8b:launcherx 从
     * 「Your apps」行起连按三次下键,焦点卡 a11y bounds 恒为 `[116,240][268,392]`,y = 240 px = **120 dp**。
     *
     * **R42 起不再用于位移;R52 起首页也不用**(焦点线改在屏幕下部,见 [focusLineCardTop])。
     * 只作 Google 实测记录保留,没有调用点。
     */
    const val BROWSE_ROW_ANCHOR = 120f

    /** **Ruling R52**:焦点行「需要可见」区间的下沿(卡底 + 聚焦溢出 + 卡片标题)离屏幕物理底边的留白(dp)。 */
    const val HOME_BOTTOM_MARGIN = 32f

    /**
     * **Ruling R52(2026-09-23,推翻 R42 的最小位移)**:首页焦点线——焦点行卡片布局框顶边的屏幕 y(dp)。
     * owner 原话:「首页一上来默认只显示一行,而且刚好一行,整体位置要往下调。往下滑动的时候,再出动效
     * 向上滑并显示第二行,再滑一次出第三行。」
     *
     * `= screenHeightDp − HOME_BOTTOM_MARGIN − (cardHeight + appFocusOverflow(cardHeight) + titleHeight)`,
     * 即焦点行放大后的视觉下沿(含卡片标题)离屏幕底边恰好 [HOME_BOTTOM_MARGIN]。960×540、中档、无标题
     * = 540 − 32 − (68.625 + 6.931) = **432.44**(R126 描边 1.5 起;R121 起中档 122 时是 431.94;R59 时中档 137 是 423.08,再之前 153 是 413.63)。
     *
     * 规则:静止态(焦点在顶栏或行 0)行 0 卡顶 = 焦点线;焦点在行 n 时整页位移 [rowShiftY](n) = −n × pitch,
     * 所以焦点行卡顶**恒在**焦点线上。每换一行都走一整行,上下对称,不粘性、不看放不放得下。
     *
     * 「恰好只露一行」不是另外凑的:行 1 静止卡顶 = 焦点线 + pitch
     * = H − 32 − overflow − title + (14 + cardHeight + title + ROW_GAP) − cardHeight
     * = H + 22 − overflow(与档位、卡片标题开关都无关;R126 起 LARGE 的 overflow 最大 7.72)≥ H + 14.2,
     * 恒在屏幕之外。前提是 `ROW_GAP + 14 − HOME_BOTTOM_MARGIN > overflow`——R51 把 ROW_GAP 改回 40 之后才
     * 成立(8 的时候行 1 会露出 ~20 dp),两个常量要一起看(`GtvLayoutTest` 三档 × 标题开关钉住)。
     *
     * 屏高由调用方传入(`HomeScreen` 读 `LocalConfiguration.current.screenHeightDp`),本函数不含 Compose 类型。
     */
    fun focusLineCardTop(size: GtvCardSize, showTitles: Boolean, screenHeightDp: Float): Float =
        screenHeightDp - HOME_BOTTOM_MARGIN -
            (cardHeight(size) + appFocusOverflow(cardHeight(size)) + titleHeight(size, showTitles))

    /** 一行内部,从行布局块顶边到卡片布局框顶边的距离(dp):上侧描边留白
     *  (`rowVerticalPad` = FOCUS_OUTSET + FOCUS_STROKE)。 */
    const val ROW_CARD_TOP = FOCUS_OUTSET + FOCUS_STROKE

    /** **R52**:行 0 布局块顶边在静止态的屏幕 y(dp)= 焦点线 − [ROW_CARD_TOP]。`HomeScreen` 那根被位移的
     *  Column 的 `padding(top)` 就是这个值(padding 在 `offset` 之内,随整页位移一起走——R32 的顺序不变)。
     *  取代 R48 的常量 `ROWS_TOP`(= 顶栏 + hero + `ROWS_LEAD`,行 0 卡顶 301.5)。 */
    fun rowsTop(size: GtvCardSize, showTitles: Boolean, screenHeightDp: Float): Float =
        focusLineCardTop(size, showTitles, screenHeightDp) - ROW_CARD_TOP

    /** 行 [row] 的卡片布局框顶边在**静止态**(位移 0)的屏幕 y(dp)= 焦点线 + row × pitch(R52)。 */
    fun restCardTop(row: Int, size: GtvCardSize, showTitles: Boolean, screenHeightDp: Float): Float =
        focusLineCardTop(size, showTitles, screenHeightDp) + row.coerceAtLeast(0) * rowPitch(size, showTitles)

    /** 行 [row] 在静止态(位移 0)「需要可见」区间的下沿(dp):卡底 + 聚焦放大与描边的纵向溢出
     *  ([appFocusOverflow])+ 卡片标题([titleHeight],关掉时为 0)。R52 起行 0 的这个值恒为
     *  `screenHeightDp − HOME_BOTTOM_MARGIN`。上沿是 [restVisibleTop]。 */
    fun restRowVisibleBottom(row: Int, size: GtvCardSize, showTitles: Boolean, screenHeightDp: Float): Float =
        restCardTop(row, size, showTitles, screenHeightDp) + cardHeight(size) + appFocusOverflow(cardHeight(size)) +
            titleHeight(size, showTitles)

    /** **R48**:行 [row] 在静止态(位移 0)「需要可见」区间的**上沿**(dp)= 卡顶 − 聚焦放大与描边的
     *  纵向溢出([appFocusOverflow]),与 [restRowVisibleBottom] 对称。行图标与卡片纵向居中、比卡矮,
     *  不单独进这个区间。 */
    fun restVisibleTop(row: Int, size: GtvCardSize, showTitles: Boolean, screenHeightDp: Float): Float =
        restCardTop(row, size, showTitles, screenHeightDp) - appFocusOverflow(cardHeight(size))

    /**
     * 首页原地移动态提示(「← → 移动 · ↑ ↓ 换行 · 确定 放下 · 返回 取消」)的顶边屏幕 y(dp)= 顶栏底 + 4,
     * 水平居中,胶囊高 ≈ 32.5(实测),底边 ≈ 106.5。**2026-09-23 从贴底挪到这里**:R52 焦点线让焦点行卡底落在
     * ≈ 500 dp(放大后 ≈ 508),原来贴底 28 dp 的提示(≈ 484–512)正好盖住被搬的卡。焦点行恒在焦点线,
     * 不会与提示相交;焦点行上面那些行,540 屏三档 × 标题开关的静止卡顶是 ≥ 104.4(全亮或 α 0.86)或 ≤ 66.6
     * (全透明)。间距先取的 12(底边 114.5),同日 ui-pending #9 把开标题的行距加高后,小档开标题的上两行
     * 卡顶从 114.7 升到 104.4,改为 4;那一档仍有 ≈ 2 dp 卡顶压在胶囊底下(胶囊底 α 0.8,提示可读)。
     * ~~R59(中档 153 → 137)后多一类:中档开标题的上两行静止卡顶 77.4、α 0.185,整张淡卡压在胶囊之下~~
     * **R121(三档 150 / 122 / 86)起**:静止卡顶只有 ≥ 104.4 或 ≤ 64.1(全透明)两类;104.4 那一类现在是
     * **中档**开标题的上两行(中档 122 就是 R59 的小档,数值原样搬过来),R59 中档 77.4 那一类不再存在。
     * **R126(聚焦描边 2 → 1.5)起**两类是 ≥ 106.4 / ≤ 64.6:焦点线下移 0.5、开标题行距少 0.5,那一类卡顶
     * 104.4 → 106.4,与胶囊底(≈ 106.5)几乎齐平,不再压进胶囊。
     */
    const val MOVE_HINT_TOP = TOP_BAR_TOP + TOP_BAR_HEIGHT + 4f

    /** **Ruling R53(2026-09-23)**:顶栏下淡出带的高度(dp)。见 [topFadeAlpha]。 */
    const val TOP_FADE_BAND = 40f

    /**
     * **Ruling R53**:首页一行(卡片 + 行图标一起)按它**当前动画中的**卡顶屏幕 y [cardTopDp] 取的 alpha:
     * `clamp((cardTop − (TOP_BAR_TOP + TOP_BAR_HEIGHT)) / TOP_FADE_BAND, 0, 1)`——卡顶在 110 dp 以下全亮,
     * 升到 70 dp(顶栏底)淡到 0。
     *
     * 为什么需要:R52 的焦点线在屏幕下部,焦点行上面的行会一路升到顶栏下面(540 屏中档无标题、焦点在行 2 时
     * 行 0 卡顶 ≈ 187,焦点在行 3 时行 0 ≈ 64;R121 之前中档 137 是 161 / 30,R59 之前 153 是 133 / −7),不淡就与药丸 / 时钟叠在一起。纯绘制,不碰焦点。
     *
     * **静止时淡出的行不持焦点;焦点行恒全亮**——后一半不靠本函数的几何保证,由 [homeRowAlpha] 对焦点行短路成 1:
     * 按住上键连发时整页位移追不上焦点,刚拿到焦点的那行卡顶可能还在淡出带里(曾淡到 0 达 130–190 ms,
     * 柔光也被离屏图层裁掉)。
     *
     * ~~`clearOfNewAppsHint`~~(R54:首页显示「有 N 个新应用」时零点下移到提示底边 92)R157 随提示一起删掉,零点恒为顶栏底。
     */
    fun topFadeAlpha(cardTopDp: Float): Float {
        val zero = TOP_BAR_TOP + TOP_BAR_HEIGHT
        return ((cardTopDp - zero) / TOP_FADE_BAND).coerceIn(0f, 1f)
    }

    /** 首页一行的 alpha:焦点行([isFocusRow],即 `HomeScreen` 的 `rowIndex == activeRowSafe`)恒 1,
     *  其余行按当前动画中的卡顶 [cardTopDp] 取 [topFadeAlpha]。
     *  **R129 起这只是「按位置」那一半**:换行时新焦点行若换行前看不见,还要再乘一个时间驱动的进场乘子
     *  (见 [rowEnterStart]),所以新焦点行在落焦后最多 [ROW_ENTER_DELAY_MS] + [ROW_ENTER_FADE_MS] 内可以不是 1——
     *  这是照 Google 有意为之,不是 R53 审查补的那个「焦点行淡到 0」回归(那次是按住上键时位置淡出误伤焦点行)。 */
    fun homeRowAlpha(isFocusRow: Boolean, cardTopDp: Float): Float =
        if (isFocusRow) 1f else topFadeAlpha(cardTopDp)

    /**
     * **Ruling R129(2026-09-29,Gordon:「照 Google」)**:首页上下换行时,**新焦点行**的进场淡入要等多久(ms)。
     * 从按键后整页位移([VMOTION_A_MS] 450 ms)起步的同一刻算起,这段时间里新焦点行的进场乘子停在起点
     * (通常是 0),之后按 `tween(`[ROW_ENTER_FADE_MS]`)` 淡到 1([Theme.homeRowEnterSpec];R129e 起匀速,首版是 FastOutSlowIn)。位移曲线不变。
     *
     * 依据:`docs/design/vertical-motion/2026-09-29-google-row-entry.md`(`unitedu-gtv` 上的 launcherx,`-gpu host`
     * 录屏 ≈ 52 fps、mp4 pts + 模板匹配):上键两次,新焦点行在整页开始动后 **+133 / +150 ms** 才第一次被找到
     * (透明度 0.20 / 0.15),再过 83 / 167 ms 到 0.9、约 300 ms 到满——取 140 ms 起步、250 ms 淡完。
     * 规格值(相对按键 / 位移起步):0.1 在 185 ms、0.5 在 228 ms、0.9 在 299 ms、390 ms 到满;此时整页位移
     * 在 140 ms 走了 40 %、299 ms 走了 92 %。
     * 视觉上是「旧的先走、位置空一下、新的再浮现」,而不是新的一行满透明度刚性滑进来。
     *
     * **R129c(2026-09-29 Gordon 电视上看:「迟滞有了,但不够明显」)起 140 → 220 ms**,刻意比 Google 晚:
     * 0.5 在 308 ms、0.9 在 379 ms、470 ms 到满(整页位移 450 ms 停)。只动新焦点行——R129b 让旧焦点行
     * 先淡出再淡回,在我们「上一行静止全亮」的布局里成了闪一下,已回滚,不要再加。
     */
    const val ROW_ENTER_DELAY_MS = 220

    /** **R129**:新焦点行进场淡入的时长(ms),在 [ROW_ENTER_DELAY_MS] 之后起步(首版曲线 `FastOutSlowInEasing`,R129e 起匀速)。依据同上。
     *  **R129d(2026-09-29 Gordon:「淡入的时机 OK,但淡入的速度可以再慢一些」)起 250 → 400**:220 ms 起步、620 ms 到满。
     *  **R129e(同日,Gordon 对 R129d「没有感受到变化」)起 400 → 600,曲线改匀速**([Theme.homeRowEnterSpec]):
     *  0.5 在 520 ms、0.9 在 760 ms、820 ms 到满(R129d 电视实测 0.5 ≈ 370 ms、0.9 ≈ 570 ms)。
     *  **R129f(同日 Gordon:「好了一些,但还是不够」)起 600 → 900**,仍匀速:0.5 在 670 ms、0.9 在 1030 ms、1120 ms 到满。 */
    const val ROW_ENTER_FADE_MS = 900

    /** **R129**「换行前看得见」的门槛:新焦点行换行前的实际透明度(位置淡出 × 进场乘子;在屏外算 0)低于它才淡入。
     *  取 0.5 = 规格原文「在顶部淡出带里透明度 < 0.5」。 */
    const val ROW_ENTER_VISIBLE_MIN = 0.5f

    /**
     * **R129**:焦点刚换到这一行时,要不要给它的进场乘子重新起一段淡入;要的话返回起点,不要返回 null。
     *
     * - [cardTopDp]:这一行**按键那一刻**的卡顶屏幕 y(= [restCardTop] + 当前动画中的整页位移)。
     * - [multiplier]:这一行当前的进场乘子(上一段淡入可能还没走完,例如连按两下)。
     * - 换行前的实际透明度 `seen` = 卡顶在屏幕底边以下(整行在屏外)→ 0,否则 [topFadeAlpha] × [multiplier]
     *   (换行前它不是焦点行,位置那一半就是 [topFadeAlpha],不走 [homeRowAlpha] 的焦点行短路)。
     * - `seen` < [ROW_ENTER_VISIBLE_MIN] → 从 **`seen`**(不是 0)起步;否则返回 null:看得见的行不另加淡入,
     *   正在淡入的那段也不打断(它自己会走到 1)。
     *
     * **为什么起点是 `seen` 而不是 0**:落焦那一帧起,这一行的位置透明度被 [homeRowAlpha] 短路成 1,画出来的就是
     * 1 × 乘子;乘子取 `seen`,画面上的透明度与按键前逐值相等——没有任何一行会「闪一下变没」。在屏外时 `seen` = 0,
     * 正在淡入的乘子被拉回 0 也看不见;常见情形(下键:下一行在屏外;上键:上一行在淡出带顶端 ≈ 0)起点都是 0。
     */
    fun rowEnterStart(cardTopDp: Float, screenHeightDp: Float, multiplier: Float): Float? {
        val seen = if (cardTopDp >= screenHeightDp) 0f
        else topFadeAlpha(cardTopDp) * multiplier.coerceIn(0f, 1f)
        return if (seen < ROW_ENTER_VISIBLE_MIN) seen else null
    }

    /* ~~NEW_APPS_HINT_GAP / _LINE / _BOTTOM~~(首页「有 N 个新应用」提示的位置,R54 起还管淡出零点):
     * R157(2026-10-01 Gordon:「remove the N new apps」)提示整行删掉——它与 R146 的顶栏焦点名字叠在同一条带上。 */

    /*
     * **Ruling R52 删除**(2026-09-23):R42 的最小位移 `nextPageShiftY` 与它的窗口边界 `TOP_SAFE`(顶栏下 16)/
     * `BOTTOM_SAFE`(58),R32 的锚点位移 `pageShiftY`,R48 的 `ROWS_TOP` / `ROWS_LEAD`(行 0 卡顶钉在 301.5)。
     * R42 为 owner 的硬验收「两行都放得下时下移页面不动」而设;R52 是 owner 明确推翻它——现在每换一行都位移
     * 一整行。历史推导见 git(c93f9ea 及更早)。
     */

    /*
     * **Ruling R48(2026-09-22)删除了 R43/R46 的行标题焦点态**(焦点行标题放大 `ROW_TITLE_FOCUS_SCALE`
     * 1.78、白/灰两态、图标淡出、cap 中线补偿 `ROW_TITLE_CAP_EM`)——首页不再画行标题。只保留一个轻量
     * 焦点提示给行图标:焦点行近白、其余行灰(R46 从 Google 取的两色),同一根弹簧过渡,不缩放
     * (见 [ROW_ICON_SIZE])。
     */
    /*
     * **Ruling R47(2026-09-22)**:R43 的 `ROW_TITLE_FOCUS_MS`(300 ms 减速 tween)已删除,行标题焦点态
     * (R48 起只剩行图标的灰↔白)
     * 改走 [Theme.rowIconFocusSpec](原名 `rowTitleFocusSpec`,2026-09-23 整枝评审改名)——与整页位移**同一根**临界阻尼弹簧([BROWSE_SPRING_STIFFNESS])。
     * owner 原话:「抄 Google 的那个动效,也就是三个东西的三层联动:先滚动,滚动的同时标题行淡入;淡入到位的
     * 时候,焦点移下来,然后再放大。这只是我的描述,整体上你按照 Google 那样去做。」
     *
     * Google 实测(`unitedu-gtv` 模拟器 launcherx,`screenrecord --size 960x540` mp4 + `ffprobe` pts,
     * `animator_duration_scale` 1.0;时间相对位移起步):
     *
     * | 段 | Top picks → Your apps(app 行) | Your apps → Continue watching |
     * |---|---|---|
     * | 页面位移 起步 / 到 95% | 0 / +0.29 s | 0 / +0.31 s |
     * | 行标题长大 起步 / 到 95% | ≈0 / ≈+0.30 s | +0.02 / +0.31 s |
     * | 焦点卡放大 起步 / 到顶 | +0.02–0.04 / +0.13 s | +0.07 / +0.17 s |
     *
     * 即 Google 的行标题与位移**同起同止**(像是被滚动直接带着走),焦点卡在位移刚起步时就开始放大、位移过半前
     * 已放完。我们 R43 的标题 300 ms 减速 tween 在 +0.14 s 就到 95%,比位移早一倍收尾——这是「三层没联动」
     * 的一半;换成同一根弹簧后标题与位移同起同止(+0.31 s 到 95%)。
     * 焦点卡的起步延迟 [FOCUS_AFTER_SHIFT_DELAY_MS] 80 ms 与 Google 的 +0.02–0.07 s 同量级,**不改**;
     * 放大时长 [FOCUS_SCALE_IN_MS] 600 ms 是 R37 owner 手感值(Google 只有 ~0.1–0.13 s),**不改**。
     */

    /**
     * **Ruling R45(2026-09-22,owner 真机反馈,取代 R35「壁纸跟页面上移」与 R36 两层方案)**:壁纸**单层、
     * 不位移**,只按整页位移调暗。owner 原话:「右边的壁纸有双重的残影,这很恐怖:我移上去的时候,龙猫会
     * 向上移,但它原来位置上留了一个残影。」根因是 R36 的两层同一位图——上层随整页 1:1 上移并在本距离内
     * 淡出、底层原地常驻 20%;R42 最小位移后常常只移几十 dp,上层淡不完,两份错位的副本同时可见。
     * 只要两份错位副本同时可见就必然残影,所以换方案而不是调参数。
     * Google 实测(`docs/screenshots/gtv/22-google-backdrop-static-while-scrolling.jpg`,launcherx 录像
     * #12/#13/#14、#26/#28/#30):上下滚动时 backdrop 图**原地一动不动**,往上走的只有 hero 文字和各行
     * 内容,图只变暗 / 换图。
     *
     * 下面是 R35 的原始依据,「距离」这一半沿用,「随页面上移」这一半已被 R45 撤销:
     *
     * **Ruling R35(2026-09-22,owner 真机反馈 Round 10)**:壁纸随整页位移淡到黑所用的距离(dp)。
     * 页面上滑(R32 的 `pageShiftY`,R52 起是 [rowShiftY])这么多时壁纸的 alpha 由 1 线性降到 0;取 [HERO_HEIGHT],即页面滑过
     * 一个 hero 高度壁纸恰好完全淡出(R35 当时:行 1 落到 R32 锚点 120 dp 之前就已走完)。R52 起每换一行走一整个
     * [rowPitch](中档无标题 122.63;R121 之前 131.06,R59 之前 140.06),192 dp 要到焦点在行 2 时才走完——行 1 时壁纸在 ≈ 0.49(R45 起终值 0.2)。
     *
     * 依据:B3 裁定 hero 区留给壁纸,在我们这里「英雄区」**就是壁纸本身**——R32 让 hero 的空位随整页
     * 走了,但壁纸层 `Wallpaper()` 住在 `MainActivity` 的 setContent 顶层(刻意的,进出编辑页不重解
     * 1920×1080、不闪黑),不在 HomeScreen 被位移的 Column 里,所以 owner 真机看到「英雄区还是不动」。
     * Google 那边 hero 的图是跟着页面上滑并淡出到黑的(`docs/screenshots/gtv/19-vertical-transition-frames.jpg`
     * #42→#52),浏览态(焦点在行 1 及以下)的背景是纯黑;这里的终值 0 对应那个黑底。
     *
     * **owner 若想浏览态仍保留壁纸**:改的是 [wallpaperAlpha] 的终值,不是这个距离——R36 已这么做
     * (终值 [WALLPAPER_BROWSE_ALPHA] 0.2);R24 那层本身固定在屏幕坐标上、不随位移走,保持不动。
     * R45 起壁纸本身也不随位移走,只有 alpha 按这个距离从 1 降到 [WALLPAPER_BROWSE_ALPHA]。
     */
    const val WALLPAPER_FADE_OVER_DP = HERO_HEIGHT

    /**
     * **Ruling R36(2026-09-22,owner 真机反馈 Round 10)**:浏览态壁纸淡出的**终值**——不再淡到 0(纯黑),
     * 停在 **0.20**,留两成壁纸影子。owner 原话:「现在是完全淡到黑。把淡出终止改成 20% 的暗度,留一点
     * 壁纸影子更有质感。」R35 的终值 0 对应 Google 浏览态的纯黑底;这是 owner 明确偏离 Google 的手感
     * 裁定,不是量出来的值。淡出距离([WALLPAPER_FADE_OVER_DP])不变,只改终点。
     *
     * 是**线性插值到 0.2**(`1 → 0.2`),不是「原曲线再乘 0.2」——后者在位移 0 时也会把静止态的壁纸
     * 压到 0.2,静止态(hero 露出)壁纸必须是全亮的。
     *
     * R36 当时用「底层常驻 0.2 + 上层随页面上移淡到 0」两层叠出这个终值;R45 删掉了两层(残影),
     * 终值改由单层的 [wallpaperAlpha] 直接插值到这里。
     */
    const val WALLPAPER_BROWSE_ALPHA = 0.20f

    /**
     * R45:整页位移 [shiftDp](R52 起与 [rowShiftY] 同一个量,≤ 0 表示上移;正负都按绝对值算,调用方不必
     * 关心符号)对应的**单层**壁纸 alpha:
     * `1 − (1 − WALLPAPER_BROWSE_ALPHA) × clamp(|shift| / WALLPAPER_FADE_OVER_DP, 0, 1)`,
     * 即从 1 线性降到 [WALLPAPER_BROWSE_ALPHA]。壁纸不再位移(R45),只有这一个量随页面变;
     * 每帧的动画值都经这里换算,变暗与卡片行走同一根弹簧曲线(`Theme.browseShiftSpec`)。
     * (R36 版本这里返回的是上层 alpha 1 → 0,终值 0.2 靠底层常驻层叠出来。)
     */
    fun wallpaperAlpha(shiftDp: Float): Float =
        1f - (1f - WALLPAPER_BROWSE_ALPHA) * (kotlin.math.abs(shiftDp) / WALLPAPER_FADE_OVER_DP).coerceIn(0f, 1f)

    /**
     * [WALLPAPER_DIM_PER_ROW] 的实现:整页位移 [shiftDp] 折成「第几行 + 行内进度」,在相邻两行的静止 alpha
     * (都取 [wallpaperAlpha])之间线性插值。整数行上与 [wallpaperAlpha] 逐值相同;每次换行,壁纸的变化量
     * 按这次位移的进度匀速分摊,不会在前半程就暗到底、或后半程才开始亮。[pitchDp] ≤ 0 时退回 [wallpaperAlpha]。
     */
    fun wallpaperAlphaPerRow(shiftDp: Float, pitchDp: Float): Float {
        if (pitchDp <= 0f) return wallpaperAlpha(shiftDp)
        val pos = kotlin.math.abs(shiftDp) / pitchDp
        val n = kotlin.math.floor(pos)
        val a0 = wallpaperAlpha(n * pitchDp)
        val a1 = wallpaperAlpha((n + 1f) * pitchDp)
        return a0 + (a1 - a0) * (pos - n)
    }

    /** 首页壁纸层实际用的 alpha:按 [perRow](默认 [WALLPAPER_DIM_PER_ROW])在两种算法间选。 */
    fun homeWallpaperAlpha(shiftDp: Float, pitchDp: Float, perRow: Boolean = WALLPAPER_DIM_PER_ROW): Float =
        if (perRow) wallpaperAlphaPerRow(shiftDp, pitchDp) else wallpaperAlpha(shiftDp)
}
