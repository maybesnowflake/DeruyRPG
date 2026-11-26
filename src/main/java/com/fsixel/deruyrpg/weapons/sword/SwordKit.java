package com.fsixel.deruyrpg.weapons.sword;

import com.fsixel.deruyrpg.api.SkillContext;
import com.fsixel.deruyrpg.api.WeaponKit;
import com.fsixel.deruyrpg.util.Items;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * 검 — 스킬 명세: docs/SKILLS.md 의 "검" 항목.
 * 이 패키지(weapons/sword/) 안에서만 작업할 것. 클래스를 더 나눠도 됨.
 */
public final class SwordKit implements WeaponKit {

    @Override
    public String id() {
        return "sword";
    }

    @Override
    public String displayName() {
        return "검";
    }

    @Override
    public ItemStack createItem() {
        return Items.weapon(Material.NETHERITE_SWORD, id(), "검", NamedTextColor.WHITE,
                "P  연속 베기 (3타마다 강화, 최대 4중첩)",
                "우  막기 / 패링",
                "좌  휩쓸기 베기",
                "⇧우  2.5칸 돌진 베기",
                "F  일섬");
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
