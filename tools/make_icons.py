# วาดไอคอนโลโก้ B (เป้าเล็ง) ทุกความละเอียด + ลูกบอล Bubble
import os, sys
from PIL import Image, ImageDraw
root = sys.argv[1] if len(sys.argv) > 1 else "app/res"
BLUE=(10,132,255,255); WHITE=(255,255,255,255); ORANGE=(255,159,10,255)
def draw(size, circle=False):
    S=size*4; im=Image.new("RGBA",(S,S),(0,0,0,0)); d=ImageDraw.Draw(im); u=S/100
    if circle:
        d.ellipse([1*u,1*u,99*u,99*u],fill=(20,20,22,235)); d.ellipse([7*u,7*u,93*u,93*u],fill=BLUE); k=0.82
    else:
        d.rounded_rectangle([0,0,S-1,S-1],radius=22.4*u,fill=BLUE); k=1.0
    c=50*u; r=27*u*k; lw=max(1,int(7*u*k))
    d.ellipse([c-r,c-r,c+r,c+r],outline=WHITE,width=lw)
    for (x1,y1,x2,y2) in [(50,12,50,26),(50,74,50,88),(12,50,26,50),(74,50,88,50)]:
        p=lambda v:50*u+(v-50)*u*k
        d.line([p(x1),p(y1),p(x2),p(y2)],fill=WHITE,width=lw)
        for (x,y) in [(x1,y1),(x2,y2)]:
            d.ellipse([p(x)-lw/2,p(y)-lw/2,p(x)+lw/2,p(y)+lw/2],fill=WHITE)
    rr=8*u*k; d.ellipse([c-rr,c-rr,c+rr,c+rr],fill=ORANGE)
    return im.resize((size,size),Image.LANCZOS)
for dens,sz in {"mdpi":48,"hdpi":72,"xhdpi":96,"xxhdpi":144,"xxxhdpi":192}.items():
    os.makedirs(f"{root}/drawable-{dens}",exist_ok=True); draw(sz).save(f"{root}/drawable-{dens}/ic_launcher.png")
os.makedirs(f"{root}/drawable-nodpi",exist_ok=True); draw(168,True).save(f"{root}/drawable-nodpi/ball.png")
draw(512).save("tools/icon-512.png")
