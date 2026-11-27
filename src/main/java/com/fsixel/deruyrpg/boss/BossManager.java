package com.fsixel.deruyrpg.boss;

import com.fsixel.deruyrpg.DeruyRPG;
import com.fsixel.deruyrpg.boss.moss.MossRig;
import com.fsixel.deruyrpg.boss.moss.MossWarden;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.SlimeSplitEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** 던전 보스(이끼 수문장) 관리: 소환·정리·히트박스 이벤트 연결. */
public final class BossManager implements Listener {

    private static final String SECTION = "boss.moss-warden";

    private final DeruyRPG plugin;
    private final BossSettings settings = new BossSettings();
    private final List<MossWarden> wardens = new ArrayList<>();

    public BossManager(DeruyRPG plugin) {
        this.plugin = plugin;
        reloadSettings();
        Bukkit.getScheduler().runTaskTimer(plugin, () -> wardens.removeIf(w -> !w.isAlive()), 20L, 20L);
    }

    public BossSettings settings() {
        return settings;
    }

    public void reloadSettings() {
        settings.load(plugin.getConfig().getConfigurationSection(SECTION));
    }

    public void saveSettings() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection(SECTION);
        if (sec == null) sec = plugin.getConfig().createSection(SECTION);
        settings.save(sec);
        plugin.saveConfig();
    }

    public MossWarden spawn(Location loc, boolean phase2) {
        MossWarden w = MossWarden.spawn(plugin, settings, loc, phase2);
        wardens.add(w);
        return w;
    }

    /** 살아있는 첫 번째 보스 (없으면 null). */
    public @Nullable MossWarden active() {
        for (MossWarden w : wardens) if (w.isAlive()) return w;
        return null;
    }

    public List<MossWarden> all() {
        return List.copyOf(wardens);
    }

    public void removeAll() {
        for (MossWarden w : List.copyOf(wardens)) {
            try {
                w.remove();
            } catch (Throwable t) {
                plugin.getLogger().warning("보스 정리 오류: " + t);
            }
        }
        wardens.clear();
    }

    /** 모든 월드에서 보스 태그가 붙은 엔티티 수 (처치 후 정리 확인용). */
    public int countTagged() {
        int n = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) if (MossRig.isTagged(e)) n++;
        }
        return n;
    }

    /** 남아 있는 보스 태그 엔티티를 전부 제거. 제거한 개수. */
    public int removeTagged() {
        int n = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (MossRig.isTagged(e)) {
                    e.remove();
                    n++;
                }
            }
        }
        return n;
    }

    private @Nullable MossWarden byHitbox(Entity e) {
        for (MossWarden w : wardens) {
            if (w.isAlive() && w.hitbox() != null && w.hitbox().equals(e)) return w;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        if (com.fsixel.deruyrpg.util.Damage.isProbe()) return;
        MossWarden w = byHitbox(e.getEntity());
        if (w != null) {
            w.onDamage(e);
            return;
        }
        // 보스 독침이 졸개를 맞혀도 피해 없음 (졸개가 보스에게 반격하지 않게)
        if (MossRig.isTagged(e.getEntity()) && e.getDamageSource().getCausingEntity() != null
                && MossRig.isTagged(e.getDamageSource().getCausingEntity())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onTarget(org.bukkit.event.entity.EntityTargetEvent e) {
        // 졸개끼리·보스를 노리지 않음
        if (e.getTarget() != null && MossRig.isTagged(e.getEntity()) && MossRig.isTagged(e.getTarget())) e.setCancelled(true);
    }

    @EventHandler
    public void onSplit(SlimeSplitEvent e) {
        if (MossRig.isTagged(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (MossRig.isTagged(e.getEntity())) {
            e.getDrops().clear();
            e.setDroppedExp(0);
        }
    }

    @EventHandler
    public void onCombust(EntityCombustEvent e) {
        if (MossRig.isTagged(e.getEntity())) e.setCancelled(true);
    }
}
