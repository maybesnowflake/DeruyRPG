package com.fsixel.deruyrpg.util;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.util.Vector;

/** 바닐라 파티클·사운드 연출 도우미. */
public final class Fx {

    private Fx() {}

    public static void sound(Location at, Sound sound, float volume, float pitch) {
        at.getWorld().playSound(at, sound, SoundCategory.PLAYERS, volume, pitch);
    }

    public static void burst(Location at, Particle particle, int count, double spread) {
        at.getWorld().spawnParticle(particle, at, count, spread, spread, spread, 0.02);
    }

    /** 바닥 원형 링. */
    public static void ring(Location center, Particle particle, double radius, int points) {
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            Location l = center.clone().add(Math.cos(a) * radius, 0.1, Math.sin(a) * radius);
            center.getWorld().spawnParticle(particle, l, 1, 0, 0, 0, 0);
        }
    }

    /** from → to 직선. */
    public static void line(Location from, Location to, Particle particle, double step) {
        Vector dir = to.toVector().subtract(from.toVector());
        double len = dir.length();
        if (len < 1e-6) return;
        dir.normalize().multiply(step);
        Location cur = from.clone();
        for (double d = 0; d <= len; d += step) {
            from.getWorld().spawnParticle(particle, cur, 1, 0, 0, 0, 0);
            cur.add(dir);
        }
    }

    /** 구 껍데기 (돔 등). */
    public static void sphere(Location center, Particle particle, double radius, int points) {
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < points; i++) {
            double y = 1 - (i / (double) (points - 1)) * 2;
            double r = Math.sqrt(1 - y * y);
            double t = golden * i;
            Location l = center.clone().add(Math.cos(t) * r * radius, y * radius, Math.sin(t) * r * radius);
            center.getWorld().spawnParticle(particle, l, 1, 0, 0, 0, 0);
        }
    }
}
