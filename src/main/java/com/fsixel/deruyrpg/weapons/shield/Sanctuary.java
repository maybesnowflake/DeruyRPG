package com.fsixel.deruyrpg.weapons.shield;

import com.fsixel.deruyrpg.DeruyRPG;
import com.fsixel.deruyrpg.util.Fx;
import com.fsixel.deruyrpg.util.Targets;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.EntityEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Trident;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** F 성역: 시전 지점에 고정된 방패 돔 (8초). */
final class Sanctuary {

    static final double RADIUS = 4.0;
    static final int DURATION = 160;
    static final double BLIND_RADIUS = 7.0;
    static final int MAX_REVIVES = 2;
    static final int SHIELD_COUNT = 12;
    static final double PUSH_RADIUS = RADIUS + 2.0;
    /** 방패 고리 회전 속도 (라디안/틱). */
    private static final double SPIN = 0.03;

    private static final NamespacedKey KB_KEY = new NamespacedKey("deruyrpg", "shield_unstoppable");
    private static final NamespacedKey EXPLOSION_KB_KEY = new NamespacedKey("deruyrpg", "shield_unstoppable_explosion");
    private static final List<PotionEffectType> CC = List.of(
            PotionEffectType.LEVITATION, PotionEffectType.BLINDNESS, PotionEffectType.DARKNESS,
            PotionEffectType.NAUSEA, PotionEffectType.WEAKNESS, PotionEffectType.MINING_FATIGUE);

    private final UUID owner;
    private final Location center;
    private final Map<UUID, Boolean> projectileInside = new HashMap<>();
    private final List<ItemDisplay> shields = new ArrayList<>();
    private final Map<UUID, OwnedRegen> reviveRegen = new HashMap<>();
    private final Runnable onEnd;
    private BukkitTask task;
    private int ticks;
    private int revivesLeft = MAX_REVIVES;
    private boolean ended;

    Sanctuary(Player owner, Runnable onEnd) {
        this.owner = owner.getUniqueId();
        this.center = owner.getLocation().clone();
        this.onEnd = onEnd;
    }

    UUID owner() { return owner; }

    void start(DeruyRPG plugin) {
        Player p = Bukkit.getPlayer(owner);
        if (p == null) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, DURATION, 1, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, DURATION, 0, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, DURATION, 0, false, false));
        addModifier(p, Attribute.KNOCKBACK_RESISTANCE, KB_KEY);
        addModifier(p, Attribute.EXPLOSION_KNOCKBACK_RESISTANCE, EXPLOSION_KB_KEY);
        clearCc(p);

        Fx.sound(center, Sound.ITEM_SHIELD_BLOCK, 1f, 0.6f);
        Fx.sound(center, Sound.ENTITY_IRON_GOLEM_REPAIR, 1f, 0.8f);
        Fx.sphere(center, Particle.END_ROD, RADIUS, 220);
        spawnShields();
        repel(p);
        p.sendActionBar(Component.text("성역 전개!", NamedTextColor.AQUA));

        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void tick() {
        Player p = Bukkit.getPlayer(owner);
        if (p == null || !p.isOnline() || p.isDead() || !p.getWorld().equals(center.getWorld()) || ++ticks > DURATION) {
            end();
            return;
        }
        if (ticks % 2 == 0) spinShields();
        if (ticks % 4 == 0) {
            Fx.sphere(center, Particle.END_ROD, RADIUS, 110);
            Fx.ring(center, Particle.ENCHANT, RADIUS, 32);
        }
        if (ticks % 5 == 0) clearCc(p);
        if (ticks % 10 == 0) {
            for (LivingEntity e : Targets.enemiesAround(p, p.getLocation(), BLIND_RADIUS)) {
                e.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, false, false));
            }
        }
        blockProjectiles(p);
    }

    /** 돔 밖에서 안으로 들어오는 투사체 차단. */
    private void blockProjectiles(Player p) {
        double r2 = RADIUS * RADIUS;
        double scan = RADIUS + 5;
        for (Entity en : center.getWorld().getNearbyEntities(center, scan, scan, scan)) {
            if (!(en instanceof Projectile pr)) continue;
            if (pr instanceof AbstractArrow a && a.isInBlock()) continue;
            boolean inside = pr.getLocation().distanceSquared(center) <= r2;
            Boolean was = projectileInside.put(pr.getUniqueId(), inside);
            if (!inside) continue;
            boolean entered = was != null
                    ? !was
                    : pr.getLocation().clone().subtract(pr.getVelocity()).distanceSquared(center) > r2;
            if (!entered || isFriendly(p, pr)) continue;
            deflect(pr);
        }
    }

    boolean isFriendly(Player p, Projectile pr) {
        return pr.getShooter() instanceof Player sp && Targets.isAlly(p, sp);
    }

    void deflect(Projectile pr) {
        Location at = pr.getLocation();
        at.getWorld().spawnParticle(Particle.ENCHANTED_HIT, at, 12, 0.15, 0.15, 0.15, 0.1);
        Fx.sound(at, Sound.ITEM_SHIELD_BLOCK, 0.8f, 1.3f);
        projectileInside.remove(pr.getUniqueId());
        if (pr instanceof Trident) {
            // 삼지창은 지우면 주인이 잃으므로 튕겨냄
            Vector out = at.toVector().subtract(center.toVector());
            if (out.lengthSquared() < 1e-6) out = new Vector(0, 1, 0);
            pr.setVelocity(out.normalize().multiply(0.6).setY(0.3));
        } else {
            pr.remove();
        }
    }

    boolean contains(Location loc) {
        return loc.getWorld().equals(center.getWorld()) && loc.distanceSquared(center) <= RADIUS * RADIUS;
    }

    boolean isActive() { return !ended; }

    /** 돔 안 아군이 치명 피해를 받을 때. 부활시켰으면 true. */
    boolean tryRevive(Player ally) {
        if (ended || revivesLeft <= 0) return false;
        Player p = Bukkit.getPlayer(owner);
        if (p == null || !Targets.isAlly(p, ally) || !contains(ally.getLocation())) return false;
        revivesLeft--;
        AttributeInstance max = ally.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = max != null ? max.getValue() : 20.0;
        ally.setHealth(Math.min(maxHp, Math.max(8.0, maxHp * 0.4)));
        ally.setFireTicks(0);
        OwnedRegen g = OwnedRegen.apply(ally, 100, 1, false, true);
        if (g != null) reviveRegen.put(ally.getUniqueId(), g);
        ally.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 0, false, true));
        ally.playEffect(EntityEffect.TOTEM_RESURRECT);
        Location l = ally.getLocation().add(0, 1, 0);
        l.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, l, 60, 0.5, 0.8, 0.5, 0.3);
        Fx.sound(l, Sound.ITEM_TOTEM_USE, 1f, 1f);
        ally.sendActionBar(Component.text("성역이 당신을 지켰다!", NamedTextColor.GOLD));
        if (!ally.equals(p)) {
            p.sendActionBar(Component.text("성역 부활 (" + revivesLeft + "회 남음)", NamedTextColor.GOLD));
        }
        return true;
    }

    void end() {
        if (ended) return;
        ended = true;
        if (task != null) task.cancel();
        projectileInside.clear();
        for (ItemDisplay d : shields) if (d.isValid()) d.remove();
        shields.clear();
        // 성역이 건 재생은 궁극기 종료와 함께 제거
        for (Map.Entry<UUID, OwnedRegen> en : reviveRegen.entrySet()) {
            Entity e = Bukkit.getEntity(en.getKey());
            if (e instanceof LivingEntity le) en.getValue().strip(le);
        }
        reviveRegen.clear();
        Player p = Bukkit.getPlayer(owner);
        if (p != null) {
            removeModifier(p, Attribute.KNOCKBACK_RESISTANCE, KB_KEY);
            removeModifier(p, Attribute.EXPLOSION_KNOCKBACK_RESISTANCE, EXPLOSION_KB_KEY);
        }
        Fx.sound(center, Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 1.2f);
        onEnd.run();
    }

    /** 발동 시 주변 적을 돔 바깥으로 밀쳐냄. */
    private void repel(Player p) {
        for (LivingEntity e : Targets.enemiesAround(p, center, PUSH_RADIUS)) {
            double d = e.getLocation().distance(center);
            double strength = 1.0 + Math.max(0, (RADIUS - d)) * 0.25;
            Targets.push(e, center, strength, 0.45);
            Location l = e.getLocation().add(0, 1, 0);
            l.getWorld().spawnParticle(Particle.CLOUD, l, 6, 0.2, 0.3, 0.2, 0.05);
        }
        center.getWorld().spawnParticle(Particle.EXPLOSION, center.clone().add(0, 0.5, 0), 1);
        Fx.ring(center, Particle.SWEEP_ATTACK, RADIUS, 16);
        Fx.sound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.6f);
    }

    /** 방패 ItemDisplay 를 돔 가장자리에 원형으로 배치. */
    private void spawnShields() {
        for (int i = 0; i < SHIELD_COUNT; i++) {
            Location at = shieldLoc(i, 0);
            ItemDisplay d = center.getWorld().spawn(at, ItemDisplay.class, e -> {
                e.setItemStack(new ItemStack(Material.SHIELD));
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                e.setPersistent(false);
                e.setRotation(at.getYaw(), 0);
                e.setTeleportDuration(2);
                e.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                        new Vector3f(1.6f, 1.6f, 1.6f), new Quaternionf(new AxisAngle4f())));
                Targets.markSummon(e);
            });
            shields.add(d);
        }
    }

    private void spinShields() {
        for (int i = 0; i < shields.size(); i++) {
            ItemDisplay d = shields.get(i);
            if (d.isValid()) d.teleport(shieldLoc(i, ticks * SPIN));
        }
    }

    /** i 번째 방패 위치: 중심에서 바깥을 바라보도록 yaw 설정. */
    private Location shieldLoc(int i, double offset) {
        double a = offset + (Math.PI * 2 * i) / SHIELD_COUNT;
        double x = Math.cos(a) * RADIUS;
        double z = Math.sin(a) * RADIUS;
        Location l = center.clone().add(x, 1.1, z);
        // 바깥 방향 (x, z) 를 바라보는 yaw
        float yaw = (float) Math.toDegrees(Math.atan2(-x, z));
        l.setYaw(yaw);
        l.setPitch(0);
        return l;
    }

    private static void clearCc(Player p) {
        for (PotionEffectType t : CC) p.removePotionEffect(t);
    }

    private static void addModifier(Player p, Attribute attr, NamespacedKey key) {
        AttributeInstance inst = p.getAttribute(attr);
        if (inst == null) return;
        inst.removeModifier(key);
        inst.addTransientModifier(new AttributeModifier(key, 1.0, AttributeModifier.Operation.ADD_NUMBER));
    }

    private static void removeModifier(Player p, Attribute attr, NamespacedKey key) {
        AttributeInstance inst = p.getAttribute(attr);
        if (inst != null) inst.removeModifier(key);
    }

    /** 플러그인 재시작 등으로 남은 수정치 정리용. */
    static void cleanup(Player p) {
        removeModifier(p, Attribute.KNOCKBACK_RESISTANCE, KB_KEY);
        removeModifier(p, Attribute.EXPLOSION_KNOCKBACK_RESISTANCE, EXPLOSION_KB_KEY);
    }
}
