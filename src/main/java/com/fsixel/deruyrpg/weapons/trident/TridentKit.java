package com.fsixel.deruyrpg.weapons.trident;

import com.fsixel.deruyrpg.DeruyRPG;
import com.fsixel.deruyrpg.api.SkillContext;
import com.fsixel.deruyrpg.api.WeaponKit;
import com.fsixel.deruyrpg.util.Damage;
import com.fsixel.deruyrpg.util.Fx;
import com.fsixel.deruyrpg.util.Items;
import com.fsixel.deruyrpg.util.Targets;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.AttackRange;
import io.papermc.paper.datacomponent.item.SwingAnimation;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
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
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
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
 * 우 투척/급류   : 바닐라처럼 우클릭 차징(10틱 이상) 후 손을 떼면 발동. 물·비(궁극기 비 포함) 속이면
 *                 플러그인 급류 IV(인챈트 무관), 밖이면 시선 방향 직진 투척. 차징 부족이면 아무것도 안 함.
 * 좌 찌르기      : 사거리 4칸, 창과 같은 찌르기 모션(바닐라 창의 SWING_ANIMATION 복사) + 삼지창 ItemDisplay
 *                 찌르기 연출. 비 속이면 물살 찌르기(전방 6칸 관통 물줄기).
 * ⇧우 물기둥     : 바라보는 블록(최대 20칸)에 8칸 물기둥 → 범위 적 띄우기 + 피해. 쿨 6초.
 * F  뇌우+비 소환 : 피버(15초) 동안 반경 5.5칸 비 + 범위 내 모든 적 초당 3회 번개 피해. 나는 항상 젖은 상태.
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

    private static final int COLUMN_COOLDOWN = 120;         // 물기둥 6초
    private static final double COLUMN_MAX_DISTANCE = 20.0; // 바라보는 블록 최대 거리
    private static final double COLUMN_RADIUS = 2.0;
    private static final double COLUMN_HEIGHT = 8.0;
    private static final double COLUMN_DAMAGE = 4.0;
    private static final int COLUMN_TICKS = 30;

    private static final int RIPTIDE_COOLDOWN = 6;          // 급류 0.3초
    private static final int RIPTIDE_LEVEL = 4;             // 급류 IV
    private static final int RIPTIDE_TICKS = 20;            // 바닐라 회전 공격 지속
    private static final double RIPTIDE_DAMAGE = 7.0;
    private static final double RIPTIDE_HIT_RADIUS = 1.6;
    private static final int RIPTIDE_MIN_CHARGE = 10;       // 바닐라 TridentItem.releaseUsing 최소 사용 틱

    // 찌르기 연출
    private static final int THRUST_MIN_INTERVAL = 6;       // 연출 최소 간격 (헛스윙 연타 시 엔티티 남발 방지)
    private static final double THRUST_DISTANCE = 2.6;
    private static final float THRUST_SCALE = 0.85f;
    /** 바닐라 창 swing_animation 을 못 읽었을 때 쓰는 값. */
    private static final int FALLBACK_SWING_DURATION = 10;

    private static final double STORM_RADIUS = 5.5;         // 궁극기 범위 (비 / 젖음 / 번개)
    private static final int[] STORM_HIT_TICKS = {0, 7, 14}; // 20틱 중 3회 = 초당 3회
    private static final double STORM_DAMAGE = 2.0;         // 1회 피해 (초당 6)
    private static final int STORM_BOLT_INTERVAL = 20;      // 진짜 번개 연출은 1초에 1번, 1명만

    private static final NamespacedKey REACH_KEY = new NamespacedKey("deruyrpg", "trident_reach");
    private static final NamespacedKey WATER_SPEED_KEY = new NamespacedKey("deruyrpg", "trident_water_speed");

    // ---- 상태 ----
    private final Map<UUID, BukkitTask> storms = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> riptides = new ConcurrentHashMap<>();
    private final Set<UUID> waterSpeed = ConcurrentHashMap.newKeySet();
    /** 급류를 마지막으로 처리한 틱 (손 떼기 이벤트·투척 이벤트 중복 방지). */
    private final Map<UUID, Integer> riptideReleaseTick = new ConcurrentHashMap<>();
    /** 찌르기 연출 마지막 틱. */
    private final Map<UUID, Integer> lastThrust = new ConcurrentHashMap<>();
    /** 찌르기 연출 ItemDisplay — onUnequip/퇴장 시 제거. */
    private final Map<UUID, Set<ItemDisplay>> thrustDisplays = new ConcurrentHashMap<>();
    private static SwingAnimation spearSwing;

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
                "우  투척 (물·비 속: 급류 IV)",
                "좌  찌르기 4칸 (비 속: 물살 찌르기)",
                "⇧우  물기둥 (바라보는 곳, 20칸)",
                "F  뇌우 + 비 소환 (반경 5.5칸)");
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
        applyMotion(item);
        return item;
    }

    /** 바닐라 창(NETHERITE_SPEAR)이 가진 swing_animation 그대로 (창 공격 모션과 동일). */
    private static SwingAnimation spearSwing() {
        if (spearSwing == null) {
            SwingAnimation s = new ItemStack(Material.NETHERITE_SPEAR).getData(DataComponentTypes.SWING_ANIMATION);
            spearSwing = (s != null && s.type() == SwingAnimation.Animation.STAB) ? s
                    : SwingAnimation.swingAnimation().type(SwingAnimation.Animation.STAB)
                    .duration(FALLBACK_SWING_DURATION).build();
        }
        return spearSwing;
    }

    private static AttackRange attackRange() {
        return AttackRange.attackRange()
                .minReach(0f).maxReach(3f + (float) REACH_BONUS)
                .minCreativeReach(0f).maxCreativeReach(5f)
                .hitboxMargin(0.3f).mobFactor(1f).build();
    }

    /**
     * 창처럼 찌르는 공격 모션(swing_animation=STAB) + 공격 사거리 4칸을 아이템에 넣는다. 바뀌었으면 true.
     * 컴포넌트는 아이템에 박히므로, 이 기능 이전에 지급된 삼지창도 들고 있으면 onPassiveTick 에서 갱신된다.
     */
    private static boolean applyMotion(ItemStack item) {
        boolean changed = false;
        SwingAnimation want = spearSwing();
        SwingAnimation cur = item.getData(DataComponentTypes.SWING_ANIMATION);
        if (cur == null || cur.type() != want.type() || cur.duration() != want.duration()) {
            item.setData(DataComponentTypes.SWING_ANIMATION, want);
            changed = true;
        }
        AttackRange range = item.getData(DataComponentTypes.ATTACK_RANGE);
        if (range == null || Math.abs(range.maxReach() - (3f + (float) REACH_BONUS)) > 1e-4) {
            item.setData(DataComponentTypes.ATTACK_RANGE, attackRange());
            changed = true;
        }
        return changed;
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
        // 예전에 받은 삼지창에도 찌르기 모션 반영 (차징 중엔 아이템 교체가 사용을 끊으므로 건너뜀)
        if (!p.isHandRaised()) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (isTrident(hand) && applyMotion(hand)) p.getInventory().setItemInMainHand(hand);
        }
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
    }

    private void removeWaterSpeed(Player p) {
        AttributeInstance attr = p.getAttribute(Attribute.ATTACK_SPEED);
        if (attr != null && attr.getModifier(WATER_SPEED_KEY) != null) attr.removeModifier(WATER_SPEED_KEY);
        waterSpeed.remove(p.getUniqueId());
    }

    /** 근접 평타: 물의 가호 추가 피해 + 비 속이면 물살 찌르기. */
    @Override
    public void onMeleeHit(SkillContext ctx, LivingEntity target, EntityDamageByEntityEvent event) {
        Player p = ctx.player();
        thrust(p);
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

    /** 허공 좌클릭: 찌르기 연출, 비 속이면 물살 찌르기. */
    @Override
    public void onLeftClick(SkillContext ctx) {
        thrust(ctx.player());
        if (!inRain(ctx.player())) return;
        if (!ctx.tryCooldown(Slot.LEFT, STREAM_COOLDOWN)) return;
        waterStream(ctx, null);
    }

    /**
     * 창의 찌르기 연출과 같은 방식: 손 쪽에서 삼지창 ItemDisplay 가 시선 방향으로 쭉 찌르고 사라짐.
     * (헛스윙과 타격이 같은 클릭에서 둘 다 들어와도 1번만)
     */
    private void thrust(Player p) {
        int now = Bukkit.getCurrentTick();
        Integer last = lastThrust.get(p.getUniqueId());
        if (last != null && now - last < THRUST_MIN_INTERVAL) return;
        lastThrust.put(p.getUniqueId(), now);

        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Vector right = new Vector(-dir.getZ(), 0, dir.getX());
        if (right.lengthSquared() < 1e-6) right = new Vector(1, 0, 0);
        right.normalize();
        // 주손(오른손) 쪽, 눈보다 조금 아래에서 출발
        Location from = eye.clone().add(dir.clone().multiply(0.7)).add(right.multiply(0.28)).subtract(0, 0.3, 0);
        UUID id = p.getUniqueId();
        TridentFx.thrust(DeruyRPG.get(), from, dir, THRUST_DISTANCE, THRUST_SCALE,
                d -> thrustDisplays.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(d),
                d -> {
                    Set<ItemDisplay> s = thrustDisplays.get(id);
                    if (s != null) s.remove(d);
                });
        Fx.sound(eye, Sound.ITEM_TRIDENT_THROW, 0.35f, 1.7f);
        if (isWet(p)) p.getWorld().spawnParticle(Particle.SPLASH,
                eye.clone().add(dir.clone().multiply(2.2)).subtract(0, 0.3, 0), 8, 0.15, 0.15, 0.15, 0.05);
    }

    private void clearThrusts(UUID id) {
        lastThrust.remove(id);
        Set<ItemDisplay> s = thrustDisplays.remove(id);
        if (s != null) for (ItemDisplay d : s) if (d.isValid()) d.remove();
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
            if (Damage.skill(p, le, dmg)) {
                le.setVelocity(le.getVelocity().add(dir.clone().setY(0).multiply(0.35)));
                splash(c.toLocation(w));
            }
        }
        return dmg;
    }

    /** 투척 삼지창 명중에도 물의 가호 적용 + 손이 비어 코어가 못 채우는 피버 게이지 보충. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTridentHit(EntityDamageByEntityEvent e) {
        if (Damage.isProbe()) return;
        if (!(e.getDamager() instanceof Trident t) || !isTrident(t.getItemStack())) return;
        if (!(t.getShooter() instanceof Player p) || !(e.getEntity() instanceof LivingEntity target)) return;
        if (!Targets.isEnemy(p, target)) return;
        if (isWet(p) || isWet(p, target)) {
            boolean fever = DeruyRPG.get().fever().isFever(p, id());
            e.setDamage(e.getDamage() + (fever ? WET_BONUS + 1 : WET_BONUS));
            splash(target.getLocation().add(0, target.getHeight() * 0.6, 0));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTridentHitMonitor(EntityDamageByEntityEvent e) {
        if (Damage.isProbe()) return;
        if (!(e.getDamager() instanceof Trident t) || !isTrident(t.getItemStack())) return;
        if (!(t.getShooter() instanceof Player p) || e.getFinalDamage() <= 0) return;
        // 주손에 RPG 무기가 있으면 코어가 이미 게이지를 채움
        if (DeruyRPG.get().kits().find(p.getInventory().getItemInMainHand()) != null) return;
        DeruyRPG.get().fever().onHit(p);
    }

    // =====================================================================
    // 우 투척 / 급류
    // =====================================================================

    /** 급류 가능 상태: 물·비·거품기둥 속 또는 궁극기 비 속. */
    private boolean canRiptide(Player p) {
        return inStorm(p) || p.isInWaterOrRainOrBubbleColumn();
    }

    /**
     * 우클릭은 바닐라 그대로 차징(투척 자세)만 시작한다. 실제 발동은 손을 뗄 때:
     *  - 차징 10틱 미만 → 아무것도 안 함 (바닐라와 동일, 투척도 안 됨)
     *  - 물·비 속 → 투척 대신 급류 IV (인챈트 무관, 플러그인 구현) — onStopUsing / onLaunch
     *  - 밖 → 바닐라 투척 (직진 보정은 onLaunch)
     */
    @Override
    public void onRightClick(SkillContext ctx) {
        // 바닐라 차징 시작을 막지 않는다.
    }

    /** 우클릭 손 떼기: 충분히 차징했고 물·비 속이면 급류. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onStopUsing(PlayerStopUsingItemEvent e) {
        Player p = e.getPlayer();
        ItemStack item = e.getItem();
        if (!isTrident(item) || !isTrident(p.getInventory().getItemInMainHand())) return;
        if (e.getTicksHeldFor() < RIPTIDE_MIN_CHARGE || !canRiptide(p)) return;
        releaseRiptide(p, p.getInventory().getItemInMainHand());
    }

    /** 같은 틱에 손 떼기·투척 이벤트 둘 다 와도 1번만. 쿨 중이면 발동 안 함. */
    private void releaseRiptide(Player p, ItemStack item) {
        int now = Bukkit.getCurrentTick();
        Integer last = riptideReleaseTick.put(p.getUniqueId(), now);
        if (last != null && last == now) return;
        SkillContext ctx = new SkillContext(DeruyRPG.get(), p, item, this, null);
        if (!ctx.tryCooldown(Slot.RIGHT, RIPTIDE_COOLDOWN)) return;
        riptide(ctx);
    }

    private void riptide(SkillContext ctx) {
        Player p = ctx.player();
        boolean fever = ctx.isFever();
        double dmg = fever ? RIPTIDE_DAMAGE + 2 : RIPTIDE_DAMAGE;

        // 바닐라 TridentItem.releaseUsing: 속도 = 3 * (1 + 레벨) / 4 (IV → 3.75), 시선 방향
        double power = 3.0 * (1.0 + RIPTIDE_LEVEL) / 4.0;
        Vector dir = p.getEyeLocation().getDirection().normalize();
        Vector v = p.getVelocity().add(dir.clone().multiply(power));
        // 바닐라는 땅 위면 1.2칸 들어 올림 → 속도로 근사
        if (p.isOnGround()) v.setY(Math.max(v.getY(), 0) + 0.6);
        p.setVelocity(v);

        // 회전 연출: 바닐라 자동 회전 공격은 첫 타격에서 튕겨 멈추므로 포즈만 켜고 판정은 직접 처리
        p.setRiptiding(true);
        Location at = p.getLocation().add(0, 1, 0);
        World w = p.getWorld();
        w.spawnParticle(Particle.SPLASH, at, 50, 0.5, 0.7, 0.5, 0.3);
        w.spawnParticle(Particle.BUBBLE_POP, at, 15, 0.4, 0.5, 0.4, 0.05);
        Fx.sound(p.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_3, 1f, 1f);

        UUID id = p.getUniqueId();
        BukkitTask old = riptides.remove(id);
        if (old != null) old.cancel();
        Set<UUID> hit = new HashSet<>();
        BukkitTask task = new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || t >= RIPTIDE_TICKS || (t > 3 && p.isOnGround())) {
                    endRiptide(id, p);
                    return;
                }
                Location c = p.getLocation().add(0, 0.5, 0);
                World w = c.getWorld();
                // 소용돌이 물보라
                Vector mv = p.getVelocity();
                Vector fwd = mv.lengthSquared() > 1e-4 ? mv.clone().normalize() : dir.clone();
                Vector side = fwd.clone().crossProduct(new Vector(0, 1, 0));
                if (side.lengthSquared() < 1e-4) side = new Vector(1, 0, 0);
                side.normalize();
                Vector up = side.clone().crossProduct(fwd).normalize();
                for (int i = 0; i < 2; i++) {
                    double ang = t * 0.9 + i * Math.PI;
                    Vector off = side.clone().multiply(Math.cos(ang) * 0.9).add(up.clone().multiply(Math.sin(ang) * 0.9));
                    w.spawnParticle(Particle.SPLASH, c.clone().add(off), 4, 0.05, 0.05, 0.05, 0.05);
                    w.spawnParticle(Particle.BUBBLE_POP, c.clone().add(off), 1, 0.05, 0.05, 0.05, 0.01);
                }
                w.spawnParticle(Particle.FALLING_WATER, c, 2, 0.3, 0.3, 0.3, 0);

                // 경로 적 피해 (적마다 1회)
                for (Entity e : w.getNearbyEntities(c, RIPTIDE_HIT_RADIUS + 0.5, RIPTIDE_HIT_RADIUS + 1, RIPTIDE_HIT_RADIUS + 0.5)) {
                    if (!Targets.isEnemy(p, e)) continue;
                    LivingEntity le = (LivingEntity) e;
                    if (le.getBoundingBox().getCenter().distanceSquared(c.toVector())
                            > (RIPTIDE_HIT_RADIUS + le.getWidth() / 2) * (RIPTIDE_HIT_RADIUS + le.getWidth() / 2) + 1) continue;
                    if (!hit.add(le.getUniqueId())) continue;
                    if (!Damage.skill(p, le, dmg)) continue;
                    le.setVelocity(fwd.clone().setY(0).multiply(0.6).setY(0.35));
                    splash(le.getLocation().add(0, le.getHeight() * 0.6, 0));
                    Fx.sound(le.getLocation(), Sound.ITEM_TRIDENT_HIT, 0.8f, 1.2f);
                }
                t++;
            }
        }.runTaskTimer(ctx.plugin(), 1L, 1L);
        riptides.put(id, task);
    }

    private void endRiptide(UUID id, Player p) {
        BukkitTask task = riptides.remove(id);
        if (task != null) task.cancel();
        if (p != null && p.isOnline()) p.setRiptiding(false);
    }

    /**
     * 차징 10틱 이상으로 손을 떼면 바닐라가 투척한다.
     * 물·비 속이면 투척을 취소하고 급류로 대체 (손 떼기 이벤트에서 이미 했으면 중복 방지).
     * 밖이면 탄퍼짐 없이 시선 방향 직진.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof Trident t) || !isTrident(t.getItemStack())) return;
        if (!(t.getShooter() instanceof Player p)) return;
        if (canRiptide(p)) {
            e.setCancelled(true); // 아이템은 손에 남음
            releaseRiptide(p, p.getInventory().getItemInMainHand());
            return;
        }
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
        double dmg = fever ? COLUMN_DAMAGE + 1.5 : COLUMN_DAMAGE;
        double lift = fever ? 1.35 : 1.2;

        Location base = columnBase(p);
        World w = base.getWorld();
        Fx.sound(base, Sound.ITEM_BUCKET_EMPTY, 1f, 0.7f);
        Fx.sound(base, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_INSIDE, 1.4f, 0.9f);
        Fx.sound(base, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.2f, 0.6f);
        Fx.sound(base, Sound.ENTITY_GENERIC_SPLASH, 1f, 0.8f);
        // 솟구치는 첫 물보라
        w.spawnParticle(Particle.SPLASH, base.clone().add(0, 0.3, 0), 120, radius * 0.6, 0.2, radius * 0.6, 0.5);
        w.spawnParticle(Particle.BUBBLE_POP, base.clone().add(0, 0.5, 0), 40, radius * 0.5, 0.3, radius * 0.5, 0.1);

        Set<UUID> hit = new HashSet<>();
        Set<UUID> lifted = new HashSet<>(); // 피해가 실제로 들어간 적만 띄움
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t > COLUMN_TICKS || !p.isOnline()) {
                    cancel();
                    return;
                }
                // 기둥이 아래에서 위로 자라남 (6틱 동안)
                double h = Math.min(COLUMN_HEIGHT, COLUMN_HEIGHT * (t + 2) / 8.0);
                double core = radius * 0.45;
                ThreadLocalRandom r = ThreadLocalRandom.current();

                // 몸통: 거품 기둥 + 물보라
                for (double y = 0; y <= h; y += 0.4) {
                    Location l = base.clone().add(0, y, 0);
                    w.spawnParticle(Particle.BUBBLE_COLUMN_UP, l, 6, core, 0.15, core, 0.08);
                    w.spawnParticle(Particle.SPLASH, l, 4, core, 0.15, core, 0.08);
                    if (r.nextInt(3) == 0) w.spawnParticle(Particle.BUBBLE_POP, l, 2, core, 0.1, core, 0.02);
                }
                // 물방울 소용돌이 (3줄 나선)
                for (int arm = 0; arm < 3; arm++) {
                    for (double y = 0; y <= h; y += 0.35) {
                        double ang = y * 1.3 + t * 0.45 + arm * (Math.PI * 2 / 3);
                        double rr = radius * (0.55 + 0.25 * Math.sin(y * 0.8 + t * 0.3));
                        Location l = base.clone().add(Math.cos(ang) * rr, y, Math.sin(ang) * rr);
                        w.spawnParticle(Particle.SPLASH, l, 1, 0, 0, 0, 0);
                        if (((int) (y * 10)) % 7 == 0) w.spawnParticle(Particle.DRIPPING_WATER, l, 1, 0, 0, 0, 0);
                    }
                }
                // 꼭대기 왕관 + 쏟아지는 물
                Location top = base.clone().add(0, h, 0);
                w.spawnParticle(Particle.SPLASH, top, 35, radius * 0.7, 0.25, radius * 0.7, 0.4);
                w.spawnParticle(Particle.BUBBLE_POP, top, 8, radius * 0.5, 0.2, radius * 0.5, 0.05);
                w.spawnParticle(Particle.FALLING_WATER, top, 18, radius * 0.9, 0.3, radius * 0.9, 0);
                // 바닥 물결
                if (t % 4 == 0) {
                    Fx.ring(base.clone().add(0, 0.1, 0), Particle.SPLASH, radius, 28);
                    Fx.ring(base.clone().add(0, 0.1, 0), Particle.BUBBLE_POP, radius * 0.75, 14);
                }
                if (t % 8 == 0) Fx.sound(base, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 1.2f, 1f);

                // 판정: 처음 들어온 적 = 피해 + 띄움, 이후 기둥 안에 있으면 계속 떠오름
                for (Entity e : w.getNearbyEntities(base.clone().add(0, h / 2, 0),
                        radius + 0.5, h / 2 + 1, radius + 0.5)) {
                    if (!Targets.isEnemy(p, e)) continue;
                    LivingEntity le = (LivingEntity) e;
                    Location l = le.getLocation();
                    double dx = l.getX() - base.getX(), dz = l.getZ() - base.getZ();
                    double dy = l.getY() - base.getY();
                    if (dx * dx + dz * dz > radius * radius || dy < -1 || dy > h) continue;
                    if (hit.add(le.getUniqueId())) {
                        if (Damage.skill(p, le, dmg)) {
                            lifted.add(le.getUniqueId());
                            le.setVelocity(new Vector(0, lift, 0));
                            splash(l.clone().add(0, 0.5, 0));
                        }
                    } else if (lifted.contains(le.getUniqueId())
                            && t <= COLUMN_TICKS - 8 && le.getVelocity().getY() < 0.3) {
                        le.setVelocity(le.getVelocity().setY(0.35));
                    }
                }
                t += 2;
            }
        }.runTaskTimer(ctx.plugin(), 0L, 2L);
    }

    /** 바라보는 블록(최대 20칸) 위. 허공이면 그 지점 아래 바닥. */
    private Location columnBase(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World w = p.getWorld();
        RayTraceResult hit = w.rayTraceBlocks(eye, dir, COLUMN_MAX_DISTANCE, FluidCollisionMode.NEVER, true);
        Location point;
        if (hit != null) {
            // 맞은 면 바깥쪽으로 살짝 빼서 블록 안에 묻히지 않게
            Vector n = hit.getHitBlockFace() != null ? hit.getHitBlockFace().getDirection().multiply(0.3) : new Vector();
            point = hit.getHitPosition().add(n).toLocation(w);
        } else {
            point = eye.clone().add(dir.multiply(COLUMN_MAX_DISTANCE));
        }
        RayTraceResult ground = w.rayTraceBlocks(point.clone().add(0, 0.1, 0), new Vector(0, -1, 0), 12,
                FluidCollisionMode.NEVER, true);
        if (ground != null) point.setY(ground.getHitPosition().getY());
        return point;
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
        ctx.flash(Component.text("뇌우 소환!", NamedTextColor.AQUA));

        UUID id = p.getUniqueId();
        int duration = feverDurationTicks(); // 피버 기본 15초
        BukkitTask task = new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t >= duration || !p.isOnline() || p.isDead()) {
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

                // 범위 표시
                if (t % 10 == 0) Fx.ring(c.clone().add(0, 0.15, 0), Particle.SPLASH, STORM_RADIUS, 40);

                // 번개: 범위 내 모든 적 초당 3회 (마법 피해, 무적시간 무시)
                int phase = t % 20;
                boolean hitTick = false;
                for (int ht : STORM_HIT_TICKS) if (phase == ht) hitTick = true;
                if (hitTick) {
                    List<LivingEntity> enemies = Targets.enemiesAround(p, c, STORM_RADIUS);
                    double dmg = STORM_DAMAGE;
                    // 진짜 번개(빛+천둥)는 1초에 1번, 무작위 1명만 — 나머지는 전기 스파크
                    LivingEntity bolt = (t % STORM_BOLT_INTERVAL == 0 && !enemies.isEmpty())
                            ? enemies.get(r.nextInt(enemies.size())) : null;
                    for (LivingEntity e : enemies) {
                        Location el = e.getLocation();
                        if (e.equals(bolt)) {
                            w.strikeLightningEffect(el);
                        } else {
                            Fx.line(el.clone().add(r.nextDouble(-0.6, 0.6), 4.5, r.nextDouble(-0.6, 0.6)),
                                    el.clone().add(0, e.getHeight() * 0.6, 0), Particle.ELECTRIC_SPARK, 0.35);
                        }
                        w.spawnParticle(Particle.ELECTRIC_SPARK, el.clone().add(0, e.getHeight() * 0.5, 0),
                                10, 0.3, e.getHeight() * 0.35, 0.3, 0.15);
                        int prevNoDmg = e.getNoDamageTicks();
                        e.setNoDamageTicks(0);
                        if (!Damage.magic(p, e, dmg)) e.setNoDamageTicks(prevNoDmg); // 보호 대상은 무적시간 원복
                    }
                    if (!enemies.isEmpty() && bolt == null) {
                        Fx.sound(c, Sound.ENTITY_GUARDIAN_ATTACK, 0.25f, 2f);
                        Fx.sound(c, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.5f, 1.8f);
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

    /**
     * 손에서 빠지면 물속 공격 속도·찌르기 연출 해제 (여러 번 불려도 안전).
     * 뇌우(궁극기)와 진행 중인 급류는 끊지 않고 자체 타이머로 끝까지 진행 — 정리는 종료·사망·퇴장 때.
     * 피버 전용 버프는 수치 보너스(ctx.isFever)뿐이라 해제할 attribute/포션이 없음.
     */
    @Override
    public void onUnequip(Player player) {
        removeWaterSpeed(player);
        clearThrusts(player.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        endRiptide(e.getPlayer().getUniqueId(), null);
        clearThrusts(e.getPlayer().getUniqueId());
        riptideReleaseTick.remove(e.getPlayer().getUniqueId());
        removeWaterSpeed(e.getPlayer());
        stopStorm(e.getPlayer().getUniqueId());
    }
}
