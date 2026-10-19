"""Prepare empty, explicit scan regions for the two independently surveyed Toka corridors.

This creates configuration only: entries stay empty, so the mod must scan every group
before it can generate an encoder. The source world is never edited.
"""
import json
import sys
from pathlib import Path

def pos(x,y,z):return {"x":x,"y":y,"z":z}

samples=[(0,0,"east"),(0,3,"east"),(1,5,"east"),(3,6,"down"),(3,-1,"up"),
         (12,0,"west"),(12,3,"west"),(11,5,"west"),(9,6,"down"),(9,-1,"up")]
lanes=[{"offset":pos(x,y,0),"face":face} for x,y,face in samples]
groups=[]
for start,end,step in [(513,562,1),(483,433,-1)]:
    coordinates=list(range(start,end+step,step))
    for offset in range(0,len(coordinates),11):
        row=coordinates[offset:offset+11]
        groups.append({"number":len(groups)+1,"min":pos(-348,120,min(row)),"max":pos(-338,126,max(row)),
                       "first":pos(-349,120,row[0]),"step":pos(0,0,step),"slices":len(row),"lanes":lanes,
                       "containers":False,"expandShulkers":True,
                       "ignoredBlocks":["minecraft:calcite","minecraft:smooth_quartz","minecraft:smooth_quartz_stairs","minecraft:bedrock"]})
project={"schemaVersion":1,"name":"Toka-complete-two-corridors","dimension":"minecraft:overworld",
         "profile":{"radix":11,"totalBits":10,"groups":10,"maxGroupWidth":4,"crossFields":False,"lockedMasks":[],"waterwayCount":10},
         "groups":groups,"entries":[]}
path=Path(sys.argv[1])
if path.exists():raise SystemExit("Refusing to overwrite existing project: "+str(path))
path.parent.mkdir(parents=True,exist_ok=True)
path.write_text(json.dumps(project,ensure_ascii=False,indent=2),encoding="utf-8")
print(f"CONFIG_ONLY groups={len(groups)} positions={sum(g['slices']*10 for g in groups)} entries=0 path={path}")
