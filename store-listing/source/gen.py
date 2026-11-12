import re, pathlib, random
R = pathlib.Path(__file__).parent
out = R / "html"; out.mkdir(exist_ok=True)

# ---------- icon: full-bleed square version of the launcher mark ----------
mark = (R / "src/lens-mark.svg").read_text()
mark = re.sub(r'<path d="M224,0 L800,0[^>]*/>',
              '<rect width="1024" height="1024" fill="url(#paper)"/>'
              '<rect width="1024" height="1024" fill="url(#sheen)"/>', mark)
mark = mark.replace("</defs>", '<radialGradient id="sheen" cx="0.3" cy="0.22" r="0.9">'
                    '<stop offset="0" stop-color="#FFFDF7" stop-opacity="0.75"/>'
                    '<stop offset="1" stop-color="#FFFDF7" stop-opacity="0"/></radialGradient></defs>')
# re-centre the mark (lens + handle) in the square
mark = mark.replace('<g filter="url(#lift)">', '<g filter="url(#lift)" transform="translate(-20,-18) translate(512,512) scale(0.86) translate(-512,-512)">')
(R / "src/icon-square.svg").write_text(mark)


FONT = """
@font-face{font-family:Jak;src:url(../fonts/PlusJakarta.ttf);font-weight:200 800}
@font-face{font-family:Geist;src:url(../fonts/Geist.woff2);font-weight:100 900}
:root{--ink:#191814;--clay:#A46E4B;--clay6:#8A5A3C;--stone:#6A6254}
*{box-sizing:border-box;margin:0;padding:0}
body{overflow:hidden;font-family:Jak,Geist,sans-serif;color:var(--ink)}
.stage{position:relative;overflow:hidden;
 background:radial-gradient(120% 80% at 30% 18%,#FDF9F1 0%,#F6EEDF 45%,#EADFC9 100%)}
.stage::after{content:"";position:absolute;inset:0;pointer-events:none;
 background:radial-gradient(90% 60% at 50% 110%,rgba(120,92,58,.16),transparent 70%)}
.eyebrow{display:inline-block;font:600 26px/1 Geist;letter-spacing:.32em;text-transform:uppercase;color:var(--clay6);
 padding:18px 30px 16px;border-radius:999px;background:#F3EADA;
 box-shadow:6px 6px 14px rgba(160,130,90,.28),-6px -6px 14px rgba(255,253,247,.95)}
h1{font-weight:800;letter-spacing:-.035em;line-height:1.02}
h1 em{font-style:normal;color:var(--clay)}
/* 3D scene */
.scene{position:absolute;perspective:2600px;transform-style:preserve-3d}
.phone{position:absolute;transform-style:preserve-3d}
.phone .layer{position:absolute;inset:0;border-radius:var(--r);background:#CFC0A3}
.phone .face{position:absolute;inset:0;border-radius:var(--r);padding:var(--bz);
 background:linear-gradient(150deg,#FFFDF8 0%,#F3EBDD 40%,#E2D5BD 100%);
 box-shadow:inset 3px 3px 4px rgba(255,255,255,.9),inset -4px -5px 8px rgba(140,110,70,.25)}
.phone .face img{width:100%;height:100%;object-fit:cover;object-position:top;display:block;border-radius:calc(var(--r) - var(--bz));
 box-shadow:0 0 0 2px rgba(25,24,20,.85)}
.phone .glare{position:absolute;inset:var(--bz);border-radius:calc(var(--r) - var(--bz));
 background:linear-gradient(115deg,rgba(255,255,255,.22) 0%,rgba(255,255,255,0) 32%,rgba(255,255,255,0) 70%,rgba(255,255,255,.07) 100%);pointer-events:none}
.phone .btn{position:absolute;right:-7px;width:10px;border-radius:6px;background:linear-gradient(90deg,#E4D8C2,#C9B896)}
.floor{position:absolute;border-radius:50%;background:radial-gradient(closest-side,rgba(74,58,34,.42),rgba(74,58,34,0));filter:blur(18px)}
/* neumorphic keycap tiles */
.tile{position:absolute;transform-style:preserve-3d}
.tile .l{position:absolute;inset:0;border-radius:28%;background:#C8B593}
.tile .f{position:absolute;inset:0;border-radius:28%;display:grid;place-items:center;
 background:linear-gradient(145deg,#FFFBF2 0%,#F2E8D6 55%,#E0D1B4 100%);
 box-shadow:inset 5px 5px 9px rgba(255,255,255,.95),inset -7px -9px 16px rgba(150,118,76,.30)}
.tile .f span{font-weight:800;color:var(--clay);
 text-shadow:-2px -2px 2px rgba(255,255,255,.8),3px 4px 5px rgba(110,70,48,.35)}
.tile.dark .l{background:#0e0d0b}
.tile.dark .f{background:linear-gradient(145deg,#3A3831,#23221E 60%,#191814);
 box-shadow:inset 4px 4px 8px rgba(255,255,255,.12),inset -6px -8px 14px rgba(0,0,0,.45)}
.tile.dark .f span{color:#D3A582;text-shadow:0 2px 6px rgba(0,0,0,.5)}
.tshadow{position:absolute;border-radius:50%;background:rgba(74,58,34,.28);filter:blur(22px)}
.lens3d{position:absolute;filter:drop-shadow(18px 34px 30px rgba(74,58,34,.35))}
"""

from PIL import Image as _I
def phone(x, y, w, img, rx=8, ry=-16, rz=-3, depth=26, z=0, floor=True):
    _im=_I.open(R/"cap"/img); h = int(w * (_im.height/_im.width))
    r = int(w * .13); bz = int(w * .035)
    layers = "".join(f'<div class="layer" style="transform:translateZ({-i*2}px);filter:brightness({1-i*0.018})"></div>' for i in range(1, depth // 2))
    btns = f'<div class="btn" style="top:{int(h*.22)}px;height:{int(h*.07)}px;transform:translateZ(-6px)"></div><div class="btn" style="top:{int(h*.32)}px;height:{int(h*.11)}px;transform:translateZ(-6px)"></div>'
    fl = f'<div class="floor" style="left:{x+w*.05}px;top:{y+h*.93}px;width:{w*1.1}px;height:{w*.22}px"></div>' if floor else ""
    return fl + f'''<div class="phone" style="--r:{r}px;--bz:{bz}px;left:{x}px;top:{y}px;width:{w}px;height:{h}px;z-index:{z};
 transform:rotateX({rx}deg) rotateY({ry}deg) rotateZ({rz}deg);
 filter:drop-shadow(30px 50px 50px rgba(74,58,34,.30))">{layers}{btns}
 <div class="face"><img src="../cap/{img}"></div><div class="glare"></div></div>'''

def tile(x, y, s, g, rx=18, ry=-24, rz=8, blur=0, dark=False, fs=None, z=5):
    fs = fs or int(s * .5)
    layers = "".join(f'<div class="l" style="transform:translateZ({-i*2}px);filter:brightness({1-i*0.03})"></div>' for i in range(1, 9))
    sh = f'<div class="tshadow" style="left:{x+s*.25}px;top:{y+s*1.05}px;width:{s*.9}px;height:{s*.25}px;{"filter:blur(%dpx)"%(22+blur*3)}"></div>' if blur < 4 else ""
    return sh + f'''<div class="tile{' dark' if dark else ''}" style="left:{x}px;top:{y}px;width:{s}px;height:{s}px;z-index:{z};
 transform:perspective(900px) rotateX({rx}deg) rotateY({ry}deg) rotateZ({rz}deg);filter:blur({blur}px) drop-shadow(14px 24px 22px rgba(74,58,34,.30))">
 {layers}<div class="f"><span style="font-size:{fs}px">{g}</span></div></div>'''

def lens(x, y, s, rot=-8, blur=0, z=6):
    return f'<img class="lens3d" src="../src/lens-foreground.svg" style="left:{x}px;top:{y}px;width:{s}px;z-index:{z};transform:rotate({rot}deg);filter:blur({blur}px) drop-shadow(18px 34px 30px rgba(74,58,34,.35))">'

def page(name, W, H, body):
    (out / f"{name}.html").write_text(f'<!doctype html><html><head><meta charset="utf-8"><style>{FONT}</style></head>'
        f'<body style="width:{W}px;height:{H}px"><div class="stage" style="width:{W}px;height:{H}px">{body}</div></body></html>')

def head(eb, t1, t2, size=96, top=120):
    return f'''<div style="position:absolute;left:0;right:0;top:{top}px;text-align:center;z-index:20">
 <div class="eyebrow">{eb}</div>
 <h1 style="font-size:{size}px;margin-top:38px">{t1}<br><em>{t2}</em></h1></div>'''

W, H = 1080, 1920
page("shot-01", W, H, head("Any app", "Translate any app,", "right where you read.") +
     phone(250, 560, 600, "02-chat.png", rx=10, ry=-20, rz=-5) +
     tile(70, 640, 170, "A", 14, 26, -10) + tile(860, 760, 150, "文", 12, -30, 12) +
     tile(820, 1480, 130, "Ñ", 20, -20, -8, blur=1) + tile(60, 1400, 120, "あ", 10, 30, 10, blur=2) +
     tile(900, 520, 80, "?", 0, -20, 20, blur=4))
page("shot-02", W, H, head("Travel", "Tickets, signs, notices.", "Understood.", 90) +
     phone(240, 580, 600, "05-travel-fr.png", rx=8, ry=18, rz=4) +
     lens(-60, 1260, 430, -20) + tile(860, 640, 150, "é", 14, -26, 10) +
     tile(70, 700, 120, "Ç", 18, 24, -12, blur=2) + tile(880, 1450, 130, "A", 16, -18, 8, blur=1))
page("shot-03", W, H, head("日本語 · 中文 · 한국어", "Kanji with readings,", "at a glance.") +
     phone(250, 570, 600, "04-menu-ja.png", rx=10, ry=-18, rz=-4) +
     tile(70, 650, 170, "日", 14, 26, -10, dark=True) + tile(850, 820, 160, "あ", 12, -30, 12) +
     tile(840, 1470, 130, "한", 20, -20, -8, blur=1) + tile(60, 1420, 130, "中", 10, 30, 10, blur=2, dark=True))
page("shot-04", W, H, head("Every website", "Read the web", "in your language.") +
     phone(240, 570, 600, "03-web-es.png", rx=8, ry=18, rz=4) +
     tile(60, 680, 160, "¿", 14, 26, -10) + tile(870, 700, 130, "ß", 12, -30, 12, blur=1) +
     lens(700, 1330, 400, 14) + tile(60, 1460, 120, "Ü", 16, 22, 10, blur=2))
page("shot-05", W, H, head("Your language", "Tap the lens.", "Pick a language.") +
     phone(250, 570, 600, "06-languages.png", rx=10, ry=-18, rz=-4) +
     tile(70, 660, 160, "Aa", 14, 26, -10, fs=70) + tile(860, 780, 150, "文", 12, -30, 12, dark=True) +
     tile(840, 1480, 130, "Ö", 20, -20, -8, blur=1) + tile(60, 1450, 120, "ñ", 10, 30, 10, blur=2))
page("shot-06", W, H, head("Private by design", "No account. No ads.", "No history.") +
     phone(250, 560, 590, "01-home.png", rx=8, ry=18, rz=4) +
     lens(-80, 1270, 430, -14) + tile(860, 700, 150, "✓", 12, -30, 12, fs=72) +
     tile(870, 1480, 120, "A", 20, -20, -8, blur=1) + tile(70, 700, 120, "あ", 10, 30, 10, blur=2))
FW, FH = 1024, 500
page("feature", FW, FH, f'''
<div style="position:absolute;left:64px;top:112px;z-index:20;width:480px">
 <div style="display:flex;align-items:center;gap:22px">
  <img src="../out/icon.png" style="width:96px;height:96px;border-radius:24px;box-shadow:8px 10px 22px rgba(120,92,58,.30),-6px -6px 14px rgba(255,253,247,.95)">
  <div style="font:800 76px/1 Jak;letter-spacing:-.04em">Lucid</div></div>
 <h1 style="font-size:44px;margin-top:30px;line-height:1.08">Translate anything<br>on screen, <em>in place.</em></h1>
 <div style="margin-top:24px;font:600 15px/1 Geist;letter-spacing:.28em;color:#8A5A3C">ANY APP · ONLINE OR OFFLINE · PRIVATE</div>
</div>''' +
     phone(800, 80, 210, "04-menu-ja.png", rx=8, ry=-24, rz=6, z=1, floor=False) +
     phone(590, 46, 235, "02-chat.png", rx=10, ry=-18, rz=-6, z=2, floor=False) +
     tile(530, 340, 90, "A", 14, 26, -10) + tile(940, 26, 66, "文", 12, -30, 12, blur=1) +
     tile(965, 400, 56, "あ", 20, -20, -8, blur=2))
