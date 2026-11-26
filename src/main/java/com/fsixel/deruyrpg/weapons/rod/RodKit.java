package com.fsixel.deruyrpg.weapons.rod;

import com.fsixel.deruyrpg.DeruyRPG;
import com.fsixel.deruyrpg.api.SkillContext;
import com.fsixel.deruyrpg.api.WeaponKit;
import com.fsixel.deruyrpg.util.Damage;
import com.fsixel.deruyrpg.util.Fx;
import com.fsixel.deruyrpg.util.Items;
import com.fsixel.deruyrpg.util.Targets;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 낚싯대 (마법사) — 스킬 명세: docs/SKILLS.md 의 "낚싯대" 항목.
 * 우클릭으로 기본 → 당근 → 균 모드 순환 (손 아이템 종류 자체가 바뀜).
 */
public final class RodKit implements WeaponKit, Listener {

    private static final NamespacedKey MODE_KEY = new NamespacedKey("deruyrpg", "rod_mode");
    /** 이 무기가 소환한 송곳니의 피해량 (PDC, double). */
    private static final NamespacedKey FANG_DMG_KEY = new NamespacedKey("deruyrpg", "rod_fang_dmg");

    private static final int BASIC = 0, CARROT = 1, FUNGUS = 2;
    private static final Material[] MODE_MAT = {
            Material.FISHING_ROD, Material.CARROT_ON_A_STICK, Material.WARPED_FUNGUS_ON_A_STICK};
    private static final String[] MODE_NAME = {"기본", "당근", "균"};
    private static final NamedTextColor[] MODE_COLOR = {
            NamedTextColor.LIGHT_PURPLE, NamedTextColor.GOLD, NamedTextColor.DARK_AQUA};

    // 수치
    private static final double SINGLE_RANGE = 20;
    private static final int CD_SINGLE_BASIC = 20, CD_SINGLE_CARROT = 15, CD_SINGLE_FUNGUS = 15;
    private static final float MANA_SINGLE_BASIC = 0.12f, MANA_SINGLE_CARROT = 0.10f, MANA_SINGLE_FUNGUS = 0.12f;
    private static final float MANA_AOE_BASIC = 0.28f, MANA_AOE_CARROT = 0.25f, MANA_AOE_FUNGUS = 0.30f;
    private static final int CD_AOE_BASIC = 120, CD_AOE_CARROT = 300, CD_AOE_FUNGUS = 300;
    /** 피버 중: 쿨다운 없음 + 최대 마나 300%, 마나 회복 5배 (ManaManager.boost). */
    private static final float FEVER_MANA_MAX = 3f, FEVER_MANA_REGEN = 5f;
    /** 우클릭 직후 이 틱 이내의 좌클릭은 무시 (바닐라 낚싯대 우클릭 시 팔 휘두름 → 가짜 LEFT_CLICK_AIR). */
    private static final int RIGHT_LEFT_GUARD_TICKS = 2;

    private static final double FANG_DMG = 8;
    private static final double ZONE_RADIUS = 6;
    private static final int ZONE_TICKS = 120;
    private static final double ULT_RADIUS = 6;
    private static final double ULT_INSTANT_DMG = 5;
    private static final double ULT_FANG_DMG = 5;

    /** 모드별 쿨다운 슬롯 이름. */
    private static final String[] SLOT_SINGLE = {"우·기본", "우·당근", "우·균"};
    private static final String[] SLOT_AOE = {"⇧우·기본", "⇧우·당근", "⇧우·균"};

    private static final Color[] RAINBOW = {
            Color.fromRGB(255, 70, 70), Color.fromRGB(255, 170, 40), Color.fromRGB(255, 240, 80),
            Color.fromRGB(80, 230, 120), Color.fromRGB(70, 200, 255), Color.fromRGB(150, 90, 255),
            Color.fromRGB(255, 100, 220)};

    /** 좌클릭 같은 틱 중복 방지 (onMeleeHit + onLeftClick). */
    private final Map<UUID, Integer> lastLeftTick = new ConcurrentHashMap<>();
    /** 마지막 우클릭(⇧우 포함) 틱. 가짜 좌클릭 판별용. */
    private final Map<UUID, Integer> lastRightTick = new ConcurrentHashMap<>();
    /** 장판·궁극기 등 진행 중 작업. onUnequip 에서 취소. */
    private final Map<UUID, List<BukkitTask>> tasks = new ConcurrentHashMap<>();

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
        ItemStack item = Items.weapon(Material.FISHING_ROD, id(), "낚싯대", NamedTextColor.LIGHT_PURPLE,
                "P  경험치 바 = 마나",
                "좌  모드 변경 (기본/당근/균)",
                "우  모드별 단일 마법",
                "   기본: 끌어오기 / 당근: 신속+재생 II / 균: 나약함+위더",
                "⇧우  모드별 범위 마법",
                "   기본: 송곳니 두 줄 / 당근: 포화+힘 / 균: 어둠·구속 장판",
                "F  대마법: 휘감는 송곳니 소용돌이",
                "피버: 쿨다운 없음, 최대 마나 300%·마나 회복 5배");
        item.editMeta(m -> m.getPersistentDataContainer().set(MODE_KEY, PersistentDataType.INTEGER, BASIC));
        return item;
    }

    @Override
    public boolean usesMana() {
        return true;
    }

    @Override
    public String[] cooldownSlots() {
        return new String[]{SLOT_SINGLE[BASIC], SLOT_AOE[BASIC]};
    }

    @Override
    public String[] cooldownSlots(Player player) {
        int m = mode(player.getInventory().getItemInMainHand());
        return new String[]{SLOT_SINGLE[m], SLOT_AOE[m]};
    }

    @Override
    public Component hudExtra(SkillContext ctx) {
        int m = mode(ctx);
        Component c = Component.text("✦ " + MODE_NAME[m], MODE_COLOR[m]);
        if (ctx.isFever()) c = c.append(Component.text(" 피버: 쿨X 마나 " + Math.round(ctx.mana().get(ctx.player()) * 100) + "%/"
                + Math.round(ctx.mana().max(ctx.player()) * 100) + "% 회복x5", NamedTextColor.GOLD));
        return c;
    }

    // ------------------------------------------------------------------ 모드

    private int mode(ItemStack item) {
        if (item != null && item.hasItemMeta()) {
            Integer m = item.getItemMeta().getPersistentDataContainer().get(MODE_KEY, PersistentDataType.INTEGER);
            if (m != null && m >= 0 && m < 3) return m;
        }
        if (item != null) {
            for (int i = 0; i < MODE_MAT.length; i++) if (item.getType() == MODE_MAT[i]) return i;
        }
        return BASIC;
    }

    private int mode(SkillContext ctx) {
        return mode(ctx.player().getInventory().getItemInMainHand());
    }

    /**
     * 좌(허공/블록): 모드 변경.
     * 바닐라 낚싯대(FISHING_ROD)는 우클릭 시 클라이언트가 팔을 휘두르고(swing 패킷), 서버는 이를
     * LEFT_CLICK_AIR 로도 처리한다 → 우클릭할 때마다 모드가 바뀌던 원인.
     * 그래서 최근 우클릭(±2틱) 근처의 좌클릭은 무시하고, 패킷 순서가 뒤바뀐 경우까지 막도록
     * 실제 전환은 1틱 뒤에 다시 확인 후 수행.
     */
    @Override
    public void onLeftClick(SkillContext ctx) {
        Player p = ctx.player();
        if (!firstLeftThisTick(p)) return;
        int leftTick = Bukkit.getCurrentTick();
        if (recentRight(p, leftTick)) return;
        Bukkit.getScheduler().runTaskLater(ctx.plugin(), () -> {
            if (!p.isOnline() || recentRight(p, leftTick)) return;
            switchMode(p);
        }, 1L);
    }

    /** leftTick 기준 ±2틱 안에 우클릭이 있었는지. */
    private boolean recentRight(Player p, int leftTick) {
        Integer r = lastRightTick.get(p.getUniqueId());
        return r != null && Math.abs(r - leftTick) <= RIGHT_LEFT_GUARD_TICKS;
    }

    private void markRight(Player p) {
        lastRightTick.put(p.getUniqueId(), Bukkit.getCurrentTick());
    }

    /** 좌(엔티티 타격): 평타 피해는 그대로, 모드 변경만. */
    @Override
    public void onMeleeHit(SkillContext ctx, LivingEntity target, EntityDamageByEntityEvent event) {
        if (!firstLeftThisTick(ctx.player())) return;
        Player p = ctx.player();
        // 타격 이벤트 처리 중에 주손 아이템을 바꾸지 않도록 다음 틱에 전환
        Bukkit.getScheduler().runTask(ctx.plugin(), () -> {
            if (p.isOnline()) switchMode(p);
        });
    }

    private boolean firstLeftThisTick(Player p) {
        int now = Bukkit.getCurrentTick();
        Integer last = lastLeftTick.put(p.getUniqueId(), now);
        return last == null || last != now;
    }

    private void switchMode(Player p) {
        ItemStack cur = p.getInventory().getItemInMainHand();
        if (!id().equals(Items.kitId(cur))) return;
        int next = (mode(cur) + 1) % 3;

        ItemStack changed = cur.withType(MODE_MAT[next]);
        changed.editMeta(m -> {
            m.getPersistentDataContainer().set(MODE_KEY, PersistentDataType.INTEGER, next);
            m.itemName(Component.text("낚싯대 · " + MODE_NAME[next], MODE_COLOR[next])
                    .decoration(TextDecoration.BOLD, true));
        });
        Items.tag(changed, id());
        p.getInventory().setItemInMainHand(changed);

        Location loc = p.getLocation();
        switch (next) {
            case BASIC -> Fx.sound(loc, Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.8f, 1.2f);
            case CARROT -> Fx.sound(loc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.4f);
            default -> Fx.sound(loc, Sound.BLOCK_SCULK_CATALYST_BLOOM, 0.9f, 1.3f);
        }
        Fx.sound(loc, Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.2f);
        p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 14, 0.35, 0.5, 0.35, 0,
                new Particle.DustOptions(modeColor(next), 1.2f));
        p.showTitle(Title.title(Component.empty(),
                Component.text("✦ " + MODE_NAME[next] + " 모드", MODE_COLOR[next]),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(700), Duration.ofMillis(200))));
    }

    /**
     * 쿨다운·마나 처리. 대상 확인 등 발동 조건을 모두 통과한 뒤에만 호출할 것.
     * 피버 중엔 쿨다운 없이 마나만, 평소엔 쿨·마나 둘 다 OK 일 때만 둘 다 소모 (하나라도 실패하면 아무것도 안 씀).
     */
    private boolean pay(SkillContext ctx, String slot, float mana, int cd) {
        if (ctx.isFever()) return ctx.tryMana(mana);
        return ctx.tryCooldownAndMana(slot, cd, mana);
    }

    private static Color modeColor(int mode) {
        return switch (mode) {
            case CARROT -> Color.fromRGB(255, 170, 40);
            case FUNGUS -> Color.fromRGB(40, 200, 180);
            default -> Color.fromRGB(190, 110, 255);
        };
    }

    // ------------------------------------------------------------------ 우 (단일)

    @Override
    public void onRightClick(SkillContext ctx) {
        ctx.cancelVanilla();
        Player p = ctx.player();
        markRight(p);
        int mode = mode(ctx);

        if (mode == CARROT) {
            Player ally = Targets.lookAlly(p, SINGLE_RANGE);
            if (ally == null) ally = p;
            if (!pay(ctx, SLOT_SINGLE[CARROT], MANA_SINGLE_CARROT, CD_SINGLE_CARROT)) return;
            ally.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 120, 1, false, true, true));
            ally.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1, false, true, true));
            if (ally != p) dustLine(p.getEyeLocation().add(0, -0.3, 0), ally.getLocation().add(0, 1, 0),
                    Color.fromRGB(255, 170, 40), 0.35);
            ally.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, ally.getLocation().add(0, 1, 0), 15, 0.4, 0.6, 0.4, 0);
            ally.getWorld().spawnParticle(Particle.HEART, ally.getLocation().add(0, 1.8, 0), 3, 0.3, 0.2, 0.3, 0);
            Fx.sound(ally.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.8f);
            return;
        }

        LivingEntity target = Targets.lookTarget(p, SINGLE_RANGE);
        if (target == null) return;

        if (mode == BASIC) {
            if (!pay(ctx, SLOT_SINGLE[BASIC], MANA_SINGLE_BASIC, CD_SINGLE_BASIC)) return;
            Location from = p.getEyeLocation().add(0, -0.3, 0);
            dustLine(from, target.getLocation().add(0, target.getHeight() * 0.6, 0), Color.fromRGB(220, 220, 220), 0.3);
            target.getWorld().spawnParticle(Particle.FISHING, target.getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0.02);
            // 약한 마법 피해 후 당기기 (피해 넉백 뒤에 속도 덮어쓰기)
            Damage.magic(p, target, 2);
            pullTo(p, target);
            Fx.sound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, 0.9f, 0.8f);
            Fx.sound(target.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1f, 0.9f);
        } else {
            if (!pay(ctx, SLOT_SINGLE[FUNGUS], MANA_SINGLE_FUNGUS, CD_SINGLE_FUNGUS)) return;
            target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 100, 0, false, true, true));
            target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 100, 1, false, true, true));
            dustLine(p.getEyeLocation().add(0, -0.3, 0), target.getLocation().add(0, target.getHeight() * 0.6, 0),
                    Color.fromRGB(40, 200, 180), 0.35);
            target.getWorld().spawnParticle(Particle.SCULK_SOUL, target.getLocation().add(0, 1, 0), 8, 0.3, 0.5, 0.3, 0.01);
            target.getWorld().spawnParticle(Particle.SMOKE, target.getLocation().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.01);
            Fx.sound(target.getLocation(), Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.8f, 1.4f);
            Fx.sound(target.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 0.4f, 1.6f);
        }
    }

    private static void pullTo(Player p, LivingEntity target) {
        if (target.getWorld() != p.getWorld()) return;
        Vector v = p.getLocation().toVector().subtract(target.getLocation().toVector());
        double dist = v.length();
        if (dist <= 1.5) return;
        v.setY(0).normalize().multiply(Math.min(2.2, 0.25 + dist * 0.13)).setY(0.3 + Math.min(0.25, dist * 0.02));
        target.setVelocity(v);
    }

    // ------------------------------------------------------------------ ⇧우 (범위)

    @Override
    public void onShiftRightClick(SkillContext ctx) {
        ctx.cancelVanilla();
        Player p = ctx.player();
        markRight(p);
        int mode = mode(ctx);

        switch (mode) {
            case BASIC -> {
                if (!pay(ctx, SLOT_AOE[BASIC], MANA_AOE_BASIC, CD_AOE_BASIC)) return;
                fangLines(p, FANG_DMG);
            }
            case CARROT -> {
                if (!pay(ctx, SLOT_AOE[CARROT], MANA_AOE_CARROT, CD_AOE_CARROT)) return;
                int dur = 160;
                for (Player a : Targets.alliesAround(p, p.getLocation(), 6)) {
                    a.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, dur, 0, false, true, true));
                    a.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, 20, 0, false, true, true));
                    a.getWorld().spawnParticle(Particle.DUST, a.getLocation().add(0, 1, 0), 20, 0.4, 0.7, 0.4, 0,
                            new Particle.DustOptions(Color.fromRGB(255, 170, 40), 1.3f));
                }
                Location c = p.getLocation();
                dustRing(c, 6, 48, Color.fromRGB(255, 200, 60));
                dustRing(c, 3, 24, Color.fromRGB(255, 140, 40));
                Fx.sound(c, Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.5f);
                Fx.sound(c, Sound.ENTITY_GENERIC_EAT, 0.8f, 1.2f);
                Fx.sound(c, Sound.ENTITY_EVOKER_CAST_SPELL, 0.8f, 1.3f);
            }
            default -> {
                if (!pay(ctx, SLOT_AOE[FUNGUS], MANA_AOE_FUNGUS, CD_AOE_FUNGUS)) return;
                debuffZone(p, sightPoint(p, 15), ZONE_RADIUS, ZONE_TICKS);
            }
        }
    }

    /** 기본 ⇧우: 전방으로 나란히 두 줄 송곳니. */
    private void fangLines(Player p, double dmg) {
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
        dir.normalize();
        Vector side = new Vector(-dir.getZ(), 0, dir.getX()).multiply(0.9);
        Location base = p.getLocation();
        for (int i = 0; i < 10; i++) {
            Location at = base.clone().add(dir.clone().multiply(1.5 + i * 1.2));
            spawnFang(p, at.clone().add(side), i * 2, dmg);
            spawnFang(p, at.clone().subtract(side), i * 2, dmg);
        }
        Fx.sound(base, Sound.ENTITY_EVOKER_CAST_SPELL, 1f, 1f);
        Fx.sound(base, Sound.ENTITY_EVOKER_PREPARE_ATTACK, 0.8f, 1.2f);
        p.getWorld().spawnParticle(Particle.WITCH, p.getLocation().add(0, 1.2, 0), 15, 0.4, 0.4, 0.4, 0.05);
    }

    /** 균 ⇧우: 어둠 + 발광 장판, 들어온 적에게 구속 II 6초 (적마다 1회). */
    private void debuffZone(Player p, Location center, double radius, int duration) {
        UUID id = p.getUniqueId();
        Set<UUID> slowed = new HashSet<>();
        Fx.sound(center, Sound.BLOCK_SCULK_CATALYST_BLOOM, 1.2f, 0.8f);
        Fx.sound(p.getLocation(), Sound.ENTITY_ILLUSIONER_PREPARE_BLINDNESS, 0.8f, 1.1f);
        final BukkitTask[] holder = new BukkitTask[1];
        holder[0] = Bukkit.getScheduler().runTaskTimer(DeruyRPG.get(), new Runnable() {
            int t = 0;

            @Override
            public void run() {
                if (t >= duration || !p.isOnline() || center.getWorld() != p.getWorld()) {
                    holder[0].cancel();
                    untrack(id, holder[0]);
                    return;
                }
                if (t % 5 == 0) {
                    dustRing(center, radius, 60, Color.fromRGB(30, 170, 160));
                    dustRing(center, radius * 0.55, 30, Color.fromRGB(20, 60, 80));
                    center.getWorld().spawnParticle(Particle.SQUID_INK, center.clone().add(0, 0.3, 0), 10,
                            radius * 0.4, 0.1, radius * 0.4, 0.01);
                    center.getWorld().spawnParticle(Particle.SCULK_SOUL, center.clone().add(0, 0.3, 0), 3,
                            radius * 0.4, 0.2, radius * 0.4, 0.01);
                }
                if (t % 10 == 0) {
                    for (LivingEntity e : Targets.enemiesAround(p, center, radius)) {
                        if (Math.abs(e.getLocation().getY() - center.getY()) > 3) continue;
                        e.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, true, true));
                        e.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 60, 0, false, false, true));
                        if (slowed.add(e.getUniqueId())) {
                            e.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 120, 1, false, true, true));
                        }
                    }
                }
                t++;
            }
        }, 0L, 1L);
        track(id, holder[0]);
    }

    // ------------------------------------------------------------------ F 대마법

    @Override
    public void onUltimate(SkillContext ctx) {
        Player p = ctx.player();
        UUID id = p.getUniqueId();
        // 피버 동안 최대 마나 300%, 회복 5배 (시작 시 가득 채움)
        ctx.mana().boost(p, FEVER_MANA_MAX, FEVER_MANA_REGEN, feverDurationTicks());
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
        dir.normalize();
        Location center = ground(p.getLocation().add(dir.multiply(5)));
        World w = center.getWorld();

        List<LivingEntity> victims = Targets.enemiesAround(p, center, ULT_RADIUS);
        for (LivingEntity e : Targets.enemiesAround(p, p.getLocation(), ULT_RADIUS)) {
            if (!victims.contains(e)) victims.add(e);
        }
        // 발동 즉시 피해
        for (LivingEntity e : victims) {
            Damage.magic(p, e, ULT_INSTANT_DMG);
            w.spawnParticle(Particle.WITCH, e.getLocation().add(0, 1, 0), 8, 0.3, 0.5, 0.3, 0.05);
        }

        Fx.sound(p.getLocation(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.2f, 0.8f);
        Fx.sound(center, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.2f, 0.7f);
        Fx.sound(center, Sound.ENTITY_BREEZE_WIND_BURST, 1f, 0.6f);

        final int gatherTicks = 16;
        final int rings = 7;          // 반경 6 → 0
        final int ringGap = 4;        // 틱
        final int fangEnd = gatherTicks + rings * ringGap + 6;
        final int pillarTicks = 14;
        final BukkitTask[] holder = new BukkitTask[1];
        holder[0] = Bukkit.getScheduler().runTaskTimer(DeruyRPG.get(), new Runnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || center.getWorld() != p.getWorld()) {
                    holder[0].cancel();
                    untrack(id, holder[0]);
                    return;
                }
                // 휘감는 나선 소용돌이 (전체 지속)
                if (t < fangEnd) vortex(center, t, t < gatherTicks
                        ? ULT_RADIUS * (1 - t / (double) gatherTicks * 0.5)
                        : ULT_RADIUS * 0.5 * (1 - (t - gatherTicks) / (double) (fangEnd - gatherTicks)) + 0.4);

                if (t < gatherTicks) {
                    // 끌어모으기
                    if (t % 2 == 0) {
                        for (LivingEntity e : victims) {
                            if (!e.isValid() || e.getWorld() != center.getWorld()) continue;
                            Vector v = center.toVector().subtract(e.getLocation().toVector()).setY(0);
                            double d = v.length();
                            if (d < 0.8) {
                                e.setVelocity(new Vector(0, e.getVelocity().getY(), 0));
                                continue;
                            }
                            // 접선 성분을 섞어 휘감기는 느낌
                            Vector tan = new Vector(-v.getZ(), 0, v.getX()).normalize().multiply(0.25);
                            e.setVelocity(v.normalize().multiply(Math.min(1.2, 0.25 + d * 0.2)).add(tan).setY(0.12));
                        }
                    }
                    if (t % 4 == 0) Fx.sound(center, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 0.6f + t * 0.06f);
                } else if (t < gatherTicks + rings * ringGap) {
                    // 바깥 원부터 안쪽으로 조여오는 송곳니
                    int k = t - gatherTicks;
                    if (k % ringGap == 0) {
                        int ring = k / ringGap;                 // 0..6
                        double r = ULT_RADIUS - ring;           // 6..0
                        fangRing(p, center, r);
                        Fx.sound(center, Sound.ENTITY_EVOKER_FANGS_ATTACK, 1f, 0.7f + ring * 0.1f);
                    }
                } else if (t == fangEnd) {
                    finish(p, center);
                } else if (t > fangEnd + pillarTicks) {
                    holder[0].cancel();
                    untrack(id, holder[0]);
                    return;
                }
                if (t > fangEnd) pillar(center, t - fangEnd);
                t++;
            }
        }, 0L, 1L);
        track(id, holder[0]);
    }

    /** 나선형 소용돌이: 여러 갈래가 회전하며 위로 감겨 올라감. */
    private void vortex(Location center, int t, double r) {
        World w = center.getWorld();
        int arms = RAINBOW.length;
        for (int i = 0; i < arms; i++) {
            for (int h = 0; h < 4; h++) {
                double y = 0.2 + h * 0.7;
                double rr = r * (1 - h * 0.15);
                double a = t * 0.5 + Math.PI * 2 * i / arms + h * 0.6;
                Location l = center.clone().add(Math.cos(a) * rr, y, Math.sin(a) * rr);
                w.spawnParticle(Particle.DUST, l, 1, 0.03, 0.03, 0.03, 0,
                        new Particle.DustOptions(RAINBOW[i], 1.4f));
            }
        }
        if (t % 2 == 0) {
            w.spawnParticle(Particle.WITCH, center.clone().add(0, 1, 0), 6, r * 0.3, 0.8, r * 0.3, 0.05);
            w.spawnParticle(Particle.ENCHANT, center.clone().add(0, 1.5, 0), 12, 1, 1, 1, 1);
            w.spawnParticle(Particle.REVERSE_PORTAL, center.clone().add(0, 0.5, 0), 6, r * 0.4, 0.2, r * 0.4, 0.02);
        }
    }

    private void fangRing(Player p, Location center, double r) {
        if (r < 0.5) {
            spawnFang(p, center, 0, ULT_FANG_DMG);
            return;
        }
        int n = Math.max(6, (int) Math.round(Math.PI * 2 * r / 1.3));
        double off = r * 0.4;
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n + off;
            spawnFang(p, center.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), 0, ULT_FANG_DMG);
        }
        dustRing(center, r, n * 2, RAINBOW[(int) Math.round(r) % RAINBOW.length]);
    }

    /** 마지막: 디버프 + 중심에서 솟아오르는 기둥. */
    private void finish(Player p, Location center) {
        World w = center.getWorld();
        for (LivingEntity e : Targets.enemiesAround(p, center, ULT_RADIUS)) {
            e.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 100, 1, false, true, true));
            e.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 100, 1, false, true, true));
            e.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 160, 0, false, true, true));
        }
        for (int i = 0; i < RAINBOW.length; i++) {
            dustRing(center, 1.0 + i * 0.75, 16 + i * 5, RAINBOW[i]);
        }
        w.spawnParticle(Particle.WITCH, center.clone().add(0, 1, 0), 60, 1.5, 1, 1.5, 0.2);
        w.spawnParticle(Particle.END_ROD, center.clone().add(0, 1, 0), 30, 0.2, 0.2, 0.2, 0.25);
        w.spawnParticle(Particle.FLASH, center.clone().add(0, 1, 0), 1, 0, 0, 0, 0, Color.WHITE);
        Fx.sound(center, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.5f, 0.5f);
        Fx.sound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.5f, 0.6f);
        Fx.sound(center, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.5f, 0.6f);
        Fx.sound(center, Sound.BLOCK_BEACON_POWER_SELECT, 1.2f, 0.7f);
        Fx.sound(center, Sound.ENTITY_EVOKER_CAST_SPELL, 1.2f, 0.6f);
    }

    /** 중심에서 위로 솟구치는 기둥 (k = 경과 틱). */
    private void pillar(Location center, int k) {
        World w = center.getWorld();
        double top = Math.min(12, k * 1.2);
        for (double y = 0; y <= top; y += 0.4) {
            double a = y * 1.3 + k * 0.4;
            double r = 0.5 + 0.15 * Math.sin(y);
            Color c = RAINBOW[(int) (y * 2) % RAINBOW.length];
            w.spawnParticle(Particle.DUST, center.clone().add(Math.cos(a) * r, y, Math.sin(a) * r), 1, 0, 0, 0, 0,
                    new Particle.DustOptions(c, 1.3f));
            w.spawnParticle(Particle.DUST, center.clone().add(-Math.cos(a) * r, y, -Math.sin(a) * r), 1, 0, 0, 0, 0,
                    new Particle.DustOptions(c, 1.3f));
        }
        w.spawnParticle(Particle.END_ROD, center.clone().add(0, top, 0), 4, 0.15, 0.2, 0.15, 0.05);
        w.spawnParticle(Particle.WITCH, center.clone().add(0, top * 0.5, 0), 4, 0.3, top * 0.3, 0.3, 0.02);
        if (k == 1) Fx.sound(center, Sound.ENTITY_BREEZE_WIND_BURST, 1.3f, 1.2f);
    }

    // ------------------------------------------------------------------ 송곳니

    private void spawnFang(Player owner, Location at, int delay, double dmg) {
        Location loc = ground(at);
        EvokerFangs fang = loc.getWorld().spawn(loc, EvokerFangs.class, f -> {
            f.setOwner(owner);
            f.setAttackDelay(delay);
            f.setPersistent(false);
            Targets.markSummon(f);
            f.getPersistentDataContainer().set(FANG_DMG_KEY, PersistentDataType.DOUBLE, dmg);
        });
        Bukkit.getScheduler().runTaskLater(DeruyRPG.get(), () -> {
            if (fang.isValid()) fang.remove();
        }, delay + 40L);
    }

    /** 이 무기의 송곳니 피해 → 바닐라 피해 취소 후 Damage.magic 으로 대체 (아군·자신 보호). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFangDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof EvokerFangs fang)) return;
        PersistentDataContainer pdc = fang.getPersistentDataContainer();
        Double dmg = pdc.get(FANG_DMG_KEY, PersistentDataType.DOUBLE);
        if (dmg == null) return;
        e.setCancelled(true);
        if (!(fang.getOwner() instanceof Player owner) || !owner.isOnline()) return;
        if (!(e.getEntity() instanceof LivingEntity target)) return;
        if (!Targets.isEnemy(owner, target)) return;
        Damage.magic(owner, target, dmg);
    }

    /** 안전장치: 이 무기로는 바닐라 찌가 나가지 않게. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.FISHING) return;
        if (id().equals(Items.kitId(e.getPlayer().getInventory().getItemInMainHand()))) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ 정리

    @Override
    public void onUnequip(Player player) {
        UUID id = player.getUniqueId();
        lastLeftTick.remove(id);
        lastRightTick.remove(id);
        List<BukkitTask> list = tasks.remove(id);
        if (list != null) {
            synchronized (list) {
                for (BukkitTask t : list) t.cancel();
            }
        }
    }

    private void track(UUID id, BukkitTask task) {
        List<BukkitTask> list = tasks.computeIfAbsent(id, k -> new ArrayList<>());
        synchronized (list) {
            list.add(task);
        }
    }

    private void untrack(UUID id, BukkitTask task) {
        List<BukkitTask> list = tasks.get(id);
        if (list == null) return;
        synchronized (list) {
            list.remove(task);
            if (list.isEmpty()) tasks.remove(id, list);
        }
    }

    // ------------------------------------------------------------------ 유틸

    /** 시선 끝 지점 (블록에 막히면 그 위, 아니면 range 칸 앞 바닥). */
    private static Location sightPoint(Player p, double range) {
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTraceBlocks(eye, eye.getDirection(), range,
                org.bukkit.FluidCollisionMode.NEVER, true);
        Location hit = r != null ? r.getHitPosition().toLocation(p.getWorld())
                : eye.clone().add(eye.getDirection().multiply(range));
        return ground(hit);
    }

    /** 지점 아래(최대 6칸)의 바닥 위 위치. 없으면 그대로. */
    private static Location ground(Location at) {
        Location l = at.clone();
        l.setY(Math.floor(l.getY()) + 1);
        for (int i = 0; i < 8; i++) {
            Block below = l.getBlock().getRelative(0, -1, 0);
            if (!l.getBlock().getType().isSolid() && below.getType().isSolid()) {
                return l;
            }
            l.add(0, -1, 0);
        }
        return at.clone();
    }

    private static void dustLine(Location from, Location to, Color color, double step) {
        Vector d = to.toVector().subtract(from.toVector());
        double len = d.length();
        if (len < 1e-6) return;
        d.normalize().multiply(step);
        Location cur = from.clone();
        Particle.DustOptions opt = new Particle.DustOptions(color, 0.8f);
        for (double x = 0; x <= len; x += step) {
            from.getWorld().spawnParticle(Particle.DUST, cur, 1, 0, 0, 0, 0, opt);
            cur.add(d);
        }
    }

    private static void dustRing(Location center, double radius, int points, Color color) {
        Particle.DustOptions opt = new Particle.DustOptions(color, 1.2f);
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            Location l = center.clone().add(Math.cos(a) * radius, 0.15, Math.sin(a) * radius);
            center.getWorld().spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, opt);
        }
    }
}
