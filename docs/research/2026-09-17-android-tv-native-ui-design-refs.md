# Android TV / Google TV 原生 UI 设计一手参考(2026-09-17)

目的:为 UnitedU「纯美学打磨」提供可引用的一手数值。范围限定视觉层(留白、卡片、焦点、字号、颜色、动效),不涉及功能。

取材原则:
- 只收 developer.android.com / androidx 源码 / developer.apple.com / 各项目官方仓库与官网;媒体报道只在官方文本缺席时补位,并明确标注。
- 数字找不到一手来源的,写「未在一手来源找到」,不猜。
- 英文术语与引文保留原文。
- 单位换算:Android TV 1080p 面板 = `xhdpi`(2×),所以 1920×1080 px = 960×540 dp([Leanback layouts](https://developer.android.com/training/tv/playback/leanback/layouts))。tvOS 在 1920×1080 上 1 pt = 1 px(@1x),所以 **Apple 的 80 pt ≈ Android 的 40 dp**。UnitedU 现有常量以 dp 计,下文对照时按此换算。

---

## 1. Google 官方 TV 设计指南(developer.android.com/design/ui/tv)

### 1.1 页面结构与可用性

指南入口 https://developer.android.com/design/ui/tv 下实际存在的子页(2026-09-17 逐一访问):

| 子页 | URL | 状态 |
|---|---|---|
| Foundations / Design for TV | https://developer.android.com/design/ui/tv/guides/foundations/design-for-tv | 可用 |
| Styles / Color system | https://developer.android.com/design/ui/tv/guides/styles/color-system | 可用 |
| Styles / Layouts | https://developer.android.com/design/ui/tv/guides/styles/layouts | 可用(数值最多) |
| Styles / Typography | https://developer.android.com/design/ui/tv/guides/styles/typography | 可用(无数值,指向 M3) |
| Components / Buttons, Cards, Featured carousel, Immersive list, Lists, Navigation drawer, Tabs | https://developer.android.com/design/ui/tv/guides/components/{buttons,cards,featured-carousel,immersive-list,lists,navigation-drawer,tabs} | 可用 |
| System / TV app icon guidelines | https://developer.android.com/design/ui/tv/guides/system/tv-app-icon-guidelines | 可用 |
| Figma 设计包 | https://www.figma.com/@tv(页面内链接写作 https://goo.gle/tv-desing-kit,原文如此) | 未打开(需 Figma 账号) |

**不存在的页面**(均 404):`foundations/layouts`、`foundations/focus-and-selection`、`foundations/focus-system`、`foundations/overscan`、`styles/color`、`styles/motion`、`design/ui/tv/guides/components/cards` 之外的任何 motion 页。结论:**Google 的 TV 指南没有独立的 focus 页和 motion 页**;焦点数值散落在 Buttons / Immersive list 两页,动效数值只能从 `androidx.tv.material3` 源码取(见 §2)。

### 1.2 10-foot UI 与暗色默认的依据

- 观看距离:"the average distance between a TV and its viewers is 3 meters (10 feet)" — [design-for-tv](https://developer.android.com/design/ui/tv/guides/foundations/design-for-tv)
- 输入反馈:"TV UI must provide instant and distinct feedback when buttons are pressed" — 同上
- 内容优先:"Great TV design is all about putting content front and center" — 同上
- 暗色:"Consider using a **dark theme** to enhance your cinematic TV experience." — [color-system](https://developer.android.com/design/ui/tv/guides/styles/color-system)
- 无壁纸动态色:"Android TV does not support wallpaper"(因此不支持 user-generated schemes),建议改用 **content-based color schemes**("movie posters, album art, and hero images" + Material Color Utilities)— 同上
- 对比度阈值:该页只说 adheres to Material color guidelines,**具体对比度数字未在一手来源找到**。

### 1.3 安全区(overscan)与网格 — 数值最完整的一页

来源:[styles/layouts](https://developer.android.com/design/ui/tv/guides/styles/layouts),下列为逐句原文:

- 设计基准:"Since a modern TV has a 16:9 aspect ratio, it is recommended to design your app with a **960px x 540px** screen size."
- 保护边距(推荐):"To keep your content and information safe, use a 5% margin layout (**58dp on the sides and 28dp on the top and bottom edges**)."
- 最低安全区:"position the elements with a 5% margin of **48dp on the left and right sides, and 27dp on the top and bottom** of a layout."
- 推导:"960 * ~5% = 48dp / 540 * ~5% = 27dp round off to 24dp"
- 网格:"Use **12 columns that are 52dp wide with 20dp of space** between them. There needs to be 58dp of space on both sides and **4dp of vertical spacing between lines**."
- 三条原则标题:"Design for large screens" / "Ensure visibility and overscan safety" / "Optimize with axes"

同一数值在开发文档的措辞([Leanback layouts](https://developer.android.com/training/tv/playback/leanback/layouts)):"Adding a 5% margin of 48 dp on the left and right edges and 27 dp on the top and bottom edges to a layout helps ensure that screen elements in the layout are within the overscan-safe area." 并附:"Don't adjust background screen elements that the user doesn't directly interact with, and don't clip the elements to the overscan-safe area." — 即**背景铺满、前景内缩**。密度表:720p → `tvdpi`,1080p → `xhdpi`,4K → `xxxhdpi`。

换算成 UnitedU 的 1920×1080 px 画面:48 dp = 96 px,27 dp = 54 px,58 dp = 116 px。

### 1.4 卡片(Cards)

来源:[components/cards](https://developer.android.com/design/ui/tv/guides/components/cards)

- 五种变体:Standard / Classic / Compact / Wide standard / Wide classic;每种 anatomy 都是 "1. Image 2. Content block"。
- 宽高比(原文):
  - 16:9 — "the most common aspect ratio for cards. It is a wide aspect ratio that is well-suited for displaying images and videos"
  - 1:1 — "a good choice for cards that need to be visually balanced, such as cast and crew, channel logos, or team logos"
  - 2:3 — "a taller aspect ratio. It is a good choice if you want to break up the grid and bring more emphasis"
- 每行卡数 → 卡宽(dp,960 基准,与 layouts 页一致):

  | 每行可见 | 卡宽 |
  |---|---|
  | 1 | 844 dp |
  | 2 | 412 dp |
  | 3 | 268 dp |
  | 4 | 196 dp |
  | 5 | 124 dp |

- 卡间距:"Varying card widths based on the number of cards visible on the screen can be achieved by implementing proper **peaking with a spacing of 20dp**."
- 文字块宽度:"The width of the content block in a card should be the same width as the image thumbnail."
- Compact 卡的图上文字:"add a semi-transparent black gradient overlay"
- **圆角、焦点表现、标题字号:该页文本未给数字**(只在 Figma 内),取 §2 源码默认值。

### 1.5 焦点表现(散落在组件页)

- Buttons:"Container **scales by 1.1x** on focus, maintaining the internal padding." Text/icon 按钮 "fully rounded corners";Wide/image 按钮 "rounded containers of **12dp**";Outlined 按钮聚焦 "the container gets a fill color along with outline"。— [components/buttons](https://developer.android.com/design/ui/tv/guides/components/buttons)
- Immersive list:聚焦卡 **1.1x** 放大,并以 border 与 elevation 作为提示;背景图建议 16:9;图片 anatomy 为 "Cinematic scrim / Poster / Background color";构图 "Scale and align the subject to the top right corner creating a cinematic experience"。— [components/immersive-list](https://developer.android.com/design/ui/tv/guides/components/immersive-list)
- Featured carousel:两种变体 Immersive / Card;anatomy 含 "cinematic scrim";**自动轮播间隔、渐变参数未在页面文本中**(见 §2 源码:5000 ms)。— [components/featured-carousel](https://developer.android.com/design/ui/tv/guides/components/featured-carousel)
- Tabs:指示器两种 "Pill"(整页级导航)/ "Bar"(内容区内);状态 Default / Focused / Selected;切换时 "the content below also slides left or right"。— [components/tabs](https://developer.android.com/design/ui/tv/guides/components/tabs)
- Navigation drawer:3–7 个目的地;展开显示 icon+text,收起只显示 icon;宽度数值只在图里,取 §2 源码(56 / 256 dp)。— [components/navigation-drawer](https://developer.android.com/design/ui/tv/guides/components/navigation-drawer)
- Lists:One/Two/Three-line;高度只在图里,取 §2 源码。— [components/lists](https://developer.android.com/design/ui/tv/guides/components/lists)
- **焦点动画时长、缓动:指南页面未给**,见 §2.3。

### 1.6 排版

来源:[styles/typography](https://developer.android.com/design/ui/tv/guides/styles/typography)

- "The TV Design type scale is a combination of **15 styles**"(Display/Headline/Title/Body/Label × Large/Medium/Small)。
- 默认字体 **Roboto**;选字要求 "large counters and apt optical sizing";正文与标签用 sans-serif;避免 decorative fonts 做正文。
- "Prioritize using larger typography for a more comfortable viewing experience on TV screens."
- **该页不给 sp 数值**,指向 M3 tokens;实际值取 §2.5 的 `TypeScaleTokens`(与 M3 手机端同值)。
- **TV 最小可读字号:未在一手来源找到**(页面只有上面那句定性建议)。

---

## 2. Compose for TV Material(`androidx.tv.material3`)默认值 — 来自源码

页面 https://developer.android.com/reference/kotlin/androidx/tv/material3/package-summary 对抓取工具只返回导航骨架,故直接读 androidx 仓库 `androidx-main` 分支源码(路径前缀 `https://raw.githubusercontent.com/androidx/androidx/androidx-main/tv/tv-material/src/main/java/androidx/tv/material3/`,下文以 `…/文件名` 简写)。培训页 [training/tv/playback/compose](https://developer.android.com/training/tv/playback/compose) 对这套组件的定位:"designed for the living room, with clear focus indicators and remote-friendly input behavior";依赖 `androidx.tv:tv-material:1.0.0`,minSdk 21。

### 2.1 Card(`…/Card.kt`)

| 项 | 默认值 | 出处 |
|---|---|---|
| 容器形状 | `RoundedCornerShape(8.dp)` | `CardDefaults.ContainerShape` |
| 容器色 | `MaterialTheme.colorScheme.surfaceVariant` | `CardDefaults.colors()` |
| 聚焦容器色 | = 未聚焦(不变色) | 同上 |
| 缩放 | `scale = 1f`,`focusedScale = 1.1f`,`pressedScale = scale` | `CardDefaults.scale()` |
| 边框 | 默认 `Border.None`;**聚焦 `BorderStroke(width = 3.dp, color = MaterialTheme.colorScheme.border)`**,形状同容器 | `CardDefaults.border()` |
| 光晕 | 默认 `Glow.None`(聚焦也无) | `CardDefaults.glow()` |
| 副标题 / 描述透明度 | `SubtitleAlpha = 0.6f`,`DescriptionAlpha = 0.8f`,字号 `bodySmall` | 文件末尾常量 |
| CompactCard 文字压底渐变 | `Color(28,27,31,alpha=0)` → `Color(28,27,31,alpha=204)`(即 #1C1B1F 0% → 80%) | `CardDefaults` 内 Brush |

结论:**官方卡片的焦点语言是「放大 1.1 + 3 dp 边框」,不是光晕**;光晕是可选项且默认关闭。

### 2.2 ClickableSurface(`…/SurfaceDefaults.kt`)— 所有可点组件的底座

- 形状 `MaterialTheme.shapes.medium`(= 12 dp,见 §2.4)
- 颜色:容器 `surface`;**聚焦容器 `inverseSurface`**(暗色主题下 = 浅色 #E6E1E5,即「聚焦反白」)
- 缩放:`focusedScale = 1.1f`
- 边框:聚焦 `BorderStroke(width = 2.dp, color = MaterialTheme.colorScheme.border)`,`inset = 0.dp`,`shape = ShapeDefaults.Small`
- 光晕:`Glow.None`

### 2.3 焦点动效(`…/SurfaceScale.kt` + `…/tokens/SurfaceTokens.kt`)

```kotlin
internal object SurfaceScaleTokens {
    const val focusDuration: Int = 300
    const val unFocusDuration: Int = 500
    const val pressedDuration: Int = 120
    const val releaseDuration: Int = 300
    val enterEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)
}
```

`tvSurfaceScale` 用 `animateFloatAsState(tween(duration, easing = enterEasing))` 驱动 `graphicsLayer(scaleX, scaleY)`。要点:**进焦 300 ms、失焦 500 ms(失焦更慢)、按下 120 ms、四种情形共用同一条减速曲线 (0,0,0.2,1)**(即 Material 的 standard decelerate)。

### 2.4 Glow / Border / Shape / Elevation

- `Glow(elevationColor: Color, elevation: Dp)`;`Glow.None = Glow(Color.Transparent, 0.dp)` — `…/Glow.kt`
- 光晕实现(`…/SurfaceGlow.kt`):模糊半径 = `glow.elevation.toPx()`,颜色 = `calculateSurfaceColorAtElevation(elevationColor, elevation)`;**仅 API 28+ 绘制**(`ifElse(API_28_OR_ABOVE, …)`)。
- `Border(border: BorderStroke, inset: Dp = 0.dp, shape: Shape = ShapeTokens.BorderDefaultShape)`;`Border.None` = 0 dp 透明 — `…/Border.kt`
- `ShapeTokens`(`…/tokens/ShapeTokens.kt`):ExtraSmall 4 dp / Small 8 dp / Medium 12 dp / Large 16 dp / ExtraLarge 28 dp / Full = CircleShape
- `Elevation`(`…/tokens/Elevation.kt`):Level0–5 = 0 / 1 / 3 / 6 / 8 / 12 dp

### 2.5 Typography(`…/tokens/TypeScaleTokens.kt`;字体族 `…/tokens/TypefaceTokens.kt` 全为 `FontFamily.SansSerif`,设备上即 Roboto)

| Token | 字号 | 行高 | 字重 | 字距 |
|---|---|---|---|---|
| displayLarge | 57 sp | 64 | Regular | -0.2 |
| displayMedium | 45 | 52 | Regular | 0 |
| displaySmall | 36 | 44 | Regular | 0 |
| headlineLarge | 32 | 40 | Regular | 0 |
| headlineMedium | 28 | 36 | Regular | 0 |
| headlineSmall | 24 | 32 | Regular | 0 |
| titleLarge | 22 | 28 | Regular | 0 |
| titleMedium | 16 | 24 | Medium | 0.2 |
| titleSmall | 14 | 20 | Medium | 0.1 |
| bodyLarge | 16 | 24 | Regular | 0.5 |
| bodyMedium | 14 | 20 | Regular | 0.2 |
| bodySmall | 12 | 16 | Regular | 0.2 |
| labelLarge | 14 | 20 | Medium | 0.1 |
| labelMedium | 12 | 16 | Medium | 0.5 |
| labelSmall | 11 | 16 | Medium | 0.5 |

注意:这套值与手机端 M3 完全相同(文件头注明 "Inspired by androidx.compose.material3.tokens v0_103"),**TV 库并没有放大字号**;放大靠 dp 在 xhdpi 上 ×2 实现(12 sp = 24 px)。

### 2.6 darkColorScheme 默认(`…/ColorScheme.kt` → `…/tokens/ColorDarkTokens.kt` → `…/tokens/PaletteTokens.kt`)

| 角色 | 调色板 | RGB | Hex |
|---|---|---|---|
| background / surface | Neutral10 | 28,27,31 | #1C1B1F |
| onBackground / onSurface | Neutral90 | 230,225,229 | #E6E1E5 |
| surfaceVariant(卡片容器) | NeutralVariant30 | 73,69,79 | #49454F |
| onSurfaceVariant | NeutralVariant80 | 202,196,208 | #CAC4D0 |
| **border(聚焦边框色)** | NeutralVariant60 | 147,143,153 | #938F99 |
| borderVariant | NeutralVariant30 | 73,69,79 | #49454F |
| inverseSurface(聚焦反白) | Neutral90 | 230,225,229 | #E6E1E5 |
| inverseOnSurface | Neutral20 | 49,48,51 | #313033 |
| primary | Primary80 | 208,188,255 | #D0BCFF |
| onPrimary | Primary20 | 56,30,114 | #381E72 |
| primaryContainer | Primary30 | 79,55,139 | #4F378B |
| scrim | Neutral0 | 0,0,0 | #000000 |

### 2.7 其他组件默认(备查)

- Button(`…/ButtonDefaults.kt`):MinWidth 58 dp、MinHeight 40 dp、形状 `CircleShape`(胶囊)、内边距 16 dp 横 / 10 dp 纵、图标 20 dp、图标间距 8 dp、`focusedScale = 1.1f`、边框默认 None、光晕 None。
- ListItem(`…/ListItemDefaults.kt`):形状 8 dp、图标 32 dp(dense 20)、内边距 16/12(dense 12/10)、聚焦边框 2 dp、最小高度 48 / 56(带图标)/ 64(两行)/ 80(三行);dense 40 / 40 / 56 / 72。
- NavigationDrawer(`…/NavigationDrawerItemDefaults.kt`):图标 24 dp、收起 56 dp、展开 256 dp、条目高 48(一行)/ 56(两行)、聚焦边框 2 dp、`animateContentSize` 展开。
- Carousel(`…/Carousel.kt`):`TimeToDisplayItemMillis = 5000`;内容切换 `fadeIn(tween(100)) with fadeOut(tween(100))`;指示点 8 dp、点距 8 dp、位于 `BottomEnd` 内缩 16 dp。
- TabRow(`…/TabRow.kt`):容器透明;tab 间 8 dp;Pill 指示器 active = `onSurface`,inactive = `secondaryContainer.copy(alpha = 0.4f)`;宽度/位移用 `animateDpAsState`,颜色 `animateColorAsState`。

### 2.8 官方样例(github.com/android/tv-samples)

- 仓库 README 列出:AccessibilityDemo、ClassicsKotlin、**TvMaterialCatalog**("demonstrates how to implement Material Design principles using Compose for TV")、**JetStreamCompose**、Leanback、LeanbackShowcase、ReferenceAppKotlin — https://github.com/android/tv-samples
- JetStreamCompose:自述 "A sample media streaming app that demonstrates the use of TV Compose with a typical Material app and real-world architecture";演示 TabRow、TvLazyRow/Column、TvVerticalGrid、ImmersiveList、Carousel、Cards、Chips、Dialogs、ListItem、Surface;**深色主题**;Apache-2.0 — https://github.com/android/tv-samples/tree/main/JetStreamCompose
- 对 UnitedU 的价值:JetStream 的首页是「顶部 TabRow + 大幅 Carousel + 多行 16:9 卡片」,正是 Google 心目中的原生形态;TvMaterialCatalog 可用来**对着看每个默认焦点效果实际长什么样**。

---

## 3. Google TV 首页(实际产品)

**说明**:Google 对首页视觉没有公开 spec,以下把「官方文本」与「媒体截图观察」分开标注。

### 3.1 官方文本能确认的

- 2020-09-30 发布文([blog.google](https://blog.google/products/chromecast/best-chromecast-google-tv)):"Google TV's **For You tab** gives you personalized watch suggestions from across your subscriptions organized based on what you like to watch";"Google TV's **Watchlist** lets you bookmark movies and shows";Live tab "see what's on now and the full channel guide"。
- Apps only 模式([Google TV Help 10070784](https://support.google.com/googletv/answer/10070784)):"In Apps only mode, you don't get personalized recommendations on your home screen. You'll find a list of installed apps that you can open to find something to watch. You'll also find sponsored content and teasers for popular movies and shows." — 即使关掉推荐,**赞助内容仍在**;这是 UnitedU「零广告」定位的直接对照。
- 屏保 / Ambient([Google TV Help 10070821](https://support.google.com/googletv/answer/10070821)):来源三选 "Google Photos" / "Art gallery" / "Custom AI Art";"Slideshow speed" 可调;"On-screen Info" 可开关 "Time" / "Weather" / "Attribution" / "Device Info"。**空闲多久触发:该页未写。**
- 圆形图标改版(2023-11):官方文出自 Android Developers Medium《Squaring the Circle on Google TV》 https://medium.com/androiddevelopers/squaring-the-circle-on-google-tv-e1ee37fe247e(本次抓取被 Cloudflare 403 拦截,以下引文经 [9to5google 2023-11-10](https://9to5google.com/2023/11/10/google-tv-homescreen-redesign-app-icons-2024/) 转述):Google 称 "smaller, circular app icons on Google TV's 'For You' tab will make it easier to users to set their favorites";要求所有应用提交 **square app icon**;banner 仍用于 Apps 页等处;时间 "early 2024"。9to5 自己的观察:改版前 Your apps 行最多 12 个应用即溢出到 "See All"。
- "Your apps" 行新外观的官方公告在 Google TV Community 线程 https://support.google.com/googletv/thread/261005780/ (标题 "A new look for 'Your apps' row";正文抓取失败),[Android Police](https://www.androidpolice.com/google-tv-home-screen-redesign-your-apps-row/) 转述 Google 说法:"increased the number of apps in the Your apps section, while also adding reorder and add apps buttons"。

### 3.2 截图观察(非 spec,来自 9to5google,2025 改版)

- 顶栏:"For You" 改名 "Home";"The 'Live' and 'Apps' tabs then appear in a **pill-shaped box** alongside a search button. In a second box, buttons for settings and the screensaver appear." — [9to5google 2025-09-19](https://9to5google.com/2025/09/19/google-tv-homescreen-redesign-test-gallery/)
- 头像下拉:"profile switcher and four buttons — Watchlist, Library, Your services, and Content preferences" — 同上
- Watchlist / Library 变成 "siloed interfaces with a large header and a horizontally-scrolling list of content" — [9to5google 2025-11-13](https://9to5google.com/2025/11/13/google-tv-homescreen-redesign-2025/)
- Ambient 卡片改小并移到底部,"allows more cards and information to be displayed on the screen without obscuring the background" — [XDA](https://www.xda-developers.com/google-tv-personalized-cards-rolling-out/)(非官方)
- **hero 背后的模糊/渐变参数、应用卡实际像素尺寸、焦点放大倍率:未在一手来源找到。**唯一的官方"形态"描述是设计指南里 Immersive list / Carousel 的 "cinematic scrim"(§1.5)。

---

## 4. Android TV 应用 banner / 图标规范

来源:[training/tv/start/start](https://developer.android.com/training/tv/start/start) 与 [tv-app-icon-guidelines](https://developer.android.com/design/ui/tv/guides/system/tv-app-icon-guidelines)

- Banner:"use an xhdpi resource with a size of **320 x 180 px**. **Text must be included in the image.** If your app is available in more than one language, you must provide separate versions of the banner with text for each supported language." 清单里 `android:banner="@drawable/banner"`。
- 首页用哪一个:"Depending on the Android TV device, either the icon or banner is used as the app launch point that appears on the home screen in the apps and games rows."
- Banner 16:9 各密度:mdpi 160×90 / hdpi 240×135 / xhdpi 320×180 / xxhdpi 480×270 / xxxhdpi 640×360(目录 `mipmap-*`)。
- Launcher icon 1:1 各密度:80 / 120 / 160 / 240 / 320 px;adaptive icon 两层 "a foreground and a background layer";"The **72 x 72 safe zone** … shows where your icon and foreground layers are never be clipped by a shaped mask."
- 主题化图标:"Android or Google TV don't support themed icons."
- **启动器给 banner 加多大圆角:未在一手来源找到**(UnitedU 现用 13.75 dp,系对 Projectivy 「圆角 40%」的实测拟合,见 `Theme.kt`)。

对 UnitedU 的含义:banner 是 320×180 的位图、文字已烧在图里,所以**任何对 banner 的缩放都会让烧入文字变糊**;卡宽选择应尽量落在 2× 整数倍(160 dp 卡 = 320 px,正好 1:1 贴图)。

---

## 5. Apple tvOS HIG(作为「优雅」的对照基准,从简)

来源:[designing-for-tvos](https://developer.apple.com/design/human-interface-guidelines/designing-for-tvos)、[focus-and-selection](https://developer.apple.com/design/human-interface-guidelines/focus-and-selection)、[layout](https://developer.apple.com/design/human-interface-guidelines/layout)、[typography](https://developer.apple.com/design/human-interface-guidelines/typography)、[images](https://developer.apple.com/design/human-interface-guidelines/images)、[app-icons](https://developer.apple.com/design/human-interface-guidelines/app-icons)(页面为 JS 渲染,数据取自 `developer.apple.com/tutorials/data/design/human-interface-guidelines/*.json`)

- 观看距离 "often 8 feet or more";设计原则:"Embrace the tvOS focus system, letting it gently highlight and expand onscreen items";"Deliver beautiful, edge-to-edge artwork, subtle and fluid animations".
- 安全区:"Inset primary content **60 points from the top and bottom** of the screen, and **80 points from the sides**."(≈ Android 30 / 40 dp)
- 焦点留白:"Include appropriate padding between focusable elements. … an element gets bigger when it comes into focus … make sure you don't let them overlap important information."
- 网格(未聚焦宽度 / 横向间距 40 pt / 最小纵向间距 100 pt):2 列 860 pt、3 列 560、4 列 410、5 列 320、6 列 260、7 列 217、**8 列 184、9 列 160**。另:"Include additional vertical spacing for titled rows." / "Make partially hidden content look symmetrical."
- 焦点效果:"tvOS generally uses the **parallax effect** to give the focused item an appearance of depth and liveliness";"As an element comes into focus, the system elevates it to the foreground, gently swaying it while applying illumination that makes the element's surface appear to shine. After a period of inactivity, out-of-focus content dims and the focused element expands.";"**Parallax is designed to be almost unnoticeable.**";"Rely on system-provided focus effects." / "Avoid changing focus without people's interaction."
- 分层图:"A layered image consists of **two to five** distinct layers";"Keep the background layer opaque.";"Generally, keep text in the foreground.";"Leave a safe zone around the foreground layers"。App icon 800×480 px,"Layered (Parallax)";"If you include text in a tvOS app icon, make sure it's above other layers so it's not cropped by the parallax effect."
- **焦点放大倍率的具体数字:未在一手来源找到**(HIG 只说 "expands";第三方博客常说 1.05–1.1×,不采信)。
- 字号(tvOS built-in text styles,pt;Weight = Medium 除 Subtitle 1 为 Regular):Title 1 **76/96**、Title 2 57/66、Title 3 48/56、Headline 38/46、Subtitle 1 38/46、Callout 31/38、**Body 29/36**、Caption 1 25/32、**Caption 2 23/30**;系统字体 SF Pro。对比:Apple 最小的 Caption 2 = 23 px,Android TV 的 `bodySmall` 12 sp = 24 px(xhdpi),两家的**最小正文像素高度几乎一致**。

---

## 6. 开源 / 第三方 TV launcher 的视觉做法

只收能验证存在的项目。

### 6.1 FLauncher(开源,Flutter,GPL-3.0)

- 正典仓库 https://gitlab.com/flauncher/flauncher(描述 "Open-source alternative launcher for Android TV, built with Flutter";268 star,最近活动 2026-07-11);GitHub 上 `svrooij/flauncher` 为镜像;任务里给的 `github.com/etiennelaurent/FLauncher` **不存在**(404)。许可:源文件头 "GNU General Public License … version 3"。
- README 功能:"No ads / Customizable categories / Manually reorder apps within categories / Wallpaper support / … / Clock / Switch between row and grid for categories / Support for non-TV (sideloaded) apps"(经 [GitHub 镜像 README](https://raw.githubusercontent.com/svrooij/flauncher/master/README.md))。
- 卡片实现(`lib/widgets/app_card.dart`,https://gitlab.com/flauncher/flauncher/-/raw/master/lib/widgets/app_card.dart):
  - `AspectRatio(aspectRatio: 16 / 9)`;`BorderRadius.circular(8)`
  - 聚焦缩放 `1.1`(`_scaleTransform`),`AnimatedContainer(duration: 200 ms, curve: Curves.easeInOut)`
  - 聚焦阴影:`Material(elevation: hasFocus ? 16 : 0, shadowColor: Colors.black)` — **用投影不用光晕**
  - 未聚焦压暗:`AnimatedOpacity(opacity: hasFocus ? 0 : 0.10)` 的黑色覆盖层(200 ms)
- 行/网格(`category_row.dart`、`apps_grid.dart`):行标题 `textTheme.titleLarge`,左 16 / 下 8;行内卡片横向 padding 8(即卡距 16);网格 `mainAxisSpacing: 16, crossAxisSpacing: 16`。
- 背景(`flauncher.dart`、`gradients.dart`):有壁纸则 `Image.memory(fit: BoxFit.cover)`,否则线性渐变预设,如 "Great Whale" `#6991C7 → #A3BDED`、"Vicious Stance" `#29323C → #485563`;**无模糊、无压暗层**。
- 衍生:Arc Launcher(https://github.com/meddouribadis/arclauncher)、LTvLauncher(https://github.com/LeanBitLab/LtvLauncher)均自述为 FLauncher 定制分支,加 OLED 屏保与流量小组件(据 [Awesome-Android-TV-FOSS-Apps](https://github.com/Generator/Awesome-Android-TV-FOSS-Apps))。

### 6.2 TV Launcher(nielsvanvelzen,开源,Compose,GPL-3.0)

- https://github.com/nielsvanvelzen/tv-launcher;README:"Experimental Android launcher with a focus on big screens like televisions … currently in an early development stage."
- 结构:顶部 "Configurable toolbar"(Clock / Settings / Tv input sources)、"Favorite app list"、"Channels"、"All apps" 网格。
- 明确写死的平台限制:"Android TV does not allow apps to set a wallpaper since there is no implementation for the WallpaperManager actions." — 与 Google color-system 页的 "does not support wallpaper" 互证;UnitedU 自己管壁纸位图是对的路。
- 视觉数值:README 未给,仅一张截图;未读源码。

### 6.3 LeanbackLauncher(tsynik,开源,Kotlin)

- https://github.com/tsynik/LeanbackLauncher;自述 "Leanback on Fire - Google Leanback Launcher on steroids";684 star。README 原文抓取失败(`kotlinx` 分支 raw 404),**视觉细节未验证**;价值在于它是 Google 旧版 Leanback Launcher 的直接后裔,想看「Google 自己怎么写 banner 行焦点缩放」可翻其源码。

### 6.4 Projectivy Launcher(闭源,公开设计)

- Play 包名 `com.spocky.projengmenu`;官网 https://projectivylauncher.com/ ;GitHub `spocky/miproja1` 只有 issue 与 release,无源码。
- 官网原文特性:"tinted & transparent cards"、"square icons & custom alignment"、"Interface adapts to your wallpaper"(adaptive colors)、"Animated Backgrounds: Use GIFs or videos"、"Adjust brightness, contrast, saturation"、"Advanced font customization"(Premium)。
- 卡片焦点三选(glow / drop shadow / none)与聚焦卡尺寸可调的说法来自 XDA 论坛帖 https://xdaforums.com/t/app-android-tv-projectivy-launcher.4436549/(**非官方文本**)。"Blur Dock Creator"(在壁纸里生成一块模糊区托住图标,"Apple TV-like dock")出自官网插件页 https://projectivylauncher.com/plugins.html 。
- UnitedU 的 `CardCorner = 13.75.dp` 注释写明来自 Projectivy 「圆角 40%」的实测拟合,`FocusScale = 1.31f` 来自 v4 参考图实测——即**现有视觉基线本身就是 Projectivy 系,而非 Google 系**。

### 6.5 其他

- ATV Launcher / ATV Launcher Pro(`ca.dstudio.atvlauncher.pro`,闭源,Play)、Wolf Launcher(闭源、只能侧载、多家媒体称已停更)、AT4K、HALauncher、Sideload Launcher 等见 [denilsonsa 的清单 gist](https://gist.github.com/denilsonsa/80cbb3a14b3bf63de75e6bf81c89ee05)。均无公开设计文本,**不作视觉参考**。
- "Sofa TV launcher":未找到可验证的项目,不收录。

---

## 7. 可直接搬进 UnitedU 的数值对照表

UnitedU 现值取自 `app/src/main/java/com/uniteduone/launcher/Theme.kt` 与 `AppCard.kt`(2026-09-17 main)。Android 列以 dp(960×540 基准)计,Apple 列以 pt 计并附换算。

| 项目 | UnitedU 现值 | Google 指南 / tv-material 默认 | Apple tvOS | FLauncher |
|---|---|---|---|---|
| 左右安全边距 | `SidePadding` 84.5 dp | 48 dp 最低 / 58 dp 推荐(§1.3) | 80 pt ≈ 40 dp | 16 px |
| 上下安全边距 | `TopPadding` 195.3 dp(布局量,非边距) | 27 dp 最低 / 28 dp 推荐 | 60 pt ≈ 30 dp | 16 px |
| 卡片比例 | 16:9(127.75 × 71.85 dp) | 16:9 首选;1:1、2:3 备选 | 未规定 | 16:9 |
| 卡宽(每行 N 张) | 4 档随 N 推导 | 4 张 196 dp / 5 张 124 dp | 8 列 184 pt / 9 列 160 pt(≈ 92 / 80 dp) | 由列数推导 |
| 卡间距 | `CardSpacing` 8.9 dp | 20 dp(peaking) | 40 pt ≈ 20 dp | 16 px |
| 行间距 | `RowSpacing` 25.4 dp(卡顶到卡顶 328 px) | 网格行间 4 dp(非卡片行) | 最小 100 pt ≈ 50 dp,带标题需更多 | 未读 |
| 聚焦放大 | **1.31** | **1.1**(指南 + 源码一致) | 未给数字("expands") | 1.1 |
| 放大时长 / 缓动 | `tween(180)`,默认缓动 | 进 300 ms / 出 500 ms / 按下 120 ms,`CubicBezier(0,0,0.2,1)` | 未给 | 200 ms `easeInOut` |
| 焦点边框 | 无 | Card 3 dp `#938F99`;Surface 2 dp | 无(parallax + 抬升) | 无 |
| 焦点光晕 / 阴影 | 光晕半径 6.8 dp,呼吸 2500 ms | 默认 `Glow.None`;API 28+ 才画 | 系统抬升 + 高光 | 投影 elevation 16 |
| 未聚焦压暗 | 邻居 0.09 / 0.06 / 0.05 | 无 | "out-of-focus content dims"(闲置后) | 黑 10% |
| 圆角 | 13.75 dp | Card 8 dp;ClickableSurface 12 dp | 未给 | 8 px |
| 卡片标题字号 | 13 sp | 副标题 `bodySmall` 12 sp @ α0.6;标题 `titleMedium` 16 sp | Caption 2 23 pt(≈ 11.5 sp) | `titleLarge`(行标题) |
| 背景基色 | 壁纸 + 主题色 | `#1C1B1F`(surface),压底渐变 #1C1B1F 0→80% | 黑/系统背景 | 渐变或壁纸 |
| Banner 贴图 | 320×180 px 缩放 | 320×180 px xhdpi,文字烧入 | 800×480 px 分层图 | 320×180 |

**读表要点(机制,不是现象)**
- Google 与 Apple 都把「聚焦=放大」压得很轻(1.1 / "almost unnoticeable"),把**辨识度交给边框(Google)或抬升+高光(Apple)**,原因是放大量大会侵占邻居、逼行距变宽(Apple 的 100 pt 最小行距就是给放大留的)。UnitedU 的 1.31 是 Projectivy 系做法,行距 25.4 dp + `RowVerticalPad` 20 dp 都是在为它让路。
- Google 的进/出焦时长不对称(300 / 500 ms),出焦更慢,视觉上是「焦点跟着手走、旧卡慢慢沉下去」。UnitedU 现在进出同为 180 ms,比官方快一倍,这是「利落」与「优雅」的分水岭。
- 官方 dark surface 不是纯黑而是 `#1C1B1F`(带一点紫的深灰),卡片容器 `#49454F`,焦点边框 `#938F99`,三者是同一色相的三个明度档——这就是「原生看起来统一」的来源。

### 开放问题(交 Gordon 定)

1. 焦点放大保持 1.31(对齐 v4 / Projectivy 参考)还是降到 1.1 并改用 3 dp 边框或抬升阴影补辨识度?两者互斥,决定后面所有行距。
2. 焦点动效是否采用官方的不对称时长(进 300 / 出 500 ms,decelerate 曲线)替换现在的对称 180 ms?
3. 圆角走 Projectivy 的 40% 卡高(13.75 dp)还是 Material 的 8 / 12 dp?这也决定 banner 四角被裁掉多少烧入文字。
4. 壁纸之上要不要加官方式压底层(`#1C1B1F` 0→80% 渐变或全局 scrim)以保证卡片标题可读?现在靠主题色与 edge-fill,没有统一暗层。
5. 是否引入 content-based color(Material Color Utilities 从壁纸取色)作为主题色来源,替代手选预设?Google 明确推荐此路,Projectivy 也用("Interface adapts to your wallpaper")。
