package com.fsixel.deruyrpg.dungeon.gen;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.structure.Palette;
import org.bukkit.util.BlockVector;

import java.util.*;

/**
 * 방 배치·정리를 틱당 예산으로 나눠 처리 (대형 구조물 동기 배치 렉 방지).
 * 마커 표지판(첫 줄 [spawn] [mob] [trigger] [boss])은 배치하지 않고 좌표만 기록.
 * [trigger] 자리는 레드스톤 램프로 바뀜 → 퍼즐 회로 출력을 그 자리에 연결하면 클리어.
 */
public final class RoomPlacer {

    public static final int BUDGET = 6000; // 틱당 블록 수

    /** 배치된 방 하나의 결과 (월드 절대 좌표). */
    public static final class Placed {
        public final String room;
        public final Location origin;
        public final BlockVector size;
        public Location spawn;
        public Location boss;
        public Location trigger;
        /** 출구 [exit] (남쪽 가장자리 통로) — 협곡 길 출발점 */
        public Location exit;
        public final List<Location> mobs = new ArrayList<>();

        Placed(String room, Location origin, BlockVector size) {
            this.room = room;
            this.origin = origin;
            this.size = size;
        }
    }

    private interface Job {
        /** @return 남은 예산 */
        int run(int budget);

        boolean done();
    }

    private final Deque<Job> jobs = new ArrayDeque<>();
    private final BukkitTask task;

    public RoomPlacer(Plugin plugin) {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    private void tick() {
        int budget = BUDGET;
        while (budget > 0 && !jobs.isEmpty()) {
            Job j = jobs.peekFirst();
            budget = j.run(budget);
            if (j.done()) jobs.pollFirst();
        }
    }

    public void shutdown() {
        task.cancel();
        // 남은 작업은 버림 (던전 월드는 다음 시작 때 통째로 초기화)
        jobs.clear();
    }

    /** 방 배치 예약. 다 놓이면 done 실행. */
    public Placed place(RoomLibrary.Room room, Location origin, Runnable done) {
        Placed out = new Placed(room.pool() + "/" + room.name(), origin.clone(), room.size());
        List<Palette> pals = room.structure().getPalettes();
        List<BlockState> blocks = pals.isEmpty() ? List.of() : pals.getFirst().getBlocks();
        World w = origin.getWorld();
        jobs.add(new Job() {
            int i;
            boolean finished;

            @Override
            public int run(int budget) {
                while (budget > 0 && i < blocks.size()) {
                    BlockState st = blocks.get(i++);
                    budget--;
                    Location at = new Location(w, origin.getBlockX() + st.getX(), origin.getBlockY() + st.getY(), origin.getBlockZ() + st.getZ());
                    if (st instanceof Sign s) {
                        String tag = PlainTextComponentSerializer.plainText().serialize(s.getSide(Side.FRONT).line(0)).trim().toLowerCase(Locale.ROOT);
                        Location c = at.clone().add(0.5, 0, 0.5);
                        boolean marker = true;
                        switch (tag) {
                            case "[spawn]" -> out.spawn = c;
                            case "[boss]" -> out.boss = c;
                            case "[mob]" -> out.mobs.add(c);
                            case "[trigger]" -> out.trigger = at.clone();
                            case "[exit]" -> out.exit = c;
                            default -> marker = false;
                        }
                        if (marker) {
                            at.getBlock().setType(tag.equals("[trigger]") ? Material.REDSTONE_LAMP : Material.AIR, false);
                            continue;
                        }
                    }
                    st.copy(at).update(true, false);
                }
                if (i >= blocks.size() && !finished) {
                    finished = true;
                    done.run();
                }
                return budget;
            }

            @Override
            public boolean done() {
                return finished;
            }
        });
        return out;
    }

    /** 임의 작업을 틱 예산으로 나눠 실행. steps.next() = 이번 걸음이 쓴 블록 수. */
    public void work(Iterator<Integer> steps, Runnable done) {
        jobs.add(new Job() {
            boolean finished;

            @Override
            public int run(int budget) {
                while (budget > 0 && steps.hasNext()) budget -= Math.max(1, steps.next());
                if (!steps.hasNext() && !finished) {
                    finished = true;
                    done.run();
                }
                return Math.max(0, budget);
            }

            @Override
            public boolean done() {
                return finished;
            }
        });
    }

    /** 영역을 공기로 비움 (판 종료 시). */
    public void clear(Location origin, BlockVector size, Runnable done) {
        World w = origin.getWorld();
        int sx = size.getBlockX(), sy = size.getBlockY(), sz = size.getBlockZ();
        long total = (long) sx * sy * sz;
        jobs.add(new Job() {
            long i;
            boolean finished;

            @Override
            public int run(int budget) {
                while (budget > 0 && i < total) {
                    int x = (int) (i % sx), z = (int) ((i / sx) % sz), y = (int) (i / ((long) sx * sz));
                    i++;
                    budget--;
                    var b = w.getBlockAt(origin.getBlockX() + x, origin.getBlockY() + y, origin.getBlockZ() + z);
                    if (!b.getType().isAir()) b.setType(Material.AIR, false);
                }
                if (i >= total && !finished) {
                    finished = true;
                    done.run();
                }
                return budget;
            }

            @Override
            public boolean done() {
                return finished;
            }
        });
    }
}
