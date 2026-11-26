package com.fsixel.deruyrpg.weapons.spear;

import com.fsixel.deruyrpg.util.Targets;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 창 ItemDisplay 연출 도우미.
 *
 * 회전 계산 (엔티티 자체 회전은 0,0 으로 두고 Transformation 회전만으로 월드 방향을 맞춤):
 *  - FIXED 표시에서 평면 아이템 모델(item/generated)은 Y축 180° 뒤집혀 그려지므로
 *    텍스처상 오른쪽 위(+x,+y) 대각선인 창끝이 모델 공간에서는 (-1, +1, 0) 방향, 면 법선은 Z.
 *  - Rz(-135°): 창끝 (-1,1,0) → (+1,0,0)
 *  - Rx(+90°) : 면 법선 Z → -Y  (아이템 면이 바닥과 수평, 창끝은 +X 그대로)
 *  - Ry(-90°) : 창끝 +X → +Z
 *  - 그 다음 Rx(pitch) 로 위아래 기울기, Ry(yaw) 로 +Z → 목표 방향.
 */
final class SpearFx {

    private SpearFx() {}

    /** 평면 창을 눕혀 창끝이 +Z 를 향하게 하는 기본 회전. */
    private static Quaternionf baseRotation() {
        return new Quaternionf()
                .rotateY((float) Math.toRadians(-90))
                .rotateX((float) Math.toRadians(90))
                .rotateZ((float) Math.toRadians(-135));
    }

    /** 창끝이 dir(월드 방향)을 향하고, 아이템 면이 수평(위에서 보임)인 회전. */
    static Quaternionf pointing(Vector dir) {
        Vector d = dir.clone();
        if (d.lengthSquared() < 1e-9) d = new Vector(0, 0, 1);
        d.normalize();
        float yaw = (float) Math.atan2(d.getX(), d.getZ());           // Ry(yaw): +Z → (sin, 0, cos)
        float pitch = (float) -Math.asin(Math.max(-1, Math.min(1, d.getY()))); // Rx(p): +Z → (0, -sin p, cos p)
        return new Quaternionf().rotateY(yaw).rotateX(pitch).mul(baseRotation());
    }

    /** 창 모양 ItemDisplay 를 at 에 생성 (엔티티 회전 0, Transformation 으로 방향 지정). */
    static ItemDisplay spawn(Location at, Vector dir, float scale, Vector3f translation) {
        Location l = at.clone();
        l.setYaw(0);
        l.setPitch(0);
        Quaternionf rot = pointing(dir);
        return l.getWorld().spawn(l, ItemDisplay.class, e -> {
            e.setItemStack(new ItemStack(Material.NETHERITE_SPEAR));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setPersistent(false);
            e.setRotation(0, 0);
            e.setTransformation(new Transformation(translation, rot,
                    new Vector3f(scale, scale, scale), new Quaternionf()));
            Targets.markSummon(e);
        });
    }

    /**
     * 작은 창이 from 에서 dir 방향으로 distance 만큼 빠르게 찌르고 사라지는 연출.
     * delay 틱 후 출발 (여러 개를 엇갈리게 찌를 때).
     */
    static void miniThrust(SpearKit kit, Player owner, Plugin plugin, Location from, Vector dir,
                           double distance, float scale, long delay) {
        Runnable run = () -> {
            if (!owner.isOnline()) return;
            Vector d = dir.clone().normalize();
            // 시작: 살짝 뒤로 당겨진 상태
            Vector3f back = toV3(d.clone().multiply(-0.3));
            ItemDisplay disp = spawn(from, d, scale, back);
            kit.track(owner, disp);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!disp.isValid()) return;
                Transformation t = disp.getTransformation();
                disp.setInterpolationDelay(0);
                disp.setInterpolationDuration(2);
                disp.setTransformation(new Transformation(toV3(d.clone().multiply(distance)),
                        t.getLeftRotation(), t.getScale(), t.getRightRotation()));
            }, 1L);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!disp.isValid()) return;
                Transformation t = disp.getTransformation();
                disp.setInterpolationDelay(0);
                disp.setInterpolationDuration(2);
                disp.setTransformation(new Transformation(t.getTranslation(),
                        t.getLeftRotation(), new Vector3f(0.01f, 0.01f, 0.01f), t.getRightRotation()));
            }, 3L);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (disp.isValid()) disp.remove();
                kit.untrack(owner, disp);
            }, 5L);
        };
        if (delay <= 0) run.run();
        else plugin.getServer().getScheduler().runTaskLater(plugin, run, delay);
    }

    static Vector3f toV3(Vector v) {
        return new Vector3f((float) v.getX(), (float) v.getY(), (float) v.getZ());
    }
}
