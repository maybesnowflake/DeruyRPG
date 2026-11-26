package com.fsixel.deruyrpg.weapons.crossbow;

import com.fsixel.deruyrpg.api.SkillContext;
import com.fsixel.deruyrpg.api.WeaponKit;
import com.fsixel.deruyrpg.util.Items;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * 쇠뇌 — 스킬 명세: docs/SKILLS.md 의 "쇠뇌" 항목.
 * 이 패키지(weapons/crossbow/) 안에서만 작업할 것. 클래스를 더 나눠도 됨.
 */
public final class CrossbowKit implements WeaponKit {

    @Override
    public String id() {
        return "crossbow";
    }

    @Override
    public String displayName() {
        return "쇠뇌";
    }

    @Override
    public ItemStack createItem() {
        return Items.weapon(Material.CROSSBOW, id(), "쇠뇌", NamedTextColor.DARK_GREEN,
                "P  직진 탄도",
                "우  사격",
                "좌  발차기 넉백",
                "⇧우  10초간 구속 화살",
                "F  유도 + 고속 연사");
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
