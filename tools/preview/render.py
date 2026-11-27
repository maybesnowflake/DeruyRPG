import sys, glob, numpy as np
sys.path.insert(0,'/tmp/viz')
from mca import read_region, chunk_blocks
from PIL import Image
reg=sys.argv[1]; out=sys.argv[2]
top={}  # (x,z)->(y,name)
for p in glob.glob(reg+'/*.mca'):
    for (cx,cz),nbt in read_region(p).items():
        for x,y,z,n in chunk_blocks(nbt):
            k=(cx*16+x, cz*16+z)
            if k not in top or y>top[k][0]: top[k]=(y,n)
xs=[k[0] for k in top]; zs=[k[1] for k in top]
x0,x1,z0,z1=min(xs),max(xs),min(zs),max(zs)
W,H=x1-x0+1,z1-z0+1
hm=np.full((H,W),np.nan); col=np.zeros((H,W,3))
def c(n):
    n=n.split(':')[-1]
    for k,v in [('water',(60,100,220)),('leaves',(50,130,50)),('vine',(60,140,40)),('log',(110,80,45)),('planks',(175,135,85)),('grass_block',(95,160,70)),('moss',(100,150,60)),('fern',(80,150,60)),('short_grass',(90,160,70)),('azalea',(80,140,60)),('mud',(95,75,60)),('dirt',(130,95,65)),('tuff',(110,110,100)),('andesite',(140,140,140)),('cobble',(120,120,120)),('stone',(125,125,125)),('wool',(70,180,70)),('lantern',(255,200,80)),('sea_lantern',(200,240,255)),('magma',(220,90,30)),('bamboo',(150,200,80)),('lily',(60,170,60)),('glass',(210,230,240))]:
        if k in n: return v
    return (150,150,150)
for (x,z),(y,n) in top.items():
    hm[z-z0,x-x0]=y; col[z-z0,x-x0]=c(n)
h=np.nan_to_num(hm,nan=np.nanmin(hm)-30)
gy,gx=np.gradient(h)
shade=np.clip(1-0.12*(gx+gy),0.45,1.4)
hn=(h-np.nanmin(hm))/(np.nanmax(hm)-np.nanmin(hm)+1e-6)
img=col*shade[...,None]*(0.65+0.45*hn[...,None])
img[np.isnan(hm)]=(12,12,20)
im=Image.fromarray(np.clip(img,0,255).astype(np.uint8))
s=int(sys.argv[3]) if len(sys.argv)>3 else 2
im=im.resize((W*s,H*s),Image.NEAREST); im.save(out); print(W,H,x0,z0)
