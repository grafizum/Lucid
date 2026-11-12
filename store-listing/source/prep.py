from PIL import Image, ImageDraw, ImageFont
import sys
S="/mnt/user-data/uploads/Screen translator/store-listing/raw/"
F=ImageFont.truetype("fonts/PlusJakarta.ttf",44); F.set_variation_by_axes([600]) if hasattr(F,'set_variation_by_axes') else None
jobs={ # name: crop_top
 "01-home":100,"02-lens-on":100,"02-chat":230,"03-web-es":230,"04-menu-ja":230,"05-travel-fr":230,"06-languages":230}
SB=110
for n,ct in jobs.items():
    im=Image.open(S+n+".png").convert("RGB")
    body=im.crop((0,ct,1080,2340-60))  # also drop gesture bar strip
    c=body.getpixel((540,10))
    if n=="06-languages": c=body.getpixel((540,10))
    out=Image.new("RGB",(1080,SB+body.height),c); out.paste(body,(0,SB))
    d=ImageDraw.Draw(out)
    lum=0.3*c[0]+0.59*c[1]+0.11*c[2]; fg=(30,28,24) if lum>140 else (245,240,230)
    d.text((70,34),"9:41",font=F,fill=fg)
    # signal, wifi, battery
    x=880
    for i in range(4): h=10+i*8; d.rounded_rectangle((x+i*12,74-h,x+i*12+7,74),2,fill=fg)
    x=950; d.pieslice((x-6,38,x+42,86),225,315,fill=fg)
    x=1000; d.rounded_rectangle((x,44,x+46,72),6,outline=fg,width=3); d.rectangle((x+5,49,x+41,67),fill=fg); d.rectangle((x+47,52,x+51,64),fill=fg)
    # gesture pill
    d.rounded_rectangle((420,out.height-26,660,out.height-16),5,fill=fg if lum<140 else (60,56,50))
    out.save(f"cap/{n}.png"); print(n,out.size,c)
