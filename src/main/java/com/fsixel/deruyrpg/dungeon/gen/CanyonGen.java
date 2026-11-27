package com.fsixel.deruyrpg.dungeon.gen;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.util.BlockVector;
import org.bukkit.util.noise.SimplexNoiseGenerator;

import java.util.*;

/**
 * 방 사이 협곡. 방 바닥판과 길을 "열린 곳"으로 보고, 열린 곳에서 거리 WALL 안쪽 기둥마다
 * 노이즈로 높이·지층을 섞은 절벽을 세움 (거리장 + 노이즈). 길 옆 절벽엔 덩굴, 도착 방 입구 앞엔 닫힌 문.
 * RoomPlacer.work 로 한 기둥씩 나눠 놓음 (렉 방지).
 */
public final class CanyonGen implements Iterator<Integer> {

    /** 방 바닥판 (월드 좌표, 양끝 포함) */
    public record Rect(int x1, int z1, int x2, int z2) {
        boolean has(int x, int z) {
            return x >= x1 && x <= x2 && z >= z1 && z <= z2;
        }

        double dist(double x, double z) {
            double dx = Math.max(Math.max(x1 - x, x - x2 - 1), 0), dz = Math.max(Math.max(z1 - z, z - z2 - 1), 0);
            return Math.hypot(dx, dz);
        }
    }

    /** 길: 꺾은선 점(x,z), 출발·도착 바닥 높이, 도착 라운드 id */
    public record Path(String to, int[][] pts, int y0, int y1) {}

    private static final Material[] STRATA = {Material.STONE, Material.ANDESITE, Material.TUFF, Material.MOSSY_COBBLESTONE,
            Material.STONE, Material.PACKED_MUD, Material.COBBLESTONE, Material.ANDESITE, Material.MOSSY_COBBLESTONE};
    private static final Material[] FLOOR = {Material.COARSE_DIRT, Material.MOSS_BLOCK, Material.GRASS_BLOCK,
            Material.ROOTED_DIRT, Material.GRASS_BLOCK, Material.MOSS_BLOCK, Material.MUD};
    private static final Material[] TOP_PLANT = {Material.SHORT_GRASS, Material.FERN, Material.AZALEA, Material.SHORT_GRASS};
    private static final int WALL = 9, HEIGHT = 18, BASE_DROP = 4;

    private final World w;
    private final List<Rect> rooms;
    private final List<Path> paths;
    private final SimplexNoiseGenerator noise;
    private final long seed;
    private final int floorY, x1, z1, x2, z2;
    private int x, z;
    /** 도착 라운드 id → 닫힌 문 블록 (열 때 공기로) */
    public final Map<String, List<Block>> gates = new HashMap<>();

    public CanyonGen(World w, List<Rect> rooms, List<Path> paths, int floorY, long seed) {
        this.w = w;
        this.rooms = rooms;
        this.paths = paths;
        this.floorY = floorY;
        this.seed = seed;
        this.noise = new SimplexNoiseGenerator(seed);
        int m = WALL + 4;
        x1 = rooms.stream().mapToInt(Rect::x1).min().orElse(0) - m;
        z1 = rooms.stream().mapToInt(Rect::z1).min().orElse(0) - m;
        x2 = rooms.stream().mapToInt(Rect::x2).max().orElse(0) + m;
        z2 = rooms.stream().mapToInt(Rect::z2).max().orElse(0) + m;
        x = x1;
        z = z1;
    }

    /** 판 끝 정리용 영역 (절벽 꼭대기까지). */
    public RoomPlacer.Placed box() {
        Location o = new Location(w, x1, floorY - BASE_DROP, z1);
        return new RoomPlacer.Placed("canyon", o, new BlockVector(x2 - x1 + 1, HEIGHT + 16 + BASE_DROP, z2 - z1 + 1));
    }

    @Override
    public boolean hasNext() {
        return z <= z2;
    }

    @Override
    public Integer next() {
        int cost = column(x, z);
        if (++x > x2) {
            x = x1;
            z++;
        }
        return cost;
    }

    // ------------------------------------------------------------------ 기둥 하나

    /** 길 위 = 바닥 높이, 절벽 = 꼭대기 높이, 열린 방/허공 = MIN_VALUE */
    private int classify(int x, int z, double[] info) {
        double cx = x + 0.5, cz = z + 0.5, dr = Double.MAX_VALUE;
        for (Rect r : rooms) {
            if (r.has(x, z)) return Integer.MIN_VALUE;
            dr = Math.min(dr, r.dist(cx, cz));
        }
        // 길 중심선을 저주파 노이즈로 휘게 (방 입구 근처는 똑바로 → 문과 맞물림)
        double warp = Math.clamp((dr - 4) / 12, 0, 1) * 7;
        double qx = cx + warp * noise.noise(x * 0.025, z * 0.025, 11), qz = cz + warp * noise.noise(x * 0.025, z * 0.025, 13);
        double dp = Double.MAX_VALUE, t = 0;
        Path best = null;
        for (Path p : paths) {
            double len = 0, total = 0;
            for (int i = 1; i < p.pts.length; i++) total += seg(p.pts[i - 1], p.pts[i]);
            for (int i = 1; i < p.pts.length; i++) {
                int[] a = p.pts[i - 1], b = p.pts[i];
                double l = seg(a, b), ax = a[0] + 0.5, az = a[1] + 0.5;
                double u = l == 0 ? 0 : Math.clamp(((qx - ax) * (b[0] - a[0]) + (qz - az) * (b[1] - a[1])) / (l * l), 0, 1);
                double d = Math.hypot(qx - ax - u * (b[0] - a[0]), qz - az - u * (b[1] - a[1]));
                if (d < dp) {
                    dp = d;
                    best = p;
                    t = total == 0 ? 0 : (len + u * l) / total;
                }
                len += l;
            }
        }
        // 폭: 잔물결 + 가끔 넓어지는 웅덩이
        double half = 2.8 + 1.0 * noise.noise(x * 0.09, z * 0.09) + 3.0 * Math.max(0, noise.noise(x * 0.02, z * 0.02, 17));
        info[0] = t;
        if (best != null && dp <= half) {
            info[1] = paths.indexOf(best);
            return (int) Math.round(best.y0 + (best.y1 - best.y0) * t);
        }
        info[1] = -1;
        double d = Math.min(dr, dp - half);
        if (d > WALL + 3 * noise.noise(x * 0.05 + 300, z * 0.05)) return Integer.MIN_VALUE;
        // 열린 곳에서 멀수록 높아지는 계단식 절벽 (3칸 단마다 풀 난 턱) + 능선 노이즈
        double cap = HEIGHT + 7 * noise.noise(x * 0.035, z * 0.035, 7);
        double h = Math.min(cap, 4 + Math.max(0, d) * 3.4 + 2 * noise.noise(x * 0.12, z * 0.12, 5));
        return floorY + (int) (Math.floor(h / 3) * 3);
    }

    private int column(int x, int z) {
        double[] info = new double[2];
        int c = classify(x, z, info);
        if (c == Integer.MIN_VALUE) return 1;
        if (info[1] >= 0) return path(x, z, c, paths.get((int) info[1]));
        int top = c, cost = 0;
        double shift = 3 * noise.noise(x * 0.03, z * 0.03 + 50);
        for (int y = floorY - BASE_DROP; y < top; y++) {
            int band = Math.floorMod((int) Math.floor((y + shift) / 2.0), STRATA.length);
            set(x, y, z, STRATA[band]);
            cost++;
        }
        set(x, top, z, Material.GRASS_BLOCK);
        if (rnd(x, z, 1) < 0.18) set(x, top + 1, z, TOP_PLANT[(int) (rnd(x, z, 2) * TOP_PLANT.length)]);
        return cost + 2;
    }

    private int path(int x, int z, int fy, Path p) {
        set(x, fy - 1, z, Material.DIRT);
        Material floor = FLOOR[(int) (rnd(x, z, 3) * FLOOR.length)];
        set(x, fy, z, floor);
        int cost = 2;
        double r = rnd(x, z, 4);
        if (r < 0.012) set(x, fy + 1, z, Material.MOSSY_COBBLESTONE);          // 굴러온 돌
        else if (r < 0.08 && floor != Material.MUD) set(x, fy + 1, z, r < 0.05 ? Material.SHORT_GRASS : Material.FERN);
        // 도착 방 입구 바로 앞 2줄 = 문 (라운드 클리어·투표 뒤 열림)
        int[] end = p.pts[p.pts.length - 1];
        if (z >= end[1] - 2 && z < end[1] && Math.abs(x - end[0]) <= 6) {
            List<Block> g = gates.computeIfAbsent(p.to, k -> new ArrayList<>());
            for (int y = fy + 1; y <= fy + 4; y++) {
                Block b = w.getBlockAt(x, y, z);
                b.setType(y == fy + 4 ? Material.MOSS_BLOCK : Material.MOSSY_COBBLESTONE, false);
                g.add(b);
                cost++;
            }
        }
        // 옆 절벽에 늘어진 덩굴
        double[] tmp = new double[2];
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            if (rnd(x, z, 10 + f.ordinal()) > 0.3) continue;
            int top = classify(x + f.getModX(), z + f.getModZ(), tmp);
            if (top == Integer.MIN_VALUE || tmp[1] >= 0) continue;
            int len = 3 + (int) (rnd(x, z, 20 + f.ordinal()) * 9);
            for (int y = top - 1; y > Math.max(fy + 2, top - 1 - len); y--) {
                Block b = w.getBlockAt(x, y, z);
                if (!b.getType().isAir() && b.getType() != Material.VINE) break;
                MultipleFacing v = b.getType() == Material.VINE ? (MultipleFacing) b.getBlockData()
                        : (MultipleFacing) Material.VINE.createBlockData();
                v.setFace(f, true);
                b.setBlockData(v, false);
                cost++;
            }
        }
        return cost;
    }

    private void set(int x, int y, int z, Material m) {
        w.getBlockAt(x, y, z).setType(m, false);
    }

    private static double seg(int[] a, int[] b) {
        return Math.hypot(b[0] - a[0], b[1] - a[1]);
    }

    /** 좌표별 고정 난수 [0,1) */
    private double rnd(int x, int z, int salt) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ (salt * 0x165667B19E3779F9L);
        h = (h ^ (h >>> 33)) * 0xff51afd7ed558ccdL;
        h = (h ^ (h >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return ((h ^ (h >>> 33)) >>> 11) * 0x1.0p-53;
    }
}
