#!/usr/bin/env python3
"""Temporary preview of the exact Java overlay, no reconstruction of icons/text."""
from PIL import Image, ImageDraw
import numpy as np
from pathlib import Path
import io, base64, json
root=Path(__file__).resolve().parents[1]
im=Image.open(root/'docs/navbar/bar.png').convert('RGBA')
src=np.array(im)
ys,xs=np.where(src[:,:,3]>28)
left,top,right,bottom=xs.min(),ys.min(),xs.max()+1,ys.max()+1
marginx=round((right-left)*.015)
marginy=round((bottom-top)*.09)
box=[max(0,left-marginx),max(0,top-marginy),
     min(im.width,right+marginx+3),min(im.height,bottom+marginy+3)]
def preview(tab):
    picture=im.copy()
    if tab>0:
        arr=np.array(picture)
        w,h=im.size
        cx=round(left+(right-left)*.139)
        x0=max(0,cx-round((right-left)*.051));x1=min(w-1,cx+round((right-left)*.051))
        y0=round(h*.645);y1=round(h*.737)
        fx=max(3,round(w*.006));fy=max(3,round(h*.008))
        for y in range(y0,y1+1):
            for x in range(x0,x1+1):
                weight=.96*min(1,min(x-x0,x1-x)/fx)*min(1,min(y-y0,y1-y)/fy)
                if weight<=0:continue
                old=arr[y,x].astype(float)
                old[:3]=np.round(old[:3]*(1-weight)+np.array([24,54,40])*weight)
                arr[y,x]=old.astype('uint8')
        picture=Image.fromarray(arr,'RGBA')
        draw=ImageDraw.Draw(picture)
        xc=round(left+(right-left)*(.139+tab*.185))
        y=h*.688
        half=(right-left)*.018
        th=max(4,h*.010)
        draw.rounded_rectangle((xc-half-8,y-5,xc+half+8,y+th+5),radius=8,fill=(245,210,125,112))
        draw.rounded_rectangle((xc-half,y,xc+half,y+th),radius=th/2,fill=(252,226,162,255))
    crop=picture.crop(box)
    crop.thumbnail((750,240))
    buf=io.BytesIO();crop.save(buf,'PNG',optimize=True)
    b64=base64.b64encode(buf.getvalue()).decode()
    print('JAVA_NAV_PREVIEW_'+str(tab)+'_BEGIN')
    for k in range(0,len(b64),3000):print(b64[k:k+3000])
    print('JAVA_NAV_PREVIEW_'+str(tab)+'_END')
    return crop.size
sizes={str(i):preview(i) for i in (0,1,2)}
print('JAVA_NAV_PREVIEW_INFO',json.dumps(dict(source=im.size,bounds=list(map(int,(left,top,right,bottom))),crop=list(map(int,box)),states=sizes)))
