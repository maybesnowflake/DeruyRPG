package com.fsixel.deruyrpg.dungeon;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * dungeon.yml 의 balance: 섹션 — 코드 수정 없이 조정하는 밸런스 수치. /dg reload 로 반영.
 * runs.csv(/dg stats) 결과를 보고 조정: 목표 챕터 15분, 보스 6분.
 */
final class Balance {
    double mobScalePerPlayer = 0.15;
    double bossScalePerPlayer = 0.15;
    int bossBasePlayers = 4;
    double eventChance = 0.15;
    int emeraldPerStage = 1;
    int eliteClearBonus = 3;
    int minibossClearBonus = 10;
    int eventRewardPerStage = 4;
    int relicPrice = 15;
    final Map<String, Integer> prices = new HashMap<>(Map.of(
            "apple", 6, "heal", 4, "arrows", 2, "repair", 5, "swift", 5, "strength", 8,
            "life", 20, "life_black", 25));

    /** 항상 기본값에서 시작 → 키·섹션을 지우고 reload 해도 재시작과 같은 결과. */
    void load(@Nullable ConfigurationSection b) {
        Balance d = new Balance();
        mobScalePerPlayer = d.mobScalePerPlayer;
        bossScalePerPlayer = d.bossScalePerPlayer;
        bossBasePlayers = d.bossBasePlayers;
        eventChance = d.eventChance;
        emeraldPerStage = d.emeraldPerStage;
        eliteClearBonus = d.eliteClearBonus;
        minibossClearBonus = d.minibossClearBonus;
        eventRewardPerStage = d.eventRewardPerStage;
        relicPrice = d.relicPrice;
        prices.clear();
        prices.putAll(d.prices);
        if (b == null) return;
        mobScalePerPlayer = Math.max(0, b.getDouble("mob-scale-per-player", mobScalePerPlayer));
        bossScalePerPlayer = Math.max(0, b.getDouble("boss-scale-per-player", bossScalePerPlayer));
        bossBasePlayers = Math.max(1, b.getInt("boss-base-players", bossBasePlayers));
        eventChance = Math.max(0, Math.min(1, b.getDouble("event-chance", eventChance)));
        emeraldPerStage = Math.max(0, b.getInt("emerald-per-stage", emeraldPerStage));
        eliteClearBonus = Math.max(0, b.getInt("elite-clear-bonus", eliteClearBonus));
        minibossClearBonus = Math.max(0, b.getInt("miniboss-clear-bonus", minibossClearBonus));
        eventRewardPerStage = Math.max(0, b.getInt("event-reward-per-stage", eventRewardPerStage));
        relicPrice = Math.max(0, b.getInt("relic-price", relicPrice));
        ConfigurationSection p = b.getConfigurationSection("prices");
        if (p != null) for (String k : p.getKeys(false)) prices.put(k.toLowerCase(Locale.ROOT), Math.max(0, p.getInt(k)));
    }

    int price(String key) {
        return prices.getOrDefault(key, 0);
    }
}
