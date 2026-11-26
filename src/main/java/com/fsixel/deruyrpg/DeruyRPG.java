package com.fsixel.deruyrpg;

import com.fsixel.deruyrpg.command.RpgCommand;
import com.fsixel.deruyrpg.core.Cooldowns;
import com.fsixel.deruyrpg.core.FeverManager;
import com.fsixel.deruyrpg.core.HudTask;
import com.fsixel.deruyrpg.core.InputListener;
import com.fsixel.deruyrpg.core.KitRegistry;
import com.fsixel.deruyrpg.core.ManaManager;
import com.fsixel.deruyrpg.core.TrainingDummy;
import com.fsixel.deruyrpg.weapons.axe.AxeKit;
import com.fsixel.deruyrpg.weapons.bow.BowKit;
import com.fsixel.deruyrpg.weapons.crossbow.CrossbowKit;
import com.fsixel.deruyrpg.weapons.mace.MaceKit;
import com.fsixel.deruyrpg.weapons.rod.RodKit;
import com.fsixel.deruyrpg.weapons.shield.ShieldKit;
import com.fsixel.deruyrpg.weapons.spear.SpearKit;
import com.fsixel.deruyrpg.weapons.sword.SwordKit;
import com.fsixel.deruyrpg.weapons.trident.TridentKit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class DeruyRPG extends JavaPlugin {

    private static DeruyRPG instance;

    private KitRegistry kits;
    private Cooldowns cooldowns;
    private FeverManager fever;
    private ManaManager mana;
    private TrainingDummy dummy;
    private HudTask hud;

    public static DeruyRPG get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        cooldowns = new Cooldowns();
        fever = new FeverManager();
        fever.load(getConfig().getConfigurationSection("fever"));
        mana = new ManaManager();
        mana.load(getConfig().getConfigurationSection("mana"));
        kits = new KitRegistry(this);

        // 무기 등록 — 무기 세션은 여기 손대지 않음 (각자 클래스 안만 수정)
        kits.register(new SwordKit());
        kits.register(new SpearKit());
        kits.register(new MaceKit());
        kits.register(new AxeKit());
        kits.register(new ShieldKit());
        kits.register(new TridentKit());
        kits.register(new BowKit());
        kits.register(new CrossbowKit());
        kits.register(new RodKit());

        getServer().getPluginManager().registerEvents(new InputListener(this), this);
        dummy = new TrainingDummy(this);
        getServer().getPluginManager().registerEvents(dummy, this);
        hud = new HudTask(this);
        hud.runTaskTimer(this, HudTask.PERIOD, HudTask.PERIOD);

        PluginCommand cmd = getCommand("rpg");
        if (cmd != null) {
            RpgCommand exec = new RpgCommand(this);
            cmd.setExecutor(exec);
            cmd.setTabCompleter(exec);
        }
        getLogger().info("DeruyRPG 활성화 — 무기 " + kits.all().size() + "종");
    }

    public KitRegistry kits() { return kits; }
    public Cooldowns cooldowns() { return cooldowns; }
    public FeverManager fever() { return fever; }
    public ManaManager mana() { return mana; }
    public TrainingDummy dummy() { return dummy; }
    public HudTask hud() { return hud; }

    /** /reload·종료 시 정리: 무기 해제 처리, 기절 해제, 연출·소환 엔티티 제거. */
    @Override
    public void onDisable() {
        if (hud != null) {
            for (org.bukkit.entity.Player p : getServer().getOnlinePlayers()) {
                try {
                    hud.forget(p);
                } catch (Throwable t) {
                    getLogger().warning("종료 정리 오류: " + t);
                }
            }
        }
        if (kits != null) {
            for (com.fsixel.deruyrpg.api.WeaponKit k : kits.all()) {
                try {
                    k.onPluginDisable();
                } catch (Throwable t) {
                    getLogger().warning("[" + k.id() + "] 종료 정리 오류: " + t);
                }
            }
        }
        com.fsixel.deruyrpg.util.Cc.unstunAll();
        int removed = 0;
        for (org.bukkit.World w : getServer().getWorlds()) {
            for (org.bukkit.entity.Entity en : w.getEntities()) {
                if (com.fsixel.deruyrpg.util.Targets.isSummon(en)) {
                    en.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) getLogger().info("연출·소환 엔티티 " + removed + "개 제거");
    }
}
