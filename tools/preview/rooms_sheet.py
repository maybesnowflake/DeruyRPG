import sys, glob, os, nbtlib
from PIL import Image, ImageDraw
files = sorted(glob.glob(sys.argv[1] + "/*/*.nbt"))
def col(n, y, hmax):
    n = n.split(":")[-1]
    base = (120,120,120)
    for k, c in [("water",(50,90,220)),("lily",(60,160,60)),("leaves",(40,120,40)),("log",(110,80,40)),("planks",(170,130,80)),
                 ("moss",(90,150,60)),("grass",(80,170,70)),("mud",(90,70,55)),("magma",(220,80,20)),("bamboo",(150,200,80)),
                 ("lamp",(255,220,0)),("sign",(255,0,255)),("lever",(255,0,0)),("redstone",(200,0,0)),("dispenser",(60,60,60)),
                 ("fence",(150,110,70)),("wool",(60,200,60)),("glass",(200,230,255)),("stone",(130,130,130)),("dirt",(130,95,60)),("root",(100,80,60))]:
        if k in n: base = c; break
    f = 0.6 + 0.4 * min(1, y / max(1, hmax))
    return tuple(int(v * f) for v in base)
S = 4; cells = []
for p in files:
    f = nbtlib.load(p); sx, sy, sz = [int(v) for v in f["size"]]
    pal = [str(e["Name"]) for e in f["palette"]]
    top = {}
    marks = []
    for b in f["blocks"]:
        x, y, z = [int(v) for v in b["pos"]]
        n = pal[int(b["state"])]
        if "nbt" in b and "front_text" in b["nbt"]:
            t = str(b["nbt"]["front_text"]["messages"][0]).strip('"')
            if t.startswith("["): marks.append((x, z, t)); continue
        if "air" in n: continue
        if (x, z) not in top or y >= top[(x, z)][0]: top[(x, z)] = (y, n)
    im = Image.new("RGB", (sx * S, sz * S + 14), (0, 0, 0)); d = ImageDraw.Draw(im)
    for (x, z), (y, n) in top.items():
        d.rectangle([x*S, z*S, x*S+S-1, z*S+S-1], fill=col(n, y, 12))
    for x, z, t in marks:
        c = {"[spawn]":(0,255,255),"[mob]":(255,40,40),"[boss]":(255,0,255),"[trigger]":(255,255,0)}.get(t,(255,255,255))
        d.ellipse([x*S-2, z*S-2, x*S+S+1, z*S+S+1], fill=c, outline=(0,0,0))
    d.text((2, sz*S+2), os.path.basename(p)[:-4][:30], fill=(255,255,255))
    cells.append(im)
W = 6; cw = max(i.width for i in cells); ch = max(i.height for i in cells)
sheet = Image.new("RGB", (cw * W, ch * ((len(cells)+W-1)//W)), (30,30,30))
for i, im in enumerate(cells): sheet.paste(im, ((i % W) * cw, (i // W) * ch))
sheet.save(sys.argv[2]); print(sheet.size)
