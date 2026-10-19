"""Read-only comparison of saved-world item frames with a native scan project.

Use a frozen backup for a stable comparison. This reports omissions; it does not
silently add items, choose target addresses, edit the save, or produce an encoder.
"""
from pathlib import Path
import gzip
import json
import sys
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from core import read_nbt_gzip

world = Path(sys.argv[1])
project = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8-sig"))
frames = []
for file in sorted((world / "entities").glob("r.*.mca")):
    raw = file.read_bytes()
    for index in range(1024):
        offset = int.from_bytes(raw[index*4:index*4+3], "big") * 4096
        if not offset:
            continue
        length = int.from_bytes(raw[offset:offset+4], "big")
        mode = raw[offset+4]
        payload = raw[offset+5:offset+4+length]
        if mode == 2:
            payload = zlib.decompress(payload)
        elif mode == 1:
            payload = gzip.decompress(payload)
        elif mode != 3:
            raise ValueError(f"Unsupported region compression {mode}")
        chunk = read_nbt_gzip(gzip.compress(payload))
        for entity in chunk.get("Entities", []):
            if entity.get("id") in {"minecraft:item_frame", "minecraft:glow_item_frame"} and entity.get("Item", {}).get("id"):
                frames.append(entity)

directions = {"none": (0,0,0), "east": (1,0,0), "west": (-1,0,0), "up": (0,1,0), "down": (0,-1,0), "south": (0,0,1), "north": (0,0,-1)}
covered = set()
for group in project["groups"]:
    for s in range(group["slices"]):
        for lane in group["lanes"]:
            base = tuple(group["first"][axis] + s*group["step"][axis] + lane["offset"][axis] for axis in ("x","y","z"))
            covered.add(base)
            covered.add(tuple(a+b for a,b in zip(base,directions[lane["face"]])))
missing = []
for frame in frames:
    position = tuple(frame.get(k) for k in ("TileX","TileY","TileZ"))
    if position not in covered:
        missing.append({"position": position, "facing": frame.get("Facing"), "id": frame["Item"]["id"]})
print(json.dumps({"saved_world_frames":len(frames), "native_frame_entries":sum(e["source"].startswith("frame:") for e in project["entries"]), "outside_sample_grid":missing}, ensure_ascii=False, indent=2))
