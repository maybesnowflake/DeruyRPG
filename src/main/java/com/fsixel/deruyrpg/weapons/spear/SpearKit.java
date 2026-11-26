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
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;

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
 * F  고대 군단  : {@link AncientLegion} — 자체 타이머로 끝까지 진행 (무기를 놓아도 안 끊김).
 *
 * 피버 사거리(+0.5)는 이 창으로 시작한 피버(ctx.isFever)일 때 들고 있는 동안만 적용.
 * 무기를 놓으면(onUnequip) attribute·아이템 attack_range 를 원래대로, 다시 들면 피버가 남아 있을 때 재적용.
 */
public final class SpearKit implements WeaponKit, Listener {

    static final NamespacedKey REACH_KEY = new NamespacedKey("deruyrpg", "spear_reach");
    static final NamespacedKey FEVER_REACH_KEY = new NamespacedKey("deruyrpg", "spear_fever_reach");

    private static final double REACH_BONUS = 2.0;

    // 피버
    private static final double FEVER_REACH = 0.5;
    private static final int FEVER_JAB_COOLDOWN = 26;   // 1.3초
    private static final double SPLASH_RATIO = 0.6;     // 평타 범위 피해 비율
    private static final double SPLASH_HALF = 1.0;      // 맞힌 대상 주변 2×2
    private static final int LUNGE_COOLDOWN = 10;       // 0.5초 내부 쿨
    private static final double LUNGE_SPEED = 1.15;

    // 우 연속 찌르기
    private static final int JAB_COUNT = 5;
    private static final int JAB_INTERVAL = 3;
    private static final double JAB_DAMAGE = 2.0;
    private static final double JAB_LENGTH = 7.5;
    private static final int JAB_COOLDOWN = 100;

    // ⇧우 큰 창
    private static final double BIG_DAMAGE = 8.0;
    private static final int BIG_COOLDOWN = 200;

    /** 플레이어별 진행 중인 작업 (연속 찌르기 등) — onUnequip 에서 정리. */
    private final Map<UUID, List<BukkitTask>> tasks = new ConcurrentHashMap<>();
    /** 플레이어별 연출/소환 엔티티 — onUnequip 에서 제거. */
    private final Map<UUID, List<Entity>> summons = new ConcurrentHashMap<>();
    /** 피버 돌진 내부 쿨 (다음 사용 가능 틱). */
    private final Map<UUID, Integer> lungeReady = new ConcurrentHashMap<>();

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
        AttackRange range = attackRange(0f);
        if (range != null) item.setData(DataComponentTypes.ATTACK_RANGE, range);
    }

    /** 바닐라 창 사거리 + 리치 보너스 + extra. 항상 새 바닐라 값에서 계산하므로 멱등. */
    private static AttackRange attackRange(float extra) {
        AttackRange range = new ItemStack(Material.NETHERITE_SPEAR).getData(DataComponentTypes.ATTACK_RANGE);
        if (range == null) return null;
        float add = (float) REACH_BONUS + extra;
        return AttackRange.attackRange()
                .minReach(0f)
                .maxReach(range.maxReach() + add)
                .minCreativeReach(0f)
                .maxCreativeReach(range.maxCreativeReach() + add)
                .hitboxMargin(range.hitboxMargin())
                .mobFactor(range.mobFactor())
                .build();
    }

    /** 이 창 아이템의 attack_range 를 (피버 여부에 맞게) 맞춤. 바뀌었으면 true. */
    private boolean syncItemRange(ItemStack item, boolean fever) {
        if (item == null || !id().equals(Items.kitId(item))) return false;
        AttackRange want = attackRange(fever ? (float) FEVER_REACH : 0f);
        AttackRange cur = item.getData(DataComponentTypes.ATTACK_RANGE);
        if (want == null || (cur != null && Math.abs(cur.maxReach() - want.maxReach()) < 1e-4)) return false;
        item.setData(DataComponentTypes.ATTACK_RANGE, want);
        return true;
    }

    /** 피버 사거리 +0.5 : 플레이어 attribute(임시) + 들고 있는 창 attack_range. */
    private void setFeverReach(Player p, boolean on) {
        AttributeInstance inst = p.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        if (inst != null) {
            boolean has = inst.getModifier(FEVER_REACH_KEY) != null;
            if (on && !has) {
                inst.addTransientModifier(new AttributeModifier(FEVER_REACH_KEY, FEVER_REACH,
                        AttributeModifier.Operation.ADD_NUMBER));
            } else if (!on && has) {
                inst.removeModifier(FEVER_REACH_KEY);
            }
        }
        // 들고 있는 창만 피버 사거리, 나머지 창(인벤토리·커서)은 항상 원래 사거리.
        PlayerInventory inv = p.getInventory();
        int held = inv.getHeldItemSlot();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (syncItemRange(contents[i], on && i == held)) inv.setItem(i, contents[i]);
        }
        ItemStack cursor = p.getItemOnCursor();
        if (syncItemRange(cursor, false)) p.setItemOnCursor(cursor);
    }

    // 버려지거나 사망으로 떨어진 창은 피버 사거리 제거 (주운 사람에게 남지 않게)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        ItemStack stack = e.getItemDrop().getItemStack();
        if (syncItemRange(stack, false)) e.getItemDrop().setItemStack(stack);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        for (ItemStack stack : e.getDrops()) syncItemRange(stack, false);
    }

    @Override
    public void onPassiveTick(SkillContext ctx) {
        setFeverReach(ctx.player(), ctx.isFever());
    }

    // ------------------------------------------------------------------ P

    @Override
    public void onMeleeHit(SkillContext ctx, LivingEntity target, EntityDamageByEntityEvent event) {
        Player p = ctx.player();
        double dist = p.getLocation().toVector().setY(0)
                .distance(target.getLocation().toVector().setY(0)) - target.getWidth() / 2.0;
        if (dist <= 2.0) {
            double bonus = ctx.isFever() ? 3.0 : 2.0;
            event.setDamage(event.getDamage() + bonus);
            Location at = target.getLocation().add(0, target.getHeight() * 0.6, 0);
            Fx.burst(at, Particle.ENCHANTED_HIT, 8, 0.25);
        }
        if (ctx.isFever()) {
            splash(p, target, event.getDamage() * SPLASH_RATIO);
            tryLunge(ctx);
        }
    }

    /** 피버: 맞힌 대상 주변 2×2 범위의 다른 적에게도 피해. */
    private void splash(Player p, LivingEntity center, double dmg) {
        Location c = center.getLocation();
        boolean any = false;
        for (Entity e : c.getWorld().getNearbyEntities(c, SPLASH_HALF + 0.5, 1.5, SPLASH_HALF + 0.5)) {
            if (e.equals(center) || !Targets.isEnemy(p, e)) continue;
            Location l = e.getLocation();
            if (Math.abs(l.getX() - c.getX()) > SPLASH_HALF || Math.abs(l.getZ() - c.getZ()) > SPLASH_HALF) continue;
            if (l.getY() < c.getY() - 1 || l.getY() > c.getY() + 2) continue;
            LivingEntity t = (LivingEntity) e;
            t.setNoDamageTicks(0);
            Damage.skill(p, t, dmg);
            Fx.burst(t.getLocation().add(0, t.getHeight() * 0.6, 0), Particle.CRIT, 5, 0.2);
            any = true;
        }
        Location at = c.clone().add(0, 0.2, 0);
        at.getWorld().spawnParticle(Particle.SWEEP_ATTACK, at.clone().add(0, center.getHeight() * 0.5, 0), 1, 0, 0, 0, 0);
        if (any) Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.7f, 1.2f);
    }

    // ------------------------------------------------------------------ 좌 (피버 돌진)

    @Override
    public void onLeftClick(SkillContext ctx) {
        if (ctx.isFever()) tryLunge(ctx);
    }

    /** 피버: 돌진 II 느낌의 짧은 전방 돌진 (내부 쿨 0.5초). */
    private void tryLunge(SkillContext ctx) {
        Player p = ctx.player();
        int now = ctx.plugin().getServer().getCurrentTick();
        Integer ready = lungeReady.get(p.getUniqueId());
        if (ready != null && now < ready) return;
        lungeReady.put(p.getUniqueId(), now + LUNGE_COOLDOWN);

        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) return;
        dir.normalize().multiply(LUNGE_SPEED);
        dir.setY(p.isOnGround() ? 0.12 : Math.max(p.getVelocity().getY(), 0.0));
        p.setVelocity(dir);
        Location at = p.getLocation().add(0, 0.2, 0);
        at.getWorld().spawnParticle(Particle.CLOUD, at, 6, 0.2, 0.05, 0.2, 0.02);
        Fx.sound(at, Sound.ITEM_SPEAR_LUNGE_2, 0.9f, 1.0f);
    }

    // ------------------------------------------------------------------ 우

    @Override
    public void onRightClick(SkillContext ctx) {
        if (!ctx.tryCooldown(Slot.RIGHT, ctx.isFever() ? FEVER_JAB_COOLDOWN : JAB_COOLDOWN)) return; // 쿨 중이면 바닐라 돌격 그대로
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
                jab(ctx, p, dmg, n);
            }
        }, 0L, JAB_INTERVAL);
        track(p, holder[0]);
    }

    private void jab(SkillContext ctx, Player p, double dmg, int n) {
        p.swingMainHand();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        miniSpears(p, eye.clone().subtract(0, 0.25, 0), dir, JAB_LENGTH - 1.2, 3, n, false); // 창끝이 약 7.5칸까지
        Fx.sound(eye, Sound.ITEM_SPEAR_ATTACK, 0.7f, 1.3f + n * 0.06f);

        for (LivingEntity t : Targets.frontBox(p, 1.6, JAB_LENGTH, 2.2)) {
            t.setNoDamageTicks(0);
            if (!Damage.skill(p, t, dmg)) continue;
            t.setVelocity(t.getVelocity().multiply(0.2)); // 연타 중 너무 멀리 밀리지 않게
            Fx.burst(t.getLocation().add(0, t.getHeight() * 0.6, 0), Particle.CRIT, 4, 0.2);
        }
    }

    /**
     * 작은 창 여러 개가 origin 앞에서 dir 방향으로 빠르게 찌르고 사라지는 연출.
     * (플레이어 연속 찌르기와 고대 군단 병사 찌르기가 같이 씀)
     * ultimate=true 면 onUnequip 정리 대상에 넣지 않음 (궁극기는 자체 타이머로 끝까지).
     */
    void miniSpears(Player owner, Location origin, Vector dir, double distance, int count, int seed, boolean ultimate) {
        Vector d = dir.clone().normalize();
        Vector right = new Vector(-d.getZ(), 0, d.getX());
        if (right.lengthSquared() < 1e-6) right = new Vector(1, 0, 0);
        right.normalize();
        Vector up = right.clone().crossProduct(d).normalize();
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            double side = (i - (count - 1) / 2.0) * 0.45 + (seed % 2 == 0 ? 0.12 : -0.12);
            double vert = r.nextDouble(-0.25, 0.25);
            Location from = origin.clone().add(d.clone().multiply(0.9))
                    .add(right.clone().multiply(side)).add(up.clone().multiply(vert));
            SpearFx.miniThrust(this, owner, com.fsixel.deruyrpg.DeruyRPG.get(), from, d, distance, 0.55f, i % 2, !ultimate);
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
        Location spawn = base.clone().add(0, 1.1, 0);
        ItemDisplay display = SpearFx.spawn(spawn, forward, 4.0f, SpearFx.toV3(forward.clone().multiply(-1.0)));
        track(p, display);
        Fx.sound(spawn, Sound.ITEM_TRIDENT_THROW, 1.0f, 0.6f);
        Fx.sound(spawn, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.0f, 0.7f);

        final Vector fwd = forward;
        ctx.plugin().getServer().getScheduler().runTaskLater(ctx.plugin(), () -> {
            if (!display.isValid()) return;
            // 뒤에서 앞으로 쭉 뻗는 연출
            Transformation t = display.getTransformation();
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(4);
            display.setTransformation(new Transformation(SpearFx.toV3(fwd.clone().multiply(3.0)),
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
                if (!Damage.skill(p, t, dmg)) continue;
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

    /**
     * 무기 해제(슬롯 변경·버리기·사망·접속 종료 등, 여러 번 불려도 안전).
     * 피버 사거리 해제 + 일반 스킬(연속 찌르기·큰 창) 정리. 고대 군단(궁극기)은 건드리지 않음.
     */
    @Override
    public void onUnequip(Player player) {
        setFeverReach(player, false);
        lungeReady.remove(player.getUniqueId());
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
