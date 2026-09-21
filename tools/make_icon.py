#!/usr/bin/env python3
"""
生成 VideoWall 的栅格图标（API 24/25 用的 PNG mipmap）与预览图。

矢量前景层见 res/drawable/ic_launcher_foreground.xml（API 26+ 的自适应图标用）。
本脚本用同一套几何关系渲染 PNG，保证新旧版本观感一致。

用法：python make_icon.py
"""
import os
from PIL import Image, ImageDraw, ImageFont

BG = (0x14, 0x17, 0x37, 255)
PANEL = (0x3A, 0x42, 0x85, 255)
ACCENT = (0x5D, 0x6E, 0xF6, 255)
TRI = (0xFF, 0xFF, 0xFF, 255)

SS = 8  # 超采样倍率，保证边缘抗锯齿

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "app", "src", "main", "res")

# PIL 自带字体没有中文字形，会渲染成方块，必须显式加载系统字体
FONT_CANDIDATES = [
    "C:/Windows/Fonts/msyh.ttc",
    "C:/Windows/Fonts/msyhbd.ttc",
    "C:/Windows/Fonts/simhei.ttf",
    "C:/Windows/Fonts/simsun.ttc",
]


def label_font(px):
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, px)
            except Exception:
                pass
    return ImageFont.load_default()

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def render(size, round_icon=False):
    """返回一张 size x size 的 RGBA 图标。"""
    n = size * SS
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # 底板：圆角方形；圆形版本用于 ic_launcher_round
    if round_icon:
        d.ellipse([0, 0, n - 1, n - 1], fill=BG)
    else:
        d.rounded_rectangle([0, 0, n - 1, n - 1], radius=0.22 * n, fill=BG)

    # 四块面板：内容区取内缩 15.5%，面板之间留 7.2% 的缝
    margin = 0.155 * n
    inner = n - 2 * margin
    gap = 0.072 * n
    panel = (inner - gap) / 2
    pr = 0.115 * n

    x0 = margin
    y0 = margin
    x1 = margin + panel + gap
    y1 = margin + panel + gap

    cells = [
        (x0, y0, PANEL),
        (x1, y0, PANEL),
        (x0, y1, PANEL),
        (x1, y1, ACCENT),
    ]
    for cx, cy, color in cells:
        d.rounded_rectangle([cx, cy, cx + panel, cy + panel], radius=pr, fill=color)

    # 右下格里的播放三角：外接框宽 46% / 高 50%，略微右移做视觉居中
    tw = 0.46 * panel
    th = 0.50 * panel
    ccx = x1 + panel / 2
    ccy = y1 + panel / 2
    left = ccx - tw / 2 + 0.04 * panel
    top = ccy - th / 2
    d.polygon(
        [(left, top), (left + tw, ccy), (left, top + th)],
        fill=TRI,
    )

    return img.resize((size, size), Image.LANCZOS)


def main():
    for name, px in DENSITIES.items():
        out = os.path.join(RES, "mipmap-" + name)
        os.makedirs(out, exist_ok=True)
        render(px, round_icon=False).save(os.path.join(out, "ic_launcher.png"))
        render(px, round_icon=True).save(os.path.join(out, "ic_launcher_round.png"))
        print("mipmap-%s: %dpx ok" % (name, px))

    # 预览图：左方形右圆形，下方标一行说明
    pv = 256
    canvas = Image.new("RGBA", (pv * 2 + 72, pv + 64), (0xF4, 0xF4, 0xF6, 255))
    canvas.paste(render(pv, False), (24, 24), render(pv, False))
    canvas.paste(render(pv, True), (pv + 48, 24), render(pv, True))
    d = ImageDraw.Draw(canvas)
    f = label_font(17)
    d.text((24, pv + 36), "方形版（圆角底板）", fill=(0x33, 0x33, 0x33), font=f)
    d.text((pv + 48, pv + 36), "圆形版 ic_launcher_round", fill=(0x33, 0x33, 0x33), font=f)
    canvas.save(os.path.join(HERE, "icon-preview.png"))

    # 小尺寸实感预览：排成一行看 48dp 下还认不认得出来
    strip = Image.new("RGBA", (48 * 5 + 6 * 6 + 200, 48 + 64), (0xF4, 0xF4, 0xF6, 255))
    for i in range(5):
        strip.paste(render(48, i % 2 == 1), (6 + i * 54, 6), render(48, i % 2 == 1))
    d2 = ImageDraw.Draw(strip)
    d2.text((6, 62), "48dp 下的实际观感（方/圆交替）", fill=(0x33, 0x33, 0x33), font=label_font(15))
    strip.save(os.path.join(HERE, "icon-small-preview.png"))
    print("preview written")


if __name__ == "__main__":
    main()
