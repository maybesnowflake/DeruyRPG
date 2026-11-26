package com.fsixel.deruyrpg.weapons.bow;

import com.fsixel.deruyrpg.api.SkillContext;
import com.fsixel.deruyrpg.api.WeaponKit;
import com.fsixel.deruyrpg.util.Items;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * 활 — 스킬 명세: docs/SKILLS.md 의 "활" 항목.
 * 이 패키지(weapons/bow/) 안에서만 작업할 것. 클래스를 더 나눠도 됨.
 */
public final class BowKit implements WeaponKit {

    @Override
    public String id() {
        return "bow";
    }

    @Override
    public String displayName() {
        return "활";
    }

    @Override
    public ItemStack createItem() {
        return Items.weapon(Material.BOW, id(), "활", NamedTextColor.GREEN,
                "P  풀차지 피해 +25%",
                "우  사격",
                "좌  발차기 넉백",
                "⇧우  다음 3발 폭발 화살",
                "F  워든 음파");
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
