package com.fsixel.deruyrpg.weapons.axe;

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
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Boss;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 도끼 (광전사) — 스킬 명세: docs/SKILLS.md 의 "도끼" 항목.
 *
 * P  광폭화   : 체력이 낮을수록 공격력·공격 속도 증가 (체력 30% 이하에서 최대). 무기를 놓으면 제거.
 * 우 회전 베기 : 주변 4×4 범위 공격 + 방패 무력화. 내 체력 2(1칸) 소모. 도끼 3자루가 주변을 한 바퀴 돎.
 * 좌 내려찍기 : 평타. 준 피해의 약 3.75% 회복.
 * ⇧우 피의 도끼: 큰 도끼를 앞으로 내려찍어 전방 적에게 출혈 도트 + 나에게 힘 II 5초.
 * F  처형     : 큰 도끼가 반경 6칸을 한 바퀴 휩씀. 체력 20% 이하 적 처형 + 피버 동안 힘 + CC 무시.
 *
 * 피버(15초): 흡혈 ×2, 평타가 주변 2칸 전방위 공격(작은 도끼 회전), 신속 III,
 *            회전 베기 체력 소모 없음 + 범위 6×6(±3칸).
 */
public final class AxeKit implements WeaponKit, Listener {

    // ---- 수치 ----
    private static final double RAGE_MAX_DAMAGE = 0.35;   // 최대 공격력 +35%
    private static final double RAGE_MAX_SPEED = 0.30;    // 최대 공격 속도 +30%
    private static final double RAGE_FULL_AT = 0.30;      // 체력 30% 이하에서 최대

    private static final int SPIN_COOLDOWN = 100;         // 5초
    private static final double SPIN_HP_COST = 2.0;       // 체력 1칸
    private static final double SPIN_HALF = 2.0;          // 4×4 → 중심에서 ±2칸
    private static final double SPIN_HALF_FEVER = 3.0;    // 피버: 6×6 → ±3칸
    private static final double SPIN_DAMAGE = 7.0;
    private static final int SHIELD_DISABLE_TICKS = 100;

    private static final double LIFESTEAL = 0.0375;              // 15% 에서 75% 감소
    private static final double LIFESTEAL_FEVER = LIFESTEAL * 2; // 피버: 흡혈량 +100%
    private static final double FEVER_SWEEP_RADIUS = 2.0;        // 피버 평타 전방위 반경
    private static final double FEVER_AIR_SWING_RATIO = 0.8;     // 허공 스윙 전방위 피해 = 공격력 × 0.8

    private static final int BLOOD_COOLDOWN = 240;        // 12초
    private static final double BLOOD_HIT = 5.0;
    private static final double BLEED_TICK_DAMAGE = 1.0;
    private static final int BLEED_INTERVAL = 10;
    private static final int BLEED_COUNT = 8;             // 4초

    private static final double EXECUTE_THRESHOLD = 0.20;
    private static final double EXECUTE_DAMAGE = 1000.0;
    private static final double EXECUTE_BOSS_DAMAGE = 40.0;
    private static final double EXECUTE_RADIUS = 6.0;
    private static final double EXECUTE_FRONT = 9.0;
    private static final double ULT_SPLASH = 6.0;

    // ---- 키 ----
    private static final NamespacedKey RAGE_DMG_KEY = new NamespacedKey("deruyrpg", "axe_rage_damage");
    private static final NamespacedKey RAGE_SPD_KEY = new NamespacedKey("deruyrpg", "axe_rage_speed");
    private static final NamespacedKey KB_KEY = new NamespacedKey("deruyrpg", "axe_unstoppable");

    private static final BlockData BLOOD = Material.REDSTONE_BLOCK.createBlockData();

    // ---- 상태 ----
    /** 광폭화 modifier 가 붙어 있는 플레이어 → 현재 단계(0~1, 0.05 단위). */
    private final Map<UUID, Double> rage = new ConcurrentHashMap<>();
    /** 처형 후 CC 무시 중인 플레이어 → 매 틱 작업. */
    private final Map<UUID, BukkitTask> unstoppable = new ConcurrentHashMap<>();
    /** 출혈 중인 대상 → 도트 작업. */
    private final Map<UUID, BukkitTask> bleeds = new ConcurrentHashMap<>();
    /** 피버 전방위 평타 마지막 발동 틱 (허공 스윙 남용 방지). */
    private final Map<UUID, Integer> lastSweep = new ConcurrentHashMap<>();
    private final AxeDisplays displays = new AxeDisplays();
    private BukkitTask sweeper;
    private int passiveCounter;

    @Override
    public String id() {
        return "axe";
    }

    @Override
    public String displayName() {
        return "도끼";
    }

    @Override
    public ItemStack createItem() {
        return Items.weapon(Material.NETHERITE_AXE, id(), "도끼", NamedTextColor.RED,
                "P  광폭화 (체력이 낮을수록 강해짐)",
                "우  회전 베기 (체력 1칸 소모, 방패 무력화)",
                "좌  내려찍기 (흡혈)",
                "⇧우  피의 도끼 (출혈 + 힘 II)",
                "F  처형 (20% 이하) + CC 무시");
    }

    @Override
    public String[] cooldownSlots() {
        return new String[]{Slot.RIGHT, Slot.SHIFT_RIGHT};
    }

    private static boolean holdsAxe(Player p) {
        return "axe".equals(Items.kitId(p.getInventory().getItemInMainHand()));
    }

    // =====================================================================
    // P 광폭화
    // =====================================================================

    @Override
    public void onPassiveTick(SkillContext ctx) {
        ensureSweeper(ctx.plugin());
        Player p = ctx.player();
        double max = maxHealth(p);
        double ratio = max <= 0 ? 1 : p.getHealth() / max;
        // 100% → 0, 30% 이하 → 1 (선형)
        double t = Math.max(0, Math.min(1, (1 - ratio) / (1 - RAGE_FULL_AT)));
        t = Math.round(t * 20) / 20.0;
        if (ctx.isFever()) t = 1; // 피버 동안 광폭화는 체력과 무관하게 최대
        applyRage(p, t);

        // 피버: 신속 III
        if (ctx.isFever()) {
            PotionEffect cur = p.getPotionEffect(PotionEffectType.SPEED);
            if (cur == null || (cur.getAmplifier() <= 2 && cur.getDuration() < 10)) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 15, 2, true, false, true));
            }
        }

        if (t >= 1 && (++passiveCounter % 4 == 0)) {
            p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 6, 0.35, 0.5, 0.35, 0,
                    new Particle.DustOptions(Color.fromRGB(170, 0, 0), 1.2f));
        }
    }

    private void applyRage(Player p, double t) {
        Double prev = rage.get(p.getUniqueId());
        if (prev != null && prev == t) return;
        if (t <= 0) {
            clearRage(p);
            return;
        }
        setModifier(p, Attribute.ATTACK_DAMAGE, RAGE_DMG_KEY, RAGE_MAX_DAMAGE * t);
        setModifier(p, Attribute.ATTACK_SPEED, RAGE_SPD_KEY, RAGE_MAX_SPEED * t);
        rage.put(p.getUniqueId(), t);
    }

    private void clearRage(Player p) {
        removeModifier(p, Attribute.ATTACK_DAMAGE, RAGE_DMG_KEY);
        removeModifier(p, Attribute.ATTACK_SPEED, RAGE_SPD_KEY);
        rage.remove(p.getUniqueId());
    }

    /** onUnequip 이 안 불리는 경우(버리기·사망·인벤 이동 등) 대비 정리 작업. */
    private void ensureSweeper(DeruyRPG plugin) {
        if (sweeper != null && !sweeper.isCancelled()) return;
        sweeper = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (UUID id : new ArrayList<>(rage.keySet())) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) {
                    rage.remove(id);
                } else if (!holdsAxe(p) || p.isDead()) {
                    clearRage(p);
                }
            }
        }, 10L, 10L);
    }

    // =====================================================================
    // 우 회전 베기
    // =====================================================================

    @Override
    public void onRightClick(SkillContext ctx) {
        ctx.cancelVanilla(); // 통나무 벗기기 등 바닐라 동작 방지
        Player p = ctx.player();
        boolean fever = ctx.isFever();
        if (!fever && p.getHealth() - SPIN_HP_COST < 1.0) {
            p.sendActionBar(Component.text("체력이 부족해 회전 베기를 쓸 수 없다", NamedTextColor.RED));
            Fx.sound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.6f);
            return;
        }
        if (!ctx.tryCooldown(Slot.RIGHT, SPIN_COOLDOWN)) return;

        if (!fever) {
            // 체력 소모 + 피 튀는 연출
            p.setHealth(Math.max(1.0, p.getHealth() - SPIN_HP_COST));
            p.playHurtAnimation(0);
            Location body = p.getLocation().add(0, 1, 0);
            p.getWorld().spawnParticle(Particle.BLOCK, body, 30, 0.3, 0.45, 0.3, 0, BLOOD);
            p.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, body, 4, 0.3, 0.3, 0.3, 0.1);
            p.getWorld().spawnParticle(Particle.DUST, body, 12, 0.35, 0.5, 0.35, 0,
                    new Particle.DustOptions(Color.fromRGB(150, 0, 0), 1.3f));
            Fx.sound(p.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 0.8f);
            Fx.sound(p.getLocation(), Sound.BLOCK_HONEY_BLOCK_BREAK, 0.7f, 0.6f);
        }

        double half = fever ? SPIN_HALF_FEVER : SPIN_HALF;
        Location c = p.getLocation();
        double dmg = fever ? SPIN_DAMAGE + 2 : SPIN_DAMAGE;
        for (LivingEntity e : Targets.enemiesAround(p, c, half * 1.5 + 0.5)) {
            Location l = e.getLocation();
            double dx = Math.abs(l.getX() - c.getX());
            double dz = Math.abs(l.getZ() - c.getZ());
            double dy = l.getY() - c.getY();
            if (dx > half + 0.3 || dz > half + 0.3 || dy < -1.5 || dy > 2.5) continue;

            if (e instanceof Player tp) {
                tp.setCooldown(Material.SHIELD, SHIELD_DISABLE_TICKS);
                if (tp.isBlocking() || tp.hasActiveItem()) {
                    tp.clearActiveItem();
                    Fx.sound(tp.getLocation(), Sound.ITEM_SHIELD_BREAK, 1f, 0.9f);
                }
            } else if (e.hasActiveItem()) {
                e.clearActiveItem();
            }
            e.setNoDamageTicks(0);
            Damage.skill(p, e, dmg);
            Targets.push(e, c, 0.45, 0.2);
            e.getWorld().spawnParticle(Particle.BLOCK, e.getLocation().add(0, 1, 0), 10, 0.25, 0.35, 0.25, 0, BLOOD);
        }

        // 연출: 도끼 3자루만 빠르게 한 바퀴 (휩쓸기 파티클·검 소리 없음)
        displays.orbit(ctx.plugin(), p, 3, half + 0.2, 1.3f, 1.0, 8, 1.0, null, null);
        Fx.sound(c, Sound.ITEM_AXE_SCRAPE, 0.8f, 0.6f);
        Fx.sound(c, Sound.ITEM_AXE_STRIP, 1f, 0.6f);
        Fx.sound(c, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.6f, 1.4f);
    }

    // =====================================================================
    // 좌 내려찍기 (흡혈)
    // =====================================================================

    @Override
    public void onMeleeHit(SkillContext ctx, LivingEntity target, EntityDamageByEntityEvent event) {
        Player p = ctx.player();
        if (p.isDead()) return;
        boolean fever = ctx.isFever();
        double dealt = Math.min(event.getFinalDamage(), target.getHealth());
        if (dealt > 0) lifesteal(p, dealt, fever);
        target.getWorld().spawnParticle(Particle.BLOCK, target.getLocation().add(0, 1, 0), 6, 0.25, 0.3, 0.25, 0, BLOOD);

        // 피버: 전방위 평타 — 원래 맞은 적에게도 범위 피해가 들어가도록 다음 틱에 무적시간을 지우고 따로 적용
        if (fever && event.getDamage() > 0) {
            double sweepDmg = event.getDamage();
            feverSweep(ctx, sweepDmg, target);
            Bukkit.getScheduler().runTask(ctx.plugin(), () -> {
                if (!target.isValid() || target.isDead() || !p.isOnline() || p.isDead()) return;
                double before = target.getHealth();
                target.setNoDamageTicks(0);
                Damage.skill(p, target, sweepDmg);
                double done = Math.max(0, before - (target.isDead() ? 0 : target.getHealth()));
                if (done > 0) lifesteal(p, done, true);
            });
        }
    }

    /** 피버 중엔 허공 스윙도 주변 적을 벤다 (공격 속도 기준으로 제한). */
    @Override
    public void onLeftClick(SkillContext ctx) {
        if (!ctx.isFever()) return;
        Player p = ctx.player();
        Integer last = lastSweep.get(p.getUniqueId());
        AttributeInstance spd = p.getAttribute(Attribute.ATTACK_SPEED);
        int gap = (int) Math.ceil(20.0 / Math.max(0.5, spd == null ? 1.0 : spd.getValue()));
        if (last != null && Bukkit.getCurrentTick() - last < gap) return;
        AttributeInstance atk = p.getAttribute(Attribute.ATTACK_DAMAGE);
        double dmg = (atk == null ? 9.0 : atk.getValue()) * FEVER_AIR_SWING_RATIO;
        feverSweep(ctx, dmg, null);
    }

    private void feverSweep(SkillContext ctx, double dmg, LivingEntity exclude) {
        Player p = ctx.player();
        lastSweep.put(p.getUniqueId(), Bukkit.getCurrentTick());
        double total = 0;
        for (LivingEntity e : Targets.enemiesAround(p, p.getLocation(), FEVER_SWEEP_RADIUS + 0.5)) {
            if (e.equals(exclude)) continue;
            double before = e.getHealth();
            e.setNoDamageTicks(0);
            Damage.skill(p, e, dmg);
            total += Math.max(0, before - (e.isDead() ? 0 : e.getHealth()));
            e.getWorld().spawnParticle(Particle.BLOCK, e.getLocation().add(0, 1, 0), 6, 0.25, 0.3, 0.25, 0, BLOOD);
        }
        if (total > 0) lifesteal(p, total, true);
        displays.orbit(ctx.plugin(), p, 2, FEVER_SWEEP_RADIUS, 0.8f, 1.0, 6, 1.0, null, null);
        Fx.sound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 1.1f);
    }

    private void lifesteal(Player p, double dealt, boolean fever) {
        double heal = dealt * (fever ? LIFESTEAL_FEVER : LIFESTEAL);
        double max = maxHealth(p);
        if (heal <= 0 || p.getHealth() >= max) return;
        p.setHealth(Math.min(max, p.getHealth() + heal));
        if (heal >= 1.0) {
            p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 2.1, 0), 1, 0.2, 0.1, 0.2, 0);
        }
    }

    // =====================================================================
    // ⇧우 피의 도끼
    // =====================================================================

    @Override
    public void onShiftRightClick(SkillContext ctx) {
        ctx.cancelVanilla();
        Player p = ctx.player();
        if (!ctx.tryCooldown(Slot.SHIFT_RIGHT, BLOOD_COOLDOWN)) return;

        boolean fever = ctx.isFever();
        double tick = fever ? BLEED_TICK_DAMAGE * 1.5 : BLEED_TICK_DAMAGE;
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 100, 1, false, true, true));
        Fx.sound(p.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.4f, 1.6f);
        Fx.sound(p.getLocation(), Sound.ITEM_TRIDENT_THROW, 0.8f, 0.5f);

        // 큰 도끼가 들렸다가 앞으로 내려찍힘 → 닿는 순간 피해
        Location base = p.getLocation();
        base.setPitch(0);
        Vector fwd = base.getDirection();
        displays.chop(ctx.plugin(), p, 3.0f, () -> {
            List<LivingEntity> hit = Targets.frontBox(p, 3, 4, 2.5);
            for (LivingEntity e : hit) {
                e.setNoDamageTicks(0);
                Damage.skill(p, e, BLOOD_HIT);
                startBleed(ctx.plugin(), p, e, tick);
            }
            Location impact = base.clone().add(fwd.clone().multiply(3.2)).add(0, 0.2, 0);
            p.getWorld().spawnParticle(Particle.BLOCK, impact, 40, 0.9, 0.3, 0.9, 0, BLOOD);
            p.getWorld().spawnParticle(Particle.EXPLOSION, impact, 1, 0, 0, 0, 0);
            p.getWorld().spawnParticle(Particle.CRIT, impact, 20, 0.8, 0.2, 0.8, 0.3);
            Fx.ring(impact, Particle.DAMAGE_INDICATOR, 1.5, 12);
            Fx.sound(impact, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 0.6f);
            Fx.sound(impact, Sound.BLOCK_ANVIL_LAND, 0.7f, 0.5f);
            Fx.sound(impact, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 0.8f);
            if (!hit.isEmpty()) {
                p.sendActionBar(Component.text("출혈! (" + hit.size() + ")", NamedTextColor.DARK_RED));
            }
        });
    }

    private void startBleed(DeruyRPG plugin, Player attacker, LivingEntity target, double perTick) {
        UUID tid = target.getUniqueId();
        BukkitTask old = bleeds.remove(tid);
        if (old != null) old.cancel();

        BukkitTask task = new BukkitRunnable() {
            int left = BLEED_COUNT;

            @Override
            public void run() {
                if (left-- <= 0 || !target.isValid() || target.isDead() || !attacker.isOnline()) {
                    BukkitTask cur = bleeds.get(tid);
                    if (cur != null && cur.getTaskId() == getTaskId()) bleeds.remove(tid);
                    cancel();
                    return;
                }
                target.setNoDamageTicks(0);
                Damage.magic(attacker, target, perTick);
                target.getWorld().spawnParticle(Particle.BLOCK, target.getLocation().add(0, 1, 0),
                        10, 0.25, 0.4, 0.25, 0, BLOOD);
            }
        }.runTaskTimer(plugin, BLEED_INTERVAL, BLEED_INTERVAL);
        bleeds.put(tid, task);
    }

    // =====================================================================
    // F 처형
    // =====================================================================

    @Override
    public void onUltimate(SkillContext ctx) {
        Player p = ctx.player();
        Location c = p.getLocation();

        Set<LivingEntity> targets = new LinkedHashSet<>(Targets.enemiesAround(p, c, EXECUTE_RADIUS));
        targets.addAll(Targets.frontBox(p, 3, EXECUTE_FRONT, 2.5));

        int executed = 0;
        for (LivingEntity e : targets) {
            double max = maxHealth(e);
            e.setNoDamageTicks(0);
            if (max > 0 && e.getHealth() / max <= EXECUTE_THRESHOLD) {
                Location l = e.getLocation();
                Damage.pierce(p, e, e instanceof Boss ? EXECUTE_BOSS_DAMAGE : EXECUTE_DAMAGE);
                l.getWorld().spawnParticle(Particle.BLOCK, l.clone().add(0, 1, 0), 60, 0.4, 0.7, 0.4, 0, BLOOD);
                l.getWorld().spawnParticle(Particle.SWEEP_ATTACK, l.clone().add(0, 1, 0), 3, 0.3, 0.3, 0.3, 0);
                Fx.sound(l, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.5f);
                Fx.sound(l, Sound.BLOCK_ANVIL_LAND, 0.6f, 0.6f);
                executed++;
            } else {
                Damage.skill(p, e, ULT_SPLASH);
                Targets.push(e, c, 0.6, 0.25);
                Fx.burst(e.getLocation().add(0, 1, 0), Particle.CRIT, 10, 0.3);
            }
        }

        // 큰 도끼가 처형 반경을 크게 한 바퀴 휩씀
        displays.orbit(ctx.plugin(), p, 1, EXECUTE_RADIUS, 3.2f, 1.2, 12, 1.0, Particle.SWEEP_ATTACK,
                tip -> tip.getWorld().spawnParticle(Particle.BLOCK, tip, 4, 0.2, 0.2, 0.2, 0, BLOOD));
        Fx.ring(c, Particle.DAMAGE_INDICATOR, 3, 30);
        Fx.ring(c, Particle.CRIMSON_SPORE, EXECUTE_RADIUS, 48);
        Fx.sound(c, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.5f);
        Fx.sound(c, Sound.ENTITY_RAVAGER_ROAR, 1f, 0.9f);
        p.sendActionBar(Component.text(executed > 0 ? "처형! (" + executed + ")" : "광전사의 포효!", NamedTextColor.DARK_RED));

        int duration = Math.max(20, ctx.fever().feverRemaining(p));
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, duration, 0, false, true, true));
        startUnstoppable(ctx.plugin(), p);
    }

    private void startUnstoppable(DeruyRPG plugin, Player p) {
        UUID id = p.getUniqueId();
        stopUnstoppable(p);
        setModifierAdd(p, Attribute.KNOCKBACK_RESISTANCE, KB_KEY, 1.0);

        BukkitTask task = new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || !plugin.fever().isFever(p)) {
                    stopUnstoppable(p);
                    return;
                }
                p.removePotionEffect(PotionEffectType.SLOWNESS);
                p.removePotionEffect(PotionEffectType.LEVITATION);
                if (++t % 5 == 0) {
                    p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 0.2, 0), 4, 0.3, 0.05, 0.3, 0,
                            new Particle.DustOptions(Color.fromRGB(120, 0, 0), 1.0f));
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
        unstoppable.put(id, task);
    }

    private void stopUnstoppable(Player p) {
        BukkitTask task = unstoppable.remove(p.getUniqueId());
        if (task != null) task.cancel();
        removeModifier(p, Attribute.KNOCKBACK_RESISTANCE, KB_KEY);
    }

    /** 처형 CC 무시 중에는 구속·공중부양 적용 자체를 막음 (매 틱 제거와 별개로). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPotion(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!unstoppable.containsKey(p.getUniqueId())) return;
        if (e.getAction() != EntityPotionEffectEvent.Action.ADDED && e.getAction() != EntityPotionEffectEvent.Action.CHANGED) return;
        PotionEffectType type = e.getModifiedType();
        if (type == PotionEffectType.SLOWNESS || type == PotionEffectType.LEVITATION) {
            e.setCancelled(true);
        }
    }

    // =====================================================================
    // 정리
    // =====================================================================

    @Override
    public void onUnequip(Player player) {
        clearRage(player);
        stopUnstoppable(player);
        displays.clear(player);
        lastSweep.remove(player.getUniqueId());
        PotionEffect sp = player.getPotionEffect(PotionEffectType.SPEED);
        if (sp != null && sp.getAmplifier() == 2 && sp.getDuration() <= 15) {
            player.removePotionEffect(PotionEffectType.SPEED);
        }
    }

    // =====================================================================
    // 도우미
    // =====================================================================

    private static double maxHealth(LivingEntity e) {
        AttributeInstance inst = e.getAttribute(Attribute.MAX_HEALTH);
        return inst == null ? 20.0 : inst.getValue();
    }

    /** 최종값 배율(+amount×100%) modifier 를 멱등하게 교체. */
    private static void setModifier(Player p, Attribute attr, NamespacedKey key, double amount) {
        AttributeInstance inst = p.getAttribute(attr);
        if (inst == null) return;
        inst.removeModifier(key);
        inst.addTransientModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                EquipmentSlotGroup.ANY));
    }

    /** 고정값 더하기 modifier 를 멱등하게 교체. */
    private static void setModifierAdd(Player p, Attribute attr, NamespacedKey key, double amount) {
        AttributeInstance inst = p.getAttribute(attr);
        if (inst == null) return;
        inst.removeModifier(key);
        inst.addTransientModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.ANY));
    }

    private static void removeModifier(Player p, Attribute attr, NamespacedKey key) {
        AttributeInstance inst = p.getAttribute(attr);
        if (inst != null) inst.removeModifier(key);
    }
}
