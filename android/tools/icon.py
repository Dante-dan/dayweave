"""拾日的图标：朝花夕拾。

一朵五瓣的花开在晨光到暮色的渐变里（朝 → 夕），一片花瓣沿抛物线飘落、被轻轻拾起。
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


def blossom():
    cx, cy = CENTER
    # 五瓣，略微偏转 -8°，让花有一点随风的姿态
    return [petal(cx, cy, PETAL_LEN, -8 + i * 72, offset=1.6) for i in range(5)]


def stamens():
    """花蕊：一圈小点落在花瓣之间，r = 5.5，角度与花瓣错开半格。"""
    cx, cy = CENTER
    return [(cx + 5.5 * math.sin(math.radians(-8 + 36 + i * 72)), cy - 5.5 * math.cos(math.radians(-8 + 36 + i * 72)), 0.95) for i in range(5)]


def falling():
    """飘落的花瓣：从花的右下沿抛物线 y = y0 + v t + ½ g t² 落下，留下由大到小的轨迹点。"""
    cx, cy = CENTER
    x0, y0 = cx + 15, cy + 13
    vx, vy, g = 6.0, -2.5, 9.0
    traj = [(x0 + vx * t, y0 + vy * t + 0.5 * g * t * t) for t in (0.5, 0.9, 1.25)]
    dots = [(x, y, r, op) for (x, y), r, op in zip(traj, (0.8, 1.0, 1.2), (0.38, 0.55, 0.75))]
    t = 1.85
    px, py = x0 + vx * t, y0 + vy * t + 0.5 * g * t * t
    # 花瓣尖顺着下落方向，像被风托着
    heading = math.degrees(math.atan2(vy + g * t, vx)) - 90 - 25
    return petal(px, py, 8.2, heading, width=0.56), dots


def svg():
    petals = blossom()
    fall, dots = falling()
    stops = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in SKY)
    body = "".join(f'<path d="{p}" fill="#FFFFFF" fill-opacity="0.96"/>' for p in petals)
    body += f'<path d="{circle(*CENTER, 3.0)}" fill="#FFD983"/>'
    body += "".join(f'<path d="{circle(x, y, r)}" fill="#FFD983" fill-opacity="0.9"/>' for x, y, r in stamens())
    body += "".join(f'<path d="{circle(x, y, r)}" fill="#FFFFFF" fill-opacity="{op}"/>' for x, y, r, op in dots)
    body += f'<path d="{fall}" fill="#FFFFFF" fill-opacity="0.92"/>'
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="432" height="432">
<defs><linearGradient id="sky" x1="0" y1="0" x2="1" y2="1">{stops}</linearGradient>
<radialGradient id="glow" cx="0.3" cy="0.22" r="0.6"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0.45"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></radialGradient></defs>
<rect width="108" height="108" rx="24" fill="url(#sky)"/><rect width="108" height="108" rx="24" fill="url(#glow)"/>{body}</svg>'''


def android():
    petals = blossom()
    fall, dots = falling()
    items = "".join(f'\n    <path android:fillColor="#F5FFFFFF" android:pathData="{p}" />' for p in petals)
    items += f'\n    <path android:fillColor="#FFFFD983" android:pathData="{circle(*CENTER, 3.0)}" />'
    for x, y, r in stamens():
        items += f'\n    <path android:fillColor="#E6FFD983" android:pathData="{circle(x, y, r)}" />'
    for x, y, r, op in dots:
        items += f'\n    <path android:fillColor="#{int(op * 255):02X}FFFFFF" android:pathData="{circle(x, y, r)}" />'
    items += f'\n    <path android:fillColor="#EBFFFFFF" android:pathData="{fall}" />'
    vector = '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">'
    (RES / "drawable").mkdir(parents=True, exist_ok=True)
    (RES / "drawable/ic_launcher_foreground.xml").write_text(f"<!-- 由 android/tools/icon.py 生成：朝花夕拾 -->\n{vector}{items}\n</vector>\n")
    mono = items.replace('#FFFFD983', '#FF000000')
    (RES / "drawable/ic_launcher_monochrome.xml").write_text(f"<!-- 由 android/tools/icon.py 生成 -->\n{vector}{mono}\n</vector>\n")
    stops = "".join(f'\n                <item android:offset="{o}" android:color="#FF{c[1:]}" />' for o, c in SKY)
    (RES / "drawable/ic_launcher_background.xml").write_text(f'''<!-- 由 android/tools/icon.py 生成：晨光到暮色 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="108" android:endY="108">{stops}
            </gradient>
        </aapt:attr>
    </path>
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="32" android:centerY="24" android:gradientRadius="66">
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
