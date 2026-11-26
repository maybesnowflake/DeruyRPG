package com.fsixel.deruyrpg.core;

import com.fsixel.deruyrpg.DeruyRPG;
import com.fsixel.deruyrpg.api.SkillContext;
import com.fsixel.deruyrpg.api.WeaponKit;
import com.fsixel.deruyrpg.util.Damage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 키 입력 → WeaponKit 호출. 무기 세션은 이 파일을 고치지 않는다. */
public final class InputListener implements Listener {

    private final DeruyRPG plugin;
    /** 같은 틱 중복 우클릭 방지 */
    private final Map<UUID, Integer> lastRightTick = new ConcurrentHashMap<>();
    /** Q(버리기)로 생기는 가짜 좌클릭 방지 */
    private final Map<UUID, Integer> lastDropTick = new ConcurrentHashMap<>();

    public InputListener(DeruyRPG plugin) {
        this.plugin = plugin;
    }

    private SkillContext ctx(Player p, ItemStack item, WeaponKit kit, Cancellable event) {
        plugin.hud().touch(p, kit.id());
        return new SkillContext(plugin, p, item, kit, event);
    }

    private boolean firstRightThisTick(Player p) {
        int now = Bukkit.getCurrentTick();
        Integer last = lastRightTick.put(p.getUniqueId(), now);
        return last == null || last != now;
    }

    private void fireRight(Player p, ItemStack item, WeaponKit kit, Cancellable event) {
        if (!firstRightThisTick(p)) return;
        SkillContext c = ctx(p, item, kit, event);
        if (p.isSneaking()) kit.onShiftRightClick(c);
        else kit.onRightClick(c);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack item = p.getInventory().getItemInMainHand();
        WeaponKit kit = plugin.kits().find(item);
        if (kit == null) return;

        Action a = e.getAction();
        if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) {
            fireRight(p, item, kit, e);
        } else if (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) {
            Integer drop = lastDropTick.get(p.getUniqueId());
            if (drop != null && drop >= Bukkit.getCurrentTick() - 1) return;
            kit.onLeftClick(ctx(p, item, kit, e));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack item = p.getInventory().getItemInMainHand();
        WeaponKit kit = plugin.kits().find(item);
        if (kit == null) return;
        fireRight(p, item, kit, e);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent e) {
        lastDropTick.put(e.getPlayer().getUniqueId(), Bukkit.getCurrentTick());
    }

    /** 근접 평타 + 피버 게이지 (때림). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDealDamage(EntityDamageByEntityEvent e) {
        if (Damage.isProbe()) return;
        if (!(e.getEntity() instanceof LivingEntity target)) return;

        Player attacker = null;
        boolean melee = false;
        if (e.getDamager() instanceof Player p) {
            attacker = p;
            melee = !Damage.isSkillDamage() && e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK;
        } else if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            attacker = p;
        }
        if (attacker == null) return;

        ItemStack item = attacker.getInventory().getItemInMainHand();
        WeaponKit kit = plugin.kits().find(item);
        if (kit == null) return;

        if (melee) {
            kit.onMeleeHit(ctx(attacker, item, kit, e), target, e);
        }
        if (!e.isCancelled() && e.getFinalDamage() > 0) {
            if (melee) Damage.recordMelee(target);
            if (Damage.isSkillDamage()) plugin.fever().onSkillHit(attacker);
            else plugin.fever().onHit(attacker);
        }
    }

    /** 스킬 피해가 실제로 들어갔는지 Damage 에 알려줌. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSkillDamagePassed(EntityDamageEvent e) {
        if (Damage.isSkillDamage() && !Damage.isProbe()) Damage.markPassed();
    }

    /** 피격 시 무기 훅 (방패/토템 등). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTakeDamage(EntityDamageEvent e) {
        if (Damage.isProbe()) return;
        if (!(e.getEntity() instanceof Player p)) return;
        ItemStack item = p.getInventory().getItemInMainHand();
        WeaponKit kit = plugin.kits().find(item);
        if (kit == null) return;
        kit.onDamaged(ctx(p, item, kit, e), e);
    }

    /** 피버 게이지 (맞음). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTakeDamageMonitor(EntityDamageEvent e) {
        if (Damage.isProbe()) return;
        if (!(e.getEntity() instanceof Player p)) return;
        if (e.getFinalDamage() <= 0) return;
        if (plugin.kits().find(p.getInventory().getItemInMainHand()) == null) return;
        plugin.fever().onHurt(p);
    }

    /** F = 궁극기. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        ItemStack item = p.getInventory().getItemInMainHand();
        WeaponKit kit = plugin.kits().find(item);
        if (kit == null) return;
        e.setCancelled(true);

        FeverManager fever = plugin.fever();
        if (fever.isFever(p)) {
            HudTask.flash(p, Component.text("이미 피버 중", NamedTextColor.GOLD), 30);
            return;
        }
        if (!fever.isFull(p)) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.6f);
            HudTask.flash(p, Component.text("궁극기 게이지 부족 (" + (int) fever.get(p) + "%)", NamedTextColor.GRAY), 30);
            return;
        }
        fever.consume(p, kit.feverDurationTicks(), kit.id());
        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.6f);
        kit.onUltimate(ctx(p, item, kit, e));
    }

    /** 접속 종료: 무기 정리만. 쿨다운·피버 게이지·마나는 유지 (재접속 쿨 초기화 악용 방지). */
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        plugin.hud().forget(p);
        lastRightTick.remove(p.getUniqueId());
        lastDropTick.remove(p.getUniqueId());
    }
}
