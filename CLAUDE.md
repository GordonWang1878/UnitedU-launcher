# UnitedU

零广告、零推荐,只有你放上去的应用。面向国行无 GMS Android TV 的开源桌面。设计定稿见 `docs/DESIGN-unitedu-open-source.md`。包名 `com.uniteduone.launcher`。

## 构建

```bash
source scripts/env.sh && gradle --no-daemon assembleRelease
```

工具链在 `~/Library/{Java,Gradle,Android}`,刻意不进全局 PATH。装法与版本见 `docs/WORKLOG.md`。

## 模拟器(开发验证)

AVD `unitedu-tv`:Android 14 TV(arm64-v8a),1920×1080/320dpi,HVF 加速。工具链已装 emulator + system-image(手装,同 SDK 绕法,见 `docs/WORKLOG.md`)。

```bash
source scripts/env.sh
emulator -avd unitedu-tv -no-snapshot -no-audio -gpu swiftshader_indirect &   # 启动
adb wait-for-device && adb shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package set-home-activity com.uniteduone.launcher/.MainActivity   # 不带 --user 0
adb shell am start -n com.uniteduone.launcher/.MainActivity                     # 不用 HOME 键,见下
adb exec-out screencap -p > /tmp/home.png        # 截图
adb emu kill                                     # 关闭
```

改 settings.json 单个字段验证用:pull → 正则替换 → push → force-stop → `am start -n`(见 M3 计划 Task 5 的 setjson.sh)。

**模拟器验证的坑**(M7 实测,细节见 WORKLOG 2026-09-17):
- `KEYCODE_HOME` 不认 `set-home-activity`(原厂 Google TV 桌面照抢)→ 一律 `am start -n` 拉起;`set-home-activity` 带 `--user 0` 会返回 Success 却不生效。
- 真机是被 HOME 拉起的:`am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.uniteduone.launcher/.MainActivity`。依赖启动 intent 的 bug 在 `am start -n` 下藏得住(`recreate()` 沿用原 intent)。
- 按不住键:`settings put secure long_press_timeout 700` 后 `input keyevent --longpress KEYCODE_DPAD_CENTER`(注入的重复事件时间戳 = downTime + 该值,越过 600 ms 阈值;测完改回 400);或装 `LONG_PRESS_MS = 0` 的探针 APK。
- HOME 角色进程受保护:`am kill` 无效,`am crash` 连任务带状态一起丢,`always_finish_activities` 不生效 ——「进程死后带 Bundle 重建」这台 AVD 造不出来。
- `pm clear` 连数据带 HOME 角色一起清(卸载同样退回原厂桌面),之后重设。
- 系统设置是半透明侧边面板:盖着时本应用仍是 STARTED,不能拿它当「退到后台」。
- 注入按键之间留 ~0.4 s:零间隔连发会跑在 Compose 异步焦点效果前面。
- uiautomator 不报全透明节点(真待机时 `focused="true"` 为 0,焦点其实还在);TV 设置应用卡片的 content-desc 也是「Settings」,与齿轮同名 —— 脚本按 bounds 区分,且确定键之前先断言焦点文案,否则会启动卡片对应的应用。

真机(Sony A95L)只在里程碑真机验收用;开发全程走模拟器。真机 adb 走「无线调试」配对码(**不是** 5555):电视「开发者选项 → 无线调试 → 使用配对码配对设备」拿码,`printf '<码>\n' | adb pair <IP:配对端口>`(管道喂码,参数形式会 protocol fault),再 `adb connect <IP:连接端口>`——**连接端口以电视「无线调试」主页面显示的为准**(2026-09-16:mDNS 广播的端口是休眠前的过期记录,端口扫描也扫不到真端口;mDNS 发现要 `ADB_MDNS_OPENSCREEN=1`)。IP 走 DHCP(当前 192.168.1.22)。配对不跨会话保留,`offline` = 配对失效,重配即可。

## 文档分流(每轮工作收尾前必查同步)

- `docs/DESIGN-*.md`:设计定稿,改设计先改它
- `docs/superpowers/plans/`:实施计划
- `docs/WORKLOG.md`:每轮工作记录(排查过程、结论、未验证项、决策)

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

   **画的表**——每个可聚焦的界面/浮层,谁负责它的焦点恢复:

   | 界面 / 浮层 | 恢复责任方 |
   |---|---|
   | 首页卡片/齿轮 | HomeScreen 看门狗 + 还原效果(选择器、设置页等整屏浮层都叠在常驻首页上,`covered` 期间冻结 `tgtRow/tgtIdx/tgtGear`,关掉后按它还原;编辑页仍整体替换首页,回来落 (0,0)) |
   | 齿轮菜单 / 长按卡片菜单 | GearMenu 自己的初始焦点循环(nonce) |
   | 修改标题对话框 | TitleDialog(nonce + focused,四向 Cancel) |
   | 设置页两栏 | SettingsScreen 看门狗(二维账本 pane/group/rowOf,`covered` 让路,`reloadNonce` 重读) |
   | 确认框(恢复默认) | ConfirmDialog(nonce + focusedBtn) |
   | 关于页 | AboutScreen(nonce + focused,四向 Cancel) |
   | 首次引导 | Onboarding(每步 nonce + 逐项 requester + 看门狗) |
   | 编辑页 | EditScreen 看门狗 + 显式重定位 |
   | 添加应用列表 | AppPicker(逐项 requester) |
   | 图片选择器 / 屏保图库 / 默认桌面卡 / 导入图片页 | 各自的初始焦点循环(nonce) |

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

