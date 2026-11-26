package com.fsixel.deruyrpg.weapons.crossbow;

import com.fsixel.deruyrpg.api.SkillContext;
import com.fsixel.deruyrpg.api.WeaponKit;
import com.fsixel.deruyrpg.util.Damage;
import com.fsixel.deruyrpg.util.Fx;
import com.fsixel.deruyrpg.util.Items;
import com.fsixel.deruyrpg.util.Targets;
import com.fsixel.deruyrpg.DeruyRPG;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
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
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 쇠뇌 — 스킬 명세: docs/SKILLS.md 의 "쇠뇌" 항목.
 *
 * P  직진 탄도  : 이 쇠뇌로 쏜 투사체를 시선 방향으로 재정렬 (탄퍼짐 제거, 다중발사 각도는 유지)
 * 우 사격       : 바닐라
 * 좌 발차기     : 전방 3칸 적 넉백 + 소량 피해 (1.5초 쿨)
 * ⇧우 구속 화살 : 10초간 쏘는 화살 명중 시 구속 II 3초 (20초 쿨)
 * F  유도 연사  : 피버 동안 4틱마다 자동 발사, 가장 가까운 적 유도
 */
public final class CrossbowKit implements WeaponKit, Listener {

    /** 화살 PDC 키 — 값은 플래그 비트. */
    private static final NamespacedKey ARROW_KEY = new NamespacedKey("deruyrpg", "crossbow_arrow");
    private static final byte FLAG_KIT = 1;
    private static final byte FLAG_SLOW = 2;
    private static final byte FLAG_HOMING = 4;

    // ---- 수치 ----
    private static final int KICK_COOLDOWN = 30;          // 1.5초
    private static final double KICK_RANGE = 3.0;
    private static final double KICK_WIDTH = 2.5;
    private static final double KICK_DAMAGE = 2.0;
    private static final double KICK_DAMAGE_FEVER = 3.0;

    private static final int SLOW_MODE_TICKS = 200;       // 10초
    private static final int SLOW_MODE_COOLDOWN = 400;    // 20초 (피버 중 절반)
    private static final int SLOW_EFFECT_TICKS = 60;      // 구속 3초
    private static final int SLOW_AMPLIFIER = 1;          // 구속 II

    private static final int VOLLEY_INTERVAL = 4;
    private static final double VOLLEY_SPEED = 2.6;
    private static final double VOLLEY_ARROW_DAMAGE = 1.0; // 바닐라 기본 2.0 (속도 곱)
    private static final double HOMING_RANGE = 24.0;
    private static final double HOMING_TURN = 0.35;
    private static final int HOMING_LIFE = 60;

    /** 구속 화살 모드 종료 틱. */
    private final Map<UUID, Integer> slowUntil = new ConcurrentHashMap<>();
    /** 유도 연사 태스크. */
    private final Map<UUID, BukkitTask> volleyTasks = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "crossbow";
    }

    @Override
    public String displayName() {
        return "쇠뇌";
    }

    @Override
    public ItemStack createItem() {
        return Items.weapon(Material.CROSSBOW, id(), "쇠뇌", NamedTextColor.DARK_GREEN,
                "P  직진 탄도",
                "우  사격",
                "좌  발차기 넉백",
                "⇧우  10초간 구속 화살",
                "F  유도 + 고속 연사");
    }

    @Override
    public String[] cooldownSlots() {
        return new String[]{Slot.LEFT, Slot.SHIFT_RIGHT};
    }

    // =====================================================================
    // 우 — 바닐라 사격 (아무것도 안 함, cancel 금지)
    // =====================================================================

    @Override
    public void onRightClick(SkillContext ctx) {
    }

    // =====================================================================
    // 좌 — 발차기
    // =====================================================================

    @Override
    public void onLeftClick(SkillContext ctx) {
        if (!ctx.tryCooldown(Slot.LEFT, KICK_COOLDOWN)) return;
        kick(ctx, null);
    }

    @Override
    public void onMeleeHit(SkillContext ctx, LivingEntity target, EntityDamageByEntityEvent event) {
        if (!ctx.tryCooldown(Slot.LEFT, KICK_COOLDOWN)) return;
        // 직접 맞은 대상은 이벤트 피해를 올리고, 넉백은 바닐라 넉백 이후(다음 틱)에 덮어씀
        if (Targets.isEnemy(ctx.player(), target)) {
            event.setDamage(Math.max(event.getDamage(), kickDamage(ctx)));
        }
        kick(ctx, target);
    }

    private double kickDamage(SkillContext ctx) {
        return ctx.isFever() ? KICK_DAMAGE_FEVER : KICK_DAMAGE;
    }

    private void kick(SkillContext ctx, @Nullable LivingEntity direct) {
        Player p = ctx.player();
        double dmg = kickDamage(ctx);
        double strength = ctx.isFever() ? 1.4 : 1.1;
        Location from = p.getLocation();

        List<LivingEntity> hits = Targets.frontBox(p, KICK_WIDTH, KICK_RANGE, 2.0);
        for (LivingEntity t : hits) {
            if (t.equals(direct)) continue;
            Damage.skill(p, t, dmg);
            Targets.push(t, from, strength, 0.35);
            Fx.burst(t.getLocation().add(0, 1, 0), Particle.CRIT, 6, 0.3);
        }
        if (direct != null && Targets.isEnemy(p, direct)) {
            Bukkit.getScheduler().runTask(ctx.plugin(), () -> {
                if (!direct.isDead()) Targets.push(direct, from, strength, 0.35);
            });
            Fx.burst(direct.getLocation().add(0, 1, 0), Particle.CRIT, 6, 0.3);
        }

        Location front = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(1.2)).add(0, 0.6, 0);
        p.getWorld().spawnParticle(Particle.SWEEP_ATTACK, front, 1, 0, 0, 0, 0);
        Fx.burst(front, Particle.POOF, 4, 0.2);
        Fx.sound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1f, 0.8f);
    }

    // =====================================================================
    // ⇧우 — 구속 화살 (바닐라 장전/발사는 그대로)
    // =====================================================================

    @Override
    public void onShiftRightClick(SkillContext ctx) {
        Player p = ctx.player();
        if (isSlowMode(p)) return; // 이미 활성 중이면 쿨다운 소모 없이 바닐라만
        if (!ctx.tryCooldown(Slot.SHIFT_RIGHT, SLOW_MODE_COOLDOWN)) return;
        slowUntil.put(p.getUniqueId(), Bukkit.getCurrentTick() + SLOW_MODE_TICKS);
        p.sendActionBar(Component.text("구속 화살 활성 (10초)", NamedTextColor.AQUA));
        Fx.sound(p.getLocation(), Sound.BLOCK_POWDER_SNOW_PLACE, 1f, 0.7f);
        Fx.sound(p.getLocation(), Sound.ITEM_CROSSBOW_LOADING_END, 0.8f, 0.6f);
        Fx.ring(p.getLocation(), Particle.SNOWFLAKE, 1.2, 20);
    }

    private boolean isSlowMode(Player p) {
        Integer end = slowUntil.get(p.getUniqueId());
        if (end == null) return false;
        if (end <= Bukkit.getCurrentTick()) {
            slowUntil.remove(p.getUniqueId());
            return false;
        }
        return true;
    }

    @Override
    public void onPassiveTick(SkillContext ctx) {
        Player p = ctx.player();
        Integer end = slowUntil.get(p.getUniqueId());
        if (end == null) return;
        if (end <= Bukkit.getCurrentTick()) {
            slowUntil.remove(p.getUniqueId());
            p.sendActionBar(Component.text("구속 화살 종료", NamedTextColor.GRAY));
            return;
        }
        Location hand = p.getLocation().add(0, 1.1, 0);
        p.getWorld().spawnParticle(Particle.SNOWFLAKE, hand, 4, 0.35, 0.4, 0.35, 0.01);
        p.getWorld().spawnParticle(Particle.ITEM_SNOWBALL, hand, 2, 0.3, 0.3, 0.3, 0);
    }

    // =====================================================================
    // F — 유도 연사
    // =====================================================================

    @Override
    public void onUltimate(SkillContext ctx) {
        Player p = ctx.player();
        stopVolley(p);
        Fx.sound(p.getLocation(), Sound.ITEM_CROSSBOW_QUICK_CHARGE_3, 1f, 1.2f);
        p.sendActionBar(Component.text("유도 연사!", NamedTextColor.GOLD));

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(ctx.plugin(), new Runnable() {
            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || !ctx.plugin().fever().isFever(p)
                        || !id().equals(Items.kitId(p.getInventory().getItemInMainHand()))) {
                    stopVolley(p);
                    return;
                }
                fireHoming(p);
            }
        }, 0L, VOLLEY_INTERVAL);
        volleyTasks.put(p.getUniqueId(), task);
    }

    private void stopVolley(Player p) {
        BukkitTask t = volleyTasks.remove(p.getUniqueId());
        if (t != null) t.cancel();
    }

    private void fireHoming(Player p) {
        Vector dir = p.getEyeLocation().getDirection();
        Arrow arrow = p.launchProjectile(Arrow.class, dir.clone().multiply(VOLLEY_SPEED));
        arrow.setShooter(p);
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        arrow.setDamage(VOLLEY_ARROW_DAMAGE);
        arrow.setCritical(true);
        byte flags = (byte) (FLAG_KIT | FLAG_HOMING | (isSlowMode(p) ? FLAG_SLOW : 0));
        arrow.getPersistentDataContainer().set(ARROW_KEY, PersistentDataType.BYTE, flags);
        Fx.sound(p.getLocation(), Sound.ITEM_CROSSBOW_SHOOT, 0.7f, 1.4f);

        LivingEntity initial = nearestEnemy(p, p.getLocation());
        new BukkitRunnable() {
            int age = 0;
            LivingEntity target = initial;

            @Override
            public void run() {
                if (!arrow.isValid() || arrow.isInBlock() || arrow.isOnGround() || ++age > HOMING_LIFE) {
                    if (arrow.isValid()) arrow.remove();
                    cancel();
                    return;
                }
                if (target == null || !target.isValid() || target.isDead()
                        || target.getWorld() != arrow.getWorld()
                        || target.getLocation().distanceSquared(arrow.getLocation()) > HOMING_RANGE * HOMING_RANGE) {
                    target = nearestEnemy(p, arrow.getLocation());
                }
                if (target == null) return;
                Vector vel = arrow.getVelocity();
                double speed = Math.max(vel.length(), 1.5);
                Vector to = target.getLocation().add(0, target.getHeight() * 0.5, 0).toVector()
                        .subtract(arrow.getLocation().toVector());
                if (to.lengthSquared() < 1e-6) return;
                Vector steer = vel.clone().normalize().multiply(1 - HOMING_TURN)
                        .add(to.normalize().multiply(HOMING_TURN));
                if (steer.lengthSquared() < 1e-6) return;
                arrow.setVelocity(steer.normalize().multiply(speed));
                arrow.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, arrow.getLocation(), 1, 0, 0, 0, 0);
            }
        }.runTaskTimer(DeruyRPG.get(), 1L, 1L);
    }

    private @Nullable LivingEntity nearestEnemy(Player p, Location from) {
        List<LivingEntity> list = Targets.enemiesAround(p, from, HOMING_RANGE);
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : list) {
            double d = e.getLocation().distanceSquared(from);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    @Override
    public void onUnequip(Player player) {
        stopVolley(player);
        slowUntil.remove(player.getUniqueId());
    }

    // =====================================================================
    // 이벤트
    // =====================================================================

    private boolean isThisKit(@Nullable ItemStack item) {
        return id().equals(Items.kitId(item));
    }

    private static byte flags(Entity e) {
        PersistentDataContainer pdc = e.getPersistentDataContainer();
        Byte b = pdc.get(ARROW_KEY, PersistentDataType.BYTE);
        return b == null ? 0 : b;
    }

    /** P — 직진 탄도 + 구속 화살 표시. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!isThisKit(e.getBow())) return;
        Entity proj = e.getProjectile();

        straighten(p, proj);
        // 이벤트 시점에 속도가 아직 0이면 다음 틱에 재정렬
        if (proj.getVelocity().lengthSquared() < 1e-4) {
            Vector look = p.getEyeLocation().getDirection();
            Bukkit.getScheduler().runTask(DeruyRPG.get(), () -> {
                if (proj.isValid()) proj.setVelocity(look.clone().multiply(Math.max(proj.getVelocity().length(), 3.15)));
            });
        }

        byte flags = FLAG_KIT;
        if (isSlowMode(p)) flags |= FLAG_SLOW;
        proj.getPersistentDataContainer().set(ARROW_KEY, PersistentDataType.BYTE, flags);
    }

    /** 속도 벡터를 시선 방향으로 재정렬. 다중발사 옆 화살(약 ±10°)은 그 각도를 유지. */
    private static void straighten(Player p, Entity proj) {
        Vector v = proj.getVelocity();
        double speed = v.length();
        if (speed < 1e-2) return;
        Vector look = p.getEyeLocation().getDirection().normalize();
        double angle = Math.toDegrees(v.angle(look));
        Vector out;
        if (angle < 5.0) {
            out = look.clone();
        } else {
            // 옆 화살: 시선 기준 좌/우를 판단해서 정확히 ±10° 로 정렬
            double side = look.clone().crossProduct(v).getY();
            double deg = side > 0 ? -10.0 : 10.0;
            out = look.clone().rotateAroundY(Math.toRadians(deg));
        }
        proj.setVelocity(out.normalize().multiply(speed));
    }

    /** 유도 화살은 연사로 무적 시간에 막히지 않게 + 땅에 박히면 제거. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent e) {
        Projectile proj = e.getEntity();
        byte f = flags(proj);
        if ((f & FLAG_HOMING) == 0) return;
        if (!(proj.getShooter() instanceof Player p)) return;
        Entity hit = e.getHitEntity();
        if (hit != null) {
            if (!Targets.isEnemy(p, hit)) {
                e.setCancelled(true); // 아군·소환물은 통과
                return;
            }
            if (hit instanceof LivingEntity le) le.setNoDamageTicks(0);
        } else if (e.getHitBlock() != null) {
            Bukkit.getScheduler().runTask(DeruyRPG.get(), () -> {
                if (proj.isValid()) proj.remove();
            });
        }
    }

    /** 구속 화살 명중. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArrowDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Projectile proj)) return;
        byte f = flags(proj);
        if ((f & FLAG_KIT) == 0 || (f & FLAG_SLOW) == 0) return;
        if (!(proj.getShooter() instanceof Player p)) return;
        if (!(e.getEntity() instanceof LivingEntity target) || !Targets.isEnemy(p, target)) return;
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, SLOW_EFFECT_TICKS, SLOW_AMPLIFIER, false, true, true));
        target.getWorld().spawnParticle(Particle.SNOWFLAKE, target.getLocation().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.02);
        Fx.sound(target.getLocation(), Sound.BLOCK_POWDER_SNOW_BREAK, 1f, 0.8f);
    }
}
