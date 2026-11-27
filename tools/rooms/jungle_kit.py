"""정글 방 공용 부품: 바닥·나무·엄폐물·물길·레버 잠금 회로.

좌표 규칙: 바닥 y=0, 마커는 바닥 바로 위(y=1). 높은 층 마커는 그 층 바닥 바로 위.
"""
from __future__ import annotations

import json
import math
import random

from nbtlib.tag import Byte, Compound, List, String

from roomgen import Room

STONE_MIX = (("mossy_stone_bricks", 5), ("stone_bricks", 3), ("cracked_stone_bricks", 2), ("mossy_cobblestone", 2))
MUD_MIX = (("mud", 4), ("muddy_mangrove_roots", 1), ("moss_block", 2), ("packed_mud", 1))


def base(sx, sz, rng, sy=24, mix=None) -> Room:
    r = Room(sx, sy, sz)
    if mix:
        r.floor_mix(0, 0, 0, sx - 1, sz - 1, rng, mix)
    else:
        r.floor_mix(0, 0, 0, sx - 1, sz - 1, rng)
    return r


def edge_trees(r, n, rng, big_chance=0.3, hmin=7, hmax=12):
    """방 가장자리(바깥 4칸)에 나무 — 전투 공간을 막지 않게."""
    sx, _, sz = r.size
    for _ in range(n):
        side = rng.randint(0, 3)
        t = rng.randint(2, (sx if side < 2 else sz) - 4)
        x, z = [(t, 1), (t, sz - 4), (1, t), (sx - 4, t)][side]
        r.jungle_tree(x, 1, z, rng.randint(hmin, hmax), rng, big=rng.random() < big_chance)


def mobs(r, pts, y=1):
    for p in pts:
        if len(p) == 3:
            r.marker(p[0], p[1], p[2], "mob")
        else:
            r.marker(p[0], y, p[1], "mob")


def rubble(r, x, z, w, d, h, rng, mix=STONE_MIX, y=1):
    """무너진 벽 엄폐물: 위로 갈수록 듬성듬성."""
    names = [m[0] for m in mix]
    weights = [m[1] for m in mix]
    for dx in range(w):
        for dz in range(d):
            top = h if rng.random() < 0.7 else max(1, h - 1)
            for dy in range(top):
                r.set(x + dx, y + dy, z + dz, rng.choices(names, weights)[0])
    for dx in range(w):
        for dz in range(d):
            if rng.random() < 0.3:
                r.set(x + dx, y + h, z + dz, "moss_carpet")


def broken_pillar(r, x, z, h, rng, y=1):
    """2×2 부서진 기둥 (몸을 숨길 수 있는 세로 엄폐물)."""
    for dx in (0, 1):
        for dz in (0, 1):
            top = h - rng.randint(0, 2)
            for dy in range(top):
                r.set(x + dx, y + dy, z + dz, rng.choice(["mossy_stone_bricks", "stone_bricks", "cracked_stone_bricks"]))
    r.set(x + rng.randint(0, 1), y + h - 1, z + rng.randint(0, 1), "vine[north=true]")


def fallen_log(r, x, z, length, axis, y=1):
    """쓰러진 통나무 (낮은 엄폐물, 넘어갈 수 있음)."""
    for i in range(length):
        px, pz = (x + i, z) if axis == "x" else (x, z + i)
        r.set(px, y, pz, f"jungle_log[axis={axis}]")
        if i % 3 == 1:
            r.set(px, y + 1, pz, "moss_carpet")


def bamboo_grove(r, cx, cz, rad, rng, y=1, density=0.55):
    """대나무 숲: 시야를 가리는 부드러운 엄폐 (도끼·검으로 베어 길을 낼 수 있음)."""
    for x in range(cx - rad, cx + rad + 1):
        for z in range(cz - rad, cz + rad + 1):
            if (x - cx) ** 2 + (z - cz) ** 2 <= rad * rad and rng.random() < density:
                r.set(x, y - 1, z, "podzol")
                h = rng.randint(4, 8)
                for dy in range(h):
                    leaves = "large" if dy >= h - 2 else "none"
                    r.set(x, y + dy, z, f"bamboo[age=1,leaves={leaves},stage=0]")


def mound(r, cx, cz, rads, rng, y=1, top="moss_block"):
    """언덕: rads = 층별 반지름(아래→위). 높은 곳 확보용."""
    for i, rad in enumerate(rads):
        r.disc(cx, y + i, cz, rad, "rooted_dirt" if i < len(rads) - 1 else top)
        r.ring(cx, y + i, cz, rad - 1, rad, "grass_block")


def water_area(r, x1, z1, x2, z2, depth=1, bottom="mud", y0=0):
    """물웅덩이/수로. 바닥(y0)은 진흙, 그 위로 depth 칸 물. depth>1 이면 바닥을 파서 만들 수 없으니(바닥 y=0)
    주변을 높여 써야 함 → raised_rim 참고."""
    sx, _, sz = r.size
    for x in range(x1, x2 + 1):
        for z in range(z1, z2 + 1):
            r.set(x, y0, z, bottom)
            edge = x in (0, sx - 1) or z in (0, sz - 1)    # 방 가장자리는 둑 (물이 허공으로 새지 않게)
            for d in range(depth):
                r.set(x, y0 + 1 + d, z, "mud_bricks" if edge else "water")


def stairs_up(r, x, z, height, facing, stair="mossy_stone_brick_stairs", fill="mossy_stone_bricks", y=1, width=2):
    """계단 경사로 (대체 경로: 특기 무기 없이도 올라가기). facing = 올라가는 방향. 꼭대기 높이 = y+height-1."""
    dx, dz = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}[facing]
    wx, wz = (1, 0) if dz else (0, 1)
    for i in range(height):
        for w in range(width):
            px, pz = x + dx * i + wx * w, z + dz * i + wz * w
            for yy in range(y, y + i):
                r.set(px, yy, pz, fill)
            r.set(px, y + i, pz, f"{stair}[facing={facing},half=bottom]")


def vine_wall(r, x1, z, x2, y1, y2, facing):
    """벽에 덩굴 (타고 오르기). facing = 덩굴이 붙은 벽이 있는 쪽."""
    for x in range(min(x1, x2), max(x1, x2) + 1):
        for y in range(y1, y2 + 1):
            if r.get(x, y, z) is None:
                r.set(x, y, z, f"vine[{facing}=true]")


def ladder(r, x, z, y1, y2, facing):
    for y in range(y1, y2 + 1):
        r.set(x, y, z, f"ladder[facing={facing}]")


# --------------------------------------------------------------------------- 레버 잠금 회로
def lever_lock(r: Room, y: int, x0: int, zp: int, slots: list[bool], spacing: int = 2,
               panel: str = "chiseled_stone_bricks", panel_fill: str = "mossy_stone_bricks",
               casing: str = "mossy_stone_bricks", roof: str = "glass", floor: str = "polished_andesite",
               lever_face="south") -> tuple[int, int, int]:
    """레버 조합 자물쇠. slots[i]=True 인 레버는 켜야 하고, False(가짜)는 꺼져 있어야 [trigger] 램프가 켜짐.

    플레이어는 z > zp 쪽(남쪽)에 서서 패널(zp 줄)의 레버를 당김. 회로는 패널 뒤 zp-1, zp-2 줄 (유리 지붕으로 보임).
      정답 칸: 패널 블록 뒤에 레드스톤 횃불(켜짐) → 레버 ON 이면 횃불 꺼짐
      가짜 칸: 패널 블록 뒤에 가루 → 레버 ON 이면 OR 줄에 신호
      OR 줄(zp-2) → 블록 F → 횃불 TF → 가루 → [trigger] 램프
    정답 전부 ON + 가짜 전부 OFF → OR 줄 꺼짐 → TF 켜짐 → 램프 켜짐 → 클리어.
    배치 때 물리 업데이트가 없어서(RoomPlacer update(true,false)) 초기 신호 세기를 미리 계산해 넣음.
    반환: [trigger] 좌표.
    """
    assert any(slots), "정답 레버가 1개 이상 필요"
    xs = [x0 + spacing * i for i in range(len(slots))]
    xF = xs[-1] + 2
    xa, xb = x0 - 1, xF + 4
    # 자리 비우기 + 회로 아래 바닥 + 외곽
    r.fill(xa, y, zp - 3, xb, y + 2, zp - 1, "air")
    r.fill(xa, y - 1, zp - 3, xb, y - 1, zp + 1, floor)
    r.fill(xa, y, zp - 3, xb, y + 1, zp - 3, casing)     # 뒷벽
    r.fill(xa, y, zp - 2, xa, y + 1, zp - 1, casing)     # 옆벽
    r.fill(xb, y, zp - 2, xb, y + 1, zp - 1, casing)
    r.fill(xa, y + 2, zp - 3, xb, y + 2, zp - 1, roof)   # 회로가 보이는 지붕
    r.fill(xa, y, zp, xb, y + 2, zp, panel_fill)         # 패널 벽

    def p_or(x):
        return max(0, max(15 - abs(x - xi) for xi, ok in zip(xs, slots) if ok))

    for x in range(x0, xF):
        r.set(x, y, zp - 2, f"redstone_wire[power={p_or(x)}]")
    for xi, ok in zip(xs, slots):
        r.set(xi, y, zp, panel)
        r.set(xi, y, zp + 1, f"lever[face=wall,facing={lever_face},powered=false]")
        if ok:
            r.set(xi, y, zp - 1, "redstone_wall_torch[facing=north,lit=true]")
        else:
            r.set(xi, y, zp - 1, f"redstone_wire[power={max(0, p_or(xi) - 1)}]")
    # 레버 하나만 어긋나도 OR 줄 끝(F 앞)까지 신호가 닿아야 함 (가루는 1칸마다 1씩 약해짐)
    for xi, ok in zip(xs, slots):
        reach = (15 if ok else 14) - (xF - 1 - xi)
        assert reach >= 1, f"레버 x={xi} 가 F 에서 너무 멂 (spacing 을 줄일 것)"
    r.set(xF, y, zp - 2, casing)                                         # F (OR 줄이 가리킴)
    r.set(xF + 1, y, zp - 2, "redstone_wall_torch[facing=east,lit=false]")  # TF
    r.set(xF + 2, y, zp - 2, "redstone_wire[power=0]")
    r.marker(xF + 3, y, zp - 2, "trigger")
    return (xF + 3, y, zp - 2)


def lever_on_trigger(r: Room, x: int, y: int, z: int, lever_dir: str = "south") -> None:
    """[trigger] 자리(램프가 됨)에 레버를 바로 붙임 — 당기면 클리어. lever_dir = 레버가 튀어나온 방향."""
    dx, dz = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}[lever_dir]
    r.marker(x, y, z, "trigger")
    r.set(x + dx, y, z + dz, f"lever[face=wall,facing={lever_dir},powered=false]")


def carve(r: Room, x1, y1, z1, x2, y2, z2) -> None:
    """블록을 지워 빈칸(구조물에 안 들어감 = 빈 월드라 공기)으로."""
    for x in range(min(x1, x2), max(x1, x2) + 1):
        for y in range(min(y1, y2), max(y1, y2) + 1):
            for z in range(min(z1, z2), max(z1, z2) + 1):
                r.blocks.pop((x, y, z), None)
                r.block_nbt.pop((x, y, z), None)


def sign(r: Room, x, y, z, lines: list[str], block="oak_wall_sign[facing=south]", color="black") -> None:
    """안내 표지판 (마커 아님). 첫 줄을 [spawn] 같은 마커 이름으로 쓰지 말 것."""
    assert not lines or not lines[0].strip().startswith("["), "안내 표지판 첫 줄은 [ 로 시작하면 안 됨"
    msgs = [json.dumps(t, ensure_ascii=False) for t in (lines + [""] * 4)[:4]]
    text = Compound({"messages": List[String](msgs), "color": String(color), "has_glowing_text": Byte(1)})
    r.set_nbt(x, y, z, block, Compound({
        "id": String("minecraft:sign"),
        "front_text": text,
        "back_text": Compound({"messages": List[String](['""'] * 4), "color": String("black"),
                               "has_glowing_text": Byte(0)}),
        "is_waxed": Byte(1),
    }))


def ring_points(cx, cz, rad, n, start=0.0):
    return [(int(round(cx + math.cos(start + 2 * math.pi * i / n) * rad)),
             int(round(cz + math.sin(start + 2 * math.pi * i / n) * rad))) for i in range(n)]


def rng_for(seed, name) -> random.Random:
    return random.Random(f"{seed}:{name}")
