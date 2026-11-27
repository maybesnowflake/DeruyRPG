"""퍼즐방 레드스톤 회로 오프라인 검사 (인게임 테스트 전 1차 확인).

    python check_circuits.py

같은 높이의 단순 회로만 다루는 간이 시뮬레이터: 레버·벽 레드스톤 횃불·가루·고체 블록·[trigger] 램프.
1) 저장된 초기 상태(가루 세기·횃불 켜짐)가 이미 안정 상태인지 — 배치 때 물리 업데이트가 없으므로 중요
2) 레버 조합 전부에 대해 "램프 켜짐 ⇔ 정답 조합" 인지
"""
import itertools
import sys

import jungle_rooms
from jungle_rooms import ROOMS, SEED
from jungle_kit import rng_for

DIRS = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0)}
OPP = {"north": "south", "south": "north", "east": "west", "west": "east"}
SOFT = ("air", "water", "glass", "leaves", "sign", "fence", "wall", "carpet", "vine", "torch", "lever", "wire",
        "lantern", "bars", "plate", "slab", "stairs", "ladder")


def short(b):
    return b[0].split(":")[-1] if b else None


def solid(b):
    n = short(b)
    return n is not None and not any(k in n for k in SOFT)


def attached(pos, props, kind):
    """레버(벽)·벽 횃불이 붙은 블록 좌표."""
    f = props.get("facing")
    if kind == "lever" and props.get("face") != "wall":
        dy = -1 if props.get("face") == "floor" else 1
        return (pos[0], pos[1] + dy, pos[2])
    dx, _, dz = DIRS[OPP[f]]
    return (pos[0] + dx, pos[1], pos[2] + dz)


def simulate(blocks, trig, levers_on):
    B = dict(blocks)
    B[trig] = ("minecraft:redstone_lamp", {})
    levers = {p: (b[1]) for p, b in B.items() if short(b) == "lever"}
    torches = {p: b[1] for p, b in B.items() if short(b) == "redstone_wall_torch"}
    wires = {p: b[1] for p, b in B.items() if short(b) == "redstone_wire"}
    lever_on = {p: (p in levers_on) for p in levers}
    lit = {p: props.get("lit") == "true" for p, props in torches.items()}
    power = {p: int(props.get("power", 0)) for p, props in wires.items()}
    init = (dict(lit), dict(power))

    def nb(p, d):
        dx, dy, dz = DIRS[d]
        return (p[0] + dx, p[1] + dy, p[2] + dz)

    def strong(p):
        return any(lever_on[l] and attached(l, levers[l], "lever") == p for l in levers)

    def weak(p):  # 가루가 이 블록을 가리킴
        for d in DIRS:
            w = nb(p, d)
            if w in wires and power[w] > 0 and wires[w].get(OPP[d]) == "side":
                return True
        return False

    for _ in range(200):
        changed = False
        for t, props in torches.items():
            a = attached(t, props, "torch")
            v = not (strong(a) or weak(a))
            if lit[t] != v:
                lit[t], changed = v, True
        for w in wires:
            src = 0
            for d in DIRS:
                n = nb(w, d)
                if n in torches and lit[n] and attached(n, torches[n], "torch") != w:
                    src = 15
                elif n in levers and lever_on[n]:
                    src = 15
                elif solid(B.get(n)) and strong(n):
                    src = 15
                elif n in wires:
                    src = max(src, power[n] - 1)
            if power[w] != src:
                power[w], changed = src, True
        if not changed:
            break
    lamp = weak(trig) or strong(trig) or any(
        (n in torches and lit[n]) or (n in levers and lever_on[n]) for n in (nb(trig, d) for d in DIRS))
    stable_init = (lit, power) == init if not levers_on else None
    return lamp, stable_init


def main():
    bad = 0
    for pool, name, tags, weight, fn in ROOMS:
        r = fn(rng_for(SEED, name))
        trig = [p for k, p in r.marker_pos if k == "trigger"]
        if not trig:
            continue
        r.shape_redstone()
        lamp0, stable = simulate(r.blocks, trig[0], set())
        levers = sorted(p for p, b in r.blocks.items() if short(b) == "lever")
        wins = [c for c in itertools.product([0, 1], repeat=len(levers))
                if simulate(r.blocks, trig[0], {l for l, on in zip(levers, c) if on})[0]]
        ok = stable and not lamp0 and len(wins) == 1
        bad += not ok
        print(f"{'OK ' if ok else 'BAD'} {pool}/{name}: 레버 {len(levers)}개, 초기 안정={stable}, 초기 램프={lamp0}, "
              f"정답 조합 {len(wins)}개 {wins}")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
