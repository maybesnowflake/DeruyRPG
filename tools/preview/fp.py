import sys, glob, numpy as np, math
sys.path.insert(0,'/tmp/viz')
from mca import read_region, chunk_blocks
from PIL import Image
reg, out = sys.argv[1], sys.argv[2]
cx_, cy_, cz_, yaw, pitch = map(float, sys.argv[3:8])
X0,X1,Z0,Z1,Y0,Y1 = map(int, sys.argv[8:14])
names=['air']; nid={'air':0}
vol=np.zeros((X1-X0,Y1-Y0,Z1-Z0),dtype=np.int16)
for p in glob.glob(reg+'/*.mca'):
    rx,rz=map(int,p.split('/')[-1].split('.')[1:3])
    if rx*512>X1 or rx*512+512<X0 or rz*512>Z1 or rz*512+512<Z0: continue
    for (cx,cz),nbt in read_region(p).items():
        if cx*16+16<X0 or cx*16>X1 or cz*16+16<Z0 or cz*16>Z1: continue
        for x,y,z,n in chunk_blocks(nbt):
            X,Z=cx*16+x,cz*16+z
            if X0<=X<X1 and Z0<=Z<Z1 and Y0<=y<Y1:
                n=n.split(':')[-1]
                if n not in nid: nid[n]=len(names); names.append(n)
                vol[X-X0,y-Y0,Z-Z0]=nid[n]
def c(n):
    for k,v in [('water',(60,100,220)),('leaves',(50,130,50)),('vine',(55,125,35)),('log',(110,80,45)),('planks',(175,135,85)),('grass_block',(95,160,70)),('moss_block',(95,145,55)),('moss_carpet',(95,145,55)),('fern',(80,150,60)),('short_grass',(90,160,70)),('azalea',(80,140,60)),('packed_mud',(150,115,90)),('mud',(95,75,60)),('rooted_dirt',(135,100,70)),('dirt',(130,95,65)),('tuff',(105,108,98)),('andesite',(140,140,138)),('mossy_cobble',(105,125,95)),('cobble',(120,120,120)),('mossy_stone',(110,130,100)),('stone',(125,125,125)),('wool',(70,180,70)),('lantern',(255,200,80)),('magma',(220,90,30)),('bamboo',(150,200,80)),('lily',(60,170,60)),('glass',(210,230,240)),('fence',(160,120,75)),('sign',(180,150,90))]:
        if k in n: return v
    return (160,160,160)
cols=np.array([c(n) for n in names],dtype=float)
thin=np.array([any(k in n for k in ('vine','short_grass','fern','carpet','lily','torch','sign','lever','wire','button','plate','lantern')) for n in names])
W,H=640,360; fov=math.radians(75)
ys=math.radians(yaw); ps=math.radians(pitch)
fwd=np.array([-math.sin(ys)*math.cos(ps), -math.sin(ps), math.cos(ys)*math.cos(ps)])
right=np.array([math.cos(ys),0,math.sin(ys)]); up=np.cross(right,fwd)*-1
up=np.cross(fwd,right)*-1 if up[1]<0 else up
u=np.linspace(-1,1,W)*math.tan(fov/2); v=np.linspace(1,-1,H)*math.tan(fov/2)*H/W
uu,vv=np.meshgrid(u,v)
d=fwd[None,None,:]+uu[...,None]*right[None,None,:]+vv[...,None]*up[None,None,:]
d/=np.linalg.norm(d,axis=2,keepdims=True); d=d.reshape(-1,3)
o=np.array([cx_-X0,cy_-Y0,cz_-Z0])
img=np.zeros((d.shape[0],3)); done=np.zeros(d.shape[0],bool)
t=np.full(d.shape[0],0.05); step=0.12
sky_top=np.array([90,140,210.]); sky_bot=np.array([185,210,235.])
prev=np.floor(o+d*0.05).astype(int)
for i in range(1600):
    idx=np.nonzero(~done)[0]
    if len(idx)==0: break
    p=o+d[idx]*t[idx,None]
    b=np.floor(p).astype(int)
    inb=(b[:,0]>=0)&(b[:,0]<vol.shape[0])&(b[:,1]>=0)&(b[:,1]<vol.shape[1])&(b[:,2]>=0)&(b[:,2]<vol.shape[2])
    out_=~inb & ((p[:,1]>vol.shape[1])|(p[:,1]<-20)|(t[idx]>220))
    k=np.zeros(len(idx),dtype=np.int16); k[inb]=vol[b[inb,0],b[inb,1],b[inb,2]]
    hit=k>0
    hit &= ~(thin[k] & ((t[idx]<4)|(np.random.rand(len(idx))<0.6)))
    if hit.any():
        hi=idx[hit]; pb=prev[hi]; nb=b[hit]
        diff=np.abs(nb-pb); axis=np.argmax(diff,axis=1)
        shade=np.where(axis==1, np.where(d[hi,1]<0,1.0,0.5), np.where(axis==0,0.78,0.64))
        fog=np.clip(t[hi]/180,0,1)[:,None]
        colr=cols[k[hit]]*shade[:,None]
        # 블록 경계 살짝 어둡게
        fr=np.abs((o+d[hi]*t[hi,None])%1-0.5).max(axis=1)
        colr*=np.where(fr>0.47,0.85,1.0)[:,None]
        img[hi]=colr*(1-fog)+sky_bot*fog; done[hi]=True
    so=idx[out_]
    if len(so):
        yv=np.clip(d[so,1]*0.5+0.5,0,1)[:,None]; img[so]=sky_bot*(1-yv)+sky_top*yv; done[so]=True
    prev[idx]=b; t[idx]+=step*(1+t[idx]/60)
rest=~done
yv=np.clip(d[rest,1]*0.5+0.5,0,1)[:,None]; img[rest]=sky_bot*(1-yv)+sky_top*yv
Image.fromarray(np.clip(img.reshape(H,W,3),0,255).astype(np.uint8)).save(out); print('ok', len(names))
