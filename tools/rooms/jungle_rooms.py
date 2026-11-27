"""정글 챕터 샘플 방 생성 — 풀마다 1개 이상 (전투는 태그별 1개씩).

    python jungle_rooms.py [출력폴더=out/rooms] [시드=1]

출력 폴더의 하위 폴더들을 서버 plugins/DeruyRPG/rooms/ 에 그대로 복사 → /dg reload → /dg rooms.
이 파일은 "출발점": 다른 세션은 같은 방식으로 방을 늘림 (목표 수는 docs/ROOMS_HANDOFF.md).
"""
import math
import random
import sys

from roomgen import Room, check

OUT = sys.argv[1] if len(sys.argv) > 1 else "out/rooms"
rng = random.Random(int(sys.argv[2]) if len(sys.argv) > 2 else 1)
made = []


def base(sx, sz, sy=24):
    r = Room(sx, sy, sz)
    r.floor_mix(0, 0, 0, sx - 1, sz - 1, rng)
    return r


def edge_trees(r, n, big_chance=0.3):
    sx, _, sz = r.size
    for _ in range(n):
        side = rng.randint(0, 3)
        t = rng.randint(2, (sx if side < 2 else sz) - 4)
        x, z = [(t, 1), (t, sz - 4), (1, t), (sx - 4, t)][side]
        r.jungle_tree(x, 1, z, rng.randint(7, 12), rng, big=rng.random() < big_chance)


def mobs(r, pts):
    for (x, z) in pts:
        r.marker(x, 1, z, "mob")


def save(r, pool, name, tags=None):
    made.append(r.save(OUT, pool, name, tags=tags))


# ------------------------------------------------------------------ camp
r = base(28, 28)
r.fill(10, 1, 10, 17, 1, 17, "spruce_planks")
for (x, z) in ((10, 10), (17, 10), (10, 17), (17, 17)):
    r.column(x, z, 2, 5, "stripped_jungle_log")
r.fill(10, 6, 10, 17, 6, 17, "green_wool")
r.set(13, 2, 13, "campfire[lit=true]")
for x in range(10, 18, 2):
    r.set(x, 2, 8, "barrel[facing=up]")
r.marker(13, 2, 15, "spawn")
edge_trees(r, 6)
r.plants(1, 0, 0, 27, 27, rng)
save(r, "camp", "explorer_camp")

# ------------------------------------------------------------------ combat (태그별)
# open: 넓은 공터
r = base(44, 44)
edge_trees(r, 10)
r.plants(1, 0, 0, 43, 43, rng, 0.08)
r.marker(22, 1, 6, "spawn")
mobs(r, [(10, 30), (22, 36), (34, 30), (16, 24), (28, 24)])
save(r, "combat", "jungle_clearing_open_01", ["open"])

# water: 얕은 수로 + 맹그로브 섬
r = base(44, 44)
for x in range(0, 44):
    for z in range(14, 30):
        if rng.random() < 0.9:
            r.set(x, 0, z, "mud")
            r.set(x, 1, z, "water")
r.disc(14, 1, 22, 4, "muddy_mangrove_roots")
r.disc(30, 1, 22, 3.5, "muddy_mangrove_roots")
for (x, z) in ((14, 22), (30, 22)):
    r.column(x, z, 2, 8, "mangrove_log")
    r.disc(x, 9, z, 3, "mangrove_leaves[persistent=true]")
r.marker(22, 1, 4, "spawn")
mobs(r, [(11, 22), (33, 22), (8, 38), (36, 38)])
save(r, "combat", "flooded_channel_water_01", ["water"])

# vertical: 거목 2층 나무다리
r = base(40, 40, sy=30)
for (x, z) in ((6, 6), (30, 6), (6, 30), (30, 30)):
    r.jungle_tree(x, 1, z, 20, rng, big=True)
for y in (8, 15):
    r.fill(7, y, 8, 30, y, 9, "jungle_planks")
    r.fill(8, y, 7, 9, y, 30, "jungle_planks")
    r.fill(30, y, 8, 31, y, 30, "jungle_planks")
for y in range(1, 16):  # 1층·2층 올라가는 사다리
    r.set(10, y, 10, "ladder[facing=south]")
r.marker(20, 1, 20, "spawn")
mobs(r, [(15, 30), (25, 30), (20, 9), (31, 20)])
save(r, "combat", "canopy_bridges_vertical_01", ["vertical"])

# narrow: 무너진 회랑
r = base(18, 50, sy=14)
r.hollow(0, 1, 0, 17, 9, 49, "mossy_stone_bricks")
r.fill(1, 10, 1, 16, 10, 48, "mossy_cobblestone")
for z in range(6, 46, 8):
    r.fill(4, 1, z, 6, 6, z, "cracked_stone_bricks")
    r.fill(11, 1, z + 4, 13, 6, z + 4, "cracked_stone_bricks")
r.set(8, 9, 20, "air")
r.marker(9, 1, 2, "spawn")
mobs(r, [(9, 20), (9, 30), (9, 44), (3, 40)])
save(r, "combat", "ruined_corridor_narrow_01", ["narrow"])

# ------------------------------------------------------------------ elite
r = base(40, 40)
r.ring(20, 1, 20, 14, 15, "mossy_stone_brick_wall")
edge_trees(r, 6)
r.marker(20, 1, 4, "spawn")
mobs(r, [(20, 26), (14, 20), (26, 20)])
save(r, "elite", "overgrown_arena_01", ["open"])

# ------------------------------------------------------------------ puzzle (레버 → 트리거 램프)
r = base(30, 30, sy=12)
r.fill(12, 1, 22, 18, 3, 24, "chiseled_stone_bricks")
r.set(13, 2, 21, "lever[face=wall,facing=north,powered=false]")
r.fill(14, 1, 21, 17, 1, 21, "redstone_wire")
r.marker(18, 1, 21, "trigger")  # 회로 끝 → 신호 들어오면 클리어
r.marker(15, 1, 4, "spawn")
save(r, "puzzle", "vine_lever_01")

# ------------------------------------------------------------------ shop / rest / event
for pool, name in (("shop", "wandering_trader_hut_01"), ("rest", "quiet_spring_01"), ("event", "trapped_explorer_01")):
    r = base(26, 26)
    edge_trees(r, 5)
    if pool == "rest":
        r.disc(13, 0, 13, 4, "water")
    if pool == "shop":
        r.fill(9, 1, 9, 16, 1, 16, "jungle_planks")
        r.set(12, 2, 12, "barrel[facing=up]")
    if pool == "event":
        r.fill(11, 1, 11, 14, 4, 14, "vine[north=true]")
    r.plants(1, 0, 0, 25, 25, rng)
    r.marker(13, 1, 3, "spawn")
    save(r, pool, name)

# ------------------------------------------------------------------ miniboss / boss (BOSS.md 조건)
for pool, name in (("miniboss", "moss_gate_small_01"), ("boss", "sunken_floodgate_01")):
    R = 17
    S = 40
    r = Room(S, 26, S)
    c = S / 2
    r.disc(c, 0, c, R + 2, "mossy_stone_bricks")
    r.disc(c, 0, c, R - 2, "moss_block")              # 평평한 바닥 반경 ≥16
    r.ring(c, 0, c, R - 2, R + 0.5, "mud")
    r.ring(c, 1, c, R - 2, R + 0.5, "water")          # 가장자리 얕은 물 링
    r.ring(c, 1, c, R + 1, R + 2, "mossy_stone_brick_wall")
    for a in (45, 135, 225, 315):                       # 기둥 4개
        px, pz = int(c + math.cos(math.radians(a)) * 11), int(c + math.sin(math.radians(a)) * 11)
        r.fill(px, 1, pz, px + 1, 12, pz + 1, "mossy_stone_bricks")
    if pool == "boss":
        r.ring(c, 13, c, R - 1, R + 2, "mossy_stone_bricks")  # 천장 테 (천장 ≥12)
    r.marker(int(c), 1, int(c), "boss")
    r.marker(int(c), 1, int(c - R + 4), "spawn")       # 입구 안쪽, 보스 정면 아님
    save(r, pool, name)

for p in made:
    print(check(p))
print(f"{len(made)}개 생성 → {OUT}")
