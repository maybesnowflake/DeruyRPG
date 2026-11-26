package com.fsixel.deruyrpg.core;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 마나. 기본 최대치 1.0 (=100%).
 * 실제 경험치는 건드리지 않고, 마나 무기를 든 동안만 클라이언트 경험치 바에 마나를 보여줌
 * (sendExperienceChange: 바 = 현재/최대, 레벨 숫자 = 현재 마나 %). 무기를 놓으면 원래 표시로 복구.
 *
 * boost(): 일정 시간 최대 마나·회복 배율 증가 (예: 낚싯대 피버 최대 300%, 회복 5배).
 * 부스트가 끝나면 초과분은 최대치(100%)로 잘림.
 */
public final class ManaManager {

    private record Boost(float maxMult, float regenMult, int until) {}

    private final Map<UUID, Float> mana = new ConcurrentHashMap<>();
    private final Map<UUID, Boost> boosts = new ConcurrentHashMap<>();

    /** 초당 회복량 */
    private float regenPerSecond = 0.05f;

    public void load(ConfigurationSection sec) {
        if (sec == null) return;
        regenPerSecond = (float) sec.getDouble("regen-per-second", regenPerSecond);
    }

    private Boost boostOf(Player p) {
        Boost b = boosts.get(p.getUniqueId());
        if (b == null) return null;
        if (b.until() <= Bukkit.getCurrentTick()) {
            boosts.remove(p.getUniqueId());
            return null;
        }
        return b;
    }

    /** ticks 동안 최대 마나 ×maxMult, 회복 ×regenMult. 시작 시 최대치까지 채움. */
    public void boost(Player p, float maxMult, float regenMult, int ticks) {
        boosts.put(p.getUniqueId(), new Boost(maxMult, regenMult, Bukkit.getCurrentTick() + ticks));
        set(p, max(p));
    }

    public void clearBoost(Player p) {
        boosts.remove(p.getUniqueId());
        set(p, get(p));
    }

    /** 현재 최대 마나 (기본 1.0). */
    public float max(Player p) {
        Boost b = boostOf(p);
        return b == null ? 1.0f : b.maxMult();
    }

    public float get(Player p) {
        return Math.min(mana.getOrDefault(p.getUniqueId(), 1.0f), max(p));
    }

    public void set(Player p, float value) {
        mana.put(p.getUniqueId(), Math.max(0f, Math.min(max(p), value)));
    }

    public void add(Player p, float amount) {
        set(p, get(p) + amount);
    }

    /** 쓸 수 있는지만 확인 (소모 안 함). 스킬 조건·쿨다운을 다 통과한 뒤 tryConsume 할 것. */
    public boolean has(Player p, float amount) {
        return get(p) >= amount;
    }

    public boolean tryConsume(Player p, float amount) {
        float cur = get(p);
        if (cur < amount) {
            HudTask.flash(p, net.kyori.adventure.text.Component.text("마나 부족",
                    net.kyori.adventure.text.format.NamedTextColor.AQUA), 20);
            return false;
        }
        set(p, cur - amount);
        return true;
    }

    /** 1초(20틱)마다 코어가 호출. */
    public void regenTick(Player p) {
        Boost b = boostOf(p);
        add(p, regenPerSecond * (b == null ? 1f : b.regenMult()));
    }

    /** 마나 무기를 든 동안 5틱마다 호출. */
    public void display(Player p) {
        float cur = get(p);
        p.sendExperienceChange(Math.min(1f, cur / max(p)), Math.round(cur * 100));
    }

    /** 원래 경험치 표시 복구. */
    public void restoreDisplay(Player p) {
        p.sendExperienceChange(p.getExp(), p.getLevel());
    }

    public void clear(Player p) {
        mana.remove(p.getUniqueId());
        boosts.remove(p.getUniqueId());
    }
}
