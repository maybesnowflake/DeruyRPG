package com.fsixel.deruyrpg.weapons.sword;

import com.fsixel.deruyrpg.DeruyRPG;
import com.fsixel.deruyrpg.api.SkillContext;
import com.fsixel.deruyrpg.api.WeaponKit;
import com.fsixel.deruyrpg.util.Cc;
import com.fsixel.deruyrpg.util.Damage;
import com.fsixel.deruyrpg.util.Fx;
import com.fsixel.deruyrpg.util.Items;
import com.fsixel.deruyrpg.util.Targets;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BlocksAttacks;
import io.papermc.paper.datacomponent.item.blocksattacks.DamageReduction;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
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
import org.bukkit.block.BlockFace;
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
 * P 연속 베기 / 우 막기(유지)·패링(시작 0.25초) / 좌 휩쓸기 베기(평타) / ⇧우 돌진 베기 / F 일섬
 *
 * 피버(15초): 평타 전부 크리티컬(×1.5) · 공격 속도 대폭 증가 · 평타 대상 무적시간 무시 ·
 *             4중첩 크리티컬 시 대상 0.1초 프리징 · ⇧우 쿨다운 15% 감소
 */
public final class SwordKit implements WeaponKit {

    // ---- 연속 베기 ----
    private static final int COMBO_MAX_STACKS = 4;
    private static final int COMBO_RESET_TICKS = 60;          // 3초 안 때리면 초기화
    private static final int COMBO_MIN_HIT_INTERVAL = 8;      // 연타 스팸 방지 (이보다 빠른 타격은 카운트 X)
    private static final double COMBO_DAMAGE_PER_STACK = 0.06; // 중첩당 평타 피해 +6%
    private static final double COMBO_SPEED_PER_STACK = 0.12;  // 중첩당 공격 속도 +0.12
    private static final double EMPOWERED_MAIN_MULT = 1.3;    // 3타째 본타 +30%
    private static final double EMPOWERED_SWEEP_BASE = 4.0;   // 3타째 휩쓸기 피해 (+중첩당 1)

    // ---- 패링 ----
    private static final int PARRY_WINDOW_TICKS = 5;          // 0.25초 패링 창
    private static final int PARRY_COOLDOWN_TICKS = 10;       // 창 종료 후 0.5초 → 총 0.75초 (패링 판정에만 적용)

    // ---- 막기 (바닐라 BLOCKS_ATTACKS) ----
    private static final float BLOCK_FACTOR = 0.5f;           // 정면(±90°) 피해 50% 감소

    // ---- 돌진 베기 ----
    private static final int DASH_COOLDOWN = 140;             // 7초
    private static final double DASH_FEVER_CD_MULT = 0.85;    // 피버 시 15% 감소
    private static final double DASH_DAMAGE = 6.0;
    private static final double DASH_DAMAGE_FEVER = 8.0;
    private static final double DASH_HIT_RADIUS = 1.7;
    /** 틱별 수평 속도 (가속 → 감속). 합 ≈ 2.3칸 + 마지막 관성 ≈ 2.5칸. */
    private static final double[] DASH_PROFILE = {0.30, 0.50, 0.60, 0.55, 0.35};

    // ---- 일섬 ----
    private static final int ULT_CHARGE_TICKS = 10;
    private static final double ULT_RANGE = 8.0;
    private static final double ULT_WIDTH = 1.6;
    private static final double ULT_DAMAGE = 16.0;
    private static final double ULT_SPEED = 1.7;              // 칸/틱 (고속 대시)
    private static final int ULT_MAX_DASH_TICKS = 10;

    // ---- 피버 ----
    private static final double FEVER_CRIT_MULT = 1.5;
    private static final double FEVER_ATTACK_SPEED = 1.6;     // 공격 속도 +1.6 (네더라이트 검 기본 1.6 → 약 2배)
    private static final int FEVER_FREEZE_TICKS = 2;          // 0.1초

    private static final NamespacedKey SPEED_KEY = new NamespacedKey("deruyrpg", "sword_combo_speed");
    private static final NamespacedKey FEVER_SPEED_KEY = new NamespacedKey("deruyrpg", "sword_fever_speed");

    // ---- 플레이어별 상태 ----
    private static final class Combo {
        int hits;
        int stacks;
        int lastHitTick;
        int lastCountedTick = -1000;
    }

    private final Map<UUID, Combo> combos = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> parryUntil = new ConcurrentHashMap<>();
    private final Set<UUID> feverSpeed = ConcurrentHashMap.newKeySet();
    private final Set<UUID> inUltimate = ConcurrentHashMap.newKeySet();
    private final Set<UUID> dashing = ConcurrentHashMap.newKeySet();
    private @Nullable BukkitTask watcher;

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
                "우  막기 (유지) · 막기 시작 0.25초 안 피격 시 패링",
                "좌  휩쓸기 베기",
                "⇧우  2.5칸 돌진 베기",
                "F  일섬",
                "피버  모든 공격 크리티컬 · 공속 대폭 증가 · 무적시간 무시");
        applyBlocking(item);
        return item;
    }

    /**
     * 바닐라 막기: 우클릭 유지 시 방패처럼 막기 자세 + 이동 느려짐, 정면(±90°) 피해 50% 감소. 쿨다운 없음.
     * (도끼에 맞으면 바닐라 방패처럼 잠시 막기 불가 — 방패의 절반 시간)
     */
    private static void applyBlocking(ItemStack item) {
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
    }

    @Override
    public String[] cooldownSlots() {
        return new String[]{Slot.RIGHT, Slot.SHIFT_RIGHT};
    }

    // =====================================================================
    // HUD / 패시브
    // =====================================================================

    @Override
    public @Nullable Component hudExtra(SkillContext ctx) {
        Player p = ctx.player();
        int stacks = currentStacks(p);
        boolean fever = ctx.isFever();
        int dmgPct = (int) Math.round(COMBO_DAMAGE_PER_STACK * 100 * stacks);
        double speed = COMBO_SPEED_PER_STACK * stacks + (fever ? FEVER_ATTACK_SPEED : 0);

        NamedTextColor color = stacks >= COMBO_MAX_STACKS ? NamedTextColor.GOLD
                : stacks > 0 ? NamedTextColor.AQUA : NamedTextColor.GRAY;
        TextComponent.Builder b = Component.text();
        b.append(Component.text("⚔ " + stacks + "/" + COMBO_MAX_STACKS, color));
        b.append(Component.text(" · 피해 +" + dmgPct + "% · 공속 +" + String.format("%.2f", speed), NamedTextColor.GRAY));
        if (fever) b.append(Component.text(" · 크리 ×" + FEVER_CRIT_MULT, NamedTextColor.GOLD));
        return b.build();
    }

    private int currentStacks(Player p) {
        Combo c = combos.get(p.getUniqueId());
        if (c == null || Bukkit.getCurrentTick() - c.lastHitTick > COMBO_RESET_TICKS) return 0;
        return c.stacks;
    }

    @Override
    public void onPassiveTick(SkillContext ctx) {
        Player p = ctx.player();
        // 2차 버전 검(막기 컴포넌트 제거됨)이면 다시 붙이기 → 우클릭 유지 막기 복구
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!hand.hasData(DataComponentTypes.BLOCKS_ATTACKS) && !p.isHandRaised()) {
            applyBlocking(hand);
            p.getInventory().setItemInMainHand(hand);
        }
        if (ctx.isFever()) applyFeverSpeed(ctx.plugin(), p);
    }

    // =====================================================================
    // P — 연속 베기 / 좌 — 휩쓸기 베기 (평타)
    // =====================================================================

    @Override
    public void onMeleeHit(SkillContext ctx, LivingEntity target, EntityDamageByEntityEvent event) {
        Player p = ctx.player();
        int now = Bukkit.getCurrentTick();
        boolean fever = ctx.isFever();
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
            int every = fever ? 2 : 3;
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

        Location at = target.getLocation().add(0, target.getHeight() * 0.6, 0);
        boolean crit = event.isCritical();
        if (fever) {
            applyFeverSpeed(ctx.plugin(), p);
            // 모든 공격 크리티컬 (바닐라 크리티컬이면 이미 ×1.5 적용됨)
            if (!crit) {
                mult *= FEVER_CRIT_MULT;
                Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1.0f);
            }
            crit = true;
            p.getWorld().spawnParticle(Particle.CRIT, at, 14, 0.3, 0.4, 0.3, 0.3);

            // 무적시간 무시 → 다음 틱에 바로 다시 때릴 수 있게
            Bukkit.getScheduler().runTask(ctx.plugin(), () -> {
                if (target.isValid() && !target.isDead()) target.setNoDamageTicks(0);
            });

            // 최대 중첩 크리티컬 → 0.1초 프리징
            if (c.stacks >= COMBO_MAX_STACKS && crit) {
                if (Cc.stun(target, FEVER_FREEZE_TICKS)) {
                    target.setFreezeTicks(Math.max(target.getFreezeTicks(), target.getMaxFreezeTicks() + 3));
                    p.getWorld().spawnParticle(Particle.SNOWFLAKE, at, 8, 0.3, 0.4, 0.3, 0.02);
                    Fx.sound(at, Sound.BLOCK_GLASS_HIT, 0.6f, 1.8f);
                }
            }
        }

        event.setDamage(event.getDamage() * mult);
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
        if (attr.getModifier(SPEED_KEY) != null) attr.removeModifier(SPEED_KEY);
        if (stacks > 0) {
            attr.addTransientModifier(new AttributeModifier(SPEED_KEY, COMBO_SPEED_PER_STACK * stacks,
                    AttributeModifier.Operation.ADD_NUMBER));
        }
    }

    private void applyFeverSpeed(DeruyRPG plugin, Player p) {
        AttributeInstance attr = p.getAttribute(Attribute.ATTACK_SPEED);
        if (attr == null) return;
        if (attr.getModifier(FEVER_SPEED_KEY) == null) {
            attr.addTransientModifier(new AttributeModifier(FEVER_SPEED_KEY, FEVER_ATTACK_SPEED,
                    AttributeModifier.Operation.ADD_NUMBER));
        }
        feverSpeed.add(p.getUniqueId());
        ensureWatcher(plugin);
    }

    private void removeFeverSpeed(Player p) {
        feverSpeed.remove(p.getUniqueId());
        AttributeInstance attr = p.getAttribute(Attribute.ATTACK_SPEED);
        if (attr != null && attr.getModifier(FEVER_SPEED_KEY) != null) attr.removeModifier(FEVER_SPEED_KEY);
    }

    private void resetCombo(Player p, Combo c) {
        c.hits = 0;
        if (c.stacks > 0) {
            c.stacks = 0;
            applySpeed(p, 0);
        }
    }

    private boolean holding(Player p) {
        return id().equals(Items.kitId(p.getInventory().getItemInMainHand()));
    }

    /**
     * 중첩 만료 · 피버 공속 해제 · 검을 놓았을 때(버리기·인벤토리 이동 등 onUnequip 이 안 오는 경우 포함) 정리.
     */
    private void ensureWatcher(DeruyRPG plugin) {
        if (watcher != null && !watcher.isCancelled()) return;
        watcher = new BukkitRunnable() {
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
                    if (!holding(p) || p.isDead() || now - en.getValue().lastHitTick > COMBO_RESET_TICKS) {
                        applySpeed(p, 0);
                        it.remove();
                    }
                }
                Iterator<UUID> fs = feverSpeed.iterator();
                while (fs.hasNext()) {
                    UUID id = fs.next();
                    Player p = Bukkit.getPlayer(id);
                    if (p == null) {
                        fs.remove();
                        continue;
                    }
                    if (!holding(p) || p.isDead() || !plugin.fever().isFever(p)) {
                        fs.remove();
                        removeFeverSpeed(p);
                    }
                }
                if (combos.isEmpty() && feverSpeed.isEmpty()) {
                    cancel();
                    watcher = null;
                }
            }
        }.runTaskTimer(plugin, 5L, 5L);
    }

    // =====================================================================
    // 우 — 막기(유지, 바닐라) + 패링(막기 시작 0.25초)
    // =====================================================================

    @Override
    public void onRightClick(SkillContext ctx) {
        // 막기 자체는 BLOCKS_ATTACKS 로 바닐라가 처리 (쿨다운 없음, 이벤트 취소 안 함).
        // 패링 판정만 쿨다운: 0.25초 창 + 창 종료 후 0.5초 = 0.75초 뒤 다시 패링 가능.
        Player p = ctx.player();
        if (!ctx.tryCooldown(Slot.RIGHT, PARRY_WINDOW_TICKS + PARRY_COOLDOWN_TICKS)) return;
        UUID id = p.getUniqueId();
        int until = Bukkit.getCurrentTick() + PARRY_WINDOW_TICKS;
        parryUntil.put(id, until);

        // 창 열림 연출: 칼을 세우는 금속음 + 정면 반원 섬광, 창이 열린 동안 반원 잔상 유지
        parryArc(p, true);
        Location front = parryFront(p);
        p.getWorld().spawnParticle(Particle.WHITE_ASH, front, 6, 0.3, 0.3, 0.3, 0);
        Fx.sound(front, Sound.ITEM_ARMOR_EQUIP_NETHERITE, 0.8f, 1.7f);
        Fx.sound(front, Sound.ENTITY_PLAYER_ATTACK_NODAMAGE, 0.6f, 1.6f);
        Fx.sound(front, Sound.BLOCK_NOTE_BLOCK_BELL, 0.35f, 2.0f);

        new BukkitRunnable() {
            int t = 1;

            @Override
            public void run() {
                Integer u = parryUntil.get(id);
                if (!p.isOnline() || p.isDead() || u == null || u != until || t >= PARRY_WINDOW_TICKS) {
                    cancel();
                    return;
                }
                parryArc(p, false);
                t++;
            }
        }.runTaskTimer(ctx.plugin(), 1L, 1L);
    }

    private static Location parryFront(Player p) {
        return p.getEyeLocation().add(flatDir(p).multiply(0.9)).subtract(0, 0.4, 0);
    }

    /** 정면 반원 (±77°). opening=true 면 진하게 두 줄, false 면 창 유지 중 옅은 잔상. */
    private static void parryArc(Player p, boolean opening) {
        Vector fwd = flatDir(p);
        Vector right = new Vector(-fwd.getZ(), 0, fwd.getX());
        Location front = parryFront(p);
        for (int i = -6; i <= 6; i++) {
            double a = i * Math.PI / 14;
            Location l = front.clone().add(right.clone().multiply(Math.sin(a) * 0.9))
                    .add(fwd.clone().multiply(-(1 - Math.cos(a)) * 0.7)).add(0, Math.abs(i) * 0.04, 0);
            if (opening) {
                p.getWorld().spawnParticle(Particle.ENCHANTED_HIT, l, 1, 0, 0, 0, 0);
                p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, l.clone().add(0, 0.35, 0), 1, 0, 0, 0, 0);
            } else if ((i & 1) == 0) {
                p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, l.clone().add(0, 0.15, 0), 1, 0, 0, 0, 0);
            }
        }
    }

    @Override
    public void onDamaged(SkillContext ctx, EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent ebe)) return;
        Player p = ctx.player();
        Integer until = parryUntil.get(p.getUniqueId());
        if (until == null || Bukkit.getCurrentTick() > until) return;

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
    // ⇧우 — 돌진 베기 (매끄러운 미끄러짐)
    // =====================================================================

    @Override
    public void onShiftRightClick(SkillContext ctx) {
        Player p = ctx.player();
        UUID id = p.getUniqueId();
        if (inUltimate.contains(id) || dashing.contains(id)) return;
        int cd = ctx.isFever() ? (int) Math.round(DASH_COOLDOWN * DASH_FEVER_CD_MULT) : DASH_COOLDOWN;
        if (!ctx.tryCooldown(Slot.SHIFT_RIGHT, cd)) return;
        ctx.cancelVanilla(); // 돌진 시엔 막기 자세 안 올림
        dashing.add(id);

        Vector dir = flatDir(p);
        double dmg = ctx.isFever() ? DASH_DAMAGE_FEVER : DASH_DAMAGE;
        Fx.sound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1.3f);
        Fx.sound(p.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 0.6f, 1.4f);

        Glide glide = new Glide(p, dir);
        glide.step(DASH_PROFILE[0]);

        Set<UUID> hit = new HashSet<>();
        new BukkitRunnable() {
            int t = 1;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || t >= DASH_PROFILE.length + 2) {
                    dashing.remove(id);
                    cancel();
                    return;
                }
                if (t < DASH_PROFILE.length) glide.step(DASH_PROFILE[t]);
                t++;
                p.setFallDistance(0);

                Location c = p.getLocation().add(0, 0.9, 0);
                p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0, 0.15, 0), 2, 0.15, 0.02, 0.15, 0.01);
                p.getWorld().spawnParticle(Particle.SWEEP_ATTACK, c.clone().add(dir.clone().multiply(0.8)), 1, 0, 0, 0, 0);
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

    /**
     * 여러 틱에 걸쳐 velocity 로 미는 지면 추종 이동.
     * 지면에선 살짝 아래로 눌러 붙이고, 1칸 턱은 작은 도약으로 넘고, 낭떠러지에선 자연스럽게 떨어짐.
     */
    private static final class Glide {
        private final Player p;
        private final Vector dir;
        private double vy;
        private int airTicks;

        Glide(Player p, Vector dir) {
            this.p = p;
            this.dir = dir;
        }

        void step(double speed) {
            boolean ground = p.isOnGround();
            if (ground && vy <= 0) {
                airTicks = 0;
                vy = -0.08;
                Block feet = p.getLocation().add(dir.clone().multiply(Math.max(0.7, speed + 0.35))).getBlock();
                double h = feet.isPassable() ? 0 : feet.getBoundingBox().getMaxY() - feet.getY();
                if (h > 0.6 && passable(feet.getRelative(BlockFace.UP))
                        && passable(feet.getRelative(BlockFace.UP, 2))) {
                    vy = 0.42; // 1칸 턱 넘기
                }
            } else {
                airTicks++;
                vy = (vy - 0.08) * 0.98;
                if (airTicks > 1 && vy < -0.6) vy = -0.6;
            }
            Vector v = dir.clone().multiply(speed);
            v.setY(vy);
            p.setVelocity(v);
        }

        void stop() {
            p.setVelocity(dir.clone().multiply(0.05).setY(Math.min(0, vy)));
        }
    }

    // =====================================================================
    // F — 일섬 (고속 대시 베기)
    // =====================================================================

    @Override
    public void onUltimate(SkillContext ctx) {
        Player p = ctx.player();
        UUID id = p.getUniqueId();
        if (ctx.isFever()) applyFeverSpeed(ctx.plugin(), p);
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
                    if (t % 3 == 0) Fx.sound(c, Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 0.8f + t * 0.1f);
                    t++;
                    return;
                }
                cancel();
                slashDash(ctx.plugin(), p);
            }
        }.runTaskTimer(ctx.plugin(), 1L, 1L);
    }

    private void slashDash(DeruyRPG plugin, Player p) {
        UUID id = p.getUniqueId();
        Location start = p.getLocation();
        Vector dir = flatDir(p);

        // 막히지 않는 가장 먼 지점 (벽 통과 금지)
        double maxDist = 0;
        for (double d = 0.25; d <= ULT_RANGE; d += 0.25) {
            Location cand = start.clone().add(dir.clone().multiply(d));
            if (!passable(cand.clone().add(0, 1, 0).getBlock())) break;
            Block feet = cand.getBlock();
            if (!passable(feet) && feet.getBoundingBox().getMaxY() - feet.getY() > 0.6
                    && !passable(feet.getRelative(BlockFace.UP))) break;
            maxDist = d;
        }
        final double total = maxDist;

        Fx.sound(start, Sound.ITEM_TRIDENT_THROW, 1f, 1.6f);
        Fx.sound(start, Sound.ENTITY_BREEZE_WIND_BURST, 0.8f, 1.2f);

        Glide glide = new Glide(p, dir);
        Set<UUID> hit = new HashSet<>();

        new BukkitRunnable() {
            int t = 0;
            int stall = 0;
            Location prev = start.clone();

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead()) {
                    finish();
                    return;
                }
                Location cur = p.getLocation();
                cutSegment(p, prev, cur, hit);
                trail(prev, cur, dir);

                double traveled = flatDist(start, cur);
                double remaining = total - traveled;
                if (cur.distanceSquared(prev) < 0.01 && t > 1) stall++;
                else stall = 0;
                prev = cur.clone();
                p.setFallDistance(0);

                if (remaining <= 0.3 || stall >= 2 || t >= ULT_MAX_DASH_TICKS) {
                    glide.stop();
                    finish();
                    return;
                }
                glide.step(Math.min(ULT_SPEED, Math.max(0.3, remaining)));
                // 고속 대시 중 연속 휘두르는 소리
                Fx.sound(cur, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, 1.0f + 0.12f * t);
                t++;
            }

            private void finish() {
                cancel();
                inUltimate.remove(id);
                if (!p.isOnline()) return;
                Location end = p.getLocation();
                // 서버 위치 지연 대비: 실제 이동 경로 전체로 한 번 더 판정
                cutSegment(p, start, end, hit);
                afterSlash(plugin, p, start, end, dir);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void cutSegment(Player p, Location from, Location to, Set<UUID> hit) {
        Vector a = from.toVector().add(new Vector(0, 1, 0));
        Vector b = to.toVector().add(new Vector(0, 1, 0));
        Location mid = from.clone().add(to).multiply(0.5).add(0, 1, 0);
        double radius = a.distance(b) / 2 + ULT_WIDTH + 1.5;
        for (LivingEntity e : Targets.enemiesAround(p, mid, radius)) {
            if (hit.contains(e.getUniqueId())) continue;
            Vector c = e.getLocation().toVector().add(new Vector(0, e.getHeight() / 2, 0));
            if (distToSegment(c, a, b) > ULT_WIDTH) continue;
            hit.add(e.getUniqueId());
            Damage.skill(p, e, ULT_DAMAGE);
            Location cl = c.toLocation(e.getWorld());
            e.getWorld().spawnParticle(Particle.CRIT, cl, 25, 0.3, 0.5, 0.3, 0.5);
            e.getWorld().spawnParticle(Particle.SWEEP_ATTACK, cl, 1, 0, 0, 0, 0);
            Fx.sound(cl, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.9f);
        }
    }

    private static void trail(Location from, Location to, Vector dir) {
        Location f = from.clone().add(0, 1, 0);
        Location t = to.clone().add(0, 1, 0);
        Fx.line(f, t, Particle.CRIT, 0.3);
        Fx.line(f, t, Particle.ENCHANTED_HIT, 0.6);
        from.getWorld().spawnParticle(Particle.CLOUD, to.clone().add(0, 0.15, 0), 2, 0.15, 0.02, 0.15, 0.01);
    }

    /** 대시 후 한 박자 늦게 경로를 따라 여러 번 "베이는" 연출. */
    private static void afterSlash(DeruyRPG plugin, Player p, Location start, Location end, Vector dir) {
        Location lineFrom = start.clone().add(0, 1, 0);
        Location lineTo = end.clone().add(0, 1, 0);
        double len = flatDist(start, end);
        Fx.sound(end, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.8f);
        for (int i = 0; i < 4; i++) {
            final int k = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                double d = len * (k + 0.5) / 4.0;
                Location l = lineFrom.clone().add(dir.clone().multiply(d));
                l.getWorld().spawnParticle(Particle.SWEEP_ATTACK, l, 2, 0.3, 0.2, 0.3, 0);
                Fx.sound(l, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.7f + 0.15f * k);
            }, 3L + i * 2L);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Fx.line(lineFrom, lineTo, Particle.CRIT, 0.35);
            Fx.sound(lineTo, Sound.BLOCK_ANVIL_LAND, 0.5f, 2.0f);
            Fx.sound(lineTo, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 0.6f);
        }, 12L);
    }

    private static double flatDist(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
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
        removeFeverSpeed(player);
        parryUntil.remove(id);
    }
}
