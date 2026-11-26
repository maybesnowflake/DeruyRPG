package com.fsixel.deruyrpg.core;

import com.fsixel.deruyrpg.DeruyRPG;
import com.fsixel.deruyrpg.api.SkillContext;
import com.fsixel.deruyrpg.api.WeaponKit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

/** 5틱마다: 패시브 틱, 액션바(게이지·쿨다운), 마나 표시, 게이지 가득 시 반짝임. */
public final class HudTask extends BukkitRunnable {

    public static final int PERIOD = 5;
    private static final int BAR_CELLS = 10;

    private final DeruyRPG plugin;
    private int counter;

    public HudTask(DeruyRPG plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        counter++;
        boolean secondTick = counter % (20 / PERIOD) == 0;

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (secondTick) plugin.mana().regenTick(p);

            ItemStack item = p.getInventory().getItemInMainHand();
            WeaponKit kit = plugin.kits().find(item);
            if (kit == null) continue;

            try {
                kit.onPassiveTick(new SkillContext(plugin, p, item, kit, null));
            } catch (Throwable t) {
                plugin.getLogger().warning("[" + kit.id() + "] 패시브 오류: " + t);
            }

            if (kit.usesMana()) plugin.mana().display(p);
            updateGlint(p, item);
            p.sendActionBar(actionBar(p, kit, new SkillContext(plugin, p, item, kit, null)));
        }
    }

    private void updateGlint(Player p, ItemStack item) {
        boolean shouldGlow = plugin.fever().isFull(p) || plugin.fever().isFever(p);
        Boolean current = item.getItemMeta().hasEnchantmentGlintOverride()
                ? item.getItemMeta().getEnchantmentGlintOverride() : null;
        if (shouldGlow && !Boolean.TRUE.equals(current)) {
            item.editMeta(m -> m.setEnchantmentGlintOverride(true));
        } else if (!shouldGlow && current != null) {
            item.editMeta(m -> m.setEnchantmentGlintOverride(null));
        }
    }

    private Component actionBar(Player p, WeaponKit kit, SkillContext ctx) {
        FeverManager fever = plugin.fever();
        TextComponent.Builder b = Component.text();

        if (fever.isFever(p)) {
            b.append(Component.text("🔥 피버 " + fmt(fever.feverRemaining(p)), NamedTextColor.GOLD));
        } else {
            int filled = (int) Math.floor(fever.get(p) / FeverManager.MAX * BAR_CELLS);
            NamedTextColor color = fever.isFull(p) ? NamedTextColor.GOLD : NamedTextColor.YELLOW;
            b.append(Component.text("F ", NamedTextColor.WHITE));
            b.append(Component.text("▮".repeat(filled), color));
            b.append(Component.text("▯".repeat(BAR_CELLS - filled), NamedTextColor.DARK_GRAY));
        }

        for (String slot : kit.cooldownSlots(p)) {
            int rem = plugin.cooldowns().remaining(p, kit.id(), slot);
            b.append(Component.text("   " + slot + " ", NamedTextColor.WHITE));
            if (rem > 0) b.append(Component.text(fmt(rem), NamedTextColor.RED));
            else b.append(Component.text("✔", NamedTextColor.GREEN));
        }
        try {
            Component extra = kit.hudExtra(ctx);
            if (extra != null) b.append(Component.text("   ")).append(extra);
        } catch (Throwable t) {
            plugin.getLogger().warning("[" + kit.id() + "] HUD 오류: " + t);
        }
        return b.build();
    }

    private static String fmt(int ticks) {
        return String.format("%.1fs", ticks / 20.0);
    }
}
