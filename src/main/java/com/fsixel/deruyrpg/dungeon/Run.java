package com.fsixel.deruyrpg.dungeon;

import com.fsixel.deruyrpg.boss.moss.MossWarden;
import org.bukkit.GameMode;
import org.bukkit.entity.LivingEntity;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/** 파티 한 팀의 던전 진행 상태. 규칙은 docs/JUNGLE_MAP.md "목숨 규칙". */
final class Run {
    enum Phase { GENERATING, ROUND, VOTE, MOVING, RETRY, ENDED }

    final int id;
    final List<UUID> members = new ArrayList<>();
    final Map<UUID, Integer> lives = new HashMap<>();
    final Map<UUID, GameMode> originalMode = new HashMap<>();
    /** 쓰러져 관전 중인 사람 (목숨 남았으면 다음 라운드 부활, 0이면 스테이지 보상·다음 챕터까지 대기) */
    final Set<UUID> down = new HashSet<>();
    /** 보스 라운드 10초 재생성 대기 */
    final Map<UUID, BukkitTask> respawnTasks = new HashMap<>();

    ChapterDef chapter;
    RoundDef round;
    int stage;
    Phase phase = Phase.ROUND;
    /** 라운드 시작 시 고정되는 인원 (몹 체력 보정) */
    int scaleCount = 1;

    int wave;
    final Set<UUID> mobs = new HashSet<>();
    @Nullable MossWarden boss;
    boolean triggerSeen;

    final Map<UUID, String> votes = new HashMap<>();
    long voteEndsAt;
    /** 이미 클리어해 보상을 받은 라운드 (재도전 시 재지급 X) */
    final Set<String> rewarded = new HashSet<>();

    /** 이번 판에 배치된 방으로 좌표가 채워진 라운드 (템플릿 조립) */
    final Map<String, RoundDef> resolved = new LinkedHashMap<>();
    final List<com.fsixel.deruyrpg.dungeon.gen.RoomPlacer.Placed> placed = new ArrayList<>();
    final Set<String> usedRooms = new HashSet<>();
    /** 라운드 id → 배치된 방 (걸어 들어왔는지 판정) */
    final Map<String, com.fsixel.deruyrpg.dungeon.gen.RoomPlacer.Placed> rooms = new HashMap<>();
    /** 협곡 길로 이어진 라운드 쌍 "A>B" 와 B 입구 문 */
    final Set<String> walk = new HashSet<>();
    final Map<String, List<org.bukkit.block.Block>> gates = new HashMap<>();
    /** 걸어서 가는 중인 다음 라운드 (MOVING) */
    @Nullable String moveTarget;
    long moveDeadline;
    boolean walkedIn;
    int slot = -1;

    // ---- 콘텐츠 (DungeonContent)
    /** 상인·정령·탐험가 NPC */
    final List<UUID> npcs = new ArrayList<>();
    @Nullable DungeonContent.EventState event;
    /** 특별 이벤트로 바뀐 전투 라운드 (재도전 때도 같게), 이벤트가 나온 스테이지 */
    final Map<String, Boolean> eventRounds = new HashMap<>();
    final Set<Integer> eventStages = new HashSet<>();
    /** 플레이어 → 유물 (1인 1개), 무기 id → 개조 */
    final Map<UUID, String> relic = new HashMap<>();
    final Map<UUID, Map<String, String>> mods = new HashMap<>();
    final Set<String> restChosen = new HashSet<>();
    final Map<UUID, Long> relicCd = new HashMap<>();
    final Map<UUID, org.bukkit.Location> lastPos = new HashMap<>();
    final Map<UUID, Integer> stillTicks = new HashMap<>();

    RoundDef def(String id) {
        RoundDef r = resolved.get(id);
        return r != null ? r : chapter.round(id);
    }

    Run(int id) {
        this.id = id;
    }

    int lives(UUID u) {
        return lives.getOrDefault(u, 0);
    }

    boolean alive(UUID u) {
        return members.contains(u) && !down.contains(u);
    }

    List<UUID> aliveMembers() {
        List<UUID> out = new ArrayList<>();
        for (UUID u : members) if (!down.contains(u)) out.add(u);
        return out;
    }

    boolean anyLivesLeft() {
        for (UUID u : members) if (lives(u) > 0) return true;
        return false;
    }

    void cancelRespawns() {
        for (BukkitTask t : respawnTasks.values()) t.cancel();
        respawnTasks.clear();
    }

    static boolean dead(@Nullable LivingEntity e) {
        return e == null || !e.isValid() || e.isDead();
    }
}
