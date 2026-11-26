package com.fsixel.deruyrpg.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** RPG 무기 아이템 태그/생성 도우미. */
public final class Items {

    public static final NamespacedKey WEAPON_KEY = new NamespacedKey("deruyrpg", "weapon");

    private Items() {}

    /** 아이템에 무기 id 태그. */
    public static ItemStack tag(ItemStack item, String kitId) {
        item.editMeta(meta -> meta.getPersistentDataContainer().set(WEAPON_KEY, PersistentDataType.STRING, kitId));
        return item;
    }

    public static @Nullable String kitId(@Nullable ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        // 읽기 전용 뷰 — getItemMeta() 복제 없이 빠르게
        return item.getPersistentDataContainer().get(WEAPON_KEY, PersistentDataType.STRING);
    }

    /**
     * 기본 무기 아이템 생성: 이름 + 스킬 설명 로어 + 태그.
     * lore 예: "P  광폭화", "우  회전 베기", ...
     */
    public static ItemStack weapon(Material material, String kitId, String name, NamedTextColor color, String... lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> applyText(meta, name, color, lore));
        return tag(item, kitId);
    }

    private static void applyText(ItemMeta meta, String name, NamedTextColor color, String... lore) {
        meta.itemName(Component.text(name, color).decoration(TextDecoration.BOLD, true));
        List<Component> lines = new ArrayList<>();
        for (String line : lore) {
            lines.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lines);
        meta.setUnbreakable(true);
    }
}
