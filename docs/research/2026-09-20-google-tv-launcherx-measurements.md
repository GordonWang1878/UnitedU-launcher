# Google TV(launcherx)实测量测 — 2026-09-20

> **量的是哪一版**:`com.google.android.apps.tv.launcherx` **1.0.976298245**(versionCode 见 WORKLOG;由 Play 从镜像自带的 1.0.595789376 更新而来,**两版是两代设计**,本文量的是新版)。
> **在哪量的**:AVD `unitedu-gtv`(Google TV android-34 arm64-v8a,`sdk_google_atv64_amati_arm64`),1920×1080 @ 320 dpi,已登 Google 账号(内容为台湾区推荐流)。
> **几何可比性**:A95L 的 `wm size` Override = 1920×1080、`wm density` = 320,与本机逐位一致,**下面所有 dp 值可 1:1 搬**。
> **量法**:优先取 uiautomator a11y 树里的 `bounds`(那就是精确布局像素,不用阈值猜边),密度 320 dpi → **dp = px ÷ 2**;只有描边、光晕、圆角、颜色这类 a11y 不暴露的才扫像素。

## 0. 一句话结论

新版 Google TV 首页是**「常驻顶栏(药丸组)+ 全屏 hero + 若干 16:9 内容行 + 圆形应用行」**;**焦点不是一种画法,而是四种**,按控件类型分:选中态填浅色、内容卡不缩放只外扩描边、圆形应用图标缩放 1.105 倍 + 描边、面板/菜单项填浅蓝。这四种混用是它「看起来像 Google」的主要来源,比单个尺寸更重要。

## 1. 屏幕与安全边距

| 项 | px | dp |
|---|---|---|
| 分辨率 / 密度 | 1920×1080 @ 320 dpi | density = 2.0 |
| 内容左基准线(行标题、首卡、头像都对齐它) | x = 116 | **58** |
| 顶栏纵向占位 | y = 68…152 | 34…76 |

## 2. 顶栏(常驻,焦点进内容行后折叠成一个向上箭头)

左起:头像 → 药丸组 A(搜索 / Home / Apps)→ 间隙 → 药丸组 B(屏保 / 快捷设置)→ 右侧时钟 + `Google TV` 字标。
**`Library` 在这一版被收进头像菜单**(旧版是第三个文字 tab)。

| 元素 | bounds(px) | 尺寸 px | 尺寸 dp |
|---|---|---|---|
| 头像 | (116,68)-(196,148) | 80×80 | **40×40** |
| 药丸组 A(整块) | (228,72)-(612,144) | 384×72 | 192×**36** |
| └ 搜索 | (228,72)-(324,144) | 96×72 | 48×36 |
| └ Home | (324,72)-(474,144) | 150×72 | 75×36 |
| └ Apps | (474,72)-(612,144) | 138×72 | 69×36 |
| 两组之间的间隙 | 612→656 | 44 | **22** |
| 屏保图标 | (656,76)-(720,140) | 64×64 | **32×32** |
| 快捷设置图标 | (736,76)-(800,140) | 64×64 | 32×32 |
| 两个图标之间 | 720→736 | 16 | 8 |

**药丸组的底色**是 `rgb(40,42,44)`(半透明深灰轨道),两个组各自独立,不是一条通栏。

## 3. 首页纵向节奏

| 区块 | 纵向 band(px) | 高 px / dp |
|---|---|---|
| hero(整块可聚焦) | 192…576 | 384 / **192** |
| └ hero 主卡 | (116,252)-(1084,576) | 968×324 / 484×162 |
| └ hero 右侧第二卡 | (1124,315)-(1920,576) | 796×261 / 398×130.5 |
| `Top picks for you` 行标题 | (116,600)-(302,630) | 186×30 / 93×15 |
| `Top picks for you` 行 band | 639…843 | 204 / **102** |
| `Your apps` 行标题 | (116,851)-(224,881) | 108×30 / 54×15 |
| `Your apps` 行 band | 890…1074 | 184 / 92 |

**行间距(band 顶到 band 顶)= 890 − 639 = 251 px ≈ 125.5 dp。** 行标题底到该行首卡顶 = 630→655 = 25 px(12.5 dp)。

## 4. 卡片尺寸

| 行类型 | 卡尺寸 px | dp | 宽高比 | 卡间距 |
|---|---|---|---|---|
| `Top picks for you`(16:9 缩略图) | 306×172 | **153×86** | **1.78 = 16:9** | pitch 346 px(173 dp)→ **gap 40 px(20 dp)** |
| `Top selling movies` / `Popular shows`(带评分元数据) | 392×237 | 196×118.5 | 1.65 | pitch 432 px(216 dp)→ gap 40 px(20 dp) |
| `Your apps` 圆形图标 | 152×152 | **76×76** | 1.00 | pitch 182 px(91 dp)→ **gap 30 px(15 dp)** |

**行尾「peeking」是真的**:`Top picks` 行第 6 张卡的 bounds 是 (1846,655)-(1920,827),被屏幕右缘切成 74 px 宽 —— 它**不是被量成 0 宽**,而是正常参与布局、只是被裁切,所以焦点能正常走到它上面。(对照 UnitedU 的铁律 §1:那边是 `Modifier.size()` 被父约束夹成 0 宽导致永远聚焦不到,两回事。)

## 5. 焦点画法 —— 四种,按控件类型分

| 控件 | 缩放 | 描边 | 填充 | 采样色 |
|---|---|---|---|---|
| 顶栏 tab(**选中态**) | 无 | 无 | 浅色实填 | `rgb(232,234,237)`,文字转深色 |
| 顶栏 tab(**聚焦但未选中**) | 无 | 无 | 中灰实填 | `rgb(88,94,100)` |
| 内容卡(16:9) | **无** | 有,约 4 px(2 dp)宽,画在布局框**外扩 10 px(5 dp)**处,四边一致 | 无 | 描边被背景色调染,亮度约 +30 |
| 圆形应用图标 | **有,152 → 168 px = 1.105×** | 有,约 4 px(2 dp),在放大后的边缘再外扩约 4 px(2 dp) | 无 | 描边 ≈ `rgb(82,82,83)`,外侧带柔光 |
| 快捷设置磁贴 / 长按菜单项 | 无 | 无 | **浅蓝实填** | 磁贴 `rgb(204,232,255)`;菜单项 `rgb(228,243,255)`,文字转深色 |

**「选中」与「聚焦」在顶栏是两套画法**,这一点最容易漏:Apps 被选中时是浅色实填,Home 拿到焦点但未选中时只是中灰填充。

**内容卡聚焦还会改整页背景**:焦点落到 `Top picks` 某张卡上时,该片源的**全幅剧照铺满整个屏幕**,左上叠标题字标 + 片源名 + `IMDb 6.4 · Drama · 2026` 元数据块,顶栏同时折叠成一个向上箭头。这是 Google TV 首页「有内容感」的主要来源,也是 UnitedU 最没有对应物的一块(产品上我们没有剧照,对照表里要决定用什么顶上)。

## 6. 快捷设置面板(齿轮)

右侧浮出的 sheet,不是全屏:
- 面板底色 `rgb(23,26,31)`,其余画面重度压暗。
- 顶部:`Sun, Sep 20` + `11:11 AM` 大字;右上角两个 62 px(31 dp)图标:`Open Settings`(1713,81)-(1775,143)、`Open profile selector`(1789,81)-(1848,143)。
- 磁贴 **2 列**,单块 248×110 px = **124×55 dp**;顺序:Screensaver / Inputs / Ethernet / Bluetooth / Sleep timer / Audio output / Accessibility。
- 底部:`Tip of the day` 卡 + `Google TV` 字样 + 帮助问号 + 一个向下的展开箭头。

## 7. 应用长按菜单

全屏黑底(`rgb(14,14,15)`),**左侧是该应用的 banner 图 + 应用名**(注意:用的是 TV banner,不是首页那个圆形图标),右侧一列整宽药丸动作:
- 菜单项 536×110 px = **268×55 dp**,全圆角。
- 聚焦项浅蓝实填 `rgb(228,243,255)` + 深色文字;未聚焦项 `rgb(22,23,24)`。
- 本机只有两项(`Move` / `Open`),因为这台模拟器的应用是系统预装;真实设备上还会有 `Remove`、`View details` 等。

## 8. 行锚定规则(实测,对我们最关键的一条)

**焦点卡永远钉在左基准线 x = 116 px(58 dp),整行在它下面平移。** 沿 `Top picks for you` 行连按 7 次右键,每次读 a11y 的焦点 bounds:

```
(116,716)-(422,888)   ×7 次,一次都没变
```

同时逐帧比对该区域的像素,平均通道差 180–391,**确认内容真的在换**,不是右键没生效。

这与 UnitedU 现在的做法(铁律 §1:位移一律自己算,`Modifier.offset` + `animateDpAsState`)是同一个模型,**规则可以直接照搬:目标偏移 = −(焦点卡索引 × pitch)**,其中 `Top picks` 的 pitch = 346 px(173 dp)。

## 8b. 纵向锚定规则(2026-09-20 补测,Task 9b Step 1)

**焦点行钉在固定 y,与横向同构。** 在 `emulator-5554` 上把 Google TV 拉到前台(它是 HOME 角色持有者),连按 DPAD_DOWN 逐次 `uiautomator dump`:

```
step0 顶栏 Home tab 聚焦(基线)        bounds=[324,72][474,144]
step1 hero 内部聚焦                    bounds=[116,343][1084,576]
step2 Top picks for you 聚焦           bounds=[116,716][422,888]
step3 Your apps「Live TV」聚焦         bounds=[116,240][268,392]
step4 再下一行                          bounds=[116,240][508,477]
step5 再下一行                          bounds=[116,240][508,477]
```

**step3/4/5 是三个不同的行,焦点卡的 y 恒为 240,一次不差** —— 在远离 hero 的稳态列表区,焦点行的绝对 y 不随行号变化。

**「钉在固定 y」与「索引 × pitch」不是二选一的两种实现,是同一个公式的两种读法**:位移 `-activeRow × pitch` 施加在装着全部行的外层容器上,行 i 的自然位置是 `anchorTop + i × pitch`,叠加后 = `anchorTop + (i − activeRow) × pitch`;当 `i == activeRow` 时恒为 `anchorTop`。**前提是 pitch 为常数** —— Google 那边行与行的卡尺寸会变(不同内容行用不同卡型),UnitedU 所有行共用一个 `cardSize`,所以这个前提在我们这边天然成立。

**step1→step2→step3 的巨大跳变(343→716→240)不是纵向节奏的一部分**,而是 §5 记录过的「内容卡聚焦时全幅剧照铺满整屏 + 元数据面板」效果顺带改变了行的 y —— 量纵向节奏时必须跳过 hero 相关的那几步,只取稳态区。

**注意这份 pitch 我们用不了**:交叉验证时量到 Google 的 band gap 约 291 px(≈145.5 dp),而 §3 在默认态量到的是 125.5 dp —— 两者差在聚焦时行标题被换成了另一种样式。更要紧的是 §3 的 125.5 dp 是**拉丁界面**的数字,中文界面装不进去(行标题盒 15 dp 会把中文裁成别的字),详见 `docs/superpowers/plans/2026-09-20-gtv-line.md` Task 9b 与 WORKLOG 的 R15 裁定。

## 9. 圆角半径(像素拟合,±2 px)

| 形状 | 视觉尺寸 | 半径 |
|---|---|---|
| 16:9 内容卡 | 306×172 px(153×86 dp) | ≈ 15–19 px → **约 8 dp** |
| 快捷设置磁贴 | 248×110 px(124×55 dp) | ≈ 22–25 px → **约 12 dp** |
| 长按菜单项药丸 | 562×110 px(281×55 dp) | ≈ 50–55 px → **整圆角(h/2 = 27.5 dp)** |

拟合法:先扫出整个形状的真实视觉包围盒,再按行量左内缩;抗锯齿让边界有 2 px 左右的不确定。

## 10. 字号(按首个大写字母的 cap height 反推,cap ≈ 0.71 em)

| 文本 | cap 高 px | dp | 推得字号 |
|---|---|---|---|
| hero 标题(`The Super Mario Galaxy Movie`) | 52 | 26.0 | **≈ 36 sp** |
| Apps 页大标题(`Your apps`) | 46 | 23.0 | **≈ 32 sp** |
| 快捷设置时钟(`11:11 AM`) | 28 | 14.0 | ≈ 20 sp(取的是数字 `1`,可能略偏小) |
| hero 副标题(`Family`) | 24 | 12.0 | **≈ 16 sp** |
| 行标题(`Top picks for you` / `Your apps`) | 19 | 9.5 | **≈ 14 sp** |
| 应用名(`Live TV`) | 19 | 9.5 | **≈ 14 sp** |

注意别用整行 ink 高度反推:带 `p` `y` 这类降部的字串量出来是 ascender+descender(约 1.0 em),会把字号算大 30%。上表一律只量首个大写字母。

## 10b. 字体许可 —— 可以内置,但有中文的坑

`Google Sans Flex` **已在 `google/fonts` 仓库里,走 SIL Open Font License 1.1**(`ofl/googlesansflex/OFL.txt`,`METADATA.pb` 的 `license: "OFL"`,`date_added: 2024-11-21`)。版权行是 `Copyright 2015 The Google Sans Flex Authors`,**没有 Reserved Font Name 子句**,所以随软件打包分发合法,开源项目可用。变量轴:`GRAD, ROND, opsz, slnt, wdth, wght`。

**坑:它没有中文子集。** `METADATA.pb` 的 subsets 只有 `latin / latin-ext / vietnamese / math / symbols / menu / cherokee / canadian-aboriginal / nushu / syriac / tifinagh` —— **没有 chinese-simplified**。UnitedU 面向国行电视、界面以中文为主,内置 Google Sans Flex 只会管到英文和数字,中文仍落回系统字体(Noto Sans CJK / 思源黑体)。这会造成中英混排两套字形,**属于对照表里要决定的一项**,不是拿来就能用。

## 10c. 直接从 APK 资源读到的参数(2026-09-21;比像素量测更准,优先用)

**来源**:镜像自带的旧版 launcherx 1.0.595789376(`TVLauncherXPrebuilt.apk`),**资源名未混淆**;`aapt2 dump resources` 读值、`aapt2 dump xmltree --file res/<x>.xml` 读动画文件。目标版 1.0.976298245 的资源名全部混淆、且不再有 fraction 类缩放资源,**所以「时长/曲线」以旧版资源为准,「倍率/尺寸」以目标版像素实测为准**,两者冲突时目标版赢。

| 参数 | 值 | 资源名 |
|---|---|---|
| 卡片聚焦动画 | `scaleX`/`scaleY` 1 → 1.1,**150 ms**,无 `interpolator` 属性 = 平台默认 `AccelerateDecelerateInterpolator`:`f(t)=cos((t+1)π)/2+0.5`;失焦是镜像,同为 150 ms | `animator/card_focus`、`card_unfocus` → `dimen/default_card_focused_scale`、`integer/default_focused_animation_duration_ms` |
| 应用卡聚焦倍率 | 旧版 **1.14**;目标版像素实测 **1.105**(152→168 px)——用后者 | `fraction/app_card_focused_scale`(另有 `vanilla_installed_app_card_focused_scale`、`magic_app_focused_scale`、`lb_focus_zoom_factor_medium` 同为 1.14) |
| 聚焦描边宽 | **2 dp**(与像素量到的 4 px 一致,互相印证了量法) | `dimen/card_focused_frame_outer_stroke_width` |
| 聚焦抬升 | 0 → 4 dp | `dimen/card_base_elevation`、`card_focused_elevation` |
| 顶栏项动画 | 聚焦 **100 ms** / 失焦 **200 ms** | `integer/top_nav_animation_duration_focus`、`_unfocus` |
| tab 切换 | 聚焦 300 ms / 切换 700 ms | `integer/tab_focus_duration`、`tab_switch_duration` |
| 其它卡型倍率(参考) | 内容卡 1.10、Live TV 1.08、YouTube 1.08、image-only 1.04、promotion 1.04、OEM banner 1.02 | `fraction/*_card_focused_scale` |

**能用什么、不能用什么**:上表这类**参数**是事实,直接用;APK 里的**素材文件**(drawable、布局 XML 原文件、Google TV logo/字标)是 Google 的专有作品,UnitedU 是公开的 Apache-2.0 仓库,**不得拷入**。实际上也用不着:界面由 Compose 代码按数值绘制,图标走 Google 自己开源的 Material 图标(Apache-2.0),字体是 OFL 的 Google Sans Flex。

**教训**:此前两次用抓帧去量 Google 的焦点动画时长都失败,最后用了占位值——而答案一直在 APK 里。**以后「照 Google」的取数顺序:先翻 APK 资源 → 再量像素 → 都不行才用占位值并注明。**

## 10d. 浏览缓动与焦点柔光(2026-09-21 第二次取数,owner 报「动效依然很不一样」后)

owner 在模拟器上对比 Google TV 后报:「动效依然是很不一样的,比如上下滚动时页面内容的动效,焦点所在应用卡片或按钮的缓慢放大效果,都不一样。」以下是逐项查证结果。

### 结论一:焦点缩放我们本来就对,不是差异来源

从 §10c 同一份旧版 APK 再核一遍,并补上目标版的静态像素实测:

| 项 | Google | UnitedU(gtv 线) | |
|---|---|---|---|
| 时长 | `default_focused_animation_duration_ms = 150`,`card_focused_animation_duration_ms` 与 `button_focused_animation_duration_ms` 都别名到它 | `FOCUS_FADE_IN_MS`/`OUT_MS` = 150 | ✅ |
| 曲线 | `animator/card_focus`/`card_unfocus` **不写 `interpolator` 属性** → 平台默认 `AccelerateDecelerate` = `cos((t+1)π)/2+0.5` | `Theme.AppFocusEasing` 同式 | ✅ |
| 倍率 | 目标版静态实测 ×1.10(Live TV 磁贴包围盒 152→168 px) | `APP_FOCUS_SCALE = 1.105` | ✅ |

所以 owner 说的「缓慢放大不一样」不在时长、曲线或倍率上 —— 见结论三。

### 结论二:浏览位移的缓动,我们用错了曲线

APK 里有一条**专门命名给浏览用**的插值器:

- `anim/tv_easing_browse` = `pathInterpolator(controlX1=0.18, controlY1=1, controlX2=0.22, controlY2=1)`
- 设计 token `interpolator/gtvm3_sys_motion_easing_browse` = `cubic-bezier(0.2, 1, 0.2, 1)`(同一条曲线的取整版)

它是**极硬的减速**:y1 在 x1=0.18 处就已经到 1,意味着位移一开始就冲出去、随后拖一条很长的渐近尾巴。我们原先用的是 `Theme.MotionEasing = CubicBezierEasing(0, 0, 0.2, 1)`(等同 Material 的标准减速)+ 300 ms(等同 `material_motion_duration_long_1`,与 browse 无关的通用值)。两条曲线的手感差别很大。

顺带抄下同一份 APK 里 gtvm3 的整套缓动 token,后续要照 Google 时直接查表:

| token | cubic-bezier |
|---|---|
| browse | 0.2, 1, 0.2, 1 |
| standard | 0.2, 0, 0, 1 |
| standard_accelerate | 0.3, 0, 1, 1 |
| standard_decelerate | 0, 0, 0, 1 |
| emphasized_accelerate | 0.3, 0, 0.8, 0.2 |
| emphasized_decelerate | 0.1, 0.7, 0.1, 1 |
| enter | 0.1, 1, 0.4, 1 |
| exit | 0.4, 1, 0.1, 1 |
| linear | 0, 0, 1, 1 |

**时长没拿到逐字证据**:`tv_easing_browse` 只被代码引用,全 APK 没有任何 XML 引用它(按资源 ID 的小端字节在 `res/*.xml` 里全量搜过,0 命中),所以查不到它配的 duration。APK 里唯一以 browse 命名的时长是 `lb_browse_rows_anim_duration = 250`,设计 token `gtvm3_sys_motion_duration_medium1` 也是 250 —— 取 250,并在代码 KDoc 里注明证据强度弱于缓动。

### 结论三:焦点柔光我们完全没画,这才是「感觉不到动效」的主因

模拟器静态实测(1920×1080 @ density 2.0;Google 的 76 dp 圆形 app 磁贴,聚焦后 84 dp,描边峰值在半径 43 dp 处)。取聚焦帧与未聚焦帧的同点亮度之差,单位 /255:

| 半径 dp | 46 | 49 | 52 | 55 | 58 | 61 | 64 | 67 | 70 | 73 |
|---|---|---|---|---|---|---|---|---|---|---|
| 高出未聚焦 | +44 | +36.5 | +33 | +30 | +26.6 | +23.6 | +20.7 | +18 | +15.9 | +13.7 |

正上方与正左方两条剖面几乎重合,排除了标签文字与邻居磁贴的干扰(未聚焦侧的底色稳定在 14/255)。换算成「超出描边外缘(半径约 44 dp)的距离 d」:d=2→+44、d=29→+13.7,近似指数衰减,半衰期约 16 dp,铺到 d≈30 dp 仍未归零。

我们的 `gtvAppFocusFrame` 只画了一圈 2 dp 描边。**Round 4 之后 owner 说「你说已经修好了,但我完全感觉不到」,当时归因为「2 dp 细环在 190 ms 内淡入,沙发距离下不可见,连续性要靠大面积位移」—— 归因方向对,但漏了 Google 其实同时在画一层直径两倍于磁贴的柔光。**§5 里「2 dp 描边 + 柔光」这句话一直写着,只是实现时只落了前半句。

### 方法:这台 AVD 量不了 ±50 ms 的动画时长

本轮在模拟器上试了三种抓帧法,都不足以分辨 250 与 300 ms,记下来免得下次重走:

1. `animator_duration_scale` 放大 30 倍:**launcherx 的行滚动不吃这个倍率**(走 RecyclerView 的 scroller,不是 ValueAnimator),30× 下第一帧就已走完。
2. `adb exec-out screencap -p` 轮询:单次往返 1.2–1.5 s,而且连发会把模拟器压到掉帧,动画本身跟着变慢,测出来的时长偏长一个数量级。
3. `screenrecord --output-format=frames`:格式已摸清 —— **每帧 20 字节头(4 字节 size + w/h/rowstride/bpp 各 4 字节)+ RGB888 裸数据**,480×270 时步长 388820 字节。但这台 AVD(swiftshader 软件渲染)实际只跑 30–40 fps 且**帧率在同一次录制内就会飘**,而且 frames 模式只在画面变化时吐帧,所以**帧号 ≠ 时间**,不能乘 16.67 ms。走 adb 管道还会再被带宽卡一道(480×270 裸流 38 MB/s),要录到设备本地 `/sdcard` 再拉回来。

**还有一个自己造的坑**:`settings put global animator_duration_scale 30` 之后再 `settings delete`,**已经在跑的进程不一定重新读**。我因此把 Google 的焦点缩放误测成约 2 秒,差点据此得出「Google 比我们慢 5 倍」的错误结论。清干净的做法是 `settings put … 1.0` + `am force-stop` 目标应用再重启。

## 11. 还没量的(留给下一轮)

1. ~~动画:焦点移动的时长与曲线~~ —— **2026-09-21 已从 APK 资源读到,见 §10c**。
2. 顶栏折叠/展开的触发点与动画。
3. ~~纵向:焦点上下换行时整页怎么位移~~ —— **2026-09-20 已量,见下面 §8b**。

## 12. 方法:资源名被抹掉时怎么办(2026-09-21 重写,推翻原结论)

**原先这一节写的是「新版只能靠 a11y bounds + 像素扫描来量」。那是错的 —— 有不用手量的办法。** Gordon 2026-09-21 直接质疑了这个结论:「资源名出现混淆其实有点难以理解……是不是可以去找找看有没有对照表、相关文档,或者能帮我们解读的方法?而不是因为名字混淆了我们就直接弃用、全靠自己手动去量,手动量效率实在太低了。」他是对的。查证结果:

**它是什么**:`aapt2 optimize --collapse-resource-names`(资源名收拢,为了缩小包体),不是什么新的命名方式。占位串 `0_resource_name_obfuscated` 是 aapt2 自己写进资源表的字面量。清点:新版 1.0.976298245 有 **14845 条被抹名、只剩 13 条有名**;旧版 1.0.595789376 **12446 条全部有名、0 条被抹**。

**四条路,通的是第四条(2026-09-22 补,推翻前一天「只有换镜像」的结论)**:

1. **找公开对照表** —— 不存在。映射只存在于 Google 自己的构建产物(R8 / aapt2 的 resource map),不随 APK 发布。
2. **跨版本按资源 ID 对齐** —— 不通。同 ID 的 dimen 只有 4.5% 值相同(资源集从 12446 长到 14858,ID 在类型内整体错位)。
3. **换一份更新的、名字没收拢的系统镜像** —— 2026-09-21 实际下了 API 36 的 TV 镜像跑通了全套取文件路径(GPT → super → 扫 ext4 主超级块认卷标 → dd 切分区 → `brew install e2fsprogs` 后 `debugfs -R "dump …"`,不用起 AVD 也不用 sdkmanager),**但那份镜像不带 launcherx**:`/product/priv-app` 里是经典 Android TV 桌面 `TVLauncher`,37 处 launcherx 字符串全在权限配置 XML 里。清单里 arm64 只有 API 31/33/34/36,android-34 仍是唯一带 launcherx 的镜像。这条路本身没错,只是没有更新的货。
4. **利用「顺序还在」做序列对齐 —— 通,而且是正解**。Gordon 2026-09-22 拒绝接受「走到头」的结论,要求专攻;重新审视后发现一条被漏掉的硬线索:**aapt2 给同一类型的资源分配 ID 时严格按名字字母序**(旧版 dimen/integer/fraction/string/color 五类各 100% 单调)。名字被抹掉,**顺序还在**——新版那 2140 条 dimen 仍按真名的字母序排列。于是两版之间是一道序列对齐题:值相同且顺序一致的条目做 LCS(最长公共子序列),名字直接传过去;对不上的新条目,名字也被夹在相邻两个已匹配名字之间。

   **验证**:新版残留的 3 条真名 string 当地面真值,**3/3 通过**(两条精确命中,一条未匹配但上下界正确夹住)。结果:integer 对上 200/246(81%)、fraction 106/126(84%)、dimen 1339/2140(63%)。工具 `scripts/research/align_launcherx_resources.py`,产物 `docs/research/launcherx-1.0.976298245-named-resources.md`。

   **一个漂亮的旁证**:fraction 里有一条**新版新增、旧版没有**的条目 0x7f0a0081,值 1.10,名字夹在 `spotlight_shadow_alpha_min` 与 `topic_banner_focused_scale` 之间——正是像素实测到的那个 1.10;而旧版的 `app_card_focused_scale` 在新版里仍是 1.14。说明"Your apps"行的圆形磁贴用的是一条新加的资源,§10c 当时按旧版名字找到的 `app_card_focused_scale` 从来不是它,是像素实测救了场。**名字帮你找候选,实测确认用的是哪一条,两者缺一不可。**

   **局限,如实记**:①匹配靠"值相等",同一区间内若有多条值相同的条目,名字可能在它们之间错位——但值本身不会错(只匹配相等值),所以"X 的值是多少"这类查询仍可靠,除非 X 恰好改了值又被同值邻居顶替;②「同名改值」候选表只报锚点间旧新未匹配数相等且单位一致的,即便如此仍需人工核(`top_nav_icon_size 30→86dp` 这种一看就不对的也会进表);③ string 只对上 4.9%,因为英文文案在 `split_config.en.apk` 里、base 里几乎没有——不影响,我们不查 string。

**取数顺序据此修订**(替换 §10c 末尾那条):**先查对齐表 `launcherx-1.0.976298245-named-resources.md`(目标版自己的值、带名字)→ 表里没有或标了「新增」的,用旧版名字定位候选 + 目标版像素实测确认 → 占位值并注明**。像素实测只用来解决「两版之间确实变了」的那几项(例如 app 卡聚焦倍率旧版 1.14、目标版 1.105),不再用来问「Google 这个参数是多少」。

## 附:截图

全部归档在 **`docs/screenshots/gtv/`**,逐张说明见该目录的 `INDEX.md`。
