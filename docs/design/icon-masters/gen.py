#!/usr/bin/env python3
"""UnitedU 图标 + banner 定稿生成器(2026-10-01,托盘 · 字标合一 · Google Sans Flex 圆头)。

出处:docs/design/logo-concepts-2026-10-01/(10 个方向 → 01 托盘 → 变体 F 字标合一 → 字体 2)。
- banner:「United」用 Google Sans Flex(wght 700 / opsz 144 / ROND 100,应用内置的同一个 OFL 字体文件)
  取字形转路径,最后一个 U 是碗(圆头),碗里一个红圆、碗口冒出黄方块和蓝三角。
- 图标:同一个碗 + 三个形状,按自适应图标 108 dp 画布放大到安全圆(直径 66)里。

用法:python3 gen.py
产出:本目录 icon-background.png / icon-foreground.png(1024)、banner.png(1280 × 720)、src/*.svg,
并直接写入 app/src/main/res/mipmap-*(各密度两层 + xhdpi banner)。需要 rsvg-convert、fontTools。
"""
import math
import os
import subprocess

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "../../.."))
RES = os.path.join(ROOT, "app/src/main/res")
SRC = os.path.join(HERE, "src")

CREAM, INK, RED, YEL, BLU = "#EFE8DA", "#1B1B1B", "#E0402C", "#F2B705", "#2156A8"

FONT = instancer.instantiateVariableFont(
    TTFont(os.path.join(RES, "font/google_sans_flex.ttf")), {"wght": 700, "opsz": 144, "ROND": 100}
)
UPM = FONT["head"].unitsPerEm
CMAP = FONT.getBestCmap()
GS = FONT.getGlyphSet()


def _bounds(ch):
    bp = BoundsPen(GS)
    GS[CMAP[ord(ch)]].draw(bp)
    return bp.bounds


CAP = _bounds("H")[3] / UPM
U_WIDTH = (lambda b: (b[2] - b[0]) / UPM)(_bounds("U"))
STEM = 0.1847  # U 竖画实测粗细(em),fonts/gen.py 用渲染量得


def text_width(s, size):
    return sum(FONT["hmtx"][CMAP[ord(c)]][0] for c in s) * size / UPM


def text_path(s, x, baseline, size, fill):
    sc, pen_x, out = size / UPM, x, []
    for ch in s:
        g = CMAP[ord(ch)]
        sp = SVGPathPen(GS)
        GS[g].draw(TransformPen(sp, (sc, 0, 0, -sc, pen_x, baseline)))
        out.append(sp.getCommands())
        pen_x += FONT["hmtx"][g][0] * sc
    return f'<path d="{" ".join(out)}" fill="{fill}"/>'


def dims(size):
    cap = CAP * size
    stem = STEM * size
    bw = max(U_WIDTH * size * 1.12, cap * 0.98, 2 * (stem + 0.33 * cap))
    inner = bw / 2 - stem
    return cap, stem, bw, inner, inner * 0.98


def mark_parts(size, cx, baseline):
    """碗 + 三个形状。返回 (svg, 外轮廓采样点),采样点用来算图标里离中心最远的距离。"""
    cap, stem, bw, inner, sq = dims(size)
    top = baseline - cap
    r = (bw - stem) / 2
    arc_cy = baseline - bw / 2
    leg_top = top + stem / 2
    cr = inner * 0.8
    o1, o2 = 0.009 * size, 0.0135 * size
    sq_x, sq_y = cx - inner + o1, top - sq * 0.55
    tri = [(cx + o2, top + sq * 0.45), (cx + o2 + sq, top + sq * 0.45), (cx + o2 + sq / 2, top - sq * 0.55)]
    svg = (
        f'<circle cx="{cx:.3f}" cy="{baseline - stem - cr - 0.008 * size:.3f}" r="{cr:.3f}" fill="{RED}"/>'
        f'<rect x="{sq_x:.3f}" y="{sq_y:.3f}" width="{sq:.3f}" height="{sq:.3f}" fill="{YEL}"/>'
        f'<path d="M{tri[0][0]:.3f} {tri[0][1]:.3f} L{tri[1][0]:.3f} {tri[1][1]:.3f} L{tri[2][0]:.3f} {tri[2][1]:.3f} Z" fill="{BLU}"/>'
        f'<path d="M{cx - r:.3f} {leg_top:.3f} V{arc_cy:.3f} A{r:.3f} {r:.3f} 0 0 0 {cx + r:.3f} {arc_cy:.3f} V{leg_top:.3f}" '
        f'fill="none" stroke="{INK}" stroke-width="{stem:.3f}" stroke-linecap="round"/>'
    )
    pts = [(sq_x, sq_y), (sq_x + sq, sq_y), *tri]
    for a in range(0, 360, 5):  # 两个圆头
        for sx in (-1, 1):
            pts.append((cx + sx * r + stem / 2 * math.cos(math.radians(a)), leg_top + stem / 2 * math.sin(math.radians(a))))
    for a in range(0, 181, 5):  # 碗底外沿
        pts.append((cx + bw / 2 * math.cos(math.radians(a)), arc_cy + bw / 2 * math.sin(math.radians(a))))
    pts += [(cx - bw / 2, leg_top), (cx + bw / 2, leg_top), (cx - bw / 2, arc_cy), (cx + bw / 2, arc_cy)]
    return svg, pts


def banner_svg(avail_w=260, avail_h=116):
    word = "United"

    def wh(size):
        cap, stem, bw, inner, sq = dims(size)
        return text_width(word, size) + size * 0.07 + bw, cap + sq * 0.55

    w1, h1 = wh(1)
    size = min(avail_w / w1, avail_h / h1)
    w, h = wh(size)
    cap, stem, bw, inner, sq = dims(size)
    left = (320 - w) / 2
    baseline = (180 - h) / 2 + h
    cx = left + text_width(word, size) + size * 0.07 + bw / 2
    mark, _ = mark_parts(size, cx, baseline)
    return (
        f'<rect width="320" height="180" fill="{CREAM}"/>' + text_path(word, left, baseline, size, INK) + mark
    ), size


def icon_fg_svg(safe_r=32.5):
    """在 108 画布里找最大字号 + 最佳基线,使整个标离中心 (54, 54) 不超过 safe_r。"""
    def worst(size, baseline):
        _, pts = mark_parts(size, 54, baseline)
        return max(math.hypot(x - 54, y - 54) for x, y in pts)

    def best_baseline(size):
        lo, hi = 40.0, 110.0
        for _ in range(80):  # 三分法:最远距离对基线是单峰的
            m1, m2 = lo + (hi - lo) / 3, hi - (hi - lo) / 3
            if worst(size, m1) < worst(size, m2):
                hi = m2
            else:
                lo = m1
        return (lo + hi) / 2

    lo, hi = 10.0, 120.0
    for _ in range(60):
        mid = (lo + hi) / 2
        if worst(mid, best_baseline(mid)) <= safe_r:
            lo = mid
        else:
            hi = mid
    size = lo
    baseline = best_baseline(size)
    svg, _ = mark_parts(size, 54, baseline)
    return svg, size, baseline


def write(path, s):
    with open(path, "w") as f:
        f.write(s)


def rsvg(svg, png, w, h):
    subprocess.run(["rsvg-convert", "-w", str(w), "-h", str(h), svg, "-o", png], check=True)


def main():
    os.makedirs(SRC, exist_ok=True)
    banner, bsize = banner_svg()
    fg, isize, ibase = icon_fg_svg()
    b_svg = os.path.join(SRC, "banner.svg")
    fg_svg = os.path.join(SRC, "icon-foreground.svg")
    bg_svg = os.path.join(SRC, "icon-background.svg")
    write(b_svg, f'<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 320 180">{banner}</svg>')
    write(fg_svg, f'<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 108 108">{fg}</svg>')
    write(bg_svg, f'<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 108 108">'
                  f'<rect width="108" height="108" fill="{CREAM}"/></svg>')

    rsvg(b_svg, os.path.join(HERE, "banner.png"), 1280, 720)
    rsvg(fg_svg, os.path.join(HERE, "icon-foreground.png"), 1024, 1024)
    rsvg(bg_svg, os.path.join(HERE, "icon-background.png"), 1024, 1024)

    for dens, px in {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}.items():
        d = os.path.join(RES, f"mipmap-{dens}")
        rsvg(fg_svg, os.path.join(d, "ic_launcher_foreground.png"), px, px)
        rsvg(bg_svg, os.path.join(d, "ic_launcher_background.png"), px, px)
    rsvg(b_svg, os.path.join(RES, "mipmap-xhdpi/banner.png"), 320, 180)
    print(f"banner 字号 {bsize:.1f}(320 × 180 画布);图标字号 {isize:.1f}、基线 {ibase:.1f}(108 画布)")


if __name__ == "__main__":
    main()
