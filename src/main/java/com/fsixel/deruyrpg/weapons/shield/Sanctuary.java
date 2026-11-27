package com.fsixel.deruyrpg.weapons.shield;

import com.fsixel.deruyrpg.DeruyRPG;
import com.fsixel.deruyrpg.core.HudTask;
import com.fsixel.deruyrpg.util.Damage;
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
    static final int SHIELD_COUNT = 24;
    static final double PUSH_RADIUS = RADIUS + 2.0;
    /** 방패 고리 회전 속도 (라디안/틱). */
    private static final double SPIN = 0.06;
    /** 돔 안으로 들어오는 적에게 주는 피해(고정) / 같은 적 재적용 간격(틱). */
    static final double ENTRY_DAMAGE = 3.0;
    static final int ENTRY_HIT_GAP = 20;
    /** 시전자 이동 속도 감소 (-30%, attribute — 정화·CC 면역에 지워지지 않음). */
    static final double CASTER_SLOW = -0.3;

    private static final NamespacedKey KB_KEY = new NamespacedKey("deruyrpg", "shield_unstoppable");
    private static final NamespacedKey EXPLOSION_KB_KEY = new NamespacedKey("deruyrpg", "shield_unstoppable_explosion");
    private static final NamespacedKey SLOW_KEY = new NamespacedKey("deruyrpg", "shield_sanctuary_slow");
    private static final List<PotionEffectType> CC = List.of(
            PotionEffectType.LEVITATION, PotionEffectType.BLINDNESS, PotionEffectType.DARKNESS,
            PotionEffectType.NAUSEA, PotionEffectType.WEAKNESS, PotionEffectType.MINING_FATIGUE);

    /** 투사체 추적 범위: 한 틱에 ~14칸까지 움직이는 투사체도 돔을 통과하기 전후로 한 번은 보이게. */
    private static final double PROJECTILE_SCAN = RADIUS + 14;
    /** 성역 구 파티클: 점 수 / 점마다 입자 수 / 퍼짐 (패킷 수 = 점 수). */
    private static final int DOME_POINTS = 55;
    private static final int DOME_POINT_COUNT = 2;
    private static final double DOME_SPREAD = 0.18;

    private final UUID owner;
    private final Location center;
    /** 지난 틱에 본 투사체 위치 (이번 틱 이동 선분의 시작점). */
    private Map<UUID, Vector> projectilePrev = new HashMap<>();
    private Map<UUID, Boolean> enemyInside = new HashMap<>();
    private final Map<UUID, Integer> enemyLastHit = new HashMap<>();
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
        addSlow(p);
        clearCc(p);

        Fx.sound(center, Sound.ITEM_SHIELD_BLOCK, 1f, 0.6f);
        Fx.sound(center, Sound.ENTITY_IRON_GOLEM_REPAIR, 1f, 0.8f);
        domeParticles(DOME_POINTS * 2);
        spawnShields();
        repel(p);
        HudTask.flash(p, Component.text("성역 전개!", NamedTextColor.AQUA), 30);

        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void tick() {
        Player p = Bukkit.getPlayer(owner);
        if (p == null || !p.isOnline() || p.isDead() || !p.getWorld().equals(center.getWorld()) || ++ticks > DURATION) {
            end();
            return;
        }
        spinShields();
        if (ticks % 4 == 0) {
            domeParticles(DOME_POINTS);
            ringParticles(16);
        }
        if (ticks % 5 == 0) clearCc(p);
        if (ticks % 10 == 0) {
            for (LivingEntity e : Targets.enemiesAround(p, p.getLocation(), BLIND_RADIUS)) {
                if (!Damage.canHarm(p, e)) continue;
                e.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, false, false));
            }
        }
        blockProjectiles(p);
        hitEntering(p);
    }

    /** 돔 밖 → 안으로 들어오는 적: 약한 피해 + 바깥으로 튕김 (같은 적은 ENTRY_HIT_GAP 간격). */
    private void hitEntering(Player p) {
        double r2 = RADIUS * RADIUS;
        Map<UUID, Boolean> now = new HashMap<>();
        for (LivingEntity e : Targets.enemiesAround(p, center, RADIUS + 3)) {
            UUID id = e.getUniqueId();
            boolean inside = e.getLocation().distanceSquared(center) <= r2;
            now.put(id, inside);
            Boolean was = enemyInside.get(id);
            if (!inside || was == null || was) continue;
            Integer last = enemyLastHit.get(id);
            if (last != null && ticks - last < ENTRY_HIT_GAP) continue;
            enemyLastHit.put(id, ticks);
            if (!Damage.skill(p, e, ENTRY_DAMAGE)) continue; // 피해가 막히면 튕김도 없음
            Targets.push(e, center, 0.7, 0.3);
            Location l = e.getLocation().add(0, 1, 0);
            l.getWorld().spawnParticle(Particle.ENCHANTED_HIT, l, 10, 0.3, 0.4, 0.3, 0.1);
            Fx.sound(l, Sound.ITEM_SHIELD_BLOCK, 0.7f, 0.9f);
        }
        enemyInside = now;
        if (ticks % 40 == 0) enemyLastHit.values().removeIf(t -> ticks - t >= ENTRY_HIT_GAP);
    }

    /**
     * 돔 밖에서 들어오는 투사체 차단. 지난 틱 위치 → 현재 위치 선분이 돔 구와 교차하면 진입으로 판정
     * (한 틱에 돔을 통째로 가로지르는 고속 투사체 포함). 돔 안에서 쏜 것·아군 투사체는 통과.
     */
    private void blockProjectiles(Player p) {
        Map<UUID, Vector> now = new HashMap<>();
        for (Entity en : center.getWorld().getNearbyEntities(center, PROJECTILE_SCAN, PROJECTILE_SCAN, PROJECTILE_SCAN)) {
            if (!(en instanceof Projectile pr) || !pr.isValid()) continue;
            if (pr instanceof AbstractArrow a && a.isInBlock()) continue;
            Vector cur = pr.getLocation().toVector();
            Vector start = segmentStart(pr, projectilePrev);
            now.put(pr.getUniqueId(), cur);
            if (start == null || isFriendly(p, pr)) continue;
            Vector entry = entryPoint(start, cur);
            if (entry == null) continue;
            now.remove(pr.getUniqueId());
            deflect(pr, entry);
        }
        projectilePrev = now;
    }

    /** 이번 틱 이동 선분의 시작점. 돔 안에서 발사된 처음 보는 투사체는 null(차단 안 함). */
    private Vector segmentStart(Projectile pr, Map<UUID, Vector> prev) {
        Vector known = prev.get(pr.getUniqueId());
        if (known != null) return known;
        Location origin = pr.getOrigin();
        if (origin != null && contains(origin)) return null;
        return pr.getLocation().toVector().subtract(pr.getVelocity());
    }

    /** start 가 돔 밖이고 start→end 선분이 돔 구와 만나면 첫 교차점, 아니면 null. */
    Vector entryPoint(Vector start, Vector end) {
        Vector c = center.toVector();
        double r2 = RADIUS * RADIUS;
        Vector f = start.clone().subtract(c);
        double cc = f.lengthSquared() - r2;
        if (cc <= 0) return null; // 시작이 돔 안 → 진입 아님
        Vector d = end.clone().subtract(start);
        double a = d.lengthSquared();
        if (a < 1e-9) return null;
        double b = 2 * f.dot(d);
        double disc = b * b - 4 * a * cc;
        if (disc < 0) return null;
        double t = (-b - Math.sqrt(disc)) / (2 * a);
        if (t < 0 || t > 1) return null;
        return start.clone().add(d.multiply(t));
    }

    /**
     * 투사체가 엔티티에 맞을 때(같은 틱에 돔을 뚫고 맞히는 경우): 돔 밖에서 출발해 돔을 지나왔으면 차단.
     * 지난 틱 위치(없으면 발사 지점/현재-속도) → 맞은 대상 중심 선분으로 판정.
     */
    boolean blockOnHit(Player p, Projectile pr, Entity hit) {
        if (ended || isFriendly(p, pr) || !pr.getWorld().equals(center.getWorld())) return false;
        Vector start = segmentStart(pr, projectilePrev);
        if (start == null) return false;
        Vector end = hit.getBoundingBox().getCenter();
        Vector entry = entryPoint(start, end);
        if (entry == null) return false;
        projectilePrev.remove(pr.getUniqueId());
        deflect(pr, entry);
        return true;
    }

    boolean isFriendly(Player p, Projectile pr) {
        return pr.getShooter() instanceof Player sp && Targets.isAlly(p, sp);
    }

    /** 돔 표면(entry)에서 막힘 연출. 삼지창은 지우면 주인이 잃으므로 튕겨내고, 나머지는 제거. */
    private void deflect(Projectile pr, Vector entry) {
        Location at = entry.toLocation(center.getWorld());
        at.getWorld().spawnParticle(Particle.ENCHANTED_HIT, at, 12, 0.15, 0.15, 0.15, 0.1);
        Fx.sound(at, Sound.ITEM_SHIELD_BLOCK, 0.8f, 1.3f);
        if (pr instanceof Trident) {
            Vector out = entry.clone().subtract(center.toVector());
            if (out.lengthSquared() < 1e-6) out = new Vector(0, 1, 0);
            out.normalize();
            Location back = entry.clone().add(out.clone().multiply(0.3)).toLocation(center.getWorld());
            back.setYaw(pr.getLocation().getYaw());
            back.setPitch(pr.getLocation().getPitch());
            pr.teleport(back);
            pr.setVelocity(out.multiply(0.6).setY(0.3));
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
        HudTask.flash(ally, Component.text("성역이 당신을 지켰다!", NamedTextColor.GOLD), 40);
        if (!ally.equals(p)) {
            HudTask.flash(p, Component.text("성역 부활 (" + revivesLeft + "회 남음)", NamedTextColor.GOLD), 40);
        }
        return true;
    }

    void end() {
        end(false);
    }

    /** @param stripBuffs true 면 시전자에게 건 성역 포션(저항 I·흡수 II·발광)도 즉시 제거 (플러그인 종료·리로드용). */
    void end(boolean stripBuffs) {
        if (ended) return;
        ended = true;
        if (task != null) task.cancel();
        projectilePrev.clear();
        enemyInside.clear();
        enemyLastHit.clear();
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
            removeModifier(p, Attribute.MOVEMENT_SPEED, SLOW_KEY);
            if (stripBuffs) {
                int left = DURATION - ticks + 2;
                removeOwned(p, PotionEffectType.RESISTANCE, 0, left);
                removeOwned(p, PotionEffectType.ABSORPTION, 1, left);
                removeOwned(p, PotionEffectType.GLOWING, 0, left);
            }
        }
        Fx.sound(center, Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 1.2f);
        onEnd.run();
    }

    /** 발동 시 주변 적을 돔 바깥으로 밀쳐냄. */
    private void repel(Player p) {
        for (LivingEntity e : Targets.enemiesAround(p, center, PUSH_RADIUS)) {
            if (!Damage.canHarm(p, e)) continue;
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
                e.setTeleportDuration(1);
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

    /** 피버 버프가 해제될 때(무기 놓기) 덮여 있던 성역 버프를 남은 시간만큼 다시 건다. 흡수량은 늘리지 않음. */
    void restoreOwnerBuffs(Player p, double absorbCap) {
        int left = DURATION - ticks;
        if (ended || left <= 0) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, left, 0, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, left, 1, false, true));
        p.setAbsorptionAmount(Math.min(p.getAbsorptionAmount(), absorbCap));
    }

    /** 성역이 건 것으로 보이는 효과(같은 단계, 남은 시간 이하)만 제거. */
    private static void removeOwned(Player p, PotionEffectType type, int amp, int maxLeft) {
        PotionEffect cur = p.getPotionEffect(type);
        if (cur != null && cur.getAmplifier() == amp && cur.getDuration() <= maxLeft) p.removePotionEffect(type);
    }

    /** 돔 구 파티클 — 점마다 입자 여러 개를 한 패킷으로 묶어 보냄. */
    private void domeParticles(int points) {
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < points; i++) {
            double y = 1 - (i / (double) (points - 1)) * 2;
            double r = Math.sqrt(1 - y * y);
            double t = golden * i + ticks * 0.15; // 매번 조금씩 돌려 빈틈이 고정되지 않게
            Location l = center.clone().add(Math.cos(t) * r * RADIUS, y * RADIUS, Math.sin(t) * r * RADIUS);
            center.getWorld().spawnParticle(Particle.END_ROD, l, DOME_POINT_COUNT, DOME_SPREAD, DOME_SPREAD, DOME_SPREAD, 0);
        }
    }

    private void ringParticles(int points) {
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            Location l = center.clone().add(Math.cos(a) * RADIUS, 0.1, Math.sin(a) * RADIUS);
            center.getWorld().spawnParticle(Particle.ENCHANT, l, 2, 0.2, 0, 0.2, 0);
        }
    }

    private static void addSlow(Player p) {
        AttributeInstance inst = p.getAttribute(Attribute.MOVEMENT_SPEED);
        if (inst == null) return;
        inst.removeModifier(SLOW_KEY);
        inst.addTransientModifier(new AttributeModifier(SLOW_KEY, CASTER_SLOW, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
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
        removeModifier(p, Attribute.MOVEMENT_SPEED, SLOW_KEY);
    }
}
