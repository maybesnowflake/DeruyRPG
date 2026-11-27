package com.fsixel.deruyrpg.dungeon.gen;

import org.bukkit.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 조립 던전 전용 빈(void) 월드. 서버 시작 때 이전 판 잔여물을 통째로 지우고 새로 만듦 (재시작 후 구역 회수).
 * 파티마다 x 방향 4096칸씩 떨어진 구역(slot)을 씀.
 */
public final class DungeonWorld implements Listener {

    public static final String NAME = "deruyrpg_dungeon";

    private static final class Void extends ChunkGenerator {
        // 기본 구현: 지형·동굴·구조물·몹 생성 안 함 → 빈 월드
    }

    private final World world;

    public DungeonWorld(Plugin plugin) {
        World old = Bukkit.getWorld(NAME);
        if (old != null) {
            for (var p : old.getPlayers()) p.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation());
            Bukkit.unloadWorld(old, false);
        }
        File dir = new File(Bukkit.getWorldContainer(), NAME);
        if (dir.exists()) {
            try (Stream<Path> walk = Files.walk(dir.toPath())) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException e) {
                plugin.getLogger().warning("던전 월드 초기화 실패: " + e);
            }
        }
        world = new WorldCreator(NAME).generator(new Void()).environment(World.Environment.NORMAL)
                .generateStructures(false).createWorld();
        if (world != null) {
            world.setTime(6000);
            world.setStorm(false);
            world.setThundering(false);
            world.setAutoSave(false);
            world.setSpawnLocation(0, 80, 0);
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public World world() {
        return world;
    }

    /** 자연 스폰·밤낮 영향 차단: 던전 몹은 플러그인이 직접 소환. */
    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!e.getLocation().getWorld().equals(world)) return;
        CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
        if (r == CreatureSpawnEvent.SpawnReason.NATURAL || r == CreatureSpawnEvent.SpawnReason.PATROL
                || r == CreatureSpawnEvent.SpawnReason.TRAP || r == CreatureSpawnEvent.SpawnReason.VILLAGE_INVASION) {
            e.setCancelled(true);
        }
    }
}
