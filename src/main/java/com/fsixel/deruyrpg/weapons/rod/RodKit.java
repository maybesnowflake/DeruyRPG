package com.fsixel.deruyrpg.weapons.rod;

import com.fsixel.deruyrpg.api.SkillContext;
import com.fsixel.deruyrpg.api.WeaponKit;
import com.fsixel.deruyrpg.util.Items;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * 낚싯대 — 스킬 명세: docs/SKILLS.md 의 "낚싯대" 항목.
 * 이 패키지(weapons/rod/) 안에서만 작업할 것. 클래스를 더 나눠도 됨.
 */
public final class RodKit implements WeaponKit {

    @Override
    public String id() {
        return "rod";
    }

    @Override
    public String displayName() {
        return "낚싯대";
    }

    @Override
    public ItemStack createItem() {
        return Items.weapon(Material.FISHING_ROD, id(), "낚싯대", NamedTextColor.LIGHT_PURPLE,
                "P  경험치 바 = 마나",
                "우  모드 변경 (기본/당근/균)",
                "좌  모드별 단일 마법",
                "⇧우  모드별 범위 마법",
                "F  대마법");
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
