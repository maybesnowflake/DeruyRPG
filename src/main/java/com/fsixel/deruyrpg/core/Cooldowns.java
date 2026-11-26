package com.fsixel.deruyrpg.core;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 플레이어 × 무기 × 슬롯 쿨다운. 서버 틱 기준. */
public final class Cooldowns {

    private final Map<UUID, Map<String, Integer>> until = new ConcurrentHashMap<>();

    private static String key(String kitId, String slot) {
        return kitId + ":" + slot;
    }

    /** 남은 틱 (0 이면 사용 가능). */
    public int remaining(Player player, String kitId, String slot) {
        Map<String, Integer> map = until.get(player.getUniqueId());
        if (map == null) return 0;
        Integer end = map.get(key(kitId, slot));
        if (end == null) return 0;
        return Math.max(0, end - Bukkit.getCurrentTick());
    }

    public boolean tryUse(Player player, String kitId, String slot, int ticks) {
        if (remaining(player, kitId, slot) > 0) return false;
        set(player, kitId, slot, ticks);
        return true;
    }

    public void set(Player player, String kitId, String slot, int ticks) {
        until.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .put(key(kitId, slot), Bukkit.getCurrentTick() + Math.max(0, ticks));
    }

    public void reset(Player player, String kitId, String slot) {
        Map<String, Integer> map = until.get(player.getUniqueId());
        if (map != null) map.remove(key(kitId, slot));
    }

    public void clear(Player player) {
        until.remove(player.getUniqueId());
    }
}
