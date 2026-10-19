"""Independently verify a native mod export with the prototype's NBT reader.

Read-only: validates project coverage, per-item signals, every chest slot and
exact comparator fractions. The mod's encoder and inventory code are not used.
"""
import csv
from fractions import Fraction
import io
import json
from pathlib import Path
import sys
import zipfile
from collections import Counter, defaultdict

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from core import read_nbt_gzip

path = Path(sys.argv[1])
rotation = int(sys.argv[2]) if len(sys.argv)>2 else 0
with zipfile.ZipFile(path) as archive:
    project = json.loads(archive.read("project.json"))
    plan = json.loads(archive.read("plan.json"))
    signals = list(csv.DictReader(io.StringIO(archive.read("signals.csv").decode("utf-8"))))
    mappings = list(csv.DictReader(io.StringIO(archive.read("mapping.csv").decode("utf-8"))))
    materials = list(csv.DictReader(io.StringIO(archive.read("materials.csv").decode("utf-8"))))
    root = read_nbt_gzip(archive.read("encoder.litematic"))
    orientation=json.loads(archive.read("orientation.json")) if "orientation.json" in archive.namelist() else None
if orientation and len(sys.argv)<=2:rotation=orientation["rotation"]
profile=project["profile"]
expected={(g["number"],s,l+(0 if profile["radix"]==2 else 1)) for g in project["groups"] for s in range(1,g["slices"]+1) for l in range(len(g["lanes"]))}
actual={(e["address"]["group"],e["address"]["slice"],e["address"]["lane"]) for e in project["entries"]}
assert (not project["groups"] or expected==actual) and len(actual)==len(project["entries"]),"Incomplete project"
active={}
max_sizes={}
for entry in project["entries"]:
    if entry["excluded"]: continue
    assert not entry["issues"],"Unresolved scan issue"
    for item in entry["items"]:
        if item["excluded"]: continue
        assert item["maxStack"] in (16,64)
        assert item["id"] not in active or active[item["id"]]==entry["address"]
        active[item["id"]]=entry["address"]
        assert item["id"] not in max_sizes or max_sizes[item["id"]]==item["maxStack"]
        max_sizes[item["id"]]=item["maxStack"]
assert active==plan["addresses"]
assert {r["item_id"]:{"group":int(r["group"]),"slice":int(r["slice"]),"lane":int(r["waterway"])} for r in mappings}==active
chests=plan["chests"]
assert len(chests)==len(signals)
region=root["Regions"]["encoder"]
sx,sy,sz=(region["Size"][a] for a in ("x","y","z"))
entities={(e["x"],e["y"],e["z"]):e for e in region["TileEntities"]}
assert len(entities)==len(chests)*2+(len(chests)+1 if orientation else 0)
palette=region["BlockStatePalette"]
bits=max(2,(len(palette)-1).bit_length())
words=region["BlockStates"]
def state_at(pos):
    x,y,z=pos
    off=((y*sz+z)*sx+x)*bits
    # NBT longs are signed; packed palette words must be shifted as unsigned.
    value=(words[off//64]&((1<<64)-1))>>(off%64)
    if off%64+bits>64: value|=(words[off//64+1]&((1<<64)-1))<<(64-off%64)
    return palette[value&((1<<bits)-1)]
def rotate(x,z):return [(x,z),(-z,x),(-x,-z),(z,-x)][rotation%4]
all_coords=[rotate(int(r["column"]),half) for r in signals for half in (0,1)]
minx,minz=(region["Position"]["x"],region["Position"]["z"]) if orientation else (min(p[0] for p in all_coords),min(p[1] for p in all_coords))
triggered=defaultdict(list)
pool_counts=Counter()
field_boxes=Counter()
actual_materials=Counter({"minecraft:chest":len(chests)*2})
if orientation: actual_materials["minecraft:oak_sign"]=len(chests)+1
for chest,row in zip(chests,signals):
    assert row["field"]==chest["signal"]["field"] and int(row["value"])==chest["signal"]["value"] and int(row["mask"])==chest["signal"]["mask"]
    assert row["items"].split(";")==chest["items"]
    halves={}
    for half in (0,1):
        x,z=rotate(int(row["column"]),half)
        position=(x-minx,int(row["layer"])*2,z-minz)
        state=state_at(position)
        assert state["Name"]=="minecraft:chest"
        assert state["Properties"]["type"]==("left" if half==0 else "right")
        assert state["Properties"]["facing"]==["east","south","west","north"][rotation%4]
        stacks=entities[position]["Items"]
        assert len(stacks)==27 and {s["Slot"] for s in stacks}==set(range(27))
        halves[state["Properties"]["type"]]=sorted(stacks,key=lambda s:s["Slot"])
    # Vanilla's DoubleBlockProperties FIRST is RIGHT, not spatial z=0/LEFT.
    items=halves["right"]+halves["left"]
    count=len(chest["items"])
    assert 1<=count<=50 and len(set(chest["items"]))==count
    for slot,stack in enumerate(items):
        assert stack["count"]>0
        if slot<count:
            assert stack["id"]==chest["items"][slot] and stack["count"]==1
            assert "minecraft:custom_name" not in stack.get("components",{})
            actual_materials[stack['id']]+=1
        elif slot<50:
            assert stack["id"]=="minecraft:paper" and stack["count"]==1
            assert "minecraft:custom_name" in stack.get("components",{})
            actual_materials["minecraft:paper [ENCODER 占位]"]+=1
        else:
            assert stack['id'] in ('minecraft:paper','minecraft:shears')
            if stack['id']=='minecraft:paper':
                assert 1<=stack['count']<=64 and 'minecraft:custom_name' in stack.get('components',{})
                actual_materials["minecraft:paper [ENCODER 占位]"]+=stack['count']
            else:
                assert stack['count']==1
                actual_materials['minecraft:shears']+=1
    fullness=sum((Fraction(s["count"],max_sizes[s['id']] if slot<count else 1 if s["id"]=="minecraft:shears" else 64) for slot,s in enumerate(items)),Fraction())
    assert fullness==Fraction(247,64)
    assert 1+(fullness*14//54)==2
    for item_id in chest['items']:assert 1+((fullness-Fraction(1,max_sizes[item_id]))*14//54)==1
    if all(max_sizes[item_id]==64 for item_id in chest['items']):
        assert items[50]['id']=='minecraft:paper' and items[50]['count']==5
        assert all(s['id']=='minecraft:shears' and s['count']==1 for s in items[51:])
    signal=chest["signal"]
    pool_counts[(signal["field"],signal["mask"],signal["value"])]+=count
    field_boxes[signal["field"]]+=1
    for item in chest["items"]: triggered[item].append(signal)
assert set(triggered)==set(active)
assert len(materials)==len({r['item'] for r in materials})
assert {r['item']:int(r['count']) for r in materials}==dict(actual_materials),'Material list does not match exported inventories'
for item,address in active.items():
    if profile["radix"]==2:
        lane_bits=(profile.get("waterwayCount",0) or 8)-1
        lane_bits=lane_bits.bit_length()
        group_bits=profile["groups"].bit_length()-1
        slice_bits=profile["totalBits"]-group_bits-lane_bits
        expected_code=(address["group"]<<(slice_bits+lane_bits))|(address["slice"]<<lane_bits)|address["lane"]
        code=0
        for signal in triggered[item]: code|=signal["value"]
        assert code==expected_code
    else:
        assert len(triggered[item])==3
        assert {s["field"]:s["value"] for s in triggered[item]}=={"分组":address["group"],"单片":address["slice"],"水道":address["lane"]}
assert sum((n+49)//50 for n in pool_counts.values())==len(chests)
if orientation:
    def literal(value):
        if isinstance(value,str):
            if value.startswith(('{','[','"')):
                try:return literal(json.loads(value))
                except (ValueError,TypeError):pass
            return value
        if isinstance(value,list):return ''.join(literal(v) for v in value)
        if isinstance(value,dict):return value.get('text','')+''.join(literal(v) for v in value.get('extra',[]))
        raise AssertionError('Unknown sign text format')
    def check_sign(x,y,z,face,lines):
        rx,rz=rotate(x,z);pos=(rx-minx,y,rz-minz);e=entities[pos]
        assert e['id']=='minecraft:sign' and e['is_waxed']==1
        state=state_at(pos);assert state['Name']=='minecraft:oak_wall_sign' and state['Properties']['facing']==face
        for side in ('front_text','back_text'):assert [literal(v) for v in e[side]['messages']]==lines
    for i,(row,chest) in enumerate(zip(signals,chests)):
        s=chest['signal'];n=len(chest['items'])
        if profile['radix']==2:
            width=profile['totalBits'];lines=[f'#{i+1} / {n}',f"{s['field']} p{chest['part']}",f"M:{s['mask']:0{width}b}",f"V:{s['value']:0{width}b}"]
        else:lines=[f'#{i+1}',f"{s['field']} = {s['value']}",f"拆分 {chest['part']}",f'{n} 种物品']
        check_sign(int(row['column']),int(row['layer'])*2,-1,['north','east','south','west'][rotation%4],lines)
    direction=['东','南','西','北'][rotation%4];axis=['+X','+Z','-X','-Z'][rotation%4]
    check_sign(-1,int(signals[0]['layer'])*2,0,['west','north','east','south'][rotation%4],['起点 #1',f'正方向 {axis}',f'向{direction} / 箱号递增',f'箱面朝{direction}'])
    assert orientation['positiveDirection']==axis
print(json.dumps({"verified":True,"project_positions":len(actual),"unique_items":len(active),"items16":sum(n==16 for n in max_sizes.values()),"double_chests":len(chests),"signs":len(chests)+1 if orientation else 0,"filled_slots":len(chests)*54,"encoded_item_copies":sum(len(c["items"]) for c in chests),"size":[sx,sy,sz],"field_boxes":dict(field_boxes),"ss2_to_ss1":True,"materials_verified":True,"data_version":root["MinecraftDataVersion"]},ensure_ascii=False,indent=2))
