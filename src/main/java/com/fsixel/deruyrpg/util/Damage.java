package com.fsixel.deruyrpg.util;

import org.bukkit.Bukkit;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 스킬 피해는 반드시 여기를 통해 줄 것.
 * - 공격자 기록(킬 판정, 피버 게이지)이 남음
 * - 코어 리스너가 스킬 피해를 평타(onMeleeHit)로 다시 처리하지 않게 표시함
 * - 반환값: 피해가 실제로 들어갔는지 (PvP 금지 구역 등에서 취소되면 false)
 *   → 넉백·끌어오기·CC 는 반환값이 true 일 때만 줄 것
 * - 평타 직후(10틱 이내) 스킬이면 무적시간을 무시하고 들어감
 * - 피해 없이 CC만 거는 스킬은 먼저 canHarm(공격자, 대상) 으로 확인할 것
 */
public final class Damage {

    private static final ThreadLocal<Boolean> SKILL = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> PROBE = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> PASSED = ThreadLocal.withInitial(() -> false);

    /** 대상별 마지막 근접 평타 틱 */
    private static final Map<UUID, Integer> lastMelee = new ConcurrentHashMap<>();

    private Damage() {}

    /** 지금 처리 중인 피해가 스킬 피해인지 (코어 리스너용). */
    public static boolean isSkillDamage() {
        return SKILL.get();
    }

    /** canHarm 확인용 가짜 이벤트인지 — 리스너는 이 이벤트에 반응하지 말 것. */
    public static boolean isProbe() {
        return PROBE.get();
    }

    /** 코어 MONITOR 리스너가 스킬 피해가 통과했을 때 호출. */
    public static void markPassed() {
        PASSED.set(true);
    }

    /** 코어가 근접 평타 직후 호출 (무적시간 보정용). */
    public static void recordMelee(LivingEntity target) {
        int now = Bukkit.getCurrentTick();
        lastMelee.put(target.getUniqueId(), now);
        if (lastMelee.size() > 128) lastMelee.values().removeIf(t -> now - t > 20); // 만료 기록 정리
    }

    public static void forget(UUID id) {
        lastMelee.remove(id);
    }

    /** 일반 스킬 피해 (방어력 적용). */
    public static boolean skill(Player attacker, LivingEntity target, double amount) {
        return apply(attacker, target, amount, DamageType.PLAYER_ATTACK);
    }

    /** 방어력 무시 피해 (워든 음파와 같은 판정). */
    public static boolean pierce(Player attacker, LivingEntity target, double amount) {
        return apply(attacker, target, amount, DamageType.SONIC_BOOM);
    }

    /** 마법 피해 (방어력 무시, 마법 저항은 적용). */
    public static boolean magic(Player attacker, LivingEntity target, double amount) {
        return apply(attacker, target, amount, DamageType.INDIRECT_MAGIC);
    }

    /** 원하는 DamageType 직접 지정. 피해가 들어갔으면 true. */
    public static boolean apply(Player attacker, LivingEntity target, double amount, DamageType type) {
        if (target.isDead() || target.equals(attacker)) return false;
        Integer melee = lastMelee.get(target.getUniqueId());
        if (melee != null && Bukkit.getCurrentTick() - melee <= 10) {
            target.setNoDamageTicks(0);
        }
        DamageSource source = DamageSource.builder(type)
                .withCausingEntity(attacker)
                .withDirectEntity(attacker)
                .build();
        boolean prev = SKILL.get();
        SKILL.set(true);
        PASSED.set(false);
        try {
            target.damage(amount, source);
            return PASSED.get();
        } finally {
            SKILL.set(prev);
            PASSED.set(false);
        }
    }

    /**
     * 공격자가 대상을 해칠 수 있는지 (PvP 꺼짐·보호 구역 플러그인 확인). 피해 없이 CC·넉백만 줄 때 사용.
     * 실제 피해는 주지 않는다.
     */
    public static boolean canHarm(Player attacker, LivingEntity target) {
        if (target.isDead() || target.equals(attacker)) return false;
        if (target instanceof Player && !target.getWorld().getPVP()) return false;
        DamageSource source = DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker)
                .withDirectEntity(attacker)
                .build();
        EntityDamageByEntityEvent probe = new EntityDamageByEntityEvent(attacker, target,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 0.0001);
        boolean prevProbe = PROBE.get();
        boolean prevSkill = SKILL.get();
        PROBE.set(true);
        SKILL.set(true);
        try {
            Bukkit.getPluginManager().callEvent(probe);
            return !probe.isCancelled();
        } finally {
            PROBE.set(prevProbe);
            SKILL.set(prevSkill);
        }
    }
}
