"""정글 챕터 방 템플릿 생성.

    python jungle_rooms.py [출력폴더=out/rooms] [시드=1]

출력 폴더의 하위 폴더들을 서버 plugins/DeruyRPG/rooms/ 에 그대로 복사 → /dg reload → /dg rooms.
규격·목표 수: docs/ROOMS_HANDOFF.md. 방마다 자기 이름으로 시드를 갈라 써서, 방을 추가해도 기존 방 모양은 그대로.

목표 수 (정글): camp 1 · combat 12 (vertical/water/narrow/open × 3) · elite 4 · puzzle 4 · shop 3 · rest 2 · event 2
                · miniboss 1 · boss 1
"""
import math
import sys

from roomgen import Room, check
from jungle_kit import (MUD_MIX, STONE_MIX, bamboo_grove, base, broken_pillar, carve, edge_trees, fallen_log,
                        ladder, lever_lock, lever_on_trigger, mobs, mound, rng_for, rubble, sign, stairs_up,
                        vine_wall, water_area)

OUT = sys.argv[1] if len(sys.argv) > 1 else "out/rooms"
SEED = int(sys.argv[2]) if len(sys.argv) > 2 else 1

ROOMS = []  # (pool, name, tags, weight, builder)


def room(pool, name, tags=None, weight=1):
    def deco(fn):
        ROOMS.append((pool, name, tags, weight, fn))
        return fn
    return deco


# =================================================================== camp
@room("camp", "explorer_camp")
def explorer_camp(rng):
    r = base(28, 28, rng)
    r.fill(10, 1, 10, 17, 1, 17, "spruce_planks")
    for (x, z) in ((10, 10), (17, 10), (10, 17), (17, 17)):
        r.column(x, z, 2, 5, "stripped_jungle_log")
    r.fill(10, 6, 10, 17, 6, 17, "green_wool")
    r.set(13, 2, 13, "campfire[lit=true]")
    for x in range(10, 18, 2):
        r.set(x, 2, 8, "barrel[facing=up]")
    r.marker(13, 2, 15, "spawn")
    edge_trees(r, 6, rng)
    r.plants(1, 0, 0, 27, 27, rng)
    return r


# =================================================================== combat · open (도끼·검: 넓게 휘두르는 공터)
@room("combat", "jungle_clearing_open_01", ["open"])
def clearing_open_01(rng):
    """기본 공터: 엄폐물 거의 없음 → 몹 떼를 한 번에 휩쓰는 방."""
    r = base(44, 44, rng)
    edge_trees(r, 10, rng)
    fallen_log(r, 12, 18, 6, "x")
    fallen_log(r, 30, 20, 5, "z")
    r.plants(1, 0, 0, 43, 43, rng, 0.08)
    r.marker(22, 1, 6, "spawn")
    mobs(r, [(10, 30), (22, 36), (34, 30), (16, 26), (28, 26)])
    return r


@room("combat", "ruin_plaza_open_02", ["open"])
def ruin_plaza_open_02(rng):
    """무너진 광장: 가운데 낮은 단(고지대) + 모서리 기둥 4 + 낮은 잔해벽. 몹은 단 위와 바깥에서 동시에."""
    r = base(48, 48, rng)
    r.floor_mix(0, 9, 9, 38, 38, rng, STONE_MIX)
    r.disc(24, 1, 24, 6, "mossy_stone_bricks")          # 단 (1칸 높이)
    r.ring(24, 1, 24, 5, 6, "stone_brick_stairs[facing=north,half=bottom]")
    for (x, z) in ((11, 11), (35, 11), (11, 35), (35, 35)):
        broken_pillar(r, x, z, rng.randint(4, 7), rng)
    rubble(r, 17, 16, 5, 1, 2, rng)
    rubble(r, 27, 32, 5, 1, 2, rng)
    rubble(r, 8, 22, 1, 5, 1, rng)
    rubble(r, 39, 21, 1, 5, 2, rng)
    edge_trees(r, 8, rng)
    r.plants(1, 0, 0, 47, 47, rng, 0.05)
    r.marker(24, 1, 4, "spawn")
    mobs(r, [(24, 2, 24), (14, 1, 40), (34, 1, 40), (42, 1, 28), (5, 1, 30)])
    return r


@room("combat", "bamboo_meadow_open_03", ["open"])
def bamboo_meadow_open_03(rng):
    """대나무 초원: 대나무 숲(시야 차단, 베면 길이 남) + 가운데 언덕(고지대) + 쓰러진 통나무."""
    r = base(44, 44, rng)
    bamboo_grove(r, 12, 14, 4, rng)
    bamboo_grove(r, 31, 28, 4, rng)
    bamboo_grove(r, 10, 33, 3, rng)
    mound(r, 22, 22, [6, 4, 2], rng)                    # 꼭대기 서는 높이 y=4
    fallen_log(r, 26, 9, 8, "x")
    fallen_log(r, 17, 31, 6, "z")
    edge_trees(r, 6, rng)
    r.plants(1, 0, 0, 43, 43, rng, 0.06)
    r.marker(22, 1, 3, "spawn")
    mobs(r, [(34, 1, 12), (7, 1, 22), (22, 4, 22), (36, 1, 37), (29, 1, 19)])
    return r


# =================================================================== combat · water (삼지창·낚싯대: 물에서 강함)
@room("combat", "flooded_channel_water_01", ["water"])
def flooded_channel_water_01(rng):
    """얕은 수로 + 맹그로브 섬 2개. 물 밖 둑으로도 돌아갈 수 있음."""
    r = base(44, 44, rng)
    for x in range(0, 44):
        for z in range(14, 30):
            if rng.random() < 0.9:
                r.set(x, 0, z, "mud")
                r.set(x, 1, z, "mud_bricks" if x in (0, 43) else "water")
    r.disc(14, 1, 22, 4, "muddy_mangrove_roots")
    r.disc(30, 1, 22, 3.5, "muddy_mangrove_roots")
    for (x, z) in ((14, 22), (30, 22)):
        r.column(x, z, 2, 8, "mangrove_log")
        r.disc(x, 9, z, 3, "mangrove_leaves[persistent=true]")
    r.marker(22, 1, 4, "spawn")
    mobs(r, [(6, 1, 22), (38, 1, 22), (8, 1, 38), (36, 1, 38), (22, 1, 27)])
    return r


@room("combat", "mangrove_delta_water_02", ["water"])
def mangrove_delta_water_02(rng):
    """삼각주: 수로 2줄이 방을 가로지름 + 가운데 연결 수로. 다리(서쪽 끝)로 돌아가거나 물로 질러가기."""
    r = base(48, 48, rng, mix=MUD_MIX)
    water_area(r, 0, 12, 47, 17)
    water_area(r, 0, 30, 47, 35)
    water_area(r, 22, 18, 26, 29)
    for z0 in (12, 30):                                   # 서쪽 끝 다리 (대체 경로)
        r.fill(3, 1, z0, 4, 1, z0 + 5, "mangrove_planks")
        r.fill(2, 2, z0, 2, 2, z0 + 5, "mangrove_fence")
        r.fill(5, 2, z0, 5, 2, z0 + 5, "mangrove_fence")
    for (x, z) in ((12, 22), (36, 24), (40, 42), (10, 41)):  # 둑의 맹그로브
        r.disc(x, 1, z, 2.2, "mangrove_roots")
        r.column(x, z, 1, 7, "mangrove_log")
        r.disc(x, 8, z, 3.5, "mangrove_leaves[persistent=true]")
        r.disc(x, 9, z, 2.5, "mangrove_leaves[persistent=true]")
    for x in range(0, 48, 3):                             # 연잎
        for z0 in (12, 30):
            z = z0 + rng.randint(0, 5)
            if rng.random() < 0.5 and r.get(x, 1, z) == "minecraft:water" and r.get(x, 2, z) is None:
                r.set(x, 2, z, "lily_pad")
    r.plants(1, 0, 0, 47, 47, rng, 0.05)
    r.marker(24, 1, 3, "spawn")
    mobs(r, [(30, 1, 14), (16, 1, 33), (40, 1, 22), (18, 1, 24), (34, 1, 42)])
    return r


@room("combat", "sunken_pool_water_03", ["water"])
def sunken_pool_water_03(rng):
    """가라앉은 사원 연못: 깊이 2 연못 + 둘레 둑 + 징검돌. 익사체는 연못 안, 원거리 몹은 둑 위."""
    r = Room(44, 20, 44)
    r.floor_mix(0, 0, 0, 43, 43, rng, STONE_MIX)
    r.fill(0, 1, 0, 43, 2, 43, "mossy_stone_bricks")      # 둑 (서는 높이 y=3)
    r.floor_mix(2, 0, 0, 43, 43, rng, STONE_MIX)
    for x in range(8, 36):
        for z in range(10, 36):
            r.set(x, 0, z, "mud")
            r.set(x, 1, z, "water")
            r.set(x, 2, z, "water")
    for z in range(11, 35, 3):                            # 징검돌 (점프로 건너기)
        x = 21 + rng.randint(-1, 1)
        r.fill(x, 1, z, x + 1, 2, z, "mossy_stone_bricks")
    for (x, z) in ((12, 14), (30, 14), (12, 30), (30, 30)):
        broken_pillar(r, x, z, rng.randint(4, 6), rng)
    for _ in range(25):
        x, z = rng.randint(9, 34), rng.randint(11, 34)
        if r.get(x, 2, z) == "minecraft:water" and r.get(x, 3, z) is None:
            r.set(x, 3, z, "lily_pad")
    for x in (0, 43):
        r.fill(x, 3, 0, x, 3, 43, "mossy_stone_brick_wall")
    edge_trees(r, 0, rng)
    r.marker(22, 3, 4, "spawn")
    mobs(r, [(15, 1, 20), (28, 1, 25), (4, 3, 22), (39, 3, 22), (22, 3, 39)])
    return r


# =================================================================== combat · vertical (철퇴·활·쇠뇌: 높이차)
@room("combat", "canopy_bridges_vertical_01", ["vertical"])
def canopy_bridges_vertical_01(rng):
    """거목 4그루 + 2층 나무다리. 사다리로 오르기 / 철퇴로 뛰어내려 찍기."""
    r = base(40, 40, rng, sy=30)
    for (x, z) in ((6, 6), (30, 6), (6, 30), (30, 30)):
        r.jungle_tree(x, 1, z, 20, rng, big=True)
    for y in (8, 15):
        r.fill(7, y, 8, 30, y, 9, "jungle_planks")
        r.fill(8, y, 7, 9, y, 30, "jungle_planks")
        r.fill(30, y, 8, 31, y, 30, "jungle_planks")
    r.column(10, 9, 1, 15, "jungle_log")                  # 사다리 받침
    ladder(r, 10, 10, 1, 15, "south")
    r.marker(20, 1, 20, "spawn")
    mobs(r, [(15, 1, 30), (25, 1, 30), (20, 1, 12), (24, 9, 8), (30, 16, 20)])
    return r


@room("combat", "temple_steps_vertical_02", ["vertical"])
def temple_steps_vertical_02(rng):
    """계단식 사원: 3단 피라미드 위에 원거리 몹. 정면 계단(대체 경로) / 옆면 덩굴 / 철퇴로 단에서 뛰어내리기."""
    r = base(46, 46, rng)
    tiers = [(11, 35, 14, 38, 1, 3), (15, 31, 18, 34, 4, 6), (19, 27, 22, 30, 7, 9)]
    for i, (x1, x2, z1, z2, y1, y2) in enumerate(tiers):
        r.fill(x1, y1, z1, x2, y2, z2, "mossy_stone_bricks" if i % 2 == 0 else "stone_bricks")
        r.fill(x1, y2, z1, x2, y2, z2, "mossy_cobblestone")
    stairs_up(r, 22, 11, 3, "south")
    stairs_up(r, 22, 15, 3, "south", y=4)
    stairs_up(r, 22, 19, 3, "south", y=7)
    vine_wall(r, 12, 13, 18, 1, 3, "south")               # 옆길: 덩굴 타기
    vine_wall(r, 28, 13, 34, 1, 3, "south")
    for (x, z) in ((5, 12), (38, 10), (6, 40)):
        broken_pillar(r, x, z, rng.randint(4, 6), rng)
    r.set(20, 10, 23, "lantern"); r.set(26, 10, 29, "lantern")
    edge_trees(r, 6, rng)
    r.plants(1, 0, 0, 45, 45, rng, 0.05)
    r.marker(23, 1, 4, "spawn")
    mobs(r, [(21, 10, 27), (17, 7, 32), (33, 4, 36), (6, 1, 30), (40, 1, 22)])
    return r


@room("combat", "canopy_ladders_vertical_03", ["vertical"])
def canopy_ladders_vertical_03(rng):
    """수관 발판: 거목마다 6층 발판, 두 그루는 12층 망루. 사다리·다리로 연결, 지상은 넓게 비움."""
    r = base(44, 44, rng, sy=32)
    trees = [(8, 8, False), (33, 9, True), (9, 33, True), (32, 32, False)]
    for (x, z, tall) in trees:
        r.jungle_tree(x, 1, z, 18, rng, big=True)
        for y in ((6, 12) if tall else (6,)):
            rad = 4.2
            for px in range(x - 5, x + 7):
                for pz in range(z - 5, z + 7):
                    if (px - x - 0.5) ** 2 + (pz - z - 0.5) ** 2 <= rad * rad and r.get(px, y, pz) != "minecraft:jungle_log":
                        r.set(px, y, pz, "jungle_planks")
            r.ring(x + 0.5, y + 1, z + 0.5, rad - 0.7, rad, "jungle_fence")
        ladder(r, x - 1, z, 1, 12 if tall else 7, "west")   # 발판을 뚫고 올라감
    r.fill(12, 6, 8, 29, 6, 9, "jungle_planks")           # 6층 다리
    r.fill(8, 6, 12, 9, 6, 29, "jungle_planks")
    r.fill(13, 6, 32, 28, 6, 33, "jungle_planks")
    r.plants(1, 0, 0, 43, 43, rng, 0.05)
    r.marker(22, 1, 22, "spawn")
    mobs(r, [(11, 7, 9), (36, 13, 10), (12, 7, 34), (22, 1, 38), (37, 1, 22)])
    return r


# =================================================================== combat · narrow (창·방패·검: 좁은 길)
@room("combat", "ruined_corridor_narrow_01", ["narrow"])
def ruined_corridor_narrow_01(rng):
    """무너진 회랑: 좌우 엇갈린 벽 조각 → 지그재그 전진."""
    r = base(18, 50, rng, sy=14)
    r.hollow(0, 1, 0, 17, 9, 49, "mossy_stone_bricks")
    r.fill(1, 10, 1, 16, 10, 48, "mossy_cobblestone")
    for z in range(6, 46, 8):
        r.fill(4, 1, z, 6, 6, z, "cracked_stone_bricks")
        r.fill(11, 1, z + 4, 13, 6, z + 4, "cracked_stone_bricks")
    r.set(8, 10, 20, "air")
    r.marker(9, 1, 2, "spawn")
    mobs(r, [(9, 1, 20), (9, 1, 32), (8, 1, 44), (2, 1, 40)])
    return r


@room("combat", "root_tunnel_narrow_02", ["narrow"])
def root_tunnel_narrow_02(rng):
    """뿌리 굴: S자 4칸 통로, 뿌리 기둥이 길을 반으로 가름 → 한 명씩 막고 찌르기."""
    r = Room(36, 10, 52)
    for x in range(36):
        for z in range(52):
            r.set(x, 0, z, rng.choice(["rooted_dirt", "mud", "moss_block"]))
            for y in range(1, 7):
                r.set(x, y, z, rng.choice(["rooted_dirt", "packed_mud", "dirt", "mud_bricks"]))
    path = [(4, 1, 7, 20), (4, 17, 31, 20), (28, 17, 31, 36), (4, 33, 31, 36), (4, 33, 7, 50)]
    for (x1, z1, x2, z2) in path:
        carve(r, x1, 1, z1, x2, 5, z2)
    for (x1, z1, x2, z2) in path:
        for x in range(x1, x2 + 1):
            for z in range(z1, z2 + 1):
                if rng.random() < 0.06:
                    r.set(x, 6, z, "shroomlight")
                elif rng.random() < 0.12:
                    r.set(x, 5, z, "hanging_roots")
    for (x, z) in ((5, 10), (6, 14), (12, 18), (20, 19), (29, 26), (30, 30), (24, 34), (14, 35), (6, 40)):
        r.column(x, z, 1, 5, "mangrove_roots")
    r.marker(5, 1, 2, "spawn")
    mobs(r, [(16, 1, 18), (29, 1, 21), (29, 1, 33), (18, 1, 33), (5, 1, 45)])
    return r


@room("combat", "collapsed_gallery_narrow_03", ["narrow"])
def collapsed_gallery_narrow_03(rng):
    """무너진 쌍회랑: 나란한 통로 2개, 벽 틈 3곳으로 오가며 옆을 칠 수 있음."""
    r = Room(15, 12, 56)
    r.floor_mix(0, 0, 0, 14, 55, rng, STONE_MIX)
    r.hollow(0, 1, 0, 14, 7, 55, "mossy_stone_bricks")
    r.fill(6, 1, 1, 8, 7, 54, "stone_bricks")              # 가운데 벽
    r.fill(0, 8, 0, 14, 8, 55, "mossy_cobblestone")
    for z0 in (14, 30, 46):                               # 벽 틈 (잔해 1칸 → 넘어갈 수 있음)
        carve(r, 6, 1, z0, 8, 7, z0 + 2)
        rubble(r, 6, z0, 3, 3, 1, rng)
    for z in range(4, 54, 6):
        r.set(0, 4, z, "air"); r.set(14, 4, z, "air")        # 빛 들어오는 틈
    for z in range(5, 54, 10):
        r.set(2, 1, z, "mossy_stone_brick_wall"); r.set(12, 1, z + 5, "mossy_stone_brick_wall")
    for z in range(3, 54, 7):
        r.set(3, 7, z, "lantern[hanging=true]"); r.set(11, 7, z, "lantern[hanging=true]")
    r.marker(3, 1, 2, "spawn")
    mobs(r, [(11, 1, 10), (11, 1, 26), (3, 1, 38), (11, 1, 50), (3, 1, 52)])
    return r


# =================================================================== elite
@room("elite", "overgrown_arena_01", ["open"])
def overgrown_arena_01(rng):
    r = base(40, 40, rng)
    r.ring(20, 1, 20, 14, 15, "mossy_stone_brick_wall")
    edge_trees(r, 6, rng)
    r.marker(20, 1, 4, "spawn")
    mobs(r, [(20, 1, 26), (14, 1, 20), (26, 1, 20)])
    return r


@room("elite", "drowned_cistern_elite_01", ["water"])
def drowned_cistern_elite_01(rng):
    """가라앉은 저수조: 깊이 2 원형 물 + 가운데 섬(엘리트). 다리는 북쪽 하나뿐 → 헤엄·삼지창으로 다른 방향 접근."""
    r = Room(44, 20, 44)
    r.floor_mix(0, 0, 0, 43, 43, rng, STONE_MIX)
    r.fill(0, 1, 0, 43, 2, 43, "mossy_stone_bricks")
    for x in range(44):
        for z in range(44):
            d = math.hypot(x + 0.5 - 22, z + 0.5 - 22)
            if 6 <= d <= 16:
                r.set(x, 0, z, "mud"); r.set(x, 1, z, "water"); r.set(x, 2, z, "water")
    r.fill(21, 1, 5, 22, 2, 16, "mossy_stone_bricks")      # 다리
    r.fill(20, 3, 5, 20, 3, 16, "mossy_stone_brick_wall")
    r.fill(23, 3, 5, 23, 3, 16, "mossy_stone_brick_wall")
    for (x, z) in ((10, 10), (32, 10), (10, 32), (32, 32)):
        r.fill(x, 1, z, x + 1, 9, z + 1, "mossy_stone_bricks")
        r.set(x, 10, z, "sea_lantern")
    r.disc(22, 2, 22, 5, "chiseled_stone_bricks")
    r.disc(22, 2, 22, 3, "mossy_stone_bricks")
    r.marker(22, 3, 2, "spawn")
    mobs(r, [(22, 3, 22), (19, 3, 24), (25, 3, 24), (10, 1, 22), (34, 1, 22)])
    return r


@room("elite", "canopy_throne_elite_01", ["vertical"])
def canopy_throne_elite_01(rng):
    """수관 왕좌: 거목 가운데 8층 넓은 발판에 엘리트. 긴 계단 1개 + 사다리 2개, 활·쇠뇌는 지상에서 견제."""
    r = base(44, 44, rng, sy=32)
    r.jungle_tree(21, 1, 21, 20, rng, big=True)
    for x in range(12, 32):
        for z in range(12, 32):
            if (x - 21.5) ** 2 + (z - 21.5) ** 2 <= 49 and r.get(x, 8, z) != "minecraft:jungle_log":
                r.set(x, 8, z, "jungle_planks")
    r.ring(21.5, 9, 21.5, 6.3, 7, "jungle_fence")
    stairs_up(r, 21, 7, 8, "south", stair="jungle_stairs", fill="jungle_planks")
    r.fill(21, 8, 15, 22, 8, 15, "jungle_planks")
    carve(r, 21, 9, 13, 22, 9, 16)                         # 계단 입구 울타리 틈
    for (x, z, f, sx) in ((14, 21, "east", 13), (29, 22, "west", 30)):
        r.column(sx, z, 1, 8, "jungle_log")                # 사다리 받침
        ladder(r, x, z, 1, 8, f)
    for (x, z) in ((6, 36), (36, 8), (38, 36)):
        rubble(r, x, z, 3, 1, 2, rng)
    edge_trees(r, 6, rng)
    r.plants(1, 0, 0, 43, 43, rng, 0.05)
    r.marker(22, 1, 3, "spawn")
    mobs(r, [(25, 9, 22), (18, 9, 24), (22, 9, 26), (8, 1, 30), (35, 1, 32)])
    return r


@room("elite", "guard_hall_elite_01", ["narrow"])
def guard_hall_elite_01(rng):
    """경비대 홀: 긴 홀 + 양옆 벽감(피할 곳). 정면 돌파는 방패·창, 벽감 사이를 끊어 가며 전진."""
    r = Room(22, 12, 56)
    r.floor_mix(0, 0, 0, 21, 55, rng, STONE_MIX)
    r.hollow(0, 1, 0, 21, 7, 55, "stone_bricks")
    r.fill(0, 8, 0, 21, 8, 55, "mossy_stone_bricks")
    r.fill(1, 1, 1, 3, 7, 54, "stone_bricks")
    r.fill(18, 1, 1, 20, 7, 54, "stone_bricks")
    for z in range(8, 52, 10):                           # 벽감
        carve(r, 1, 1, z, 3, 4, z + 3)
        if z + 8 < 55:
            carve(r, 18, 1, z + 5, 20, 4, z + 8)
        r.set(2, 5, z + 1, "lantern[hanging=true]")
    for z in range(12, 52, 10):
        r.fill(7, 1, z, 7, 6, z, "chiseled_stone_bricks")
        r.fill(14, 1, z, 14, 6, z, "chiseled_stone_bricks")
    r.fill(8, 1, 52, 13, 1, 54, "mossy_stone_bricks")      # 끝 단상
    r.marker(10, 1, 2, "spawn")
    mobs(r, [(10, 2, 52), (5, 1, 40), (15, 1, 30), (10, 1, 46)])
    return r


# =================================================================== puzzle (모두 [trigger] 램프에 신호가 들어가면 클리어)
@room("puzzle", "vine_knot_01", ["vertical"])
def vine_knot_01(rng):
    """1-A 덩굴 매듭: 높이 7의 나무 발판 3곳에 매듭(이끼 블록+레버). 셋 다 당기면 끝 램프 켜짐.
    발판 사이는 울타리로 막혀 있어 나무마다 덩굴·사다리로 따로 올라가야 함 (특기: 철퇴·윈드 차지로 빠르게)."""
    r = base(44, 40, rng, sy=24)
    xs = [14, 20, 26]
    lever_lock(r, 8, 14, 24, [True, True, True], spacing=6, panel="moss_block",
               panel_fill="jungle_log[axis=x]", casing="jungle_log[axis=x]", roof="jungle_leaves[persistent=true]",
               floor="jungle_planks")
    for i, x in enumerate(xs):
        r.fill(x - 2, 7, 25, x + 2, 7, 28, "jungle_planks")        # 매듭 발판
        for side in (x - 2, x + 2):
            r.fill(side, 8, 25, side, 8, 28, "jungle_fence")        # 옆 발판으로 못 건너게
        r.fill(x - 1, 8, 28, x + 1, 8, 28, "jungle_fence")
        carve(r, x, 8, 28, x, 8, 28)                                 # 나무 쪽 출입구
        r.fill(x, 1, 29, x, 7, 30, "jungle_log")                    # 나무 기둥
        if i == 1:
            ladder(r, x, 31, 1, 7, "south")                          # 가운데만 사다리
        else:
            vine_wall(r, x, 31, x, 1, 7, "north")
        for dx in (-1, 1):
            r.set(x + dx, 9, 25, "vine[north=true]")
    for x in range(13, 33, 5):                                       # 회로 아래 받침 기둥
        r.column(x, 22, 1, 6, "jungle_log")
    sign(r, 20, 10, 25, ["매듭 셋을", "모두 끊어라", "", ""], block="oak_wall_sign[facing=south]")
    edge_trees(r, 4, rng)
    r.plants(1, 0, 0, 43, 39, rng, 0.05)
    r.marker(20, 1, 36, "spawn")
    return r


@room("puzzle", "water_gauge_01", ["water"])
def water_gauge_01(rng):
    """1-B 수위 맞추기: 수로의 수위 기둥 4개 중 꼭대기가 빛나는(바다 랜턴) 기둥 앞 수문 레버만 켜기.
    가짜 레버가 켜져 있으면 안 열림. 수로는 깊이 2 → 서쪽 다리로 돌아가거나 헤엄·삼지창으로 건너기."""
    r = Room(40, 20, 38)
    r.floor_mix(0, 0, 0, 39, 37, rng, MUD_MIX)
    r.fill(0, 1, 0, 39, 2, 37, "mud_bricks")               # 땅 (서는 높이 y=3)
    r.floor_mix(2, 0, 0, 39, 37, rng, (("moss_block", 3), ("grass_block", 3), ("mud", 1)))
    slots = [True, False, True, False]
    rng.shuffle(slots)
    if not any(slots):
        slots[0] = True
    xs = [12 + 4 * i for i in range(4)]
    lever_lock(r, 3, 12, 8, slots, spacing=4, panel="chiseled_stone_bricks", floor="mud_bricks")
    for x, ok in zip(xs, slots):
        r.set(x, 5, 9, "iron_bars")
    water_area(r, 0, 16, 39, 22, depth=2)                    # 수로
    for x, ok in zip(xs, slots):                              # 수위 기둥 (레버와 같은 x)
        r.column(x, 19, 1, 3, "mossy_stone_bricks")
        r.set(x, 4, 19, "sea_lantern" if ok else "cracked_stone_bricks")
    r.fill(2, 2, 16, 4, 2, 22, "mangrove_planks")           # 서쪽 다리 (돌아가는 길)
    sign(r, 18, 4, 9, ["빛나는 수위 앞", "수문만 연다", "", ""])
    r.plants(3, 0, 0, 39, 37, rng, 0.04)
    for (x, z) in ((2, 30), (36, 30), (36, 4)):
        r.jungle_tree(x, 3, z, 8, rng)
    r.marker(20, 3, 32, "spawn")
    return r


@room("puzzle", "trap_corridor_01", ["narrow"])
def trap_corridor_01(rng):
    """1-C 함정 복도: 이끼 타일 = 안전, 금 간 타일 압력판 = 옆벽 발사기 화살, 마그마 = 뜨거움.
    안전한 이끼 길(2칸 폭)을 따라 끝까지 가서 레버. 방패로 화살을 막으며 질러갈 수도 있음."""
    r = Room(15, 10, 50)
    r.floor_mix(0, 0, 0, 14, 49, rng, STONE_MIX)
    r.hollow(4, 1, 0, 10, 5, 49, "stone_bricks")
    r.fill(4, 6, 0, 10, 6, 49, "mossy_stone_bricks")
    for z in range(0, 50):                                  # 바깥 벽 (발사기 뒤 막기)
        for x in (3, 11):
            r.column(x, z, 1, 5, "mossy_stone_bricks")
    c = 6
    for z in range(6, 42):
        if z % 2 == 0:
            c = max(5, min(8, c + rng.choice([-1, 0, 1])))
        for x in range(5, 10):
            safe = x in (c, c + 1)
            if safe:
                r.set(x, 0, z, "moss_block")
            elif x in (5, 9):
                r.set(x, 0, z, "cracked_stone_bricks")
                r.set(x, 1, z, "stone_pressure_plate[powered=false]")
                wx, f = (4, "east") if x == 5 else (10, "west")
                r.container(wx, 1, z, f"dispenser[facing={f},triggered=false]", [("arrow", 16)])
            else:
                r.set(x, 0, z, "magma_block")
    r.fill(5, 0, 1, 9, 0, 5, "mossy_stone_bricks")
    r.fill(5, 0, 42, 9, 0, 48, "mossy_stone_bricks")
    for z in range(2, 48, 6):
        r.set(5, 5, z, "lantern[hanging=true]"); r.set(9, 5, z, "lantern[hanging=true]")
    lever_on_trigger(r, 7, 1, 48, "north")
    sign(r, 6, 2, 1, ["이끼만 밟아라", "", "", ""], block="oak_wall_sign[facing=south]")
    r.set(6, 2, 0, "stone_bricks")
    r.marker(7, 1, 2, "spawn")
    return r


@room("puzzle", "moss_glyph_lever_01")
def moss_glyph_lever_01(rng):
    """2 레버 문양 (사원): 뒷벽 벽화의 이끼 칸과 같은 자리 레버만 켜기. 레버 5개 중 정답 2~3개."""
    r = Room(30, 12, 30)
    r.floor_mix(0, 0, 0, 29, 29, rng, STONE_MIX)
    r.hollow(0, 1, 0, 29, 7, 29, "stone_bricks")
    while True:
        slots = [rng.random() < 0.5 for _ in range(5)]
        if 2 <= sum(slots) <= 3:
            break
    xs = [9 + 2 * i for i in range(5)]
    lever_lock(r, 1, 9, 6, slots, spacing=2, panel="chiseled_stone_bricks")
    for x, ok in zip(xs, slots):                             # 벽화 (반대편 벽, 같은 x)
        for y in (2, 3, 4):
            r.set(x, y, 28, "mossy_stone_bricks" if ok else "stone_bricks")
        if ok:
            r.set(x, 3, 27, "glow_lichen[north=true]")
        r.set(x, 5, 28, "chiseled_stone_bricks")
    for x in range(8, 21):
        if x not in xs:
            r.set(x, 3, 28, "cracked_stone_bricks")
    sign(r, 14, 3, 7, ["벽화의 이끼를", "따라 당겨라", "", ""])
    for (x, z) in ((3, 12), (25, 12), (3, 22), (25, 22)):
        r.fill(x, 1, z, x + 1, 7, z + 1, "chiseled_stone_bricks")
    r.fill(0, 8, 0, 29, 8, 29, "mossy_stone_bricks")
    carve(r, 9, 8, 12, 20, 8, 22)                           # 천장 구멍 (빛)
    r.marker(14, 1, 18, "spawn")
    return r


# =================================================================== shop
@room("shop", "wandering_trader_hut_01")
def wandering_trader_hut_01(rng):
    r = base(26, 26, rng)
    edge_trees(r, 5, rng)
    r.fill(9, 1, 9, 16, 1, 16, "jungle_planks")
    r.set(12, 2, 12, "barrel[facing=up]")
    r.plants(1, 0, 0, 25, 25, rng)
    r.marker(13, 1, 3, "spawn")
    return r


@room("shop", "mossy_stall_shop_02")
def mossy_stall_shop_02(rng):
    """이끼 노점: 초록 차양 + 통 진열대. 상인 자리 = 진열대 뒤(13,2,16)."""
    r = base(26, 26, rng)
    r.fill(8, 1, 12, 18, 1, 18, "spruce_planks")
    for (x, z) in ((8, 12), (18, 12), (8, 18), (18, 18)):
        r.column(x, z, 2, 5, "stripped_jungle_log")
    for x in range(8, 19):
        for z in range(11, 20):
            r.set(x, 6, z, "lime_wool" if (x + z) % 2 else "green_wool")
    for x in range(9, 18):
        r.set(x, 2, 13, "barrel[facing=up]" if x % 3 else "composter")
    r.set(10, 3, 13, "lantern"); r.set(16, 3, 13, "lantern")
    r.set(13, 2, 17, "spruce_stairs[facing=north]")
    r.set(9, 2, 17, "decorated_pot"); r.set(17, 2, 17, "decorated_pot")
    edge_trees(r, 5, rng)
    r.plants(1, 0, 0, 25, 25, rng, 0.08)
    r.marker(13, 1, 4, "spawn")
    return r


@room("shop", "temple_merchant_shop_03")
def temple_merchant_shop_03(rng):
    """사원 상인: 무너진 사당 안 돌 계산대. 상인 자리 = 계산대 뒤(14,1,20)."""
    r = Room(28, 14, 28)
    r.floor_mix(0, 0, 0, 27, 27, rng, STONE_MIX)
    r.hollow(4, 1, 6, 23, 6, 25, "mossy_stone_bricks")
    carve(r, 12, 1, 6, 15, 4, 6)                            # 입구
    for x in range(4, 24):
        for z in range(6, 26):
            if rng.random() < 0.55:
                r.set(x, 7, z, "mossy_stone_bricks")
    r.fill(8, 1, 17, 19, 1, 17, "polished_andesite")
    r.fill(8, 2, 17, 19, 2, 17, "polished_andesite_slab[type=bottom]")
    for x in (9, 12, 15, 18):
        r.set(x, 3, 17, "candle[candles=3,lit=true]")
    for x in (6, 21):
        r.set(x, 1, 23, "chest[facing=north]")
        r.set(x, 1, 22, "decorated_pot")
    r.set(14, 1, 23, "lectern[facing=north]")
    vine_wall(r, 5, 7, 11, 3, 6, "north")
    vine_wall(r, 16, 7, 22, 3, 6, "north")
    edge_trees(r, 4, rng)
    r.marker(14, 1, 2, "spawn")
    return r


# =================================================================== rest
@room("rest", "quiet_spring_01")
def quiet_spring_01(rng):
    r = base(26, 26, rng)
    edge_trees(r, 5, rng)
    r.disc(13, 0, 13, 5, "mud")                          # 샘 바닥 (허공 위 물 금지)
    r.disc(13, 1, 13, 4, "water")
    r.ring(13, 1, 13, 4, 5.2, "mossy_cobblestone")
    r.plants(1, 0, 0, 25, 25, rng)
    r.marker(13, 1, 3, "spawn")
    return r


@room("rest", "campfire_glade_rest_02")
def campfire_glade_rest_02(rng):
    """모닥불 숲속 빈터: 모닥불 + 통나무 의자 + 꽃."""
    r = base(26, 26, rng)
    r.disc(13, 0, 14, 4, "coarse_dirt")
    r.set(13, 1, 14, "campfire[lit=true]")
    for (x, z, a) in ((10, 11, "x"), (10, 17, "x"), (9, 13, "z"), (17, 13, "z")):
        fallen_log(r, x, z, 4 if a == "x" else 3, a)
    for _ in range(20):
        x, z = rng.randint(2, 23), rng.randint(2, 23)
        if r.get(x, 1, z) is None and (x - 13) ** 2 + (z - 14) ** 2 > 30:
            r.set(x, 1, z, rng.choice(["poppy", "blue_orchid", "oxeye_daisy", "azure_bluet"]))
    r.set(20, 1, 20, "sweet_berry_bush[age=3]")
    edge_trees(r, 6, rng)
    r.marker(13, 1, 3, "spawn")
    return r


# =================================================================== event
@room("event", "trapped_explorer_01")
def trapped_explorer_01(rng):
    r = base(26, 26, rng)
    edge_trees(r, 5, rng)
    r.fill(11, 1, 11, 14, 4, 14, "vine[north=true]")
    r.plants(1, 0, 0, 25, 25, rng)
    r.marker(13, 1, 3, "spawn")
    return r


@room("event", "vine_shrine_event_02")
def vine_shrine_event_02(rng):
    """덩굴 사당: 제단의 레버를 당기면(=[trigger]) 이벤트 진행. 보상/저주 선택은 플러그인 몫."""
    r = base(26, 26, rng)
    r.disc(13, 0, 15, 5, "mossy_stone_bricks")
    r.fill(11, 1, 17, 15, 1, 19, "mossy_stone_bricks")
    r.fill(12, 2, 18, 14, 2, 18, "chiseled_stone_bricks")
    lever_on_trigger(r, 13, 3, 18, "north")
    r.set(13, 4, 18, "moss_carpet")
    for (x, z) in ((9, 13), (17, 13), (9, 19), (17, 19)):
        r.column(x, z, 1, 4, "mossy_stone_brick_wall")
        r.set(x, 5, z, "torch")
    vine_wall(r, 11, 20, 15, 1, 1, "north")
    edge_trees(r, 5, rng)
    r.plants(1, 0, 0, 25, 25, rng, 0.06)
    r.marker(13, 1, 4, "spawn")
    return r


# =================================================================== miniboss / boss (BOSS.md 조건)
def _boss_arena(rng, pool):
    R, S = 17, 40
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
    return r


@room("miniboss", "moss_gate_small_01")
def moss_gate_small_01(rng):
    return _boss_arena(rng, "miniboss")


@room("boss", "sunken_floodgate_01")
def sunken_floodgate_01(rng):
    return _boss_arena(rng, "boss")


# =================================================================== 실행
if __name__ == "__main__":
    made, errors = [], []
    for pool, name, tags, weight, fn in ROOMS:
        try:
            r = fn(rng_for(SEED, name))
            made.append(r.save(OUT, pool, name, tags=tags, weight=weight))
        except ValueError as e:
            errors.append(str(e))
    for p in made:
        print(check(p))
    counts = {}
    for pool, *_ in ROOMS:
        counts[pool] = counts.get(pool, 0) + 1
    print(f"{len(made)}개 생성 → {OUT}  " + " · ".join(f"{k} {v}" for k, v in counts.items()))
    if errors:
        print("\n검증 실패:\n  " + "\n  ".join(errors))
        sys.exit(1)
