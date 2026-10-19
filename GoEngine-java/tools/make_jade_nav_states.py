#!/usr/bin/env python3
"""Derive five fully illustrated navbar PNGs from the original six jade PNGs.

Original art stays unchanged. The five outputs contain all layers, including
background, icons, labels and exactly one selected underline. Android displays
only one finished bitmap and transparent hit targets: no runtime retouching.
"""
from __future__ import annotations
import argparse
import base64
import io
import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / "docs/navbar"
ORDER = ("home", "game", "record", "training", "settings")
LABELS = ("首页", "对弈", "棋谱", "训练", "设置")


def visible_bounds(src: Image.Image):
    a = np.asarray(src.getchannel("A"))
    ys, xs = np.where(a > 25)
    if len(xs) == 0:
        raise ValueError("Source bar is transparent")
    return (int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1)


def clean_background(src: Image.Image, bounds):
    arr = np.array(src)
    left, top, right, bottom = bounds
    w, h = right - left, bottom - top
    # Each artwork slot covers only the inside of the ornate frame, not border.
    # Inpainting uses neighboring original jade/forest pixels.
    mask = np.zeros(arr.shape[:2], dtype=np.uint8)
    centers = [left + w * (0.10 + i * .20) for i in range(5)]
    for cx in centers:
        x0, x1 = round(cx - w * .087), round(cx + w * .087)
        y0, y1 = round(top + h * .045), round(top + h * .965)
        cv2.rectangle(mask, (x0, y0), (x1, y1), 255, -1)
    image_bgr = cv2.cvtColor(arr[:,:,:3], cv2.COLOR_RGB2BGR)
    filled = cv2.inpaint(image_bgr, mask, 9, cv2.INPAINT_TELEA)
    alpha = cv2.inpaint(arr[:,:,3], mask, 9, cv2.INPAINT_TELEA)
    rgba = np.dstack((cv2.cvtColor(filled, cv2.COLOR_BGR2RGB), alpha))
    return Image.fromarray(rgba, "RGBA"), centers


def clean_icon(src: Image.Image, active: bool, is_home: bool):
    icon = src.convert("RGBA")
    a = np.asarray(icon.getchannel("A"))
    yy, xx = np.where(a > 12)
    box = (int(xx.min()), int(yy.min()), int(xx.max()) + 1, int(yy.max()) + 1)
    icon = icon.crop(box)
    if is_home and not active:
        # Home-only glowing underline is at the very bottom of the image.
        # Remove it from the inactive icon, then tone gold into ivory.
        arr = np.array(icon)
        h = arr.shape[0]
        arr[round(h * .88):, :, 3] = 0
        icon = Image.fromarray(arr, "RGBA")
        alpha = icon.getchannel("A")
        gray = ImageEnhance.Brightness(ImageEnhance.Color(icon.convert("RGB")).enhance(.17)).enhance(.95)
        cool = Image.new("RGB", icon.size, (213, 227, 202))
        gray = Image.blend(gray, cool, .40).convert("RGBA")
        gray.putalpha(alpha)
        icon = gray
    return icon


def make_selected_glow(canvas: Image.Image, img: Image.Image, origin):
    w, h = img.size
    halo = Image.new("RGBA", img.size, (244, 199, 89, 0))
    halo.putalpha(img.getchannel("A").filter(ImageFilter.GaussianBlur(max(3, w//21))))
    halo.putalpha(halo.getchannel("A").point(lambda v: round(v*.48)))
    canvas.alpha_composite(halo, (origin[0], origin[1]))
    canvas.alpha_composite(img, origin)


def render_states(output: Path, preview=False):
    original = Image.open(ART / "bar.png").convert("RGBA")
    bounds = visible_bounds(original)
    base, centers = clean_background(original, bounds)
    left, top, right, bottom = bounds
    w, h = right - left, bottom - top
    # Exclude only the transparent gutters. Keep the artist's glowing frame.
    padding_x, padding_y = round(w*.018), round(h*.10)
    crop = (max(0,left-padding_x), max(0,top-padding_y),
            min(original.width,right+padding_x), min(original.height,bottom+padding_y))
    states = {}
    for selected in range(5):
        canvas = base.copy()
        for i, key in enumerate(ORDER):
            img = clean_icon(Image.open(ART / f"{key}.png"), active=(i==selected), is_home=(i==0))
            target = min(round(w * .145), round(h*.72))
            scale = min(target/img.width, target/img.height)
            img = img.resize((round(img.width*scale), round(img.height*scale)), Image.Resampling.LANCZOS)
            cx, cy = centers[i], top+h*.52
            position = (round(cx-img.width/2), round(cy-img.height/2))
            if i == selected and i != 0:
                make_selected_glow(canvas, img, position)
            else:
                canvas.alpha_composite(img, position)
            if i == selected and i != 0:
                line_width = round(w*.041)
                ly = round(top+h*.955)
                draw = ImageDraw.Draw(canvas)
                draw.rounded_rectangle((round(cx-line_width/2), ly,
                                        round(cx+line_width/2),ly+round(h*.020)),
                                        radius=max(1,round(h*.010)),
                                        fill=(255,222,140,255))
        result=canvas.crop(crop)
        output.mkdir(parents=True,exist_ok=True)
        result.save(output / f"nav_{ORDER[selected]}.png", optimize=True)
        states[ORDER[selected]]={"size":result.size,"bytes":(output / f"nav_{ORDER[selected]}.png").stat().st_size}
        if preview and selected in (0,1,2):
            image=result.copy()
            image.thumbnail((700,210))
            buf=io.BytesIO()
            image.save(buf,"PNG",optimize=True)
            print(f"NAV_PREVIEW_{ORDER[selected]}_BEGIN")
            b64=base64.b64encode(buf.getvalue()).decode()
            for k in range(0,len(b64),3000):
                print(b64[k:k+3000])
            print(f"NAV_PREVIEW_{ORDER[selected]}_END")
    print("NAV_GENERATED",json.dumps({"original":original.size,
                                       "bounds":bounds,"crop":crop,
                                       "states":states},ensure_ascii=False))


if __name__ == "__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--output",type=Path,default=ROOT/"build/jade_nav_states")
    parser.add_argument("--preview",action="store_true")
    args=parser.parse_args()
    render_states(args.output,args.preview)
