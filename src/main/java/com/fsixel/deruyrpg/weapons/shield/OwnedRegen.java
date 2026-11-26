package com.fsixel.deruyrpg.weapons.shield;

import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.Nullable;

/**
 * 방패 무기가 직접 건 재생 효과 기록. 다른 출처(물약·황금사과 등)의 재생은 건드리지 않도록
 * 현재 효과가 우리가 건 것(같은 등급, 남은 시간이 우리가 건 시간 이하)일 때만 제거한다.
 */
record OwnedRegen(int amp, int endTick) {

    /** 재생을 걸고, 실제로 우리 효과가 적용됐으면 기록을 반환. */
    static @Nullable OwnedRegen apply(LivingEntity e, int duration, int amp, boolean ambient, boolean particles) {
        e.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, duration, amp, ambient, particles));
        PotionEffect cur = e.getPotionEffect(PotionEffectType.REGENERATION);
        if (cur == null || cur.getAmplifier() != amp || cur.getDuration() > duration) return null;
        return new OwnedRegen(amp, Bukkit.getCurrentTick() + duration);
    }

    boolean expired() {
        return Bukkit.getCurrentTick() >= endTick;
    }

    /** 현재 재생이 이 기록과 일치하는지. */
    boolean matches(LivingEntity e) {
        PotionEffect cur = e.getPotionEffect(PotionEffectType.REGENERATION);
        if (cur == null || cur.getAmplifier() != amp) return false;
        int left = endTick - Bukkit.getCurrentTick();
        return cur.getDuration() <= left + 2;
    }

    /** 우리가 건 재생이면 즉시 제거. */
    void strip(LivingEntity e) {
        if (!expired() && matches(e)) e.removePotionEffect(PotionEffectType.REGENERATION);
    }
}
