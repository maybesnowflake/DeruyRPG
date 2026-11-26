package com.fsixel.deruyrpg.weapons.trident;

import com.fsixel.deruyrpg.api.SkillContext;
import com.fsixel.deruyrpg.api.WeaponKit;
import com.fsixel.deruyrpg.util.Items;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * 삼지창 — 스킬 명세: docs/SKILLS.md 의 "삼지창" 항목.
 * 이 패키지(weapons/trident/) 안에서만 작업할 것. 클래스를 더 나눠도 됨.
 */
public final class TridentKit implements WeaponKit {

    @Override
    public String id() {
        return "trident";
    }

    @Override
    public String displayName() {
        return "삼지창";
    }

    @Override
    public ItemStack createItem() {
        return Items.weapon(Material.TRIDENT, id(), "삼지창", NamedTextColor.AQUA,
                "P  물의 가호",
                "우  투척 / 급류",
                "좌  찌르기 4칸 (비 속: 물살 찌르기)",
                "⇧우  물기둥",
                "F  뇌우 + 비 소환");
    }

    // TODO: 스킬 구현
    @Override
    public void onRightClick(SkillContext ctx) {
    }

    @Override
    public void onShiftRightClick(SkillContext ctx) {
    }

    @Override
    public void onUltimate(SkillContext ctx) {
    }
}
