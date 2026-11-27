"""DeruyRPG 던전 방 템플릿(.nbt 구조물) 생성 도구.

규격 (docs/DUNGEON_GEN.md):
- 가로·세로 ≤ 64, 높이 ≤ 250, 바닥 포함 (던전 월드는 빈 허공)
- 마커 표지판 첫 줄: [spawn](필수 1) [mob](여러 개) [boss] [trigger](퍼즐 완료, 레드스톤 램프로 바뀜)
- 저장 위치: rooms/<풀>/<이름>.nbt (+ 선택 <이름>.yml: tags, weight)
  풀: camp combat elite puzzle shop rest event miniboss boss

사용 예:
    from roomgen import Room
    r = Room(40, 20, 40)
    r.fill(0, 0, 0, 39, 0, 39, "moss_block")
    r.marker(20, 1, 20, "spawn")
    r.save("out/rooms", "combat", "jungle_clearing_01", tags=["open"])

필요: pip install nbtlib
"""
from __future__ import annotations

import math
import os
import random
from typing import Iterable

import nbtlib
from nbtlib.tag import Compound, Int, List, String

# 1.21.4 형식으로 저장 → 서버(26.x)가 로드할 때 데이터 변환기로 자동 업그레이드
DATA_VERSION = 4189

AIR = "minecraft:air"


def _ns(block: str) -> str:
    return block if ":" in block else "minecraft:" + block


def _parse(block: str) -> tuple[str, dict[str, str]]:
    """'oak_stairs[facing=east,half=top]' → ('minecraft:oak_stairs', {...})"""
    if "[" in block:
        name, rest = block.split("[", 1)
        props = dict(p.split("=") for p in rest.rstrip("]").split(",") if p)
    else:
        name, props = block, {}
    return _ns(name.strip()), {k.strip(): v.strip() for k, v in props.items()}


class Room:
    """블록을 좌표에 찍고 .nbt 구조물로 저장. 좌표는 0 기준 (x 가로, y 높이, z 세로)."""

    def __init__(self, sx: int, sy: int, sz: int):
        if sx > 64 or sz > 64 or sy > 250:
            raise ValueError(f"방 크기 초과: {sx}x{sy}x{sz} (가로·세로 ≤64, 높이 ≤250)")
        self.size = (sx, sy, sz)
        self.blocks: dict[tuple[int, int, int], tuple[str, dict[str, str]]] = {}
        self.block_nbt: dict[tuple[int, int, int], Compound] = {}
        self.markers: dict[str, int] = {}

    # ---------------------------------------------------------------- 기본
    def inside(self, x: int, y: int, z: int) -> bool:
        sx, sy, sz = self.size
        return 0 <= x < sx and 0 <= y < sy and 0 <= z < sz

    def set(self, x: int, y: int, z: int, block: str) -> None:
        if not self.inside(x, y, z):
            return
        self.blocks[(x, y, z)] = _parse(block)
        self.block_nbt.pop((x, y, z), None)

    def get(self, x: int, y: int, z: int) -> str | None:
        b = self.blocks.get((x, y, z))
        return b[0] if b else None

    def fill(self, x1, y1, z1, x2, y2, z2, block: str) -> None:
        for x in range(min(x1, x2), max(x1, x2) + 1):
            for y in range(min(y1, y2), max(y1, y2) + 1):
                for z in range(min(z1, z2), max(z1, z2) + 1):
                    self.set(x, y, z, block)

    def hollow(self, x1, y1, z1, x2, y2, z2, block: str) -> None:
        """벽만 (바닥·천장 제외)."""
        for x in range(min(x1, x2), max(x1, x2) + 1):
            for z in range(min(z1, z2), max(z1, z2) + 1):
                if x in (x1, x2) or z in (z1, z2):
                    for y in range(min(y1, y2), max(y1, y2) + 1):
                        self.set(x, y, z, block)

    def disc(self, cx: float, y: int, cz: float, r: float, block: str) -> None:
        for x in range(int(cx - r) - 1, int(cx + r) + 2):
            for z in range(int(cz - r) - 1, int(cz + r) + 2):
                if (x + 0.5 - cx) ** 2 + (z + 0.5 - cz) ** 2 <= r * r:
                    self.set(x, y, z, block)

    def ring(self, cx: float, y: int, cz: float, r1: float, r2: float, block: str) -> None:
        for x in range(int(cx - r2) - 1, int(cx + r2) + 2):
            for z in range(int(cz - r2) - 1, int(cz + r2) + 2):
                d = math.hypot(x + 0.5 - cx, z + 0.5 - cz)
                if r1 <= d <= r2:
                    self.set(x, y, z, block)

    def column(self, x: int, z: int, y1: int, y2: int, block: str) -> None:
        self.fill(x, y1, z, x, y2, z, block)

    def scatter(self, area: Iterable[tuple[int, int, int]], block: str, chance: float, rng: random.Random) -> None:
        for (x, y, z) in area:
            if rng.random() < chance:
                self.set(x, y, z, block)

    # ---------------------------------------------------------------- 정글 장식
    def jungle_tree(self, x: int, y: int, z: int, h: int, rng: random.Random, big: bool = False) -> None:
        w = 2 if big else 1
        for dx in range(w):
            for dz in range(w):
                self.column(x + dx, z + dz, y, y + h - 1, "jungle_log")
        top = y + h
        r = 4 if big else 3
        for yy in range(top - 2, top + 2):
            rr = r - max(0, yy - top + 1)
            for dx in range(-rr, rr + w):
                for dz in range(-rr, rr + w):
                    if dx * dx + dz * dz <= rr * rr + 1 and self.get(x + dx, yy, z + dz) is None:
                        self.set(x + dx, yy, z + dz, "jungle_leaves[persistent=true]")
        for _ in range(6 if big else 3):  # 덩굴
            vx, vz = x + rng.randint(-r, r), z + rng.randint(-r, r)
            for yy in range(top - 2, top - 2 - rng.randint(2, 6), -1):
                if self.get(vx, yy, vz) is None:
                    self.set(vx, yy, vz, "vine[north=true]")

    def floor_mix(self, y: int, x1: int, z1: int, x2: int, z2: int, rng: random.Random,
                  mix=(("grass_block", 5), ("moss_block", 3), ("coarse_dirt", 1), ("rooted_dirt", 1))) -> None:
        names = [m[0] for m in mix]
        weights = [m[1] for m in mix]
        for x in range(x1, x2 + 1):
            for z in range(z1, z2 + 1):
                self.set(x, y, z, rng.choices(names, weights)[0])

    def plants(self, y: int, x1: int, z1: int, x2: int, z2: int, rng: random.Random, chance: float = 0.12) -> None:
        opts = ["short_grass", "fern", "moss_carpet", "azalea", "flowering_azalea", "large_fern[half=lower]"]
        for x in range(x1, x2 + 1):
            for z in range(z1, z2 + 1):
                if self.get(x, y, z) is None and rng.random() < chance:
                    b = rng.choice(opts)
                    self.set(x, y, z, b)
                    if b.startswith("large_fern"):
                        self.set(x, y + 1, z, "large_fern[half=upper]")

    # ---------------------------------------------------------------- 마커
    def marker(self, x: int, y: int, z: int, kind: str) -> None:
        """마커 표지판. kind: spawn | mob | boss | trigger. 바닥 바로 위 칸에 둘 것."""
        kind = kind.strip("[]").lower()
        if kind not in ("spawn", "mob", "boss", "trigger"):
            raise ValueError("마커는 spawn|mob|boss|trigger")
        if kind == "spawn" and self.markers.get("spawn"):
            raise ValueError("[spawn] 은 방마다 1개")
        self.set(x, y, z, "oak_sign[rotation=0]")
        lines = [f'"[{kind}]"', '""', '""', '""']  # 1.21.4 형식(JSON 문자열)
        text = Compound({"messages": List[String](lines), "color": String("black"), "has_glowing_text": nbtlib.Byte(0)})
        self.block_nbt[(x, y, z)] = Compound({
            "id": String("minecraft:sign"),
            "front_text": text,
            "back_text": Compound({"messages": List[String](['""'] * 4), "color": String("black"),
                                   "has_glowing_text": nbtlib.Byte(0)}),
            "is_waxed": nbtlib.Byte(0),
        })
        self.markers[kind] = self.markers.get(kind, 0) + 1

    # ---------------------------------------------------------------- 저장
    def validate(self, pool: str) -> list[str]:
        errs = []
        if self.markers.get("spawn", 0) != 1:
            errs.append("[spawn] 표지판이 정확히 1개 필요")
        if pool in ("combat", "elite") and self.markers.get("mob", 0) < 3:
            errs.append("전투방은 [mob] 3개 이상 권장")
        if pool in ("boss", "miniboss") and not self.markers.get("boss"):
            errs.append("보스방은 [boss] 필요")
        if pool in ("puzzle",) and not self.markers.get("trigger"):
            errs.append("퍼즐방은 [trigger] 필요 (없으면 /dg next 로만 진행)")
        return errs

    def save(self, root: str, pool: str, name: str, tags: list[str] | None = None, weight: int = 1,
             strict: bool = True) -> str:
        errs = self.validate(pool)
        if errs and strict:
            raise ValueError(f"{pool}/{name}: " + ", ".join(errs))
        palette: list[tuple[str, tuple]] = []
        index: dict[tuple, int] = {}
        blocks = []
        for pos, (name_, props) in sorted(self.blocks.items()):
            key = (name_, tuple(sorted(props.items())))
            if key not in index:
                index[key] = len(palette)
                palette.append(key)
            b = {"pos": List[Int]([Int(v) for v in pos]), "state": Int(index[key])}
            if pos in self.block_nbt:
                b["nbt"] = self.block_nbt[pos]
            blocks.append(Compound(b))
        pal = []
        for n, props in palette:
            c = {"Name": String(n)}
            if props:
                c["Properties"] = Compound({k: String(v) for k, v in props})
            pal.append(Compound(c))
        root_tag = Compound({
            "DataVersion": Int(DATA_VERSION),
            "size": List[Int]([Int(v) for v in self.size]),
            "palette": List[Compound](pal),
            "blocks": List[Compound](blocks),
            "entities": List[Compound]([]),
        })
        d = os.path.join(root, pool)
        os.makedirs(d, exist_ok=True)
        path = os.path.join(d, name + ".nbt")
        nbtlib.File(root_tag).save(path, gzipped=True)
        if tags or weight != 1:
            with open(os.path.join(d, name + ".yml"), "w", encoding="utf-8") as f:
                if tags:
                    f.write("tags: [" + ", ".join(tags) + "]\n")
                if weight != 1:
                    f.write(f"weight: {weight}\n")
        return path


def check(path: str) -> str:
    """저장된 .nbt 를 다시 읽어 크기·블록 수·마커 확인."""
    f = nbtlib.load(path)
    size = [int(v) for v in f["size"]]
    marks = {}
    for b in f["blocks"]:
        if "nbt" in b and "front_text" in b["nbt"]:
            t = str(b["nbt"]["front_text"]["messages"][0]).strip('"')
            marks[t] = marks.get(t, 0) + 1
    return f"{os.path.basename(path)} size={size} blocks={len(f['blocks'])} markers={marks}"
