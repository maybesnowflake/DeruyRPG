package com.fsixel.deruyrpg.weapons.sword;

import com.fsixel.deruyrpg.DeruyRPG;
import com.fsixel.deruyrpg.api.SkillContext;
import com.fsixel.deruyrpg.api.WeaponKit;
import com.fsixel.deruyrpg.util.Damage;
import com.fsixel.deruyrpg.util.Fx;
import com.fsixel.deruyrpg.util.Items;
import com.fsixel.deruyrpg.util.Targets;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BlocksAttacks;
import io.papermc.paper.datacomponent.item.blocksattacks.DamageReduction;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 검 — docs/SKILLS.md "검".
 * P 연속 베기 / 우 막기·패링 / 좌 휩쓸기 베기(평타) / ⇧우 돌진 베기 / F 일섬
 */
public final class SwordKit implements WeaponKit {

    // ---- 수치 ----
    private static final int COMBO_MAX_STACKS = 4;
    private static final int COMBO_RESET_TICKS = 60;          // 3초 안 때리면 초기화
    private static final int COMBO_MIN_HIT_INTERVAL = 8;      // 연타 스팸 방지 (이보다 빠른 타격은 카운트 X)
    private static final double COMBO_DAMAGE_PER_STACK = 0.06; // 중첩당 평타 피해 +6%
    private static final double COMBO_SPEED_PER_STACK = 0.12;  // 중첩당 공격 속도 +0.12
    private static final double EMPOWERED_MAIN_MULT = 1.3;    // 3타째 본타 +30%
    private static final double EMPOWERED_SWEEP_BASE = 4.0;   // 3타째 휩쓸기 피해 (+중첩당 1)

    private static final int PARRY_WINDOW_TICKS = 5;          // 0.25초
    private static final int PARRY_RERAISE_TICKS = 10;        // 막기 연타로 패링창 남용 방지
    private static final float BLOCK_FACTOR = 0.5f;           // 정면 피해 50% 감소

    private static final int DASH_COOLDOWN = 140;             // 7초
    private static final double DASH_DAMAGE = 6.0;
    private static final double DASH_DAMAGE_FEVER = 8.0;
    private static final double DASH_HIT_RADIUS = 1.7;

    private static final int ULT_CHARGE_TICKS = 12;
    private static final double ULT_RANGE = 8.0;
    private static final double ULT_WIDTH = 1.6;
    private static final double ULT_DAMAGE = 16.0;

    private static final NamespacedKey SPEED_KEY = new NamespacedKey("deruyrpg", "sword_combo_speed");

    // ---- 플레이어별 상태 ----
    private static final class Combo {
        int hits;
        int stacks;
        int lastHitTick;
        int lastCountedTick = -1000;
    }

    private final Map<UUID, Combo> combos = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> raiseTick = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> parryArmed = new ConcurrentHashMap<>();
    private final Set<UUID> inUltimate = ConcurrentHashMap.newKeySet();
    private @Nullable BukkitTask comboWatcher;

    @Override
    public String id() {
        return "sword";
    }

    @Override
    public String displayName() {
        return "검";
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = Items.weapon(Material.NETHERITE_SWORD, id(), "검", NamedTextColor.WHITE,
                "P  연속 베기 (3타마다 강화, 최대 4중첩)",
                "우  막기 / 패링 (0.25초)",
                "좌  휩쓸기 베기",
                "⇧우  2.5칸 돌진 베기",
                "F  일섬");
        // 바닐라 막기: 우클릭 유지 시 방패처럼 막기 자세 + 이동 느려짐, 정면(±90°) 피해 50% 감소
        item.setData(DataComponentTypes.BLOCKS_ATTACKS, BlocksAttacks.blocksAttacks()
                .blockDelaySeconds(0f)
                .disableCooldownScale(0.5f)
                .addDamageReduction(DamageReduction.damageReduction()
                        .horizontalBlockingAngle(90f)
                        .base(0f)
                        .factor(BLOCK_FACTOR)
                        .build())
                .blockSound(Key.key("minecraft", "item.shield.block"))
                .disableSound(Key.key("minecraft", "item.shield.break"))
                .build());
        return item;
    }

    @Override
    public String[] cooldownSlots() {
        return new String[]{Slot.SHIFT_RIGHT};
    }

    // =====================================================================
    // P — 연속 베기 / 좌 — 휩쓸기 베기 (평타)
    // =====================================================================

    @Override
    public void onMeleeHit(SkillContext ctx, LivingEntity target, EntityDamageByEntityEvent event) {
        Player p = ctx.player();
        int now = Bukkit.getCurrentTick();
        Combo c = combos.computeIfAbsent(p.getUniqueId(), k -> new Combo());
        ensureWatcher(ctx.plugin());

        if (now - c.lastHitTick > COMBO_RESET_TICKS) {
            resetCombo(p, c);
        }
        c.lastHitTick = now;

        double mult = 1.0 + COMBO_DAMAGE_PER_STACK * c.stacks;

        if (now - c.lastCountedTick >= COMBO_MIN_HIT_INTERVAL) {
            c.lastCountedTick = now;
            c.hits++;
            int every = ctx.isFever() ? 2 : 3;
            if (c.hits % every == 0) {
                mult *= EMPOWERED_MAIN_MULT;
                empoweredSweep(ctx, target, c.stacks);
                if (c.stacks < COMBO_MAX_STACKS) {
                    c.stacks++;
                    applySpeed(p, c.stacks);
                    Fx.sound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 0.8f + 0.2f * c.stacks);
                }
            }
        }

        event.setDamage(event.getDamage() * mult);
        // 평타 휩쓸기 연출
        Location at = target.getLocation().add(0, target.getHeight() * 0.6, 0);
        p.getWorld().spawnParticle(Particle.SWEEP_ATTACK, at, 1, 0, 0, 0, 0);
    }

    private void empoweredSweep(SkillContext ctx, LivingEntity mainTarget, int stacks) {
        Player p = ctx.player();
        double dmg = EMPOWERED_SWEEP_BASE + stacks;
        for (LivingEntity e : Targets.frontBox(p, 5, 3.5, 2.5)) {
            if (e.equals(mainTarget)) continue;
            Damage.skill(p, e, dmg);
            Targets.push(e, p.getLocation(), 0.5, 0.2);
        }
        Location base = p.getLocation().add(0, 1.0, 0);
        Vector fwd = flatDir(p);
        Vector right = new Vector(-fwd.getZ(), 0, fwd.getX());
        for (int i = -2; i <= 2; i++) {
            Location l = base.clone().add(fwd.clone().multiply(2.0)).add(right.clone().multiply(i * 0.9));
            p.getWorld().spawnParticle(Particle.SWEEP_ATTACK, l, 1, 0, 0, 0, 0);
        }
        Fx.sound(base, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.7f);
        Fx.sound(base, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.8f, 1.2f);
    }

    private void applySpeed(Player p, int stacks) {
        AttributeInstance attr = p.getAttribute(Attribute.ATTACK_SPEED);
        if (attr == null) return;
        attr.removeModifier(SPEED_KEY);
        if (stacks > 0) {
            attr.addTransientModifier(new AttributeModifier(SPEED_KEY, COMBO_SPEED_PER_STACK * stacks,
                    AttributeModifier.Operation.ADD_NUMBER));
        }
    }

    private void resetCombo(Player p, Combo c) {
        c.hits = 0;
        if (c.stacks > 0) {
            c.stacks = 0;
            applySpeed(p, 0);
        }
    }

    /** 중첩 만료 + 검을 놓았을 때(버리기·인벤토리 이동 등 onUnequip 이 안 오는 경우 포함) 정리. */
    private void ensureWatcher(DeruyRPG plugin) {
        if (comboWatcher != null && !comboWatcher.isCancelled()) return;
        comboWatcher = new BukkitRunnable() {
            @Override
            public void run() {
                int now = Bukkit.getCurrentTick();
                Iterator<Map.Entry<UUID, Combo>> it = combos.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<UUID, Combo> en = it.next();
                    Player p = Bukkit.getPlayer(en.getKey());
                    if (p == null) {
                        it.remove();
                        continue;
                    }
                    Combo c = en.getValue();
                    boolean holding = id().equals(Items.kitId(p.getInventory().getItemInMainHand()));
                    if (!holding || p.isDead() || now - c.lastHitTick > COMBO_RESET_TICKS) {
                        applySpeed(p, 0);
                        it.remove();
                    }
                }
                if (combos.isEmpty()) {
                    cancel();
                    comboWatcher = null;
                }
            }
        }.runTaskTimer(plugin, 10L, 10L);
    }

    // =====================================================================
    // 우 — 막기 / 패링
    // =====================================================================

    @Override
    public void onRightClick(SkillContext ctx) {
        // 막기 자체는 BLOCKS_ATTACKS 컴포넌트로 바닐라가 처리. 여기선 막기 시작 시각만 기록.
        UUID id = ctx.player().getUniqueId();
        int now = Bukkit.getCurrentTick();
        Integer prev = raiseTick.put(id, now);
        parryArmed.put(id, prev == null || now - prev >= PARRY_RERAISE_TICKS);
    }

    @Override
    public void onDamaged(SkillContext ctx, EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent ebe)) return;
        Player p = ctx.player();
        if (!p.isHandRaised() || !id().equals(Items.kitId(p.getActiveItem()))) return;

        Integer start = raiseTick.get(p.getUniqueId());
        if (start == null || !parryArmed.getOrDefault(p.getUniqueId(), false)) return;
        if (Bukkit.getCurrentTick() - start > PARRY_WINDOW_TICKS) return;

        Entity damager = ebe.getDamager();
        if (!isFrontal(p, damager.getLocation())) return;

        LivingEntity attacker = null;
        if (damager instanceof LivingEntity le) attacker = le;
        else if (damager instanceof Projectile proj && proj.getShooter() instanceof LivingEntity le) attacker = le;

        // 패링 성공: 피해 무효 + 공격자 구속 + 모루 소리
        event.setCancelled(true);
        boolean fever = ctx.isFever();
        if (attacker != null && !attacker.equals(p) && Targets.isEnemy(p, attacker)) {
            attacker.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, fever ? 60 : 40, fever ? 3 : 2));
        }
        if (damager instanceof Projectile proj) proj.remove();

        Location fx = p.getEyeLocation().add(flatDir(p).multiply(0.8)).subtract(0, 0.3, 0);
        Fx.sound(fx, Sound.BLOCK_ANVIL_LAND, 0.8f, 1.6f);
        Fx.sound(fx, Sound.ITEM_SHIELD_BLOCK, 1f, 1.4f);
        p.getWorld().spawnParticle(Particle.CRIT, fx, 20, 0.2, 0.2, 0.2, 0.4);
        p.getWorld().spawnParticle(Particle.ENCHANTED_HIT, fx, 10, 0.2, 0.2, 0.2, 0.2);
        p.sendActionBar(Component.text("패링!", NamedTextColor.GOLD));
    }

    private static boolean isFrontal(Player p, Location from) {
        Vector to = from.toVector().subtract(p.getLocation().toVector()).setY(0);
        if (to.lengthSquared() < 1e-6) return true;
        return to.normalize().dot(flatDir(p)) > 0.0;
    }

    // =====================================================================
    // ⇧우 — 돌진 베기
    // =====================================================================

    @Override
    public void onShiftRightClick(SkillContext ctx) {
        Player p = ctx.player();
        if (inUltimate.contains(p.getUniqueId())) return;
        // 쿨다운 중이면 그냥 바닐라 막기 허용
        if (!ctx.tryCooldown(Slot.SHIFT_RIGHT, DASH_COOLDOWN)) return;
        ctx.cancelVanilla();
        Bukkit.getScheduler().runTask(ctx.plugin(), () -> {
            if (p.isOnline() && p.isHandRaised()) p.clearActiveItem();
        });

        Vector dir = flatDir(p);
        double speed = p.isOnGround() ? 1.15 : 0.55;   // 지면 마찰 기준 ≈ 2.5칸
        p.setVelocity(dir.clone().multiply(speed).setY(0.08));

        double dmg = ctx.isFever() ? DASH_DAMAGE_FEVER : DASH_DAMAGE;
        Fx.sound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1.3f);
        Fx.sound(p.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 0.6f, 1.4f);

        Set<UUID> hit = new HashSet<>();
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || t++ >= 7) {
                    cancel();
                    return;
                }
                Location c = p.getLocation().add(0, 0.9, 0);
                p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0, 0.2, 0), 2, 0.1, 0.05, 0.1, 0.01);
                for (LivingEntity e : Targets.enemiesAround(p, c, DASH_HIT_RADIUS + 0.5)) {
                    if (!hit.add(e.getUniqueId())) continue;
                    Damage.skill(p, e, dmg);
                    Targets.push(e, p.getLocation(), 0.4, 0.25);
                    e.getWorld().spawnParticle(Particle.SWEEP_ATTACK, e.getLocation().add(0, e.getHeight() * 0.6, 0),
                            1, 0, 0, 0, 0);
                    Fx.sound(e.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.8f, 1.1f);
                }
            }
        }.runTaskTimer(ctx.plugin(), 1L, 1L);
    }

    // =====================================================================
    // F — 일섬
    // =====================================================================

    @Override
    public void onUltimate(SkillContext ctx) {
        Player p = ctx.player();
        UUID id = p.getUniqueId();
        if (!inUltimate.add(id)) return;

        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ULT_CHARGE_TICKS + 2, 6, false, false, false));
        Fx.sound(p.getLocation(), Sound.ITEM_TRIDENT_RETURN, 1f, 0.6f);

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead()) {
                    inUltimate.remove(id);
                    cancel();
                    return;
                }
                if (t < ULT_CHARGE_TICKS) {
                    // 멈춰서 기 모으기
                    Vector v = p.getVelocity();
                    p.setVelocity(new Vector(0, Math.min(0, v.getY()), 0));
                    Location c = p.getLocation().add(0, 1.0, 0);
                    double r = 1.6 - 1.2 * t / (double) ULT_CHARGE_TICKS;
                    Fx.ring(p.getLocation().add(0, 0.05, 0), Particle.ENCHANTED_HIT, r, 12);
                    p.getWorld().spawnParticle(Particle.CRIT, c, 3, 0.3, 0.4, 0.3, 0.05);
                    if (t % 4 == 0) Fx.sound(c, Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 0.8f + t * 0.08f);
                    t++;
                    return;
                }
                cancel();
                inUltimate.remove(id);
                slash(p);
            }
        }.runTaskTimer(ctx.plugin(), 1L, 1L);
    }

    private void slash(Player p) {
        Location start = p.getLocation();
        Vector dir = flatDir(p);

        // 막히지 않는 가장 먼 지점 찾기 (벽 통과 금지)
        Location end = start.clone();
        for (double d = 0.25; d <= ULT_RANGE; d += 0.25) {
            Location cand = start.clone().add(dir.clone().multiply(d));
            if (!passable(cand.getBlock()) || !passable(cand.clone().add(0, 1, 0).getBlock())) break;
            end = cand;
        }

        Vector a = start.toVector().add(new Vector(0, 1, 0));
        Vector b = end.toVector().add(new Vector(0, 1, 0));
        double len = b.distance(a);
        double reach = Math.max(len, 1.0) + ULT_WIDTH;
        Location mid = start.clone().add(dir.clone().multiply(len / 2)).add(0, 1, 0);

        // 일직선 위의 적 베기
        for (LivingEntity e : Targets.enemiesAround(p, mid, reach / 2 + 1.5)) {
            Vector c = e.getLocation().toVector().add(new Vector(0, e.getHeight() / 2, 0));
            if (distToSegment(c, a, b) <= ULT_WIDTH) {
                Damage.skill(p, e, ULT_DAMAGE);
                e.getWorld().spawnParticle(Particle.CRIT, c.toLocation(e.getWorld()), 25, 0.3, 0.5, 0.3, 0.5);
                e.getWorld().spawnParticle(Particle.SWEEP_ATTACK, c.toLocation(e.getWorld()), 1, 0, 0, 0, 0);
            }
        }

        // 연출: 크리티컬 일직선 + 순간이동
        Location lineFrom = start.clone().add(0, 1, 0);
        Location lineTo = end.clone().add(0, 1, 0);
        Fx.line(lineFrom, lineTo, Particle.CRIT, 0.2);
        Fx.line(lineFrom, lineTo, Particle.ENCHANTED_HIT, 0.5);
        for (double d = 0.5; d < len; d += 1.5) {
            Location l = lineFrom.clone().add(dir.clone().multiply(d));
            p.getWorld().spawnParticle(Particle.SWEEP_ATTACK, l, 1, 0, 0, 0, 0);
        }
        Fx.sound(start, Sound.ITEM_TRIDENT_THROW, 1f, 1.6f);
        Fx.sound(start, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.5f);

        Location tp = end.clone();
        tp.setYaw(p.getLocation().getYaw());
        tp.setPitch(p.getLocation().getPitch());
        p.teleport(tp);
        p.setFallDistance(0);
        Fx.sound(tp, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.8f);

        // 한 박자 늦은 "베인" 연출
        Location fxFrom = lineFrom.clone();
        Location fxTo = lineTo.clone();
        Bukkit.getScheduler().runTaskLater(DeruyRPG.get(), () -> {
            Fx.line(fxFrom, fxTo, Particle.CRIT, 0.35);
            Fx.sound(fxTo, Sound.BLOCK_ANVIL_LAND, 0.5f, 2.0f);
        }, 6L);
    }

    private static boolean passable(Block b) {
        return b.isPassable() && !b.isLiquid() || b.getType().isAir();
    }

    private static double distToSegment(Vector p, Vector a, Vector b) {
        Vector ab = b.clone().subtract(a);
        double l2 = ab.lengthSquared();
        if (l2 < 1e-9) return p.distance(a);
        double t = Math.max(0, Math.min(1, p.clone().subtract(a).dot(ab) / l2));
        return p.distance(a.clone().add(ab.multiply(t)));
    }

    private static Vector flatDir(Player p) {
        Vector v = p.getLocation().getDirection().setY(0);
        if (v.lengthSquared() < 1e-6) v = new Vector(0, 0, 1);
        return v.normalize();
    }

    // =====================================================================

    @Override
    public void onUnequip(Player player) {
        UUID id = player.getUniqueId();
        Combo c = combos.remove(id);
        if (c != null) applySpeed(player, 0);
        raiseTick.remove(id);
        parryArmed.remove(id);
    }
}
