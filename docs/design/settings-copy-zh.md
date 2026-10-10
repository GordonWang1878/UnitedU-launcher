# 设置页文案(简体中文)

**怎么改**:直接改「」里的字;编号和行尾的 `key` 别动(我靠它找回对应的那一句)。带 `%1$d` / `%1$s` 的是程序自动填的数字或名字,改字时保留它们。同一句话在好几处出现时只在第一处给原文,后面写「同 X」——改第一处,所有地方一起改。繁体、英文我按你改完的简体跟着改。

「胶囊」= 右边一颗颗的按钮;「左侧说明」= 光标停在某一行时,左半屏显示的那段话;「选项层」= 在一行上按确定后进去的那一页。

## 1 设置第一层

- **1.1** 左侧页名上方小字:「UnitedU」(品牌名,不进翻译)
- **1.2** 左侧页名:「设置」 `menu_settings_title`
- **1.3** 第 1 颗胶囊:「通用」 `settings_group_general`
- **1.3a** 　这颗胶囊的第二行小字:「语言、默认桌面、上传资料、时钟」 `shell_root_general_desc`
- **1.4** 第 2 颗胶囊:「布局」 `settings_group_layout`
- **1.4a** 　这颗胶囊的第二行小字:「编辑桌面、应用名、卡片显示偏好」 `shell_root_layout_desc`
- **1.5** 第 3 颗胶囊:「外观偏好」 `settings_group_appearance`
- **1.5a** 　这颗胶囊的第二行小字:「壁纸、主题色」 `shell_root_appearance_desc`
- **1.6** 第 4 颗胶囊:「屏保」 `settings_group_screensaver`
- **1.6a** 　这颗胶囊的第二行小字:「屏保图库、启动时间、系统屏保等」 `shell_root_screensaver_desc`
- **1.7** 第 5 颗胶囊:「原生电视设置」 `menu_system_settings`
- **1.7a** 　这颗胶囊的第二行小字:「网络、画面、声音等电视自身的设置」 `menu_system_settings_desc`
- **1.8** 第 6 颗胶囊:「关于」 `menu_about`
- **1.8a** 　这颗胶囊的第二行小字:「版本、更新、恢复默认」 `menu_about_desc`

## 2 通用(第一层 → 通用)

- **2.0** 页名同 1.3,页名上方小字同 1.2
- **2.1** 胶囊:「语言」 `settings_language`
- **2.1a** 　左侧说明:「UnitedU 界面的语言，可以和电视系统不同。」 `settings_language_desc`
- **2.1e** 选项层(按确定进去的那一页):页名同 2.1,页名上方小字「设置 · 通用」,左侧说明同上
- **2.1e.1** 　第 1 个选项:「跟随系统」 `settings_lang_system`
- **2.1e.2** 　第 2 个选项:「简体中文」 `settings_lang_zh_cn`
- **2.1e.3** 　第 3 个选项:「繁體中文」 `settings_lang_zh_tw`
- **2.1e.4** 　第 4 个选项:「English」 `settings_lang_en`
- **2.2** 胶囊:「设置默认桌面」 `menu_set_default_home`
- **2.2a** 　左侧说明:「按遥控器的主页键时打开哪个桌面。设成 UnitedU，每次按主页键都会回到这里。」 `menu_set_default_home_desc`
- **2.2b** 　胶囊右侧的值(系统没设默认桌面时;设了就显示那个桌面的名字):「未设置」 `settings_home_not_set`
- **2.2g** 　按确定:打开下面的「默认桌面」页
- **2.3** 胶囊:「上传资料」 `settings_phone_transfer`
- **2.3a** 　左侧说明:「用手机扫码，把照片和视频传到电视，或给电视装应用。」 `settings_phone_transfer_desc`
- **2.3g** 　按确定:打开上传资料的扫码页
- **2.4** 胶囊(右侧显示子页里各行的当前值):「闲置画面」 `settings_standby`
- **2.4a** 　左侧说明:「一段时间不按遥控器，桌面会淡去，进入闲置画面。在这里设定闲置画面的偏好。」 `settings_standby_desc`
- **2.4s** 　子页(按确定进去):页名同 2.4,页名上方小字「设置 · 通用」,里面是:
- **2.4s.1** 胶囊(子页):「闲置启动时间」 `settings_idle_after`
- **2.4s.1a** 　左侧说明:「多久不按遥控器，桌面就进入闲置画面。」 `settings_idle_after_desc`
- **2.4s.1e** 选项层(按确定进去的那一页):页名同 2.4s.1,页名上方小字「设置 · 通用 · 闲置画面」,左侧说明同上
- **2.4s.1e.1** 　第 1 个选项:「从不」 `settings_idle_off`
- **2.4s.1e.2** 　第 2 个选项:「%1$d 分钟后」(例:1 分钟后) `settings_after_minutes`
- **2.4s.1e.3** 　第 3 个选项:同 2.4s.1e.2(这一处显示:3 分钟后)
- **2.4s.1e.4** 　第 4 个选项:同 2.4s.1e.2(这一处显示:5 分钟后)
- **2.4s.1e.5** 　第 5 个选项:同 2.4s.1e.2(这一处显示:10 分钟后)
- **2.4s.2** 胶囊(子页):「闲置时显示」 `settings_idle_content`
- **2.4s.2a** 　左侧说明:「壁纸+时钟：桌面淡去，留下壁纸和时钟。全黑：画面全部变黑。不变：桌面保持原样。」 `settings_idle_content_desc`
- **2.4s.2e** 选项层(按确定进去的那一页):页名同 2.4s.2,页名上方小字「设置 · 通用 · 闲置画面」,左侧说明同上
- **2.4s.2e.1** 　第 1 个选项:「壁纸+时钟」 `settings_idle_clock`
- **2.4s.2e.2** 　第 2 个选项:「全黑」 `settings_idle_black`
- **2.4s.2e.3** 　第 3 个选项:「不变」 `settings_idle_nofade`
- **2.5** 胶囊:「时钟显示」 `settings_clock_display`
- **2.5a** 　左侧说明:「右上角的时钟显示哪些内容。」 `settings_clock_display_desc`
- **2.5e** 选项层(按确定进去的那一页):页名同 2.5,页名上方小字「设置 · 通用」,左侧说明同上
- **2.5e.1** 　第 1 个选项:「仅时间」 `settings_clock_time_only`
- **2.5e.2** 　第 2 个选项:「时间与日期」 `settings_clock_time_date`
- **2.5e.3** 　第 3 个选项:「时间、日期与星期」 `settings_clock_time_date_weekday`

### 2.H 「默认桌面」页(通用 → 默认桌面)

- **2.H1** 页名:「默认桌面」 `home_settings_title`
- **2.H2** 页名上方小字「设置 · 通用」
- **2.H3** 当前默认桌面那一行的小标签:「当前」 `home_settings_current_label`
- **2.H4** 读不到当前默认桌面时:「未知」 `home_settings_unknown`
- **2.H5** 下面的说明:「按主页(Home)键时打开的桌面。Android 只允许在原生电视设置里更改。」 `home_settings_note`
- **2.H6** 右边第一颗胶囊:「去原生电视设置更改」 `home_settings_change_button`
- **2.H7** (R162 新增)第二颗条件胶囊「主页键接管」(UnitedU 不是默认桌面、或服务已开着时才出现)及它的四种状态小字、左侧说明、受限时的提示:`homekey_capsule` / `homekey_state_*` / `homekey_note_*` / `toast_homekey_restricted`,逐句见 `settings-inventory.md`「第三层 · 默认桌面页」

## 3 布局(第一层 → 布局)

- **3.0** 页名同 1.4,页名上方小字同 1.2
- **3.1** 胶囊:「编辑桌面」 `menu_edit`
- **3.1a** 　左侧说明:「添加或移除应用卡片、调整顺序、管理每一行。」 `menu_edit_desc`
- **3.1g** 　按确定:打开编辑桌面页
- **3.2** 胶囊:「卡片大小」 `settings_card_size`
- **3.2a** 　左侧说明:「小：一行 8 张 · 中：一行 6 张 · 大：一行 5 张」 `settings_card_size_desc`
- **3.2e** 选项层(按确定进去的那一页):页名同 3.2,页名上方小字「设置 · 布局」,左侧说明同上
- **3.2e.1** 　第 1 个选项:「大」 `settings_card_large`
- **3.2e.2** 　第 2 个选项:「中」 `settings_card_medium`
- **3.2e.3** 　第 3 个选项:「小」 `settings_card_small`
- **3.3** 胶囊:「显示应用名」 `settings_show_titles`
- **3.3a** 　左侧说明:「在每张卡片下方显示应用名。」 `settings_show_titles_desc`
- **3.3e** 选项层(按确定进去的那一页):页名同 3.3,页名上方小字「设置 · 布局」,左侧说明同上
- **3.3e.1** 　第 1 个选项:「关」 `settings_off`
- **3.3e.2** 　第 2 个选项:「开」 `settings_on`
- **3.4** 胶囊:「卡片饱和度」 `settings_card_saturation`
- **3.4a** 　左侧说明:「卡片颜色的浓淡。调低更素雅，100% 是应用原本的颜色。」 `settings_card_saturation_desc`
- **3.4c** 　滑块被选中时胶囊里显示的短名字:「卡片饱和度」 `shell_slider_card_saturation`
- **3.4d** 　滑块:左右键调,数值是百分比(没有文字)
- **3.5** 胶囊:「卡片亮度」 `settings_card_brightness`
- **3.5a** 　左侧说明:「卡片的明暗。调低更柔和，100% 是原本的亮度。」 `settings_card_brightness_desc`
- **3.5c** 　滑块被选中时胶囊里显示的短名字:「卡片亮度」 `shell_slider_card_brightness`
- **3.5d** 　滑块:左右键调,数值是百分比(没有文字)
- **3.6** 胶囊:「卡片透明度」 `settings_card_opacity`
- **3.6a** 　左侧说明:「卡片透出壁纸的程度。」 `settings_card_opacity_desc`
- **3.6c** 　滑块被选中时胶囊里显示的短名字:「卡片透明度」 `shell_slider_card_opacity`
- **3.6d** 　滑块:左右键调,数值是百分比(没有文字)

## 4 外观偏好(第一层 → 外观偏好)

- **4.0** 页名同 1.5,页名上方小字同 1.2
- **4.1** 胶囊:「切换壁纸」 `menu_wallpaper`
- **4.1a** 　左侧说明:「从内置壁纸或你自己上传的照片里选一张。」 `menu_wallpaper_desc`
- **4.1g** 　按确定:打开切换壁纸页
- **4.2** 胶囊:「壁纸模糊」 `settings_wallpaper_blur`
- **4.2a** 　左侧说明:「把壁纸调模糊，卡片会更醒目。」 `settings_wallpaper_blur_desc`
- **4.2c** 　滑块被选中时胶囊里显示的短名字:「壁纸模糊」 `shell_slider_wallpaper_blur`
- **4.2d** 　滑块:左右键调,数值是百分比(没有文字)
- **4.3** 胶囊:「壁纸亮度」 `settings_wallpaper_brightness`
- **4.3a** 　左侧说明:「调暗或调亮壁纸。」 `settings_wallpaper_brightness_desc`
- **4.3c** 　滑块被选中时胶囊里显示的短名字:「壁纸亮度」 `shell_slider_wallpaper_brightness`
- **4.3d** 　滑块:左右键调,数值是百分比(没有文字)
- **4.4** 胶囊:「主题色」 `settings_theme_color`
- **4.4a** 　左侧说明:「焦点框、按钮等高亮处的颜色。」 `settings_theme_color_desc`
- **4.4e** 选项层(按确定进去的那一页):页名同 4.4,页名上方小字「设置 · 外观偏好」,左侧说明同上
- **4.4e.1** 　第 1 个选项:「白」 `preset_white`
- **4.4e.2** 　第 2 个选项:「香槟」 `preset_champagne`
- **4.4e.3** 　第 3 个选项:「雾蓝」 `preset_blue`
- **4.4e.4** 　第 4 个选项:「淡紫」 `preset_purple`
- **4.4e.5** 　第 5 个选项:「鼠尾草」 `preset_green`
- **4.4f** 　开着「主题色跟随壁纸」时这一行右侧显示:「跟随壁纸」 `shell_theme_following_wallpaper`
- **4.5** 胶囊:「主题色跟随壁纸」 `settings_follow_wallpaper`
- **4.5a** 　左侧说明:「打开后，主题色会从当前壁纸里自动选取。」 `settings_follow_wallpaper_desc`
- **4.5e** 选项层(按确定进去的那一页):页名同 4.5,页名上方小字「设置 · 外观偏好」,左侧说明同上
- **4.5e.1** 　第 1 个选项:同 3.3e.1
- **4.5e.2** 　第 2 个选项:同 3.3e.2

## 5 屏保(第一层 → 屏保)

- **5.0** 页名同 1.6,页名上方小字同 1.2
- **5.1** 胶囊:「立即启动屏保」 `settings_start_screensaver`
- **5.1a** 　左侧说明:「马上播放屏保，按任意键退出。」 `settings_start_screensaver_desc`
- **5.1g** 　按确定:打开屏保(马上开始播放)
- **5.2** 胶囊:「屏保启动时间」 `settings_screensaver_after`
- **5.2a** 　左侧说明:「闲置画面出现之后，再过多久自动开始屏保。」 `settings_screensaver_after_desc`
- **5.2b** 　胶囊第二行小字(只在某些情况出现,见下):「从桌面进入闲置画面算起」 `settings_screensaver_note_after_standby`
- **5.2b2** 　第二行小字(屏保图库里没有照片时):「轮播里没有照片，屏保不会开始」 `settings_screensaver_note_empty`
- **5.2b3** 　第二行小字(「进入闲置」是从不时):「从最后一次按遥控器算起」 `settings_screensaver_note_from_input`
- **5.2e** 选项层(按确定进去的那一页):页名同 5.2,页名上方小字「设置 · 屏保」,左侧说明同上
- **5.2e.1** 　第 1 个选项:同 2.4s.1e.1
- **5.2e.2** 　第 2 个选项:同 2.4s.1e.2(这一处显示:1 分钟后)
- **5.2e.3** 　第 3 个选项:同 2.4s.1e.2(这一处显示:5 分钟后)
- **5.2e.4** 　第 4 个选项:同 2.4s.1e.2(这一处显示:10 分钟后)
- **5.2e.5** 　第 5 个选项:同 2.4s.1e.2(这一处显示:30 分钟后)
- **5.3** 胶囊:「屏保图库」 `settings_screensaver_gallery`
- **5.3a** 　左侧说明:「屏保会轮播带✓的图片和视频。可以采用或取消内置图片，也可以采用你自己上传的图片或视频。」 `settings_screensaver_gallery_desc`
- **5.3g** 　按确定:打开屏保图库页
- **5.4** 胶囊:「自动切换间隔」 `settings_screensaver_interval`
- **5.4a** 　左侧说明:「每张图片停留多久，再换下一张。」 `settings_screensaver_interval_desc`
- **5.4e** 选项层(按确定进去的那一页):页名同 5.4,页名上方小字「设置 · 屏保」,左侧说明同上
- **5.4e.1** 　第 1 个选项:「%1$d 秒」(例:30 秒) `settings_seconds`
- **5.4e.2** 　第 2 个选项:「%1$d 分钟」(例:1 分钟) `settings_idle_minutes`
- **5.4e.3** 　第 3 个选项:同 5.4e.2(这一处显示:5 分钟)
- **5.5** 胶囊:「系统屏保」 `settings_system_screensaver`
- **5.5a** 　左侧说明:「在其他应用里闲置时，由电视启动的屏保。选 UnitedU，就会播放同一个屏保图库。」 `settings_system_screensaver_desc`
- **5.5b** 　胶囊右侧的值由几段拼成,用「 · 」连接,例:「开 · UnitedU · 无操作 5 分钟后」
- **5.5b1** 　　开着:同 3.3e.2
- **5.5b2** 　　关着(只显示这一段):同 3.3e.1
- **5.5b3** 　　来源是本应用时:「UnitedU」 `app_name`
- **5.5b4** 　　启动时间:从不:「不会自动开始」 `settings_sys_never_starts`
- **5.5b5** 　　启动时间:秒:「无操作 %1$d 秒后」(例:无操作 30 秒后) `settings_sys_idle_seconds`
- **5.5b6** 　　启动时间:分钟:「无操作 %1$d 分钟后」(例:无操作 5 分钟后) `settings_sys_idle_minutes`
- **5.5b7** 　　启动时间:小时:「无操作 %1$d 小时后」(例:无操作 2 小时后) `settings_sys_idle_hours`
- **5.5b8** 　　读不到系统设置时:「查看」 `settings_sys_view`
- **5.5g** 　按确定:打开电视自己的屏保设置
- **5.6** 胶囊:「自动关屏」 `settings_screen_off`
- **5.6a** 　左侧说明:「多久没操作，电视就自动关闭屏幕。这是原生电视设置里的选项，按确定前往。」 `settings_screen_off_desc`
- **5.6b** 　胶囊右侧的值:无操作多久后关屏幕,用的是 5.5b5–5.5b7 那几句(例:无操作 24 小时后);系统设成从不时:
- **5.6b1** 　　从不:「从不」 `settings_sys_never`
- **5.6c** 　胶囊第二行小字(读到电视设置里的菜单名时,%1$s = 菜单路径):「在 %1$s 里修改」 `settings_screen_off_where`
- **5.6c2** 　胶囊第二行小字(读不到时):「在原生电视设置里找「自动关闭」「关机定时器」或「关闭显示屏」」 `settings_screen_off_where_generic`
- **5.6g** 　按确定:打开电视设置

## 6 电视设置

- **6.1** 第一层胶囊见 1.7;按确定直接打开电视自己的设置,没有我们的页面

## 7 关于(第一层 → 关于)

- **7.1** 页名:「关于 UnitedU」 `about_title`
- **7.2** 页名上方小字同 1.2
- **7.3** 版本号(%1$s 版本名,%2$d 版本号):「版本 %1$s (%2$d)」 `about_version`
- **7.4** 页脚(许可 + 项目地址;第三方声明随 APK 附带在 assets/licenses/):「Apache-2.0 · github.com/GordonWang1878/UnitedU-launcher」 `about_footer`
- **7.5** (原「许可声明标题」,2026-10-10 删)
- **7.6** (原「许可声明」,2026-10-10 删)
- **7.7** 第 1 颗胶囊(平时):「检查更新」 `about_check`
- **7.8** 第 1 颗胶囊(检查中):「检查中…」 `about_checking`
- **7.9** 第 1 颗胶囊(发现新版本后):「下载并安装」 `about_download`
- **7.10** 第 1 颗胶囊(下载中,%1$d = 百分比):「下载中 %1$d%%」 `about_downloading`
- **7.11** 第 1 颗胶囊(下载中,不知道进度):「下载中…」 `about_downloading_unknown`
- **7.12** 第 1 颗胶囊(校验中):「正在检查更新包…」 `about_verifying`
- **7.13** 第 1 颗胶囊(下好了,等你按):「安装更新」 `about_ready_install`
- **7.14** 第 2 颗胶囊:「恢复默认」 `settings_action_restore_defaults`
- **7.15** 按完「检查更新」后版本号下面出现的一句:
- **7.15.1** 　一句:「已是最新版本」 `about_latest`
- **7.15.2** 　一句:「发现新版本 %1$s」 `about_found`
- **7.15.3** 　一句:「连不上更新服务器，请检查网络后再试」 `about_net_failed`
- **7.15.4** 　一句:「暂时拿不到更新信息，请稍后再试」 `about_bad_json`
- **7.15.5** 　一句:「更新包不完整或已损坏，请再试一次」 `about_verify_failed`
- **7.15.6** 　一句:「下载失败，请稍后再试」 `about_download_failed`
- **7.15.7** 　一句:「这个更新包无法安装」 `about_install_failed`
- **7.15.8** 　一句:「请在电视上允许「安装未知应用」，然后重试」 `about_needs_permission`
- **7.15.9** 　一句:「允许「显示在其他应用上层」后，更新装好会自动回到桌面。回来按「安装更新」继续（不允许也能装）」 `about_overlay_hint`

### 7.R 「恢复默认」确认页(关于 → 恢复默认)

- **7.R1** 页名:「恢复默认设置？」 `restore_title`
- **7.R2** 页名上方小字「设置 · 关于」
- **7.R3** 说明:「只重置这些设置。桌面上的应用、卡片图和你的照片都不会变。」 `restore_body`
- **7.R4** 第 1 颗胶囊(默认焦点):「取消」 `dialog_cancel`
- **7.R5** 第 2 颗胶囊:「恢复」 `restore_ok`

## 8 只在特定情况出现的

- **8.1** 「通用」最后一行,只在电视的动画速度不是 1× 时出现:
- **8.1.1** 　胶囊:「系统动画速度」 `settings_sys_anim_scale`
- **8.1.2** 　左侧说明:「电视的动画速度不是 1×，界面动画会变慢或变快。按确定前往开发者选项修改。」 `settings_sys_anim_scale_desc`
- **8.1.3** 　胶囊右侧的值(变慢):「%1$s×，界面动画会变慢」(例:1.5×，界面动画会变慢) `settings_sys_anim_slower`
- **8.1.4** 　胶囊右侧的值(变快):「%1$s×，界面动画会变快」(例:0.5×，界面动画会变快) `settings_sys_anim_faster`
- **8.1.5** 　胶囊右侧的值(关掉了):「已关闭，界面没有动画」 `settings_sys_anim_off`
- **8.1.6** 　胶囊右侧的值(只有窗口 / 过渡动画不是 1×,%1$s、%2$s 是倍数):「窗口 %1$s×、过渡 %2$s×，只影响打开和切换应用」 `settings_sys_anim_window`
- **8.2** 带预览的选项层(卡片大小等)里光标移到别的选项时,预览框下面那行(%1$s = 光标所在的选项):「预览：%1$s · 按确定保存，按返回不改」 `shell_preview_hint`

## 9 设置里会弹出的提示条

- **9.1** 恢复默认之后:「已恢复默认设置」 `toast_restored`
- **9.2** 打不开电视的屏保设置时:「打不开系统屏保设置」 `toast_system_screensaver_unavailable`
- **9.3** 打不开别的设置页时(%1$s = 出错信息):「打不开：%1$s」 `toast_open_failed`
