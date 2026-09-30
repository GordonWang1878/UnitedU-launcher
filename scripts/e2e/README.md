# 端到端测试(模拟器)

遥控器按键 + `uiautomator` 读屏 + 断言,在 Android 14 TV 模拟器上把主要用户旅程从头走一遍。2026-09-30 随五路独立复审一起写成(R140),以后改界面后可以整套重跑。**只驱动模拟器**,不碰电视。

## 跑法

```bash
source scripts/env.sh
emulator -avd unitedu-tv -port 5560 -no-snapshot -no-audio -gpu host &      # 另开;等 sys.boot_completed = 1
gradle --no-daemon assembleRelease
adb -s emulator-5560 install -r app/build/outputs/apk/release/app-release.apk
adb -s emulator-5560 shell cmd package set-home-activity com.uniteduone.launcher/.MainActivity
python3 scripts/e2e/fixtures.py          # 造并装测试 APK(占位应用、假调谐器、假直播、认得的应用)与上传素材
python3 scripts/e2e/run_all.py           # 整套约 25 分钟;单跑一段:python3 scripts/e2e/j_home.py
python3 scripts/e2e/fixtures.py --uninstall   # 测完卸掉测试包
```

环境变量:`DEV`(模拟器序列号,默认 `emulator-5560`)、`E2E_OUT`(截图、结果 JSON、测试 APK,默认 `/tmp/unitedu-e2e`)。
依赖:本机 `curl`、`ffmpeg`、Python 3 + Pillow;SDK build-tools 35.0.0(`scripts/env.sh` 注入)。

## 覆盖了什么

| 脚本 | 旅程 |
|---|---|
| `j_home.py` | 首页长按菜单;改名(取消 / 保存 / 清空恢复);换卡片图(内置图英文名);应用内提示条出现与消失;HOME 收掉菜单与改名页;菜单淡出期间快速按键 |
| `j_edit.py` | 编辑桌面:行菜单七项(加应用、改行名、行图标、上移 / 下移、新建行、删空行不弹框、删非空行确认默认在取消);卡片菜单 → 换卡片图 → 返回落回同一张卡(含 0.3 s 内快速进出);搬运;移出;HOME;退出落回「编辑桌面」 |
| `j_upload.py` | 从手机添加:网页注入、上传 / 缺 CSRF 头被拒 / txt 拒收 / 超限 413;关页落点与选用;**关页即停**(前台、后台都查)、快速重开仍是 8090;屏保视频上传、角标、全屏预览、删除确认;内置屏保图英文名 |
| `j_settings.py` | 设置外壳四组逐行(左侧说明存在且各不相同)、选项层、滑块、闲置画面子页、切简体再切回(重建后落回「语言」)、立即屏保、跳系统页再返回、关于页检查更新、恢复默认确认 |
| `j_apps_inputs.py` | 所有应用页两层菜单加到桌面、提示条、打开应用再返回;应用页 / 首页开着时装卸应用焦点不丢;输入源页改名 / 隐藏 / 恢复 / 切换 |
| `j_onb.py` | `pm clear` 全新安装 → 首次引导(简体):选语言重建、返回上一步、放到桌面(行名本地化、行图标)、完成 |
| `j_persist.py` | 2026-09-30 测试轮:layout / settings / titles / hidden-inputs 写坏(乱码、空文件、类型错、零行、超大、缺失)后冷启动——不崩、不弹引导、有 `.prev` 就恢复、`.bad` 留证 |
| `j_upload_edge.py` | 同上:怪文件名(中文 / emoji / `../` / 超长 / 只差大小写)、20 张分批与一次、> 30 MB 拒收、并发同名、删刚传的与正在用的壁纸、上传中途关页 |
| `j_pkg.py` | 同上:首页 / 长按菜单 / 改名 / 移动态 / 编辑页 / 添加应用 / 应用页 / 设置 / 选图 / 输入源开着时卸载、覆盖安装、新装 |
| `j_settings_values.py` | 同上:每个选项每个取值写盘与摘要一致、滑块两端、开关来回、选项层不确定就返回不写盘、恢复默认 |
| `j_overlays.py` | 同上:34 个浮层逐个按 HOME / 按返回(收干净、单个焦点、落回打开它的地方),引导里的 HOME / 返回 |
| `j_recreate.py` | 同上:8 个界面开着时字体缩放触发 Activity 重建两次;浮层开着时 force-stop 冷启动 |
| `j_idle.py` | 同上:闲置 1 分钟只留时钟、唤醒键被吞、再 1 分钟自动屏保、退出屏保键被吞、设置开着不进待机(约 4 分钟) |
| `j_i18n.py` | 同上:en / zh-CN / zh-TW 各 11 个界面,找越出屏幕 / 胶囊的文字并截图(`E2E_OUT/i18n-*.png`) |
| `j_monkey.py` | 同上:adb monkey 只发方向 / 确定 / 返回 / 菜单,8 个种子 × 500 事件;每个种子后查崩溃 / ANR / 焦点 / 落盘(`MONKEY_SEEDS`、`MONKEY_EVENTS` 可调) |

`run_all.py` 按上表顺序全跑(约 1 小时 45 分);只跑几段:`E2E_ONLY=j_persist,j_pkg python3 scripts/e2e/run_all.py`。

## 写这类脚本的规矩(踩过的坑)

- **每一步都读屏断言,不盲发长串按键**:焦点落在哪一格只信 `uiautomator` 的 `focused="true"` 节点,标签取该节点框内的文字(Compose 的文字在子节点上)。`move_to()` 沿一个方向走到标签出现为止。
- 按键间隔 0.6 s;要模拟真人快按用 `keys_fast()`(一条 shell 里连发,间隔约 0.15 s)。
- 长按:`long_press_timeout` 临时调到 700 ms 再 `input keyevent --longpress 23`,测完改回 400。
- HOME 用 HOME intent 发(第一次拉起也用它,之后才走同一个实例的 `onNewIntent`)。
- 提示条只显示 2–3.5 秒:要截它就在按键后立刻截,不要等一次读屏之后。
- 标签文字直接读 `values-en/strings.xml`,改文案不用改脚本;脚本以英文界面跑(引导那段用简体)。
- 测试布局在 `fixtures/layout.json`,每段开头 `restart()` 推回去。
- **首页顶栏「应用」胶囊的 content-desc 就叫「All Apps」**:焦点在它上面时右边的名字小胶囊(R133)也以 text 画出「All Apps」——判「应用页开着」要认页头下那行提示(`S("apps_page_hint")`)。同理 `has()` 是子串匹配,设置第一层的说明文字里就有「Language」「Wallpaper」,判某一页开着用整段相等(`j_overlays.has_exact`)。
- 图库目录所在的 `/sdcard` 不区分大小写(Android 11+ casefold):`ls PHOTO.JPG` 找得到 photo.jpg。
- 三语测试用 `set_lang("zh-CN")` 让 `S()` 改读对应语言的 strings.xml;`run_all.py` 每段开头会切回英文。
