package com.fsixel.deruyrpg.dungeon;

import com.fsixel.deruyrpg.DeruyRPG;
import com.fsixel.deruyrpg.boss.moss.MossWarden;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.Powerable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 던전 진행: 챕터 → 스테이지 → 라운드(갈림길), 공유 목숨, 부활·재도전·귀환.
 * 설정은 plugins/DeruyRPG/dungeon.yml, 명령어는 /dg (관리 명령은 deruyrpg.admin).
 */
public final class DungeonManager implements Listener, CommandExecutor, TabCompleter {

    private static final int TICK = 10; // 0.5초마다 진행 검사
    private static final int VOTE_SECONDS = 20;
    private static final double SCALE_PER_PLAYER = 0.15;

    private final DeruyRPG plugin;
    private final NamespacedKey mobKey;
    private final File file;
    private YamlConfiguration yml;

    private int maxLives = 3, buyMaxLives = 4, bossRespawnSeconds = 10;
    private @Nullable Location returnLoc;
    private final Map<String, ChapterDef> chapters = new LinkedHashMap<>();

    private final Map<Integer, Run> runs = new HashMap<>();
    private final Map<UUID, Run> byPlayer = new HashMap<>();
    private int nextRunId = 1;
    private final BukkitTask task;

    public DungeonManager(DeruyRPG plugin) {
        this.plugin = plugin;
        this.mobKey = new NamespacedKey(plugin, "dungeon_run");
        this.file = new File(plugin.getDataFolder(), "dungeon.yml");
        if (!file.exists()) plugin.saveResource("dungeon.yml", false);
        load();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK, TICK);
    }

    // ================================================================== 설정

    public void load() {
        yml = YamlConfiguration.loadConfiguration(file);
        maxLives = Math.max(1, yml.getInt("max-lives", 3));
        buyMaxLives = Math.max(maxLives, yml.getInt("buy-max-lives", 4));
        bossRespawnSeconds = Math.max(1, yml.getInt("boss-respawn-seconds", 10));
        returnLoc = yml.getLocation("return");
        chapters.clear();
        ConfigurationSection cs = yml.getConfigurationSection("chapters");
        if (cs == null) return;
        for (String cid : cs.getKeys(false)) {
            ConfigurationSection c = cs.getConfigurationSection(cid);
            if (c == null) continue;
            Map<Integer, Integer> rewards = new HashMap<>();
            ConfigurationSection rw = c.getConfigurationSection("stage-rewards");
            if (rw != null) for (String k : rw.getKeys(false)) {
                try {
                    rewards.put(Integer.parseInt(k), rw.getInt(k));
                } catch (NumberFormatException ignored) {
                }
            }
            Map<String, RoundDef> rounds = new LinkedHashMap<>();
            ConfigurationSection rs = c.getConfigurationSection("rounds");
            if (rs != null) for (String rid : rs.getKeys(false)) {
                ConfigurationSection r = rs.getConfigurationSection(rid);
                if (r == null) continue;
                RoundType type;
                try {
                    type = RoundType.valueOf(r.getString("type", "combat").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("dungeon.yml " + cid + "." + rid + ": 알 수 없는 type");
                    continue;
                }
                List<List<String>> waves = new ArrayList<>();
                for (Object w : r.getList("waves", List.of())) {
                    if (w instanceof List<?> l) {
                        List<String> wave = new ArrayList<>();
                        for (Object o : l) wave.add(String.valueOf(o));
                        waves.add(wave);
                    }
                }
                List<Location> points = new ArrayList<>();
                for (Object o : r.getList("points", List.of())) if (o instanceof Location l) points.add(l);
                rounds.put(rid, new RoundDef(rid, r.getInt("stage", 1), type, r.getString("name", rid),
                        r.getStringList("next"), waves, r.getLocation("spawn"), points,
                        r.getLocation("trigger"), r.getLocation("boss-spawn")));
            }
            String next = c.getString("next-chapter", "");
            chapters.put(cid, new ChapterDef(cid, c.getString("name", cid), c.getString("start", ""),
                    next == null || next.isBlank() ? null : next, rewards, rounds));
        }
    }

    private void saveYml() {
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("dungeon.yml 저장 실패: " + e);
        }
        load();
    }

    /** 위치가 비어 있는 라운드 목록 (시작 전 점검). */
    private List<String> missing(ChapterDef c) {
        List<String> out = new ArrayList<>();
        if (c.round(c.start()) == null) out.add("start(" + c.start() + ") 없음");
        for (RoundDef r : c.rounds().values()) {
            if (r.spawn() == null) out.add(r.id() + ".spawn");
            for (String n : r.next()) if (c.round(n) == null) out.add(r.id() + ".next→" + n + " 없음");
        }
        return out;
    }

    // ================================================================== 시작·종료

    public @Nullable String start(String chapterId, Collection<Player> players) {
        ChapterDef c = chapters.get(chapterId);
        if (c == null) return "챕터 없음: " + chapterId;
        List<String> miss = missing(c);
        if (!miss.isEmpty()) return "설정 안 된 항목: " + String.join(", ", miss.subList(0, Math.min(8, miss.size())));
        for (Player p : players) if (byPlayer.containsKey(p.getUniqueId())) return p.getName() + "은(는) 이미 던전 중";
        if (players.isEmpty()) return "참가자 없음";
        Run run = new Run(nextRunId++);
        for (Player p : players) {
            UUID u = p.getUniqueId();
            run.members.add(u);
            run.lives.put(u, maxLives);
            run.originalMode.put(u, p.getGameMode());
            byPlayer.put(u, run);
        }
        runs.put(run.id, run);
        run.chapter = c;
        run.stage = c.round(c.start()).stage();
        broadcast(run, Component.text("[던전] " + c.name() + " 챕터 시작 — 목숨 " + maxLives + "개", NamedTextColor.GOLD));
        enterRound(run, c.start());
        return null;
    }

    private void end(Run run, boolean cleared) {
        if (run.phase == Run.Phase.ENDED) return;
        run.phase = Run.Phase.ENDED;
        run.cancelRespawns();
        clearMobs(run);
        removeBoss(run);
        Location back = returnLoc != null ? returnLoc : Bukkit.getWorlds().getFirst().getSpawnLocation();
        for (UUID u : run.members) {
            byPlayer.remove(u);
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            restore(run, p);
            p.teleport(back);
            p.showTitle(Title.title(
                    Component.text(cleared ? "챕터 클리어!" : "던전 실패", cleared ? NamedTextColor.GOLD : NamedTextColor.RED),
                    Component.text("기본 월드로 돌아갑니다", NamedTextColor.GRAY)));
        }
        runs.remove(run.id);
    }

    public void stopAll() {
        for (Run r : new ArrayList<>(runs.values())) end(r, false);
        task.cancel();
    }

    // ================================================================== 라운드

    private void enterRound(Run run, String roundId) {
        RoundDef r = run.chapter.round(roundId);
        if (r == null) {
            end(run, false);
            return;
        }
        // 스테이지 경계: 이전 스테이지 클리어 보상 (목숨 +, 최대치 넘지 않음)
        if (r.stage() > run.stage) {
            int reward = run.chapter.stageRewards().getOrDefault(run.stage, 0);
            if (reward > 0) {
                for (UUID u : run.members) {
                    int before = run.lives(u);
                    if (before < maxLives) run.lives.put(u, Math.min(maxLives, before + reward));
                }
                broadcast(run, Component.text("스테이지 " + run.stage + " 클리어 — 목숨 +" + reward + " (최대 " + maxLives + ")", NamedTextColor.GREEN));
            }
            run.stage = r.stage();
        }
        run.round = r;
        run.phase = Run.Phase.ROUND;
        run.wave = 0;
        run.mobs.clear();
        run.votes.clear();
        run.triggerSeen = false;
        run.cancelRespawns();
        // 목숨 남은 쓰러진 사람은 다음 라운드 시작 때 부활
        for (UUID u : new ArrayList<>(run.down)) {
            if (run.lives(u) > 0) revive(run, u, false);
        }
        run.scaleCount = Math.max(1, run.aliveMembers().size());
        for (UUID u : run.members) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            p.teleport(r.spawn());
            p.showTitle(Title.title(Component.text(r.name(), NamedTextColor.GREEN),
                    Component.text("스테이지 " + r.stage() + " · " + r.type().label, NamedTextColor.GRAY)));
        }
        statusLine(run);
        switch (r.type()) {
            case COMBAT, ELITE -> spawnWave(run);
            case MINIBOSS, BOSS -> {
                Location at = r.bossSpawn() != null ? r.bossSpawn() : r.spawn();
                run.boss = plugin.bosses().spawn(at, false, r.type() == RoundType.MINIBOSS);
            }
            case REST -> {
                for (UUID u : run.aliveMembers()) {
                    Player p = Bukkit.getPlayer(u);
                    if (p != null) heal(p);
                }
                hint(run, "휴식 — 체력 회복. 준비되면 /dg next");
            }
            case SHOP -> hint(run, "상점 (거래 창 준비 중) — 준비되면 /dg next");
            case PUZZLE, EVENT -> hint(run, r.trigger() != null ? "장치를 작동시키세요" : "완료하면 /dg next");
        }
    }

    private void hint(Run run, String msg) {
        broadcast(run, Component.text(msg, NamedTextColor.YELLOW));
    }

    private void spawnWave(Run run) {
        RoundDef r = run.round;
        if (run.wave >= r.waves().size()) {
            clearRound(run);
            return;
        }
        double scale = 1 + SCALE_PER_PLAYER * (run.scaleCount - 1);
        for (String entry : r.waves().get(run.wave)) {
            String[] parts = entry.split(":");
            EntityType type = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(parts[0].trim().toLowerCase(Locale.ROOT)));
            int count = 1;
            if (parts.length > 1) {
                try {
                    count = Math.max(1, Integer.parseInt(parts[1].trim()));
                } catch (NumberFormatException ignored) {
                }
            }
            if (type == null || !type.isAlive() || !type.isSpawnable()) {
                plugin.getLogger().warning("던전 몹 종류 오류: " + entry + " (" + run.chapter.id() + "." + r.id() + ")");
                continue;
            }
            for (int i = 0; i < count; i++) {
                Location at = spawnPoint(r);
                Entity en = at.getWorld().spawnEntity(at, type);
                if (!(en instanceof LivingEntity le)) {
                    en.remove();
                    continue;
                }
                le.getPersistentDataContainer().set(mobKey, PersistentDataType.INTEGER, run.id);
                le.setRemoveWhenFarAway(false);
                AttributeInstance hp = le.getAttribute(Attribute.MAX_HEALTH);
                if (hp != null && scale > 1) {
                    hp.setBaseValue(hp.getBaseValue() * scale);
                    le.setHealth(hp.getValue());
                }
                if (le instanceof Mob mob) {
                    Player target = nearestAlive(run, at);
                    if (target != null) mob.setTarget(target);
                }
                run.mobs.add(le.getUniqueId());
            }
        }
        if (r.waves().size() > 1) hint(run, "웨이브 " + (run.wave + 1) + "/" + r.waves().size());
        if (run.mobs.isEmpty()) { // 전부 소환 실패 → 다음 웨이브로
            run.wave++;
            spawnWave(run);
        }
    }

    private Location spawnPoint(RoundDef r) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (!r.points().isEmpty()) return r.points().get(rnd.nextInt(r.points().size())).clone().add(rnd.nextDouble(-0.5, 0.5), 0, rnd.nextDouble(-0.5, 0.5));
        Location base = r.spawn().clone();
        double a = rnd.nextDouble(Math.PI * 2), d = rnd.nextDouble(3, 6);
        Location l = base.add(Math.cos(a) * d, 0, Math.sin(a) * d);
        l.setY(l.getWorld().getHighestBlockYAt(l.getBlockX(), l.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1);
        if (Math.abs(l.getY() - r.spawn().getY()) > 4) return r.spawn().clone(); // 지붕·구덩이 위로 튀지 않게
        return l;
    }

    private void clearRound(Run run) {
        if (run.phase != Run.Phase.ROUND) return;
        run.rewarded.add(run.round.id());
        run.boss = null;
        RoundDef r = run.round;
        if (r.last()) {
            String next = run.chapter.nextChapter();
            ChapterDef nc = next != null ? chapters.get(next) : null;
            if (nc != null && missing(nc).isEmpty()) {
                // 다음 챕터: 목숨 0인 사람도 부활 (목숨 1)
                for (UUID u : run.members) if (run.lives(u) <= 0) run.lives.put(u, 1);
                run.chapter = nc;
                run.stage = nc.round(nc.start()).stage();
                broadcast(run, Component.text("[던전] " + nc.name() + " 챕터 시작", NamedTextColor.GOLD));
                run.phase = Run.Phase.MOVING;
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (run.phase == Run.Phase.MOVING) enterRound(run, nc.start());
                }, 60);
            } else {
                end(run, true);
            }
            return;
        }
        broadcast(run, Component.text(r.name() + " 클리어!", NamedTextColor.GREEN));
        if (r.next().size() == 1) {
            moveTo(run, r.next().getFirst(), 40);
            return;
        }
        run.phase = Run.Phase.VOTE;
        run.votes.clear();
        run.voteEndsAt = System.currentTimeMillis() + VOTE_SECONDS * 1000L;
        Component msg = Component.text("갈림길 — 파티가 함께 갈 길을 고르세요 (" + VOTE_SECONDS + "초): ", NamedTextColor.AQUA);
        for (String n : r.next()) {
            RoundDef nr = run.chapter.round(n);
            String label = nr != null ? nr.name() + " (" + nr.type().label + ")" : n;
            msg = msg.append(Component.text("[" + label + "] ", NamedTextColor.WHITE)
                    .clickEvent(ClickEvent.runCommand("/dg vote " + n)));
        }
        broadcast(run, msg);
    }

    private void moveTo(Run run, String roundId, int delay) {
        run.phase = Run.Phase.MOVING;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (run.phase == Run.Phase.MOVING && runs.containsKey(run.id)) enterRound(run, roundId);
        }, delay);
    }

    private void resolveVote(Run run) {
        Map<String, Integer> count = new HashMap<>();
        for (String v : run.votes.values()) count.merge(v, 1, Integer::sum);
        String pick = run.round.next().getFirst();
        int best = -1;
        for (String n : run.round.next()) { // 동표면 앞 순서
            int c = count.getOrDefault(n, 0);
            if (c > best) {
                best = c;
                pick = n;
            }
        }
        RoundDef nr = run.chapter.round(pick);
        broadcast(run, Component.text("선택: " + (nr != null ? nr.name() : pick), NamedTextColor.AQUA));
        moveTo(run, pick, 30);
    }

    // ================================================================== 진행 검사

    private void tick() {
        long now = System.currentTimeMillis();
        for (Run run : new ArrayList<>(runs.values())) {
            if (run.phase == Run.Phase.VOTE) {
                int voters = 0;
                for (UUID u : run.members) if (run.lives(u) > 0 || !run.down.contains(u)) voters++;
                if (now >= run.voteEndsAt || run.votes.size() >= voters) resolveVote(run);
                continue;
            }
            if (run.phase != Run.Phase.ROUND) continue;
            RoundDef r = run.round;
            if (r.type().combat()) {
                run.mobs.removeIf(id -> Run.dead((LivingEntity) Bukkit.getEntity(id)));
                if (run.mobs.isEmpty()) {
                    run.wave++;
                    spawnWave(run);
                }
            } else if (r.type().boss()) {
                MossWarden w = run.boss;
                if (w == null || !w.isAlive()) clearRound(run);
            } else if (r.trigger() != null && powered(r.trigger().getBlock())) {
                clearRound(run);
            }
        }
    }

    private static boolean powered(Block b) {
        if (b.getBlockData() instanceof Powerable pw && pw.isPowered()) return true;
        return b.isBlockPowered() || b.getBlockPower() > 0;
    }

    // ================================================================== 사망·부활

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Run run = byPlayer.get(p.getUniqueId());
        if (run == null) return;
        if (run.down.contains(p.getUniqueId()) || run.phase == Run.Phase.MOVING || run.phase == Run.Phase.RETRY) {
            e.setCancelled(true);
            return;
        }
        if (e.getFinalDamage() >= p.getHealth()) {
            e.setCancelled(true);
            down(run, p);
        }
    }

    /** 혹시 죽음 처리가 새어 나간 경우(/kill 등) 대비. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        Run run = byPlayer.get(e.getPlayer().getUniqueId());
        if (run == null) return;
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setKeepLevel(true);
        e.setDroppedExp(0);
        if (!run.down.contains(e.getPlayer().getUniqueId())) {
            Player p = e.getPlayer();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (byPlayer.get(p.getUniqueId()) == run) down(run, p);
            });
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Run run = byPlayer.get(e.getPlayer().getUniqueId());
        if (run != null && run.round != null && run.round.spawn() != null) e.setRespawnLocation(run.round.spawn());
    }

    private void down(Run run, Player p) {
        UUID u = p.getUniqueId();
        if (run.down.contains(u)) return;
        int left = Math.max(0, run.lives(u) - 1);
        run.lives.put(u, left);
        run.down.add(u);
        p.setGameMode(GameMode.SPECTATOR);
        for (PotionEffect pe : p.getActivePotionEffects()) p.removePotionEffect(pe.getType());
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_DEATH, 1f, 0.8f);
        if (left <= 0) {
            p.showTitle(Title.title(Component.text("목숨 소진", NamedTextColor.RED),
                    Component.text("관전하며 파티를 기다립니다 (스테이지 보상·다음 챕터에서 부활)", NamedTextColor.GRAY)));
        } else if (run.round.type() == RoundType.BOSS) {
            p.showTitle(Title.title(Component.text("쓰러짐", NamedTextColor.RED),
                    Component.text(bossRespawnSeconds + "초 뒤 재생성 · 남은 목숨 " + left, NamedTextColor.GRAY)));
            BukkitTask t = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                run.respawnTasks.remove(u);
                if (run.phase == Run.Phase.ROUND && run.down.contains(u)) revive(run, u, true);
            }, bossRespawnSeconds * 20L);
            run.respawnTasks.put(u, t);
        } else {
            p.showTitle(Title.title(Component.text("쓰러짐", NamedTextColor.RED),
                    Component.text("다음 라운드에서 부활 · 남은 목숨 " + left, NamedTextColor.GRAY)));
        }
        broadcast(run, Component.text(p.getName() + " 쓰러짐 (남은 목숨 " + left + ")", NamedTextColor.RED));
        checkWipe(run);
    }

    private void revive(Run run, UUID u, boolean teleport) {
        run.down.remove(u);
        Player p = Bukkit.getPlayer(u);
        if (p == null) return;
        GameMode gm = run.originalMode.getOrDefault(u, GameMode.SURVIVAL);
        p.setGameMode(gm == GameMode.SPECTATOR ? GameMode.SURVIVAL : gm);
        heal(p);
        if (teleport && run.round.spawn() != null) p.teleport(run.round.spawn());
        p.sendActionBar(Component.text("부활! 남은 목숨 " + run.lives(u), NamedTextColor.GREEN));
    }

    private void checkWipe(Run run) {
        if (run.phase == Run.Phase.ENDED || run.phase == Run.Phase.FAILING) return; // RETRY 중에도 목숨 소진은 검사
        if (!run.aliveMembers().isEmpty()) return;
        if (!run.anyLivesLeft()) {
            // 전원 소진: 즉시 진행 불가 상태로 (tick·클리어·투표 이동 모두 멈춤) → 3초 뒤 귀환
            run.phase = Run.Phase.FAILING;
            run.cancelRespawns();
            broadcast(run, Component.text("전멸 — 남은 목숨이 없습니다", NamedTextColor.DARK_RED));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (run.phase == Run.Phase.FAILING) end(run, false);
            }, 60);
            return;
        }
        // 투표·이동 중엔 목숨 남은 관전자가 다음 라운드에서 부활, RETRY 중엔 이미 재도전 예약됨
        if (run.phase != Run.Phase.ROUND) return;
        // 전멸이지만 목숨 남은 사람이 있음 → 그 라운드 재도전
        run.phase = Run.Phase.RETRY;
        run.cancelRespawns();
        broadcast(run, Component.text("전멸 — 3초 뒤 " + run.round.name() + " 재도전", NamedTextColor.GOLD));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (run.phase != Run.Phase.RETRY) return;
            clearMobs(run);
            removeBoss(run);
            enterRound(run, run.round.id()); // 목숨 남은 사람 부활 + 라운드 초기화
        }, 60);
    }

    private void heal(Player p) {
        AttributeInstance hp = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(hp != null ? hp.getValue() : 20);
        p.setFoodLevel(20);
        p.setSaturation(5);
        p.setFireTicks(0);
    }

    private void restore(Run run, Player p) {
        GameMode gm = run.originalMode.getOrDefault(p.getUniqueId(), GameMode.SURVIVAL);
        p.setGameMode(gm);
        if (gm != GameMode.SPECTATOR) heal(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Run run = byPlayer.remove(e.getPlayer().getUniqueId());
        if (run == null) return;
        UUID u = e.getPlayer().getUniqueId();
        restore(run, e.getPlayer());
        Location back = returnLoc != null ? returnLoc : Bukkit.getWorlds().getFirst().getSpawnLocation();
        e.getPlayer().teleport(back);
        run.members.remove(u);
        run.down.remove(u);
        BukkitTask t = run.respawnTasks.remove(u);
        if (t != null) t.cancel();
        run.votes.remove(u);
        if (run.members.isEmpty()) end(run, false);
        else checkWipe(run);
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent e) {
        Integer id = e.getEntity().getPersistentDataContainer().get(mobKey, PersistentDataType.INTEGER);
        if (id == null) return;
        Run run = runs.get(id);
        if (run != null) run.mobs.remove(e.getEntity().getUniqueId());
        e.getDrops().clear(); // 보상은 라운드·상점 시스템에서
        e.setDroppedExp(0);
    }

    /** 던전 슬라임은 분열하지 않음 (자식이 웨이브 추적에서 빠지는 문제 방지). */
    @EventHandler(ignoreCancelled = true)
    public void onSplit(org.bukkit.event.entity.SlimeSplitEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(mobKey, PersistentDataType.INTEGER)) e.setCancelled(true);
    }

    private void clearMobs(Run run) {
        for (UUID id : run.mobs) {
            Entity en = Bukkit.getEntity(id);
            if (en != null) en.remove();
        }
        run.mobs.clear();
    }

    private void removeBoss(Run run) {
        MossWarden w = run.boss;
        run.boss = null;
        if (w != null) w.remove();
    }

    private @Nullable Player nearestAlive(Run run, Location at) {
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (UUID u : run.aliveMembers()) {
            Player p = Bukkit.getPlayer(u);
            if (p == null || !p.getWorld().equals(at.getWorld())) continue;
            double d = p.getLocation().distanceSquared(at);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    // ================================================================== 공개 API (상점 등)

    /** 목숨 구매: 최대 buy-max-lives 까지. 성공 여부. */
    public boolean buyLife(Player p) {
        Run run = byPlayer.get(p.getUniqueId());
        if (run == null || run.stage <= 1) return false; // 스테이지 1에는 목숨 상품 없음
        int now = run.lives(p.getUniqueId());
        if (now >= buyMaxLives) return false;
        run.lives.put(p.getUniqueId(), now + 1);
        return true;
    }

    public boolean inDungeon(Player p) {
        return byPlayer.containsKey(p.getUniqueId());
    }

    // ================================================================== 표시

    private void broadcast(Run run, Component msg) {
        for (UUID u : run.members) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendMessage(msg);
        }
    }

    private void statusLine(Run run) {
        StringBuilder sb = new StringBuilder("목숨: ");
        for (UUID u : run.members) {
            Player p = Bukkit.getPlayer(u);
            sb.append(p != null ? p.getName() : "?").append(' ').append(run.lives(u))
                    .append(run.down.contains(u) ? "(관전)" : "").append("  ");
        }
        broadcast(run, Component.text(sb.toString().trim(), NamedTextColor.GRAY));
    }

    // ================================================================== 명령어 /dg

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("/dg status · vote <라운드> · next · leave  |  관리: start <챕터> [플레이어...] · stop · set <챕터> <라운드> <spawn|point|trigger|boss-spawn|clearpoints> · setreturn · reload · lives <플레이어> <n>", NamedTextColor.YELLOW));
            return true;
        }
        boolean admin = sender.hasPermission("deruyrpg.admin");
        Player self = sender instanceof Player pl ? pl : null;
        Run mine = self != null ? byPlayer.get(self.getUniqueId()) : null;
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> {
                if (mine == null) {
                    sender.sendMessage(Component.text("진행 중인 던전 " + runs.size() + "개", NamedTextColor.GRAY));
                } else {
                    sender.sendMessage(Component.text(mine.chapter.name() + " · 스테이지 " + mine.stage + " · " + mine.round.name()
                            + " (" + mine.round.type().label + ") · " + mine.phase, NamedTextColor.GREEN));
                    statusLine(mine);
                }
            }
            case "vote" -> {
                if (mine == null || mine.phase != Run.Phase.VOTE || args.length < 2) return true;
                if (!mine.round.next().contains(args[1])) return true;
                if (mine.lives(self.getUniqueId()) <= 0 && mine.down.contains(self.getUniqueId())) {
                    sender.sendMessage(Component.text("목숨이 없어 투표할 수 없습니다", NamedTextColor.GRAY));
                    return true;
                }
                mine.votes.put(self.getUniqueId(), args[1]);
                broadcast(mine, Component.text(self.getName() + " → " + mine.chapter.round(args[1]).name(), NamedTextColor.GRAY));
            }
            case "next" -> {
                Run target = mine;
                if (target == null && admin && args.length >= 2) {
                    Player o = Bukkit.getPlayerExact(args[1]);
                    target = o != null ? byPlayer.get(o.getUniqueId()) : null;
                }
                if (target == null || target.phase != Run.Phase.ROUND) return true;
                boolean free = !target.round.type().combat() && !target.round.type().boss() && target.round.trigger() == null;
                if (free || admin) {
                    clearMobs(target);
                    removeBoss(target);
                    clearRound(target);
                }
            }
            case "leave" -> {
                if (mine == null) return true;
                onQuitLike(self);
            }
            default -> {
                if (!admin) {
                    sender.sendMessage(Component.text("권한 없음", NamedTextColor.RED));
                    return true;
                }
                adminCommand(sender, self, args);
            }
        }
        return true;
    }

    private void onQuitLike(Player p) {
        Run run = byPlayer.remove(p.getUniqueId());
        if (run == null) return;
        UUID u = p.getUniqueId();
        restore(run, p);
        p.teleport(returnLoc != null ? returnLoc : Bukkit.getWorlds().getFirst().getSpawnLocation());
        run.members.remove(u);
        run.down.remove(u);
        BukkitTask t = run.respawnTasks.remove(u);
        if (t != null) t.cancel();
        run.votes.remove(u);
        if (run.members.isEmpty()) end(run, false);
        else checkWipe(run);
    }

    private void adminCommand(CommandSender sender, @Nullable Player self, String[] args) {
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("/dg start <챕터> [플레이어...] (생략 시 주변 10칸)", NamedTextColor.YELLOW));
                    return;
                }
                List<Player> ps = new ArrayList<>();
                if (args.length > 2) {
                    for (int i = 2; i < args.length; i++) {
                        Player p = Bukkit.getPlayerExact(args[i]);
                        if (p != null) ps.add(p);
                    }
                } else if (self != null) {
                    for (Player p : self.getWorld().getPlayers()) {
                        if (p.getLocation().distanceSquared(self.getLocation()) <= 100 && p.getGameMode() != GameMode.SPECTATOR) ps.add(p);
                    }
                }
                String err = start(args[1], ps);
                sender.sendMessage(err == null ? Component.text("시작: " + ps.size() + "명", NamedTextColor.GREEN) : Component.text(err, NamedTextColor.RED));
            }
            case "stop" -> {
                Run target = self != null ? byPlayer.get(self.getUniqueId()) : null;
                if (args.length >= 2) {
                    Player o = Bukkit.getPlayerExact(args[1]);
                    if (o != null) target = byPlayer.get(o.getUniqueId());
                }
                if (target != null) end(target, false);
                else if (args.length >= 2 && args[1].equalsIgnoreCase("all")) for (Run r : new ArrayList<>(runs.values())) end(r, false);
            }
            case "reload" -> {
                load();
                sender.sendMessage(Component.text("dungeon.yml 다시 읽음 — 챕터 " + chapters.size() + "개", NamedTextColor.GREEN));
            }
            case "setreturn" -> {
                if (self == null) return;
                yml.set("return", self.getLocation());
                saveYml();
                sender.sendMessage(Component.text("귀환 지점 저장", NamedTextColor.GREEN));
            }
            case "lives" -> {
                if (args.length < 3) return;
                Player o = Bukkit.getPlayerExact(args[1]);
                Run run = o != null ? byPlayer.get(o.getUniqueId()) : null;
                if (run == null) return;
                try {
                    run.lives.put(o.getUniqueId(), Math.max(0, Math.min(buyMaxLives, Integer.parseInt(args[2]))));
                    statusLine(run);
                } catch (NumberFormatException ignored) {
                }
            }
            case "set" -> {
                if (self == null || args.length < 4) {
                    sender.sendMessage(Component.text("/dg set <챕터> <라운드> <spawn|point|trigger|boss-spawn|clearpoints>", NamedTextColor.YELLOW));
                    return;
                }
                String base = "chapters." + args[1] + ".rounds." + args[2];
                if (!yml.isConfigurationSection(base)) {
                    sender.sendMessage(Component.text("dungeon.yml 에 라운드 없음: " + args[1] + "." + args[2], NamedTextColor.RED));
                    return;
                }
                Location here = self.getLocation().toBlockLocation().add(0.5, 0, 0.5);
                here.setYaw(self.getLocation().getYaw());
                switch (args[3].toLowerCase(Locale.ROOT)) {
                    case "spawn" -> yml.set(base + ".spawn", here);
                    case "boss-spawn" -> yml.set(base + ".boss-spawn", here);
                    case "point" -> {
                        List<Object> pts = new ArrayList<>(yml.getList(base + ".points", List.of()));
                        pts.add(here);
                        yml.set(base + ".points", pts);
                    }
                    case "clearpoints" -> yml.set(base + ".points", null);
                    case "trigger" -> {
                        Block b = self.getTargetBlockExact(6);
                        if (b == null) {
                            sender.sendMessage(Component.text("레버·압력판 등을 바라보고 입력", NamedTextColor.RED));
                            return;
                        }
                        yml.set(base + ".trigger", b.getLocation());
                    }
                    default -> {
                        sender.sendMessage(Component.text("spawn|point|trigger|boss-spawn|clearpoints", NamedTextColor.RED));
                        return;
                    }
                }
                saveYml();
                sender.sendMessage(Component.text(args[1] + "." + args[2] + " " + args[3] + " 저장", NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text("알 수 없는 명령", NamedTextColor.RED));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        boolean admin = sender.hasPermission("deruyrpg.admin");
        if (args.length == 1) {
            out.addAll(List.of("status", "vote", "next", "leave"));
            if (admin) out.addAll(List.of("start", "stop", "set", "setreturn", "reload", "lives"));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("start") || args[0].equalsIgnoreCase("set"))) {
            out.addAll(chapters.keySet());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("vote") && sender instanceof Player p) {
            Run r = byPlayer.get(p.getUniqueId());
            if (r != null && r.round != null) out.addAll(r.round.next());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            ChapterDef c = chapters.get(args[1]);
            if (c != null) out.addAll(c.rounds().keySet());
        } else if (args.length == 4 && args[0].equalsIgnoreCase("set")) {
            out.addAll(List.of("spawn", "point", "trigger", "boss-spawn", "clearpoints"));
        } else {
            return null;
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
