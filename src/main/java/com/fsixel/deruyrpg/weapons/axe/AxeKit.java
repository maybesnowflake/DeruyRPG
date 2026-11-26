package com.fsixel.deruyrpg.weapons.axe;

import com.fsixel.deruyrpg.api.SkillContext;
import com.fsixel.deruyrpg.api.WeaponKit;
import com.fsixel.deruyrpg.util.Items;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * 도끼 — 스킬 명세: docs/SKILLS.md 의 "도끼" 항목.
 * 이 패키지(weapons/axe/) 안에서만 작업할 것. 클래스를 더 나눠도 됨.
 */
public final class AxeKit implements WeaponKit {

    @Override
    public String id() {
        return "axe";
    }

    @Override
    public String displayName() {
        return "도끼";
    }

    @Override
    public ItemStack createItem() {
        return Items.weapon(Material.NETHERITE_AXE, id(), "도끼", NamedTextColor.RED,
                "P  광폭화",
                "우  회전 베기 (체력 1칸 소모)",
                "좌  내려찍기 (흡혈)",
                "⇧우  피의 도끼 (출혈 + 힘 II)",
                "F  처형 (20% 이하)");
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
