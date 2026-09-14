# TvHome — 自建电视桌面

固化 Projectivy 快照 v4 的视觉,只保留换壁纸与换图标两项能力。设计与决策见 `../docs/DESIGN-custom-launcher.md`,进度记在 `../docs/FEASIBILITY-sony-a95l-cn-android-tv-launcher.md` §6.28 起。

包名 `com.gordonwang.tvhome`。**它不会自动接管桌面**——装上只是多一个应用,HOME 角色要显式切换(见下)。

## 怎么构建

工具链不在 PATH 里(刻意的,避免污染 Hub 的环境),每次构建先导出这三个变量:

```bash
export JAVA_HOME="$HOME/Library/Java/jdk-17/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$HOME/Library/Gradle/gradle-8.14.5/bin:$JAVA_HOME/bin:$PATH"
cd "Sony TV/launcher" && gradle --no-daemon assembleRelease
adb -s 192.168.1.50:5555 install -r app/build/outputs/apk/release/app-release.apk
```

⚠️ **`install -r` 会连桌面任务一起清掉**,屏幕露出栈里下一个任务(2026-09-13 早上实测是 Projectivy),HOME 角色并没变。同日起由 `RelaunchAfterUpdate` 接收器兜底:收到 `MY_PACKAGE_REPLACED` 后,**若系统默认桌面仍是本应用**就发一个隐式 HOME 意图把自己作为桌面任务拉回来(隐式才建成 `type=home` 任务);默认桌面是别人则什么都不做。

**部署前提(一次性,重装保留、`pm clear`/卸载重装要重授)**:这台索尼固件**不豁免默认桌面的后台启动**(实测 `BAL_BLOCK`),接收器要能发 HOME 必须给它悬浮窗 appop:

```bash
adb -s 192.168.1.50:5555 shell appops set com.gordonwang.tvhome SYSTEM_ALERT_WINDOW allow
```

授权后重装,`logcat -s TvHome ActivityTaskManager` 应见 `BAL_ALLOW_… result code=0` 且栈顶变 `type=home` 的本应用任务(2026-09-13 10:55 休眠中实测通过)。没授权则日志是 `Background activity launch blocked`,此时退回「装完 `am start -a android.intent.action.MAIN -c android.intent.category.HOME`」(shell 身份 `BAL_ALLOW_PERMISSION`,同样实测可用)。装包前确认电视没人在用别的 App——自启会把画面拽回桌面。

**装到电视上的应该是 release 包**,不是 debug:debug 包 60.2 MB(`classes.dex` 42.2 MB,几乎全是
`material-icons-extended` 里没用到的图标),release 开 R8 后 1.85 MB 且带 Compose 官方 baseline profile。
release 用 **debug keystore** 签(`signingConfigs["sideload"]`),所以两者可以互相 `install -r` 覆盖,
不会因签名不符要求先卸载——卸载会连 `layout.json`、壁纸、自定义卡片图一起删掉。
调试要看堆栈时才用 `assembleDebug`。
**`isShrinkResources` 刻意保持关闭**:只省 128 KB,却要靠静态分析判断资源有没有被引用,
对 `res/font` 这类只在代码里按 `R.font.*` 引用的东西不划算。

## 改这份界面前必须知道的七条(都是真机代价换来的)

1. **绝不用任何可滚动容器**——`LazyRow`、`LazyColumn`、`horizontalScroll`、`verticalScroll` 一律不用。
   2026-09-11 实测:卡片放进 `LazyRow` 后,**按上/下键焦点会整棵树消失**,之后按什么都没反应,
   三行的桌面退化成只有第一行能用。日志形态是「卡片失焦 → Compose 根失焦 → View 重新拿到焦点
   但没有任何节点持有」,说明 Compose 没消费这个按键、平台的 View 级导航接手了。
   排除法试过五种改法(去 `focusProperties`、去 `unbounded`、行容器加 `focusGroup`、
   `LazyRow` 自己加 `focusGroup`、`Row`+`horizontalScroll`)**全部无效**,只有不可滚动的 `Row` 行。
   纵向早在 §6.28 就因为 `bringIntoView` 会在水平移动焦点时偷偷带动垂直滚动(按一次右键整体上移 158px)
   而改成自己算位移了 —— **横向是同一条规律的第二次应用:位移一律自己算,用 `Modifier.offset` + `animateDpAsState`。**
   **而且必须同时给那个 `Row` 加 `Modifier.wrapContentWidth(Alignment.Start, unbounded = true)`**,
   放在 `.offset(x = ...)` 之前(链上更外层)。少了它,父容器按屏幕宽给约束,`Modifier.size()` 会被
   `constrain`,**空间用完之后的条目被量成 0 宽**;更糟的是焦点搜索的右向判据是
   `focused.right < cand.right`(严格小于),这些 0 宽条目的 right 全部相等,**永远聚焦不到**。
   实测边界:第 7 格只剩 43% 宽,第 8 格起为 0;编辑界面里行尾的「＋」正是第一个被牺牲的,
   它一消失,那一行在界面内再也加不进应用。**自算位移救不了这件事——`offset` 发生在测量之后。**
   纵向的 `wrapContentHeight(unbounded = true)` 是同一招;2026-09-11 把纵向解法搬到横向时
   只搬了「自算位移」这一半、漏了「放开测量」这一半,当场造出上面这个回归。
   代价是所有条目都会被组合;行内条目很多时要自己盯住布局。

2. **焦点是否落下,只能由目标自报 `isFocused`**。`FocusRequester.requestFocus()` 返回 `Unit`,
   只有一个节点都没挂上时才抛异常,所以 `runCatching { requestFocus() }.isSuccess` **恒为真**,
   循环第一帧就退出、焦点其实没落下。每个新出现的界面都必须显式请求初始焦点,并且要等首帧合成之后
   (`withFrameNanos`),再靠目标自报 `isFocused` 来判断是否可以停止重试。

3. **焦点丢了要靠看门狗,不要靠「在猜得到的几个时刻补请求」**。卡片节点被销毁
   (某个应用后台更新 → `PACKAGE_*` → 那一行短一格)时焦点会消失,而那一刻不在任何
   一张「猜得到的时刻」清单里 —— 遥控器就此全死,按 HOME 也回不来。
   **推论:每个浮层必须自己负责焦点恢复,因为外层看门狗一定会为它让路。**
   「添加应用」列表就掉进过这个两不管地带:编辑界面的看门狗第一行是「浮层开着就返回」,
   而选择器自己的初始焦点循环落地后被 `landed` 闩死、永不重试 —— 在那里按一下左右键
   焦点消失,**没有任何人会把它捞回来**。画一张表:每个可聚焦的界面/浮层,谁负责它的恢复。
   **实现约束(同一个坑踩过两次)**:恢复用的 `FocusRequester` **必须逐项挂,不能只挂第 0 项**。
   `LazyColumn` 会把滚出视野的项回收,只挂第 0 项时它会变成悬空引用,`requestFocus()` 抛异常
   被 `runCatching` 静默吞掉,循环空转到上限后放弃。丢焦点本身不会滚动列表,
   所以「上次拿到焦点的那一项」一定还在组合窗口内 —— 挂它才安全。
   **这个洞的严重度还会被别处的修复推高**:候选列表从 38 项扩到 49 项之后,
   「滚到第 0 项被回收」从极端情况变成了常规操作。

4. **「有没有焦点」只信控件自己上报,不要用根节点的 `onFocusChanged`。**
   曾经用根节点的 `hasFocus && !isFocused` 当判据,它在多数路径上是对的,
   但在「退到后台再回来」这条路上**不重发**,会停在过期的 `true`:日志说有焦点,
   截图里卡片却既没有放大也没有光晕(上边缘 777→812、光晕峰值 142→66)。
   现在由 `AppCard` / `GearButton` 通过 `onFocusChange(Boolean)` 同时上报「得到」和「失去」。
   **推论:看门狗的账本必须覆盖它会去抢焦点的全部场合。**它不认识齿轮菜单的菜单项,
   菜单一开账本就变成「没有焦点」,于是每帧抢着请求、把菜单刚拿到的焦点搅掉 ——
   菜单开着时必须让路。

5. **要把焦点送回某个具体位置时,「目标」必须与「当前位置」分开 —— *坐标的每一个分量都要拆*,
   而且要从 `ON_PAUSE` 就冻结。**
   Compose 会抢在还原逻辑之前把焦点给第一张卡;若两者共用一个量,那次焦点事件会把目标
   改写成 0,**记忆在被用到之前就没了**。另外显式重定位不能和看门狗写在同一个效果里 ——
   看门狗第一行是「已经有焦点就什么都不做」,会把明确的请求整个吞掉。
   **「每一个分量」是字面意思**:编辑界面先把*列号*拆成了 `rowFocused` / `focusTarget`,
   却让*行号* `focusRow` 继续一身二职(既跟着焦点走给看门狗用、又当重定位的目标)。
   结果 `onDismiss` 设好目标行之后,Compose 抢先给出的那次焦点事件把行号改了回去,
   焦点静默跳到另一行 —— **而下一步的「移出」就打在那一行上,真的删错了应用。**
   还有:**退出条件不能只判「当前 == 目标」**,两者本来就相等的路径会一次请求都不发;
   必须同时要求「焦点真的落下了」。

6. **一个效果里,「守卫」和「依赖」必须成对出现。**凡是写了 `if (X) return@LaunchedEffect`,
   `X` 就**必须**同时出现在这个效果的 key 里。少了 key,X 从 true 变回 false 时效果不会重启,
   那条守卫就变成了单向阀门 —— 进得去出不来。
   实测代价:编辑界面的焦点看门狗漏了这一半(首页那份是对的,`menuOpen` 既是 key 又是 guard),
   于是按行尾加号弹出「没有可添加的应用了」再按返回,三个 key 一个都没变、看门狗永不重启,
   **那个界面从此只剩返回键能用**。反过来也要检查:key 里有的量,守卫里要不要也判一次。

7. **别用「一次性布尔闩」表达状态,用可自愈的判据(比如 nonce 比对)。**
   `returnToGear` 曾是个布尔闩,只有「看门狗跑完整个循环」这一条窄路能清掉;
   任何一次早退(菜单又开了、`restoring` 被置位、Compose 自己把焦点还给了第一张卡)
   都会把它留在 `true`。**一旦闩住,「从应用返回还原到离开前那张卡」永久失效,
   而且此后每一次丢焦点都被送到齿轮** —— 症状与病因隔得很远,极难联想。
   改成「记下当时的 `focusNonce`,用 `focusNonce == gearNonce` 判断」之后:来了新的 nonce
   比对自然不成立,不需要任何人去清;而且守卫读的量本身就是 key,天然满足第 6 条。
   **判据:凡是写了 `x = true`,数一数有几条路把它写回 `false`;只有一条就换写法。**

## 工具链是怎么装的(2026-09-10,全部手装,Hub 上原本一样都没有)

| 组件 | 位置 | 来源与坑 |
|---|---|---|
| JDK 17 | `~/Library/Java/jdk-17` | Adoptium `api.adoptium.net`(重定向到 GitHub 资源站,可达)。**没用 brew**:`brew install openjdk@17` 从 ghcr.io 拉了二十多分钟没落地,直接下 tar.gz 两分钟就好 |
| Gradle 8.14.5 | `~/Library/Gradle/gradle-8.14.5` | `services.gradle.org`,**必须 `curl -L`**(有 Cloudflare 307 跳转,不跟随会拿到 169 字节的 HTML 却不报错) |
| Android SDK | `~/Library/Android/sdk` | **手动解压**三个包,没用 sdkmanager。`dl.google.com` 在这条网络被掐 SNI,而 `dl-ssl.google.com` 提供完全相同的内容且可达——装 adb 时踩过同一个坑 |

SDK 三个包(从 `https://dl-ssl.google.com/android/repository/repository2-3.xml` 解出文件名,下完对 size 与 `unzip -t` 双验):
`platform-tools_r37.0.1-darwin.zip` → `platform-tools/`;`platform-35_r02.zip` → `platforms/android-35/`;`build-tools_r35_macosx.zip` → `build-tools/35.0.0/`。
licenses 目录里手写了 `android-sdk-license` 的三个 hash,否则 AGP 认为没接受许可。

**两条必须写在配置里的**(否则 AGP 会去够不着的域名):
- `app/build.gradle.kts` 的 `buildToolsVersion = "35.0.0"` — AGP 8.7 默认找 34.0.0,缺了就自动下载。
- `gradle.properties` 的 `android.builder.sdkDownload=false` — 关掉自动下载,让缺组件时报真正的原因。
- `settings.gradle.kts` 把 `google()` 换成了 `https://dl-ssl.google.com/dl/android/maven2/`,AGP / AndroidX / Compose 全部从这里拉,实测可用。

## 可替换素材(设计里的「固定路径后门」)

全在应用的**外部**文件目录,`adb push` 直接可写,应用读不需要任何权限:

```
/sdcard/Android/data/com.gordonwang.tvhome/files/
├── wallpaper.jpg            # 壁纸,已在电脑上处理好,桌面只负责显示
├── layout.json              # 三行的成员与顺序,首次启动自动生成默认值
├── icons/<包名>.png         # 覆盖某个应用的卡片图,存在即生效
└── library/
    ├── wallpapers/          # 内置选择器的壁纸候选池
    ├── screensavers/        # 屏保轮播池:所有图片参与轮播(30s 一张,交叉淡入+Ken Burns)
    └── cards/               # 内置选择器的卡片图候选池
```

`assets/wallpaper-gold-fog.jpg` 是当前那张,用 ffmpeg 生成:6×4 的真随机噪声 → 放大到 1080p → 高斯模糊 sigma 78 → 归一化 → 色调曲线 `0/0 0.55/0 0.80/0.22 1/0.62` 压掉暗部 → 染成金色。生成脚本的参数记在 §6.28。**注意**:`geq=random()` 只按列变化会得到竖条纹,必须用 `/dev/urandom` 喂 rawvideo 才是真二维噪声。

同一张图也打包在 `app/src/main/assets/default-wallpaper.jpg`,**首次启动会自动铺到上面那个路径**——没有它的话,全新安装或清除数据后就是永久全黑且无法自救。

**换图方式**:壁纸和卡片图用内置 D-pad 选择器(`ImagePicker.kt`),扫描 `library/wallpapers/` 或 `library/cards/` 目录,全键盘导航,不跳外部应用。图片通过 `adb push` 预先放到对应的 `library/` 子目录。屏保图片也用 `adb push` 放到 `library/screensavers/`,该目录里的所有图片自动参与轮播。**轮播列表在每次进入待机那一刻重扫**(2026-09-13 起):推完/删完图,下次待机即生效,不用重启桌面。之前是进程启动时扫一次的快照,同步删掉的图会以「只剩壁纸」的形式留在轮播里各占 30 秒(FEASIBILITY §6.50)。旧版单文件 `screensaver.jpg`/`.png` 会在首次加载时自动迁移到 `library/screensavers/`。

## 实测数据(2026-09-11)

| 项 | TvHome | Projectivy | 说明 |
|---|---|---|---|
| 内存 PSS | **83 MB** | 99 MB | 位图按卡片尺寸降采样、只解码要画的、壁纸 RGB_565 |
| 冷启动 | 2.1 s | 1.45 s | Compose 相对 View 系统的固有开销,未追平;`singleTask` 常驻后不再冷启动 |
| 视觉偏差 | 第一行卡片顶 487 对 485、左边缘完全一致 | — | 卡片尺寸/圆角/聚焦放大/压暗系数经独立复审确认正确 |

健壮性实测:坏 JSON、空文件、配置里全是未安装的包、坏壁纸、全新安装——都不崩,齿轮菜单始终可达。

## 切换桌面

```bash
# 切到自建桌面
adb -s 192.168.1.50:5555 shell cmd package set-home-activity --user 0 com.gordonwang.tvhome/.MainActivity
# 切回原厂桌面(随时可用)
adb -s 192.168.1.50:5555 shell cmd package set-home-activity --user 0 com.dangbei.TVHomeLauncher
```

崩溃时系统会落到 `com.android.tv.settings/.system.FallbackHome`,不会黑屏。但**电视重启会让 adb 失效**,两者叠在一起就只能用遥控器救——所以**齿轮菜单里已经有一项「设置默认桌面」**,它先弹一张引导卡(`HomeSettingsCard`),按卡上的按钮打开系统的主屏幕应用设置页(`android.settings.HOME_SETTINGS`,实测解析到 `com.android.permissioncontroller/.role.ui.DefaultAppActivity`),不依赖 adb 就能换回去。

## 界面上能做什么(齿轮 → 四项)

| 菜单项 | 作用 |
|---|---|
| 编辑桌面 | 三行 + 每行末尾加号;选中卡片弹「往左移 / 往右移 / 换卡片图 / 从这一行移出」;加号打开应用选择器。改动即时写回 `layout.json`,退出后首页立即生效 |
| 换壁纸 | 内置选择器,从 `library/wallpapers/` 选图复制到 `wallpaper.jpg` |
| 屏保图库 | 缩略图网格预览 `library/screensavers/` 里的**全部**图片(2026-09-13 起不再封顶 9 张,网格纵向滚动,DOWN/UP 焦点带着滚,§6.53),按确定键全屏预览(HDR + Ken Burns,和真实屏保一致),← → 切换,返回退出;所有图片自动参与轮播(30 秒一张,2 秒交叉淡入),HDR gain map 保留 |
| 系统设置 | 打开电视的 Android 设置 |
| 设置默认桌面 | 先弹一张 United UI 风格的引导卡(`HomeSettingsCard`):显示当前默认桌面的图标+名称 + 「在系统设置中更改」按钮 + 说明。按钮跳系统主屏幕应用设置页(`android.settings.HOME_SETTINGS`,实测解析到 `com.android.permissioncontroller/.role.ui.DefaultAppActivity`)。**应用无权限直接改 HOME 角色,切换必须在系统页完成,这张卡只是把它包在一次明确点击之后**(§6.54) |

三行的**名字**是固定的(VIDEO / LIVE / MUSIC),按设计不在界面里改,要改找代码里的 `Layout.DEFAULT`。
