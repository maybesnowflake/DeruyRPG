package com.fsixel.deruyrpg.util;

import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * 스킬 피해는 반드시 여기를 통해 줄 것.
 * - 공격자 기록(킬 판정, 피버 게이지)이 남음
 * - 코어 리스너가 스킬 피해를 평타(onMeleeHit)로 다시 처리하지 않게 표시함
 */
public final class Damage {

    private static final ThreadLocal<Boolean> SKILL = ThreadLocal.withInitial(() -> false);

    private Damage() {}

    /** 지금 처리 중인 피해가 스킬 피해인지 (코어 리스너용). */
    public static boolean isSkillDamage() {
        return SKILL.get();
    }

    /** 일반 스킬 피해 (방어력 적용). */
    public static void skill(Player attacker, LivingEntity target, double amount) {
        apply(attacker, target, amount, DamageType.PLAYER_ATTACK);
    }

    /** 방어력 무시 피해 (워든 음파와 같은 판정). */
    public static void pierce(Player attacker, LivingEntity target, double amount) {
        apply(attacker, target, amount, DamageType.SONIC_BOOM);
    }

    /** 마법 피해 (방어력 무시, 마법 저항은 적용). */
    public static void magic(Player attacker, LivingEntity target, double amount) {
        apply(attacker, target, amount, DamageType.INDIRECT_MAGIC);
    }

    /** 원하는 DamageType 직접 지정. */
    public static void apply(Player attacker, LivingEntity target, double amount, DamageType type) {
        if (target.isDead() || target.equals(attacker)) return;
        DamageSource source = DamageSource.builder(type)
                .withCausingEntity(attacker)
                .withDirectEntity(attacker)
                .build();
        boolean prev = SKILL.get();
        SKILL.set(true);
        try {
            target.damage(amount, source);
        } finally {
            SKILL.set(prev);
        }
    }
}
