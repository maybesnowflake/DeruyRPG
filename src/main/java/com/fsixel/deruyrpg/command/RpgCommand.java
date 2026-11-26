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
            sender.sendMessage(Component.text("/rpg give <무기|all> [플레이어] · fever · fevercfg · mana · reset · dummy · list", NamedTextColor.YELLOW));
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
            for (String s : List.of("give", "fever", "fevercfg", "mana", "reset", "dummy", "list")) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("fevercfg")) {
            for (String s : List.of("max", "hit", "hurt", "skill", "interval")) if (s.startsWith(args[1].toLowerCase())) out.add(s);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("fevercfg")) {
            return out;
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
