import zlib, struct, io, math, os, glob, sys
import numpy as np, nbtlib
def read_region(path):
    out = {}
    with open(path,'rb') as f: data=f.read()
    rx, rz = map(int, os.path.basename(path).split('.')[1:3])
    for i in range(1024):
        off = int.from_bytes(data[i*4:i*4+3],'big'); cnt=data[i*4+3]
        if off==0: continue
        p=off*4096; ln=struct.unpack('>I',data[p:p+4])[0]; ct=data[p+4]
        raw=data[p+5:p+4+ln]
        if ct==2: raw=zlib.decompress(raw)
        elif ct==1:
            import gzip; raw=gzip.decompress(raw)
        elif ct==4:
            import lz4.block as lb; raw=lb.decompress(raw)  # unlikely
        nbt=nbtlib.File.parse(io.BytesIO(raw))
        cx, cz = int(nbt['xPos']), int(nbt['zPos'])
        out[(cx,cz)] = nbt
    return out
def chunk_blocks(nbt):
    """yield (lx,y,lz,name) for non-air"""
    for sec in nbt['sections']:
        if 'block_states' not in sec: continue
        bs=sec['block_states']; pal=[str(p['Name']) for p in bs['palette']]
        sy=int(sec['Y'])
        if len(pal)==1:
            if pal[0].endswith(':air'): continue
            idx=np.zeros(4096,dtype=np.int64)
        else:
            bits=max(4, math.ceil(math.log2(len(pal))))
            per=64//bits; longs=np.array([int(v) & 0xFFFFFFFFFFFFFFFF for v in bs['data']],dtype=np.uint64)
            idx=np.zeros(4096,dtype=np.int64); mask=np.uint64((1<<bits)-1)
            for k in range(per):
                vals=(longs>>np.uint64(k*bits))&mask
                pos=np.arange(len(longs))*per+k
                ok=pos<4096
                idx[pos[ok]]=vals[ok].astype(np.int64)
        for i in np.nonzero([not pal[j].endswith(':air') for j in idx])[0] if False else range(0):
            pass
        names=np.array(pal,dtype=object)[idx]
        nz=np.nonzero(~np.char.endswith(names.astype(str),':air'))[0]
        for i in nz:
            y=i>>8; z=(i>>4)&15; x=i&15
            yield x, sy*16+y, z, names[i]
