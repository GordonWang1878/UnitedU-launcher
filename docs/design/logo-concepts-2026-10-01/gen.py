#!/usr/bin/env python3
"""UnitedU 图标 / banner 第三轮方案(2026-10-01):10 个方向,不沿用现有彩虹玻璃 U。

坐标约定:
- 图标按自适应图标的 108 × 108 dp 画布画;前景主体放在中心直径 66 的安全圆内,
  可见区是中心 72 × 72(18–90)。
- banner 按 320 × 180(16:9)画。

用法:python3 gen.py  → 生成 svg/ 下 20 个源文件 + overview.png + tv-context.png
(需要 rsvg-convert;字体用 macOS 自带的。)
"""
import os
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
SVG_DIR = os.path.join(HERE, "svg")
CJK = "PingFang SC, Hiragino Sans GB, sans-serif"


# ───────────────────────── 01 托盘 ─────────────────────────
def tray_bg(p):
    return '<rect width="108" height="108" fill="#EFE8DA"/>'


def tray_fg(p):
    return (
        '<circle cx="54" cy="64" r="8.5" fill="#E0402C"/>'
        '<rect x="40" y="40" width="13" height="13" fill="#F2B705"/>'
        '<path d="M55.5 53 L68.5 53 L62 40.5 Z" fill="#2156A8"/>'
        '<path d="M35 40 V60 A19 19 0 0 0 73 60 V40" fill="none" stroke="#1B1B1B" stroke-width="8"/>'
    )


def tray_banner(p):
    return (
        '<rect width="320" height="180" fill="#EFE8DA"/>'
        f'<g transform="translate(-6 -2) scale(1.55)">{tray_fg(p)}</g>'
        '<text x="128" y="106" font-family="Futura" font-weight="700" font-size="38" fill="#1B1B1B">'
        'United<tspan fill="#E0402C">U</tspan></text>'
    )


# ───────────────────────── 02 磁铁 ─────────────────────────
def magnet_bg(p):
    return '<rect width="108" height="108" fill="#172541"/>'


def magnet_fg(p, tiles=True):
    s = (
        '<path d="M41 50 V63 A13 13 0 0 0 67 63 V50" fill="none" stroke="#E5502F" stroke-width="12"/>'
        '<rect x="35" y="50" width="12" height="7" fill="#F3E9D2"/>'
        '<rect x="61" y="50" width="12" height="7" fill="#F3E9D2"/>'
    )
    if tiles:
        s += (
            '<rect x="34" y="32" width="10" height="10" rx="2.5" fill="#3FB3A9" transform="rotate(-14 39 37)"/>'
            '<rect x="49" y="27" width="10" height="10" rx="2.5" fill="#F0B43C" transform="rotate(9 54 32)"/>'
            '<rect x="64" y="32" width="10" height="10" rx="2.5" fill="#F3E9D2" transform="rotate(18 69 37)"/>'
        )
    return s


def magnet_banner(p):
    return (
        '<rect width="320" height="180" fill="#172541"/>'
        f'<g transform="translate(-14 -2) scale(1.6)">{magnet_fg(p)}</g>'
        '<text x="124" y="110" font-family="Gill Sans" font-weight="700" font-size="42" fill="#F3E9D2">UnitedU</text>'
    )


# ───────────────────────── 03 焦点 ─────────────────────────
def focus_bg(p):
    return '<rect width="108" height="108" fill="#0D0D0F"/>'


def focus_fg(p):
    dim = "#3A3A41"
    return (
        f'<rect x="60" y="37" width="17" height="17" rx="4" fill="{dim}"/>'
        f'<rect x="37" y="60" width="17" height="17" rx="4" fill="{dim}"/>'
        f'<rect x="60" y="60" width="17" height="17" rx="4" fill="{dim}"/>'
        '<rect x="34.5" y="34.5" width="22" height="22" rx="5.5" fill="#C8F560"/>'
        '<rect x="31" y="31" width="29" height="29" rx="8.5" fill="none" stroke="#FFFFFF" stroke-width="2.2"/>'
    )


def focus_banner(p):
    dim = "#2E2E34"
    tiles = ""
    xs = [26, 98, 170, 242]
    for i, x in enumerate(xs):
        if i == 1:
            continue
        tiles += f'<rect x="{x}" y="34" width="56" height="32" rx="5" fill="{dim}"/>'
    # 第 2 张是焦点:放大 + 实心 + 白描边
    tiles += (
        '<rect x="93" y="31" width="66" height="38" rx="6" fill="#C8F560"/>'
        '<rect x="89" y="27" width="74" height="46" rx="9" fill="none" stroke="#FFFFFF" stroke-width="2.2"/>'
    )
    return (
        '<rect width="320" height="180" fill="#0D0D0F"/>'
        + tiles
        + '<text x="26" y="140" font-family="Avenir Next" font-weight="600" font-size="38" fill="#FFFFFF" '
        'letter-spacing="-0.5">unitedu<tspan fill="#C8F560">.</tspan></text>'
    )


# ───────────────────────── 04 方向键 ─────────────────────────
def dpad_bg(p):
    return '<rect width="108" height="108" fill="#E8E6E1"/>'


def chevron(cx, cy, ang, col):
    return (
        f'<path d="M-3 1.6 L0 -1.6 L3 1.6" transform="translate({cx} {cy}) rotate({ang})" fill="none" '
        f'stroke="{col}" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>'
    )


def dpad_fg(p, bg="#E8E6E1"):
    return (
        '<circle cx="54" cy="54" r="22" fill="none" stroke="#FF5B1F" stroke-width="13"/>'
        '<path d="M38.4 38.4 A22 22 0 0 1 69.6 38.4" fill="none" stroke="#CFCBC3" stroke-width="13"/>'
        f'<path d="M32 32 L76 76 M76 32 L32 76" stroke="{bg}" stroke-width="1.6"/>'
        '<circle cx="54" cy="54" r="10" fill="#2B2B2B"/>'
        + chevron(54, 32, 0, "#8A8780") + chevron(54, 76, 180, "#FFFFFF")
        + chevron(32, 54, -90, "#FFFFFF") + chevron(76, 54, 90, "#FFFFFF")
    )


def dpad_banner(p):
    return (
        '<rect width="320" height="180" fill="#E8E6E1"/>'
        f'<g transform="translate(-6 0) scale(1.65)">{dpad_fg(p)}</g>'
        '<text x="138" y="78" font-family="Menlo" font-size="8" fill="#8A8780" letter-spacing="2.2">TV LAUNCHER</text>'
        '<text x="136" y="112" font-family="Helvetica Neue" font-weight="700" font-size="36" fill="#2B2B2B">UnitedU</text>'
        '<line x1="138" y1="126" x2="290" y2="126" stroke="#C9C5BD" stroke-width="1"/>'
        '<circle cx="296" cy="24" r="3.2" fill="#FF5B1F"/>'
    )


# ───────────────────────── 05 合 · 印章 ─────────────────────────
def seal_bg(p):
    return '<rect width="108" height="108" fill="#F2ECE1"/>'


def seal_glyph(stroke="#F2ECE1", w=4.2, dy=0.8):
    return (
        f'<g transform="translate(0 {dy})" fill="none" stroke="{stroke}" stroke-width="{w}" '
        'stroke-linejoin="miter" stroke-linecap="square">'
        '<path d="M36 51.5 L54 36 L72 51.5"/>'
        '<path d="M46 50 L62 50"/>'
        '<rect x="41.5" y="56" width="25" height="14.5"/>'
        '</g>'
    )


def seal_fg(p):
    return '<rect x="30" y="30" width="48" height="48" rx="3" fill="#C23B2E"/>' + seal_glyph()


def seal_banner(p):
    return (
        '<rect width="320" height="180" fill="#F2ECE1"/>'
        '<text x="54" y="107" font-family="Baskerville" font-size="44" fill="#222222">UnitedU</text>'
        f'<g transform="translate(213 59) scale(0.6)">{seal_fg(p)}</g>'
    )


# ───────────────────────── 06 测试色条 ─────────────────────────
BARS = ["#D8D8D8", "#F2D21B", "#1EC8E8", "#22C55E", "#D93BCB", "#E8342B", "#2443E6"]


def bars_bg(p):
    return '<rect width="108" height="108" fill="#0B0B0C"/>'


def bars_fg(p):
    w = 46 / 7
    rects = "".join(
        f'<rect x="{31 + i * w:.3f}" y="20" width="{w + 0.05:.3f}" height="70" fill="{c}"/>'
        for i, c in enumerate(BARS)
    )
    return (
        f'<mask id="{p}m" maskUnits="userSpaceOnUse" x="0" y="0" width="108" height="108">'
        '<path d="M38 33 V57 A16 16 0 0 0 70 57 V33" fill="none" stroke="#fff" stroke-width="14"/></mask>'
        f'<g mask="url(#{p}m)">{rects}</g>'
    )


def bars_banner(p):
    w = 320 / 7
    strip = "".join(
        f'<rect x="{i * w:.3f}" y="166" width="{w + 0.1:.3f}" height="14" fill="{c}"/>'
        for i, c in enumerate(BARS)
    )
    return (
        '<rect width="320" height="180" fill="#0B0B0C"/>'
        f'<g transform="translate(-14 -6) scale(1.5)">{bars_fg(p)}</g>'
        '<text x="118" y="108" font-family="DIN Condensed" font-weight="700" font-size="60" fill="#FFFFFF">UnitedU</text>'
        + strip
    )


# ───────────────────────── 07 衬线 ─────────────────────────
def serif_bg(p):
    return '<rect width="108" height="108" fill="#0F2B23"/>'


def serif_fg(p):
    return (
        '<text x="54" y="74" text-anchor="middle" font-family="Didot" font-size="56" fill="#F1EADB">U</text>'
        '<text x="54.5" y="66.5" text-anchor="middle" font-family="Didot" font-style="italic" font-size="20" fill="#C8A15A">u</text>'
    )


def serif_banner(p):
    return (
        '<rect width="320" height="180" fill="#0F2B23"/>'
        '<text x="160" y="104" text-anchor="middle" font-family="Didot" font-size="48" fill="#F1EADB">'
        'United<tspan font-style="italic" fill="#C8A15A">U</tspan></text>'
        '<line x1="132" y1="124" x2="188" y2="124" stroke="#C8A15A" stroke-width="0.9"/>'
    )


# ───────────────────────── 08 门洞 ─────────────────────────
def arch_bg(p):
    return '<rect width="108" height="108" fill="#CF6847"/>'


def arch_fg(p):
    # U 倒过来是一扇拱门:门里亮着灯,光洒在地上 = 回家(HOME 键)
    return (
        f'<linearGradient id="{p}spill" x1="0" y1="0" x2="0" y2="1">'
        '<stop offset="0" stop-color="#F6B48F"/><stop offset="1" stop-color="#CF6847"/></linearGradient>'
        f'<path d="M38 80 H70 L84 104 H24 Z" fill="url(#{p}spill)"/>'
        '<path d="M38 80 V50 A16 16 0 0 1 70 50 V80 Z" fill="#FFF3E3"/>'
        '<rect x="34" y="79" width="40" height="2.4" fill="#A9472C"/>'
    )


def arch_banner(p):
    return (
        '<rect width="320" height="180" fill="#CF6847"/>'
        f'<g transform="translate(-8 -14) scale(1.6)">{arch_fg(p)}</g>'
        '<text x="128" y="104" font-family="Avenir Next" font-weight="500" font-size="40" fill="#FFF3E3">UnitedU</text>'
    )


# ───────────────────────── 09 交叠 ─────────────────────────
def overlap_bg(p):
    return '<rect width="108" height="108" fill="#FAF8F3"/>'


def overlap_fg(p):
    # 屏幕(圆角框)和你(圆)叠在一起,重叠处是第三种颜色
    return (
        f'<clipPath id="{p}r"><rect x="28" y="32" width="38" height="30" rx="8"/></clipPath>'
        '<rect x="28" y="32" width="38" height="30" rx="8" fill="#FF6A55"/>'
        '<circle cx="64" cy="62" r="16" fill="#2F55E6"/>'
        f'<circle cx="64" cy="62" r="16" fill="#2A1E5C" clip-path="url(#{p}r)"/>'
    )


def overlap_banner(p):
    return (
        '<rect width="320" height="180" fill="#FAF8F3"/>'
        f'<g transform="translate(-8 -6) scale(1.55)">{overlap_fg(p)}</g>'
        '<text x="128" y="106" font-family="Avenir Next" font-weight="700" font-size="40" fill="#1D2340">'
        'United<tspan fill="#2F55E6">U</tspan></text>'
    )


# ───────────────────────── 10 像素 ─────────────────────────
WELL = [
    "XX......XX",
    "XX......XX",
    "XX......XX",
    "XX......XX",
    "XX......XX",
    "XXXpmmpXXX",
    ".XXXXXXXX.",
    "..XXXXXX..",
]
PIECE = ["YYY", ".Y."]
PIX = {"X": "#FFFFFF", "Y": "#FFD23F", "p": "#FF5DA2", "m": "#5CE1B4"}


def pixel_cells(rows, ox, oy, c):
    out = []
    for r, line in enumerate(rows):
        for col, ch in enumerate(line):
            if ch in PIX:
                out.append(
                    f'<rect x="{ox + col * c:.2f}" y="{oy + r * c:.2f}" width="{c + 0.04:.2f}" height="{c + 0.04:.2f}" fill="{PIX[ch]}"/>'
                )
    return "".join(out)


def pixel_bg(p):
    return '<rect width="108" height="108" fill="#2A36E6"/>'


def pixel_fg(p, ox=31.5, oy=29.25, c=4.5):
    return pixel_cells(PIECE, ox + 3.5 * c, oy, c) + pixel_cells(WELL, ox, oy + 3 * c, c)


GLYPHS = {
    "U": ["X...X", "X...X", "X...X", "X...X", "X...X", "X...X", ".XXX."],
    "n": [".....", ".....", "XXXX.", "X...X", "X...X", "X...X", "X...X"],
    "i": ["X", ".", "X", "X", "X", "X", "X"],
    "t": [".X..", ".X..", "XXXX", ".X..", ".X..", ".X..", "..XX"],
    "e": [".....", ".....", ".XXX.", "X...X", "XXXXX", "X....", ".XXXX"],
    "d": ["....X", "....X", ".XXXX", "X...X", "X...X", "X...X", ".XXXX"],
}


def pixel_word(word, ox, oy, c, fill="#FFFFFF"):
    out, x = [], ox
    for ch in word:
        g = GLYPHS[ch]
        for r, line in enumerate(g):
            for col, v in enumerate(line):
                if v == "X":
                    out.append(f'<rect x="{x + col * c:.2f}" y="{oy + r * c:.2f}" width="{c + 0.04:.2f}" height="{c + 0.04:.2f}" fill="{fill}"/>')
        x += (len(g[0]) + 1) * c
    return "".join(out)


def pixel_banner(p):
    return (
        '<rect width="320" height="180" fill="#2A36E6"/>'
        + pixel_fg(p, ox=30, oy=57, c=6)
        + pixel_word("UnitedU", 112, 73, 5)
    )


CONCEPTS = [
    ("01", "tray", "托盘", "一只碗只装你放进去的东西:三种形状 = 你挑的应用", "包豪斯平面 · 奶油底 + 红黄蓝", tray_bg, tray_fg, tray_banner),
    ("02", "magnet", "磁铁", "U 形磁铁把你的应用吸到一起", "中世纪海报 · 藏青 + 朱红", magnet_bg, magnet_fg, magnet_banner),
    ("03", "focus", "焦点", "电视遥控的核心是焦点:你按到哪,哪里亮", "极简黑 + 荧光青柠", focus_bg, focus_fg, focus_banner),
    ("04", "dpad", "方向键", "遥控器方向键,亮起的三段正好是 U", "工业设计(博朗风)· 浅灰 + 橙", dpad_bg, dpad_fg, dpad_banner),
    ("05", "seal", "印章", "United 的中文本义「合」,刻成白文印", "东方 · 宣纸 + 朱砂", seal_bg, seal_fg, seal_banner),
    ("06", "bars", "色条", "电视台测试色条裁成 U:一看就是电视的东西", "广播复古 · 黑底七色", bars_bg, bars_fg, bars_banner),
    ("07", "serif", "衬线", "大 U 里藏一个小 u:United + you", "杂志衬线 · 墨绿 + 象牙 + 金", serif_bg, serif_fg, serif_banner),
    ("08", "arch", "门洞", "U 倒过来是一扇亮着灯的门 = 回家(HOME 键)", "地中海平面 · 陶土墙 + 暖光", arch_bg, arch_fg, arch_banner),
    ("09", "overlap", "交叠", "屏幕(框)和你(圆)叠在一起,重叠处是第三种颜色", "扁平叠色 · 珊瑚 + 钴蓝 + 深紫", overlap_bg, overlap_fg, overlap_banner),
    ("10", "pixel", "像素", "俄罗斯方块:你的应用一块块落进 U 里", "8-bit 像素 · 钴蓝 + 白", pixel_bg, pixel_fg, pixel_banner),
]


def icon_full(p, bg, fg):
    return f'<g id="{p}background">{bg(p)}</g><g id="{p}foreground">{fg(p)}</g>'


def masked_icon(p, bg, fg, x, y, size, shape="circle"):
    """按 108 画布的可见区 18–90 取景,再套圆形 / 圆角方形遮罩。"""
    clip = (
        '<circle cx="54" cy="54" r="36"/>' if shape == "circle" else '<rect x="18" y="18" width="72" height="72" rx="16"/>'
    )
    return (
        f'<svg x="{x}" y="{y}" width="{size}" height="{size}" viewBox="18 18 72 72">'
        f'<clipPath id="{p}clip">{clip}</clipPath>'
        f'<g clip-path="url(#{p}clip)">{bg(p)}{fg(p)}</g></svg>'
    )


def banner_at(p, banner, x, y, w, rx=10):
    h = w * 9 / 16
    return (
        f'<svg x="{x}" y="{y}" width="{w}" height="{h}" viewBox="0 0 320 180">'
        f'<clipPath id="{p}bclip"><rect width="320" height="180" rx="{rx * 320 / w:.2f}"/></clipPath>'
        f'<g clip-path="url(#{p}bclip)">{banner(p)}</g></svg>'
    )


def write(path, s):
    with open(path, "w") as f:
        f.write(s)


def render(svg_path, png_path):
    subprocess.run(["rsvg-convert", svg_path, "-o", png_path], check=True)


def sources():
    os.makedirs(SVG_DIR, exist_ok=True)
    for n, key, *_rest in CONCEPTS:
        bg, fg, banner = _rest[3], _rest[4], _rest[5]
        p = f"c{n}"
        write(
            os.path.join(SVG_DIR, f"{n}-{key}-icon.svg"),
            f'<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 108 108">{icon_full(p, bg, fg)}</svg>',
        )
        write(
            os.path.join(SVG_DIR, f"{n}-{key}-banner.svg"),
            f'<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 320 180">{banner(p)}</svg>',
        )


def overview():
    W, cell_h, top = 1640, 360, 92
    H = top + 5 * cell_h + 20
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">',
        f'<rect width="{W}" height="{H}" fill="#151517"/>',
        f'<text x="40" y="56" font-family="{CJK}" font-size="30" font-weight="600" fill="#F2F2F2">'
        'UnitedU 图标 + Banner · 10 个方向(2026-10-01)</text>',
    ]
    for i, (n, key, name, idea, style, bg, fg, banner) in enumerate(CONCEPTS):
        col, row = i % 2, i // 2
        x0, y0 = 40 + col * 800, top + row * cell_h
        p = f"o{n}"
        parts.append(masked_icon(p + "i", bg, fg, x0, y0 + 30, 210))
        parts.append(banner_at(p + "b", banner, x0 + 236, y0 + 0, 500))
        parts.append(
            f'<text x="{x0}" y="{y0 + 312}" font-family="{CJK}" font-size="24" font-weight="600" fill="#F2F2F2">'
            f'{n}  {name}</text>'
            f'<text x="{x0 + 130}" y="{y0 + 312}" font-family="{CJK}" font-size="20" fill="#B9B9C0">{idea}</text>'
            f'<text x="{x0 + 130}" y="{y0 + 340}" font-family="{CJK}" font-size="17" fill="#7E7E88">{style}</text>'
        )
    parts.append("</svg>")
    path = os.path.join(SVG_DIR, "_overview.svg")
    write(path, "".join(parts))
    render(path, os.path.join(HERE, "overview.png"))


def tv_context():
    """1920×1080 = 电视界面像素(xhdpi):banner 160×90 dp = 320×180 px,图标 48 dp = 96 px。"""
    W, H = 1920, 1080
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">',
        '<defs><linearGradient id="wall" x1="0" y1="0" x2="0" y2="1">'
        '<stop offset="0" stop-color="#22242A"/><stop offset="1" stop-color="#0E0F12"/></linearGradient></defs>',
        f'<rect width="{W}" height="{H}" fill="url(#wall)"/>',
        f'<text x="96" y="110" font-family="{CJK}" font-size="34" fill="#E6E6EA">电视上的实际大小(1080p 界面,banner 320×180 px · 图标 96 px)</text>',
    ]
    xs = [96 + k * 352 for k in range(5)]
    for i, (n, key, name, idea, style, bg, fg, banner) in enumerate(CONCEPTS):
        row, col = i // 5, i % 5
        x, y = xs[col], 190 + row * 300
        p = f"t{n}"
        if i == 6:  # 模拟一张卡在焦点上:放大 1.1 + 白描边
            sw = 352
            sx, sy = x - 16, y - 9
            parts.append(f'<rect x="{sx - 5}" y="{sy - 5}" width="{sw + 10}" height="{sw * 9 / 16 + 10}" rx="16" fill="none" stroke="#FFFFFF" stroke-width="4"/>')
            parts.append(banner_at(p, banner, sx, sy, sw, rx=12))
        else:
            parts.append(banner_at(p, banner, x, y, 320, rx=12))
        parts.append(f'<text x="{x}" y="{y + 222}" font-family="{CJK}" font-size="24" fill="#9A9AA3">{n} {name}</text>')
    parts.append(f'<text x="96" y="830" font-family="{CJK}" font-size="26" fill="#9A9AA3">图标(圆形遮罩)</text>')
    for i, (n, key, name, idea, style, bg, fg, banner) in enumerate(CONCEPTS):
        x = 96 + i * 176
        parts.append(masked_icon(f"ti{n}", bg, fg, x, 860, 96))
        parts.append(masked_icon(f"ts{n}", bg, fg, x + 108, 908, 48))
        parts.append(f'<text x="{x + 30}" y="1000" font-family="{CJK}" font-size="22" fill="#9A9AA3">{n}</text>')
    parts.append("</svg>")
    path = os.path.join(SVG_DIR, "_tv-context.svg")
    write(path, "".join(parts))
    render(path, os.path.join(HERE, "tv-context.png"))


if __name__ == "__main__":
    sources()
    overview()
    tv_context()
    print("ok")
