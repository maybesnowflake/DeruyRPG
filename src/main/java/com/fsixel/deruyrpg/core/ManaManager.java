package com.fsixel.deruyrpg.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 마나 (0.0 ~ 1.0).
 * 실제 경험치는 건드리지 않고, 마나 무기를 든 동안만 클라이언트 경험치 바에 마나를 보여줌
 * (sendExperienceChange). 무기를 놓으면 원래 경험치 표시로 복구.
 */
public final class ManaManager {

    private final Map<UUID, Float> mana = new ConcurrentHashMap<>();

    /** 초당 회복량 */
    private float regenPerSecond = 0.05f;

    public void load(ConfigurationSection sec) {
        if (sec == null) return;
        regenPerSecond = (float) sec.getDouble("regen-per-second", regenPerSecond);
    }

    public float get(Player p) {
        return mana.getOrDefault(p.getUniqueId(), 1.0f);
    }

    public void set(Player p, float value) {
        mana.put(p.getUniqueId(), Math.max(0f, Math.min(1f, value)));
    }

    public void add(Player p, float amount) {
        set(p, get(p) + amount);
    }

    public boolean tryConsume(Player p, float amount) {
        float cur = get(p);
        if (cur < amount) {
            p.sendActionBar(net.kyori.adventure.text.Component.text("마나 부족",
                    net.kyori.adventure.text.format.NamedTextColor.AQUA));
            return false;
        }
        set(p, cur - amount);
        return true;
    }

    /** 1초(20틱)마다 코어가 호출. */
    public void regenTick(Player p) {
        add(p, regenPerSecond);
    }

    /** 마나 무기를 든 동안 5틱마다 호출. */
    public void display(Player p) {
        p.sendExperienceChange(get(p), Math.round(get(p) * 100));
    }

    /** 원래 경험치 표시 복구. */
    public void restoreDisplay(Player p) {
        p.sendExperienceChange(p.getExp(), p.getLevel());
    }

    public void clear(Player p) {
        mana.remove(p.getUniqueId());
    }
}
