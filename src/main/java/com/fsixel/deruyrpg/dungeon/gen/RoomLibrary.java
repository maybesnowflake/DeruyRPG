package com.fsixel.deruyrpg.dungeon.gen;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * 방 템플릿 풀. plugins/DeruyRPG/rooms/&lt;풀&gt;/&lt;이름&gt;.nbt (+ 같은 이름 .yml: tags, weight).
 * 풀 이름은 라운드의 pool (기본 = 라운드 type, 예: combat, elite, puzzle, shop, rest, event, miniboss, boss).
 * 방 크기는 셀(dungeon.yml room-cell) 안에 들어가야 함. 회전 없이 그대로 배치 (MVP).
 */
public final class RoomLibrary {

    public record Room(String pool, String name, Structure structure, BlockVector size, Set<String> tags, int weight) {}

    /** 방은 Y=64에 놓이므로 월드 상한(320) 안에 들어가도록 */
    public static final int MAX_HEIGHT = 250;

    private final File dir;
    private final Logger log;
    private final Map<String, List<Room>> pools = new HashMap<>();

    public RoomLibrary(File dir, Logger log) {
        this.dir = dir;
        this.log = log;
    }

    public void load(int cell) {
        pools.clear();
        if (!dir.exists()) dir.mkdirs();
        File[] poolDirs = dir.listFiles(File::isDirectory);
        if (poolDirs == null) return;
        int n = 0;
        for (File pd : poolDirs) {
            File[] files = pd.listFiles((d, name) -> name.endsWith(".nbt"));
            if (files == null) continue;
            for (File f : files) {
                try {
                    Structure s = Bukkit.getStructureManager().loadStructure(f);
                    BlockVector size = s.getSize();
                    if (size.getBlockY() > MAX_HEIGHT) {
                        log.warning("방 템플릿 " + pd.getName() + "/" + f.getName() + " 높이 " + size.getBlockY() + " > " + MAX_HEIGHT + " — 건너뜀");
                        continue;
                    }
                    if (size.getBlockX() > cell || size.getBlockZ() > cell) {
                        log.warning("방 템플릿 " + pd.getName() + "/" + f.getName() + " 이 셀(" + cell + ")보다 큼 — 건너뜀");
                        continue;
                    }
                    String name = f.getName().substring(0, f.getName().length() - 4);
                    File meta = new File(pd, name + ".yml");
                    Set<String> tags = new HashSet<>();
                    int weight = 1;
                    if (meta.exists()) {
                        YamlConfiguration y = YamlConfiguration.loadConfiguration(meta);
                        for (String t : y.getStringList("tags")) tags.add(t.toLowerCase(Locale.ROOT));
                        weight = Math.max(1, y.getInt("weight", 1));
                    }
                    pools.computeIfAbsent(pd.getName().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                            .add(new Room(pd.getName(), name, s, size, tags, weight));
                    n++;
                } catch (Exception e) {
                    log.warning("방 템플릿 읽기 실패 " + f + ": " + e);
                }
            }
        }
        log.info("던전 방 템플릿 " + n + "개 (" + pools.keySet() + ")");
    }

    public boolean has(String pool) {
        List<Room> l = pools.get(pool.toLowerCase(Locale.ROOT));
        return l != null && !l.isEmpty();
    }

    public Map<String, Integer> counts() {
        Map<String, Integer> out = new TreeMap<>();
        pools.forEach((k, v) -> out.put(k, v.size()));
        return out;
    }

    /**
     * 풀에서 하나 고름: 태그가 겹치는 방 우선(가중치 ×3), 이번 판에 이미 쓴 방은 다른 후보가 있으면 제외.
     */
    public @Nullable Room pick(String pool, Collection<String> wantTags, Set<String> usedThisRun) {
        List<Room> all = pools.get(pool.toLowerCase(Locale.ROOT));
        if (all == null || all.isEmpty()) return null;
        List<Room> cand = new ArrayList<>();
        for (Room r : all) if (!usedThisRun.contains(r.pool() + "/" + r.name())) cand.add(r);
        if (cand.isEmpty()) cand = all;
        int total = 0;
        int[] w = new int[cand.size()];
        for (int i = 0; i < cand.size(); i++) {
            Room r = cand.get(i);
            boolean match = false;
            for (String t : wantTags) if (r.tags().contains(t.toLowerCase(Locale.ROOT))) match = true;
            w[i] = r.weight() * (match ? 3 : 1);
            total += w[i];
        }
        int x = ThreadLocalRandom.current().nextInt(total);
        for (int i = 0; i < cand.size(); i++) {
            x -= w[i];
            if (x < 0) return cand.get(i);
        }
        return cand.getLast();
    }
}
