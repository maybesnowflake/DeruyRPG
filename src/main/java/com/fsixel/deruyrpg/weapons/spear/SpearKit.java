package com.fsixel.deruyrpg.weapons.spear;

import com.fsixel.deruyrpg.api.SkillContext;
import com.fsixel.deruyrpg.api.WeaponKit;
import com.fsixel.deruyrpg.util.Damage;
import com.fsixel.deruyrpg.util.Fx;
import com.fsixel.deruyrpg.util.Items;
import com.fsixel.deruyrpg.util.Targets;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.AttackRange;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 창 — 스킬 명세: docs/SKILLS.md 의 "창" 항목.
 *
 * P  긴 창      : 리치 +2 (ENTITY_INTERACTION_RANGE + attack_range 컴포넌트). 2칸 이내 적 타격 시 추가 피해.
 * 우 연속 찌르기 : 3틱 간격 5회 전방 찌르기.
 * 좌 찌르기     : 바닐라 평타 (P 보너스만 적용).
 * ⇧우 큰 창     : 거대 ItemDisplay 창이 앞으로 뻗으며 3×5 관통.
 * F  고대 군단  : {@link AncientLegion}.
 */
public final class SpearKit implements WeaponKit {

    static final NamespacedKey REACH_KEY = new NamespacedKey("deruyrpg", "spear_reach");

    private static final double REACH_BONUS = 2.0;

    // 우 연속 찌르기
    private static final int JAB_COUNT = 5;
    private static final int JAB_INTERVAL = 3;
    private static final double JAB_DAMAGE = 2.0;
    private static final double JAB_LENGTH = 5.5;
    private static final int JAB_COOLDOWN = 100;

    // ⇧우 큰 창
    private static final double BIG_DAMAGE = 8.0;
    private static final int BIG_COOLDOWN = 200;

    /** 플레이어별 진행 중인 작업 (연속 찌르기 등) — onUnequip 에서 정리. */
    private final Map<UUID, List<BukkitTask>> tasks = new ConcurrentHashMap<>();
    /** 플레이어별 연출/소환 엔티티 — onUnequip 에서 제거. */
    private final Map<UUID, List<Entity>> summons = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "spear";
    }

    @Override
    public String displayName() {
        return "창";
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = Items.weapon(Material.NETHERITE_SPEAR, id(), "창", NamedTextColor.YELLOW,
                "P  리치 +2, 2칸 내 추가 피해",
                "우  빠른 연속 찌르기",
                "좌  찌르기",
                "⇧우  큰 창 3×5 관통 찌르기",
                "F  고대 군단");
        applyReach(item);
        return item;
    }

    /** 기본 속성(공격력·속도)은 유지하고 리치 +2 를 멱등하게 추가. */
    private static void applyReach(ItemStack item) {
        ItemAttributeModifiers base = item.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        ItemAttributeModifiers.Builder b = ItemAttributeModifiers.itemAttributes();
        if (base != null) {
            for (ItemAttributeModifiers.Entry e : base.modifiers()) {
                if (REACH_KEY.equals(e.modifier().getKey())) continue;
                b.addModifier(e.attribute(), e.modifier(), e.getGroup(), e.display());
            }
        }
        b.addModifier(Attribute.ENTITY_INTERACTION_RANGE,
                new AttributeModifier(REACH_KEY, REACH_BONUS, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND),
                EquipmentSlotGroup.MAINHAND);
        item.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, b.build());

        // 바닐라 창은 attack_range 컴포넌트가 실제 공격 사거리를 정하므로 같이 늘림.
        // 최소 사거리는 0 으로 (2칸 이내 추가 피해 패시브가 동작하도록).
        AttackRange range = item.getData(DataComponentTypes.ATTACK_RANGE);
        if (range != null) {
            item.setData(DataComponentTypes.ATTACK_RANGE, AttackRange.attackRange()
                    .minReach(0f)
                    .maxReach(range.maxReach() + (float) REACH_BONUS)
                    .minCreativeReach(0f)
                    .maxCreativeReach(range.maxCreativeReach() + (float) REACH_BONUS)
                    .hitboxMargin(range.hitboxMargin())
                    .mobFactor(range.mobFactor())
                    .build());
        }
    }

    // ------------------------------------------------------------------ P

    @Override
    public void onMeleeHit(SkillContext ctx, LivingEntity target, EntityDamageByEntityEvent event) {
        Player p = ctx.player();
        double dist = p.getLocation().toVector().setY(0)
                .distance(target.getLocation().toVector().setY(0)) - target.getWidth() / 2.0;
        if (dist > 2.0) return;
        double bonus = ctx.isFever() ? 3.0 : 2.0;
        event.setDamage(event.getDamage() + bonus);
        Location at = target.getLocation().add(0, target.getHeight() * 0.6, 0);
        Fx.burst(at, Particle.ENCHANTED_HIT, 8, 0.25);
    }

    // ------------------------------------------------------------------ 우

    @Override
    public void onRightClick(SkillContext ctx) {
        if (!ctx.tryCooldown(Slot.RIGHT, JAB_COOLDOWN)) return; // 쿨 중이면 바닐라 돌격 그대로
        ctx.cancelVanilla();
        Player p = ctx.player();
        double dmg = ctx.isFever() ? JAB_DAMAGE * 1.3 : JAB_DAMAGE;
        int count = ctx.isFever() ? JAB_COUNT + 1 : JAB_COUNT;

        BukkitTask[] holder = new BukkitTask[1];
        holder[0] = ctx.plugin().getServer().getScheduler().runTaskTimer(ctx.plugin(), new Runnable() {
            int n = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || n >= count) {
                    holder[0].cancel();
                    untrack(p, holder[0]);
                    return;
                }
                n++;
                jab(p, dmg, n);
            }
        }, 0L, JAB_INTERVAL);
        track(p, holder[0]);
    }

    private void jab(Player p, double dmg, int n) {
        p.swingMainHand();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        // 살짝 좌우로 흔들리는 찌르기 선
        Vector right = new Vector(-dir.getZ(), 0, dir.getX());
        if (right.lengthSquared() > 1e-6) right.normalize().multiply((n % 2 == 0 ? 0.15 : -0.15));
        Location from = eye.clone().add(dir.clone().multiply(0.8)).add(right).subtract(0, 0.2, 0);
        Location to = eye.clone().add(dir.clone().multiply(JAB_LENGTH)).add(right).subtract(0, 0.2, 0);
        Fx.line(from, to, Particle.CRIT, 0.35);
        to.getWorld().spawnParticle(Particle.SWEEP_ATTACK, to, 1, 0, 0, 0, 0);
        Fx.sound(eye, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.6f, 1.5f + n * 0.08f);

        for (LivingEntity t : Targets.frontBox(p, 1.6, JAB_LENGTH, 2.2)) {
            t.setNoDamageTicks(0);
            Damage.skill(p, t, dmg);
            t.setVelocity(t.getVelocity().multiply(0.2)); // 연타 중 너무 멀리 밀리지 않게
            Fx.burst(t.getLocation().add(0, t.getHeight() * 0.6, 0), Particle.CRIT, 4, 0.2);
        }
    }

    // ------------------------------------------------------------------ ⇧우

    @Override
    public void onShiftRightClick(SkillContext ctx) {
        if (!ctx.tryCooldown(Slot.SHIFT_RIGHT, BIG_COOLDOWN)) return;
        ctx.cancelVanilla();
        Player p = ctx.player();
        double dmg = ctx.isFever() ? BIG_DAMAGE * 1.25 : BIG_DAMAGE;

        Location base = p.getLocation();
        Vector forward = base.getDirection().setY(0);
        if (forward.lengthSquared() < 1e-6) forward = new Vector(0, 0, 1);
        forward.normalize();
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());

        // 거대 창 연출
        Location spawn = base.clone().add(0, 1.2, 0);
        spawn.setPitch(0);
        ItemDisplay display = spawnSpearDisplay(p, spawn, 4.0f);
        Fx.sound(spawn, Sound.ITEM_TRIDENT_THROW, 1.0f, 0.6f);
        Fx.sound(spawn, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.0f, 0.7f);

        final Vector fwd = forward;
        ctx.plugin().getServer().getScheduler().runTaskLater(ctx.plugin(), () -> {
            if (!display.isValid()) return;
            // 뒤에서 앞으로 쭉 뻗는 연출
            Transformation t = display.getTransformation();
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(4);
            display.setTransformation(new Transformation(new Vector3f(0, 0, 3.0f),
                    t.getLeftRotation(), t.getScale(), t.getRightRotation()));
        }, 2L);

        ctx.plugin().getServer().getScheduler().runTaskLater(ctx.plugin(), () -> {
            if (!p.isOnline()) return;
            // 3×5 범위 파티클
            for (double f = 0.5; f <= 5.0; f += 0.5) {
                for (double s = -1.5; s <= 1.5; s += 0.75) {
                    Location l = base.clone().add(fwd.clone().multiply(f)).add(right.clone().multiply(s)).add(0, 1.0, 0);
                    l.getWorld().spawnParticle(Particle.CRIT, l, 1, 0.05, 0.3, 0.05, 0);
                }
            }
            Location tip = base.clone().add(fwd.clone().multiply(5)).add(0, 1.0, 0);
            Fx.burst(tip, Particle.SWEEP_ATTACK, 3, 0.6);
            Fx.sound(tip, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.6f);
            for (LivingEntity t : Targets.frontBox(p, 3, 5, 2.5)) {
                t.setNoDamageTicks(0);
                Damage.skill(p, t, dmg);
                t.setVelocity(fwd.clone().multiply(0.9).setY(0.3));
                Fx.burst(t.getLocation().add(0, t.getHeight() * 0.6, 0), Particle.ENCHANTED_HIT, 12, 0.3);
            }
        }, 4L);

        ctx.plugin().getServer().getScheduler().runTaskLater(ctx.plugin(), () -> {
            if (display.isValid()) {
                Fx.burst(display.getLocation(), Particle.CLOUD, 10, 0.5);
                display.remove();
            }
            untrack(p, display);
        }, 16L);
    }

    /**
     * 창 모양 ItemDisplay. 위치의 yaw 방향(수평)으로 창끝이 향하도록 회전.
     * 아이템 텍스처는 오른쪽 위 대각선(+x,+y)으로 그려져 있으므로 Z축 -45°, Y축 -90° 회전.
     */
    ItemDisplay spawnSpearDisplay(Player owner, Location at, float scale) {
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setItemStack(new ItemStack(Material.NETHERITE_SPEAR));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setPersistent(false);
            e.setRotation(at.getYaw(), 0);
            Quaternionf rot = new Quaternionf().rotateY((float) Math.toRadians(-90)).rotateZ((float) Math.toRadians(-45));
            e.setTransformation(new Transformation(new Vector3f(0, 0, -1.0f), rot,
                    new Vector3f(scale, scale, scale), new Quaternionf(new AxisAngle4f())));
            Targets.markSummon(e);
        });
        track(owner, d);
        return d;
    }

    // ------------------------------------------------------------------ F

    @Override
    public void onUltimate(SkillContext ctx) {
        new AncientLegion(this, ctx).start();
    }

    // ------------------------------------------------------------------ 정리

    void track(Player p, BukkitTask task) {
        tasks.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).add(task);
    }

    void untrack(Player p, BukkitTask task) {
        List<BukkitTask> l = tasks.get(p.getUniqueId());
        if (l != null) l.remove(task);
    }

    void track(Player p, Entity e) {
        summons.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).add(e);
    }

    void untrack(Player p, Entity e) {
        List<Entity> l = summons.get(p.getUniqueId());
        if (l != null) l.remove(e);
    }

    @Override
    public void onUnequip(Player player) {
        List<BukkitTask> l = tasks.remove(player.getUniqueId());
        if (l != null) for (BukkitTask t : l) t.cancel();
        List<Entity> s = summons.remove(player.getUniqueId());
        if (s != null) {
            for (Entity e : s) {
                if (e.isValid()) {
                    e.getWorld().spawnParticle(Particle.CLOUD, e.getLocation().add(0, 1, 0), 6, 0.2, 0.4, 0.2, 0.01);
                    e.remove();
                }
            }
        }
    }
}
