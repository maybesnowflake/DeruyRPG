package com.fsixel.deruyrpg.dungeon;

import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 라운드 한 칸. dungeon.yml 의 chapters.&lt;챕터&gt;.rounds.&lt;id&gt;.
 *
 * @param waves   전투 라운드: 웨이브 목록, 웨이브마다 "ZOMBIE:3" 형식 항목들. 앞 웨이브 전멸 시 다음 웨이브
 * @param points  몹 소환 지점 (없으면 spawn 주변 6칸)
 * @param trigger 비전투 라운드 클리어 블록 (레버·압력판 등이 켜지면 클리어). 없으면 /dg next
 * @param pool    spawn 이 비어 있으면 이 풀(rooms/&lt;pool&gt;/)에서 방 템플릿을 매 판 무작위로 골라 배치
 * @param tags    방 고를 때 우선할 태그 (예: vertical, water, narrow, open)
 */
public record RoundDef(
        String id,
        int stage,
        RoundType type,
        String name,
        List<String> next,
        List<List<String>> waves,
        @Nullable Location spawn,
        List<Location> points,
        @Nullable Location trigger,
        @Nullable Location bossSpawn,
        String pool,
        List<String> tags
) {
    /** 고정 위치(spawn)가 없으면 템플릿 조립 */
    public boolean generated() {
        return spawn == null;
    }

    public RoundDef at(Location spawn, List<Location> points, @Nullable Location trigger, @Nullable Location bossSpawn) {
        return new RoundDef(id, stage, type, name, next, waves, spawn, points, trigger, bossSpawn, pool, tags);
    }

    public boolean last() {
        return next.isEmpty();
    }
}
