package com.fsixel.deruyrpg.weapons.shield;

import com.fsixel.deruyrpg.api.SkillContext;
import com.fsixel.deruyrpg.api.WeaponKit;
import com.fsixel.deruyrpg.util.Items;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * 방패 — 스킬 명세: docs/SKILLS.md 의 "방패" 항목.
 * 이 패키지(weapons/shield/) 안에서만 작업할 것. 클래스를 더 나눠도 됨.
 */
public final class ShieldKit implements WeaponKit {

    @Override
    public String id() {
        return "shield";
    }

    @Override
    public String displayName() {
        return "방패";
    }

    @Override
    public ItemStack createItem() {
        return Items.weapon(Material.SHIELD, id(), "방패", NamedTextColor.BLUE,
                "좌  방패 ↔ 불사의 토템 스왑",
                "P  철벽 / 재생 + 1회 부활",
                "우  막기 / 나에게 재생",
                "⇧우  넉백+구속 / 아군 회복",
                "F  성역");
    }

    @Override
    public boolean usesMana() {
        return true;
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
