package com.fsixel.deruyrpg.core;

import com.fsixel.deruyrpg.DeruyRPG;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 훈련봇: /rpg dummy  (넉백·띄우기는 받지만 스스로 움직이거나 공격하지 않음)
 * - 맞을 때마다 머리 위에 피해 숫자 (크리티컬/큰 피해는 색 다름)
 * - 이름에 체력, 최근 연속 피해 합계 표시
 * - 죽지 않음, 3초 동안 안 맞으면 체력·합계 초기화
 */
public final class TrainingDummy implements Listener {

    public static final NamespacedKey KEY = new NamespacedKey("deruyrpg", "dummy");
    private static final double MAX_HP = 1000;

    private final DeruyRPG plugin;
    private final Map<UUID, Double> combo = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastHit = new ConcurrentHashMap<>();

    public TrainingDummy(DeruyRPG plugin) {
        this.plugin = plugin;
        // 3초 무피격 시 리셋
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int now = Bukkit.getCurrentTick();
            lastHit.entrySet().removeIf(en -> {
                if (now - en.getValue() < 60) return false;
                Entity e = Bukkit.getEntity(en.getKey());
                if (e instanceof LivingEntity le && !le.isDead()) {
                    le.setHealth(MAX_HP);
                    updateName(le, 0);
                }
                combo.remove(en.getKey());
                return true;
            });
        }, 10L, 10L);
    }

    public static boolean isDummy(Entity e) {
        return e.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE);
    }

    public LivingEntity spawn(Location loc) {
        // 마네킹(플레이어형): 평화로움 난이도에서도 사라지지 않고, 넉백·띄우기는 받음
        return loc.getWorld().spawn(loc, Mannequin.class, h -> {
            h.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
            h.setImmovable(false);
            h.setSilent(true);
            h.setPersistent(true);
            h.setRemoveWhenFarAway(false);
            var maxHp = h.getAttribute(Attribute.MAX_HEALTH);
            if (maxHp != null) maxHp.setBaseValue(MAX_HP);
            h.setHealth(MAX_HP);
            h.getEquipment().setHelmet(new ItemStack(org.bukkit.Material.TARGET));
            h.getEquipment().setHelmetDropChance(0f);
            h.setDescription(Component.text("훈련봇", NamedTextColor.GRAY));
            h.setCustomNameVisible(true);
            updateName(h, 0);
        });
    }

    public int clearAll() {
        int n = 0;
        for (var w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (isDummy(e)) {
                    e.remove();
                    n++;
                }
            }
        }
        return n;
    }

    private void updateName(LivingEntity e, double comboDmg) {
        Component name = Component.text("훈련봇 ", NamedTextColor.GOLD)
                .append(Component.text("❤ " + (int) Math.ceil(e.getHealth()) + "/" + (int) MAX_HP, NamedTextColor.RED));
        if (comboDmg > 0) {
            name = name.append(Component.text("  Σ " + fmt(comboDmg), NamedTextColor.YELLOW));
        }
        e.customName(name);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (com.fsixel.deruyrpg.util.Damage.isProbe()) return;
        if (!(e.getEntity() instanceof LivingEntity le) || !isDummy(le)) return;
        double dmg = e.getFinalDamage();
        if (dmg <= 0) return;

        // 죽지 않게: 체력이 바닥나면 최대치로 되돌림 (표시 숫자는 실제 피해 그대로)
        if (dmg >= le.getHealth()) {
            e.setDamage(0);
            le.setHealth(MAX_HP);
        }

        UUID id = le.getUniqueId();
        double total = combo.merge(id, dmg, Double::sum);
        lastHit.put(id, Bukkit.getCurrentTick());
        Bukkit.getScheduler().runTask(plugin, () -> updateName(le, total));
        popup(le, dmg, e.getCause());
    }

    private void popup(LivingEntity le, double dmg, EntityDamageEvent.DamageCause cause) {
        NamedTextColor color = dmg >= 20 ? NamedTextColor.DARK_RED
                : dmg >= 10 ? NamedTextColor.GOLD
                : cause == EntityDamageEvent.DamageCause.MAGIC ? NamedTextColor.LIGHT_PURPLE
                : NamedTextColor.WHITE;
        Location at = le.getLocation().add(
                (Math.random() - 0.5) * 0.8, le.getHeight() + 0.4 + Math.random() * 0.3, (Math.random() - 0.5) * 0.8);
        TextDisplay td = le.getWorld().spawn(at, TextDisplay.class, t -> {
            t.text(Component.text(fmt(dmg), color));
            t.setBillboard(Display.Billboard.CENTER);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(true);
            t.setPersistent(false);
            t.setSeeThrough(true);
            com.fsixel.deruyrpg.util.Targets.markSummon(t); // /reload 시 코어가 제거
        });
        // 위로 떠오르다 사라짐
        new org.bukkit.scheduler.BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!td.isValid() || ++t > 16) {
                    td.remove();
                    cancel();
                    return;
                }
                td.teleport(td.getLocation().add(0, 0.06, 0));
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((int) d) : String.format("%.1f", d);
    }

    @EventHandler
    public void onTarget(EntityTargetEvent e) {
        if (isDummy(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler
    public void onCombust(EntityCombustEvent e) {
        if (isDummy(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (isDummy(e.getEntity())) {
            e.getDrops().clear();
            e.setDroppedExp(0);
        }
    }
}
