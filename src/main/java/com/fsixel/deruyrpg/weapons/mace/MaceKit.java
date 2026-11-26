package com.fsixel.deruyrpg.weapons.mace;

import com.fsixel.deruyrpg.api.SkillContext;
import com.fsixel.deruyrpg.api.WeaponKit;
import com.fsixel.deruyrpg.util.Items;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * 철퇴 — 스킬 명세: docs/SKILLS.md 의 "철퇴" 항목.
 * 이 패키지(weapons/mace/) 안에서만 작업할 것. 클래스를 더 나눠도 됨.
 */
public final class MaceKit implements WeaponKit {

    @Override
    public String id() {
        return "mace";
    }

    @Override
    public String displayName() {
        return "철퇴";
    }

    @Override
    public ItemStack createItem() {
        return Items.weapon(Material.MACE, id(), "철퇴", NamedTextColor.GRAY,
                "P  낙하 거리 비례 피해",
                "우  도약 후 범위 강타",
                "좌  강타",
                "⇧우  공중 돌풍 점프",
                "F  지진");
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
