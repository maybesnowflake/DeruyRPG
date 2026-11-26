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
    private static final double LEFT_RANGE = 20;
    private static final int LEFT_CD = 15;
    private static final float MANA_LEFT_BASIC = 0.12f, MANA_LEFT_CARROT = 0.10f, MANA_LEFT_FUNGUS = 0.12f;
    private static final float MANA_AOE_BASIC = 0.28f, MANA_AOE_CARROT = 0.25f, MANA_AOE_FUNGUS = 0.30f;
    private static final int CD_AOE_BASIC = 120, CD_AOE_CARROT = 300, CD_AOE_FUNGUS = 300;

    private static final Color[] RAINBOW = {
            Color.fromRGB(255, 70, 70), Color.fromRGB(255, 170, 40), Color.fromRGB(255, 240, 80),
            Color.fromRGB(80, 230, 120), Color.fromRGB(70, 200, 255), Color.fromRGB(150, 90, 255),
            Color.fromRGB(255, 100, 220)};

    /** 좌클릭 같은 틱 중복 방지 (onMeleeHit + onLeftClick). */
    private final Map<UUID, Integer> lastLeftTick = new ConcurrentHashMap<>();
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
                "우  모드 변경 (기본/당근/균)",
                "좌  모드별 단일 마법",
                "   기본: 끌어오기 / 당근: 신속+재생 / 균: 구속+나약함",
                "⇧우  모드별 범위 마법",
                "   기본: 송곳니 / 당근: 힘+저항 / 균: 어둠 장판",
                "F  대마법");
        item.editMeta(m -> m.getPersistentDataContainer().set(MODE_KEY, PersistentDataType.INTEGER, BASIC));
        return item;
    }

    @Override
    public boolean usesMana() {
        return true;
    }

    @Override
    public String[] cooldownSlots() {
        return new String[]{Slot.LEFT, Slot.SHIFT_RIGHT};
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

    /** 우: 모드 변경. */
    @Override
    public void onRightClick(SkillContext ctx) {
        ctx.cancelVanilla();
        Player p = ctx.player();
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

    private static Color modeColor(int mode) {
        return switch (mode) {
            case CARROT -> Color.fromRGB(255, 170, 40);
            case FUNGUS -> Color.fromRGB(40, 200, 180);
            default -> Color.fromRGB(190, 110, 255);
        };
    }

    // ------------------------------------------------------------------ 좌 (단일)

    @Override
    public void onLeftClick(SkillContext ctx) {
        if (!firstLeftThisTick(ctx.player())) return;
        castSingle(ctx, null);
    }

    @Override
    public void onMeleeHit(SkillContext ctx, LivingEntity target, EntityDamageByEntityEvent event) {
        if (!firstLeftThisTick(ctx.player())) return;
        castSingle(ctx, target);
    }

    private boolean firstLeftThisTick(Player p) {
        int now = Bukkit.getCurrentTick();
        Integer last = lastLeftTick.put(p.getUniqueId(), now);
        return last == null || last != now;
    }

    private void castSingle(SkillContext ctx, LivingEntity hit) {
        Player p = ctx.player();
        int mode = mode(ctx);
        if (ctx.onCooldown(Slot.LEFT)) return;

        if (mode == CARROT) {
            Player ally = Targets.lookAlly(p, LEFT_RANGE);
            if (ally == null) ally = p;
            if (!ctx.tryMana(MANA_LEFT_CARROT)) return;
            ctx.tryCooldown(Slot.LEFT, LEFT_CD);
            int bonus = ctx.isFever() ? 40 : 0;
            ally.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 120 + bonus, 1, false, true, true));
            ally.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 80 + bonus, 1, false, true, true));
            if (ally != p) dustLine(p.getEyeLocation().add(0, -0.3, 0), ally.getLocation().add(0, 1, 0),
                    Color.fromRGB(255, 170, 40), 0.35);
            ally.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, ally.getLocation().add(0, 1, 0), 15, 0.4, 0.6, 0.4, 0);
            Fx.sound(ally.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.8f);
            return;
        }

        LivingEntity target = hit != null && Targets.isEnemy(p, hit) ? hit : Targets.lookTarget(p, LEFT_RANGE);
        if (target == null) return;

        if (mode == BASIC) {
            if (!ctx.tryMana(MANA_LEFT_BASIC)) return;
            ctx.tryCooldown(Slot.LEFT, LEFT_CD + 5);
            Location from = p.getEyeLocation().add(0, -0.3, 0);
            dustLine(from, target.getLocation().add(0, target.getHeight() * 0.6, 0), Color.fromRGB(220, 220, 220), 0.3);
            target.getWorld().spawnParticle(Particle.FISHING, target.getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0.02);
            if (hit == null) {
                // 허공 스윙: 약한 마법 피해 후 당기기 (피해 넉백 뒤에 속도 덮어쓰기)
                Damage.magic(p, target, ctx.isFever() ? 3 : 2);
                pullTo(p, target);
            } else {
                // 직접 타격: 평타 넉백이 끝난 다음 틱에 당기기 (타격 이벤트 안에서 중첩 피해 X)
                LivingEntity t = target;
                Bukkit.getScheduler().runTask(ctx.plugin(), () -> {
                    if (t.isValid() && p.isOnline()) pullTo(p, t);
                });
            }
            Fx.sound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, 0.9f, 0.8f);
            Fx.sound(target.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1f, 0.9f);
        } else {
            if (!ctx.tryMana(MANA_LEFT_FUNGUS)) return;
            ctx.tryCooldown(Slot.LEFT, LEFT_CD);
            int bonus = ctx.isFever() ? 30 : 0;
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80 + bonus, 1, false, true, true));
            target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 100 + bonus, 0, false, true, true));
            dustLine(p.getEyeLocation().add(0, -0.3, 0), target.getLocation().add(0, target.getHeight() * 0.6, 0),
                    Color.fromRGB(40, 200, 180), 0.35);
            target.getWorld().spawnParticle(Particle.SCULK_SOUL, target.getLocation().add(0, 1, 0), 8, 0.3, 0.5, 0.3, 0.01);
            Fx.sound(target.getLocation(), Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.8f, 1.4f);
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
        int mode = mode(ctx);
        String slot = Slot.SHIFT_RIGHT;
        if (ctx.onCooldown(slot)) return;

        switch (mode) {
            case BASIC -> {
                if (!ctx.tryMana(MANA_AOE_BASIC)) return;
                ctx.tryCooldown(slot, CD_AOE_BASIC);
                fangLine(p, ctx.isFever() ? 8 : 6);
            }
            case CARROT -> {
                if (!ctx.tryMana(MANA_AOE_CARROT)) return;
                ctx.tryCooldown(slot, CD_AOE_CARROT);
                int dur = ctx.isFever() ? 200 : 160;
                for (Player a : Targets.alliesAround(p, p.getLocation(), 6)) {
                    a.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, dur, 0, false, true, true));
                    a.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, dur, 0, false, true, true));
                    a.getWorld().spawnParticle(Particle.DUST, a.getLocation().add(0, 1, 0), 20, 0.4, 0.7, 0.4, 0,
                            new Particle.DustOptions(Color.fromRGB(255, 170, 40), 1.3f));
                }
                Location c = p.getLocation();
                dustRing(c, 6, 48, Color.fromRGB(255, 200, 60));
                dustRing(c, 3, 24, Color.fromRGB(255, 140, 40));
                Fx.sound(c, Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.5f);
                Fx.sound(c, Sound.ENTITY_EVOKER_CAST_SPELL, 0.8f, 1.3f);
            }
            default -> {
                if (!ctx.tryMana(MANA_AOE_FUNGUS)) return;
                ctx.tryCooldown(slot, CD_AOE_FUNGUS);
                debuffZone(p, sightPoint(p, 15), ctx.isFever() ? 4.5 : 3.5, ctx.isFever() ? 160 : 120);
            }
        }
    }

    /** 기본 ⇧우: 전방 일렬 송곳니. */
    private void fangLine(Player p, double dmg) {
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
        dir.normalize();
        Location base = p.getLocation();
        for (int i = 0; i < 10; i++) {
            Location at = base.clone().add(dir.clone().multiply(1.5 + i * 1.2));
            spawnFang(p, at, i * 2, dmg);
        }
        Fx.sound(base, Sound.ENTITY_EVOKER_CAST_SPELL, 1f, 1f);
        Fx.sound(base, Sound.ENTITY_EVOKER_PREPARE_ATTACK, 0.8f, 1.2f);
        p.getWorld().spawnParticle(Particle.WITCH, p.getLocation().add(0, 1.2, 0), 15, 0.4, 0.4, 0.4, 0.05);
    }

    /** 균 ⇧우: 어둠 + 발광 장판. */
    private void debuffZone(Player p, Location center, double radius, int duration) {
        UUID id = p.getUniqueId();
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
                    dustRing(center, radius, 40, Color.fromRGB(30, 170, 160));
                    dustRing(center, radius * 0.55, 20, Color.fromRGB(20, 60, 80));
                    center.getWorld().spawnParticle(Particle.SQUID_INK, center.clone().add(0, 0.3, 0), 6,
                            radius * 0.4, 0.1, radius * 0.4, 0.01);
                    center.getWorld().spawnParticle(Particle.SCULK_SOUL, center.clone().add(0, 0.3, 0), 2,
                            radius * 0.4, 0.2, radius * 0.4, 0.01);
                }
                if (t % 10 == 0) {
                    for (LivingEntity e : Targets.enemiesAround(p, center, radius)) {
                        if (Math.abs(e.getLocation().getY() - center.getY()) > 3) continue;
                        e.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, true, true));
                        e.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 60, 0, false, false, true));
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
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
        dir.normalize();
        Location center = ground(p.getLocation().add(dir.multiply(5)));

        List<LivingEntity> victims = Targets.enemiesAround(p, p.getLocation(), 8);
        for (LivingEntity e : Targets.enemiesAround(p, center, 6)) if (!victims.contains(e)) victims.add(e);

        Fx.sound(p.getLocation(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.2f, 0.8f);
        Fx.sound(center, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.2f, 0.7f);

        final int gatherTicks = 16;
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
                if (t < gatherTicks) {
                    // 끌어모으기 + 소용돌이 파티클
                    double r = 7.0 * (1 - t / (double) gatherTicks) + 0.5;
                    for (int i = 0; i < RAINBOW.length; i++) {
                        double a = t * 0.45 + Math.PI * 2 * i / RAINBOW.length;
                        Location l = center.clone().add(Math.cos(a) * r, 0.3 + t * 0.12, Math.sin(a) * r);
                        center.getWorld().spawnParticle(Particle.DUST, l, 2, 0.05, 0.05, 0.05, 0,
                                new Particle.DustOptions(RAINBOW[i], 1.6f));
                    }
                    center.getWorld().spawnParticle(Particle.WITCH, center.clone().add(0, 1, 0), 6, 0.6, 0.8, 0.6, 0.05);
                    center.getWorld().spawnParticle(Particle.ENCHANT, center.clone().add(0, 1.5, 0), 12, 1, 1, 1, 1);
                    if (t % 2 == 0) {
                        for (LivingEntity e : victims) {
                            if (!e.isValid() || e.getWorld() != center.getWorld()) continue;
                            Vector v = center.toVector().subtract(e.getLocation().toVector()).setY(0);
                            double d = v.length();
                            if (d < 0.8) {
                                e.setVelocity(new Vector(0, e.getVelocity().getY(), 0));
                                continue;
                            }
                            e.setVelocity(v.normalize().multiply(Math.min(1.3, 0.25 + d * 0.22)).setY(0.12));
                        }
                    }
                    if (t % 4 == 0) Fx.sound(center, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 0.6f + t * 0.06f);
                } else if (t == gatherTicks) {
                    detonate(p, center, ctx.isFever());
                } else {
                    holder[0].cancel();
                    untrack(id, holder[0]);
                    return;
                }
                t++;
            }
        }, 0L, 1L);
        track(id, holder[0]);
    }

    private void detonate(Player p, Location center, boolean fever) {
        World w = center.getWorld();
        double dmg = 7;
        spawnFang(p, center, 0, dmg);
        for (int i = 0; i < 6; i++) {
            double a = Math.PI * 2 * i / 6;
            spawnFang(p, center.clone().add(Math.cos(a) * 1.3, 0, Math.sin(a) * 1.3), 2, dmg);
        }
        for (int i = 0; i < 11; i++) {
            double a = Math.PI * 2 * i / 11 + 0.3;
            spawnFang(p, center.clone().add(Math.cos(a) * 2.6, 0, Math.sin(a) * 2.6), 4, dmg);
        }

        int mul = fever ? 1 : 0;
        for (LivingEntity e : Targets.enemiesAround(p, center, 4)) {
            e.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 100, 1, false, true, true));
            e.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 100, 1, false, true, true));
            e.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 160, mul, false, true, true));
        }

        for (Color c : RAINBOW) {
            w.spawnParticle(Particle.DUST, center.clone().add(0, 1, 0), 25, 1.8, 1.0, 1.8, 0,
                    new Particle.DustOptions(c, 1.8f));
        }
        for (int i = 0; i < RAINBOW.length; i++) {
            dustRing(center, 1.0 + i * 0.5, 16 + i * 4, RAINBOW[i]);
        }
        w.spawnParticle(Particle.WITCH, center.clone().add(0, 1, 0), 60, 1.5, 1, 1.5, 0.2);
        w.spawnParticle(Particle.END_ROD, center.clone().add(0, 1, 0), 30, 0.2, 0.2, 0.2, 0.25);
        w.spawnParticle(Particle.REVERSE_PORTAL, center.clone().add(0, 1, 0), 60, 1.5, 1, 1.5, 0.1);
        Fx.sound(center, Sound.ENTITY_EVOKER_FANGS_ATTACK, 1.5f, 0.8f);
        Fx.sound(center, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.5f, 0.5f);
        Fx.sound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.5f, 0.6f);
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
