package com.fsixel.deruyrpg.weapons.spear;

import com.fsixel.deruyrpg.api.SkillContext;
import com.fsixel.deruyrpg.api.WeaponKit;
import com.fsixel.deruyrpg.util.Items;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * 창 — 스킬 명세: docs/SKILLS.md 의 "창" 항목.
 * 이 패키지(weapons/spear/) 안에서만 작업할 것. 클래스를 더 나눠도 됨.
 */
public final class SpearKit implements WeaponKit {

    @Override
    public String id() {
        return "spear";
    }

    @Override
    public String displayName() {
        return "창";
    }

    @Override
    public ItemStack createItem() {
        return Items.weapon(Material.NETHERITE_SPEAR, id(), "창", NamedTextColor.YELLOW,
                "P  리치 +2, 2칸 내 추가 피해",
                "우  빠른 연속 찌르기",
                "좌  찌르기",
                "⇧우  큰 창 3×5 관통 찌르기",
                "F  고대 군단");
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
