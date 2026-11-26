package com.fsixel.deruyrpg.api;

import com.fsixel.deruyrpg.DeruyRPG;
import com.fsixel.deruyrpg.core.Cooldowns;
import com.fsixel.deruyrpg.core.FeverManager;
import com.fsixel.deruyrpg.core.ManaManager;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 스킬 호출 시 넘어오는 정보 묶음.
 *
 * 자주 쓰는 패턴:
 * <pre>
 *   if (!ctx.tryCooldown(Slot.RIGHT, 100)) return; // 5초 쿨, 쿨 중이면 false
 *   if (!ctx.tryMana(0.2f)) return;                // 마나 20% 소모
 *   double dmg = ctx.isFever() ? 10 : 6;
 * </pre>
 */
public final class SkillContext {

    private final DeruyRPG plugin;
    private final Player player;
    private final ItemStack item;
    private final WeaponKit kit;
    private final @Nullable Cancellable event;

    public SkillContext(DeruyRPG plugin, Player player, ItemStack item, WeaponKit kit, @Nullable Cancellable event) {
        this.plugin = plugin;
        this.player = player;
        this.item = item;
        this.kit = kit;
        this.event = event;
    }

    public DeruyRPG plugin() { return plugin; }
    public Player player() { return player; }
    public ItemStack item() { return item; }
    public WeaponKit kit() { return kit; }

    /** 원래 이벤트 (바닐라 동작 막고 싶을 때 cancelVanilla()). 패시브 틱에서는 null. */
    public @Nullable Cancellable event() { return event; }

    /** 바닐라 동작(투척, 활 당기기, 막기 등)을 취소. */
    public void cancelVanilla() {
        if (event != null) event.setCancelled(true);
    }

    // ---- 쿨다운 ----
    public Cooldowns cooldowns() { return plugin.cooldowns(); }

    /** 쿨다운이 끝났으면 ticks 만큼 걸고 true, 아직이면 false. 피버 중엔 쿨다운이 config 비율만큼 줄어듦. */
    public boolean tryCooldown(String slot, int ticks) {
        return plugin.cooldowns().tryUse(player, kit.id(), slot, isFever() ? plugin.fever().scaleCooldown(ticks) : ticks);
    }

    public boolean onCooldown(String slot) {
        return plugin.cooldowns().remaining(player, kit.id(), slot) > 0;
    }

    // ---- 피버 ----
    public FeverManager fever() { return plugin.fever(); }

    public boolean isFever() { return plugin.fever().isFever(player); }

    // ---- 마나 ----
    public ManaManager mana() { return plugin.mana(); }

    /** 마나(0.0~1.0)를 amount 만큼 쓸 수 있으면 소모하고 true. */
    public boolean tryMana(float amount) {
        return plugin.mana().tryConsume(player, amount);
    }
}
