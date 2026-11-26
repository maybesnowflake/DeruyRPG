package com.fsixel.deruyrpg.core;

import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 피버(궁극기) 게이지.
 * - 무기로 때리면 gainOnHit, 맞으면 gainOnHurt 만큼 참 (0~100)
 * - 가득 차면 소리 + 무기 반짝임(글린트)
 * - F 누르면 코어가 consume() → 궁극기 호출 → 피버 상태 시작
 */
public final class FeverManager {

    public static final double MAX = 100.0;

    private final Map<UUID, Double> gauge = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> feverUntil = new ConcurrentHashMap<>();

    private double gainOnHit = 5.0;
    private double gainOnHurt = 2.0;
    private double cooldownMultiplier = 1.0;

    public void load(ConfigurationSection sec) {
        if (sec == null) return;
        gainOnHit = sec.getDouble("gain-on-hit", gainOnHit);
        gainOnHurt = sec.getDouble("gain-on-hurt", gainOnHurt);
        cooldownMultiplier = sec.getDouble("cooldown-multiplier", cooldownMultiplier);
    }

    public double get(Player p) {
        return gauge.getOrDefault(p.getUniqueId(), 0.0);
    }

    public boolean isFull(Player p) {
        return get(p) >= MAX;
    }

    public void add(Player p, double amount) {
        if (isFever(p)) return; // 피버 중엔 안 참
        double before = get(p);
        double after = Math.min(MAX, before + amount);
        gauge.put(p.getUniqueId(), after);
        if (before < MAX && after >= MAX) {
            p.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.6f);
            p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0.1);
        }
    }

    public void onHit(Player p) { add(p, gainOnHit); }

    public void onHurt(Player p) { add(p, gainOnHurt); }

    public void set(Player p, double value) {
        gauge.put(p.getUniqueId(), Math.max(0, Math.min(MAX, value)));
    }

    /** 게이지를 비우고 피버 시작. */
    public void consume(Player p, int durationTicks) {
        gauge.put(p.getUniqueId(), 0.0);
        feverUntil.put(p.getUniqueId(), Bukkit.getCurrentTick() + durationTicks);
    }

    public boolean isFever(Player p) {
        Integer end = feverUntil.get(p.getUniqueId());
        return end != null && end > Bukkit.getCurrentTick();
    }

    public int feverRemaining(Player p) {
        Integer end = feverUntil.get(p.getUniqueId());
        return end == null ? 0 : Math.max(0, end - Bukkit.getCurrentTick());
    }

    public int scaleCooldown(int ticks) {
        return (int) Math.round(ticks * cooldownMultiplier);
    }

    public void clear(Player p) {
        gauge.remove(p.getUniqueId());
        feverUntil.remove(p.getUniqueId());
    }
}
