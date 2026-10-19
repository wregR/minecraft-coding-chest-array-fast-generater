"""Independent, read-only survey of both Toka display corridors. Does not edit the save or generate an encoder."""
import gzip
import json
import sys
import zlib
from collections import Counter
from functools import lru_cache
from pathlib import Path

sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from core import read_nbt_gzip

world=Path(sys.argv[1])

@lru_cache(maxsize=12)
def region(kind,rx,rz):
    file=world/kind/f"r.{rx}.{rz}.mca"
    return file.read_bytes() if file.exists() else b""

@lru_cache(maxsize=128)
def chunk(kind,cx,cz):
    data=region(kind,cx//32,cz//32)
    index=((cx&31)+(cz&31)*32)*4
    if len(data)<8192:return {}
    offset=int.from_bytes(data[index:index+3],"big")*4096
    if not offset:return {}
    length=int.from_bytes(data[offset:offset+4],"big")
    mode=data[offset+4]
    payload=data[offset+5:offset+4+length]
    if mode==2:payload=zlib.decompress(payload)
    elif mode==1:payload=gzip.decompress(payload)
    elif mode!=3:raise ValueError(f"Unsupported region compression {mode}")
    return read_nbt_gzip(gzip.compress(payload))

def block(pos):
    x,y,z=pos
    root=chunk("region",x//16,z//16)
    if not root:return {"Name":"unknown:unloaded"}
    section=next((s for s in root.get("sections",[]) if s["Y"]==y//16),{})
    states=section.get("block_states",{})
    palette=states.get("palette",[])
    if not palette:return {"Name":"minecraft:air"}
    bits=max(4,(len(palette)-1).bit_length())
    index=(y&15)*256+(z&15)*16+(x&15)
    per_word=64//bits
    data=states.get("data",[])
    value=(data[index//per_word]>>(index%per_word*bits))&((1<<bits)-1) if data else 0
    return palette[value]

frames={}
for cz in range(24,39):
    for cx in range(-23,-20):
        for e in chunk("entities",cx,cz).get("Entities",[]):
            if e.get("id") not in {"minecraft:item_frame","minecraft:glow_item_frame"}:continue
            if not e.get("Item",{}).get("id"):continue
            key=tuple(e.get(k) for k in ("TileX","TileY","TileZ"))
            frames.setdefault(key,[]).append(e)

ignored={f"minecraft:{name}" for name in ("air","cave_air","void_air","water","lava","calcite","smooth_quartz","smooth_quartz_stairs","bedrock")}
# These are the sampled five-lane cross section and its mirror across the central aisle.
lanes=[(-349,120,(1,0,0)),(-349,123,(1,0,0)),(-348,125,(1,0,0)),(-346,126,(0,-1,0)),(-346,119,(0,1,0)),
       (-337,120,(-1,0,0)),(-337,123,(-1,0,0)),(-338,125,(-1,0,0)),(-340,126,(0,-1,0)),(-340,119,(0,1,0))]
records=[]
for z in range(400,601):
    for lane,(x,y,d) in enumerate(lanes,1):
        base=(x,y,z);front=(x+d[0],y+d[1],z+d[2])
        candidates=frames.get(front,[])+frames.get(base,[])
        if candidates:
            items=[e["Item"]["id"] for e in candidates]
            source="frame"
            state=block(base)
        else:
            state=block(front)
            if state["Name"] in ignored:state=block(base)
            items=[] if state["Name"] in ignored else [state["Name"]]
            source="block"
        if items:records.append({"z":z,"lane":lane,"base":base,"source":source,"state":state,"ids":items})

counts=Counter(r["z"] for r in records)
if len(sys.argv)>2 and sys.argv[2]=="columns":
    print(json.dumps([r for r in records if 484<=r["z"]<=512],ensure_ascii=False,indent=2))
    raise SystemExit(0)
def runs(zs):
    out=[]
    for z in sorted(zs):
        if not out or z!=out[-1][1]+1:out.append([z,z])
        else:out[-1][1]=z
    return out

print(json.dumps({"dense_runs_at_least_8_of_10":runs(z for z,c in counts.items() if c>=8),
                  "occupied_runs":runs(counts),"occupied_positions":len(records),
                  "distinct_raw_ids":len({i for r in records for i in r["ids"]}),
                  "most_repeated_raw_ids":Counter(i for r in records for i in r["ids"]).most_common(12),
                  "sparse_columns":[{"z":z,"count":c,"ids":[i for r in records if r["z"]==z for i in r["ids"]]} for z,c in sorted(counts.items()) if c<8]},ensure_ascii=False,indent=2))
