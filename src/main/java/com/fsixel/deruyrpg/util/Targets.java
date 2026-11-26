package com.fsixel.deruyrpg.util;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 대상 찾기.
 * 아군 = 같은 스코어보드 팀(/team). 팀이 없으면 자기 자신만 아군.
 * 스킬 연출용 소환물은 Targets.markSummon() 으로 표시하면 대상에서 빠짐.
 */
public final class Targets {

    public static final NamespacedKey SUMMON_KEY = new NamespacedKey("deruyrpg", "summon");

    private Targets() {}

    public static void markSummon(Entity e) {
        e.getPersistentDataContainer().set(SUMMON_KEY, PersistentDataType.BYTE, (byte) 1);
    }

    public static boolean isSummon(Entity e) {
        return e.getPersistentDataContainer().has(SUMMON_KEY, PersistentDataType.BYTE);
    }

    public static boolean isAlly(Player self, Entity other) {
        if (other.equals(self)) return true;
        if (!(other instanceof Player op)) return false;
        Team team = self.getScoreboard().getEntryTeam(self.getName());
        return team != null && team.hasEntry(op.getName());
    }

    /** 공격 가능한 대상인지 (자신·아군·소환물·관전자·크리에이티브·갑옷거치대 제외). */
    public static boolean isEnemy(Player self, Entity e) {
        if (!(e instanceof LivingEntity le) || le.isDead()) return false;
        if (e instanceof ArmorStand || isSummon(e)) return false;
        if (isAlly(self, e)) return false;
        if (e instanceof Player p && (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE)) {
            return false;
        }
        return true;
    }

    /** 반경 안의 적. */
    public static List<LivingEntity> enemiesAround(Player self, Location center, double radius) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (isEnemy(self, e) && e.getLocation().distanceSquared(center) <= radius * radius) {
                out.add((LivingEntity) e);
            }
        }
        return out;
    }

    /** 반경 안의 아군 (자신 포함). */
    public static List<Player> alliesAround(Player self, Location center, double radius) {
        List<Player> out = new ArrayList<>();
        for (Player p : center.getWorld().getPlayers()) {
            if (isAlly(self, p) && p.getLocation().distanceSquared(center) <= radius * radius) out.add(p);
        }
        return out;
    }

    /**
     * 플레이어 앞 직사각형 범위의 적. (가로 width × 앞으로 length 칸, 높이 height)
     * 예: 창 3×5 → frontBox(p, 3, 5, 2)
     */
    public static List<LivingEntity> frontBox(Player self, double width, double length, double height) {
        Location eye = self.getLocation();
        Vector forward = eye.getDirection().setY(0);
        if (forward.lengthSquared() < 1e-6) forward = new Vector(0, 0, 1);
        forward.normalize();
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());

        List<LivingEntity> out = new ArrayList<>();
        double r = Math.max(width, length) + 1;
        for (Entity e : self.getWorld().getNearbyEntities(eye, r, height + 1, r)) {
            if (!isEnemy(self, e)) continue;
            Vector rel = e.getLocation().toVector().subtract(eye.toVector());
            double f = rel.dot(forward);
            double s = rel.dot(right);
            double y = rel.getY();
            if (f >= 0 && f <= length && Math.abs(s) <= width / 2.0 && y >= -1 && y <= height) {
                out.add((LivingEntity) e);
            }
        }
        return out;
    }

    /** 시선 방향 첫 번째 적 (블록에 막힘). */
    public static @Nullable LivingEntity lookTarget(Player self, double range) {
        RayTraceResult r = self.getWorld().rayTrace(self.getEyeLocation(), self.getEyeLocation().getDirection(),
                range, org.bukkit.FluidCollisionMode.NEVER, true, 0.4, e -> isEnemy(self, e));
        return r != null && r.getHitEntity() instanceof LivingEntity le ? le : null;
    }

    /** 시선 방향 첫 번째 아군 플레이어. */
    public static @Nullable Player lookAlly(Player self, double range) {
        RayTraceResult r = self.getWorld().rayTrace(self.getEyeLocation(), self.getEyeLocation().getDirection(),
                range, org.bukkit.FluidCollisionMode.NEVER, true, 0.4,
                e -> e instanceof Player p && !p.equals(self) && isAlly(self, p));
        return r != null && r.getHitEntity() instanceof Player p ? p : null;
    }

    /** 대상을 center 쪽으로 당김 (strength ≈ 0.3~1.5). */
    public static void pull(LivingEntity target, Location center, double strength) {
        Vector v = center.toVector().subtract(target.getLocation().toVector());
        if (v.lengthSquared() < 1e-6) return;
        target.setVelocity(v.normalize().multiply(strength).setY(0.25));
    }

    /** 대상을 from 에서 밀어냄. */
    public static void push(LivingEntity target, Location from, double strength, double up) {
        Vector v = target.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (v.lengthSquared() < 1e-6) v = new Vector(0, 0, 0.01);
        target.setVelocity(v.normalize().multiply(strength).setY(up));
    }
}
