package com.fsixel.deruyrpg.command;

import com.fsixel.deruyrpg.DeruyRPG;
import com.fsixel.deruyrpg.api.WeaponKit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * /rpg give <무기|all> [플레이어]
 * /rpg fever [플레이어]      — 게이지 가득 (테스트용)
 * /rpg mana [플레이어]       — 마나 가득
 * /rpg reset [플레이어]      — 쿨다운 초기화
 * /rpg dummy [clear]       — 훈련봇 소환 / 전부 제거
 * /rpg fevercfg [max|hit|hurt|skill|interval] [값]  — 피버 게이지 설정 보기/변경 (config.yml 저장)
 * /rpg boss <spawn|setspawn|phase2|kill|clear|check|hp>  — 정글 던전 보스(이끼 수문장) 테스트
 * /rpg list
 */
public final class RpgCommand implements TabExecutor {

    private final DeruyRPG plugin;

    public RpgCommand(DeruyRPG plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("/rpg give <무기|all> [플레이어] · fever · fevercfg · mana · reset · dummy · boss · list", NamedTextColor.YELLOW));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "list" -> {
                for (WeaponKit k : plugin.kits().all()) {
                    sender.sendMessage(Component.text("- " + k.id() + " (" + k.displayName() + ")", NamedTextColor.GRAY));
                }
            }
            case "give" -> {
                if (args.length < 2) return usage(sender);
                Player target = target(sender, args, 2);
                if (target == null) return true;
                if (args[1].equalsIgnoreCase("all")) {
                    for (WeaponKit k : plugin.kits().all()) target.getInventory().addItem(k.createItem());
                } else {
                    WeaponKit k = plugin.kits().get(args[1].toLowerCase());
                    if (k == null) {
                        sender.sendMessage(Component.text("없는 무기: " + args[1], NamedTextColor.RED));
                        return true;
                    }
                    target.getInventory().addItem(k.createItem());
                }
                sender.sendMessage(Component.text(target.getName() + "에게 지급", NamedTextColor.GREEN));
            }
            case "fever" -> {
                Player target = target(sender, args, 1);
                if (target != null) plugin.fever().set(target, plugin.fever().max());
            }
            case "mana" -> {
                Player target = target(sender, args, 1);
                if (target != null) plugin.mana().set(target, plugin.mana().max(target));
            }
            case "dummy" -> {
                if (args.length > 1 && args[1].equalsIgnoreCase("clear")) {
                    int n = plugin.dummy().clearAll();
                    sender.sendMessage(Component.text("훈련봇 " + n + "개 제거", NamedTextColor.GREEN));
                } else if (sender instanceof Player p) {
                    var loc = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(3));
                    loc.setYaw(p.getLocation().getYaw() + 180);
                    loc.setPitch(0);
                    plugin.dummy().spawn(loc);
                    sender.sendMessage(Component.text("훈련봇 소환 (/rpg dummy clear 로 제거)", NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text("게임 안에서 사용하세요", NamedTextColor.RED));
                }
            }
            case "fevercfg" -> {
                if (args.length >= 3) {
                    double v;
                    try {
                        v = Double.parseDouble(args[2]);
                    } catch (NumberFormatException ex) {
                        sender.sendMessage(Component.text("숫자를 입력하세요", NamedTextColor.RED));
                        return true;
                    }
                    if (!Double.isFinite(v)) {
                        sender.sendMessage(Component.text("올바른 숫자를 입력하세요", NamedTextColor.RED));
                        return true;
                    }
                    if (!plugin.fever().setOption(args[1].toLowerCase(), v)) {
                        sender.sendMessage(Component.text("항목: max, hit, hurt, skill, interval", NamedTextColor.RED));
                        return true;
                    }
                    var sec = plugin.getConfig().getConfigurationSection("fever");
                    if (sec == null) sec = plugin.getConfig().createSection("fever");
                    plugin.fever().save(sec);
                    plugin.saveConfig();
                }
                sender.sendMessage(Component.text("피버 게이지: " + plugin.fever().describe(), NamedTextColor.YELLOW));
                if (args.length < 3) {
                    sender.sendMessage(Component.text("변경: /rpg fevercfg <max|hit|hurt|skill|interval> <값>", NamedTextColor.GRAY));
                }
            }
            case "boss" -> bossCommand(sender, args);
            case "reset" -> {
                Player target = target(sender, args, 1);
                if (target != null) plugin.cooldowns().clear(target);
            }
            default -> {
                return usage(sender);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ 보스

    private void bossCommand(CommandSender sender, String[] args) {
        var bosses = plugin.bosses();
        String sub = args.length > 1 ? args[1].toLowerCase() : "";
        switch (sub) {
            case "spawn" -> {
                if (bosses.active() != null) {
                    sender.sendMessage(Component.text("이미 보스가 있습니다. /rpg boss clear 로 정리 후 다시 소환하세요", NamedTextColor.RED));
                    return;
                }
                boolean mini = args[args.length - 1].equalsIgnoreCase("mini");
                boolean p2 = !mini && args[args.length - 1].equalsIgnoreCase("p2");
                int n = (p2 || mini) ? args.length - 1 : args.length; // p2/mini 제외한 인자 수
                org.bukkit.Location loc;
                if (n >= 6) {
                    // /rpg boss spawn <world> <x> <y> <z> [yaw]
                    org.bukkit.World w = Bukkit.getWorld(args[2]);
                    if (w == null) {
                        sender.sendMessage(Component.text("없는 월드: " + args[2], NamedTextColor.RED));
                        return;
                    }
                    try {
                        float yaw = n >= 7 ? Float.parseFloat(args[6]) : 0f;
                        loc = new org.bukkit.Location(w, Double.parseDouble(args[3]), Double.parseDouble(args[4]),
                                Double.parseDouble(args[5]), yaw, 0f);
                    } catch (NumberFormatException ex) {
                        sender.sendMessage(Component.text("좌표는 숫자로 입력하세요", NamedTextColor.RED));
                        return;
                    }
                } else if (n >= 3 && args[2].equalsIgnoreCase("here")) {
                    if (!(sender instanceof Player p)) {
                        sender.sendMessage(Component.text("게임 안에서 사용하세요 (콘솔: /rpg boss spawn <world> <x> <y> <z> [yaw] [p2])", NamedTextColor.RED));
                        return;
                    }
                    loc = facingPlayer(p);
                } else if (bosses.settings().spawn != null) {
                    loc = bosses.settings().spawn.clone();
                } else if (sender instanceof Player p) {
                    loc = facingPlayer(p);
                    sender.sendMessage(Component.text("보스방 좌표가 없어 앞에 소환합니다 (/rpg boss setspawn 으로 저장)", NamedTextColor.GRAY));
                } else {
                    sender.sendMessage(Component.text("보스방 좌표가 없습니다: /rpg boss spawn <world> <x> <y> <z> [yaw] [p2]", NamedTextColor.RED));
                    return;
                }
                bosses.spawn(loc, p2, mini);
                sender.sendMessage(Component.text("이끼 수문장 소환" + (p2 ? " (2페이즈)" : mini ? " (미니보스)" : "") + " @ "
                        + loc.getWorld().getName() + " " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ(), NamedTextColor.GREEN));
            }
            case "setspawn" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Component.text("게임 안에서 사용하세요", NamedTextColor.RED));
                    return;
                }
                org.bukkit.Location l = p.getLocation().clone();
                l.setPitch(0);
                bosses.settings().spawn = l;
                bosses.saveSettings();
                sender.sendMessage(Component.text("보스방 좌표 저장: " + l.getWorld().getName() + " "
                        + String.format("%.1f %.1f %.1f yaw %.0f", l.getX(), l.getY(), l.getZ(), l.getYaw())
                        + " (보스는 지금 바라보는 방향을 봄)", NamedTextColor.GREEN));
            }
            case "phase2" -> {
                var w = bosses.active();
                if (w != null) {
                    if (w.phase() >= 2) {
                        sender.sendMessage(Component.text("이미 2페이즈입니다", NamedTextColor.YELLOW));
                    } else {
                        w.forcePhase2();
                        sender.sendMessage(Component.text("각성 연출 시작", NamedTextColor.GREEN));
                    }
                    return;
                }
                org.bukkit.Location loc = bosses.settings().spawn != null ? bosses.settings().spawn.clone()
                        : sender instanceof Player p ? facingPlayer(p) : null;
                if (loc == null) {
                    sender.sendMessage(Component.text("보스방 좌표가 없습니다: /rpg boss spawn <world> <x> <y> <z> [yaw] p2", NamedTextColor.RED));
                    return;
                }
                bosses.spawn(loc, true);
                sender.sendMessage(Component.text("보스가 없어 2페이즈로 바로 소환", NamedTextColor.GREEN));
            }
            case "kill" -> {
                var w = bosses.active();
                if (w == null) {
                    sender.sendMessage(Component.text("살아있는 보스 없음", NamedTextColor.GRAY));
                    return;
                }
                w.kill();
                sender.sendMessage(Component.text("보스 처치 연출 시작 — 끝난 뒤 /rpg boss check 로 남은 블록 확인", NamedTextColor.GREEN));
            }
            case "clear" -> {
                bosses.removeAll();
                int left = bosses.removeTagged();
                sender.sendMessage(Component.text("보스 정리 완료" + (left > 0 ? " (남은 엔티티 " + left + "개 추가 제거)" : ""), NamedTextColor.GREEN));
            }
            case "check" -> {
                var w = bosses.active();
                sender.sendMessage(Component.text(w == null ? "살아있는 보스 없음" : w.status(), NamedTextColor.YELLOW));
                int n = bosses.countTagged();
                sender.sendMessage(Component.text("보스 태그 엔티티(표시 블록·히트박스·연출·졸개): " + n + "개",
                        n == 0 || w != null ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
            case "hp" -> {
                var w = bosses.active();
                if (w == null || w.hitbox() == null) {
                    sender.sendMessage(Component.text("살아있는 보스 없음", NamedTextColor.GRAY));
                    return;
                }
                if (args.length < 3) {
                    sender.sendMessage(Component.text("/rpg boss hp <퍼센트>", NamedTextColor.RED));
                    return;
                }
                double pct;
                try {
                    pct = Double.parseDouble(args[2]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage(Component.text("숫자로 입력하세요", NamedTextColor.RED));
                    return;
                }
                var hb = w.hitbox();
                var max = hb.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                double m = max != null ? max.getValue() : hb.getHealth();
                hb.setHealth(Math.max(1.0, Math.min(m, m * pct / 100.0)));
                sender.sendMessage(Component.text("보스 체력 " + (int) Math.ceil(hb.getHealth()) + "/" + (int) m, NamedTextColor.GREEN));
                if (w.phase() == 1 && pct <= 50) {
                    sender.sendMessage(Component.text("체력 50% 이하 → 곧바로 각성 연출이 시작됩니다", NamedTextColor.GRAY));
                }
            }
            default -> sender.sendMessage(Component.text(
                    "/rpg boss spawn [here|<world> <x> <y> <z> [yaw]] [p2|mini] · setspawn · phase2 · kill · clear · check · hp <퍼센트>",
                    NamedTextColor.YELLOW));
        }
    }

    /** 플레이어 앞 6칸, 플레이어를 바라보게. */
    private static org.bukkit.Location facingPlayer(Player p) {
        var dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new org.bukkit.util.Vector(0, 0, 1);
        org.bukkit.Location loc = p.getLocation().add(dir.normalize().multiply(6));
        loc.setYaw(p.getLocation().getYaw() + 180);
        loc.setPitch(0);
        return loc;
    }

    private List<String> bossTab(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 2) {
            out.addAll(List.of("spawn", "setspawn", "phase2", "kill", "clear", "check", "hp"));
        } else if (args[1].equalsIgnoreCase("spawn")) {
            if (args.length == 3) {
                out.add("here");
                out.add("p2");
                out.add("mini");
                for (org.bukkit.World w : Bukkit.getWorlds()) out.add(w.getName());
            } else if (args.length == 4 && args[2].equalsIgnoreCase("here")) {
                out.add("p2");
                out.add("mini");
            } else if (args.length >= 4 && args.length <= 6 && sender instanceof Player p) {
                var l = p.getLocation();
                out.add(String.valueOf(switch (args.length) {
                    case 4 -> l.getBlockX();
                    case 5 -> l.getBlockY();
                    default -> l.getBlockZ();
                }));
            } else if (args.length == 7 && sender instanceof Player p) {
                out.add(String.valueOf(Math.round(p.getLocation().getYaw() + 180)));
            } else if (args.length == 8) {
                out.add("p2");
                out.add("mini");
            }
        } else if (args.length == 3 && args[1].equalsIgnoreCase("hp")) {
            out.addAll(List.of("100", "51", "50", "25", "1"));
        }
        String last = args[args.length - 1].toLowerCase();
        out.removeIf(s -> !s.toLowerCase().startsWith(last));
        return out;
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(Component.text("사용법: /rpg give <무기|all> [플레이어]", NamedTextColor.RED));
        return true;
    }

    private Player target(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            Player p = Bukkit.getPlayerExact(args[index]);
            if (p == null) sender.sendMessage(Component.text("플레이어 없음: " + args[index], NamedTextColor.RED));
            return p;
        }
        if (sender instanceof Player p) return p;
        sender.sendMessage(Component.text("플레이어를 지정하세요", NamedTextColor.RED));
        return null;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                                      @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("give", "fever", "fevercfg", "mana", "reset", "dummy", "boss", "list")) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("fevercfg")) {
            for (String s : List.of("max", "hit", "hurt", "skill", "interval")) if (s.startsWith(args[1].toLowerCase())) out.add(s);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("fevercfg")) {
            return out;
        } else if (args.length >= 2 && args[0].equalsIgnoreCase("boss")) {
            return bossTab(sender, args);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("dummy")) {
            if ("clear".startsWith(args[1].toLowerCase())) out.add("clear");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            out.add("all");
            for (WeaponKit k : plugin.kits().all()) out.add(k.id());
            out.removeIf(s -> !s.startsWith(args[1].toLowerCase()));
        } else {
            return null; // 플레이어 이름
        }
        return out;
    }
}
