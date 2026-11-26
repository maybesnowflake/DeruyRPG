package com.fsixel.deruyrpg.weapons.trident;

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
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 삼지창 — 스킬 명세: docs/SKILLS.md 의 "삼지창" 항목.
 *
 * P  물의 가호   : 나/적이 물·비에 닿아 있으면 근접(+투척) 추가 피해, 물속 공격 속도 증가, 물보라.
 * 우 투척/급류   : 바닐라. 투척은 시선 방향 직진. 비 소환 중엔 땅에서도 급류.
 * 좌 찌르기      : 사거리 4칸(아이템 modifier). 비 속이면 물살 찌르기(전방 6칸 관통 물줄기).
 * ⇧우 물기둥     : 전방 물기둥(파티클) → 범위 적 띄우기 + 피해.
 * F  뇌우+비 소환 : 피버 동안 비 파티클이 따라다니고 주변 적에게 번개. 나는 항상 젖은 상태.
 */
public final class TridentKit implements WeaponKit, Listener {

    // ---- 수치 ----
    private static final double REACH_BONUS = 1.0;          // 기본 3 → 4칸
    private static final double WET_BONUS = 3.0;            // 물의 가호 근접 추가 피해
    private static final double WATER_ATTACK_SPEED = 0.5;   // 물속 공격 속도 +0.5 (1.1 → 1.6)

    private static final int STREAM_COOLDOWN = 12;          // 물살 찌르기 0.6초
    private static final double STREAM_RANGE = 6.0;
    private static final double STREAM_WIDTH = 1.0;
    private static final double STREAM_DAMAGE = 4.0;

    private static final int COLUMN_COOLDOWN = 160;         // 물기둥 8초
    private static final double COLUMN_DISTANCE = 3.5;
    private static final double COLUMN_RADIUS = 2.0;
    private static final double COLUMN_HEIGHT = 4.5;
    private static final double COLUMN_DAMAGE = 5.0;

    private static final int RIPTIDE_COOLDOWN = 20;         // 비 소환 중 땅 급류 1초

    private static final int STORM_TICKS = 200;             // 피버 10초
    private static final double STORM_RADIUS = 7.0;         // 비 파티클 / 젖음 판정 반경
    private static final double LIGHTNING_RADIUS = 9.0;
    private static final int LIGHTNING_INTERVAL = 20;       // 1초마다
    private static final int LIGHTNING_TARGETS = 2;
    private static final double LIGHTNING_DAMAGE = 5.0;

    private static final NamespacedKey REACH_KEY = new NamespacedKey("deruyrpg", "trident_reach");
    private static final NamespacedKey WATER_SPEED_KEY = new NamespacedKey("deruyrpg", "trident_water_speed");

    // ---- 상태 ----
    private final Map<UUID, BukkitTask> storms = new ConcurrentHashMap<>();
    private final Set<UUID> waterSpeed = ConcurrentHashMap.newKeySet();
    private BukkitTask cleanupTask;

    @Override
    public String id() {
        return "trident";
    }

    @Override
    public String displayName() {
        return "삼지창";
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = Items.weapon(Material.TRIDENT, id(), "삼지창", NamedTextColor.AQUA,
                "P  물의 가호 (물·비에 젖으면 추가 피해)",
                "우  투척 / 급류 (직진 투척)",
                "좌  찌르기 4칸 (비 속: 물살 찌르기)",
                "⇧우  물기둥",
                "F  뇌우 + 비 소환");
        // 아이템 modifier 를 하나라도 넣으면 바닐라 기본값이 사라지므로 삼지창 기본 공격력·속도를 다시 넣는다.
        item.editMeta(meta -> {
            meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(
                    NamespacedKey.minecraft("base_attack_damage"), 8.0,
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
            meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(
                    NamespacedKey.minecraft("base_attack_speed"), -2.9,
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
            meta.addAttributeModifier(Attribute.ENTITY_INTERACTION_RANGE, new AttributeModifier(
                    REACH_KEY, REACH_BONUS, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        });
        return item;
    }

    @Override
    public int feverDurationTicks() {
        return STORM_TICKS;
    }

    @Override
    public String[] cooldownSlots() {
        return new String[]{Slot.LEFT, Slot.SHIFT_RIGHT, Slot.RIGHT};
    }

    // =====================================================================
    // 판정 도우미
    // =====================================================================

    private boolean isTrident(ItemStack item) {
        return id().equals(Items.kitId(item));
    }

    private boolean inStorm(Player p) {
        return storms.containsKey(p.getUniqueId());
    }

    /** 나: 물/비/거품기둥 또는 궁극기 중. */
    private boolean isWet(Player p) {
        return inStorm(p) || p.isInWaterOrRainOrBubbleColumn();
    }

    /** 적: 물/비/거품기둥, 또는 내 궁극기 비 범위 안. */
    private boolean isWet(Player owner, LivingEntity target) {
        if (target.isInWaterOrRainOrBubbleColumn()) return true;
        return inStorm(owner) && target.getWorld().equals(owner.getWorld())
                && target.getLocation().distanceSquared(owner.getLocation()) <= STORM_RADIUS * STORM_RADIUS;
    }

    /** "비 속" — 바닐라 비 또는 궁극기 중. */
    private boolean inRain(Player p) {
        return inStorm(p) || p.isInRain();
    }

    private static void splash(Location at) {
        World w = at.getWorld();
        w.spawnParticle(Particle.SPLASH, at, 25, 0.35, 0.35, 0.35, 0.1);
        w.spawnParticle(Particle.BUBBLE_POP, at, 6, 0.3, 0.3, 0.3, 0.02);
    }

    // =====================================================================
    // P 물의 가호
    // =====================================================================

    @Override
    public void onPassiveTick(SkillContext ctx) {
        Player p = ctx.player();
        boolean underwater = p.isInWaterOrBubbleColumn();
        if (underwater) addWaterSpeed(p);
        else removeWaterSpeed(p);

        if (isWet(p)) {
            p.getWorld().spawnParticle(Particle.DRIPPING_WATER, p.getLocation().add(0, 1.2, 0), 2, 0.3, 0.4, 0.3, 0);
        }
    }

    private void addWaterSpeed(Player p) {
        AttributeInstance attr = p.getAttribute(Attribute.ATTACK_SPEED);
        if (attr == null) return;
        if (attr.getModifier(WATER_SPEED_KEY) == null) {
            attr.addTransientModifier(new AttributeModifier(WATER_SPEED_KEY, WATER_ATTACK_SPEED,
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        }
        waterSpeed.add(p.getUniqueId());
        ensureCleanup();
    }

    private void removeWaterSpeed(Player p) {
        AttributeInstance attr = p.getAttribute(Attribute.ATTACK_SPEED);
        if (attr != null && attr.getModifier(WATER_SPEED_KEY) != null) attr.removeModifier(WATER_SPEED_KEY);
        waterSpeed.remove(p.getUniqueId());
    }

    /** 무기를 던지거나 인벤토리에서 옮기면 onUnequip 이 안 불리므로 주기적으로 정리. */
    private void ensureCleanup() {
        if (cleanupTask != null && !cleanupTask.isCancelled()) return;
        cleanupTask = Bukkit.getScheduler().runTaskTimer(DeruyRPG.get(), () -> {
            for (UUID id : new ArrayList<>(waterSpeed)) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) {
                    waterSpeed.remove(id);
                } else if (!isTrident(p.getInventory().getItemInMainHand()) || !p.isInWaterOrBubbleColumn()) {
                    removeWaterSpeed(p);
                }
            }
        }, 10L, 10L);
    }

    /** 근접 평타: 물의 가호 추가 피해 + 비 속이면 물살 찌르기. */
    @Override
    public void onMeleeHit(SkillContext ctx, LivingEntity target, EntityDamageByEntityEvent event) {
        Player p = ctx.player();
        double bonus = 0;
        if (isWet(p) || isWet(p, target)) {
            bonus += ctx.isFever() ? WET_BONUS + 1 : WET_BONUS;
            splash(target.getLocation().add(0, target.getHeight() * 0.6, 0));
            Fx.sound(target.getLocation(), Sound.ENTITY_GENERIC_SPLASH, 0.5f, 1.4f);
        }
        if (inRain(p) && ctx.tryCooldown(Slot.LEFT, STREAM_COOLDOWN)) {
            // 맞은 대상은 이 타격에 물줄기 피해를 합산 (무적 시간 때문에 따로 때리지 않음)
            bonus += waterStream(ctx, target);
        }
        if (bonus > 0) event.setDamage(event.getDamage() + bonus);
    }

    /** 허공 좌클릭: 비 속이면 물살 찌르기. */
    @Override
    public void onLeftClick(SkillContext ctx) {
        if (!inRain(ctx.player())) return;
        if (!ctx.tryCooldown(Slot.LEFT, STREAM_COOLDOWN)) return;
        waterStream(ctx, null);
    }

    /**
     * 전방 6칸 관통 물줄기. exclude 는 피해를 주지 않음(평타 대상).
     * @return 물줄기 피해량 (평타 대상 합산용)
     */
    private double waterStream(SkillContext ctx, LivingEntity exclude) {
        Player p = ctx.player();
        double dmg = ctx.isFever() ? STREAM_DAMAGE + 1.5 : STREAM_DAMAGE;
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        double len = STREAM_RANGE;
        RayTraceResult block = p.getWorld().rayTraceBlocks(eye, dir, STREAM_RANGE, FluidCollisionMode.NEVER, true);
        if (block != null) len = block.getHitPosition().distance(eye.toVector());

        Location start = eye.clone().add(dir.clone().multiply(0.6)).subtract(0, 0.25, 0);
        Vector startV = start.toVector();
        World w = p.getWorld();
        for (double d = 0; d <= len; d += 0.4) {
            Location l = start.clone().add(dir.clone().multiply(d));
            w.spawnParticle(Particle.SPLASH, l, 3, 0.08, 0.08, 0.08, 0.02);
            w.spawnParticle(Particle.BUBBLE_POP, l, 1, 0.05, 0.05, 0.05, 0.01);
            if (((int) (d * 10)) % 12 == 0) w.spawnParticle(Particle.FALLING_WATER, l, 2, 0.1, 0.1, 0.1, 0);
        }
        Fx.sound(eye, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.6f, 1.5f);
        Fx.sound(eye, Sound.ITEM_TRIDENT_THROW, 0.6f, 1.6f);

        Location mid = start.clone().add(dir.clone().multiply(len / 2));
        for (Entity e : w.getNearbyEntities(mid, len / 2 + 1.5, len / 2 + 1.5, len / 2 + 1.5)) {
            if (!Targets.isEnemy(p, e) || e.equals(exclude)) continue;
            LivingEntity le = (LivingEntity) e;
            Vector c = le.getBoundingBox().getCenter();
            Vector rel = c.clone().subtract(startV);
            double t = rel.dot(dir);
            if (t < -0.5 || t > len + 0.5) continue;
            double distSq = rel.clone().subtract(dir.clone().multiply(t)).lengthSquared();
            double allow = STREAM_WIDTH + le.getWidth() / 2;
            if (distSq > allow * allow) continue;
            Damage.skill(p, le, dmg);
            le.setVelocity(le.getVelocity().add(dir.clone().setY(0).multiply(0.35)));
            splash(c.toLocation(w));
        }
        return dmg;
    }

    /** 투척 삼지창 명중에도 물의 가호 적용 + 손이 비어 코어가 못 채우는 피버 게이지 보충. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTridentHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Trident t) || !isTrident(t.getItemStack())) return;
        if (!(t.getShooter() instanceof Player p) || !(e.getEntity() instanceof LivingEntity target)) return;
        if (!Targets.isEnemy(p, target)) return;
        if (isWet(p) || isWet(p, target)) {
            boolean fever = DeruyRPG.get().fever().isFever(p);
            e.setDamage(e.getDamage() + (fever ? WET_BONUS + 1 : WET_BONUS));
            splash(target.getLocation().add(0, target.getHeight() * 0.6, 0));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTridentHitMonitor(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Trident t) || !isTrident(t.getItemStack())) return;
        if (!(t.getShooter() instanceof Player p) || e.getFinalDamage() <= 0) return;
        // 주손에 RPG 무기가 있으면 코어가 이미 게이지를 채움
        if (DeruyRPG.get().kits().find(p.getInventory().getItemInMainHand()) != null) return;
        DeruyRPG.get().fever().onHit(p);
    }

    // =====================================================================
    // 우 투척 / 급류
    // =====================================================================

    /** 바닐라 투척/급류는 그대로. 비 소환 중 땅(물·비 밖)에서 급류 인챈트가 있으면 즉시 급류. */
    @Override
    public void onRightClick(SkillContext ctx) {
        Player p = ctx.player();
        int level = ctx.item().getEnchantmentLevel(Enchantment.RIPTIDE);
        if (level <= 0 || !inStorm(p) || p.isInWaterOrRain()) return; // 바닐라에 맡김
        ctx.cancelVanilla();
        if (!ctx.tryCooldown(Slot.RIGHT, RIPTIDE_COOLDOWN)) return;

        double power = 3.0 * (1.0 + level) / 4.0; // 바닐라 급류 공식
        Vector v = p.getEyeLocation().getDirection().normalize().multiply(power);
        if (p.isOnGround()) v.setY(Math.max(v.getY(), 0) + 0.45);
        p.setVelocity(v);
        p.startRiptideAttack(20, 8.0f, ctx.item());
        p.getWorld().spawnParticle(Particle.SPLASH, p.getLocation().add(0, 1, 0), 40, 0.4, 0.6, 0.4, 0.2);
        Fx.sound(p.getLocation(), Sound.ITEM_TRIDENT_THROW, 1f, 0.7f);
        Fx.sound(p.getLocation(), Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.8f, 1.0f);
    }

    /** 투척은 탄퍼짐 없이 시선 방향 직진. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof Trident t) || !isTrident(t.getItemStack())) return;
        if (!(t.getShooter() instanceof Player p)) return;
        double speed = t.getVelocity().length();
        t.setVelocity(p.getEyeLocation().getDirection().normalize().multiply(speed));
    }

    // =====================================================================
    // ⇧우 물기둥
    // =====================================================================

    @Override
    public void onShiftRightClick(SkillContext ctx) {
        ctx.cancelVanilla(); // 투척 차징 막기
        if (!ctx.tryCooldown(Slot.SHIFT_RIGHT, COLUMN_COOLDOWN)) return;
        Player p = ctx.player();
        boolean fever = ctx.isFever();
        double radius = fever ? COLUMN_RADIUS + 0.75 : COLUMN_RADIUS;
        double dmg = fever ? COLUMN_DAMAGE + 2 : COLUMN_DAMAGE;

        Location base = columnBase(p);
        Fx.sound(base, Sound.ITEM_BUCKET_EMPTY, 1f, 0.8f);
        Fx.sound(base, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_INSIDE, 1.2f, 1f);
        Fx.sound(base, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1f, 0.7f);

        Set<UUID> hit = new HashSet<>();
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t > 24 || !p.isOnline()) {
                    cancel();
                    return;
                }
                World w = base.getWorld();
                // 연출
                for (double y = 0; y <= COLUMN_HEIGHT; y += 0.5) {
                    double r = radius * 0.45;
                    w.spawnParticle(Particle.BUBBLE_COLUMN_UP, base.clone().add(0, y, 0), 4, r, 0.2, r, 0.05);
                    w.spawnParticle(Particle.SPLASH, base.clone().add(0, y, 0), 3, r, 0.2, r, 0.05);
                }
                w.spawnParticle(Particle.SPLASH, base.clone().add(0, COLUMN_HEIGHT, 0), 20, radius * 0.6, 0.2, radius * 0.6, 0.3);
                w.spawnParticle(Particle.FALLING_WATER, base.clone().add(0, COLUMN_HEIGHT, 0), 6, radius * 0.6, 0.1, radius * 0.6, 0);
                if (t % 4 == 0) Fx.ring(base, Particle.SPLASH, radius, 20);
                if (t % 8 == 0) Fx.sound(base, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 1f, 1f);

                // 판정: 처음 들어온 적 = 피해 + 띄움, 이후 기둥 안에 있으면 계속 떠오름
                for (Entity e : w.getNearbyEntities(base.clone().add(0, COLUMN_HEIGHT / 2, 0),
                        radius + 0.5, COLUMN_HEIGHT / 2 + 1, radius + 0.5)) {
                    if (!Targets.isEnemy(p, e)) continue;
                    LivingEntity le = (LivingEntity) e;
                    Location l = le.getLocation();
                    double dx = l.getX() - base.getX(), dz = l.getZ() - base.getZ();
                    double dy = l.getY() - base.getY();
                    if (dx * dx + dz * dz > radius * radius || dy < -1 || dy > COLUMN_HEIGHT) continue;
                    if (hit.add(le.getUniqueId())) {
                        Damage.skill(p, le, dmg);
                        le.setVelocity(new Vector(0, fever ? 1.25 : 1.05, 0));
                        splash(l.clone().add(0, 0.5, 0));
                    } else if (t <= 16 && le.getVelocity().getY() < 0.3) {
                        le.setVelocity(le.getVelocity().setY(0.35));
                    }
                }
                t += 2;
            }
        }.runTaskTimer(ctx.plugin(), 0L, 2L);
    }

    /** 전방 3.5칸 지점의 바닥. 벽이 있으면 벽 앞. */
    private Location columnBase(Player p) {
        Location feet = p.getLocation();
        Vector fwd = feet.getDirection().setY(0);
        if (fwd.lengthSquared() < 1e-6) fwd = new Vector(0, 0, 1);
        fwd.normalize();
        Location from = feet.clone().add(0, 0.5, 0);
        double dist = COLUMN_DISTANCE;
        RayTraceResult wall = p.getWorld().rayTraceBlocks(from, fwd, COLUMN_DISTANCE, FluidCollisionMode.NEVER, true);
        if (wall != null) dist = Math.max(0.5, wall.getHitPosition().distance(from.toVector()) - 0.5);
        Location base = from.clone().add(fwd.multiply(dist));
        RayTraceResult ground = p.getWorld().rayTraceBlocks(base, new Vector(0, -1, 0), 4, FluidCollisionMode.NEVER, true);
        if (ground != null) base.setY(ground.getHitPosition().getY());
        else base.setY(feet.getY());
        return base;
    }

    // =====================================================================
    // F 뇌우 + 비 소환
    // =====================================================================

    @Override
    public void onUltimate(SkillContext ctx) {
        Player p = ctx.player();
        stopStorm(p.getUniqueId());
        Fx.sound(p.getLocation(), Sound.ITEM_TRIDENT_THUNDER, 1f, 1f);
        Fx.sound(p.getLocation(), Sound.WEATHER_RAIN_ABOVE, 1f, 0.8f);
        p.sendActionBar(Component.text("뇌우 소환!", NamedTextColor.AQUA));

        UUID id = p.getUniqueId();
        BukkitTask task = new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t >= STORM_TICKS || !p.isOnline() || p.isDead()) {
                    stopStorm(id);
                    return;
                }
                Location c = p.getLocation();
                World w = c.getWorld();
                ThreadLocalRandom r = ThreadLocalRandom.current();

                // 비 (월드 날씨는 건드리지 않음)
                for (int i = 0; i < 22; i++) {
                    double a = r.nextDouble(Math.PI * 2), d = Math.sqrt(r.nextDouble()) * STORM_RADIUS;
                    Location l = c.clone().add(Math.cos(a) * d, 4 + r.nextDouble(3), Math.sin(a) * d);
                    w.spawnParticle(Particle.FALLING_WATER, l, 1, 0, 0, 0, 0);
                }
                for (int i = 0; i < 6; i++) {
                    double a = r.nextDouble(Math.PI * 2), d = Math.sqrt(r.nextDouble()) * STORM_RADIUS;
                    w.spawnParticle(Particle.RAIN, c.clone().add(Math.cos(a) * d, 0.1, Math.sin(a) * d), 2, 0.1, 0, 0.1, 0);
                }
                if (t % 4 == 0) {
                    for (int i = 0; i < 6; i++) {
                        double a = r.nextDouble(Math.PI * 2), d = Math.sqrt(r.nextDouble()) * STORM_RADIUS;
                        w.spawnParticle(Particle.CLOUD, c.clone().add(Math.cos(a) * d, 7, Math.sin(a) * d), 1, 0.3, 0.1, 0.3, 0);
                    }
                }
                if (t % 30 == 0) Fx.sound(c, Sound.WEATHER_RAIN, 0.8f, 1f);

                // 번개
                if (t % LIGHTNING_INTERVAL == 0) {
                    List<LivingEntity> enemies = Targets.enemiesAround(p, c, LIGHTNING_RADIUS);
                    enemies.sort(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(c)));
                    for (int i = 0; i < Math.min(LIGHTNING_TARGETS, enemies.size()); i++) {
                        LivingEntity e = enemies.get(i);
                        w.strikeLightningEffect(e.getLocation());
                        w.spawnParticle(Particle.ELECTRIC_SPARK, e.getLocation().add(0, 1, 0), 25, 0.4, 0.8, 0.4, 0.2);
                        Damage.magic(p, e, LIGHTNING_DAMAGE);
                    }
                }
                t++;
            }
        }.runTaskTimer(ctx.plugin(), 0L, 1L);
        storms.put(id, task);
    }

    private void stopStorm(UUID id) {
        BukkitTask task = storms.remove(id);
        if (task != null) task.cancel();
    }

    // =====================================================================
    // 정리
    // =====================================================================

    /** 손에서 빠지면 물속 공격 속도 해제. 비 소환은 피버(궁극기) 동안 유지. */
    @Override
    public void onUnequip(Player player) {
        removeWaterSpeed(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        removeWaterSpeed(e.getPlayer());
        stopStorm(e.getPlayer().getUniqueId());
    }
}
