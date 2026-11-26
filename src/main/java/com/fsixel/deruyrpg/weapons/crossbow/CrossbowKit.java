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
import org.bukkit.FluidCollisionMode;
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
 * ⇧우 구속 화살 : 10초간 쏘는 화살 명중 시 구속 III 7초 (20초 쿨)
 * F  유도 연사  : 피버(15초) 동안 우클릭마다 장전 없이 즉시 유도 화살 (4틱 간격).
 *                 발사 시 시선 ±35° 원뿔에서 시선에 가장 가까운 적을 고정, 가까울수록 강하게 보정.
 *   피버 보너스 : 이 쇠뇌로 시작한 피버 중 쏜 화살(발사 시점 기록) 피해 +25%, 명중 넉백 +0.3, 구속 III 7초
 */
public final class CrossbowKit implements WeaponKit, Listener {

    /** 화살 PDC 키 — 값은 플래그 비트. */
    private static final NamespacedKey ARROW_KEY = new NamespacedKey("deruyrpg", "crossbow_arrow");
    private static final byte FLAG_KIT = 1;
    private static final byte FLAG_SLOW = 2;
    private static final byte FLAG_HOMING = 4;
    /** 이 쇠뇌로 시작한 피버 중에 쏜 화살 (발사 시점 기록). */
    private static final byte FLAG_FEVER = 8;

    // ---- 수치 ----
    private static final int KICK_COOLDOWN = 30;          // 1.5초
    private static final double KICK_RANGE = 3.0;
    private static final double KICK_WIDTH = 2.5;
    private static final double KICK_DAMAGE = 2.0;
    private static final double KICK_DAMAGE_FEVER = 3.0;

    private static final int SLOW_MODE_TICKS = 200;       // 10초
    private static final int SLOW_MODE_COOLDOWN = 400;    // 20초 (피버 중 절반)
    private static final int SLOW_EFFECT_TICKS = 140;     // 구속 7초
    private static final int SLOW_AMPLIFIER = 2;          // 구속 III

    private static final int FEVER_SHOT_INTERVAL = 4;      // 피버 우클릭 최소 간격(틱)
    /** 화살 비행 속도 배율 (사용자 요청 4배). 바닐라 화살 피해는 속도에 비례하므로 피해값은 같은 비율로 나눠 보정. */
    private static final double SPEED_MULT = 4.0;
    private static final double VOLLEY_SPEED = 2.6 * SPEED_MULT;
    private static final double VOLLEY_ARROW_DAMAGE = 1.0; // 바닐라 기본 2.0 (속도 곱)
    private static final double HOMING_RANGE = 32.0;       // 발사 시 타겟 탐색 거리
    private static final double HOMING_CONE_DEG = 35.0;    // 시선 원뿔 반각
    private static final double REACQUIRE_RANGE = 16.0;    // 타겟 사망 시 재지정 반경 (화살 기준)
    private static final double HOMING_TURN_MIN = 0.12;    // 멀 때 보정 비율
    private static final double HOMING_TURN_MAX = 0.6;     // 가까울 때 보정 비율
    private static final double HOMING_RAMP_DIST = 16.0;   // 이 거리 이하부터 보정 증가
    private static final double OCCLUDED_PENALTY_DEG = 60.0;
    private static final double OCCLUDED_PENALTY_BLOCKS = 24.0;
    private static final int HOMING_LIFE = 60;

    private static final double FEVER_DAMAGE_MULT = 1.25;
    private static final double FEVER_KNOCKBACK = 0.3;

    /** 구속 화살 모드 종료 틱. */
    private final Map<UUID, Integer> slowUntil = new ConcurrentHashMap<>();
    /** 피버 즉시 사격 마지막 틱. */
    private final Map<UUID, Integer> lastShot = new ConcurrentHashMap<>();

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
                "⇧우  10초간 구속 화살 (구속 III 7초)",
                "F  피버: 우클릭마다 즉시 유도 사격");
    }

    @Override
    public String[] cooldownSlots() {
        return new String[]{Slot.LEFT, Slot.SHIFT_RIGHT};
    }

    // =====================================================================
    // 우 — 바닐라 사격 (피버 중에는 즉시 유도 사격)
    // =====================================================================

    @Override
    public void onRightClick(SkillContext ctx) {
        if (ctx.isFever()) feverShot(ctx);
    }

    // =====================================================================
    // 좌 — 발차기
    // =====================================================================

    @Override
    public void onLeftClick(SkillContext ctx) {
        if (!ctx.tryCooldown(Slot.LEFT, KICK_COOLDOWN)) return;
        kick(ctx, null, null);
    }

    @Override
    public void onMeleeHit(SkillContext ctx, LivingEntity target, EntityDamageByEntityEvent event) {
        if (!ctx.tryCooldown(Slot.LEFT, KICK_COOLDOWN)) return;
        // 직접 맞은 대상은 이벤트 피해를 올리고, 넉백은 바닐라 넉백 이후(다음 틱)에 덮어씀
        if (Targets.isEnemy(ctx.player(), target)) {
            event.setDamage(Math.max(event.getDamage(), kickDamage(ctx)));
        }
        kick(ctx, target, event);
    }

    private double kickDamage(SkillContext ctx) {
        return ctx.isFever() ? KICK_DAMAGE_FEVER : KICK_DAMAGE;
    }

    private void kick(SkillContext ctx, @Nullable LivingEntity direct, @Nullable EntityDamageByEntityEvent directEvent) {
        Player p = ctx.player();
        double dmg = kickDamage(ctx);
        double strength = ctx.isFever() ? 1.4 : 1.1;
        Location from = p.getLocation();

        List<LivingEntity> hits = Targets.frontBox(p, KICK_WIDTH, KICK_RANGE, 2.0);
        for (LivingEntity t : hits) {
            if (t.equals(direct)) continue;
            if (!Damage.skill(p, t, dmg)) continue; // 피해가 막히면 넉백도 없음
            Targets.push(t, from, strength, 0.35);
            Fx.burst(t.getLocation().add(0, 1, 0), Particle.CRIT, 6, 0.3);
        }
        if (direct != null && directEvent != null && Targets.isEnemy(p, direct)) {
            Bukkit.getScheduler().runTask(ctx.plugin(), () -> {
                // 평타가 취소(PvP 꺼짐·보호구역)됐으면 넉백 없음
                if (!directEvent.isCancelled() && !direct.isDead()) Targets.push(direct, from, strength, 0.35);
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
        if (ctx.isFever()) { // 피버 중엔 구속이 상시라 ⇧우도 즉시 사격
            feverShot(ctx);
            return;
        }
        if (isSlowMode(p)) return; // 이미 활성 중이면 쿨다운 소모 없이 바닐라만
        if (!ctx.tryCooldown(Slot.SHIFT_RIGHT, SLOW_MODE_COOLDOWN)) return;
        slowUntil.put(p.getUniqueId(), Bukkit.getCurrentTick() + SLOW_MODE_TICKS);
        ctx.flash(Component.text("구속 화살 활성 (10초) — 명중 시 구속 III 7초", NamedTextColor.AQUA));
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
            ctx.flash(Component.text("구속 화살 종료", NamedTextColor.GRAY));
            return;
        }
        Location hand = p.getLocation().add(0, 1.1, 0);
        p.getWorld().spawnParticle(Particle.SNOWFLAKE, hand, 4, 0.35, 0.4, 0.35, 0.01);
        p.getWorld().spawnParticle(Particle.ITEM_SNOWBALL, hand, 2, 0.3, 0.3, 0.3, 0);
    }

    // =====================================================================
    // F — 유도 연사 (피버 동안 우클릭마다 장전 없이 즉시 유도 화살)
    // =====================================================================

    @Override
    public void onUltimate(SkillContext ctx) {
        Player p = ctx.player();
        if (p.hasActiveItem()) p.clearActiveItem(); // 장전 중이던 것 취소
        lastShot.remove(p.getUniqueId());
        Fx.sound(p.getLocation(), Sound.ITEM_CROSSBOW_QUICK_CHARGE_3, 1f, 1.2f);
        Fx.ring(p.getLocation().add(0, 0.2, 0), Particle.ELECTRIC_SPARK, 1.3, 24);
        ctx.flash(Component.text("유도 연사! 우클릭으로 즉시 사격", NamedTextColor.GOLD));
    }

    /** 피버 중 우클릭: 바닐라 장전 취소 + 내부 간격마다 유도 화살 1발. */
    private void feverShot(SkillContext ctx) {
        Player p = ctx.player();
        ctx.cancelVanilla();
        if (p.hasActiveItem()) p.clearActiveItem();
        int now = Bukkit.getCurrentTick();
        Integer last = lastShot.get(p.getUniqueId());
        if (last != null && now - last < FEVER_SHOT_INTERVAL) return;
        lastShot.put(p.getUniqueId(), now);
        fireHoming(p);
    }

    private void fireHoming(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Arrow arrow = p.launchProjectile(Arrow.class, dir.clone().multiply(VOLLEY_SPEED));
        arrow.setShooter(p);
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        arrow.setDamage(VOLLEY_ARROW_DAMAGE / SPEED_MULT);
        arrow.setCritical(true);
        arrow.setGravity(false);
        // 중력 없는 연출 화살: 저장 안 함(/reload·청크 저장 시 공중에 남지 않게) + 소환물 표시(onDisable 정리).
        // 화살은 LivingEntity 가 아니라 markSummon 이 Targets 판정·피해 처리에 주는 영향 없음. 수명 타이머는 아래 유지.
        arrow.setPersistent(false);
        Targets.markSummon(arrow);
        boolean fever = DeruyRPG.get().fever().isFever(p, id());
        byte flags = (byte) (FLAG_KIT | FLAG_HOMING | (isSlowMode(p) ? FLAG_SLOW : 0) | (fever ? FLAG_FEVER : 0));
        arrow.getPersistentDataContainer().set(ARROW_KEY, PersistentDataType.BYTE, flags);
        Fx.sound(p.getLocation(), Sound.ITEM_CROSSBOW_SHOOT, 0.7f, 1.4f);

        LivingEntity initial = acquireCone(p);
        if (initial != null) {
            initial.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, center(initial), 4, 0.2, 0.3, 0.2, 0.02);
        }
        final double speed = VOLLEY_SPEED;
        new BukkitRunnable() {
            int age = 0;
            LivingEntity target = initial;
            boolean hadTarget = initial != null;

            @Override
            public void run() {
                if (!arrow.isValid() || arrow.isInBlock() || arrow.isOnGround() || ++age > HOMING_LIFE) {
                    if (arrow.isValid()) arrow.remove();
                    cancel();
                    return;
                }
                if (target != null && (!target.isValid() || target.isDead() || target.getWorld() != arrow.getWorld())) {
                    target = null;
                }
                // 고정 타겟이 죽으면 다음 가까운 적으로 재지정 (처음부터 타겟이 없던 화살은 직진)
                if (target == null && hadTarget) target = reacquire(p, arrow);
                if (target == null) return;

                Location aLoc = arrow.getLocation();
                Vector to = center(target).toVector().subtract(aLoc.toVector());
                double dist = to.length();
                if (dist < 1e-3) return;
                Vector cur = arrow.getVelocity();
                if (cur.lengthSquared() < 1e-6) cur = to.clone();
                cur.normalize();
                Vector want = to.multiply(1.0 / dist);

                double t;
                if (dist <= speed * 1.5) {
                    t = 1.0; // 바로 앞이면 정확히 조준
                } else {
                    double close = 1.0 - Math.min(dist, HOMING_RAMP_DIST) / HOMING_RAMP_DIST; // 0(멀다) ~ 1(가깝다)
                    t = HOMING_TURN_MIN + (HOMING_TURN_MAX - HOMING_TURN_MIN) * close;
                }
                Vector steer = cur.multiply(1 - t).add(want.multiply(t));
                if (steer.lengthSquared() < 1e-6) steer = want;
                arrow.setVelocity(steer.normalize().multiply(speed));
                arrow.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, aLoc, 1, 0, 0, 0, 0);
            }
        }.runTaskTimer(DeruyRPG.get(), 1L, 1L);
    }

    private static Location center(LivingEntity e) {
        return e.getLocation().add(0, e.getHeight() * 0.5, 0);
    }

    /** 발사 시점: 시선 원뿔(±CONE) 안에서 시선에 가장 가까운 적. 블록 뒤 적은 후순위. */
    private @Nullable LivingEntity acquireCone(Player p) {
        Location eye = p.getEyeLocation();
        Vector look = eye.getDirection().normalize();
        double minCos = Math.cos(Math.toRadians(HOMING_CONE_DEG));
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (LivingEntity e : Targets.enemiesAround(p, eye, HOMING_RANGE)) {
            Vector to = center(e).toVector().subtract(eye.toVector());
            double d = to.length();
            if (d < 1e-3) continue;
            double cos = to.dot(look) / d;
            if (cos < minCos) continue;
            double angle = Math.toDegrees(Math.acos(Math.min(1.0, cos)));
            double score = angle + d * 0.1;
            if (!p.hasLineOfSight(e)) score += OCCLUDED_PENALTY_DEG;
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    /** 비행 중 재지정: 화살 기준 가장 가까운 적. 블록 뒤 적은 후순위. */
    private @Nullable LivingEntity reacquire(Player p, Arrow arrow) {
        Location from = arrow.getLocation();
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (LivingEntity e : Targets.enemiesAround(p, from, REACQUIRE_RANGE)) {
            Vector to = center(e).toVector().subtract(from.toVector());
            double d = to.length();
            double score = d;
            if (d > 1e-3 && from.getWorld().rayTraceBlocks(from, to.clone().normalize(), d,
                    FluidCollisionMode.NEVER, true) != null) {
                score += OCCLUDED_PENALTY_BLOCKS;
            }
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    @Override
    public @Nullable Component hudExtra(SkillContext ctx) {
        Player p = ctx.player();
        if (ctx.isFever()) return Component.text("유도 연사", NamedTextColor.GOLD);
        Integer end = slowUntil.get(p.getUniqueId());
        if (end != null && end > Bukkit.getCurrentTick()) {
            int sec = (int) Math.ceil((end - Bukkit.getCurrentTick()) / 20.0);
            return Component.text("구속 화살 " + sec + "s", NamedTextColor.AQUA);
        }
        return null;
    }

    @Override
    public void onUnequip(Player player) {
        // 여러 번 불려도 안전 (맵 제거만). 피버 연사 간격 상태 정리 — 피버가 남아 있으면 다시 들었을 때 우클릭으로 재개.
        lastShot.remove(player.getUniqueId());
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
        // 속도 4배 + 피해 보정
        proj.setVelocity(proj.getVelocity().multiply(SPEED_MULT));
        if (proj instanceof org.bukkit.entity.AbstractArrow aa) aa.setDamage(aa.getDamage() / SPEED_MULT);
        // 이벤트 시점에 속도가 아직 0이면 다음 틱에 재정렬
        if (proj.getVelocity().lengthSquared() < 1e-4) {
            Vector look = p.getEyeLocation().getDirection();
            Bukkit.getScheduler().runTask(DeruyRPG.get(), () -> {
                if (proj.isValid()) proj.setVelocity(look.clone().multiply(3.15 * SPEED_MULT));
            });
        }

        byte flags = FLAG_KIT;
        if (isSlowMode(p)) flags |= FLAG_SLOW;
        if (DeruyRPG.get().fever().isFever(p, id())) flags |= FLAG_FEVER; // 이 쇠뇌 피버만, 발사 시점 기록
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

    /** 피버 중 화살 피해 +25%. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArrowDamageFever(EntityDamageByEntityEvent e) {
        if (Damage.isProbe()) return;
        if (!(e.getDamager() instanceof Projectile proj)) return;
        byte f = flags(proj);
        if ((f & FLAG_KIT) == 0 || (f & FLAG_FEVER) == 0) return;
        if (!(proj.getShooter() instanceof Player p)) return;
        if (!(e.getEntity() instanceof LivingEntity target) || !Targets.isEnemy(p, target)) return;
        e.setDamage(e.getDamage() * FEVER_DAMAGE_MULT);
    }

    /** 구속 화살 명중 (피버 중엔 모든 화살) + 피버 넉백. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArrowDamage(EntityDamageByEntityEvent e) {
        if (Damage.isProbe()) return;
        if (!(e.getDamager() instanceof Projectile proj)) return;
        byte f = flags(proj);
        if ((f & FLAG_KIT) == 0) return;
        if (!(proj.getShooter() instanceof Player p)) return;
        if (!(e.getEntity() instanceof LivingEntity target) || !Targets.isEnemy(p, target)) return;
        boolean fever = (f & FLAG_FEVER) != 0;
        if (fever) {
            Vector push = proj.getVelocity().setY(0);
            if (push.lengthSquared() < 1e-6) push = target.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
            if (push.lengthSquared() > 1e-6) {
                Vector kb = push.normalize().multiply(FEVER_KNOCKBACK).setY(0.08);
                Bukkit.getScheduler().runTask(DeruyRPG.get(), () -> {
                    if (target.isValid() && !target.isDead()) target.setVelocity(target.getVelocity().add(kb));
                });
            }
        }
        if ((f & FLAG_SLOW) == 0 && !fever) return;
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, SLOW_EFFECT_TICKS, SLOW_AMPLIFIER, false, true, true));
        target.getWorld().spawnParticle(Particle.SNOWFLAKE, target.getLocation().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.02);
        Fx.sound(target.getLocation(), Sound.BLOCK_POWDER_SNOW_BREAK, 1f, 0.8f);
    }
}
