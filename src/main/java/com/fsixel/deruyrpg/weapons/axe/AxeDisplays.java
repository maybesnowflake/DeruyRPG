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
import org.joml.Matrix3f;
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
 * 모든 디스플레이는 markSummon + persistent=false, 타이머로 제거되고 접속 종료·사망 시 clear() 로 즉시 정리된다.
 *
 * 모든 디스플레이는 yaw/pitch 0 으로 소환하고, 방향은 orient() 로 월드 좌표 기준 회전을 만든다.
 */
final class AxeDisplays {

    private static final float DEG = (float) (Math.PI / 180.0);
    /** 아이템 중심 → 날 끝까지 거리 (스케일 1 기준, 대략). */
    private static final double HALF_LEN = 0.6;

    private final Map<UUID, List<Entity>> displays = new ConcurrentHashMap<>();
    private final Map<UUID, List<BukkitTask>> tasks = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ 방향 계산

    /*
     * 바닐라 도끼 텍스처(16x16): 손잡이 왼쪽 아래 → 머리 오른쪽 위, 도끼날(절단면)은 머리의 왼쪽 위.
     * FIXED 모드 ItemDisplay 는 (아이템 프레임과 같게) 텍스처 +x → 로컬 +X, +y → 로컬 +Y 로 그려진다.
     * 따라서 로컬 좌표에서
     *   TIP0  = (+1,+1,0)/√2 : 손잡이 → 머리 (아이템 "끝")
     *   EDGE0 = (-1,+1,0)/√2 : 도끼날이 바라보는 방향 (베는 쪽)
     * 아래 orient() 는 이 두 벡터를 원하는 월드 방향으로 보내는 회전을 만든다.
     * 디스플레이 엔티티는 yaw=0, pitch=0 으로 소환하므로 로컬 = 월드 좌표.
     */
    static final Vector3f TIP0 = new Vector3f(1, 1, 0).normalize();
    static final Vector3f EDGE0 = new Vector3f(-1, 1, 0).normalize();

    /** 도끼 끝(TIP0)이 tip, 도끼날(EDGE0)이 edge 쪽을 보게 하는 회전 (edge 는 tip 에 수직이 되게 보정). */
    static Quaternionf orient(Vector3f tip, Vector3f edge) {
        Vector3f t = new Vector3f(tip).normalize();
        Vector3f e = new Vector3f(edge).sub(new Vector3f(t).mul(t.dot(edge))).normalize();
        Vector3f n = new Vector3f(t).cross(e);
        Matrix3f world = new Matrix3f(t, e, n);
        Matrix3f local = new Matrix3f(TIP0, EDGE0, new Vector3f(TIP0).cross(EDGE0));
        Matrix3f r = world.mul(local.transpose(new Matrix3f()), new Matrix3f());
        return r.getNormalizedRotation(new Quaternionf());
    }

    // ------------------------------------------------------------------ 주변 회전

    /** 회전하는 도끼 끝을 바깥쪽에서 위로 세운 각도 (보는 사람에게 면이 보이도록). */
    static final float ORBIT_TILT = 30f * DEG;

    /**
     * 플레이어 주변을 도는 도끼들. 손잡이는 중심, 머리는 바깥, 도끼날은 회전 진행 방향(접선)을 향한다.
     *
     * @param count  도끼 개수 (균등 배치)
     * @param reach  중심에서 날 끝까지 수평 반경
     * @param scale  도끼 크기
     * @param y      발 기준 높이
     * @param ticks  회전 시간
     * @param turns  회전 수 (양수: 위에서 볼 때 시계 방향)
     * @param trail  날 끝에 남길 파티클 (null 이면 없음)
     */
    void orbit(DeruyRPG plugin, Player owner, int count, double reach, float scale, double y,
               int ticks, double turns, @Nullable Particle trail, @Nullable Consumer<Location> trailExtra) {
        Location base = anchor(owner);
        double start = Math.toRadians(owner.getLocation().getYaw() + 90); // 정면에서 시작
        int dir = turns >= 0 ? 1 : -1;

        List<ItemDisplay> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double a = start + Math.PI * 2 * i / count;
            ItemDisplay d = spawn(owner, base, scale, orbitTransform(a, dir, reach, y, scale));
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
                    d.setTransformation(orbitTransform(a, dir, reach, y, scale));
                    if (trail != null || trailExtra != null) {
                        Vector3f tp = orbitTip(a, reach, y, scale);
                        Location tip = now.clone().add(tp.x, tp.y, tp.z);
                        if (trail != null) tip.getWorld().spawnParticle(trail, tip, 1, 0, 0, 0, 0);
                        if (trailExtra != null) trailExtra.accept(tip);
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
        track(owner, task);
    }

    /** 각도 a 의 바깥 방향(수평) + 위로 ORBIT_TILT 만큼 든 도끼 끝 방향. */
    static Vector3f orbitTipDir(double a) {
        float c = (float) Math.cos(ORBIT_TILT);
        return new Vector3f((float) Math.cos(a) * c, (float) Math.sin(ORBIT_TILT), (float) Math.sin(a) * c);
    }

    /** 각도 a 에서 회전 진행 방향(접선, 수평). dir=+1 이면 a 가 증가하는 방향. */
    static Vector3f orbitMoveDir(double a, int dir) {
        return new Vector3f((float) -Math.sin(a) * dir, 0, (float) Math.cos(a) * dir);
    }

    /** 도끼 중심 위치 (앵커 기준): 날 끝이 수평 반경 reach 에 오도록. */
    static Vector3f orbitCenter(double a, double reach, double y, float scale) {
        Vector3f tipDir = orbitTipDir(a);
        float off = (float) (HALF_LEN * scale);
        // 날 끝: 수평 반경 reach, 도끼 중심과 끝의 높이 평균이 y 근처가 되게
        Vector3f tip = new Vector3f((float) (Math.cos(a) * reach), (float) y + tipDir.y * off * 0.5f,
                (float) (Math.sin(a) * reach));
        return tip.sub(new Vector3f(tipDir).mul(off));
    }

    static Vector3f orbitTip(double a, double reach, double y, float scale) {
        return orbitCenter(a, reach, y, scale).add(orbitTipDir(a).mul((float) (HALF_LEN * scale)));
    }

    static Transformation orbitTransform(double a, int dir, double reach, double y, float scale) {
        Quaternionf rot = orient(orbitTipDir(a), orbitMoveDir(a, dir));
        return new Transformation(orbitCenter(a, reach, y, scale), rot, new Vector3f(scale), new Quaternionf());
    }

    // ------------------------------------------------------------------ 내려찍기

    /** 손잡이 끝(손) 위치: 정면 0.5칸, 높이 1.2칸. */
    static final float CHOP_FWD = 0.5f;
    static final float CHOP_UP = 1.2f;
    /** 도끼날 면을 뒤(시전자 시점)에서도 보이게 손잡이 축으로 비트는 각도. */
    static final float CHOP_TWIST = 30f * DEG;
    /** 프레임별 도끼 각도(도, 0=정면 수평, 90=수직 위). 0~2: 앞으로 들어 올림, 3: 멈춤, 4~7: 앞으로 내려찍기. */
    static final float[] CHOP_FRAMES = {40f, 70f, 95f, 95f, 60f, 25f, -5f, -20f};

    /**
     * 플레이어 앞에서 큰 도끼를 들어 올렸다가 앞으로 내려찍는 연출.
     * onImpact 는 도끼가 바닥에 닿는 틱에 호출된다 (플레이어가 유효할 때만).
     *
     * @return 내려찍는 데 걸리는 틱 수
     */
    int chop(DeruyRPG plugin, Player owner, float scale, Runnable onImpact) {
        Location base = owner.getLocation();
        double yawRad = Math.toRadians(base.getYaw());
        final Vector3f fwd = new Vector3f((float) -Math.sin(yawRad), 0, (float) Math.cos(yawRad));
        base.setYaw(0);
        base.setPitch(0);
        final int impact = CHOP_FRAMES.length - 1;

        ItemDisplay d = spawn(owner, base, scale, chopTransform(CHOP_FRAMES[0], fwd, scale));

        BukkitTask task = new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t++;
                if (t <= impact && d.isValid()) {
                    d.setInterpolationDelay(0);
                    d.setInterpolationDuration(1);
                    d.setTransformation(chopTransform(CHOP_FRAMES[t], fwd, scale));
                }
                if (t == impact && owner.isOnline() && !owner.isDead()) {
                    onImpact.run();
                }
                if (t >= impact + 14 || !owner.isOnline()) {
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
        return impact;
    }

    /** phi 도에서 도끼 끝 방향 = 위·sin + 정면·cos. */
    static Vector3f chopTipDir(float phiDeg, Vector3f fwd) {
        float phi = phiDeg * DEG;
        return new Vector3f(fwd).mul((float) Math.cos(phi)).add(0, (float) Math.sin(phi), 0);
    }

    /** 내려찍는 진행 방향 (phi 가 줄어드는 방향) = 정면·sin − 위·cos. */
    static Vector3f chopMoveDir(float phiDeg, Vector3f fwd) {
        float phi = phiDeg * DEG;
        return new Vector3f(fwd).mul((float) Math.sin(phi)).sub(0, (float) Math.cos(phi), 0);
    }

    /** 도끼날 방향: 진행 방향을 손잡이 축으로 CHOP_TWIST 만큼 비틈 (시전자 시점에서 면이 보이게). */
    static Vector3f chopEdgeDir(float phiDeg, Vector3f fwd) {
        Vector3f t = chopTipDir(phiDeg, fwd);
        Vector3f m = chopMoveDir(phiDeg, fwd);
        Vector3f n = new Vector3f(t).cross(m);
        return m.mul((float) Math.cos(CHOP_TWIST)).add(n.mul((float) Math.sin(CHOP_TWIST)));
    }

    static Transformation chopTransform(float phiDeg, Vector3f fwd, float scale) {
        Vector3f tipDir = chopTipDir(phiDeg, fwd);
        Quaternionf rot = orient(tipDir, chopEdgeDir(phiDeg, fwd));
        Vector3f hand = new Vector3f(fwd).mul(CHOP_FWD).add(0, CHOP_UP, 0);
        Vector3f center = hand.add(new Vector3f(tipDir).mul((float) (HALF_LEN * scale)));
        return new Transformation(center, rot, new Vector3f(scale), new Quaternionf());
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
