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
     *  药丸内的居中留白),图形本身画多大改由 [TOP_BAR_GEAR_GLYPH]/[TOP_BAR_SCREENSAVER_GLYPH]
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
    /** 屏保(相册 / Slideshow)图标的**图形绘制大小**,道理与量法都同上一个常量,数值不同——
     *  **Google 这两个图标本来就不一样大,不能取平均**(任务原话明确点出「gear 明显比
     *  gallery/screensaver 图形大」,这次测量印证了这一点:齿轮 16×18dp,相册只有 13×13dp,
     *  比齿轮小了三分之一左右)。
     *
     *  **Google 的量法**:同一张参考图,屏保图标框 a11y bounds (656,76)-(720,140),图标本体
     *  是灰阶、周围没有角标遮挡,不需要角标分离这一步。同样用「药丸底色均值(≈46.2)与图标核心
     *  亮度峰值(≈249.3)取中点(≈147.8)」的阈值量,90–150 区间内包围盒同样稳定不变,量得
     *  26×26px = **13×13 dp**(比任务原始表格的 14×13.5dp 略小,差距不到 1px,这颗图标没有
     *  角标遮挡问题,判断是阈值取舍的正常量测噪声,不是系统性误判——以这次自己复核的严格阈值
     *  结果为准)。
     *
     *  **框到图的换算**:Round 5 sweep 表量出改动前(单一 32dp 常量时期)这颗图标的墨迹是
     *  24×24dp,正方形,占框比例 24/32=0.75。按同一比例反推:13/0.75≈17.3dp,取 **17.5 dp**。
     *  **已装机复核**:改动后实际墨迹(外框,内含的三角形播放符号完全落在外框范围内,不单独
     *  外扩)27×26px = 13.5×13.0dp(bg 均值同上一个常量,峰值≈201.6,中点≈121.6),与目标
     *  13×13dp 相差都在 0.5dp(1px)以内,同样落在测量噪声范围,不再二次迭代——复核过程见报告
     *  「Round 6」。 */
    const val TOP_BAR_SCREENSAVER_GLYPH = 17.5f
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
     *  `rowPitch` 与依赖它的 `GtvLayoutTest` 三处断言值随之变化(143.5625→140.5625、
     *  -287.125→-281.125、167.5625→164.5625),这是同一条公式在换了正确输入之后的正确结果,
     *  不是需要另外吸收的偏差,不要为了凑回旧值而改动这个常量或公式。 */
    const val ROW_TITLE_LINE = 20f
    const val ROW_TITLE_TO_CARD = 12.5f
    /** Fix round 1(R15,2026-09-20):**125.5 dp 同样是 Google 用 Latin 量出来的行距,对中文标题不
     *  成立,不要试图凑回这个数。** 沿用它会把 `ROW_GAP` 推到约 −10dp(23+12.5+14+86.06−125.5≈−10),
     *  而 `rowVerticalPad`(上下各 7dp)是货真价实要留给外扩焦点描边的空间——那么负的 `ROW_GAP` 会让
     *  行 N 的焦点描边直接压在行 N+1 的标题字形上。**改为放弃凑 125.5,`ROW_GAP` 改取一个有真实、非负
     *  余量的值。** 这里的「余量」定义是:`ROW_GAP` 的值本身,就是「行 N 的卡片行(含它自己下方预留
     *  给描边的 7dp)结束」到「行 N+1 标题行盒开始」之间的物理间距——因为 `CategoryRow` 的标题行盒
     *  紧贴在 `Column` 顶部、前面不再有别的 padding。取 **8 dp**:比行内自己的 `ROW_TITLE_TO_CARD`
     *  (12.5dp)略窄,让一行标题在视觉上更贴近它自己那一行的卡片、不与上一行混淆,同时明显大于
     *  `FOCUS_STROKE`(2dp),保证聚焦描边与下一行标题之间总有可见的黑色间隙,不会贴到一起。
     *  装机验证见 `task-9b-report.md` Fix round 1 一节。 */
    const val ROW_GAP = 8f
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
    const val CARD_TITLE_GAP = 4f
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
     *  导致再次漂移的可能。`titleHeight(true)` 与依赖它的 `rowPitch(size, true)` 会跟着变
     *  4dp——这是显示标题时行间距该有的样子,不是需要另外吸收的偏差(`GtvLayoutTest` 新增的
     *  `showTitles = true` 断言直接编码这条不变量)。装机复核见
     *  `.superpowers/sdd/2026-09-20-gtv-line/owner-feedback-fix-report.md`「Round 2 · Fix 1」与
     *  `docs/screenshots/gtv-owner-fix1-card-title-{clipped,fixed}-*.png` 的裁切前后对照。 */
    const val CARD_TITLE_LINE = 20f
    /** 行标题图标与文字之间的间距(CategoryRow)。Fix 4(终审 2026-09-20)从字面量搬进来,数值不变。 */
    const val ROW_TITLE_ICON_GAP = 8f

    /** 长按 / 齿轮菜单(GearMenu,Task 8):药丸尺寸,实测报告 §7,268×55 dp,全圆角(h/2)。 */
    const val MENU_ITEM_WIDTH = 268f
    const val MENU_ITEM_HEIGHT = 55f
    /** 药丸之间的纵向间距。报告没给这一项,按参考图 docs/screenshots/gtv/16-app-longpress-menu.png
     *  像素量测(两药丸间隙 y 355→384 px,该图 1:1 对应 320dpi 实机,/2 得 dp)≈ 14.5 dp,取整 16。 */
    const val MENU_ITEM_GAP = 16f
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
     * 用在:app 卡片的缩放 + 描边(`GtvFocusStroke.gtvAppFocusFrame`,见 [APP_FOCUS_SCALE] 一族);
     * `gtvFocusStroke` 覆盖的另外两处内容卡式描边(`RowIconPicker`、`gtvAppFocusFrame` 的
     * `moving` 分支)延续复用同一对时长——`card_focus`/`_unfocus` 是 Google 对「app 卡片」的量测,
     * 这两处不是严格意义上的 app 卡片聚焦,只是为了整条线的焦点淡入淡出手感统一而借用同一个数字,
     * 不是又找到了各自的独立测量,如实记录不夸大。`GearMenu.MenuPill` 同理复用(见该文件调用点
     * 的注释)。曲线用 `Theme.AppFocusEasing`(同一份 APK 引用的 `AccelerateDecelerateInterpolator`,
     * 不是 `Theme.MotionEasing` 那条给「位置动画」用的减速曲线)。
     */
    const val FOCUS_FADE_IN_MS = 150
    const val FOCUS_FADE_OUT_MS = 150

    /**
     * **Ruling R27(2026-09-21,owner 真机反馈 Round 7)**:浏览位移(**四处**:首页行 x/y 平移、
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
     */
    const val BROWSE_SHIFT_MS = 250

    /**
     * owner 反馈 Round 4:Google 对 **app tile**(不是 content card)的聚焦处理——放大,不是外扩
     * 静态描边。旧版本(1.0.595789376)`fraction/app_card_focused_scale = 1.14`;但 controller
     * 在**本项目实际对照的目标版本**(1.0.976298245)上装机像素量测聚焦态应用图块
     * 152 → 168 px = **1.105×**——两代版本数值不同,以目标版本的实测为准,1.14 只作为旧版记录,
     * 不要把它当成现在该用的数字。 */
    const val APP_FOCUS_SCALE = 1.105f
    /** app tile 聚焦描边与**缩放后**边缘之间的间隙(dp)。controller 在目标版本(1.0.976298245)
     *  上装机像素量测得出,不是命名资源(Google 没有给这段间隙单独取名字)。 */
    const val APP_FOCUS_GAP = 2f
    /** app tile 聚焦描边本身的宽度(dp)。`dimen/card_focused_frame_outer_stroke_width = 2dp`——
     *  与内容卡的 [FOCUS_STROKE] 数值恰好相同,但这是两个分别命名的 Google 资源(content card
     *  与 app tile 各自的边框宽度只是刚好都是 2dp),不合并成一个常量,避免以后其中一个改了
     *  而误伤另一个。 */
    const val APP_FOCUS_STROKE = 2f

    /** app tile 聚焦时的视觉溢出量(缩放增量的一半 + 描边间隙 + 描边本身),给定卡片某一边的
     *  未缩放长度。纯几何,不含 Compose 类型,方便单测验证「聚焦时会不会碰到下一行标题」
     *  「行尾右缘会不会被屏幕边缘裁描边」这类不变量(owner 反馈 Round 4 §5)。
     *
     *  **[APP_FOCUS_GLOW_DP](R28 的柔光)刻意不在这条公式里,不要"顺手补全"**:这个函数是
     *  **布局约定**(`rowShiftX` 拿它决定行要不要左移、`GtvLayoutTest` 拿它验证不碰下一行标题),
     *  而柔光是纯视觉溢出,画在 `drawBehind` 里、不参与测量。把 30dp 柔光加进来会让每行凭空
     *  多出 30dp 的间距预算,破坏已经与 Google 对齐的纵向节奏。详见 [APP_FOCUS_GLOW_DP]。 */
    fun appFocusOverflow(dimension: Float): Float =
        dimension * (APP_FOCUS_SCALE - 1f) / 2f + APP_FOCUS_GAP + APP_FOCUS_STROKE

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
     * 这一项(加进去会把行间距撑开 30dp)。代价是柔光会盖到相邻卡片与上一行标题区的底部——
     * 这是 Google 那张剖面本身就有的样子(它的柔光同样铺出 30dp,行距比这还紧),不是 bug。
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
     *  相邻两圈首尾相接、不重叠。[APP_FOCUS_GLOW_DP] / 这个值 = 15 圈。
     *
     *  **为什么是画一串圆环而不是 `BlurMaskFilter`**:后者在硬件加速画布上行为不稳(各家 GPU
     *  实现不一致、Compose 还要另开 layer),而柔光每帧都跟着 `scale` 变几何;一串按指数衰减
     *  的细环是纯几何,逐帧重算的代价是 `APP_FOCUS_GLOW_DP / APP_FOCUS_GLOW_RING_DP` = 30 次 `drawRoundRect`
     *  (只有聚焦中的那张卡才画,而且收尾段那十几圈 alpha 已经接近 0),
     *  而且圆角半径随外扩距离同步增大这件事直接套用已有写法(`r = corner + (outX + outY) / 2`)。
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
     * `alpha = 增量 / 前景亮度`,再夹到 [APP_FOCUS_GLOW_MAX_ALPHA]。
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

    fun cardWidth(size: GtvCardSize): Float = when (size) {
        GtvCardSize.SMALL -> 122f
        GtvCardSize.MEDIUM -> 153f
        GtvCardSize.LARGE -> 192f
    }

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
     * 右边永远还有更多"这个假设对它成立。我们的行是有限的应用列表,常见 5 张卡:MEDIUM 档
     * 5 张卡只占 845dp(5×153 + 4×20),这台机型屏宽 960dp,连左右两条 58dp 基准线一起量都
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

    fun titleHeight(showTitles: Boolean): Float =
        if (showTitles) CARD_TITLE_GAP + CARD_TITLE_LINE else 0f

    /** Task 9b:补上焦点描边留白项(`CategoryRow` 的卡片行上下各留 `FOCUS_OUTSET + FOCUS_STROKE`,
     *  见 `Theme.gtvCardMetrics.rowVerticalPad`),此前公式没有这一项,是每行 26.5dp 纵向漂移的
     *  四个来源之一。
     *
     *  **Fix round 1(R15):中档、不显示标题时的返回值不再是 125.5——那是 Google 用 Latin 标题量出来的
     *  行距,`ROW_TITLE_LINE`/`ROW_GAP` 已经为了不裁切中文字形改成 CJK 实测值,现在是 143.5625。
     *  这不是需要修的偏差,是同一个公式在换了正确输入之后的正确结果;不要为了凑回 125.5 而改动
     *  `ROW_TITLE_LINE`/`ROW_GAP`,见两个常量各自的 KDoc。** */
    fun rowPitch(size: GtvCardSize, showTitles: Boolean): Float =
        ROW_TITLE_LINE + ROW_TITLE_TO_CARD + 2f * (FOCUS_OUTSET + FOCUS_STROKE) +
            cardHeight(size) + titleHeight(showTitles) + ROW_GAP

    fun rowShiftY(activeRow: Int, size: GtvCardSize, showTitles: Boolean): Float =
        -activeRow.coerceAtLeast(0) * rowPitch(size, showTitles)
}
