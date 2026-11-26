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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 5틱마다: 무기 해제 감지(onUnequip), 패시브 틱, 액션바(게이지·쿨다운), 마나 표시, 게이지 가득 시 반짝임.
 * 무기 메시지는 flash() 로 보내면 잠깐 동안 HUD 대신 표시된다.
 */
public final class HudTask extends BukkitRunnable {

    public static final int PERIOD = 5;
    private static final int BAR_CELLS = 10;

    private record Flash(Component message, int until) {}

    private static final Map<UUID, Flash> flashes = new ConcurrentHashMap<>();

    private final DeruyRPG plugin;
    /** 플레이어가 직전에 주손에 들고 있던 무기 id (해제 감지용) */
    private final Map<UUID, String> lastKit = new ConcurrentHashMap<>();
    private int counter;

    public HudTask(DeruyRPG plugin) {
        this.plugin = plugin;
    }

    /** 액션바에 ticks 동안 message 표시 (HUD 대신). */
    public static void flash(Player p, Component message, int ticks) {
        flashes.put(p.getUniqueId(), new Flash(message, Bukkit.getCurrentTick() + ticks));
        p.sendActionBar(message);
    }

    /** 무기 해제 처리 (슬롯 변경·버리기·사망·인벤토리 이동 등 모든 경우). */
    public void unequip(Player p, String kitId) {
        WeaponKit kit = plugin.kits().get(kitId);
        if (kit == null) return;
        try {
            kit.onUnequip(p);
        } catch (Throwable t) {
            plugin.getLogger().warning("[" + kitId + "] 해제 오류: " + t);
        }
        if (kit.usesMana()) plugin.mana().restoreDisplay(p);
    }

    /**
     * 입력(스킬·평타·F)으로 무기가 쓰였을 때 코어가 호출 — 5틱 HUD 검사 사이에 잠깐 든 무기도 해제 추적에 잡히게.
     */
    public void touch(Player p, String kitId) {
        String prev = lastKit.put(p.getUniqueId(), kitId);
        if (prev != null && !prev.equals(kitId)) unequip(p, prev);
    }

    public void forget(Player p) {
        String prev = lastKit.remove(p.getUniqueId());
        if (prev != null) unequip(p, prev);
        flashes.remove(p.getUniqueId());
    }

    @Override
    public void run() {
        counter++;
        boolean secondTick = counter % (20 / PERIOD) == 0;
        int now = Bukkit.getCurrentTick();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (secondTick) plugin.mana().regenTick(p);

            ItemStack item = p.getInventory().getItemInMainHand();
            WeaponKit kit = p.isDead() ? null : plugin.kits().find(item);

            String prev = lastKit.get(p.getUniqueId());
            String cur = kit == null ? null : kit.id();
            if (prev != null && !prev.equals(cur)) unequip(p, prev);
            if (cur == null) lastKit.remove(p.getUniqueId());
            else lastKit.put(p.getUniqueId(), cur);
            if (kit == null) continue;

            SkillContext ctx = new SkillContext(plugin, p, item, kit, null);
            try {
                kit.onPassiveTick(ctx);
            } catch (Throwable t) {
                plugin.getLogger().warning("[" + kit.id() + "] 패시브 오류: " + t);
            }

            if (kit.usesMana()) plugin.mana().display(p);
            updateGlint(p, item, kit);

            Flash f = flashes.get(p.getUniqueId());
            if (f != null && f.until() > now) {
                p.sendActionBar(f.message());
            } else {
                if (f != null) flashes.remove(p.getUniqueId());
                p.sendActionBar(actionBar(p, kit, ctx));
            }
        }
    }

    private void updateGlint(Player p, ItemStack item, WeaponKit kit) {
        boolean shouldGlow = plugin.fever().isFull(p) || plugin.fever().isFever(p, kit.id());
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
            String owner = fever.feverKit(p);
            WeaponKit ownerKit = owner == null ? null : plugin.kits().get(owner);
            if (owner == null || owner.equals(kit.id())) {
                b.append(Component.text("🔥 피버 " + fmt(fever.feverRemaining(p)), NamedTextColor.GOLD));
            } else {
                b.append(Component.text("🔥 " + (ownerKit == null ? owner : ownerKit.displayName())
                        + " 피버 " + fmt(fever.feverRemaining(p)), NamedTextColor.GRAY));
            }
        } else {
            int filled = (int) Math.floor(fever.get(p) / fever.max() * BAR_CELLS);
            filled = Math.max(0, Math.min(BAR_CELLS, filled));
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
