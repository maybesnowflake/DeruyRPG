package com.fsixel.deruyrpg.weapons.spear;

import com.fsixel.deruyrpg.api.SkillContext;
import com.fsixel.deruyrpg.util.Damage;
import com.fsixel.deruyrpg.util.Fx;
import com.fsixel.deruyrpg.util.Targets;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F 고대 군단 (아지르 궁극기 느낌).
 * 플레이어 양옆으로 병사(사막 미라 허스크 + 창) 6명이 일렬로 서서 앞으로 돌진,
 * 경로의 적을 밀쳐내며 피해 → 멈춘 뒤 각자 작은 창 연속 찌르기(플레이어 우클릭과 같은 연출) → 모래 먼지와 함께 사라짐.
 *
 * 궁극기라 무기를 놓아도(onUnequip) 끊기지 않고 자체 타이머로 끝까지 진행. 접속 종료·사망 때만 즉시 정리.
 * 평화로움 난이도에선 허스크가 바로 사라지므로 마네킹(Mannequin)으로 대신 소환.
 */
final class AncientLegion {

    private static final double[] OFFSETS = {-3, -2, -1, 1, 2, 3};

    private static final int SUMMON_DELAY = 2;      // 소환 후 0.1초 뒤 돌진
    private static final int CHARGE_TICKS = 16;     // 돌진 시간
    private static final double CHARGE_SPEED = 0.55; // 틱당 칸 (≈ 8.8칸)
    private static final int JABS = 4;
    private static final int JAB_INTERVAL = 3;

    private static final double CHARGE_DAMAGE = 7.0;
    private static final double JAB_DAMAGE = 2.5;

    private static final BlockData SAND = Material.SAND.createBlockData();
    private static final Particle.DustOptions GOLD = new Particle.DustOptions(Color.fromRGB(0xE8C15A), 1.3f);

    private final SpearKit kit;
    private final SkillContext ctx;
    private final Player owner;
    private final List<LivingEntity> soldiers = new ArrayList<>();
    /** 돌진에 맞은 적 → 피해가 실제로 들어갔는지 (안 들어갔으면 밀쳐내지 않음) */
    private final Map<UUID, Boolean> chargeHit = new HashMap<>();
    private Vector forward;
    private BukkitTask task;
    private int jabCount = 0;

    AncientLegion(SpearKit kit, SkillContext ctx) {
        this.kit = kit;
        this.ctx = ctx;
        this.owner = ctx.player();
    }

    void start() {
        Location base = owner.getLocation();
        forward = base.getDirection().setY(0);
        if (forward.lengthSquared() < 1e-6) forward = new Vector(0, 0, 1);
        forward.normalize();
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        float yaw = base.getYaw();

        ctx.flash(Component.text("고대 군단!", NamedTextColor.GOLD));
        Fx.sound(base, Sound.BLOCK_BELL_RESONATE, 0.8f, 0.8f);
        Fx.sound(base, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.0f, 0.7f);

        for (double off : OFFSETS) {
            Location at = base.clone().add(right.clone().multiply(off)).subtract(forward.clone().multiply(0.5));
            at = snapToGround(at);
            at.setYaw(yaw);
            at.setPitch(0);
            World w = at.getWorld();
            w.spawnParticle(Particle.BLOCK, at.clone().add(0, 0.5, 0), 25, 0.3, 0.6, 0.3, 0, SAND);
            w.spawnParticle(Particle.DUST, at.clone().add(0, 1, 0), 10, 0.3, 0.6, 0.3, 0, GOLD);
            LivingEntity m = w.getDifficulty() == Difficulty.PEACEFUL
                    ? w.spawn(at, Mannequin.class, this::setupMannequin)
                    : w.spawn(at, Husk.class, this::setupSoldier);
            soldiers.add(m);
        }

        task = ctx.plugin().getServer().getScheduler().runTaskTimer(ctx.plugin(), new Runnable() {
            int tick = 0;

            @Override
            public void run() {
                tick++;
                if (!owner.isOnline() || owner.isDead()) {
                    finish();
                    return;
                }
                int chargeStart = SUMMON_DELAY;
                int chargeEnd = chargeStart + CHARGE_TICKS;
                int jabEnd = chargeEnd + 4 + JABS * JAB_INTERVAL;

                if (tick == chargeStart) {
                    Fx.sound(owner.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 0.8f, 1.2f);
                    Fx.sound(owner.getLocation(), Sound.ITEM_ARMOR_EQUIP_GOLD, 1.0f, 0.8f);
                }
                if (tick >= chargeStart && tick < chargeEnd) {
                    chargeStep();
                } else if (tick > chargeEnd + 2 && tick <= jabEnd
                        && (tick - chargeEnd - 3) % JAB_INTERVAL == 0) {
                    jabStep();
                } else if (tick > jabEnd + 4) {
                    finish();
                }
            }
        }, 1L, 1L);
    }

    private void setupSoldier(Husk m) {
        Targets.markSummon(m);
        m.setPersistent(false);
        m.setInvulnerable(true);
        m.setAware(false);
        m.setAI(false);
        m.setGravity(false);
        m.setCollidable(false);
        m.setSilent(true);
        m.setRemoveWhenFarAway(false);
        m.setCanPickupItems(false);
        m.setBaby(false);
        m.setShouldBurnInDay(false);
        m.setConversionTime(-1);
        m.customName(Component.text("고대 병사", NamedTextColor.GOLD));
        m.setCustomNameVisible(false);
        EntityEquipment eq = m.getEquipment();
        eq.clear();
        eq.setItemInMainHand(new ItemStack(Material.NETHERITE_SPEAR));
        eq.setHelmet(new ItemStack(Material.GOLDEN_HELMET));
        eq.setItemInMainHandDropChance(0f);
        eq.setHelmetDropChance(0f);
    }

    /** 평화로움 난이도용 병사 (몬스터가 아니라 사라지지 않음). */
    private void setupMannequin(Mannequin m) {
        Targets.markSummon(m);
        m.setPersistent(false);
        m.setInvulnerable(true);
        m.setAI(false);
        m.setGravity(false);
        m.setCollidable(false);
        m.setImmovable(true);
        m.setSilent(true);
        m.setRemoveWhenFarAway(false);
        m.setCanPickupItems(false);
        m.setDescription(null);
        m.customName(Component.text("고대 병사", NamedTextColor.GOLD));
        m.setCustomNameVisible(false);
        EntityEquipment eq = m.getEquipment();
        eq.clear();
        eq.setItemInMainHand(new ItemStack(Material.NETHERITE_SPEAR));
        eq.setHelmet(new ItemStack(Material.GOLDEN_HELMET));
        eq.setItemInMainHandDropChance(0f);
        eq.setHelmetDropChance(0f);
    }

    /** 병사 전원 한 칸씩 전진, 경로의 적 밀쳐내기 + 피해. */
    private void chargeStep() {
        double dmg = ctx.isFever() ? CHARGE_DAMAGE * 1.2 : CHARGE_DAMAGE;
        for (LivingEntity m : soldiers) {
            if (!m.isValid()) continue;
            Location cur = m.getLocation();
            Location next = cur.clone().add(forward.clone().multiply(CHARGE_SPEED));
            next = stepTerrain(cur, next);
            if (next == null) continue; // 벽에 막힘
            m.teleport(next);
            World w = next.getWorld();
            w.spawnParticle(Particle.BLOCK, next.clone().add(0, 0.1, 0), 4, 0.3, 0.05, 0.3, 0, SAND);
            w.spawnParticle(Particle.DUST, next.clone().add(0, 1.0, 0), 2, 0.2, 0.5, 0.2, 0, GOLD);

            for (Entity e : w.getNearbyEntities(next.clone().add(0, 1, 0), 1.0, 1.2, 1.0)) {
                if (!Targets.isEnemy(owner, e)) continue;
                LivingEntity t = (LivingEntity) e;
                Boolean passed = chargeHit.get(t.getUniqueId());
                if (passed == null) {
                    t.setNoDamageTicks(0);
                    passed = Damage.skill(owner, t, dmg);
                    chargeHit.put(t.getUniqueId(), passed);
                    if (passed) {
                        Fx.sound(t.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.8f, 0.8f);
                        Fx.burst(t.getLocation().add(0, t.getHeight() * 0.6, 0), Particle.CRIT, 10, 0.3);
                    }
                }
                // 피해가 들어간 적만 계속 앞으로 밀어냄 (PvP 꺼짐·보호구역이면 안 밂)
                if (passed) Targets.velocity(t, forward.clone().multiply(1.1).setY(0.35)); // 넉백 면역이면 안 밀림
            }
        }
        if (!soldiers.isEmpty() && soldiers.get(0).isValid()) {
            Fx.sound(soldiers.get(0).getLocation(), Sound.BLOCK_SAND_STEP, 1.0f, 0.8f);
        }
    }

    /** 각 병사가 앞으로 한 번 찌르기. */
    private void jabStep() {
        jabCount++;
        double dmg = ctx.isFever() ? JAB_DAMAGE * 1.2 : JAB_DAMAGE;
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        boolean sound = true;
        for (LivingEntity m : soldiers) {
            if (!m.isValid()) continue;
            m.swingMainHand();
            Location from = m.getLocation().add(0, 1.35, 0);
            kit.miniSpears(owner, from, forward, 3.0, 2, jabCount, true);
            if (sound) {
                Fx.sound(from, Sound.ITEM_SPEAR_ATTACK, 0.9f, 1.2f + jabCount * 0.06f);
                sound = false;
            }
            Location origin = m.getLocation();
            for (Entity e : origin.getWorld().getNearbyEntities(origin.clone().add(forward.clone().multiply(2)), 2.5, 2.0, 2.5)) {
                if (!Targets.isEnemy(owner, e)) continue;
                Vector rel = e.getLocation().toVector().subtract(origin.toVector());
                double f = rel.dot(forward);
                double s = rel.dot(right);
                if (f < 0 || f > 3.5 || Math.abs(s) > 0.9 || rel.getY() < -1 || rel.getY() > 2.5) continue;
                LivingEntity t = (LivingEntity) e;
                t.setNoDamageTicks(0);
                if (!Damage.skill(owner, t, dmg)) continue;
                t.setVelocity(t.getVelocity().multiply(0.3));
                Fx.burst(t.getLocation().add(0, t.getHeight() * 0.6, 0), Particle.ENCHANTED_HIT, 4, 0.2);
            }
        }
    }

    private void finish() {
        if (task != null) task.cancel();
        boolean sound = true;
        for (LivingEntity m : soldiers) {
            if (m.isValid()) {
                Location at = m.getLocation();
                at.getWorld().spawnParticle(Particle.BLOCK, at.clone().add(0, 1, 0), 30, 0.3, 0.7, 0.3, 0, SAND);
                at.getWorld().spawnParticle(Particle.POOF, at.clone().add(0, 1, 0), 8, 0.3, 0.6, 0.3, 0.02);
                if (sound) {
                    Fx.sound(at, Sound.BLOCK_SAND_BREAK, 1.0f, 0.7f);
                    sound = false;
                }
                m.remove();
            }
        }
        soldiers.clear();
    }

    // ---- 지형 ----

    private static boolean solid(Block b) {
        return b.getType().isSolid();
    }

    /** 아래쪽 바닥으로 최대 3칸 내림. */
    private static Location snapToGround(Location at) {
        Location l = at.clone();
        for (int i = 0; i < 3 && !solid(l.clone().subtract(0, 0.1, 0).getBlock()); i++) {
            l.subtract(0, 1, 0);
        }
        if (!solid(l.clone().subtract(0, 0.1, 0).getBlock())) return at.clone();
        l.setY(Math.floor(l.getY() - 0.1) + 1);
        return l;
    }

    /** 다음 위치: 한 칸 턱은 오르고, 한두 칸 내리막은 따라감. 벽(2칸 높이)이면 null. */
    private static Location stepTerrain(Location cur, Location next) {
        Block feet = next.getBlock();
        if (solid(feet)) {
            Location up = next.clone().add(0, 1, 0);
            if (solid(up.getBlock()) || solid(up.clone().add(0, 1, 0).getBlock())) return null;
            up.setY(Math.floor(up.getY()));
            return up;
        }
        if (solid(next.clone().add(0, 1, 0).getBlock())) return null;
        Location below = next.clone().subtract(0, 0.1, 0);
        if (!solid(below.getBlock())) {
            Location down = next.clone().subtract(0, 1, 0);
            if (solid(down.clone().subtract(0, 0.1, 0).getBlock())) {
                down.setY(Math.floor(down.getY() - 0.1) + 1);
                return down;
            }
            Location down2 = next.clone().subtract(0, 2, 0);
            if (solid(down2.clone().subtract(0, 0.1, 0).getBlock())) {
                down2.setY(Math.floor(down2.getY() - 0.1) + 1);
                return down2;
            }
        }
        return next;
    }
}
