package com.fsixel.deruyrpg.weapons.trident;

import com.fsixel.deruyrpg.util.Targets;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.function.Consumer;

/**
 * 삼지창 찌르기 연출 (창 SpearFx.miniThrust 와 같은 방식: ItemDisplay + Transformation 보간).
 *
 * 회전: FIXED 표시의 평면 아이템(item/trident 스프라이트, 끝이 오른쪽 위 대각선)을
 * Rz(-135°) → Rx(+90°) → Ry(-90°) 로 눕혀 끝이 +Z 를 향하게 한 뒤, pitch/yaw 로 목표 방향.
 */
final class TridentFx {

    private TridentFx() {}

    private static Quaternionf baseRotation() {
        return new Quaternionf()
                .rotateY((float) Math.toRadians(-90))
                .rotateX((float) Math.toRadians(90))
                .rotateZ((float) Math.toRadians(-135));
    }

    static Quaternionf pointing(Vector dir) {
        Vector d = dir.clone();
        if (d.lengthSquared() < 1e-9) d = new Vector(0, 0, 1);
        d.normalize();
        float yaw = (float) Math.atan2(d.getX(), d.getZ());
        float pitch = (float) -Math.asin(Math.max(-1, Math.min(1, d.getY())));
        return new Quaternionf().rotateY(yaw).rotateX(pitch).mul(baseRotation());
    }

    /**
     * from 에서 dir 방향으로 삼지창이 distance 만큼 빠르게 찌르고 사라짐 (약 6틱).
     * track/untrack 은 소환물 정리용 콜백.
     */
    static void thrust(Plugin plugin, Location from, Vector dir, double distance, float scale,
                       Consumer<ItemDisplay> track, Consumer<ItemDisplay> untrack) {
        Vector d = dir.clone().normalize();
        Location l = from.clone();
        l.setYaw(0);
        l.setPitch(0);
        Quaternionf rot = pointing(d);
        ItemDisplay disp = l.getWorld().spawn(l, ItemDisplay.class, e -> {
            e.setItemStack(new ItemStack(Material.TRIDENT));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setPersistent(false);
            e.setRotation(0, 0);
            e.setTeleportDuration(0);
            e.setTransformation(new Transformation(toV3(d.clone().multiply(-0.35)), rot,
                    new Vector3f(scale, scale, scale), new Quaternionf()));
            Targets.markSummon(e);
        });
        track.accept(disp);
        // 1틱 뒤: 앞으로 쭉 찌름
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!disp.isValid()) return;
            Transformation t = disp.getTransformation();
            disp.setInterpolationDelay(0);
            disp.setInterpolationDuration(2);
            disp.setTransformation(new Transformation(toV3(d.clone().multiply(distance)),
                    t.getLeftRotation(), t.getScale(), t.getRightRotation()));
        }, 1L);
        // 3틱 뒤: 살짝 당기며 사라짐
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!disp.isValid()) return;
            Transformation t = disp.getTransformation();
            disp.setInterpolationDelay(0);
            disp.setInterpolationDuration(3);
            disp.setTransformation(new Transformation(toV3(d.clone().multiply(distance * 0.7)),
                    t.getLeftRotation(), new Vector3f(0.01f, 0.01f, 0.01f), t.getRightRotation()));
        }, 3L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (disp.isValid()) disp.remove();
            untrack.accept(disp);
        }, 6L);
    }

    static Vector3f toV3(Vector v) {
        return new Vector3f((float) v.getX(), (float) v.getY(), (float) v.getZ());
    }
}
