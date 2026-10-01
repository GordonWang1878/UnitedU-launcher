#!/usr/bin/env python3
"""01 托盘的变体(2026-10-01 第二轮):为电视远看加粗、加大、减细节。

和第一轮的区别:
- 字标直接从 Futura Bold 取字形转成路径(不靠渲染时找字体),宽度可精确算;
- 碗(U)的线宽 8 → 10.5,里面的形状放大,有的变体减到两件 / 一件;
- banner 字号从 38 加到 46(横排)/ 62(字标合一)。

坐标约定同上一层 gen.py:图标 108 × 108 dp 画布,主体在中心直径 66 的安全圆内;banner 320 × 180。
用法:python3 gen.py → svg/ + variants.png + variants-tv.png
"""
import os
import subprocess

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTCollection

HERE = os.path.dirname(os.path.abspath(__file__))
SVG_DIR = os.path.join(HERE, "svg")
CJK = "PingFang SC, Hiragino Sans GB, sans-serif"

FUTURA = TTCollection("/System/Library/Fonts/Supplemental/Futura.ttc").fonts[2]  # Bold
assert FUTURA["name"].getDebugName(2) == "Bold"
UPM = FUTURA["head"].unitsPerEm
CAP = FUTURA["OS/2"].sCapHeight / UPM
_CMAP = FUTURA.getBestCmap()
_GLYPHS = FUTURA.getGlyphSet()
_KERN = FUTURA["kern"].kernTables[0].kernTable if "kern" in FUTURA else {}


def text_width(s, size):
    w, prev = 0.0, None
    for ch in s:
        g = _CMAP[ord(ch)]
        if prev is not None:
            w += _KERN.get((prev, g), 0)
        w += FUTURA["hmtx"][g][0]
        prev = g
    return w * size / UPM


def text_paths(s, x, baseline, size, fills):
    """逐字转路径;fills 与 s 等长,允许每个字单独上色。"""
    sc, pen_x, prev, out = size / UPM, x, None, []
    for ch, fill in zip(s, fills):
        g = _CMAP[ord(ch)]
        if prev is not None:
            pen_x += _KERN.get((prev, g), 0) * sc
        sp = SVGPathPen(_GLYPHS)
        _GLYPHS[g].draw(TransformPen(sp, (sc, 0, 0, -sc, pen_x, baseline)))
        out.append(f'<path d="{sp.getCommands()}" fill="{fill}"/>')
        pen_x += FUTURA["hmtx"][g][0] * sc
        prev = g
    return "".join(out)


# ───────────────────────── 图形部件 ─────────────────────────
def bowl(cx, top, width, stroke, arc_cy, color):
    r = (width - stroke) / 2
    return (
        f'<path d="M{cx - r:.2f} {top:.2f} V{arc_cy:.2f} A{r:.2f} {r:.2f} 0 0 0 {cx + r:.2f} {arc_cy:.2f} V{top:.2f}" '
        f'fill="none" stroke="{color}" stroke-width="{stroke:.2f}"/>'
    )


def circle(cx, cy, r, c):
    return f'<circle cx="{cx:.2f}" cy="{cy:.2f}" r="{r:.2f}" fill="{c}"/>'


def square(x, y, s, c, rot=0):
    t = f' transform="rotate({rot} {x + s / 2:.2f} {y + s / 2:.2f})"' if rot else ""
    return f'<rect x="{x:.2f}" y="{y:.2f}" width="{s:.2f}" height="{s:.2f}" fill="{c}"{t}/>'


def triangle(x, base_y, s, c):
    return f'<path d="M{x:.2f} {base_y:.2f} L{x + s:.2f} {base_y:.2f} L{x + s / 2:.2f} {base_y - s:.2f} Z" fill="{c}"/>'


CREAM, INK, RED, YEL, BLU = "#EFE8DA", "#1B1B1B", "#E0402C", "#F2B705", "#2156A8"

# 碗的统一几何(图标坐标):外宽 52、线宽 10.5、口在 y=44、底弧圆心 y=60 → 外沿最低 86
BOWL = dict(cx=54, top=44, width=52, stroke=10.5, arc_cy=60)


def mark_three(ink=INK, red=RED, yel=YEL, blu=BLU, dy=0):
    b = dict(BOWL, top=BOWL["top"] + dy, arc_cy=BOWL["arc_cy"] + dy)
    return (
        circle(54, 65.5 + dy, 10, red)
        + square(39, 37 + dy, 14, yel)
        + triangle(55, 51 + dy, 14, blu)
        + bowl(color=ink, **b)
    )


def mark_two(ink=INK, red=RED, yel=YEL):
    return circle(54, 63, 12.5, red) + square(45.5, 30, 17, yel, rot=14) + bowl(color=ink, **BOWL)


def mark_one(ink=INK, red=RED):
    b = dict(BOWL, top=40, arc_cy=56)
    return circle(54, 57.5, 14, red) + bowl(color=ink, **b)


# 每个变体:编号、名字、说明、图标底色、图标前景、前景在图标坐标里的外框(x0, y0, x1, y1)、碗的上下沿、banner 底色、字色
VARIANTS = [
    dict(k="A", name="放大原版", note="构图不变,线宽 8→10.5,三个形状都放大", bg=CREAM, fg=mark_three(),
         box=(28, 37, 80, 86), u=(44, 86), bbg=CREAM, fills=[INK] * 6 + [RED]),
    dict(k="B", name="两件", note="去掉三角,圆和方块再放大", bg=CREAM, fg=mark_two(),
         box=(28, 27, 80, 86), u=(44, 86), bbg=CREAM, fills=[INK] * 6 + [RED]),
    dict(k="C", name="一个红点", note="只留一个大红圆,远看最干净", bg=CREAM, fg=mark_one(),
         box=(28, 40, 80, 82), u=(40, 82), bbg=CREAM, fills=[INK] * 6 + [RED]),
    dict(k="D", name="深底", note="同 A,换成深色底:在电视的深色界面里不刺眼", bg="#1C1C1E",
         fg=mark_three(ink=CREAM, red="#F0503A", yel="#F5BE1A", blu="#4C86F0"),
         box=(28, 37, 80, 86), u=(44, 86), bbg="#1C1C1E", fills=[CREAM] * 6 + ["#F0503A"]),
    dict(k="E", name="蓝底", note="同 B,整块包豪斯蓝:在应用列表里最跳", bg=BLU,
         fg=mark_two(ink="#F3EBDD", red="#EE4A33", yel="#F7C21B"),
         box=(28, 27, 80, 86), u=(44, 86), bbg=BLU, fills=["#F3EBDD"] * 6 + ["#F7C21B"]),
]


def banner_row(v, size=46, ratio=1.8, gap=12, avail=280):
    """横排:标在左、字在右,整组水平居中;碗高 = ratio × 字的大写高,字的大写中线对齐碗的中线。
    总宽超过 avail(左右各留 20)就整组等比缩小。"""
    x0, y0, x1, y1 = v["box"]
    u0, u1 = v["u"]
    sc = ratio * CAP * size / (u1 - u0)
    total = (x1 - x0) * sc + gap + text_width("UnitedU", size)
    if total > avail:
        f = avail / total
        size, sc, gap = size * f, sc * f, gap * f
    mw, mh = (x1 - x0) * sc, (y1 - y0) * sc
    tw = text_width("UnitedU", size)
    left = (320 - (mw + gap + tw)) / 2
    top = (180 - mh) / 2
    u_mid = top + ((u0 + u1) / 2 - y0) * sc
    baseline = u_mid + CAP * size / 2
    v["_size"] = size
    return (
        f'<rect width="320" height="180" fill="{v["bbg"]}"/>'
        f'<g transform="translate({left - x0 * sc:.2f} {top - y0 * sc:.2f}) scale({sc:.4f})">{v["fg"]}</g>'
        + text_paths("UnitedU", left + mw + gap, baseline, size, v["fills"])
    )


def banner_word(size=60):
    """字标合一:最后一个 U 就是碗(比普通 U 宽一点),三个形状冒出碗口,字可以做到最大。"""
    word = "United"
    cap = CAP * size
    stem = 0.165 * size  # 和 Futura Bold 的竖画差不多粗
    bw = cap * 1.16
    gap = size * 0.07
    tw = text_width(word, size)
    left = (320 - (tw + gap + bw)) / 2
    baseline = 90 + cap / 2 + 8  # 形状会冒出碗口,整体略往下压
    cx = left + tw + gap + bw / 2
    top = baseline - cap
    arc_cy = baseline - bw / 2
    inner = bw / 2 - stem
    cr = inner * 0.8
    sq = inner * 0.98
    return (
        f'<rect width="320" height="180" fill="{CREAM}"/>'
        + text_paths(word, left, baseline, size, [INK] * 6)
        + circle(cx, baseline - stem - cr - 0.5, cr, RED)
        + square(cx - inner + 0.6, top - sq * 0.55, sq, YEL)
        + triangle(cx + 0.9, top + sq * 0.45, sq, BLU)
        + bowl(cx, top, bw, stem, arc_cy, INK)
    )


def banner_stack(v, mark_h=68, size=56):
    """上下排:标在上居中,字在下居中。"""
    x0, y0, x1, y1 = v["box"]
    sc = mark_h / (y1 - y0)
    mw = (x1 - x0) * sc
    cap = CAP * size
    gap = 12
    total = mark_h + gap + cap
    top = (180 - total) / 2
    tw = text_width("UnitedU", size)
    return (
        f'<rect width="320" height="180" fill="{v["bbg"]}"/>'
        f'<g transform="translate({160 - mw / 2 - x0 * sc:.2f} {top - y0 * sc:.2f}) scale({sc:.4f})">{v["fg"]}</g>'
        + text_paths("UnitedU", 160 - tw / 2, top + mark_h + gap + cap, size, v["fills"])
    )


ORIGINAL = dict(
    k="原", name="第一轮 01", note="对照:线宽 8,字号 38",
    bg=CREAM,
    fg=(
        circle(54, 64, 8.5, RED) + square(40, 40, 13, YEL)
        + '<path d="M55.5 53 L68.5 53 L62 40.5 Z" fill="#2156A8"/>'
        + bowl(54, 40, 46, 8, 60, INK)
    ),
)
ORIGINAL_BANNER = (
    f'<rect width="320" height="180" fill="{CREAM}"/>'
    f'<g transform="translate(-6 -2) scale(1.55)">{ORIGINAL["fg"]}</g>'
    '<text x="128" y="106" font-family="Futura" font-weight="700" font-size="38" fill="#1B1B1B">'
    'United<tspan fill="#E0402C">U</tspan></text>'
)

ITEMS = [(ORIGINAL, ORIGINAL_BANNER)] + [(v, banner_row(v)) for v in VARIANTS] + [
    (dict(VARIANTS[0], k="F", name="字标合一", note="banner 里最后一个 U 就是碗,字号 60(+58%)"), banner_word()),
    (dict(VARIANTS[0], k="G", name="上下排", note="标在上、字在下,字号 56(+47%)"), banner_stack(VARIANTS[0])),
]


def masked_icon(p, bg, fg, x, y, size):
    return (
        f'<svg x="{x}" y="{y}" width="{size}" height="{size}" viewBox="18 18 72 72">'
        f'<clipPath id="{p}clip"><circle cx="54" cy="54" r="36"/></clipPath>'
        f'<g clip-path="url(#{p}clip)"><rect width="108" height="108" fill="{bg}"/>{fg}</g></svg>'
    )


def banner_at(p, body, x, y, w, rx=10):
    return (
        f'<svg x="{x}" y="{y}" width="{w}" height="{w * 9 / 16}" viewBox="0 0 320 180">'
        f'<clipPath id="{p}b"><rect width="320" height="180" rx="{rx * 320 / w:.2f}"/></clipPath>'
        f'<g clip-path="url(#{p}b)">{body}</g></svg>'
    )


def render(svg, png):
    subprocess.run(["rsvg-convert", svg, "-o", png], check=True)


def sources():
    os.makedirs(SVG_DIR, exist_ok=True)
    for v, b in ITEMS[1:]:
        k = v["k"]
        with open(os.path.join(SVG_DIR, f"01{k}-icon.svg"), "w") as f:
            f.write(
                '<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 108 108">'
                f'<g id="background"><rect width="108" height="108" fill="{v["bg"]}"/></g>'
                f'<g id="foreground">{v["fg"]}</g></svg>'
            )
        with open(os.path.join(SVG_DIR, f"01{k}-banner.svg"), "w") as f:
            f.write(f'<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 320 180">{b}</svg>')


def sheet():
    W, cell_h, top = 1640, 370, 92
    H = top + 4 * cell_h + 10
    s = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}">',
        f'<rect width="{W}" height="{H}" fill="#151517"/>',
        f'<text x="40" y="56" font-family="{CJK}" font-size="30" font-weight="600" fill="#F2F2F2">'
        '01 托盘 · 变体(为远看加粗加大)</text>',
    ]
    for i, (v, b) in enumerate(ITEMS):
        col, row = i % 2, i // 2
        x0, y0 = 40 + col * 800, top + row * cell_h
        s.append(masked_icon(f"s{i}", v["bg"], v["fg"], x0, y0 + 30, 210))
        s.append(banner_at(f"s{i}", b, x0 + 236, y0, 500))
        s.append(
            f'<text x="{x0}" y="{y0 + 318}" font-family="{CJK}" font-size="24" font-weight="600" fill="#F2F2F2">{v["k"]}  {v["name"]}</text>'
            f'<text x="{x0 + 150}" y="{y0 + 318}" font-family="{CJK}" font-size="19" fill="#B9B9C0">{v["note"]}</text>'
        )
    s.append("</svg>")
    path = os.path.join(SVG_DIR, "_variants.svg")
    open(path, "w").write("".join(s))
    render(path, os.path.join(HERE, "variants.png"))


def tv():
    """1920 × 1080 = 电视界面像素:banner 320 × 180,图标 96 / 48 px。"""
    W, H = 1920, 1080
    s = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}">',
        '<defs><linearGradient id="wall" x1="0" y1="0" x2="0" y2="1">'
        '<stop offset="0" stop-color="#22242A"/><stop offset="1" stop-color="#0E0F12"/></linearGradient></defs>',
        f'<rect width="{W}" height="{H}" fill="url(#wall)"/>',
        f'<text x="96" y="110" font-family="{CJK}" font-size="34" fill="#E6E6EA">电视上的实际大小 · 全屏后退两步看</text>',
    ]
    for i, (v, b) in enumerate(ITEMS):
        row, col = i // 4, i % 4
        x, y = 96 + col * 440, 180 + row * 300
        s.append(banner_at(f"t{i}", b, x, y, 320, rx=12))
        s.append(f'<text x="{x}" y="{y + 222}" font-family="{CJK}" font-size="24" fill="#9A9AA3">{v["k"]} {v["name"]}</text>')
    s.append(f'<text x="96" y="830" font-family="{CJK}" font-size="26" fill="#9A9AA3">图标</text>')
    icons = [ITEMS[0][0]] + VARIANTS
    for i, v in enumerate(icons):
        x = 96 + i * 260
        s.append(masked_icon(f"ti{i}", v["bg"], v["fg"], x, 860, 96))
        s.append(masked_icon(f"ts{i}", v["bg"], v["fg"], x + 112, 908, 48))
        s.append(f'<text x="{x + 30}" y="1000" font-family="{CJK}" font-size="22" fill="#9A9AA3">{v["k"]}</text>')
    s.append("</svg>")
    path = os.path.join(SVG_DIR, "_variants-tv.svg")
    open(path, "w").write("".join(s))
    render(path, os.path.join(HERE, "variants-tv.png"))


if __name__ == "__main__":
    for v in VARIANTS:
        print(v["k"], "横排字号", round(v.get("_size", 0), 1))
    sources()
    sheet()
    tv()
    print("ok")
