package com.fsixel.deruyrpg.weapons.axe;

import com.fsixel.deruyrpg.DeruyRPG;
import com.fsixel.deruyrpg.util.Targets;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 도끼 연출용 ItemDisplay (NETHERITE_AXE) 관리.
 * 모든 디스플레이는 markSummon + persistent=false, 타이머로 제거되고 onUnequip 시 clear() 로 즉시 정리된다.
 *
 * 아이템 텍스처는 손잡이가 왼쪽 아래, 날이 오른쪽 위(+x,+y 대각선)이므로 Z축 -45° 회전하면 날이 +X 를 향한다.
 */
final class AxeDisplays {

    private static final float DEG = (float) (Math.PI / 180.0);
    /** 아이템 중심 → 날 끝까지 거리 (스케일 1 기준, 대략). */
    private static final double HALF_LEN = 0.6;

    private final Map<UUID, List<Entity>> displays = new ConcurrentHashMap<>();
    private final Map<UUID, List<BukkitTask>> tasks = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ 주변 회전

    /**
     * 플레이어 주변을 도는 도끼들.
     *
     * @param count  도끼 개수 (균등 배치)
     * @param reach  중심에서 날 끝까지 반경
     * @param scale  도끼 크기
     * @param y      발 기준 높이
     * @param ticks  회전 시간
     * @param turns  회전 수
     * @param trail  날 끝에 남길 파티클 (null 이면 없음)
     */
    void orbit(DeruyRPG plugin, Player owner, int count, double reach, float scale, double y,
               int ticks, double turns, @Nullable Particle trail, @Nullable Consumer<Location> trailExtra) {
        Location base = anchor(owner);
        double center = Math.max(0.3, reach - HALF_LEN * scale);
        double start = Math.toRadians(owner.getLocation().getYaw() + 90); // 정면에서 시작

        List<ItemDisplay> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double a = start + Math.PI * 2 * i / count;
            ItemDisplay d = spawn(owner, base, scale, orbitTransform(a, center, y, scale));
            list.add(d);
        }

        BukkitTask task = new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t++;
                if (t > ticks || !owner.isOnline() || owner.isDead()) {
                    for (ItemDisplay d : list) remove(owner, d);
                    untrack(owner, this.getTaskId());
                    cancel();
                    return;
                }
                Location now = anchor(owner);
                double progress = (double) t / ticks;
                for (int i = 0; i < list.size(); i++) {
                    ItemDisplay d = list.get(i);
                    if (!d.isValid()) continue;
                    double a = start + Math.PI * 2 * i / count + Math.PI * 2 * turns * progress;
                    if (now.getWorld() == d.getWorld()) d.teleport(now);
                    d.setInterpolationDelay(0);
                    d.setInterpolationDuration(1);
                    d.setTransformation(orbitTransform(a, center, y, scale));
                    if (trail != null || trailExtra != null) {
                        Location tip = now.clone().add(Math.cos(a) * reach, y, Math.sin(a) * reach);
                        if (trail != null) tip.getWorld().spawnParticle(trail, tip, 1, 0, 0, 0, 0);
                        if (trailExtra != null) trailExtra.accept(tip);
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
        track(owner, task);
    }

    /** 각도 a 위치에 날이 바깥을 향하도록 눕힌(약간 기울인) 도끼. */
    private static Transformation orbitTransform(double a, double center, double y, float scale) {
        Quaternionf rot = new Quaternionf()
                .rotateY((float) -a)
                .rotateX(75 * DEG)
                .rotateZ(-45 * DEG);
        Vector3f tr = new Vector3f((float) (Math.cos(a) * center), (float) y, (float) (Math.sin(a) * center));
        return new Transformation(tr, rot, new Vector3f(scale), new Quaternionf());
    }

    // ------------------------------------------------------------------ 내려찍기

    /**
     * 플레이어 앞에서 큰 도끼를 들어 올렸다가 앞으로 내려찍는 연출.
     * onImpact 는 도끼가 바닥에 닿는 틱에 호출된다 (플레이어가 유효할 때만).
     *
     * @return 내려찍는 데 걸리는 틱 수
     */
    int chop(DeruyRPG plugin, Player owner, float scale, Runnable onImpact) {
        Location base = owner.getLocation();
        base.setPitch(0);
        final Vector3f pivot = new Vector3f(0, 1.3f, 0.6f);
        final float[] angles = {115f, 75f, 30f, -12f}; // 위(뒤) → 앞 바닥
        final int windUp = 3;

        ItemDisplay d = spawn(owner, base, scale, chopTransform(angles[0], pivot, scale));

        BukkitTask task = new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t++;
                int step = t - windUp; // 1,2,3 → angles[1..3]
                if (step >= 1 && step < angles.length && d.isValid()) {
                    d.setInterpolationDelay(0);
                    d.setInterpolationDuration(1);
                    d.setTransformation(chopTransform(angles[step], pivot, scale));
                }
                if (step == angles.length && owner.isOnline() && !owner.isDead()) {
                    onImpact.run();
                }
                if (t >= windUp + angles.length + 14 || !owner.isOnline()) {
                    if (d.isValid()) {
                        d.getWorld().spawnParticle(Particle.SMOKE, d.getLocation().add(0, 0.5, 0), 8, 0.6, 0.3, 0.6, 0.01);
                    }
                    remove(owner, d);
                    untrack(owner, getTaskId());
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
        track(owner, task);
        return windUp + angles.length;
    }

    /**
     * 엔티티 yaw 기준 로컬 좌표(+Z 정면, +Y 위)에서 피벗을 축으로 phi 도 만큼 세운 도끼.
     * phi=90 이면 날이 위, 0 이면 정면 수평.
     */
    private static Transformation chopTransform(float phiDeg, Vector3f pivot, float scale) {
        float phi = phiDeg * DEG;
        Quaternionf rot = new Quaternionf()
                .rotateX(-phi)
                .rotateY(-90 * DEG)
                .rotateX(35 * DEG)   // 날 면이 뒤에서도 보이도록 비틈
                .rotateZ(-45 * DEG);
        float off = (float) (HALF_LEN * scale);
        Vector3f tr = new Vector3f(pivot).add(0, (float) Math.sin(phi) * off, (float) Math.cos(phi) * off);
        return new Transformation(tr, rot, new Vector3f(scale), new Quaternionf());
    }

    // ------------------------------------------------------------------ 공통

    private static Location anchor(Player p) {
        Location l = p.getLocation();
        l.setYaw(0);
        l.setPitch(0);
        return l;
    }

    private ItemDisplay spawn(Player owner, Location at, float scale, Transformation tf) {
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setItemStack(new ItemStack(Material.NETHERITE_AXE));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setPersistent(false);
            e.setRotation(at.getYaw(), 0);
            e.setTeleportDuration(1);
            e.setViewRange(Math.max(1.0f, scale));
            e.setBrightness(new Display.Brightness(15, 15));
            e.setTransformation(tf);
            Targets.markSummon(e);
        });
        displays.computeIfAbsent(owner.getUniqueId(), k -> new ArrayList<>()).add(d);
        return d;
    }

    private void remove(Player owner, Entity e) {
        if (e.isValid()) e.remove();
        List<Entity> l = displays.get(owner.getUniqueId());
        if (l != null) l.remove(e);
    }

    private void track(Player owner, BukkitTask task) {
        tasks.computeIfAbsent(owner.getUniqueId(), k -> new ArrayList<>()).add(task);
    }

    private void untrack(Player owner, int taskId) {
        List<BukkitTask> l = tasks.get(owner.getUniqueId());
        if (l != null) l.removeIf(t -> t.getTaskId() == taskId);
    }

    /** 이 플레이어의 모든 연출 즉시 제거. */
    void clear(Player owner) {
        List<BukkitTask> t = tasks.remove(owner.getUniqueId());
        if (t != null) for (BukkitTask task : t) task.cancel();
        List<Entity> d = displays.remove(owner.getUniqueId());
        if (d != null) for (Entity e : d) if (e.isValid()) e.remove();
    }
}
