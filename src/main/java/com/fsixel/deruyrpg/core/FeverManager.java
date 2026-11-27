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
 * - 무기로 때리면 gainOnHit, 맞으면 gainOnHurt 만큼 참 (0~최대치, config fever.max)
 * - 가득 차면 소리 + 무기 반짝임(글린트)
 * - F 누르면 코어가 consume() → 궁극기 호출 → 피버 상태 시작
 */
public final class FeverManager {


    private final Map<UUID, Double> gauge = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> feverUntil = new ConcurrentHashMap<>();
    /** 피버를 시작한 무기 id — 피버 효과는 이 무기에만 적용 */
    private final Map<UUID, String> feverKit = new ConcurrentHashMap<>();
    /** 스킬 피해로 게이지 얻은 마지막 틱 (스킬 다단히트·광역·도트로 게이지가 폭주하지 않게) */
    private final Map<UUID, Integer> lastSkillGain = new ConcurrentHashMap<>();

    /** 게이지 최대치 (가득 차야 F 사용 가능) */
    private double max = 100.0;
    private double gainOnHit = 5.0;
    private double gainOnHurt = 2.0;
    /** 스킬 피해 1회당 증가량 */
    private double gainOnSkillHit = 5.0;
    /** 스킬 피해로 게이지가 오르는 최소 간격(틱). 0 = 제한 없음(맞을 때마다) */
    private int skillGainInterval = 0;
    private double cooldownMultiplier = 1.0;

    public void load(ConfigurationSection sec) {
        if (sec == null) return;
        gainOnHit = sec.getDouble("gain-on-hit", gainOnHit);
        gainOnHurt = sec.getDouble("gain-on-hurt", gainOnHurt);
        max = Math.max(1.0, sec.getDouble("max", max));
        gainOnSkillHit = sec.getDouble("gain-on-skill-hit", gainOnSkillHit);
        skillGainInterval = Math.max(0, sec.getInt("skill-gain-interval-ticks", skillGainInterval));
        cooldownMultiplier = sec.getDouble("cooldown-multiplier", cooldownMultiplier);
    }

    public double get(Player p) {
        return Math.min(max, gauge.getOrDefault(p.getUniqueId(), 0.0));
    }

    public boolean isFull(Player p) {
        return get(p) >= max;
    }

    public void add(Player p, double amount) {
        if (isFever(p)) return; // 피버 중엔 안 참
        double before = get(p);
        double after = Math.min(max, before + amount);
        gauge.put(p.getUniqueId(), after);
        if (before < max && after >= max) {
            p.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.6f);
            p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0.1);
        }
    }

    public void onHit(Player p) { add(p, gainOnHit); }

    /** 스킬 피해: gain-on-skill-hit 만큼, skill-gain-interval-ticks 간격 제한 (0이면 매번). */
    public void onSkillHit(Player p) {
        if (skillGainInterval > 0) {
            int now = Bukkit.getCurrentTick();
            Integer last = lastSkillGain.get(p.getUniqueId());
            if (last != null && now - last < skillGainInterval) return;
            lastSkillGain.put(p.getUniqueId(), now);
        }
        add(p, gainOnSkillHit);
    }

    public double max() { return max; }

    /** 설정값 변경 (/rpg fevercfg). key: max, hit, hurt, skill, interval */
    public boolean setOption(String key, double value) {
        switch (key) {
            case "max" -> max = Math.max(1.0, value);
            case "hit" -> gainOnHit = Math.max(0, value);
            case "hurt" -> gainOnHurt = Math.max(0, value);
            case "skill" -> gainOnSkillHit = Math.max(0, value);
            case "interval" -> skillGainInterval = (int) Math.max(0, value);
            default -> { return false; }
        }
        return true;
    }

    public String describe() {
        return String.format("최대 %.0f · 평타 +%.1f · 피격 +%.1f · 스킬 +%.1f · 스킬 간격 %d틱",
                max, gainOnHit, gainOnHurt, gainOnSkillHit, skillGainInterval);
    }

    /** config.yml 에 저장할 값들 */
    public void save(ConfigurationSection sec) {
        sec.set("max", max);
        sec.set("gain-on-hit", gainOnHit);
        sec.set("gain-on-hurt", gainOnHurt);
        sec.set("gain-on-skill-hit", gainOnSkillHit);
        sec.set("skill-gain-interval-ticks", skillGainInterval);
    }

    public void onHurt(Player p) { add(p, gainOnHurt); }

    public void set(Player p, double value) {
        gauge.put(p.getUniqueId(), Math.max(0, Math.min(max, value)));
    }

    /** 게이지를 비우고 피버 시작 (무기 미지정). */
    public void consume(Player p, int durationTicks, String kitId) {
        feverKit.put(p.getUniqueId(), kitId);
        consume(p, durationTicks);
    }

    /** 이 무기로 시작한 피버가 진행 중인지 (무기별 피버 효과 판정용). */
    public boolean isFever(Player p, String kitId) {
        return isFever(p) && kitId.equals(feverKit.get(p.getUniqueId()));
    }

    /** 진행 중인 피버를 시작한 무기 id (없으면 null). */
    public String feverKit(Player p) {
        return isFever(p) ? feverKit.get(p.getUniqueId()) : null;
    }

    public void consume(Player p, int durationTicks) {
        gauge.put(p.getUniqueId(), 0.0);
        feverUntil.put(p.getUniqueId(), Bukkit.getCurrentTick() + durationTicks);
    }

    /** 어떤 무기든 피버 진행 중인지. 무기 효과 판정에는 isFever(p, kitId) / ctx.isFever() 를 쓸 것. */
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
        feverKit.remove(p.getUniqueId());
        lastSkillGain.remove(p.getUniqueId());
    }
}
