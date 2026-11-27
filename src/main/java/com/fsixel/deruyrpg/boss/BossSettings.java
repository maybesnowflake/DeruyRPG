package com.fsixel.deruyrpg.boss;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

/** config.yml 의 boss.moss-warden 섹션. */
public final class BossSettings {

    /** 1페이즈 최대 체력 */
    public double health = 400.0;
    /** 보스가 주는 모든 피해 배율 */
    public double damageMultiplier = 1.0;
    /** 2페이즈 최대 체력 = health × 이 값 */
    public double phase2HealthMultiplier = 1.5;
    /** 보스방 소환 좌표 (없으면 null) */
    public @Nullable Location spawn;

    public void load(@Nullable ConfigurationSection sec) {
        if (sec == null) return;
        health = Math.max(1.0, sec.getDouble("health", health));
        damageMultiplier = Math.max(0.0, sec.getDouble("damage-multiplier", damageMultiplier));
        phase2HealthMultiplier = Math.max(0.1, sec.getDouble("phase2-health-multiplier", phase2HealthMultiplier));
        spawn = null;
        ConfigurationSection sp = sec.getConfigurationSection("spawn");
        if (sp != null) {
            String wn = sp.getString("world");
            World w = wn == null ? null : Bukkit.getWorld(wn);
            if (w != null) {
                spawn = new Location(w, sp.getDouble("x"), sp.getDouble("y"), sp.getDouble("z"),
                        (float) sp.getDouble("yaw"), 0f);
            }
        }
    }

    public void save(ConfigurationSection sec) {
        sec.set("health", health);
        sec.set("damage-multiplier", damageMultiplier);
        sec.set("phase2-health-multiplier", phase2HealthMultiplier);
        if (spawn == null || spawn.getWorld() == null) {
            sec.set("spawn", null);
        } else {
            ConfigurationSection sp = sec.createSection("spawn");
            sp.set("world", spawn.getWorld().getName());
            sp.set("x", spawn.getX());
            sp.set("y", spawn.getY());
            sp.set("z", spawn.getZ());
            sp.set("yaw", (double) spawn.getYaw());
        }
    }
}
