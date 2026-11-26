package com.fsixel.deruyrpg.weapons.bow;

import com.fsixel.deruyrpg.DeruyRPG;
import com.fsixel.deruyrpg.api.SkillContext;
import com.fsixel.deruyrpg.api.WeaponKit;
import com.fsixel.deruyrpg.util.Damage;
import com.fsixel.deruyrpg.util.Fx;
import com.fsixel.deruyrpg.util.Items;
import com.fsixel.deruyrpg.util.Targets;
import net.kyori.adventure.bossbar.BossBar;
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
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 활 — docs/SKILLS.md 의 "활" 항목.
 * P 풀차지(+25%) / 우 바닐라 사격 / 좌 발차기 / ⇧우 폭발 화살 3발 / F 워든 음파.
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
    private static final double EXPLOSION_RADIUS = 3.0;
    private static final double EXPLOSION_DAMAGE = 6.0;

    private static final int SONIC_CHARGE_TICKS = 8;
    private static final double SONIC_RANGE = 15.0;
    private static final double SONIC_WIDTH = 1.5;
    private static final double SONIC_DAMAGE = 14.0;

    // ---- 화살 PDC ----
    private static final NamespacedKey ARROW_KEY = new NamespacedKey("deruyrpg", "bow_arrow");
    private static final NamespacedKey FULL_KEY = new NamespacedKey("deruyrpg", "bow_full");
    /** 1 = 폭발 대기, 2 = 폭발함(직격 보너스 대기), 3 = 끝 */
    private static final NamespacedKey BOOM_KEY = new NamespacedKey("deruyrpg", "bow_boom");
    private static final NamespacedKey FEVER_KEY = new NamespacedKey("deruyrpg", "bow_fever");

    // ---- 플레이어 상태 ----
    private final Map<UUID, Integer> explosiveCharges = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();
    private boolean watcherStarted;

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
                "F  워든 음파");
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
        for (LivingEntity t : targets) {
            if (t.equals(hitTarget) && hitEvent != null) {
                // 지금 처리 중인 평타에 발차기 피해를 얹음 (무적 프레임 충돌 방지)
                hitEvent.setDamage(hitEvent.getDamage() + dmg);
            } else {
                Damage.skill(p, t, dmg);
            }
        }
        // 바닐라 넉백이 덮어쓰지 않게 다음 틱에 밀어냄
        double push = ctx.isFever() ? KICK_PUSH * 1.2 : KICK_PUSH;
        Bukkit.getScheduler().runTask(ctx.plugin(), () -> {
            for (LivingEntity t : targets) {
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
        updateBar(p);
        startWatcher(ctx.plugin());
        Fx.sound(p.getLocation(), Sound.ENTITY_TNT_PRIMED, 0.8f, 1.3f);
        Fx.burst(p.getLocation().add(0, 1, 0), Particle.FLAME, 15, 0.4);
    }

    private void updateBar(Player p) {
        UUID id = p.getUniqueId();
        int left = explosiveCharges.getOrDefault(id, 0);
        if (left <= 0) {
            explosiveCharges.remove(id);
            BossBar bar = bars.remove(id);
            if (bar != null) p.hideBossBar(bar);
            return;
        }
        Component name = Component.text("폭발 화살 " + left + "/" + EXPLOSIVE_SHOTS + "발", NamedTextColor.RED);
        float progress = Math.min(1f, left / (float) EXPLOSIVE_SHOTS);
        BossBar bar = bars.get(id);
        if (bar == null) {
            bar = BossBar.bossBar(name, progress, BossBar.Color.RED, BossBar.Overlay.NOTCHED_6);
            bars.put(id, bar);
            p.showBossBar(bar);
        } else {
            bar.name(name);
            bar.progress(progress);
        }
    }

    /** 활을 버리거나 해서 손에서 사라진 경우(onUnequip 미호출) 상태 정리. */
    private void startWatcher(DeruyRPG plugin) {
        if (watcherStarted) return;
        watcherStarted = true;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Iterator<UUID> it = explosiveCharges.keySet().iterator();
            while (it.hasNext()) {
                UUID id = it.next();
                Player p = Bukkit.getPlayer(id);
                if (p == null || !id().equals(Items.kitId(p.getInventory().getItemInMainHand()))) {
                    it.remove();
                    BossBar bar = bars.remove(id);
                    if (bar != null && p != null) p.hideBossBar(bar);
                }
            }
            bars.keySet().removeIf(id -> !explosiveCharges.containsKey(id));
        }, 10L, 10L);
    }

    @Override
    public void onUnequip(Player player) {
        UUID id = player.getUniqueId();
        explosiveCharges.remove(id);
        BossBar bar = bars.remove(id);
        if (bar != null) player.hideBossBar(bar);
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
        if (DeruyRPG.get().fever().isFever(p)) pdc.set(FEVER_KEY, PersistentDataType.BYTE, (byte) 1);

        UUID id = p.getUniqueId();
        int left = explosiveCharges.getOrDefault(id, 0);
        if (left > 0) {
            pdc.set(BOOM_KEY, PersistentDataType.BYTE, (byte) 1);
            explosiveCharges.put(id, left - 1);
            updateBar(p);
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
        double dmg = fever ? EXPLOSION_DAMAGE * 1.3 : EXPLOSION_DAMAGE;

        // 블록 파괴 없는 직접 범위 피해. 직격 대상은 화살 피해에 합산 (onArrowDamage).
        for (LivingEntity t : Targets.enemiesAround(p, at, EXPLOSION_RADIUS)) {
            if (t.equals(direct)) continue;
            Damage.skill(p, t, dmg);
            Targets.push(t, at, 0.7, 0.4);
        }
        Fx.burst(at, Particle.EXPLOSION, 3, 0.6);
        Fx.burst(at, Particle.SMOKE, 20, 0.8);
        Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.1f);

        if (direct == null) arrow.remove(); // 블록에 박힌 폭발 화살은 소멸
    }

    // =========================================================
    // P 풀차지 + 폭발 직격 보너스
    // =========================================================
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onArrowDamage(EntityDamageByEntityEvent e) {
        if (!isOurArrow(e.getDamager())) return;
        AbstractArrow arrow = (AbstractArrow) e.getDamager();
        PersistentDataContainer pdc = arrow.getPersistentDataContainer();

        double dmg = e.getDamage();
        if (pdc.has(FULL_KEY, PersistentDataType.BYTE)) {
            dmg *= FULL_CHARGE_MULT;
            e.getEntity().getWorld().spawnParticle(Particle.CRIT, e.getEntity().getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0.1);
        }
        if (boomState(arrow) == 2) {
            pdc.set(BOOM_KEY, PersistentDataType.BYTE, (byte) 3);
            if (arrow.getShooter() instanceof Player p && Targets.isEnemy(p, e.getEntity())) {
                boolean fever = pdc.has(FEVER_KEY, PersistentDataType.BYTE);
                dmg += fever ? EXPLOSION_DAMAGE * 1.3 : EXPLOSION_DAMAGE;
            }
        }
        e.setDamage(dmg);
    }

    // =========================================================
    // F 워든 음파
    // =========================================================
    @Override
    public void onUltimate(SkillContext ctx) {
        Player p = ctx.player();
        Fx.sound(p.getLocation(), Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.2f, 1.0f);
        Bukkit.getScheduler().runTaskLater(ctx.plugin(), () -> {
            if (!p.isOnline() || p.isDead()) return;
            fireSonicBoom(p);
        }, SONIC_CHARGE_TICKS);
    }

    private void fireSonicBoom(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Vector flat = dir.clone().setY(0);
        Vector kb = (flat.lengthSquared() < 1e-6 ? new Vector(0, 0, 0) : flat.normalize().multiply(1.6)).setY(0.5);
        Set<LivingEntity> hit = new HashSet<>();

        for (double d = 1.0; d <= SONIC_RANGE; d += 1.0) {
            Location point = eye.clone().add(dir.clone().multiply(d));
            p.getWorld().spawnParticle(Particle.SONIC_BOOM, point, 1, 0, 0, 0, 0);
            for (Entity e : p.getWorld().getNearbyEntities(point, SONIC_WIDTH, SONIC_WIDTH, SONIC_WIDTH)) {
                if (e instanceof LivingEntity le && Targets.isEnemy(p, e) && hit.add(le)) {
                    Damage.pierce(p, le, SONIC_DAMAGE);
                    le.setVelocity(kb);
                }
            }
        }
        Fx.sound(eye, Sound.ENTITY_WARDEN_SONIC_BOOM, 2f, 1f);
    }
}
