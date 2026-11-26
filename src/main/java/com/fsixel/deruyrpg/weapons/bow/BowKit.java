package com.fsixel.deruyrpg.weapons.bow;

import com.fsixel.deruyrpg.DeruyRPG;
import com.fsixel.deruyrpg.api.SkillContext;
import com.fsixel.deruyrpg.api.WeaponKit;
import com.fsixel.deruyrpg.util.Damage;
import com.fsixel.deruyrpg.util.Fx;
import com.fsixel.deruyrpg.util.Items;
import com.fsixel.deruyrpg.util.Targets;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 활 — docs/SKILLS.md 의 "활" 항목.
 * P 풀차지(+25%) / 우 바닐라 사격 / 좌 발차기 / ⇧우 폭발 화살 3발(TNT급 범위) / F 피버: 사격마다 워든 음파 동반, 화살 피해 +200%.
 */
public final class BowKit implements WeaponKit, Listener {

    // ---- 수치 ----
    private static final float FULL_CHARGE_FORCE = 0.999f;
    private static final double FULL_CHARGE_MULT = 1.25;

    private static final int KICK_COOLDOWN = 40;          // 2초
    private static final double KICK_DAMAGE = 2.0;
    private static final double KICK_PUSH = 1.4;

    private static final int EXPLOSIVE_SHOTS = 3;
    private static final int EXPLOSIVE_COOLDOWN = 300;    // 15초 (발동 시점부터)
    private static final double EXPLOSION_RADIUS = 4.5;   // TNT 수준
    private static final double EXPLOSION_DAMAGE = 10.0;  // 중심 피해, 가장자리로 갈수록 감소
    private static final double EXPLOSION_EDGE_RATIO = 0.25; // 반경 끝에서의 피해 비율

    private static final double FEVER_MULT = 3.0;         // 피버 중 상시 피해 +200%
    private static final double SONIC_RANGE = 30.0;
    private static final double SONIC_WIDTH = 1.5;
    private static final double SONIC_DAMAGE = 10.0;      // 풀차지 기준, 당긴 정도에 비례 (최소 35%)
    private static final double GLOW_RADIUS = 30.0;
    private static final int GLOW_PERIOD = 20;            // 1초마다 갱신
    private static final int GLOW_TICKS = 40;

    // ---- 화살 PDC ----
    private static final NamespacedKey ARROW_KEY = new NamespacedKey("deruyrpg", "bow_arrow");
    private static final NamespacedKey FULL_KEY = new NamespacedKey("deruyrpg", "bow_full");
    /** 1 = 폭발 대기, 2 = 폭발함(직격 보너스 대기), 3 = 끝 */
    private static final NamespacedKey BOOM_KEY = new NamespacedKey("deruyrpg", "bow_boom");
    private static final NamespacedKey FEVER_KEY = new NamespacedKey("deruyrpg", "bow_fever");

    // ---- 플레이어 상태 ----
    private final Map<UUID, Integer> explosiveCharges = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastGlow = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "bow";
    }

    @Override
    public String displayName() {
        return "활";
    }

    @Override
    public ItemStack createItem() {
        return Items.weapon(Material.BOW, id(), "활", NamedTextColor.GREEN,
                "P  풀차지 피해 +25%",
                "우  사격",
                "좌  발차기 넉백",
                "⇧우  다음 3발 폭발 화살",
                "F  피버: 사격마다 워든 음파 동반 + 주변 적 발광, 화살 피해 +200%");
    }

    @Override
    public String[] cooldownSlots() {
        return new String[]{Slot.LEFT, Slot.SHIFT_RIGHT};
    }

    // =========================================================
    // 우 사격: 바닐라 그대로
    // =========================================================
    @Override
    public void onRightClick(SkillContext ctx) {
        // 바닐라 활 당기기 — 아무것도 안 함
    }

    // =========================================================
    // 좌 발차기
    // =========================================================
    @Override
    public void onLeftClick(SkillContext ctx) {
        kick(ctx, null, null);
    }

    @Override
    public void onMeleeHit(SkillContext ctx, LivingEntity target, EntityDamageByEntityEvent event) {
        kick(ctx, target, event);
    }

    private void kick(SkillContext ctx, LivingEntity hitTarget, EntityDamageByEntityEvent hitEvent) {
        Player p = ctx.player();
        List<LivingEntity> targets = Targets.frontBox(p, 2.5, 3.0, 2.0);
        if (hitTarget != null && !targets.contains(hitTarget) && Targets.isEnemy(p, hitTarget)) targets.add(hitTarget);
        if (targets.isEmpty()) return;
        if (!ctx.tryCooldown(Slot.LEFT, KICK_COOLDOWN)) return;

        double dmg = ctx.isFever() ? KICK_DAMAGE * 1.5 : KICK_DAMAGE;
        Location from = p.getLocation();
        List<LivingEntity> passed = new java.util.ArrayList<>();
        LivingEntity direct = null;
        for (LivingEntity t : targets) {
            if (t.equals(hitTarget) && hitEvent != null) {
                // 지금 처리 중인 평타에 발차기 피해를 얹음 (무적 프레임 충돌 방지). 넉백은 평타가 취소되지 않았을 때만.
                hitEvent.setDamage(hitEvent.getDamage() + dmg);
                direct = t;
            } else if (Damage.skill(p, t, dmg)) {
                passed.add(t);
            }
        }
        // 바닐라 넉백이 덮어쓰지 않게 다음 틱에 밀어냄 (피해가 실제로 들어간 대상만)
        double push = ctx.isFever() ? KICK_PUSH * 1.2 : KICK_PUSH;
        final LivingEntity directHit = direct;
        Bukkit.getScheduler().runTask(ctx.plugin(), () -> {
            if (directHit != null && !hitEvent.isCancelled()) passed.add(directHit);
            for (LivingEntity t : passed) {
                if (t.isValid() && !t.isDead()) Targets.push(t, from, push, 0.35);
            }
        });

        Vector look = p.getLocation().getDirection().setY(0);
        if (look.lengthSquared() > 1e-6) look.normalize().multiply(1.2);
        Location fx = p.getLocation().add(look).add(0, 0.6, 0);
        Fx.burst(fx, Particle.SWEEP_ATTACK, 1, 0.1);
        Fx.burst(fx, Particle.CLOUD, 8, 0.3);
        Fx.sound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1f, 0.8f);
    }

    // =========================================================
    // ⇧우 폭발 화살 (바닐라 당기기는 막지 않음)
    // =========================================================
    @Override
    public void onShiftRightClick(SkillContext ctx) {
        Player p = ctx.player();
        UUID id = p.getUniqueId();
        if (explosiveCharges.getOrDefault(id, 0) > 0) return; // 이미 충전 중이면 재충전 안 함
        if (!ctx.tryCooldown(Slot.SHIFT_RIGHT, EXPLOSIVE_COOLDOWN)) return;

        explosiveCharges.put(id, EXPLOSIVE_SHOTS);
        Fx.sound(p.getLocation(), Sound.ENTITY_TNT_PRIMED, 0.8f, 1.3f);
        Fx.burst(p.getLocation().add(0, 1, 0), Particle.FLAME, 15, 0.4);
    }

    @Override
    public Component hudExtra(SkillContext ctx) {
        int left = explosiveCharges.getOrDefault(ctx.player().getUniqueId(), 0);
        if (left <= 0) return null;
        return Component.text("💥 " + left + "/" + EXPLOSIVE_SHOTS, NamedTextColor.RED);
    }

    @Override
    public void onUnequip(Player player) {
        // 여러 번 불려도 안전 (맵 제거만). 피버 발광 갱신 상태도 여기서 정리 → 다시 들면 onPassiveTick 이 재개.
        UUID id = player.getUniqueId();
        explosiveCharges.remove(id);
        lastGlow.remove(id);
    }

    // =========================================================
    // 화살 발사 / 명중
    // =========================================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!id().equals(Items.kitId(e.getBow()))) return;
        if (!(e.getProjectile() instanceof AbstractArrow arrow)) return;

        PersistentDataContainer pdc = arrow.getPersistentDataContainer();
        pdc.set(ARROW_KEY, PersistentDataType.BYTE, (byte) 1);
        if (e.getForce() >= FULL_CHARGE_FORCE) pdc.set(FULL_KEY, PersistentDataType.BYTE, (byte) 1);
        boolean fever = DeruyRPG.get().fever().isFever(p, id()); // 이 활로 시작한 피버만 (발사 시점에 화살에 기록)
        if (fever) {
            pdc.set(FEVER_KEY, PersistentDataType.BYTE, (byte) 1);
            fireSonicBoom(p, arrow.getVelocity(), e.getForce());
        }

        UUID id = p.getUniqueId();
        int left = explosiveCharges.getOrDefault(id, 0);
        if (left > 0) {
            pdc.set(BOOM_KEY, PersistentDataType.BYTE, (byte) 1);
            if (left - 1 <= 0) explosiveCharges.remove(id);
            else explosiveCharges.put(id, left - 1);
            Fx.sound(p.getLocation(), Sound.ENTITY_TNT_PRIMED, 0.5f, 1.8f);
            trail(arrow);
        }
    }

    private void trail(AbstractArrow arrow) {
        Bukkit.getScheduler().runTaskTimer(DeruyRPG.get(), task -> {
            if (!arrow.isValid() || arrow.isInBlock() || arrow.isOnGround()
                    || boomState(arrow) != 1 || arrow.getTicksLived() > 200) {
                task.cancel();
                return;
            }
            arrow.getWorld().spawnParticle(Particle.SMOKE, arrow.getLocation(), 2, 0.05, 0.05, 0.05, 0);
            arrow.getWorld().spawnParticle(Particle.FLAME, arrow.getLocation(), 1, 0, 0, 0, 0);
        }, 1L, 1L);
    }

    private static byte boomState(AbstractArrow arrow) {
        Byte b = arrow.getPersistentDataContainer().get(BOOM_KEY, PersistentDataType.BYTE);
        return b == null ? 0 : b;
    }

    private static boolean isOurArrow(Entity e) {
        return e instanceof AbstractArrow a && a.getPersistentDataContainer().has(ARROW_KEY, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(ProjectileHitEvent e) {
        if (!isOurArrow(e.getEntity())) return;
        AbstractArrow arrow = (AbstractArrow) e.getEntity();
        if (boomState(arrow) != 1) return;
        if (!(arrow.getShooter() instanceof Player p) || !p.isOnline()) return;

        arrow.getPersistentDataContainer().set(BOOM_KEY, PersistentDataType.BYTE, (byte) 2);
        Entity direct = e.getHitEntity();
        Location at = direct != null ? direct.getLocation().add(0, direct.getHeight() / 2, 0) : arrow.getLocation();
        boolean fever = arrow.getPersistentDataContainer().has(FEVER_KEY, PersistentDataType.BYTE);
        double base = fever ? EXPLOSION_DAMAGE * FEVER_MULT : EXPLOSION_DAMAGE;

        // 블록 파괴 없는 TNT급 범위 피해 (거리 감쇠). 직격 대상은 화살 피해에 합산 (onArrowDamage).
        for (LivingEntity t : Targets.enemiesAround(p, at, EXPLOSION_RADIUS + 1.0)) {
            if (t.equals(direct)) continue;
            Location c = t.getLocation().add(0, t.getHeight() / 2, 0);
            double dist = c.distance(at);
            if (dist > EXPLOSION_RADIUS) continue;
            double falloff = 1.0 - (1.0 - EXPLOSION_EDGE_RATIO) * (dist / EXPLOSION_RADIUS);
            if (Damage.skill(p, t, base * falloff)) {
                Targets.push(t, at, 0.4 + 0.9 * falloff, 0.25 + 0.35 * falloff);
            }
        }
        Fx.burst(at, Particle.EXPLOSION_EMITTER, 1, 0);
        Fx.burst(at, Particle.EXPLOSION, 8, 1.5);
        Fx.burst(at, Particle.LARGE_SMOKE, 30, 1.5);
        Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.9f);

        if (direct == null) arrow.remove(); // 블록에 박힌 폭발 화살은 소멸
    }

    // =========================================================
    // P 풀차지 + 폭발 직격 보너스
    // =========================================================
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onArrowDamage(EntityDamageByEntityEvent e) {
        if (Damage.isProbe()) return;
        if (!isOurArrow(e.getDamager())) return;
        AbstractArrow arrow = (AbstractArrow) e.getDamager();
        PersistentDataContainer pdc = arrow.getPersistentDataContainer();

        double dmg = e.getDamage();
        boolean feverArrow = pdc.has(FEVER_KEY, PersistentDataType.BYTE);
        // 피버 화살은 풀차지 여부와 상관없이 ×3(+200%), 아니면 풀차지 +25% (중첩 없음)
        if (feverArrow || pdc.has(FULL_KEY, PersistentDataType.BYTE)) {
            dmg *= feverArrow ? FEVER_MULT : FULL_CHARGE_MULT;
            e.getEntity().getWorld().spawnParticle(Particle.CRIT, e.getEntity().getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0.1);
        }
        if (boomState(arrow) == 2) {
            pdc.set(BOOM_KEY, PersistentDataType.BYTE, (byte) 3);
            if (arrow.getShooter() instanceof Player p && Targets.isEnemy(p, e.getEntity())) {
                dmg += feverArrow ? EXPLOSION_DAMAGE * FEVER_MULT : EXPLOSION_DAMAGE;
            }
        }
        e.setDamage(dmg);
    }

    // =========================================================
    // F 피버: 사격마다 워든 음파 동반 + 주변 적 발광
    // =========================================================
    @Override
    public void onUltimate(SkillContext ctx) {
        Player p = ctx.player();
        Fx.sound(p.getLocation(), Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.2f, 1.0f);
        Fx.sound(p.getLocation(), Sound.ENTITY_WARDEN_ROAR, 0.8f, 1.2f);
        Fx.burst(p.getLocation().add(0, 1, 0), Particle.SCULK_SOUL, 25, 0.6);
        lastGlow.remove(p.getUniqueId());
        glowEnemies(p);
    }

    @Override
    public void onPassiveTick(SkillContext ctx) {
        if (!ctx.isFever()) {
            lastGlow.remove(ctx.player().getUniqueId());
            return;
        }
        Player p = ctx.player();
        int now = Bukkit.getCurrentTick();
        Integer last = lastGlow.get(p.getUniqueId());
        if (last != null && now - last < GLOW_PERIOD) return;
        glowEnemies(p);
    }

    private void glowEnemies(Player p) {
        lastGlow.put(p.getUniqueId(), Bukkit.getCurrentTick());
        for (LivingEntity t : Targets.enemiesAround(p, p.getLocation(), GLOW_RADIUS)) {
            t.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, GLOW_TICKS, 0, false, false, false));
        }
    }

    /** 화살과 같은 방향으로 워든 음파 발사 (피버 중 사격마다). */
    private void fireSonicBoom(Player p, Vector arrowVel, float force) {
        Location eye = p.getEyeLocation();
        Vector dir = (arrowVel == null || arrowVel.lengthSquared() < 1e-6 ? eye.getDirection() : arrowVel.clone()).normalize();
        Vector flat = dir.clone().setY(0);
        Vector kb = (flat.lengthSquared() < 1e-6 ? new Vector(0, 0, 0) : flat.normalize().multiply(1.2)).setY(0.4);
        double dmg = SONIC_DAMAGE * Math.max(0.35, Math.min(1.0, force));
        Set<LivingEntity> hit = new HashSet<>();

        for (double d = 1.0; d <= SONIC_RANGE; d += 1.0) {
            Location point = eye.clone().add(dir.clone().multiply(d));
            p.getWorld().spawnParticle(Particle.SONIC_BOOM, point, 1, 0, 0, 0, 0);
            for (Entity e : p.getWorld().getNearbyEntities(point, SONIC_WIDTH, SONIC_WIDTH, SONIC_WIDTH)) {
                if (e instanceof LivingEntity le && Targets.isEnemy(p, e) && hit.add(le)) {
                    if (Damage.pierce(p, le, dmg)) le.setVelocity(kb);
                }
            }
        }
        Fx.sound(eye, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.5f, 1.1f);
    }
}
