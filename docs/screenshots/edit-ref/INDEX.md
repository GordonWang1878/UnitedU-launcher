# 编辑桌面:Google 原生桌面的「改桌面」参照截图(2026-10-08)

给「编辑桌面」页重做当对照。全部来自模拟器,没碰电视。

- **Google TV**:`unitedu-gtv`(已登录),`com.google.android.apps.tv.launcherx`。下文简称 gtv。
- **Android TV**:`unitedu-tv`,`com.google.android.tvlauncher`。下文简称 atv。
- 1920×1080 / 320 dpi,**dp = px ÷ 2**。JPG 质量 85。
- 测试中临时往 atv 收藏行加了 YouTube,测完已移除;gtv「Your apps」顺序也已复原。
- **截图方法的坑**:模拟器用 `-no-window -gpu host` 起时,`screencap` 返回的是**旧帧**(屏幕已变,截图还是上一个画面);改用 `screenrecord --time-limit 2` 取最后一帧才是当前画面。带窗口启动时,宿主鼠标划过模拟器窗口会注入 MotionEvent、把首页滚走,所以要用 `-no-window`。
- 渠道 / 频道相关的编辑(频道行左侧 Remove / Move 圆钮、频道移动模式、Customize channels 面板)已在 `../channels/ref/` 里:`atv-08`~`atv-10`、`atv-15`、`atv-16`,这里不重复截。

## Google TV

| 文件 | 内容 |
|---|---|
| `gtv-00-home-your-apps-row-unfocused.jpg` | 首页默认态。「Your apps」行在第 3 行,行尾常驻两张带字的功能卡「Reorder」「Add apps」 |
| `gtv-01-your-apps-reorder-tile-focused.jpg` | 焦点在「Reorder」卡:圆角矩形,左上 ⇄ 图标、左下文字;未聚焦是深色半透明底,聚焦变浅蓝实填 + 略放大 + 柔光 |
| `gtv-02-your-apps-add-apps-tile-focused.jpg` | 焦点在「Add apps」卡(Play 三角图标),画法同上 |
| `gtv-03-reorder-mode-entered.jpg` | 按 Reorder 进入**行内整理模式**:行标题换成「Arrange your Apps」,其余行全部压暗,两张功能卡变灰不可用,行下方居中出现「Done」胶囊;焦点落在最后一个应用上,图标外白圈,名字下方小号大写提示「PRESS TO MOVE」 |
| `gtv-04-reorder-picked-up.jpg` | 按确定「拿起」:提示变成「PRESS TO CONFIRM」,图标左侧出现小箭头(只画能走的方向;在行尾所以只有左箭头) |
| `gtv-05-reorder-moved-two-left.jpg` | 左移两格后:两侧都有箭头,邻居已让位 |
| `gtv-06-reorder-confirmed.jpg` | 再按确定放下:提示回到「PRESS TO MOVE」,箭头消失,仍在整理模式里 |
| `gtv-07-reorder-done-focused.jpg` | 按下键到「Done」(浅色实填 = 聚焦)。按 Done 或返回键退出 |
| `gtv-08-after-done.jpg` | 退出后行标题恢复「Your apps」,焦点留在刚搬过的那张卡上 |
| `gtv-reorder.mp4` | 录屏(960×540,6 s):整理模式里拿起一张卡、连按两次左键。卡片换位是左右平滑滑动,箭头跟着出现 / 消失 |
| `gtv-09-app-longpress-menu.jpg` | 长按系统应用(YouTube):整屏深色菜单,左边 banner + 应用名,右边一列胶囊「Move / Open / View in Play Store」,焦点默认在 Move |
| `gtv-10-move-from-menu.jpg` | 菜单里选 Move:直接进上面同一个「Arrange your Apps」模式,焦点在这张卡上、显示「PRESS TO MOVE」(还没拿起) |
| `gtv-11-apps-tab-your-apps.jpg` | Apps 页里的「Your apps」:行尾只有「Reorder」一张功能卡(没有 Add apps,因为这一页本身就是找应用的地方),下面是搜索框和「App categories」 |
| `gtv-12-sideloaded-app-longpress-menu.jpg` | 长按侧载应用(IconProbe):「Move / Open / Uninstall」。**gtv 的 Your apps 没有「从行里移除」**——行里就是已装应用,只能排序或卸载 |
| `gtv-13-add-apps-opens-apps-tab.jpg` | 按「Add apps」:不弹选择器,而是切到 Apps 页(顶部推荐应用大卡 + Your apps + 搜索),装好的应用自动出现在 Your apps |
| `gtv-11-settings-profile-apps-only-mode.jpg` | 设置 → Accounts & sign-in → 账号 →「Apps only mode」开关,右栏是长段说明(隐藏 Google 推荐、只留应用)。这是 gtv 设置里唯一与「首页长什么样」有关的项;没有单独的 Home screen 设置页。邮箱、账号名与头像已打码(仓库公开) |
| `gtv-13-menu-move-picked-up.jpg` | 从长按菜单进入整理模式后拿起 IconProbe:「PRESS TO CONFIRM」+ 左右箭头 |
| `gtv-14-menu-move-one-step-right.jpg` | 右移一格 |
| `gtv-15-menu-move-confirmed-still-arranging.jpg` | 放下后仍在整理模式(「PRESS TO MOVE」),要按 Done / 返回才退出 |
| `gtv-16-arrange-focus-stops-at-last-app.jpg` | 整理模式里右键走到最后一个应用就停,进不了灰掉的功能卡 |
| `gtv-17-after-done-focus-stays-on-tile.jpg` | 退出后焦点留在最后操作的卡上 |
| `gtv-07-app-longpress-menu-system-app.jpg` | 与 `gtv-09` 同一画面(重复) |
| `gtv-08-longpress-app-enters-arrange-mode.jpg` | 与 `gtv-10` 同一画面(重复,焦点在 YouTube) |
| `gtv-09-your-apps-app-tile-focused.jpg` | Your apps 里应用图标聚焦态(白圈 + 放大 + 按图标取色的柔光) |
| `gtv-12-app-longpress-menu-sideloaded.jpg` | 与 `gtv-12-sideloaded-…` 同一画面(重复) |


## Android TV

| 文件 | 内容 |
|---|---|
| `atv-01-favorites-row.jpg` | 首页「Favorite Apps」行:收藏的应用(banner 卡)+ 行尾一张「+」卡。焦点在 Play Store,卡片放大、名字写在卡下方 |
| `atv-02-favorites-add-tile-focused.jpg` | 焦点在「+」卡:深青灰底 + 大号加号,聚焦后卡下方显示「Add app to favorites」 |
| `atv-03-add-favorite-picker.jpg` | 按「+」:右侧滑出半屏面板「Select app」,一列 banner + 应用名(只列未收藏的应用),左侧首页压暗 |
| `atv-04-after-add-favorite.jpg` | 选 YouTube 后:追加在行尾、「+」之前;面板关闭,焦点留在「+」上(方便连续添加) |
| `atv-05-favorite-longpress-menu.jpg` | 长按收藏卡:卡片上方弹出浅灰小菜单(带图标):「Remove from favorites / Move / Open」,默认焦点在离卡最近的 Open,其余画面压暗 |
| `atv-06-favorite-move-mode.jpg` | 选 Move:原地进入移动模式,卡片多一圈粗灰描边;**没有文字提示、没有箭头** |
| `atv-07-favorite-move-step-left.jpg` | 左键一格:与邻居换位,描边跟着走 |
| `atv-favorite-move.mp4` | 录屏(960×540,5 s):收藏卡左移一格,换位是约 0.25 s 的横向滑动 |
| `atv-08-favorite-move-confirmed.jpg` | 按确定放下:描边消失,回到普通聚焦态 |
| `atv-09-favorite-menu-remove-focused.jpg` | 长按菜单焦点在「Remove from favorites」(心形减号图标) |
| `atv-10-apps-tab.jpg` | Apps 页:顶部 Play 商店推广 +「Installed Apps」网格 |
| `atv-11-apps-tab-longpress-menu.jpg` | Apps 页长按应用:菜单在卡片右侧,「Open / Move / Add to favorites / Info / Uninstall」(系统应用的 Uninstall 置灰) |
| `atv-12-apps-tab-move-mode.jpg` | Apps 页选 Move:切到**单独的全屏整理页**(灰底,只剩应用网格),被搬的卡放大 + 描边 |
| `atv-13-apps-tab-move-step-left.jpg` | 左移一格;确定放下后按返回回到 Apps 页 |

## 设计要点

1. **入口是看得见的「带字卡片」,放在被编辑对象的末尾。** gtv 的「Reorder」「Add apps」、atv 的「+ / Add app to favorites」都常驻在行尾,和应用卡同一行、同一套焦点规则,用户顺着方向键就能撞见;不靠隐藏手势,也不把编辑藏进设置。长按菜单只是第二入口。
2. **编辑是「原地模式」,不是另开一页。** gtv 在同一行上切模式:标题改成「Arrange your Apps」、其余全部压暗、功能卡变灰、底下给一个「Done」——用户一眼知道「现在在改这一行」,改完的结果就是首页本身。atv 收藏行也是原地移动;只有 Apps 页那个大网格才切到专门的全屏整理页。
3. **提示贴着焦点、随状态换字。** gtv 在被选中的卡下方写一行小号大写「PRESS TO MOVE」→「PRESS TO CONFIRM」,拿起后左右出现小箭头且只画能走的方向;没有全屏说明文字。atv 更省,只靠一圈描边表示「拿在手上」。
4. **两步式移动:先选中,再拿起,再放下。** 确定键在「拿起 / 放下」之间切换,方向键只在拿起时才搬卡;放下后仍留在模式里,可以接着搬别的,最后统一按 Done / 返回退出,焦点留在最后操作的那张卡上。
5. **动作的数量很少。** gtv 的 Your apps 只能排序、卸载、加(去 Apps 页装),没有「从行里移除」;atv 收藏行是 移除 / 移动 / 打开,添加走右侧面板。菜单都是 3 项左右的胶囊或小卡,默认焦点落在最安全 / 最常用的那项上。
