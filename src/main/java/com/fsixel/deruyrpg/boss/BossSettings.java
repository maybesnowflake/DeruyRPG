package com.fsixel.deruyrpg.boss;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

/** config.yml 의 boss.moss-warden 섹션. */
public final class BossSettings {

    /** 1페이즈 최대 체력 */
    public double health = 200.0;
    /** 방어력 (바닐라 ARMOR, 최대 30) — 평타·투사체·일반 스킬 피해를 줄임. 관통(음파)·마법 피해는 무시 */
    public double armor = 10.0;
    /** 방어 강도 (바닐라 ARMOR_TOUGHNESS, 최대 20) — 큰 한 방 피해도 덜 뚫리게 함 */
    public double armorToughness = 4.0;
    /** 보스가 주는 모든 피해 배율 */
    public double damageMultiplier = 1.0;
    /** 2페이즈 최대 체력 = health × 이 값 */
    public double phase2HealthMultiplier = 1.5;
    /** 미니보스(1페이즈 전용) 능력치 배율 — 체력·피해·방어력 */
    public double miniStatMultiplier = 0.75;
    /** 이 설정이 미니보스용 복사본인지 (각성 없이 1페이즈에서 처치) */
    public boolean mini = false;
    /** 보스방 소환 좌표 (없으면 null) */
    public @Nullable Location spawn;

    public void load(@Nullable ConfigurationSection sec) {
        if (sec == null) return;
        health = Math.max(1.0, sec.getDouble("health", health));
        armor = Math.max(0.0, Math.min(30.0, sec.getDouble("armor", armor)));
        armorToughness = Math.max(0.0, Math.min(20.0, sec.getDouble("armor-toughness", armorToughness)));
        damageMultiplier = Math.max(0.0, sec.getDouble("damage-multiplier", damageMultiplier));
        phase2HealthMultiplier = Math.max(0.1, sec.getDouble("phase2-health-multiplier", phase2HealthMultiplier));
        miniStatMultiplier = Math.max(0.05, sec.getDouble("mini-stat-multiplier", miniStatMultiplier));
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

    /** 미니보스용 복사본: 체력·피해·방어력 × miniStatMultiplier. */
    public BossSettings mini() {
        BossSettings m = new BossSettings();
        double k = miniStatMultiplier;
        m.health = health * k;
        m.damageMultiplier = damageMultiplier * k;
        m.armor = armor * k;
        m.armorToughness = armorToughness * k;
        m.phase2HealthMultiplier = phase2HealthMultiplier;
        m.miniStatMultiplier = k;
        m.spawn = spawn;
        m.mini = true;
        return m;
    }

    public void save(ConfigurationSection sec) {
        sec.set("health", health);
        sec.set("armor", armor);
        sec.set("armor-toughness", armorToughness);
        sec.set("damage-multiplier", damageMultiplier);
        sec.set("mini-stat-multiplier", miniStatMultiplier);
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
