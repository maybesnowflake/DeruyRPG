package com.fsixel.deruyrpg.util;

import com.fsixel.deruyrpg.DeruyRPG;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 군중제어(CC) 공통 도우미.
 * - stun(대상, 틱): 이동·점프 불가 + 제자리 고정 (기절/프리징)
 * - setImmune(대상, 틱): 그 시간 동안 stun 무시 + cleanse 자동
 * - cleanse(대상): 구속·공중부양·실명·어둠·멀미·나약함·채굴 피로 + 스턴 제거
 */
public final class Cc {

    private static final NamespacedKey STUN_MOVE = new NamespacedKey("deruyrpg", "cc_stun_move");
    private static final NamespacedKey STUN_JUMP = new NamespacedKey("deruyrpg", "cc_stun_jump");

    private static final Set<PotionEffectType> CC_EFFECTS = Set.of(
            PotionEffectType.SLOWNESS, PotionEffectType.LEVITATION, PotionEffectType.BLINDNESS,
            PotionEffectType.DARKNESS, PotionEffectType.NAUSEA, PotionEffectType.WEAKNESS,
            PotionEffectType.MINING_FATIGUE);

    private static final Map<UUID, Integer> stunUntil = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> immuneUntil = new ConcurrentHashMap<>();

    private Cc() {}

    public static boolean isImmune(LivingEntity e) {
        Integer end = immuneUntil.get(e.getUniqueId());
        return end != null && end > Bukkit.getCurrentTick();
    }

    /** ticks 동안 CC 면역 (이미 걸린 CC는 바로 제거). */
    public static void setImmune(LivingEntity e, int ticks) {
        immuneUntil.merge(e.getUniqueId(), Bukkit.getCurrentTick() + ticks, Math::max);
        cleanse(e);
    }

    public static void clearImmune(LivingEntity e) {
        immuneUntil.remove(e.getUniqueId());
    }

    public static boolean isStunned(LivingEntity e) {
        Integer end = stunUntil.get(e.getUniqueId());
        return end != null && end > Bukkit.getCurrentTick();
    }

    /** 기절: ticks 동안 이동·점프 불가. CC 면역이면 무시. 성공 시 true. */
    public static boolean stun(LivingEntity e, int ticks) {
        if (isImmune(e) || e.isDead()) return false;
        int end = Bukkit.getCurrentTick() + ticks;
        stunUntil.merge(e.getUniqueId(), end, Math::max);
        addMod(e, Attribute.MOVEMENT_SPEED, STUN_MOVE);
        addMod(e, Attribute.JUMP_STRENGTH, STUN_JUMP);
        e.setVelocity(new Vector(0, Math.min(0, e.getVelocity().getY()), 0));
        e.getWorld().spawnParticle(Particle.SNOWFLAKE, e.getLocation().add(0, e.getHeight() + 0.3, 0), 6, 0.2, 0.1, 0.2, 0);
        Bukkit.getScheduler().runTaskLater(DeruyRPG.get(), () -> {
            Integer cur = stunUntil.get(e.getUniqueId());
            if (cur != null && cur <= Bukkit.getCurrentTick()) unstun(e);
        }, ticks + 1L);
        return true;
    }

    public static void unstun(LivingEntity e) {
        stunUntil.remove(e.getUniqueId());
        removeMod(e, Attribute.MOVEMENT_SPEED, STUN_MOVE);
        removeMod(e, Attribute.JUMP_STRENGTH, STUN_JUMP);
    }

    /** 플러그인 종료 시: 걸려 있는 기절 전부 해제. */
    public static void unstunAll() {
        for (UUID id : java.util.List.copyOf(stunUntil.keySet())) {
            org.bukkit.entity.Entity e = Bukkit.getEntity(id);
            if (e instanceof LivingEntity le) unstun(le);
        }
        stunUntil.clear();
    }

    /** 모든 CC 제거. */
    public static void cleanse(LivingEntity e) {
        for (PotionEffectType t : CC_EFFECTS) e.removePotionEffect(t);
        unstun(e);
    }

    private static void addMod(LivingEntity e, Attribute attr, NamespacedKey key) {
        AttributeInstance inst = e.getAttribute(attr);
        if (inst == null || inst.getModifier(key) != null) return;
        inst.addTransientModifier(new AttributeModifier(key, -1.0,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
    }

    private static void removeMod(LivingEntity e, Attribute attr, NamespacedKey key) {
        AttributeInstance inst = e.getAttribute(attr);
        if (inst != null && inst.getModifier(key) != null) inst.removeModifier(key);
    }
}
