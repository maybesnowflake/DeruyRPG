package com.fsixel.deruyrpg.dungeon;

import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 라운드 한 칸. dungeon.yml 의 chapters.&lt;챕터&gt;.rounds.&lt;id&gt;.
 *
 * @param waves   전투 라운드: 웨이브 목록, 웨이브마다 "ZOMBIE:3" 형식 항목들. 앞 웨이브 전멸 시 다음 웨이브
 * @param points  몹 소환 지점 (없으면 spawn 주변 6칸)
 * @param trigger 비전투 라운드 클리어 블록 (레버·압력판 등이 켜지면 클리어). 없으면 /rpg dungeon next
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
        @Nullable Location bossSpawn
) {
    public boolean last() {
        return next.isEmpty();
    }
}
