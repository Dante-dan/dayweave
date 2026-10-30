"""拾日的图标：朝花夕拾。

一个圆是“日”：上沿细如日光走过的轨迹，下沿渐厚如捧起的掌心。花开在晨光处，
一片花瓣沿贝塞尔曲线穿过这一天，落进暮色里的掌心。背景从晨光到暮色。
几何全部由函数算出，同一份数据输出 SVG 预览和 Android 自适应图标（108dp 画布，安全区半径 33dp）。

用法：python3 -I android/tools/icon.py
"""
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"

# 晨光（桃、珊瑚）→ 暮色（玫瑰、紫）
SKY = [("0", "#FFC59A"), ("0.42", "#F98BA8"), ("1", "#7B5CD6")]
CENTER = (50.0, 48.0)
PETAL_LEN = 19.0


def petal(cx, cy, length, angle_deg, width=0.5, offset=0.0):
    """带缺口的花瓣（樱花式）：根部在 (cx, cy)，朝 angle 方向生长。返回 SVG path 数据。"""
    a = math.radians(angle_deg)
    # 单位花瓣，朝上（-y），根部在原点
    pts = [
        ("M", [(0, 0)]),
        ("C", [(-width, -0.18), (-width - 0.04, -0.86), (-0.24, -1.0)]),
        ("L", [(0, -0.86)]),
        ("L", [(0.24, -1.0)]),
        ("C", [(width + 0.04, -0.86), (width, -0.18), (0, 0)]),
    ]

    def tr(p):
        # offset：根部离花心留一点空隙，花瓣之间透气
        x, y = p[0] * length, p[1] * length - offset
        # 旋转：角度 0 指向正上方，顺时针
        rx = x * math.cos(a) - y * math.sin(a)
        ry = x * math.sin(a) + y * math.cos(a)
        return f"{cx + rx:.2f},{cy + ry:.2f}"

    out = []
    for cmd, ps in pts:
        out.append(cmd + " ".join(tr(p) for p in ps))
    return " ".join(out) + " Z"


def circle(cx, cy, r):
    return f"M{cx - r:.2f},{cy:.2f} a{r:.2f},{r:.2f} 0 1,0 {2 * r:.2f},0 a{r:.2f},{r:.2f} 0 1,0 {-2 * r:.2f},0 Z"


# 构图：一个圆就是“日”（一天）。上沿细，是日光走过的轨迹（时间）；下沿渐粗，像捧起的掌心（拾）。
# 花开在左上的晨光处（朝花），一片花瓣沿贝塞尔曲线穿过这一天，落在右下暮色里的掌心（夕拾）。
DAY = (54.0, 55.0)
DAY_R = 22.5
DAWN_ANGLE = math.radians(212)  # 花在圆上的位置（y 轴向下，从 +x 顺时针）


def arc_path(cx, cy, r, a0, a1):
    """从角度 a0 顺时针画到 a1（弧度）。"""
    x0, y0 = cx + r * math.cos(a0), cy + r * math.sin(a0)
    x1, y1 = cx + r * math.cos(a1), cy + r * math.sin(a1)
    large = 1 if (a1 - a0) % (2 * math.pi) > math.pi else 0
    return f"M{x0:.2f},{y0:.2f} A{r:.2f},{r:.2f} 0 {large},1 {x1:.2f},{y1:.2f}"


def light_arc():
    """上半圆：日光的轨迹，细。"""
    return arc_path(*DAY, DAY_R, math.pi + 0.05, 2 * math.pi - 0.05)


def cradle():
    """下半圆：捧起的掌心。用两条半径略不同的弧围成一个两头尖、中间厚的月牙形。"""
    cx, cy = DAY
    a0, a1 = -0.02, math.pi + 0.02
    outer = [(cx + (DAY_R + 1.6 * math.sin(t)) * math.cos(t), cy + (DAY_R + 1.6 * math.sin(t)) * math.sin(t))
             for t in [a0 + (a1 - a0) * i / 48 for i in range(49)]]
    inner = [(cx + (DAY_R - 1.9 * math.sin(t)) * math.cos(t), cy + (DAY_R - 1.9 * math.sin(t)) * math.sin(t))
             for t in [a1 - (a1 - a0) * i / 48 for i in range(49)]]
    pts = outer + inner
    return "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in pts) + " Z"


def dawn():
    return DAY[0] + DAY_R * math.cos(DAWN_ANGLE), DAY[1] + DAY_R * math.sin(DAWN_ANGLE)


def blossom():
    cx, cy = dawn()
    return [petal(cx, cy, 9.2, -8 + i * 72, offset=0.8) for i in range(5)]


def stamens():
    cx, cy = dawn()
    return [(cx, cy, 1.7)]


REST = (DAY[0] + 6.5, DAY[1] + DAY_R - 5.2)


def bezier(t, p0, p1, p2):
    u = 1 - t
    return u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0], u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1]


def falling():
    """花瓣从花飘到掌心：二次贝塞尔，控制点偏右上，先被风托一下再落下。轨迹点由小到大。"""
    start = dawn()
    ctrl = (DAY[0] + 16, DAY[1] - 14)
    trail = [(*bezier(t, start, ctrl, REST), r, op) for t, r, op in ((0.38, 0.75, 0.45), (0.58, 0.95, 0.65), (0.76, 1.15, 0.85))]
    rest = petal(REST[0] - 4.0, REST[1] + 0.6, 8.0, 72, width=0.6)
    return trail, rest


def svg():
    petals = blossom()
    trail, rest = falling()
    stops = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in SKY)
    body = f'<path d="{circle(*DAY, DAY_R)}" fill="#FFFFFF" fill-opacity="0.13"/>'
    body += f'<path d="{light_arc()}" fill="none" stroke="#FFFFFF" stroke-opacity="0.6" stroke-width="1.4" stroke-linecap="round"/>'
    body += f'<path d="{cradle()}" fill="#FFFFFF" fill-opacity="0.96"/>'
    body += "".join(f'<path d="{circle(x, y, r)}" fill="#FFFFFF" fill-opacity="{op}"/>' for x, y, r, op in trail)
    body += f'<path d="{rest}" fill="#FFFFFF"/>'
    body += "".join(f'<path d="{p}" fill="#FFFFFF" fill-opacity="0.97"/>' for p in petals)
    body += "".join(f'<path d="{circle(x, y, r)}" fill="#FFD983"/>' for x, y, r in stamens())
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="432" height="432">
<defs><linearGradient id="sky" x1="0" y1="0.2" x2="1" y2="0.8">{stops}</linearGradient>
<radialGradient id="glow" cx="0.3" cy="0.33" r="0.45"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0.55"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></radialGradient></defs>
<rect width="108" height="108" rx="24" fill="url(#sky)"/><rect width="108" height="108" rx="24" fill="url(#glow)"/>{body}</svg>'''


def android():
    petals = blossom()
    trail, rest = falling()
    items = f'\n    <path android:fillColor="#21FFFFFF" android:pathData="{circle(*DAY, DAY_R)}" />'
    items += f'\n    <path android:pathData="{light_arc()}" android:strokeColor="#99FFFFFF" android:strokeWidth="1.4" android:strokeLineCap="round" />'
    items += f'\n    <path android:fillColor="#F5FFFFFF" android:pathData="{cradle()}" />'
    for x, y, r, op in trail:
        items += f'\n    <path android:fillColor="#{int(op * 255):02X}FFFFFF" android:pathData="{circle(x, y, r)}" />'
    items += f'\n    <path android:fillColor="#FFFFFFFF" android:pathData="{rest}" />'
    items += "".join(f'\n    <path android:fillColor="#F7FFFFFF" android:pathData="{p}" />' for p in petals)
    for x, y, r in stamens():
        items += f'\n    <path android:fillColor="#FFFFD983" android:pathData="{circle(x, y, r)}" />'
    vector = '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">'
    (RES / "drawable").mkdir(parents=True, exist_ok=True)
    (RES / "drawable/ic_launcher_foreground.xml").write_text(f"<!-- 由 android/tools/icon.py 生成：朝花夕拾 -->\n{vector}{items}\n</vector>\n")
    mono = items.replace('#FFFFD983', '#FF000000').replace('android:strokeColor="#99FFFFFF"', 'android:strokeColor="#FF000000"').replace('#21FFFFFF', '#00000000')
    (RES / "drawable/ic_launcher_monochrome.xml").write_text(f"<!-- 由 android/tools/icon.py 生成 -->\n{vector}{mono}\n</vector>\n")
    stops = "".join(f'\n                <item android:offset="{o}" android:color="#FF{c[1:]}" />' for o, c in SKY)
    (RES / "drawable/ic_launcher_background.xml").write_text(f'''<!-- 由 android/tools/icon.py 生成：晨光到暮色 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="0" android:startY="22" android:endX="108" android:endY="86">{stops}
            </gradient>
        </aapt:attr>
    </path>
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="34" android:centerY="38" android:gradientRadius="50">
                <item android:offset="0" android:color="#73FFFFFF" />
                <item android:offset="1" android:color="#00FFFFFF" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
''')
    adaptive = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
'''
    (RES / "mipmap-anydpi-v26").mkdir(parents=True, exist_ok=True)
    (RES / "mipmap-anydpi-v26/ic_launcher.xml").write_text(adaptive)
    (RES / "mipmap-anydpi-v26/ic_launcher_round.xml").write_text(adaptive)


if __name__ == "__main__":
    out = ROOT / "tools/icon-preview.svg"
    out.write_text(svg())
    android()
    print(out)
