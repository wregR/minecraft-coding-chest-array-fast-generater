"""Read-only Anvil inspection to independently check in-game scan results. Never opens a world for writing."""
from pathlib import Path
import gzip
import json
import sys
import zlib
from collections import Counter, defaultdict

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from core import read_nbt_gzip

world = Path(sys.argv[1])
kind = sys.argv[2] if len(sys.argv) > 2 else "entities"
records = []
directory = "region" if kind == "states" else "entities" if kind == "summary" else kind
for file in sorted((world / directory).glob("r.*.mca" if kind == "summary" else "r.-1.*.mca")):
    if file.stat().st_size < 8192:
        continue
    raw = file.read_bytes()
    for index in range(1024):
        sector = int.from_bytes(raw[index * 4:index * 4 + 3], "big")
        if not sector:
            continue
        offset = sector * 4096
        length = int.from_bytes(raw[offset:offset + 4], "big")
        mode = raw[offset + 4]
        if mode != 2:
            continue
        root = read_nbt_gzip(gzip.compress(zlib.decompress(raw[offset + 5:offset + 4 + length])))
        if kind == "summary":
            for e in root.get("Entities", []):
                if e.get("id") in {"minecraft:item_frame", "minecraft:glow_item_frame"} and e.get("Item", {}).get("id"):
                    records.append({"pos":[e.get("TileX"),e.get("TileY"),e.get("TileZ")],"facing":e.get("Facing"),"id":e["Item"]["id"]})
            continue
        if kind == "states":
            for z in range(514, 518):
                for x,y in [(-348,120),(-349,120),(-348,123),(-349,123),(-347,125),(-348,125),(-346,125),(-346,126),(-346,120),(-346,119)]:
                    if root.get("xPos") != x//16 or root.get("zPos") != z//16:
                        continue
                    section = next((s for s in root.get("sections", []) if s["Y"] == y//16), None)
                    bs = section.get("block_states", {}) if section else {}
                    pal = bs.get("palette", [])
                    if not pal:
                        continue
                    idx=(y&15)*256+(z&15)*16+(x&15)
                    bits=max(4,(len(pal)-1).bit_length()); per_word=64//bits; data=bs.get("data",[])
                    value=(data[idx//per_word]>>(idx%per_word*bits))&((1<<bits)-1) if data else 0
                    records.append({"pos":[x,y,z],"state":pal[value]})
            continue
        for entity in root.get("Entities", []) + root.get("block_entities", []):
            coords = entity.get("Pos", [entity.get("x", 0), entity.get("y", 0), entity.get("z", 0)])
            x, y, z = coords
            if kind == "region" and not (entity.get("id") == "minecraft:chest" and -349 <= x <= -345 and 118 <= y <= 128 and 514 <= z <= 517):
                continue
            if -354 <= x <= -332 and 504 <= z <= 538:
                state = None
                if kind == "region":
                    section = next((s for s in root.get("sections", []) if s["Y"] == int(y)//16), None)
                    if section:
                        block_states = section.get("block_states", {})
                        palette = block_states.get("palette", [])
                        if palette:
                            idx = (int(y)&15)*256+(int(z)&15)*16+(int(x)&15)
                            bits = max(4, (len(palette)-1).bit_length())
                            per_word = 64//bits
                            data = block_states.get("data", [])
                            palette_index = ((data[idx//per_word]>>(idx%per_word*bits))&((1<<bits)-1)) if data else 0
                            state = palette[palette_index]
                    records.append({"pos":coords,"state":state})
                    continue
                records.append({"pos": coords, "id": entity.get("id"), "tile": [entity.get(k) for k in ("TileX", "TileY", "TileZ")], "facing": entity.get("Facing"), "item": entity.get("Item"), "items": [i.get("id") for i in entity.get("Items", [])]})
if kind == "summary":
    by_xy=defaultdict(list)
    for r in records:
        if all(v is not None for v in r["pos"]):
            x,y,z=r["pos"];by_xy[x,y,r["facing"]].append(z)
    summary={"frames":len(records),"unique_frame_items":len({r["id"] for r in records}),"rows":[{"xy_face":list(key),"count":len(z),"z_min":min(z),"z_max":max(z),"gaps":dict(Counter(b-a for a,b in zip(sorted(set(z)),sorted(set(z))[1:])))} for key,z in sorted(by_xy.items(), key=lambda kv:-len(kv[1])) if len(z)>=5]}
    print(json.dumps(summary,ensure_ascii=False,indent=2))
else:
    print(json.dumps(sorted(records, key=lambda e: (e["pos"][2], e["pos"][0], -e["pos"][1])), ensure_ascii=False, indent=2))
