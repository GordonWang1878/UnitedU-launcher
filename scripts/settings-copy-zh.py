"""设置页文案盘点(2026-10-01 Gordon 要的 review 稿):按屏幕顺序、编号,简体中文,写到 docs/design/settings-copy-zh.md。

结构来自 settingsGroups() / SHELL_ROOT 导出的 /tmp/settings-copy-dump.json(导出用的临时单测已删;要重导出见 WORKLOG 2026-10-01),
文案读 app/src/main/res/values/strings.xml。设置页结构变了要重导出再跑。
"""
import json, re, html
REPO = "/Users/gordonwang/GitHub/UnitedU-launcher"
xml = open(f"{REPO}/app/src/main/res/values/strings.xml", encoding="utf-8").read()
S = {}
for m in re.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>', xml, re.S):
    v = m.group(2).replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n")
    S[m.group(1)] = html.unescape(v)
d = json.load(open("/tmp/settings-copy-dump.json"))
groups = {g["id"]: g for g in d["groups"]}
seen = {}          # key -> 编号(第一次出现的地方)
out = []
def line(num, where, key, example=None):
    if key in seen:
        ex = f"(这一处显示:{example})" if example else ""
        out.append(f"- **{num}** {where}:同 {seen[key]}{ex}")
        return
    seen[key] = num
    ex = f"(例:{example})" if example else ""
    txt = S.get(key, f"<缺 {key}>")
    out.append(f"- **{num}** {where}:「{txt}」{ex} `{key}`")
def fmt(key, arg):
    t = S[key]
    return re.sub(r"%1\$[ds]", str(arg), t)
def h(t): out.append(""); out.append(t); out.append("")

out.append("# 设置页文案(简体中文)")
out.append("")
out.append("**怎么改**:直接改「」里的字;编号和行尾的 `key` 别动(我靠它找回对应的那一句)。带 `%1$d` / `%1$s` 的是程序自动填的数字或名字,改字时保留它们。"
           "同一句话在好几处出现时只在第一处给原文,后面写「同 X」——改第一处,所有地方一起改。繁体、英文我按你改完的简体跟着改。")
out.append("")
out.append("「胶囊」= 右边一颗颗的按钮;「左侧说明」= 光标停在某一行时,左半屏显示的那段话;「选项层」= 在一行上按确定后进去的那一页。")

# 1 第一层
h("## 1 设置第一层")
out.append("- **1.1** 左侧页名上方小字:「UnitedU」(品牌名,不进翻译)")
line("1.2", "左侧页名", "menu_settings_title")
root_titles = {}
for i, e in enumerate(d["root"], 1):
    n = f"1.{i + 2}"
    line(n, f"第 {i} 颗胶囊", e["label"])
    line(f"{n}a", "　这颗胶囊的第二行小字", e["hint"])
    root_titles[e["id"]] = (n, e["label"])

section = 2
def options_block(prefix, r, title_num, path_title):
    """选项层:页名 = 胶囊标题,页名上方 = 路径,左侧说明 = 这一行的说明,右边 = 选项。"""
    out.append(f"- **{prefix}** 选项层(按确定进去的那一页):页名同 {title_num},页名上方小字「设置 · {path_title}」,左侧说明同上")
    args = r.get("optionArgs") or []
    for j, o in enumerate(r["options"], 1):
        arg = args[j - 1] if j - 1 < len(args) else None
        if arg is not None:
            line(f"{prefix}.{j}", f"　第 {j} 个选项", o, example=fmt(o, arg))
        else:
            line(f"{prefix}.{j}", f"　第 {j} 个选项", o)

def rows_block(sec, rows, path_title, depth_label=""):
    k = 1
    for r in rows:
        num = f"{sec}.{k}"
        if r["type"] == "control":
            line(num, f"胶囊{depth_label}", r["label"])
            if r.get("desc"): line(f"{num}a", "　左侧说明", r["desc"])
            if r.get("note"): line(f"{num}b", "　胶囊第二行小字(只在某些情况出现,见下)", r["note"])
            if r["id"] == "screensaverAfter":
                line(f"{num}b2", "　第二行小字(屏保图库里没有照片时)", "settings_screensaver_note_empty")
                line(f"{num}b3", "　第二行小字(「进入闲置」是从不时)", "settings_screensaver_note_from_input")
            if r["kind"] == "SLIDER":
                short = {"wallpaperBlur": "shell_slider_wallpaper_blur", "wallpaperBrightness": "shell_slider_wallpaper_brightness",
                         "cardSaturation": "shell_slider_card_saturation", "cardBrightness": "shell_slider_card_brightness",
                         "cardOpacity": "shell_slider_card_opacity"}.get(r["id"])
                if short: line(f"{num}c", "　滑块被选中时胶囊里显示的短名字", short)
                out.append(f"- **{num}d** 　滑块:左右键调,数值是百分比(没有文字)")
            elif r["options"]:
                options_block(f"{num}e", r, num, path_title)
            if r["id"] == "themeColor":
                line(f"{num}f", "　开着「主题色跟随壁纸」时这一行右侧显示", "shell_theme_following_wallpaper")
        elif r["type"] == "action":
            line(num, f"胶囊{depth_label}", r["label"])
            if r.get("desc"): line(f"{num}a", "　左侧说明", r["desc"])
            if r.get("hint"): line(f"{num}b", "　胶囊右侧的值", r["hint"])
            if r["id"] == "setDefaultHome":
                line(f"{num}b", "　胶囊右侧的值(系统没设默认桌面时;设了就显示那个桌面的名字)", "settings_home_not_set")
            if r["id"] == "systemScreensaver":
                out.append(f"- **{num}b** 　胶囊右侧的值由几段拼成,用「 · 」连接,例:「开 · UnitedU · 无操作 5 分钟后」")
                line(f"{num}b1", "　　开着", "settings_on")
                line(f"{num}b2", "　　关着(只显示这一段)", "settings_off")
                line(f"{num}b3", "　　来源是本应用时", "app_name")
                line(f"{num}b4", "　　启动时间:从不", "settings_sys_never_starts")
                line(f"{num}b5", "　　启动时间:秒", "settings_sys_idle_seconds", example=fmt("settings_sys_idle_seconds", 30))
                line(f"{num}b6", "　　启动时间:分钟", "settings_sys_idle_minutes", example=fmt("settings_sys_idle_minutes", 5))
                line(f"{num}b7", "　　启动时间:小时", "settings_sys_idle_hours", example=fmt("settings_sys_idle_hours", 2))
                line(f"{num}b8", "　　读不到系统设置时", "settings_sys_view")
            if r["id"] == "screenOff":
                out.append(f"- **{num}b** 　胶囊右侧的值:无操作多久后关屏幕,用的是 5.5b5–5.5b7 那几句(例:无操作 24 小时后);系统设成从不时:")
                line(f"{num}b1", "　　从不", "settings_sys_never")
                line(f"{num}c", "　胶囊第二行小字(读到电视设置里的菜单名时,%1$s = 菜单路径)", "settings_screen_off_where")
                line(f"{num}c2", "　胶囊第二行小字(读不到时)", "settings_screen_off_where_generic")
            jumps = {"editLayout": "编辑桌面页", "pickWallpaper": "换壁纸页", "openImport": "扫码页(从手机添加)",
                     "screensaverGallery": "屏保图库页", "systemScreensaver": "电视自己的屏保设置", "screenOff": "电视设置",
                     "startScreensaver": "屏保(马上开始播放)", "setDefaultHome": "下面的「默认桌面」页"}
            if r["id"] in jumps:
                out.append(f"- **{num}g** 　按确定:打开{jumps[r['id']]}")
        elif r["type"] == "sub":
            line(num, f"胶囊{depth_label}(右侧显示子页里各行的当前值)", r["label"])
            if r.get("desc"): line(f"{num}a", "　左侧说明", r["desc"])
            out.append(f"- **{num}s** 　子页(按确定进去):页名同 {num},页名上方小字「设置 · {path_title}」,里面是:")
            rows_block(f"{num}s", r["rows"], f"{path_title} · {S[r['label']]}", depth_label="(子页)")
        k += 1

order = ["GENERAL", "LAYOUT", "APPEARANCE", "SCREENSAVER"]
for gid in order:
    g = groups[gid]
    title = S[g["title"]]
    h(f"## {section} {title}(第一层 → {title})")
    out.append(f"- **{section}.0** 页名同 {root_titles['g:' + gid][0]},页名上方小字同 1.2")
    rows_block(str(section), g["rows"], title)
    if gid == "GENERAL":
        h(f"### {section}.H 「默认桌面」页(通用 → 默认桌面)")
        line(f"{section}.H1", "页名", "home_settings_title")
        out.append(f"- **{section}.H2** 页名上方小字「设置 · 通用」")
        line(f"{section}.H3", "当前默认桌面那一行的小标签", "home_settings_current_label")
        line(f"{section}.H4", "读不到当前默认桌面时", "home_settings_unknown")
        line(f"{section}.H5", "下面的说明", "home_settings_note")
        line(f"{section}.H6", "右边唯一一颗胶囊", "home_settings_change_button")
    section += 1

h(f"## {section} 电视设置")
out.append(f"- **{section}.1** 第一层胶囊见 1.7;按确定直接打开电视自己的设置,没有我们的页面")
section += 1

h(f"## {section} 关于(第一层 → 关于)")
s = section
line(f"{s}.1", "页名", "about_title")
out.append(f"- **{s}.2** 页名上方小字同 1.2")
line(f"{s}.3", "版本号(%1$s 版本名,%2$d 版本号)", "about_version")
line(f"{s}.4", "许可声明标题", "about_license_title")
line(f"{s}.5", "许可声明", "about_license")
line(f"{s}.6", "项目地址", "about_repo")
line(f"{s}.7", "第 1 颗胶囊(平时)", "about_check")
line(f"{s}.8", "第 1 颗胶囊(检查中)", "about_checking")
line(f"{s}.9", "第 1 颗胶囊(发现新版本后)", "about_download")
line(f"{s}.10", "第 1 颗胶囊(下载中,%1$d = 百分比)", "about_downloading")
line(f"{s}.11", "第 1 颗胶囊(下载中,不知道进度)", "about_downloading_unknown")
line(f"{s}.12", "第 1 颗胶囊(校验中)", "about_verifying")
line(f"{s}.13", "第 1 颗胶囊(下好了,等你按)", "about_ready_install")
line(f"{s}.14", "第 2 颗胶囊", "settings_action_restore_defaults")
out.append(f"- **{s}.15** 按完「检查更新」后版本号下面出现的一句:")
for j, k in enumerate(["about_latest", "about_found", "about_net_failed", "about_bad_json", "about_verify_failed",
                       "about_download_failed", "about_install_failed", "about_needs_permission", "about_overlay_hint"], 1):
    line(f"{s}.15.{j}", "　一句", k)
h(f"### {s}.R 「恢复默认」确认页(关于 → 恢复默认)")
line(f"{s}.R1", "页名", "restore_title")
out.append(f"- **{s}.R2** 页名上方小字「设置 · 关于」")
line(f"{s}.R3", "说明", "restore_body")
line(f"{s}.R4", "第 1 颗胶囊(默认焦点)", "dialog_cancel")
line(f"{s}.R5", "第 2 颗胶囊", "restore_ok")
section += 1

h(f"## {section} 只在特定情况出现的")
s = section
out.append(f"- **{s}.1** 「通用」最后一行,只在电视的动画速度不是 1× 时出现:")
line(f"{s}.1.1", "　胶囊", "settings_sys_anim_scale")
line(f"{s}.1.2", "　左侧说明", "settings_sys_anim_scale_desc")
line(f"{s}.1.3", "　胶囊右侧的值(变慢)", "settings_sys_anim_slower", example=fmt("settings_sys_anim_slower", "1.5"))
line(f"{s}.1.4", "　胶囊右侧的值(变快)", "settings_sys_anim_faster", example=fmt("settings_sys_anim_faster", "0.5"))
line(f"{s}.1.5", "　胶囊右侧的值(关掉了)", "settings_sys_anim_off")
line(f"{s}.1.6", "　胶囊右侧的值(只有窗口 / 过渡动画不是 1×,%1$s、%2$s 是倍数)", "settings_sys_anim_window")
line(f"{s}.2", "带预览的选项层(卡片大小等)里光标移到别的选项时,预览框下面那行(%1$s = 光标所在的选项)", "shell_preview_hint")
section += 1

h(f"## {section} 设置里会弹出的提示条")
s = section
line(f"{s}.1", "恢复默认之后", "toast_restored")
line(f"{s}.2", "打不开电视的屏保设置时", "toast_system_screensaver_unavailable")
line(f"{s}.3", "打不开别的设置页时(%1$s = 出错信息)", "toast_open_failed")

open(f"{REPO}/docs/design/settings-copy-zh.md", "w", encoding="utf-8").write("\n".join(out) + "\n")
print(len([l for l in out if l.startswith("- **")]), "lines")
missing = [k for k in seen if k not in S]
print("missing keys:", missing)
