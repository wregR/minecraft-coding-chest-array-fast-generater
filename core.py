"""Core compiler for Minecraft all-item encoder chest schematics.

The module intentionally has no third-party dependencies.  It reads the
Litematica NBT dialect needed by the project and writes a compact 1.21.0
Litematic containing only filled encoder double chests.
"""
from __future__ import annotations

import base64
import csv
import gzip
import io
import json
import math
import struct
import time
import zipfile
from collections import defaultdict
from dataclasses import dataclass
from typing import Any, Iterable


AIR = "minecraft:air"
PAPER = "minecraft:paper"
SHEARS = "minecraft:shears"
FILLER_NAME = '{"text":"编码器占位物，请勿取出","italic":false}'


class ProjectError(ValueError):
    """A validation error that can be shown to the web user."""


# ---------- Minimal NBT reader/writer ----------


class _Reader:
    def __init__(self, data: bytes):
        self.data, self.pos = data, 0

    def take(self, size: int) -> bytes:
        value = self.data[self.pos:self.pos + size]
        if len(value) != size:
            raise ProjectError("投影文件不完整或不是有效的 NBT 文件")
        self.pos += size
        return value

    def number(self, fmt: str) -> int | float:
        return struct.unpack(fmt, self.take(struct.calcsize(fmt)))[0]

    def string(self) -> str:
        return self.take(self.number(">H")).decode("utf-8")

    def payload(self, tag: int) -> Any:
        if tag == 1:
            return self.number(">b")
        if tag == 2:
            return self.number(">h")
        if tag == 3:
            return self.number(">i")
        if tag == 4:
            return self.number(">q")
        if tag == 5:
            return self.number(">f")
        if tag == 6:
            return self.number(">d")
        if tag == 7:
            return self.take(self.number(">i"))
        if tag == 8:
            return self.string()
        if tag == 9:
            child_type, length = self.number(">b"), self.number(">i")
            return [self.payload(child_type) for _ in range(length)]
        if tag == 10:
            result: dict[str, Any] = {}
            while True:
                child_type = self.number(">b")
                if child_type == 0:
                    return result
                # Assignment evaluates the right hand side before a subscript
                # target in Python. Read the name explicitly first; NBT stores
                # name before payload.
                name = self.string()
                result[name] = self.payload(child_type)
        if tag == 11:
            return [self.number(">i") for _ in range(self.number(">i"))]
        if tag == 12:
            return [self.number(">q") for _ in range(self.number(">i"))]
        raise ProjectError(f"投影使用了不支持的 NBT 标签类型 {tag}")


def read_nbt_gzip(raw: bytes) -> dict[str, Any]:
    try:
        data = gzip.decompress(raw)
    except OSError as exc:
        raise ProjectError("只支持 gzip 压缩的 .litematic 文件") from exc
    reader = _Reader(data)
    if reader.number(">b") != 10:
        raise ProjectError("投影根节点不是 NBT Compound")
    reader.string()  # Root name, normally empty.
    root = reader.payload(10)
    if not isinstance(root, dict):
        raise ProjectError("投影根节点格式错误")
    return root


class _Byte(int):
    pass


class _Short(int):
    pass


class _Int(int):
    pass


class _Long(int):
    pass


class _LongArray(list):
    pass


class _List(list):
    def __init__(self, tag_type: int, values: Iterable[Any] = ()):  # type: ignore[override]
        super().__init__(values)
        self.tag_type = tag_type


def _tag_type(value: Any) -> int:
    if isinstance(value, _Byte): return 1
    if isinstance(value, _Short): return 2
    if isinstance(value, _Int): return 3
    if isinstance(value, _Long): return 4
    if isinstance(value, _LongArray): return 12
    if isinstance(value, str): return 8
    if isinstance(value, _List) or isinstance(value, list): return 9
    if isinstance(value, dict): return 10
    raise TypeError(f"Cannot write NBT for {type(value)!r}")


def _write_string(out: io.BytesIO, value: str) -> None:
    encoded = value.encode("utf-8")
    out.write(struct.pack(">H", len(encoded)))
    out.write(encoded)


def _write_payload(out: io.BytesIO, value: Any) -> None:
    tag = _tag_type(value)
    if tag == 1: out.write(struct.pack(">b", int(value)))
    elif tag == 2: out.write(struct.pack(">h", int(value)))
    elif tag == 3: out.write(struct.pack(">i", int(value)))
    elif tag == 4: out.write(struct.pack(">q", int(value)))
    elif tag == 8: _write_string(out, value)
    elif tag == 9:
        child_type = value.tag_type if isinstance(value, _List) else (10 if not value else _tag_type(value[0]))
        out.write(struct.pack(">bi", child_type, len(value)))
        for item in value: _write_payload(out, item)
    elif tag == 10:
        for key, child in value.items():
            out.write(struct.pack(">b", _tag_type(child)))
            _write_string(out, key)
            _write_payload(out, child)
        out.write(b"\x00")
    elif tag == 12:
        out.write(struct.pack(">i", len(value)))
        for item in value: out.write(struct.pack(">q", int(item)))
    else:
        raise TypeError(f"Unsupported NBT write type {tag}")


def write_nbt_gzip(root: dict[str, Any]) -> bytes:
    out = io.BytesIO()
    out.write(b"\x0a\x00\x00")
    _write_payload(out, root)
    return gzip.compress(out.getvalue())


# ---------- Litematic layout reader ----------


def _block_name(state: dict[str, Any]) -> str:
    return str(state.get("Name", ""))


def _state_at(region: dict[str, Any], local: tuple[int, int, int]) -> dict[str, Any] | None:
    size = region.get("Size", {})
    sx, sy, sz = abs(int(size.get("x", 0))), abs(int(size.get("y", 0))), abs(int(size.get("z", 0)))
    x, y, z = local
    if not (0 <= x < sx and 0 <= y < sy and 0 <= z < sz):
        return None
    palette = region.get("BlockStatePalette", [])
    longs = region.get("BlockStates", [])
    if not palette or not longs:
        return None
    bits = max(2, (len(palette) - 1).bit_length())
    index = y * sx * sz + z * sx + x
    offset = index * bits
    first, shift = offset >> 6, offset & 63
    values = [value & ((1 << 64) - 1) for value in longs]
    if first >= len(values):
        return None
    value = values[first] >> shift
    if shift + bits > 64 and first + 1 < len(values):
        value |= values[first + 1] << (64 - shift)
    palette_id = value & ((1 << bits) - 1)
    return palette[palette_id] if palette_id < len(palette) else None


def _items_from_entity(entity: dict[str, Any]) -> list[tuple[int, str]]:
    entries: dict[int, str] = {}
    for item in entity.get("Items", []):
        slot = int(item.get("Slot", item.get("slot", -1)))
        item_id = str(item.get("id", ""))
        count = int(item.get("Count", item.get("count", 0)))
        if 0 <= slot < 27 and item_id and count > 0:
            entries[slot] = item_id if ":" in item_id else f"minecraft:{item_id}"
    return [(slot, entries[slot]) for slot in sorted(entries)]


# Known first-version exclusions. The list is deliberately conservative; an
# unsupported id is reported instead of being silently encoded with bad SS.
NON_64_ITEMS = {
    "minecraft:ender_pearl", "minecraft:egg", "minecraft:snowball", "minecraft:potion",
    "minecraft:splash_potion", "minecraft:lingering_potion", "minecraft:honey_bottle",
    "minecraft:bucket", "minecraft:water_bucket", "minecraft:lava_bucket", "minecraft:milk_bucket",
    "minecraft:powder_snow_bucket", "minecraft:cod_bucket", "minecraft:salmon_bucket",
    "minecraft:pufferfish_bucket", "minecraft:tropical_fish_bucket", "minecraft:axolotl_bucket",
    "minecraft:tadpole_bucket", "minecraft:written_book", "minecraft:writable_book",
    "minecraft:enchanted_book", "minecraft:music_disc_13", "minecraft:music_disc_cat",
    "minecraft:music_disc_blocks", "minecraft:music_disc_chirp", "minecraft:music_disc_far",
    "minecraft:music_disc_mall", "minecraft:music_disc_mellohi", "minecraft:music_disc_stal",
    "minecraft:music_disc_strad", "minecraft:music_disc_ward", "minecraft:music_disc_11",
    "minecraft:music_disc_wait", "minecraft:music_disc_otherside", "minecraft:music_disc_5",
    "minecraft:music_disc_relic", "minecraft:music_disc_creator", "minecraft:music_disc_creator_music_box",
    "minecraft:music_disc_precipice", "minecraft:music_disc_tears",
}


def _is_obviously_unstackable(item_id: str) -> bool:
    suffixes = ("_sword", "_pickaxe", "_axe", "_shovel", "_hoe", "_helmet", "_chestplate", "_leggings", "_boots", "_elytra", "_shield", "_trident", "_mace", "_crossbow", "_bow", "_fishing_rod", "_flint_and_steel", "_shears", "_minecart", "_boat", "_chest_boat", "_horse_armor")
    return item_id in NON_64_ITEMS or item_id.endswith(suffixes)


@dataclass(frozen=True)
class Waterway:
    name: str
    items: tuple[str, ...]
    containers: int


def read_waterway_litematic(raw: bytes, name: str = "水道投影") -> Waterway:
    root = read_nbt_gzip(raw)
    regions = root.get("Regions")
    if not isinstance(regions, dict) or not regions:
        raise ProjectError("投影中没有 Litematic Regions")
    containers: list[tuple[int, int, int, dict[str, Any], list[tuple[int, str]]]] = []
    for region in regions.values():
        pos = region.get("Position", {})
        rx, ry, rz = int(pos.get("x", 0)), int(pos.get("y", 0)), int(pos.get("z", 0))
        entities = region.get("TileEntities", region.get("BlockEntities", []))
        for entity in entities:
            ex, ey, ez = int(entity.get("x", 0)), int(entity.get("y", 0)), int(entity.get("z", 0))
            # Litematic tile entity coordinates may be local or absolute.
            state = _state_at(region, (ex - rx, ey - ry, ez - rz))
            absolute = (ex, ey, ez)
            if state is None:
                state = _state_at(region, (ex, ey, ez))
                absolute = (rx + ex, ry + ey, rz + ez)
            if state is None:
                continue
            block = _block_name(state)
            if block not in {"minecraft:chest", "minecraft:barrel"}:
                continue
            if block == "minecraft:chest":
                chest_type = str(state.get("Properties", {}).get("type", "single"))
                if chest_type != "single":
                    raise ProjectError(f"{name} 包含大箱，坐标 {absolute}；排版只允许单箱或木桶")
            containers.append((*absolute, state, _items_from_entity(entity)))
    if not containers:
        raise ProjectError(f"{name} 未找到含物品数据的单箱或木桶")
    yz = {(y, z) for _, y, z, _, _ in containers}
    if len(yz) != 1:
        raise ProjectError(f"{name} 的有效容器必须位于同一条 X+ 直线上")
    containers.sort(key=lambda value: value[0])
    if len({x for x, *_ in containers}) != len(containers):
        raise ProjectError(f"{name} 在相同 X 坐标发现多个有效容器，无法确定从内到外顺序")
    items = tuple(item for *_, slots in containers for _, item in slots)
    if not items:
        raise ProjectError(f"{name} 的有效容器均为空")
    blocked = sorted({item for item in items if _is_obviously_unstackable(item)})
    if blocked:
        raise ProjectError("第一版仅支持 64 堆叠物品，发现暂不支持的物品：" + "、".join(blocked[:8]))
    return Waterway(name, items, len(containers))


# ---------- Code planning ----------


@dataclass(frozen=True)
class AddressedItem:
    item_id: str
    thread: int
    waterway: int
    slot: int
    code: str
    source_name: str


@dataclass(frozen=True)
class EncoderChest:
    group: str
    value: str
    part: int
    items: tuple[AddressedItem, ...]


def _ceil_log(radix: int, amount: int) -> int:
    return 0 if amount <= 1 else math.ceil(math.log(amount, radix))


def _to_base(value: int, radix: int, width: int) -> str:
    digits = "0123456789AB"
    if value >= radix ** width:
        raise ProjectError("编码宽度不足")
    result = ""
    for _ in range(width):
        result = digits[value % radix] + result
        value //= radix
    return result


def allocate_addresses(config: dict[str, Any], uploaded: list[dict[str, Any]]) -> tuple[list[AddressedItem], dict[str, int]]:
    radix = int(config["radix"])
    threads = int(config.get("threads", 1))
    waterways = int(config["waterways"])
    total = int(config["total_digits"])
    if radix == 2:
        if threads not in {1, 2, 4, 8}:
            raise ProjectError("二进制线程数只能是 1、2、4 或 8")
    elif threads != 1:
        raise ProjectError("十进制和十二进制第一版不支持多线程")
    if len(uploaded) != threads * waterways:
        raise ProjectError("请先上传每个线程的每一条水道投影")
    thread_digits = _ceil_log(radix, threads)
    water_digits = _ceil_log(radix, waterways)
    slot_digits = total - thread_digits - water_digits
    if slot_digits < 0:
        raise ProjectError("总码位不足以表示线程与水道")
    per_waterway = radix ** slot_digits
    addresses: list[AddressedItem] = []
    seen: dict[str, AddressedItem] = {}
    ordered = sorted(uploaded, key=lambda row: (int(row["thread"]), int(row["waterway"])))
    for row in ordered:
        thread, waterway = int(row["thread"]), int(row["waterway"])
        values = row["items"]
        if not (0 <= thread < threads and 0 <= waterway < waterways):
            raise ProjectError("上传的线程或水道编号超出当前版型")
        if len(values) > per_waterway:
            raise ProjectError(f"线程 {thread}、水道 {waterway} 有 {len(values)} 种物品，但当前码长每水道最多容纳 {per_waterway} 种")
        for slot, item_id in enumerate(values):
            if item_id in seen:
                previous = seen[item_id]
                raise ProjectError(f"物品 {item_id} 重复：{previous.source_name} 与 {row['name']}")
            numeric = ((thread * waterways) + waterway) * per_waterway + slot
            code = _to_base(numeric, radix, total)
            item = AddressedItem(item_id, thread, waterway, slot, code, str(row["name"]))
            addresses.append(item)
            seen[item_id] = item
    return addresses, {"thread_digits": thread_digits, "water_digits": water_digits, "slot_digits": slot_digits, "per_waterway": per_waterway}


def _binary_partitions(width: int) -> Iterable[tuple[int, ...]]:
    # A boundary after each bit produces every ordered integer partition.
    for mask in range(1 << (width - 1)):
        result, current = [], 1
        for bit in range(width - 1):
            if mask & (1 << bit):
                result.append(current); current = 1
            else:
                current += 1
        result.append(current)
        yield tuple(result)


def _make_chests(addresses: list[AddressedItem], radix: int, partition: tuple[int, ...]) -> list[EncoderChest]:
    pools: dict[tuple[str, str], list[AddressedItem]] = defaultdict(list)
    for item in addresses:
        offset = 0
        for number, width in enumerate(partition, 1):
            value = item.code[offset:offset + width]
            offset += width
            if set(value) == {"0"}:
                continue  # no signal is the silent zero value
            pools[(f"第{number}段", value)].append(item)
    output: list[EncoderChest] = []
    for (group, value), items in sorted(pools.items()):
        for part, start in enumerate(range(0, len(items), 50), 1):
            output.append(EncoderChest(group, value, part, tuple(items[start:start + 50])))
    return output


def optimise_encoding(config: dict[str, Any], uploaded: list[dict[str, Any]]) -> dict[str, Any]:
    addresses, sizing = allocate_addresses(config, uploaded)
    radix, width = int(config["radix"]), int(config["total_digits"])
    if radix == 2:
        max_width = int(config.get("max_group_width", 5))
        candidates = [partition for partition in _binary_partitions(width) if max(partition) <= max_width]
        if not candidates:
            raise ProjectError("当前最大组合位宽无法覆盖总码位")
    else:
        candidates = [tuple(1 for _ in range(width))]
    options: list[tuple[tuple[int, ...], list[EncoderChest]]] = []
    for candidate in candidates:
        chests = _make_chests(addresses, radix, candidate)
        options.append((candidate, chests))
    options.sort(key=lambda pair: (len(pair[1]), len(pair[0]), max(pair[0])))
    selected_partition, selected_chests = options[0]
    requested = config.get("partition")
    if requested:
        selected_partition = tuple(int(v) for v in requested)
        if sum(selected_partition) != width:
            raise ProjectError("手动位组之和必须等于总码位")
        if radix == 2 and max(selected_partition) > int(config.get("max_group_width", 5)):
            raise ProjectError("手动位组超过当前允许的组合位宽")
        selected_chests = _make_chests(addresses, radix, selected_partition)
    compact_options = [{"partition": list(partition), "chests": len(chests)} for partition, chests in options[:12]]
    return {
        "addresses": addresses,
        "chests": selected_chests,
        "sizing": sizing,
        "partition": selected_partition,
        "options": compact_options,
    }


# ---------- 1.21.0 encoder Litematic writer ----------


def _item_nbt(item_id: str, count: int, slot: int, named: bool = False) -> dict[str, Any]:
    item: dict[str, Any] = {"Slot": _Byte(slot), "id": item_id, "count": _Byte(count)}
    if named:
        item["components"] = {"minecraft:custom_name": FILLER_NAME}
    return item


def chest_inventory(chest: EncoderChest) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for slot in range(50):
        if slot < len(chest.items):
            result.append(_item_nbt(chest.items[slot].item_id, 1, slot))
        else:
            result.append(_item_nbt(PAPER, 1, slot, named=True))
    result.append(_item_nbt(PAPER, 5, 50, named=True))
    for slot in range(51, 54):
        result.append(_item_nbt(SHEARS, 1, slot))
    return result


def _pack_states(ids: list[int], palette_size: int) -> _LongArray:
    bits = max(2, (palette_size - 1).bit_length())
    words = [0] * math.ceil(len(ids) * bits / 64)
    for index, state_id in enumerate(ids):
        offset, word, shift = index * bits, (index * bits) >> 6, (index * bits) & 63
        words[word] |= state_id << shift
        if shift + bits > 64:
            words[word + 1] |= state_id >> (64 - shift)
    return _LongArray([word if word < (1 << 63) else word - (1 << 64) for word in words])


def write_encoder_litematic(chests: list[EncoderChest], title: str = "全物品编码箱") -> bytes:
    length = max(1, len(chests))
    # Region memory order: y, z, x. Each double chest consumes the two z rows.
    states: list[int] = []
    for z in range(2):
        for x in range(length):
            states.append(1 if z == 0 else 2)
    entities: list[dict[str, Any]] = []
    for x, chest in enumerate(chests):
        inventory = chest_inventory(chest)
        entities.append({"id": "minecraft:chest", "x": _Int(x), "y": _Int(0), "z": _Int(0), "Items": _List(10, inventory[:27])})
        entities.append({"id": "minecraft:chest", "x": _Int(x), "y": _Int(0), "z": _Int(1), "Items": _List(10, [dict(item, Slot=_Byte(int(item["Slot"]) - 27)) for item in inventory[27:]])})
    palette = _List(10, [
        {"Name": AIR},
        {"Name": "minecraft:chest", "Properties": {"facing": "east", "type": "left", "waterlogged": "false"}},
        {"Name": "minecraft:chest", "Properties": {"facing": "east", "type": "right", "waterlogged": "false"}},
    ])
    now = int(time.time() * 1000)
    region = {
        "Position": {"x": _Int(0), "y": _Int(0), "z": _Int(0)},
        "Size": {"x": _Int(length), "y": _Int(1), "z": _Int(2)},
        "BlockStatePalette": palette,
        "BlockStates": _pack_states(states, len(palette)),
        "TileEntities": _List(10, entities),
        "Entities": _List(10, []),
        "PendingBlockTicks": _List(10, []),
        "PendingFluidTicks": _List(10, []),
    }
    root = {
        "Version": _Int(6), "SubVersion": _Int(1), "MinecraftDataVersion": _Int(3953),
        "Metadata": {
            "Name": title, "Author": "全物品编码投影生成器", "Description": "自动生成的 1.21.0 编码双箱投影",
            "RegionCount": _Int(1), "TimeCreated": _Long(now), "TimeModified": _Long(now),
            "TotalBlocks": _Int(len(chests) * 2), "TotalVolume": _Int(length * 2),
            "EnclosingSize": {"x": _Int(length), "y": _Int(1), "z": _Int(2)},
        },
        "Regions": {"encoder_chests": region},
    }
    return write_nbt_gzip(root)


def export_package(config: dict[str, Any], uploaded: list[dict[str, Any]]) -> bytes:
    plan = optimise_encoding(config, uploaded)
    addresses: list[AddressedItem] = plan["addresses"]
    chests: list[EncoderChest] = plan["chests"]
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("encoder.litematic", write_encoder_litematic(chests, str(config.get("project_name", "全物品编码箱"))))
        mapping = io.StringIO()
        writer = csv.writer(mapping)
        writer.writerow(["item_id", "source_projection", "thread", "waterway", "slot_from_inner", "code"])
        for item in addresses:
            writer.writerow([item.item_id, item.source_name, item.thread, item.waterway, item.slot, item.code])
        archive.writestr("mapping.csv", mapping.getvalue())
        for index, chest in enumerate(chests, 1):
            archive.writestr(f"encoders/{index:04d}_{chest.group}_{chest.value}_{chest.part}.txt", "\n".join(item.item_id for item in chest.items) + "\n")
        archive.writestr("manifest.json", json.dumps({
            "minecraft_version": "1.21.0", "config": config, "partition": list(plan["partition"]),
            "encoder_double_chests": len(chests), "items": len(addresses), "sizing": plan["sizing"],
            "ss_template": "slots 1-50: item/paper x1; slot 51: paper x5; slots 52-54: shears x1",
        }, ensure_ascii=False, indent=2))
    return out.getvalue()


def plan_summary(config: dict[str, Any], uploaded: list[dict[str, Any]]) -> dict[str, Any]:
    plan = optimise_encoding(config, uploaded)
    return {
        "items": len(plan["addresses"]), "encoder_chests": len(plan["chests"]),
        "partition": list(plan["partition"]), "sizing": plan["sizing"], "options": plan["options"],
        "chests": [{"group": c.group, "value": c.value, "part": c.part, "item_count": len(c.items), "sample": [i.item_id for i in c.items[:5]]} for c in plan["chests"]],
    }


def decode_base64(value: str) -> bytes:
    try:
        return base64.b64decode(value.split(",", 1)[-1], validate=True)
    except Exception as exc:
        raise ProjectError("上传数据无法解码") from exc
