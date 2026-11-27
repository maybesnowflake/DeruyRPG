package com.fsixel.deruyrpg.boss.moss;

import com.fsixel.deruyrpg.DeruyRPG;
import com.fsixel.deruyrpg.boss.BossSettings;
import com.fsixel.deruyrpg.boss.moss.MossModel.Bone;
import com.fsixel.deruyrpg.boss.moss.MossModel.Model;
import com.fsixel.deruyrpg.boss.moss.MossModel.Pose;
import com.fsixel.deruyrpg.util.Cc;
import com.fsixel.deruyrpg.util.Damage;
import com.fsixel.deruyrpg.util.Targets;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Bogged;
import org.bukkit.entity.Creaking;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Slime;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 첫 정글 던전 보스 "이끼 수문장".
 *
 * 1페이즈: 땅딸막한 이끼 바위 (내려치기 / 회전 덩굴 / 가시 발아)
 * 체력 50% → 몸을 숙였다 일어나며 각성. 최대 체력 ×1.5 로 가득 채워 2페이즈 시작.
 * 2페이즈: 마인크래프트 던전스 정글 보스(Jungle Abomination) 기술
 *   - 석상 곤봉 내려찍기 (이동·회전 불가, 독)
 *   - 덩굴 촉수를 바닥에 꽂아 덩굴 4~10개 분출 (원형 8 / 표적 / 사각 4), 덩굴은 무적·솟을 때 피해
 *   - 몸에서 독침 12발 × 2링
 *   - 졸개 소환 (속삭이는 덩굴 2~4, 체력 절반 아래면 도약잎 2 추가)
 *   - 체력 25% 깎일 때마다 5초 그로기 (받는 피해 +60%)
 *   - 체력 절반 아래: 덩굴 분출 뒤 독침 연계
 *
 * 몸은 히트박스(투명 슬라임, AI 없음) + BlockDisplay 리그. 보스가 만든 엔티티는 전부 MossRig.BOSS_KEY 태그.
 */
public final class MossWarden {

    enum State { IDLE, ATTACK, TRANSITION, STAGGER, DYING, DEAD }

    // ---- 수치 (damage-multiplier 곱함)
    private static final double P1_SLAM_DMG = 10, P1_SLAM_RADIUS = 2.3;
    private static final double P1_SPIN_DMG = 3;
    private static final int P1_SPIN_STUN = 20, P1_SPIN_GRACE = 35;
    private static final double P1_THORN_DMG = 6;
    private static final int SPIKE_HIT_TICK = 3;
    private static final double SPIKE_HIT_RADIUS = 0.8;
    private static final double P2_SLAM_DMG = 13, P2_SLAM_RADIUS = 3.0;
    private static final double P2_VINE_DMG = P2_SLAM_DMG * 0.4, P2_VINE_TOUCH = 2;
    private static final double P2_DART_DMG = 2.5;
    private static final double STAGGER_TAKEN = 1.6;
    private static final int STAGGER_TICKS = 100;
    private static final int TRANSITION_TICKS = 110;
    private static final double AGGRO = 32, LEASH = 24;

    private final DeruyRPG plugin;
    private final BossSettings cfg;
    private final World world;
    private final Location home;
    private Location anchor;
    private float yaw;

    private Slime hitbox;
    private MossRig rig;
    private @Nullable MossRig rig2;
    private float[] grow1, grow2;
    private int[] growDelay2;
    private int phase;
    private double maxHp;

    private State state = State.IDLE;
    private int age, stateT;
    private @Nullable Attack attack;
    private final Deque<Attack> queue = new ArrayDeque<>();
    private int nextAttack;
    private String lastAttack = "";
    private int repeat;
    private boolean walking;
    private int walkT;
    private int staggerStage;
    private int summonReadyAt;
    private boolean pendingTransition, pendingDeath;

    private final BossBar bar;
    private final Set<UUID> viewers = new HashSet<>();
    private final List<Effect> effects = new ArrayList<>();
    private final List<LivingEntity> minions = new ArrayList<>();
    private final Set<Long> tickets = new HashSet<>();
    private BukkitTask task;

    private MossWarden(DeruyRPG plugin, BossSettings cfg, Location loc, boolean startPhase2) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.world = loc.getWorld();
        this.home = loc.clone();
        this.anchor = loc.clone();
        this.yaw = loc.getYaw();
        this.phase = startPhase2 ? 2 : 1;
        this.bar = BossBar.bossBar(name(), 1f, phase == 1 ? BossBar.Color.GREEN : BossBar.Color.PURPLE,
                BossBar.Overlay.NOTCHED_10);
    }

    public static MossWarden spawn(DeruyRPG plugin, BossSettings cfg, Location loc, boolean phase2) {
        MossWarden w = new MossWarden(plugin, cfg, loc, phase2);
        w.start();
        return w;
    }

    private void start() {
        int cx = anchor.getBlockX() >> 4, cz = anchor.getBlockZ() >> 4;
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (world.addPluginChunkTicket(cx + x, cz + z, plugin)) tickets.add(((long) (cx + x) << 32) ^ ((cz + z) & 0xffffffffL));
            }
        }
        Model m = model();
        rig = new MossRig(m, anchor, yaw, MossAnim.idle(phase, 0), 1f);
        hitbox = world.spawn(anchor, Slime.class, s -> {
            s.setSize(m.slimeSize);
            s.setAI(false);
            s.setInvisible(true);
            s.setSilent(true);
            s.setPersistent(false);
            s.setRemoveWhenFarAway(false);
            s.customName(Component.text("이끼 수문장"));
            s.setCustomNameVisible(false);
            s.getPersistentDataContainer().set(MossRig.BOSS_KEY, PersistentDataType.BYTE, (byte) 1);
        });
        maxHp = phase == 1 ? cfg.health : cfg.health * cfg.phase2HealthMultiplier;
        resetHitbox(m.slimeSize, maxHp);
        nextAttack = 40;
        if (phase == 2) {
            queue.add(new P2Summon());
            summonReadyAt = 0;
        }
        sound(anchor, Sound.BLOCK_MOSS_PLACE, 2f, 0.6f);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSafe, 1L, 1L);
    }

    private void resetHitbox(int size, double hp) {
        hitbox.setSize(size); // 바닐라가 최대체력·공격력을 크기 기준으로 다시 정하므로 아래에서 덮어씀
        hp = Math.min(hp, 1024);
        maxHp = hp;
        setAttr(hitbox, Attribute.MAX_HEALTH, hp);
        setAttr(hitbox, Attribute.ATTACK_DAMAGE, 0);
        setAttr(hitbox, Attribute.KNOCKBACK_RESISTANCE, 1);
        setAttr(hitbox, Attribute.ARMOR, cfg.armor);
        setAttr(hitbox, Attribute.ARMOR_TOUGHNESS, cfg.armorToughness);
        hitbox.setHealth(hp);
    }

    private static void setAttr(LivingEntity e, Attribute a, double v) {
        AttributeInstance i = e.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }

    private Model model() {
        return phase == 1 ? MossModel.PHASE1 : MossModel.PHASE2;
    }

    private Component name() {
        if (cfg.mini) return Component.text("이끼 수문장", NamedTextColor.GREEN); // 일부러 같은 이름 (최종 보스인 척)
        return phase == 1
                ? Component.text("이끼 수문장", NamedTextColor.GREEN)
                : Component.text("격분한 이끼 수문장", NamedTextColor.DARK_PURPLE).decorate(TextDecoration.BOLD);
    }

    // ================================================================== 공개 API

    public boolean isAlive() {
        return state != State.DEAD;
    }

    public LivingEntity hitbox() {
        return hitbox;
    }

    public int phase() {
        return phase;
    }

    public boolean owns(Entity e) {
        if (e.equals(hitbox) || minions.contains(e)) return true;
        return MossRig.isTagged(e);
    }

    public void forcePhase2() {
        if (!cfg.mini && phase == 1 && state != State.TRANSITION && state != State.DYING && state != State.DEAD) {
            hitbox.setHealth(Math.max(1, maxHp * 0.5));
            startTransition();
        }
    }

    public void kill() {
        if (state != State.DYING && state != State.DEAD) startDeath();
    }

    public String status() {
        int disp = rig == null ? 0 : rig.displays().size();
        if (rig2 != null) disp += rig2.displays().size();
        return String.format("%d페이즈 · %s · 체력 %.0f/%.0f · 표시블록 %d · 연출 %d · 졸개 %d",
                phase, state, hitbox.isValid() ? hitbox.getHealth() : 0, maxHp, disp, effects.size(), minions.size());
    }

    /** 히트박스 피해 처리 (BossManager 가 호출). */
    public void onDamage(EntityDamageEvent e) {
        if (e.isCancelled()) return;
        Entity cause = e.getDamageSource().getCausingEntity();
        boolean byPlayer = cause instanceof Player p && p.getGameMode() != GameMode.SPECTATOR;
        if (Damage.isProbe()) { // 피해 없는 CC 확인용 — 플레이어만 허용
            if (!byPlayer) e.setCancelled(true);
            return;
        }
        if (!byPlayer || state == State.TRANSITION || state == State.DYING || state == State.DEAD
                || pendingTransition || pendingDeath) {
            e.setCancelled(true);
            return;
        }
        if (state == State.STAGGER) e.setDamage(e.getDamage() * STAGGER_TAKEN);
        double hp = hitbox.getHealth();
        double fin = e.getFinalDamage();
        if (cfg.mini && hp - fin < 1) { // 미니보스: 각성 없이 처치
            e.setDamage(Math.max(0, hp - 1));
            pendingDeath = true;
        } else if (!cfg.mini && phase == 1 && hp - fin <= maxHp * 0.5) {
            e.setDamage(Math.max(0, hp - maxHp * 0.5));
            pendingTransition = true;
        } else if (phase == 2 && hp - fin < 1) {
            e.setDamage(Math.max(0, hp - 1));
            pendingDeath = true;
        }
        Location c = hitbox.getLocation().add(0, hitbox.getHeight() * 0.6, 0);
        world.spawnParticle(Particle.BLOCK, c, 14, 0.8, 0.6, 0.8, Material.MOSS_BLOCK.createBlockData());
        sound(c, Sound.BLOCK_MOSS_HIT, 1.2f, phase == 1 ? 1.3f : 0.7f);
    }

    // ================================================================== 메인 루프

    private void tickSafe() {
        try {
            tick();
        } catch (Throwable t) {
            plugin.getLogger().severe("[이끼 수문장] 오류로 보스를 정리합니다: " + t);
            t.printStackTrace();
            remove();
        }
    }

    private void tick() {
        if (state == State.DEAD) return;
        if (hitbox == null || !hitbox.isValid()) {
            remove();
            return;
        }
        age++;
        stateT++;
        tickEffects();
        minions.removeIf(m -> !m.isValid() || m.isDead());

        if (phase == 1 && state != State.TRANSITION && state != State.DYING && hitbox.getHealth() <= maxHp * 0.5 + 1e-6) {
            pendingTransition = true; // /rpg boss hp 등으로 체력이 직접 바뀐 경우도 바로 각성
        }
        if (pendingTransition && state != State.TRANSITION) {
            pendingTransition = false;
            startTransition();
        }
        if (pendingDeath && state != State.DYING) {
            pendingDeath = false;
            startDeath();
        }
        if (phase == 2 && state != State.DYING && state != State.STAGGER) checkStagger();

        Player target = target();
        Pose pose;
        boolean fast = false;
        switch (state) {
            case IDLE -> pose = tickIdle(target);
            case ATTACK -> {
                Attack a = attack;
                if (a == null) {
                    state = State.IDLE;
                    pose = MossAnim.idle(phase, age);
                    break;
                }
                a.t++;
                if (!a.lockTurn(a.t) && target != null) turnToward(target.getLocation(), turnRate());
                pose = a.pose(a.t);
                fast = a.fast(a.t);
                if (a.tick(a.t)) finishAttack(a);
            }
            case TRANSITION -> {
                tickTransition();
                return;
            }
            case STAGGER -> {
                float f = MossAnim.seg(stateT, 0, 8) * (1 - MossAnim.seg(stateT, STAGGER_TICKS - 15, STAGGER_TICKS));
                pose = MossAnim.p2Stagger(age, f);
                if (age % 6 == 0) {
                    Location core = rig.world(Bone.CORE, new Vector3f(0, 3.5f, 1.4f));
                    world.spawnParticle(Particle.DUST, core, 6, 0.4, 0.4, 0.2, new Particle.DustOptions(Color.fromRGB(0xB57BFF), 1.6f));
                }
                if (stateT >= STAGGER_TICKS) {
                    state = State.IDLE;
                    nextAttack = age + 15;
                }
            }
            case DYING -> {
                tickDeath();
                return;
            }
            default -> {
                return;
            }
        }
        hitbox.teleport(anchor.clone());
        if (fast || age % 2 == 0) rig.apply(pose, anchor, yaw, null, fast ? 1 : 2);
        if (age % 10 == 0) updateBar();
    }

    private float turnRate() {
        return phase == 1 ? 8f : 3f;
    }

    private Pose tickIdle(@Nullable Player target) {
        walking = false;
        if (target != null) {
            turnToward(target.getLocation(), turnRate());
            double d = hdist(anchor, target.getLocation());
            if (age >= nextAttack) {
                Attack a = queue.isEmpty() ? choose(target, d) : queue.poll();
                if (a != null) {
                    startAttack(a);
                    return a.pose(0);
                }
            }
            double want = phase == 1 ? 2.8 : 4.2;
            if (d > want) walk(phase == 1 ? 0.075 : 0.045);
        } else if (hdist(anchor, home) > 1.5) {
            turnToward(home, turnRate());
            walk(phase == 1 ? 0.06 : 0.04);
        }
        Pose p = MossAnim.idle(phase, age);
        if (walking) MossAnim.walk(p, phase, ++walkT);
        return p;
    }

    private void walk(double speed) {
        Vector f = forward();
        Location next = anchor.clone().add(f.clone().multiply(speed));
        if (hdist(next, home) > LEASH && hdist(next, home) > hdist(anchor, home)) return;
        double half = model().slimeSize * 0.26;
        Location probe = next.clone().add(f.clone().multiply(half));
        if (!probe.clone().add(0, 0.5, 0).getBlock().isPassable() || !probe.clone().add(0, 1.5, 0).getBlock().isPassable()) {
            return;
        }
        anchor = next;
        walking = true;
        if (walkT % (phase == 1 ? 8 : 14) == 0) {
            sound(anchor, phase == 1 ? Sound.BLOCK_MOSS_STEP : Sound.ENTITY_IRON_GOLEM_STEP, 0.9f, phase == 1 ? 1.4f : 0.6f);
        }
    }

    private void startAttack(Attack a) {
        attack = a;
        a.t = 0;
        state = State.ATTACK;
        stateT = 0;
        if (a.name.equals(lastAttack)) repeat++;
        else repeat = 0;
        lastAttack = a.name;
        a.begin();
    }

    private void finishAttack(Attack a) {
        attack = null;
        state = State.IDLE;
        stateT = 0;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        nextAttack = Math.max(nextAttack, age + (phase == 1 ? r.nextInt(25, 41) : r.nextInt(30, 51)));
        if (!queue.isEmpty()) nextAttack = age + 4; // 연계 기술은 바로
    }

    private void cancelAttack() {
        if (attack != null) attack.cancel();
        attack = null;
        queue.clear();
    }

    // ================================================================== 패턴 선택

    private @Nullable Attack choose(Player target, double d) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double ang = angleTo(target.getLocation());
        if (phase == 1) {
            boolean low = hitbox.getHealth() < maxHp * 0.75;
            Attack a;
            if (d <= 4.5) {
                double x = r.nextDouble();
                a = x < 0.55 ? new P1Slam() : x < 0.85 ? new P1Spin() : new P1Thorns(low);
            } else if (d <= 10) {
                a = r.nextDouble() < 0.45 ? new P1Spin() : new P1Thorns(low);
            } else if (d <= 18) {
                a = new P1Thorns(low);
            } else {
                return null;
            }
            if (repeat >= 1 && a.name.equals(lastAttack)) {
                a = a instanceof P1Thorns ? (d <= 4.5 ? new P1Slam() : new P1Spin()) : new P1Thorns(low);
            }
            return a;
        }
        boolean low = hitbox.getHealth() < maxHp * 0.5;
        if (age >= summonReadyAt && minions.size() < 3 && r.nextDouble() < 0.3) return new P2Summon();
        Attack a;
        if (d <= 6 && ang < 55) {
            double x = r.nextDouble();
            a = x < 0.45 ? new P2Slam() : x < 0.75 ? new P2Vines(low) : new P2Darts();
        } else if (d <= 24) {
            a = r.nextDouble() < 0.55 ? new P2Vines(low) : new P2Darts();
        } else {
            return null;
        }
        if (repeat >= 1 && a.name.equals(lastAttack)) a = a instanceof P2Darts ? new P2Vines(low) : new P2Darts();
        if (a instanceof P2Vines && low) queue.add(new P2Darts()); // 체력 절반 아래: 덩굴 + 독침 연계
        return a;
    }

    // ================================================================== 공격 공통

    abstract class Attack {
        final String name;
        int t;

        Attack(String name) {
            this.name = name;
        }

        void begin() {}

        abstract Pose pose(float t);

        /** true 면 끝. */
        abstract boolean tick(int t);

        boolean lockTurn(int t) {
            return false;
        }

        boolean fast(int t) {
            return false;
        }

        void cancel() {}
    }

    // ------------------------------------------------------------------ 1페이즈 ① 내려치기

    final class P1Slam extends Attack {
        private final boolean left = ThreadLocalRandom.current().nextBoolean();
        private Location impact;

        P1Slam() {
            super("p1slam");
        }

        @Override
        Pose pose(float t) {
            return MossAnim.p1Slam(t, left);
        }

        @Override
        boolean lockTurn(int t) {
            return t > 5;
        }

        @Override
        boolean fast(int t) {
            return t >= MossAnim.P1_SLAM_RAISE && t <= MossAnim.P1_SLAM_HIT + 2;
        }

        @Override
        boolean tick(int t) {
            Model m = model();
            Vector3f fist = left ? m.fistL : m.fistR;
            Bone fore = left ? Bone.FORE_L : Bone.FORE_R;
            if (t == 1) sound(anchor, Sound.ENTITY_IRON_GOLEM_REPAIR, 0.8f, 1.6f);
            if (t >= 6 && t < MossAnim.P1_SLAM_HIT) {
                // 내려칠 자리 예고 (회전이 잠긴 뒤 위치 고정)
                if (impact == null) {
                    impact = ground(MossRig.toWorld(m, MossAnim.p1Slam(MossAnim.P1_SLAM_HIT, left), fore, fist, anchor, yaw));
                }
                // 실제 피해 범위(P1_SLAM_RADIUS) = 테두리 원, 안쪽도 채워서 "여기 맞는다"를 보여줌. 내려치기 직전엔 빨갛게
                Color c = t >= MossAnim.P1_SLAM_HIT - 5 ? Color.fromRGB(0xE0533A) : Color.fromRGB(0x9BD45A);
                ringFx(impact, P1_SLAM_RADIUS, c, 22);
                if (t % 2 == 0) {
                    world.spawnParticle(Particle.DUST, impact.clone().add(0, 0.1, 0), 10, P1_SLAM_RADIUS * 0.45, 0, P1_SLAM_RADIUS * 0.45,
                            new Particle.DustOptions(c, 1.0f));
                }
            }
            if (t == MossAnim.P1_SLAM_HIT) {
                Location hit = impact != null ? impact : ground(rig.world(fore, fist));
                for (Player p : players(hit, P1_SLAM_RADIUS, 2.5)) {
                    if (hurt(p, P1_SLAM_DMG)) Targets.velocity(p, p.getLocation().toVector().subtract(hit.toVector()).setY(0)
                            .normalize().multiply(0.4).setY(0.45));
                }
                world.spawnParticle(Particle.BLOCK, hit.clone().add(0, 0.2, 0), 50, 1.0, 0.2, 1.0, Material.MOSS_BLOCK.createBlockData());
                world.spawnParticle(Particle.BLOCK, hit.clone().add(0, 0.2, 0), 30, 0.8, 0.2, 0.8, Material.STONE.createBlockData());
                world.spawnParticle(Particle.EXPLOSION, hit, 1);
                sound(hit, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.6f, 0.8f);
                sound(hit, Sound.BLOCK_STONE_BREAK, 1.6f, 0.6f);
            }
            return t >= MossAnim.P1_SLAM_END;
        }
    }

    // ------------------------------------------------------------------ 1페이즈 ② 회전 덩굴

    final class P1Spin extends Attack {
        private static final int OPEN = 10, SPIN_END = 50, END = 58;
        private final Set<UUID> hit = new HashSet<>();

        P1Spin() {
            super("p1spin");
        }

        @Override
        Pose pose(float t) {
            float f = MossAnim.seg(t, 0, OPEN) * (1 - MossAnim.seg(t, SPIN_END, END));
            return MossAnim.p1Spin(t, f);
        }

        @Override
        boolean lockTurn(int t) {
            return true;
        }

        @Override
        boolean fast(int t) {
            return t > OPEN && t <= SPIN_END;
        }

        @Override
        boolean tick(int t) {
            if (t == 1) sound(anchor, Sound.BLOCK_CAVE_VINES_PLACE, 1.5f, 0.7f);
            if (t > OPEN && t <= SPIN_END) {
                float speed = 18f * MossAnim.clamp01((t - OPEN) / 6f);
                yaw = wrap(yaw + speed);
                if (t % 6 == 0) sound(anchor, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f, 0.6f);
                if (t % 3 == 0) {
                    Model m = model();
                    shoot(rig.world(Bone.FORE_L, m.wristL));
                    shoot(rig.world(Bone.FORE_R, m.wristR));
                }
            }
            if (t == SPIN_END) {
                // 맞은 사람만 보스 쪽으로 끌어당기며 1초 기절
                Location center = anchor.clone().add(0, 1.2, 0);
                for (UUID id : hit) {
                    Player p = Bukkit.getPlayer(id);
                    if (p == null || !valid(p) || p.getLocation().distanceSquared(anchor) > 24 * 24) continue;
                    Cc.stun(p, P1_SPIN_STUN);
                    effects.add(new Pull(p, center, 8));
                    p.sendActionBar(Component.text("끌려감 — 1초 기절", NamedTextColor.DARK_GREEN));
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!valid(p)) return;
                        p.removePotionEffect(PotionEffectType.GLOWING);
                        p.sendActionBar(Component.text("기절 해제 — 다음 공격 대비!", NamedTextColor.YELLOW));
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.6f);
                    }, P1_SPIN_STUN);
                    sound(p.getLocation(), Sound.BLOCK_CAVE_VINES_PICK_BERRIES, 1.2f, 0.6f);
                }
                if (!hit.isEmpty()) {
                    // 기절이 풀린 뒤 다음 공격을 피할 여유
                    nextAttack = age + P1_SPIN_STUN + P1_SPIN_GRACE;
                }
            }
            return t >= END;
        }

        private void shoot(Location wrist) {
            Vector out = wrist.toVector().subtract(anchor.toVector()).setY(0);
            if (out.lengthSquared() < 1e-4) out = forward();
            out.normalize();
            // 회전 방향 뒤로 살짝 휘어서 나감
            out.rotateAroundY(Math.toRadians(-15 + ThreadLocalRandom.current().nextDouble(-8, 8)));
            effects.add(new VineShot(wrist, out, hit));
        }
    }

    // ------------------------------------------------------------------ 1페이즈 ③ 가시 발아

    final class P1Thorns extends Attack {
        private static final int LOCK = 4, ERUPT = 26, LEN = 15, END = ERUPT + LEN + 12;
        private final boolean fan;
        private final List<List<Location>> paths = new ArrayList<>();
        private final Set<UUID> hit = new HashSet<>();
        private final List<BlockDisplay> plates = new ArrayList<>();

        P1Thorns(boolean fan) {
            super("p1thorns");
            this.fan = fan;
        }

        @Override
        Pose pose(float t) {
            return MossAnim.p1Reach(t, MossAnim.seg(t, 0, 14) * (1 - MossAnim.seg(t, END - 10, END)));
        }

        @Override
        boolean lockTurn(int t) {
            return t >= LOCK;
        }

        @Override
        boolean tick(int t) {
            if (t == 1) sound(anchor, Sound.BLOCK_ROOTED_DIRT_PLACE, 1.5f, 0.6f);
            if (t == LOCK) {
                Vector f = forward();
                double[] angs = fan ? new double[]{-25, 25} : new double[]{0}; // 두 갈래 사이 가운데가 안전 통로
                for (double a : angs) {
                    Vector dir = f.clone().rotateAroundY(Math.toRadians(a));
                    List<Location> path = new ArrayList<>();
                    for (int i = 0; i < LEN; i++) {
                        Location l = ground(anchor.clone().add(dir.clone().multiply(2.0 + i)));
                        path.add(l);
                        plates.add(display(l, "minecraft:lime_stained_glass", new Matrix4f()
                                .rotateY((float) Math.atan2(dir.getX(), dir.getZ()))
                                .translate(-0.55f, 0.03f, -0.55f).scale(1.1f, 0.04f, 1.1f), true));
                    }
                    paths.add(path);
                }
                sound(anchor, Sound.BLOCK_SWEET_BERRY_BUSH_PLACE, 1.5f, 0.5f);
            }
            if (t > LOCK && t < ERUPT + LEN && t % 4 == 0) {
                for (List<Location> path : paths) {
                    for (Location l : path) {
                        world.spawnParticle(Particle.DUST, l.clone().add(0, 0.15, 0), 1, 0.3, 0, 0.3,
                                new Particle.DustOptions(Color.fromRGB(0x55FF55), 1.2f));
                    }
                }
            }
            int i = t - ERUPT;
            if (i >= 0 && i < LEN) {
                for (List<Location> path : paths) {
                    effects.add(new Spike(path.get(i), hit));
                }
                // 지나간 예고판 제거
                for (int k = 0; k < paths.size(); k++) {
                    BlockDisplay d = plates.get(k * LEN + i);
                    d.remove();
                }
            }
            return t >= END;
        }

        @Override
        void cancel() {
            for (BlockDisplay d : plates) d.remove();
        }
    }

    // ================================================================== 각성 (1 → 2페이즈)

    private void startTransition() {
        cancelAttack();
        state = State.TRANSITION;
        stateT = 0;
        bar.name(Component.text("이끼 수문장 …?", NamedTextColor.GRAY));
        for (Player p : players(anchor, 40, 20)) {
            p.sendActionBar(Component.text("이끼 수문장의 몸이 떨리기 시작한다…", NamedTextColor.DARK_GREEN));
        }
        sound(anchor, Sound.BLOCK_ROOTED_DIRT_BREAK, 2f, 0.5f);
    }

    private void tickTransition() {
        int t = stateT;
        hitbox.teleport(anchor.clone());
        ThreadLocalRandom r = ThreadLocalRandom.current();

        // 1) 몸을 앞으로 숙이며 떨림
        if (rig != null && rig2 == null || (rig != null && rig.model() == MossModel.PHASE1)) {
            if (grow1 == null) grow1 = filled(rig.model().parts.size(), 1f);
            float shrink = 1 - MossAnim.seg(t, 56, 70);
            for (int i = 0; i < grow1.length; i++) grow1[i] = shrink;
            if (t % 2 == 0) rig.apply(MossAnim.p1Hunch(age, MossAnim.seg(t, 0, 24)), anchor, yaw, grow1, 2);
        }
        // 2) 뿌리·덩굴이 몸을 감싸며 자라남 (파트마다 시차)
        if (t == 30) {
            Model m2 = MossModel.PHASE2;
            rig2 = new MossRig(m2, anchor, yaw, MossAnim.p2Rise(age, 0), 0.001f);
            grow2 = filled(m2.parts.size(), 0.001f);
            growDelay2 = new int[m2.parts.size()];
            for (int i = 0; i < growDelay2.length; i++) {
                growDelay2[i] = 30 + Math.round(30f * i / growDelay2.length) + r.nextInt(6);
            }
        }
        if (rig2 != null) {
            for (int i = 0; i < grow2.length; i++) grow2[i] = Math.max(0.001f, MossAnim.seg(t, growDelay2[i], growDelay2[i] + 12));
            if (t % 2 == 0) rig2.apply(MossAnim.p2Rise(age, MossAnim.seg(t, 72, 104)), anchor, yaw, grow2, 2);
        }
        if (t == 70 && rig != null && rig.model() == MossModel.PHASE1) {
            rig.remove();
            rig = rig2;
        }
        // 연출
        if (t % 2 == 0 && t < 72) {
            for (int k = 0; k < 4; k++) {
                double a = r.nextDouble(Math.PI * 2), rad = r.nextDouble(1.5, 3.5);
                Location l = anchor.clone().add(Math.cos(a) * rad, 0.2, Math.sin(a) * rad);
                world.spawnParticle(Particle.BLOCK, l, 4, 0.1, 0.3, 0.1, Material.ROOTED_DIRT.createBlockData());
                world.spawnParticle(Particle.BLOCK, l, 2, 0.1, 0.5, 0.1, Material.VINE.createBlockData());
            }
        }
        if (t >= 20 && t < 95 && t % 15 == 0) sound(anchor, Sound.ENTITY_WARDEN_HEARTBEAT, 2.5f, 0.6f);
        if (t >= 40 && t < 100 && t % 5 == 0) {
            world.spawnParticle(Particle.DUST, anchor.clone().add(forward().multiply(1.4)).add(0, 3.4, 0), 8, 0.4, 0.4, 0.4,
                    new Particle.DustOptions(Color.fromRGB(0xA77CF0), 1.5f));
        }
        if (t == 95) {
            sound(anchor, Sound.ENTITY_RAVAGER_ROAR, 3f, 0.6f);
            sound(anchor, Sound.ENTITY_WARDEN_ROAR, 2f, 0.8f);
            for (Player p : players(anchor, 7, 6)) {
                Targets.velocity(p, p.getLocation().toVector().subtract(anchor.toVector()).setY(0).normalize().multiply(1.1).setY(0.5));
            }
            ringFx(anchor, 4, Color.fromRGB(0x7A2BD0), 40);
            for (Player p : players(anchor, 48, 30)) {
                p.showTitle(Title.title(Component.text("격분", NamedTextColor.DARK_PURPLE).decorate(TextDecoration.BOLD),
                        Component.text("이끼 수문장이 진짜 모습을 드러냈다", NamedTextColor.LIGHT_PURPLE),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(500))));
            }
        }
        if (t >= TRANSITION_TICKS) {
            phase = 2;
            if (rig2 != null) {
                rig = rig2;
                rig2 = null;
            }
            grow1 = grow2 = null;
            resetHitbox(MossModel.PHASE2.slimeSize, cfg.health * cfg.phase2HealthMultiplier);
            bar.name(name());
            bar.color(BossBar.Color.PURPLE);
            state = State.IDLE;
            stateT = 0;
            staggerStage = 0;
            queue.add(new P2Summon()); // 던전스처럼 시작하자마자 졸개 소환
            nextAttack = age + 20;
            rig.apply(MossAnim.idle(2, age), anchor, yaw, null, 4);
        }
        if (age % 10 == 0) updateBar();
    }

    private static float[] filled(int n, float v) {
        float[] a = new float[n];
        java.util.Arrays.fill(a, v);
        return a;
    }

    // ================================================================== 2페이즈 (던전스 정글 보스)

    private void checkStagger() {
        if (state == State.TRANSITION || pendingDeath) return;
        double frac = hitbox.getHealth() / maxHp;
        boolean crossed = false;
        while (staggerStage < 3 && frac <= 0.75 - 0.25 * staggerStage) {
            staggerStage++;
            crossed = true;
        }
        if (!crossed) return;
        cancelAttack();
        state = State.STAGGER;
        stateT = 0;
        sound(anchor, Sound.ENTITY_IRON_GOLEM_DAMAGE, 2f, 0.5f);
        sound(anchor, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 2f, 0.6f);
        for (Player p : players(anchor, 40, 20)) {
            p.sendActionBar(Component.text("수문장이 휘청인다! 5초간 받는 피해 +60%", NamedTextColor.LIGHT_PURPLE));
        }
    }

    // ------------------------------------------------------------------ 석상 곤봉 내려찍기

    final class P2Slam extends Attack {
        private Location impact;

        P2Slam() {
            super("p2slam");
        }

        @Override
        void begin() {
            impact = ground(MossRig.toWorld(MossModel.PHASE2, MossAnim.p2Slam(MossAnim.P2_SLAM_HIT), Bone.FORE_L,
                    MossModel.PHASE2.fistL, anchor, yaw));
            sound(anchor, Sound.ENTITY_RAVAGER_STUNNED, 1.5f, 0.6f);
        }

        @Override
        Pose pose(float t) {
            return MossAnim.p2Slam(t);
        }

        @Override
        boolean lockTurn(int t) {
            return true; // 던전스처럼 내려찍는 동안 회전 불가
        }

        @Override
        boolean fast(int t) {
            return t >= MossAnim.P2_SLAM_RAISE && t <= MossAnim.P2_SLAM_HIT + 2;
        }

        @Override
        boolean tick(int t) {
            if (t >= 6 && t < MossAnim.P2_SLAM_HIT && t % 2 == 0) {
                ringFx(impact, P2_SLAM_RADIUS, Color.fromRGB(0xC0392B), 26);
                ringFx(impact, P2_SLAM_RADIUS * (t / (float) MossAnim.P2_SLAM_HIT), Color.fromRGB(0x6B1F1F), 18);
            }
            if (t == MossAnim.P2_SLAM_HIT) {
                for (Player p : players(impact, P2_SLAM_RADIUS, 3)) {
                    if (hurt(p, P2_SLAM_DMG)) {
                        p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 80, 1));
                        Targets.velocity(p, p.getLocation().toVector().subtract(impact.toVector()).setY(0)
                                .normalize().multiply(0.6).setY(0.55));
                    }
                }
                world.spawnParticle(Particle.EXPLOSION, impact, 2, 0.6, 0.1, 0.6);
                world.spawnParticle(Particle.BLOCK, impact.clone().add(0, 0.3, 0), 90, 1.6, 0.3, 1.6, Material.MOSSY_STONE_BRICKS.createBlockData());
                world.spawnParticle(Particle.ITEM_SLIME, impact.clone().add(0, 0.5, 0), 30, 1.4, 0.4, 1.4, 0.05);
                sound(impact, Sound.ENTITY_WARDEN_ATTACK_IMPACT, 2f, 0.6f);
                sound(impact, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.6f);
                effects.add(new Shockwave(impact, 4.5, Material.MOSS_BLOCK.createBlockData()));
            }
            return t >= MossAnim.P2_SLAM_END;
        }
    }

    // ------------------------------------------------------------------ 덩굴 분출

    final class P2Vines extends Attack {
        private final boolean low;

        P2Vines(boolean low) {
            super("p2vines");
            this.low = low;
        }

        @Override
        Pose pose(float t) {
            return MossAnim.p2VinePlunge(t);
        }

        @Override
        boolean lockTurn(int t) {
            return t > 14;
        }

        @Override
        boolean fast(int t) {
            return t >= MossAnim.P2_VINE_RAISE && t <= MossAnim.P2_VINE_HIT + 1;
        }

        @Override
        boolean tick(int t) {
            if (t == 1) sound(anchor, Sound.BLOCK_CAVE_VINES_PLACE, 2f, 0.5f);
            if (t == MossAnim.P2_VINE_HIT) {
                Location tip = ground(rig.world(Bone.FORE_R, MossModel.PHASE2.fistR));
                world.spawnParticle(Particle.BLOCK, tip.clone().add(0, 0.3, 0), 40, 0.6, 0.2, 0.6, Material.ROOTED_DIRT.createBlockData());
                sound(tip, Sound.BLOCK_ROOTED_DIRT_BREAK, 2f, 0.5f);
                sound(tip, Sound.ENTITY_EVOKER_PREPARE_ATTACK, 1.5f, 0.6f);
                for (Location l : pattern()) effects.add(new AbomVine(l));
            }
            return t >= MossAnim.P2_VINE_END;
        }

        /** 던전스: 덩굴 4~10개 — 보스 주변 원형 8개 / 영웅 발밑 / 영웅 주변 사각 4개. */
        private List<Location> pattern() {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            List<Player> ps = players(anchor, 24, 10);
            List<Location> out = new ArrayList<>();
            int kind = ps.isEmpty() ? 0 : r.nextInt(3);
            List<Integer> kinds = new ArrayList<>(List.of(kind));
            if (low && !ps.isEmpty()) kinds.add((kind + 1 + r.nextInt(2)) % 3);
            for (int k : kinds) {
                switch (k) {
                    case 0 -> {
                        double off = r.nextDouble(Math.PI * 2);
                        for (int i = 0; i < 8; i++) {
                            double a = off + i * Math.PI / 4;
                            out.add(anchor.clone().add(Math.cos(a) * 4.8, 0, Math.sin(a) * 4.8));
                        }
                    }
                    case 1 -> {
                        for (Player p : ps) out.add(p.getLocation());
                        while (out.size() < 4) {
                            Player p = ps.get(r.nextInt(ps.size()));
                            out.add(p.getLocation().add(r.nextDouble(-3, 3), 0, r.nextDouble(-3, 3)));
                        }
                    }
                    default -> {
                        Location c = ps.get(r.nextInt(ps.size())).getLocation();
                        for (int dx = -1; dx <= 1; dx += 2) {
                            for (int dz = -1; dz <= 1; dz += 2) out.add(c.clone().add(dx * 1.5, 0, dz * 1.5));
                        }
                    }
                }
            }
            while (out.size() > 10) out.remove(out.size() - 1);
            List<Location> g = new ArrayList<>();
            for (Location l : out) g.add(ground(l));
            return g;
        }
    }

    // ------------------------------------------------------------------ 독침 (12발 × 2링)

    final class P2Darts extends Attack {
        private static final int RING1 = 14, RING2 = 24, END = 36;

        P2Darts() {
            super("p2darts");
        }

        @Override
        Pose pose(float t) {
            return MossAnim.p2Puff(t, MossAnim.seg(t, 0, 12) * (1 - MossAnim.seg(t, RING2 + 2, END)));
        }

        @Override
        boolean tick(int t) {
            if (t == 1) sound(anchor, Sound.ENTITY_PUFFER_FISH_BLOW_UP, 2f, 0.5f);
            if (t < RING1 && t % 3 == 0) {
                world.spawnParticle(Particle.DUST, anchor.clone().add(0, 3.4, 0), 10, 1.2, 0.8, 1.2,
                        new Particle.DustOptions(Color.fromRGB(0x4E9A06), 1.3f));
            }
            if (t == RING1) ring(0);
            if (t == RING2) ring(15);
            return t >= END;
        }

        private void ring(double offsetDeg) {
            sound(anchor, Sound.ENTITY_PUFFER_FISH_BLOW_OUT, 2f, 0.7f);
            sound(anchor, Sound.ENTITY_ARROW_SHOOT, 2f, 0.6f);
            for (int i = 0; i < 12; i++) {
                double a = Math.toRadians(-yaw + offsetDeg + i * 30);
                Vector dir = new Vector(Math.sin(a), -0.04, Math.cos(a));
                Location from = anchor.clone().add(dir.getX() * 2.8, 3.0, dir.getZ() * 2.8);
                Arrow arrow = world.spawn(from, Arrow.class, ar -> {
                    ar.setShooter(hitbox);
                    ar.setVelocity(dir.clone().multiply(1.2));
                    ar.setDamage(P2_DART_DMG * cfg.damageMultiplier / 1.2);
                    ar.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                    ar.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 70, 0), true);
                    ar.setPersistent(false);
                    ar.setColor(Color.fromRGB(0x4E9A06));
                    MossRig.tag(ar);
                });
                effects.add(new Timed(arrow, 60));
            }
        }
    }

    // ------------------------------------------------------------------ 졸개 소환

    final class P2Summon extends Attack {
        private static final int SPAWN = 22, END = 40;

        P2Summon() {
            super("p2summon");
        }

        @Override
        Pose pose(float t) {
            return MossAnim.p2Roar(t, MossAnim.seg(t, 0, 14) * (1 - MossAnim.seg(t, 30, END)));
        }

        @Override
        boolean tick(int t) {
            if (t == 8) sound(anchor, Sound.ENTITY_RAVAGER_ROAR, 2f, 0.9f);
            if (t == SPAWN) {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                boolean low = hitbox.getHealth() < maxHp * 0.5;
                int whisperers = r.nextInt(2, 5);
                int leapleaves = low ? 2 : 0;
                int cap = 6 - minions.size();
                for (int i = 0; i < whisperers + leapleaves && cap > 0; i++, cap--) {
                    double a = r.nextDouble(Math.PI * 2), d = r.nextDouble(4, 6.5);
                    Location l = ground(anchor.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d));
                    if (!l.clone().add(0, 0.5, 0).getBlock().isPassable()) continue;
                    world.spawnParticle(Particle.BLOCK, l.clone().add(0, 0.2, 0), 25, 0.4, 0.2, 0.4, Material.ROOTED_DIRT.createBlockData());
                    sound(l, Sound.BLOCK_ROOTED_DIRT_BREAK, 1.2f, 0.8f);
                    LivingEntity mob = i < whisperers ? spawnWhisperer(l) : spawnLeapleaf(l);
                    minions.add(mob);
                }
                summonReadyAt = age + 600;
            }
            return t >= END;
        }
    }

    private LivingEntity spawnWhisperer(Location l) {
        return world.spawn(l, Bogged.class, b -> {
            minionBase(b, Component.text("속삭이는 덩굴", NamedTextColor.GREEN));
            b.getEquipment().setHelmet(new org.bukkit.inventory.ItemStack(Material.AZALEA_LEAVES));
            b.getEquipment().setHelmetDropChance(0f);
        });
    }

    private LivingEntity spawnLeapleaf(Location l) {
        return world.spawn(l, Creaking.class, c -> {
            minionBase(c, Component.text("도약잎", NamedTextColor.DARK_GREEN));
            setAttr(c, Attribute.MAX_HEALTH, 24);
            c.setHealth(24);
        });
    }

    private void minionBase(Mob m, Component name) {
        m.customName(name);
        m.setCustomNameVisible(true);
        m.setPersistent(false);
        m.setRemoveWhenFarAway(false);
        m.getPersistentDataContainer().set(MossRig.BOSS_KEY, PersistentDataType.BYTE, (byte) 1);
        Player t = target();
        if (t != null) m.setTarget(t);
    }

    // ================================================================== 사망

    private void startDeath() {
        cancelAttack();
        state = State.DYING;
        stateT = 0;
        bar.progress(0f);
        for (LivingEntity m : minions) {
            world.spawnParticle(Particle.BLOCK, m.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, Material.MOSS_BLOCK.createBlockData());
            m.remove();
        }
        minions.clear();
        sound(anchor, Sound.ENTITY_IRON_GOLEM_DEATH, 2f, 0.5f);
        sound(anchor, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 2f, 0.5f);
    }

    private void tickDeath() {
        int t = stateT;
        hitbox.teleport(anchor.clone());
        if (t < 40 && t % 2 == 0) rig.apply(MossAnim.death(phase, age, MossAnim.seg(t, 0, 40)), anchor, yaw, null, 2);
        if (t % 10 == 0 && t < 40) sound(anchor, Sound.BLOCK_MOSS_BREAK, 2f, 0.5f + t / 80f);
        if (t == 40) {
            rig.crumble(12);
            List<org.bukkit.entity.BlockDisplay> ds = rig.displays();
            for (int i = 0; i < ds.size(); i++) {
                BlockData bd = ds.get(i).getBlock();
                world.spawnParticle(Particle.BLOCK, rig.partCenter(i), 6, 0.3, 0.3, 0.3, bd);
            }
            sound(anchor, Sound.BLOCK_STONE_BREAK, 2f, 0.5f);
            sound(anchor, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.7f);
        }
        if (t >= 56) {
            for (Player p : players(anchor, 48, 30)) {
                p.showTitle(Title.title(Component.text("이끼 수문장 처치", NamedTextColor.GOLD),
                        Component.text("정글 던전의 수문이 열렸다", NamedTextColor.YELLOW),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2000), Duration.ofMillis(600))));
            }
            remove();
        }
    }

    /** 즉시 전부 정리 (표시 블록·히트박스·연출·졸개·보스바·청크 티켓). */
    public void remove() {
        if (state == State.DEAD && task == null) return;
        cancelAttack(); // 예고판 등 공격 중 연출 제거
        state = State.DEAD;
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (rig != null) rig.remove();
        if (rig2 != null) rig2.remove();
        for (Effect e : effects) {
            try {
                e.cleanup();
            } catch (Throwable ignored) {
                // 정리 중 오류는 무시하고 나머지 계속
            }
        }
        effects.clear();
        for (LivingEntity m : minions) m.remove();
        minions.clear();
        if (hitbox != null) hitbox.remove();
        for (UUID id : viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.hideBossBar(bar);
        }
        viewers.clear();
        for (long key : tickets) world.removePluginChunkTicket((int) (key >> 32), (int) key, plugin);
        tickets.clear();
    }

    // ================================================================== 연출 효과 (메인 루프가 매 틱 진행)

    interface Effect {
        /** true 면 끝 (cleanup 호출됨). */
        boolean tick();

        void cleanup();
    }

    private void tickEffects() {
        // 효과가 새 효과를 추가할 수 있어 복사본으로 순회
        for (Effect e : List.copyOf(effects)) {
            boolean done;
            try {
                done = e.tick();
            } catch (Throwable t) {
                done = true;
            }
            if (done) {
                e.cleanup();
                effects.remove(e);
            }
        }
    }

    /** 일정 시간 뒤 제거 (독침 화살). */
    final class Timed implements Effect {
        private final Entity e;
        private int life;

        Timed(Entity e, int life) {
            this.e = e;
            this.life = life;
        }

        @Override
        public boolean tick() {
            return --life <= 0 || !e.isValid() || (e instanceof Projectile pr && pr instanceof AbstractArrow a && a.isInBlock());
        }

        @Override
        public void cleanup() {
            e.remove();
        }
    }

    /** 회전 덩굴: 손목에서 뻗어나가는 덩굴 한 줄기. 맞은 플레이어를 hit 에 기록. */
    final class VineShot implements Effect {
        private final Location pos;
        private final Vector dir;
        private final Set<UUID> hit;
        private int step;

        VineShot(Location from, Vector dir, Set<UUID> hit) {
            this.pos = from.clone();
            this.dir = dir.clone().multiply(1.3);
            this.hit = hit;
        }

        @Override
        public boolean tick() {
            Location prev = pos.clone();
            pos.add(dir);
            if (!pos.getBlock().isPassable()) return true;
            BlockData vine = Material.VINE.createBlockData();
            for (int k = 0; k <= 4; k++) {
                Location l = prev.clone().add(dir.clone().multiply(k / 4.0));
                world.spawnParticle(Particle.BLOCK, l, 1, 0.02, 0.02, 0.02, vine);
                world.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(0x2F6B12), 1.1f));
            }
            for (Player p : players(pos, 2.2, 3)) {
                if (hit.contains(p.getUniqueId())) continue;
                if (segDist(p, prev, pos) > 0.9) continue;
                if (hurt(p, P1_SPIN_DMG)) {
                    hit.add(p.getUniqueId());
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 0));
                    p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 60, 0, false, false, false)); // 표식: 회전 끝나면 끌려감
                    p.sendActionBar(Component.text("덩굴 표식! 회전이 끝나면 끌려갑니다", NamedTextColor.GREEN));
                    sound(p.getLocation(), Sound.BLOCK_VINE_PLACE, 1.2f, 1.4f);
                    world.spawnParticle(Particle.BLOCK, p.getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, vine);
                }
            }
            return ++step >= 7;
        }

        @Override
        public void cleanup() {}
    }

    /** 덩굴로 붙잡아 끌어당김 (기절 중). */
    final class Pull implements Effect {
        private final Player p;
        private final Location center;
        private int life;

        Pull(Player p, Location center, int life) {
            this.p = p;
            this.center = center;
            this.life = life;
        }

        @Override
        public boolean tick() {
            if (!valid(p)) return true;
            Vector v = center.toVector().subtract(p.getLocation().toVector()).setY(0);
            double d = v.length();
            double stop = model().slimeSize * 0.26 + 1.0;
            BlockData vine = Material.VINE.createBlockData();
            Location from = center.clone();
            Location to = p.getLocation().add(0, 1, 0);
            Vector seg = to.toVector().subtract(from.toVector());
            for (int k = 0; k <= 8; k++) {
                world.spawnParticle(Particle.BLOCK, from.clone().add(seg.clone().multiply(k / 8.0)), 1, 0, 0, 0, vine);
            }
            if (d > stop) Targets.velocity(p, v.normalize().multiply(Math.min(1.1, (d - stop) * 0.45 + 0.25)).setY(0.12));
            return --life <= 0 || d <= stop;
        }

        @Override
        public void cleanup() {}
    }

    /** 가시 발아: 바닥 아래에서 솟는 가시 식물 하나. */
    final class Spike implements Effect {
        private final Location base;
        private final Set<UUID> hit;
        private final List<BlockDisplay> parts = new ArrayList<>();
        private final float tilt = (float) ThreadLocalRandom.current().nextDouble(-0.25, 0.25);
        private int t;

        Spike(Location base, Set<UUID> hit) {
            this.base = base;
            this.hit = hit;
            parts.add(display(base, "minecraft:cactus", spikeM(-2.2f, tilt).translate(-0.15f, 0, -0.15f).scale(0.3f, 1.5f, 0.3f), false));
            parts.add(display(base, "minecraft:sweet_berry_bush[age=3]", spikeM(-2.2f, tilt).translate(-0.5f, 0.1f, -0.5f).scale(1f, 1.1f, 1f), false));
            parts.add(display(base, "minecraft:pointed_dripstone[vertical_direction=up,thickness=tip]", spikeM(-2.2f, tilt)
                    .translate(-0.3f, 1.2f, -0.3f).scale(0.6f, 0.9f, 0.6f), false));
        }

        private Matrix4f spikeM(float y, float tilt) {
            return new Matrix4f().translate(0, y, 0).rotateZ(tilt).rotateX(tilt * 0.6f);
        }

        private void move(float y, int interp) {
            Matrix4f[] ms = {
                    spikeM(y, tilt).translate(-0.15f, 0, -0.15f).scale(0.3f, 1.5f, 0.3f),
                    spikeM(y, tilt).translate(-0.5f, 0.1f, -0.5f).scale(1f, 1.1f, 1f),
                    spikeM(y, tilt).translate(-0.3f, 1.2f, -0.3f).scale(0.6f, 0.9f, 0.6f)};
            for (int i = 0; i < parts.size(); i++) {
                BlockDisplay d = parts.get(i);
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(interp);
                d.setTransformationMatrix(ms[i]);
            }
        }

        @Override
        public boolean tick() {
            t++;
            if (t == 1) {
                move(0f, 3);
                world.spawnParticle(Particle.BLOCK, base.clone().add(0, 0.2, 0), 12, 0.3, 0.1, 0.3, Material.ROOTED_DIRT.createBlockData());
                sound(base, Sound.BLOCK_SWEET_BERRY_BUSH_BREAK, 1.2f, 0.7f);
                sound(base, Sound.ENCHANT_THORNS_HIT, 0.7f, 1.2f);
            }
            if (t == SPIKE_HIT_TICK) { // 다 솟아오른 뒤(보간 3틱) 판정 — 예고판(1.1칸) 안 + 몸 절반 여유
                for (Player p : players(base, SPIKE_HIT_RADIUS, 2.2)) {
                    if (hit.contains(p.getUniqueId())) continue;
                    if (hurt(p, P1_THORN_DMG)) {
                        hit.add(p.getUniqueId());
                        p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 70, 0));
                        Targets.velocity(p, new Vector(0, 0.55, 0));
                    }
                }
            }
            if (t == 26) move(-2.2f, 5);
            return t >= 32;
        }

        @Override
        public void cleanup() {
            for (BlockDisplay d : parts) d.remove();
        }
    }

    /** 2페이즈 덩굴 분출 (던전스 Abomination Vine): 무적, 솟을 때 피해, 몇 초 뒤 가라앉음. */
    final class AbomVine implements Effect {
        private static final int WARN = 16, LIVE = 76, END = 86;
        private final Location base;
        private final List<BlockDisplay> parts = new ArrayList<>();
        private final List<Matrix4f> local = new ArrayList<>();
        private final float seed = (float) ThreadLocalRandom.current().nextDouble(10);
        private int t;

        AbomVine(Location base) {
            this.base = base;
        }

        private void add(String block, Matrix4f m) {
            local.add(m);
            parts.add(display(base, block, new Matrix4f().translate(0, -3.6f, 0).mul(m), false));
        }

        private void pose(float y, float sway, int interp) {
            Matrix4f root = new Matrix4f().translate(0, y, 0).rotateX(sway).rotateZ(sway * 0.7f);
            for (int i = 0; i < parts.size(); i++) {
                BlockDisplay d = parts.get(i);
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(interp);
                d.setTransformationMatrix(new Matrix4f(root).mul(local.get(i)));
            }
        }

        @Override
        public boolean tick() {
            t++;
            if (t < WARN && t % 2 == 0) {
                ringFx(base, 1.0, Color.fromRGB(0x1E5A0A), 12);
                world.spawnParticle(Particle.BLOCK, base.clone().add(0, 0.1, 0), 3, 0.4, 0, 0.4, Material.ROOTED_DIRT.createBlockData());
            }
            if (t == WARN) {
                add("minecraft:mangrove_roots", new Matrix4f().translate(-0.8f, 0, -0.8f).scale(1.6f, 0.4f, 1.6f));
                add("minecraft:jungle_log[axis=y]", new Matrix4f().translate(-0.25f, 0, -0.25f).scale(0.5f, 3.0f, 0.5f));
                add("minecraft:vine[east=true,west=true,north=true,south=true]",
                        new Matrix4f().translate(-0.45f, 0.2f, -0.45f).scale(0.9f, 2.9f, 0.9f));
                add("minecraft:jungle_leaves", new Matrix4f().translate(-0.5f, 2.8f, -0.5f).scale(1.0f, 0.7f, 1.0f));
                add("minecraft:mangrove_roots", new Matrix4f().translate(-0.35f, 3.3f, -0.35f).rotateY(0.6f).scale(0.7f, 0.6f, 0.7f));
                // 다음 틱에 보간이 시작되게 한 틱 쉼
            }
            if (t == WARN + 1) {
                pose(0, 0, 4);
                sound(base, Sound.ENTITY_EVOKER_FANGS_ATTACK, 1.4f, 0.6f);
                sound(base, Sound.BLOCK_ROOTED_DIRT_BREAK, 1.4f, 0.7f);
                world.spawnParticle(Particle.BLOCK, base.clone().add(0, 0.3, 0), 30, 0.6, 0.3, 0.6, Material.ROOTED_DIRT.createBlockData());
                for (Player p : players(base, 1.8, 3.5)) {
                    if (hdist(p.getLocation(), base) > 1.15) continue;
                    if (hurt(p, P2_VINE_DMG)) Targets.velocity(p, new Vector(0, 0.7, 0));
                }
            }
            if (t > WARN + 4 && t < LIVE && t % 4 == 0) {
                pose(0, (float) Math.sin((t + seed * 10) * 0.25) * 0.18f, 4);
            }
            if (t > WARN + 4 && t < LIVE && t % 10 == 0) {
                for (Player p : players(base, 1.5, 3.5)) {
                    if (hdist(p.getLocation(), base) <= 0.85) hurt(p, P2_VINE_TOUCH);
                }
            }
            if (t == LIVE) {
                pose(-3.6f, 0, 8);
                sound(base, Sound.BLOCK_ROOTED_DIRT_PLACE, 1f, 0.6f);
            }
            return t >= END;
        }

        @Override
        public void cleanup() {
            for (BlockDisplay d : parts) d.remove();
        }
    }

    /** 곤봉 충격파: 바닥을 따라 퍼지는 고리. */
    final class Shockwave implements Effect {
        private final Location c;
        private final double max;
        private final BlockData data;
        private double r = 0.5;

        Shockwave(Location c, double max, BlockData data) {
            this.c = c;
            this.max = max;
            this.data = data;
        }

        @Override
        public boolean tick() {
            r += 0.5;
            int n = (int) (r * 10);
            for (int i = 0; i < n; i++) {
                double a = Math.PI * 2 * i / n;
                world.spawnParticle(Particle.BLOCK, c.clone().add(Math.cos(a) * r, 0.15, Math.sin(a) * r), 1, 0, 0.05, 0, data);
            }
            return r >= max;
        }

        @Override
        public void cleanup() {}
    }

    // ================================================================== 도우미

    private BlockDisplay display(Location at, String block, Matrix4f m, boolean glow) {
        Location l = at.clone();
        l.setYaw(0);
        l.setPitch(0);
        return world.spawn(l, BlockDisplay.class, d -> {
            d.setBlock(Bukkit.createBlockData(block));
            d.setPersistent(false);
            d.setViewRange(1.5f);
            d.setDisplayWidth(4f);
            d.setDisplayHeight(5f);
            if (glow) d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformationMatrix(m);
            MossRig.tag(d);
        });
    }

    private @Nullable Player target() {
        Player best = null;
        double bd = AGGRO * AGGRO;
        for (Player p : world.getPlayers()) {
            if (!valid(p)) continue;
            double d = p.getLocation().distanceSquared(anchor);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private static boolean valid(Player p) {
        return p.isValid() && !p.isDead() && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE);
    }

    /** 수평 반경 r, 높이 차 h 이내의 공격 가능한 플레이어. */
    private List<Player> players(Location c, double r, double h) {
        List<Player> out = new ArrayList<>();
        for (Player p : c.getWorld().getPlayers()) {
            if (!valid(p)) continue;
            Location l = p.getLocation();
            if (hdist(l, c) <= r && l.getY() > c.getY() - h && l.getY() < c.getY() + h) out.add(p);
        }
        return out;
    }

    private boolean hurt(Player p, double amount) {
        if (!valid(p)) return false;
        double before = p.getHealth();
        DamageSource src = DamageSource.builder(DamageType.MOB_ATTACK).withCausingEntity(hitbox).withDirectEntity(hitbox).build();
        p.damage(amount * cfg.damageMultiplier, src);
        return p.getHealth() < before || p.isDead() || p.getNoDamageTicks() > 0;
    }

    private static double hdist(Location a, Location b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 플레이어 몸(발~머리 중간)과 선분 사이 거리. */
    private static double segDist(Player p, Location a, Location b) {
        Vector pt = p.getLocation().add(0, 0.9, 0).toVector();
        Vector ab = b.toVector().subtract(a.toVector());
        double len2 = ab.lengthSquared();
        double f = len2 < 1e-6 ? 0 : Math.max(0, Math.min(1, pt.clone().subtract(a.toVector()).dot(ab) / len2));
        return pt.distance(a.toVector().add(ab.multiply(f)));
    }

    private Vector forward() {
        double r = Math.toRadians(yaw);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    private void turnToward(Location to, float maxDeg) {
        double dx = to.getX() - anchor.getX(), dz = to.getZ() - anchor.getZ();
        if (dx * dx + dz * dz < 1e-4) return;
        float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float diff = wrap(want - yaw);
        yaw = wrap(yaw + Math.max(-maxDeg, Math.min(maxDeg, diff)));
    }

    private double angleTo(Location to) {
        double dx = to.getX() - anchor.getX(), dz = to.getZ() - anchor.getZ();
        float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
        return Math.abs(wrap(want - yaw));
    }

    private static float wrap(float a) {
        a %= 360;
        if (a >= 180) a -= 360;
        if (a < -180) a += 360;
        return a;
    }

    /** 보스방 바닥 높이 (앵커 근처에서 위아래로 찾음). */
    private Location ground(Location l) {
        Location out = l.clone();
        int base = anchor.getBlockY();
        for (int y = base + 3; y >= base - 4; y--) {
            Block b = world.getBlockAt(l.getBlockX(), y, l.getBlockZ());
            if (!b.isPassable() && b.getRelative(0, 1, 0).isPassable()) {
                out.setY(y + 1);
                return out;
            }
        }
        out.setY(anchor.getY());
        return out;
    }

    private void ringFx(Location c, double r, Color color, int points) {
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.3f);
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            world.spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * r, 0.15, Math.sin(a) * r), 1, 0, 0, 0, dust);
        }
    }

    private void sound(Location at, Sound s, float v, float p) {
        world.playSound(at, s, SoundCategory.HOSTILE, v, p);
    }

    private void updateBar() {
        bar.progress((float) Math.max(0, Math.min(1, hitbox.getHealth() / maxHp)));
        Set<UUID> now = new HashSet<>();
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(anchor) <= 48 * 48) now.add(p.getUniqueId());
        }
        for (Iterator<UUID> it = viewers.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            if (!now.contains(id)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.hideBossBar(bar);
                it.remove();
            }
        }
        for (UUID id : now) {
            if (viewers.add(id)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.showBossBar(bar);
            }
        }
    }
}
