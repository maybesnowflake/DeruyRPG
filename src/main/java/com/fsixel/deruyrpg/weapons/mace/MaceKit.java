package com.fsixel.deruyrpg.weapons.mace;

import com.fsixel.deruyrpg.DeruyRPG;
import com.fsixel.deruyrpg.api.SkillContext;
import com.fsixel.deruyrpg.api.WeaponKit;
import com.fsixel.deruyrpg.util.Cc;
import com.fsixel.deruyrpg.util.Damage;
import com.fsixel.deruyrpg.util.Fx;
import com.fsixel.deruyrpg.util.Items;
import com.fsixel.deruyrpg.util.Targets;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 철퇴.
 * P  낙하 강타: 바닐라 강타(Smash) 기반. 낙하 거리 비례 추가 피해 (상한 없음, 바닐라 보너스 그대로).
 * 우 도약 강타: 위로 도약 → 착지 시 주변 범위 피해 + 띄우기. 착지 낙하 피해 무효.
 * 좌 강타: 평타 (바닐라 강타 유지).
 * ⇧우 돌풍: 공중에서만. 한 번 더 점프 (Wind Burst 느낌).
 * F  지진: 주변 블록이 FallingBlock 으로 튀어오름(설치 안 함) + 파동 범위 띄우기·피해·기절 0.5초.
 * 피버: 낙하 강타 +15%, 도약 강타 쿨다운 0, 돌풍 공중 2회(착지 후 쿨다운 시작).
 */
public final class MaceKit implements WeaponKit, Listener {

    // ---- 수치 ----
    private static final double SMASH_MIN_FALL = 1.5;     // 바닐라 강타 발동 조건
    private static final double PASSIVE_PER_BLOCK = 0.4;  // 칸당 추가 피해

    private static final int LEAP_CD = 160;               // 8초
    private static final double LEAP_RADIUS = 3.5;
    private static final double LEAP_DAMAGE = 6.0;
    private static final double LEAP_DAMAGE_FEVER = 8.0;
    private static final int LEAP_TIMEOUT = 80;           // 4초 안에 착지 못하면 취소

    private static final double FEVER_SMASH_MULT = 1.15;  // 피버 낙하 강타 +15%

    private static final int GUST_CD = 60;                // 3초
    private static final int FEVER_GUST_CHARGES = 2;      // 피버: 공중 체공 1회당 돌풍 2회

    private static final int QUAKE_WAVES = 6;             // 반경 1~6칸
    private static final int QUAKE_WAVE_INTERVAL = 2;
    private static final double QUAKE_DAMAGE = 9.0;
    private static final int BLOCK_LIFETIME = 25;
    private static final int QUAKE_DIVE_TIMEOUT = 60;
    private static final int QUAKE_STUN = 10;             // 0.5초 기절

    /** 도약 강타 착지 대기 중인 작업 (무기 해제 시 취소). */
    private final Map<UUID, BukkitTask> landing = new ConcurrentHashMap<>();
    /** 궁극기(지진) 내리꽂기 착지 대기 — 무기 해제와 무관하게 끝까지 진행 (종료·사망·접속 종료 때만 정리). */
    private final Map<UUID, BukkitTask> quakeDive = new ConcurrentHashMap<>();
    /** 낙하 피해 무효 만료 틱. */
    private final Map<UUID, Integer> noFallUntil = new ConcurrentHashMap<>();
    /** 지진 연출용 FallingBlock (블록 설치 금지 대상). */
    private final Set<UUID> quakeBlocks = ConcurrentHashMap.newKeySet();
    /** 피버 돌풍: 이번 체공 중 사용 횟수 (착지 시 쿨다운 시작 후 제거). */
    private final Map<UUID, Integer> gustAirUses = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> gustLandWatch = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "mace";
    }

    @Override
    public String displayName() {
        return "철퇴";
    }

    @Override
    public ItemStack createItem() {
        return Items.weapon(Material.MACE, id(), "철퇴", NamedTextColor.GRAY,
                "P  낙하 강타 — 낙하 거리 비례 추가 피해 (상한 없음)",
                "우  도약 강타 — 착지 시 범위 피해 + 띄우기",
                "좌  강타",
                "⇧우  돌풍 — 공중에서 한 번 더 점프",
                "F  지진 — 충격파 피해 + 0.5초 기절",
                "피버: 낙하 강타 +15%, 도약 쿨 0, 돌풍 공중 2회");
    }

    @Override
    public String[] cooldownSlots() {
        return new String[]{Slot.RIGHT, Slot.SHIFT_RIGHT};
    }

    // ================= P: 낙하 강타 =================

    @Override
    public void onMeleeHit(SkillContext ctx, LivingEntity target, EntityDamageByEntityEvent event) {
        Player p = ctx.player();
        double fall = p.getFallDistance();
        if (fall <= SMASH_MIN_FALL || p.isGliding()) return;

        // 바닐라 강타 보너스는 그대로 두고, 낙하 거리 비례 추가 피해를 상한 없이 더함
        double dmg = event.getDamage() + fall * PASSIVE_PER_BLOCK;
        if (ctx.isFever()) dmg *= FEVER_SMASH_MULT;
        event.setDamage(Math.max(0, dmg));

        if (fall >= 5) {
            Location at = target.getLocation();
            Fx.burst(at.clone().add(0, 0.3, 0), Particle.DUST_PLUME, 20, 0.6);
            Fx.burst(at.clone().add(0, 1, 0), Particle.CRIT, 15, 0.5);
        }
    }

    // ================= 우: 도약 강타 =================

    @Override
    public void onRightClick(SkillContext ctx) {
        Player p = ctx.player();
        if (landing.containsKey(p.getUniqueId()) || quakeDive.containsKey(p.getUniqueId())) return;
        // 피버: 쿨다운 0 (착지 전 재사용은 위 체크로 막힘)
        if (!ctx.isFever() && !ctx.tryCooldown(Slot.RIGHT, LEAP_CD)) return;
        ctx.cancelVanilla();

        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() > 1e-6) dir.normalize().multiply(0.35);
        p.setVelocity(dir.setY(1.15));
        p.setFallDistance(0);

        Location at = p.getLocation();
        Fx.sound(at, Sound.ENTITY_WIND_CHARGE_THROW, 0.8f, 0.7f);
        Fx.burst(at.clone().add(0, 0.2, 0), Particle.CLOUD, 12, 0.3);

        boolean fever = ctx.isFever();
        waitForLanding(ctx.plugin(), p, landing, LEAP_TIMEOUT, () -> leapSlam(p, fever));
    }

    private void leapSlam(Player p, boolean fever) {
        Location c = p.getLocation();
        double dmg = fever ? LEAP_DAMAGE_FEVER : LEAP_DAMAGE;
        for (LivingEntity e : Targets.enemiesAround(p, c, LEAP_RADIUS)) {
            if (!Damage.skill(p, e, dmg)) continue;   // 피해가 들어간 대상만 띄우기
            Targets.push(e, c, 0.45, 0.75);
        }
        Fx.sound(c, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1f, 0.9f);
        Fx.sound(c, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.4f);
        Fx.burst(c.clone().add(0, 0.2, 0), Particle.DUST_PLUME, 40, 1.2);
        Fx.ring(c, Particle.CLOUD, LEAP_RADIUS, 28);
        groundDust(c, LEAP_RADIUS);
    }

    // ================= ⇧우: 돌풍 =================

    @Override
    public void onShiftRightClick(SkillContext ctx) {
        Player p = ctx.player();
        ctx.cancelVanilla();
        if (isGrounded(p)) {
            ctx.flash(Component.text("돌풍은 공중에서만 쓸 수 있습니다", NamedTextColor.GRAY));
            return;
        }
        UUID id = p.getUniqueId();
        Integer used = gustAirUses.get(id);
        if (used != null) {
            // 피버 체공 중 추가 사용
            if (used >= FEVER_GUST_CHARGES) return;
            gustAirUses.put(id, used + 1);
        } else if (ctx.isFever()) {
            if (ctx.onCooldown(Slot.SHIFT_RIGHT)) return;
            gustAirUses.put(id, 1);
            watchGustLanding(ctx.plugin(), p);
        } else if (!ctx.tryCooldown(Slot.SHIFT_RIGHT, GUST_CD)) {
            return;
        }

        Vector v = p.getVelocity();
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() > 1e-6) dir.normalize().multiply(0.25);
        p.setVelocity(new Vector(v.getX() * 0.5 + dir.getX(), ctx.isFever() ? 1.0 : 0.85, v.getZ() * 0.5 + dir.getZ()));
        p.setFallDistance(0);

        Location at = p.getLocation();
        Fx.sound(at, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1f, 1f);
        at.getWorld().spawnParticle(Particle.GUST_EMITTER_SMALL, at, 1);
        Fx.burst(at, Particle.GUST, 4, 0.4);
    }

    /** 피버 돌풍: 착지하면 그때부터 쿨다운 시작. */
    private void watchGustLanding(DeruyRPG plugin, Player p) {
        UUID id = p.getUniqueId();
        BukkitTask old = gustLandWatch.remove(id);
        if (old != null) old.cancel();
        final int[] age = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            age[0]++;
            if (!p.isOnline() || p.isDead() || (age[0] > 3 && isGrounded(p))) {
                finishGust(p);
            }
        }, 1L, 1L);
        gustLandWatch.put(id, task);
    }

    private void finishGust(Player p) {
        UUID id = p.getUniqueId();
        BukkitTask t = gustLandWatch.remove(id);
        if (t != null) t.cancel();
        if (gustAirUses.remove(id) != null) {
            DeruyRPG.get().cooldowns().set(p, id(), Slot.SHIFT_RIGHT, GUST_CD);
        }
    }

    @Override
    public @Nullable Component hudExtra(SkillContext ctx) {
        Integer used = gustAirUses.get(ctx.player().getUniqueId());
        if (used == null) return null;
        return Component.text("돌풍 " + (FEVER_GUST_CHARGES - used) + "/" + FEVER_GUST_CHARGES, NamedTextColor.AQUA);
    }

    // ================= F: 지진 =================

    @Override
    public void onUltimate(SkillContext ctx) {
        Player p = ctx.player();
        cancelLanding(landing, p.getUniqueId());
        cancelLanding(quakeDive, p.getUniqueId());
        if (isGrounded(p)) {
            earthquake(ctx.plugin(), p);
            return;
        }
        // 공중이면 내리꽂은 뒤 착지 지점에서 발동
        p.setVelocity(new Vector(0, -1.6, 0));
        Fx.sound(p.getLocation(), Sound.ITEM_MACE_SMASH_AIR, 1f, 0.6f);
        DeruyRPG plugin = ctx.plugin();
        waitForLanding(plugin, p, quakeDive, QUAKE_DIVE_TIMEOUT, () -> earthquake(plugin, p));
    }

    private void earthquake(DeruyRPG plugin, Player p) {
        Location center = p.getLocation();
        World w = center.getWorld();
        Fx.sound(center, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.5f, 0.6f);
        Fx.sound(center, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.6f);
        w.spawnParticle(Particle.EXPLOSION, center, 2, 0.3, 0.1, 0.3, 0);

        Set<UUID> hit = new HashSet<>();
        for (int i = 1; i <= QUAKE_WAVES; i++) {
            final int radius = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline() || p.isDead()) return;
                quakeWave(plugin, p, center, radius, hit);
            }, (long) (i - 1) * QUAKE_WAVE_INTERVAL);
        }
    }

    private void quakeWave(DeruyRPG plugin, Player p, Location center, int radius, Set<UUID> hit) {
        World w = center.getWorld();
        int points = Math.max(6, (int) Math.round(2 * Math.PI * radius * 0.8));
        double offset = Math.random() * Math.PI;
        for (int k = 0; k < points; k++) {
            double a = offset + Math.PI * 2 * k / points;
            int bx = (int) Math.floor(center.getX() + Math.cos(a) * radius);
            int bz = (int) Math.floor(center.getZ() + Math.sin(a) * radius);
            Block surface = findSurface(w, bx, center.getBlockY(), bz);
            if (surface == null) continue;
            spawnQuakeBlock(plugin, surface);
        }

        Fx.sound(center, Sound.BLOCK_ANVIL_LAND, 0.4f, 0.5f + radius * 0.05f);
        Fx.sound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.35f, 0.8f);

        double inner = radius - 1.0, outer = radius + 0.5;
        for (LivingEntity e : Targets.enemiesAround(p, center, outer + 1)) {
            double d = horizontalDist(e.getLocation(), center);
            if (d < inner || d > outer || Math.abs(e.getLocation().getY() - center.getY()) > 3) continue;
            if (!hit.add(e.getUniqueId())) continue;
            if (!Damage.skill(p, e, QUAKE_DAMAGE)) continue;   // 피해가 들어간 대상만 기절·띄우기
            Cc.stun(e, QUAKE_STUN);               // 기절 먼저 (stun 이 속도를 초기화하므로)
            Targets.push(e, center, 0.3, 1.0);
            Fx.burst(e.getLocation().add(0, 0.5, 0), Particle.CRIT, 10, 0.4);
        }
    }

    private void spawnQuakeBlock(DeruyRPG plugin, Block surface) {
        BlockData data = surface.getBlockData();
        Location at = surface.getLocation().add(0.5, 1.02, 0.5);
        World w = at.getWorld();
        w.spawnParticle(Particle.BLOCK, at, 8, 0.3, 0.1, 0.3, 0.1, data);

        FallingBlock fb = w.spawnFallingBlock(at, data);
        Targets.markSummon(fb);
        quakeBlocks.add(fb.getUniqueId());
        fb.setDropItem(false);
        fb.setCancelDrop(true);
        fb.setHurtEntities(false);
        fb.setGravity(true);
        fb.setInvulnerable(true);
        fb.setPersistent(false);
        fb.setVelocity(new Vector((Math.random() - 0.5) * 0.1, 0.35 + Math.random() * 0.25, (Math.random() - 0.5) * 0.1));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            quakeBlocks.remove(fb.getUniqueId());
            if (fb.isValid()) fb.remove();
        }, BLOCK_LIFETIME);
    }

    /** x,z 칸의 지표 블록 (y 기준 위 1 ~ 아래 3칸). 위가 비어 있는 단단한 일반 블록만. */
    private static @Nullable Block findSurface(World w, int x, int y, int z) {
        for (int dy = 1; dy >= -3; dy--) {
            Block b = w.getBlockAt(x, y + dy - 1, z);
            Material m = b.getType();
            if (!m.isSolid() || !m.isOccluding()) continue;
            if (!b.getRelative(0, 1, 0).isPassable()) continue;
            if (m == Material.BEDROCK || m == Material.BARRIER) return null;
            return b;
        }
        return null;
    }

    /** FallingBlock 이 블록으로 설치되는 것 차단 (월드 영구 변경 금지). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuakeBlockLand(EntityChangeBlockEvent e) {
        if (!(e.getEntity() instanceof FallingBlock fb)) return;
        // 우리 지진 블록 (집합에서 빠졌어도 연출 표시가 있으면 설치 금지)
        if (!quakeBlocks.remove(fb.getUniqueId()) && !Targets.isSummon(fb)) return;
        e.setCancelled(true);
        fb.remove();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuakeBlockDrop(EntityDropItemEvent e) {
        if (e.getEntity() instanceof FallingBlock && quakeBlocks.contains(e.getEntity().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    // ================= 착지 처리 =================

    /** 이륙을 기다렸다가 착지하면 onLand 실행. 대기 중·착지 직후 낙하 피해 무효. */
    private void waitForLanding(DeruyRPG plugin, Player p, Map<UUID, BukkitTask> map, int timeout, Runnable onLand) {
        UUID id = p.getUniqueId();
        cancelLanding(map, id);
        noFallUntil.put(id, Bukkit.getCurrentTick() + timeout + 10);
        final int[] age = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            age[0]++;
            if (!p.isOnline() || p.isDead()) {
                cancelLanding(map, id);
                return;
            }
            if (age[0] > 3 && isGrounded(p)) {
                cancelLanding(map, id);
                noFallUntil.put(id, Bukkit.getCurrentTick() + 5);
                onLand.run();
                return;
            }
            if (age[0] > timeout) {
                cancelLanding(map, id);
            }
        }, 1L, 1L);
        map.put(id, task);
    }

    private static void cancelLanding(Map<UUID, BukkitTask> map, UUID id) {
        BukkitTask t = map.remove(id);
        if (t != null) t.cancel();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (Damage.isProbe()) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(e.getEntity() instanceof Player p)) return;
        Integer until = noFallUntil.get(p.getUniqueId());
        if (until == null) return;
        if (Bukkit.getCurrentTick() > until) {
            noFallUntil.remove(p.getUniqueId());
            return;
        }
        e.setCancelled(true);
    }

    // ================= 공통 =================

    @SuppressWarnings("deprecation")
    private static boolean isGrounded(Player p) {
        if (p.isOnGround()) return true;
        Location l = p.getLocation();
        return l.clone().subtract(0, 0.05, 0).getBlock().getType().isSolid() && p.getVelocity().getY() <= 0.01;
    }

    private static double horizontalDist(Location a, Location b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static void groundDust(Location c, double radius) {
        Block below = c.clone().subtract(0, 0.5, 0).getBlock();
        if (!below.getType().isSolid()) return;
        c.getWorld().spawnParticle(Particle.BLOCK, c.clone().add(0, 0.1, 0), 40,
                radius / 2, 0.1, radius / 2, 0.15, below.getBlockData());
    }

    /**
     * 무기 해제 (여러 번 불려도 안전). 도약 착지 대기·피버 돌풍만 정리.
     * 궁극기(지진) 내리꽂기·파동은 끝까지 진행. 낙하 피해 무효는 시간 만료로 자연 정리 (공중에서 바꿔 들어도 보호).
     */
    @Override
    public void onUnequip(Player player) {
        cancelLanding(landing, player.getUniqueId());
        finishGust(player);
    }

    /** 접속 종료: 남은 상태 전부 정리. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        cancelLanding(landing, id);
        cancelLanding(quakeDive, id);
        noFallUntil.remove(id);
        BukkitTask t = gustLandWatch.remove(id);
        if (t != null) t.cancel();
        gustAirUses.remove(id);
    }
}
