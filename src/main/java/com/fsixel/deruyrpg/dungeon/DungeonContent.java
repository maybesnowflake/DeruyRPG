package com.fsixel.deruyrpg.dungeon;

import com.fsixel.deruyrpg.DeruyRPG;
import com.fsixel.deruyrpg.util.Damage;
import com.fsixel.deruyrpg.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 던전 콘텐츠: 상점(에메랄드 거래 창) · 유물(암시장) · 휴식(회복 또는 무기 개조 2택) · 특별 이벤트(덩굴에 갇힌 탐험가).
 * 기획: docs/JUNGLE_MAP.md "확정 콘텐츠".
 */
final class DungeonContent implements Listener {

    static final double EVENT_CHANCE = 0.15; // 전투 라운드가 특별 이벤트로 바뀔 확률 (스테이지당 최대 1번)
    private static final int EVENT_SECONDS = 30;
    private static final int RESCUE_TICKS = 60; // 탐험가 옆에서 웅크린 누적 시간

    private final DeruyRPG plugin;
    private final DungeonManager dm;
    private final NamespacedKey npcKey;

    DungeonContent(DeruyRPG plugin, DungeonManager dm) {
        this.plugin = plugin;
        this.dm = dm;
        this.npcKey = new NamespacedKey(plugin, "dungeon_npc");
    }

    // ================================================================== 에메랄드

    /** 몹 처치 에메랄드: 스테이지가 높을수록 많이, 엘리트형 몹은 배율. */
    int emeraldsFor(Run run, @Nullable String customId) {
        int base = Math.max(1, run.stage);
        int mult = customId != null ? DungeonMobs.WORTH.getOrDefault(customId, 1) : 1;
        if (run.round != null && run.round.type() == RoundType.ELITE) mult = Math.max(mult, 2);
        return base * mult;
    }

    static void give(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), left);
    }

    /** 일반 아이템(이름·메타 없는 것)만, 저장 칸에서 — removeItem 이 차감하는 조건과 동일. */
    static int count(Player p, Material m) {
        ItemStack plain = new ItemStack(m);
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) if (it != null && it.isSimilar(plain)) n += it.getAmount();
        return n;
    }

    // ================================================================== NPC (상인·탐험가)

    private Villager npc(Run run, Location at, String name, boolean trader) {
        Villager v = at.getWorld().spawn(at, Villager.class, x -> {
            x.setAI(false);
            x.setInvulnerable(true);
            x.setSilent(true);
            x.setCollidable(false);
            x.setPersistent(false);
            x.setProfession(trader ? Villager.Profession.CARTOGRAPHER : Villager.Profession.NONE);
            x.customName(Component.text(name, trader ? NamedTextColor.GOLD : NamedTextColor.AQUA));
            x.setCustomNameVisible(true);
            x.getPersistentDataContainer().set(npcKey, PersistentDataType.INTEGER, run.id);
        });
        run.npcs.add(v.getUniqueId());
        return v;
    }

    /** 라운드 시작 지점 앞 3칸 (바라보는 방향). */
    private static Location front(Location spawn, double d) {
        Location l = spawn.clone();
        Vector3 dir = Vector3.yaw(l.getYaw());
        l.add(dir.x * d, 0, dir.z * d);
        l.setYaw(l.getYaw() + 180);
        return l;
    }

    private record Vector3(double x, double z) {
        static Vector3 yaw(float yaw) {
            double r = Math.toRadians(yaw);
            return new Vector3(-Math.sin(r), Math.cos(r));
        }
    }

    void cleanup(Run run) {
        for (UUID id : run.npcs) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        run.npcs.clear();
        run.event = null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onNpc(PlayerInteractEntityEvent e) {
        Integer id = e.getRightClicked().getPersistentDataContainer().get(npcKey, PersistentDataType.INTEGER);
        if (id == null) return;
        e.setCancelled(true);
        Run run = dm.runOf(e.getPlayer());
        if (run == null || run.id != id || run.round == null) return;
        if (run.round.type() == RoundType.SHOP) openShop(e.getPlayer(), run);
        else if (run.round.type() == RoundType.REST) openRest(e.getPlayer(), run);
    }

    // ================================================================== 상점

    private enum Ware {
        APPLE("황금 사과", Material.GOLDEN_APPLE, 6, 1),
        HEAL("치유 물약 (투척)", Material.SPLASH_POTION, 4, 1),
        ARROWS("화살 16개", Material.ARROW, 2, 1),
        REPAIR("장비 수리", Material.ANVIL, 5, 1),
        SWIFT("신속 물약", Material.POTION, 5, 1),
        STRENGTH("힘 물약", Material.BLAZE_POWDER, 8, 2),
        LIFE("목숨 +1 (최대 4)", Material.TOTEM_OF_UNDYING, 20, 2);

        final String label;
        final Material icon;
        final int price, minStage;

        Ware(String label, Material icon, int price, int minStage) {
            this.label = label;
            this.icon = icon;
            this.price = price;
            this.minStage = minStage;
        }
    }

    private enum Relic {
        TIDE("수문 부적", Material.HEART_OF_THE_SEA, "물에 들어가면 흡수 하트 2칸 (20초마다)"),
        HUNTER("사냥꾼 표식", Material.SPECTRAL_ARROW, "1.5초 가만히 있으면 다음 원거리 피해 +50%"),
        MOSS("이끼 심장", Material.MOSS_BLOCK, "몹 처치 시 체력 1칸 회복 (3초마다)"),
        VINE("덩굴 장갑", Material.VINE, "근접 공격 15% 확률로 적 둔화 1.5초");

        final String label, desc;
        final Material icon;

        Relic(String label, Material icon, String desc) {
            this.label = label;
            this.icon = icon;
            this.desc = desc;
        }
    }

    private static final int RELIC_PRICE = 15;

    private static final class Gui implements InventoryHolder {
        final Run run;
        final String kind; // shop | relic | rest
        final Map<Integer, String> slots = new HashMap<>();
        Inventory inv;

        Gui(Run run, String kind) {
            this.run = run;
            this.kind = kind;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inv;
        }
    }

    private boolean relicShop(Run run) {
        return run.round.tags().contains("relic") || run.round.id().contains("black");
    }

    void startShop(Run run) {
        npc(run, front(run.round.spawn(), 3), relicShop(run) ? "암시장 상인" : "떠돌이 상인", true);
        dm.hintAll(run, "상인을 우클릭해 거래 (에메랄드) — 준비되면 /dg next");
    }

    void openShop(Player p, Run run) {
        Gui g = new Gui(run, relicShop(run) ? "relic" : "shop");
        g.inv = Bukkit.createInventory(g, 27, Component.text(g.kind.equals("relic") ? "암시장 — 유물" : "떠돌이 상인"));
        int slot = 10;
        if (g.kind.equals("relic")) {
            for (Relic r : Relic.values()) {
                g.inv.setItem(slot, icon(r.icon, r.label, RELIC_PRICE, r.desc, "1인 1개 (이번 판 동안)"));
                g.slots.put(slot++, "relic:" + r.name());
            }
            if (run.stage >= 2) {
                g.inv.setItem(slot, icon(Material.TOTEM_OF_UNDYING, "목숨 +1 (최대 4)", 25));
                g.slots.put(slot, "ware:LIFE25");
            }
        } else {
            for (Ware w : Ware.values()) {
                if (run.stage < w.minStage) continue;
                g.inv.setItem(slot, icon(w.icon, w.label, w.price));
                g.slots.put(slot++, "ware:" + w.name());
            }
        }
        g.inv.setItem(22, icon(Material.EMERALD, "내 에메랄드: " + count(p, Material.EMERALD), -1));
        p.openInventory(g.inv);
    }

    private static ItemStack icon(Material m, String name, int price, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        if (price >= 0) l.add(Component.text("가격: 에메랄드 " + price, NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Gui) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Gui g)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        String what = g.slots.get(e.getRawSlot());
        if (what == null || dm.runOf(p) != g.run) return;
        if (g.kind.equals("rest")) {
            chooseRest(p, g.run, what);
            p.closeInventory();
            return;
        }
        if (g.run.round == null || g.run.round.type() != RoundType.SHOP) {
            p.closeInventory();
            return;
        }
        if (what.startsWith("relic:")) buyRelic(p, g.run, Relic.valueOf(what.substring(6)));
        else if (what.equals("ware:LIFE25")) buyWare(p, g.run, Ware.LIFE, 25);
        else {
            Ware w = Ware.valueOf(what.substring(5));
            buyWare(p, g.run, w, w.price);
        }
        p.getOpenInventory().getTopInventory().setItem(22, icon(Material.EMERALD, "내 에메랄드: " + count(p, Material.EMERALD), -1));
    }

    private boolean pay(Player p, int price) {
        if (count(p, Material.EMERALD) < price) {
            p.sendActionBar(Component.text("에메랄드가 부족합니다 (" + price + "개 필요)", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return false;
        }
        var left = p.getInventory().removeItem(new ItemStack(Material.EMERALD, price));
        if (!left.isEmpty()) { // 전액 차감 못 함 → 되돌리고 실패
            int missing = 0;
            for (ItemStack it : left.values()) missing += it.getAmount();
            if (price - missing > 0) give(p, new ItemStack(Material.EMERALD, price - missing));
            p.sendActionBar(Component.text("결제 실패 — 일반 에메랄드가 부족합니다", NamedTextColor.RED));
            return false;
        }
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1f);
        return true;
    }

    private void buyWare(Player p, Run run, Ware w, int price) {
        if (w == Ware.LIFE) {
            if (!dm.canBuyLife(p)) {
                p.sendActionBar(Component.text("목숨을 더 살 수 없습니다 (스테이지 1 또는 최대치)", NamedTextColor.RED));
                return;
            }
            if (!pay(p, price)) return;
            dm.buyLife(p);
            p.sendActionBar(Component.text("목숨 +1 → " + run.lives(p.getUniqueId()), NamedTextColor.GREEN));
            return;
        }
        if (w == Ware.REPAIR && !needsRepair(p)) {
            p.sendActionBar(Component.text("수리할 장비가 없습니다", NamedTextColor.GRAY));
            return;
        }
        if (!pay(p, price)) return;
        switch (w) {
            case APPLE -> give(p, new ItemStack(Material.GOLDEN_APPLE));
            case HEAL -> give(p, potion(Material.SPLASH_POTION, PotionType.STRONG_HEALING));
            case ARROWS -> give(p, new ItemStack(Material.ARROW, 16));
            case SWIFT -> give(p, potion(Material.POTION, PotionType.LONG_SWIFTNESS));
            case STRENGTH -> give(p, potion(Material.POTION, PotionType.STRENGTH));
            case REPAIR -> repair(p);
            default -> {
            }
        }
    }

    private static ItemStack potion(Material m, PotionType type) {
        ItemStack it = new ItemStack(m);
        if (it.getItemMeta() instanceof PotionMeta pm) {
            pm.setBasePotionType(type);
            it.setItemMeta(pm);
        }
        return it;
    }

    private static boolean needsRepair(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getItemMeta() instanceof Damageable d && d.hasDamage()) return true;
        }
        return false;
    }

    private static void repair(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getItemMeta() instanceof Damageable d && d.hasDamage()) {
                d.setDamage(0);
                it.setItemMeta(d);
            }
        }
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1.2f);
    }

    // ================================================================== 유물

    private void buyRelic(Player p, Run run, Relic r) {
        if (run.relic.containsKey(p.getUniqueId())) {
            p.sendActionBar(Component.text("유물은 1인 1개입니다 (보유: " + Relic.valueOf(run.relic.get(p.getUniqueId())).label + ")", NamedTextColor.RED));
            return;
        }
        if (!pay(p, RELIC_PRICE)) return;
        run.relic.put(p.getUniqueId(), r.name());
        dm.hintAll(run, p.getName() + " — 유물 [" + r.label + "] 획득: " + r.desc);
    }

    private boolean hasRelic(Player p, Relic r) {
        Run run = dm.runOf(p);
        return run != null && r.name().equals(run.relic.get(p.getUniqueId()));
    }

    /** 0.5초마다: 수문 부적·사냥꾼 표식 충전. */
    void relicTick(Run run, long now) {
        for (UUID u : run.aliveMembers()) {
            Player p = Bukkit.getPlayer(u);
            String rel = run.relic.get(u);
            if (p == null || rel == null) continue;
            if (rel.equals("TIDE") && p.isInWater() && now >= run.relicCd.getOrDefault(u, 0L)) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 160, 0));
                p.getWorld().playSound(p.getLocation(), Sound.ITEM_BUCKET_FILL, 0.8f, 1.4f);
                run.relicCd.put(u, now + 20_000);
            }
            if (rel.equals("HUNTER")) {
                Location last = run.lastPos.get(u);
                Location cur = p.getLocation();
                run.lastPos.put(u, cur);
                if (last != null && last.getWorld() == cur.getWorld() && last.distanceSquared(cur) < 0.01) {
                    int still = run.stillTicks.merge(u, 10, Integer::sum);
                    if (still == 30) {
                        p.sendActionBar(Component.text("사냥꾼 표식 충전 — 다음 원거리 +50%", NamedTextColor.GOLD));
                        p.playSound(p.getLocation(), Sound.ITEM_CROSSBOW_LOADING_END, 1f, 1.5f);
                    }
                } else if (run.stillTicks.getOrDefault(u, 0) < 30) {
                    run.stillTicks.put(u, 0);
                }
            }
        }
    }

    // ================================================================== 휴식 (회복 또는 무기 개조)

    /** 무기별 개조 2택: POWER 피해 +15% / LEECH 피해의 8% 회복 / GUARD 받는 피해 -12% (그 무기를 들고 있을 때, 이번 판 동안). */
    private static String[] modsFor(String kit) {
        return switch (kit) {
            case "shield", "rod", "mace", "trident" -> new String[]{"POWER", "GUARD"};
            default -> new String[]{"POWER", "LEECH"};
        };
    }

    private static String modLabel(String mod) {
        return switch (mod) {
            case "POWER" -> "날 세우기: 이 무기 피해 +15%";
            case "LEECH" -> "흡혈 덩굴: 이 무기 피해의 8% 회복";
            case "GUARD" -> "이끼 갑주: 이 무기를 들면 받는 피해 -12%";
            default -> mod;
        };
    }

    void startRest(Run run) {
        npc(run, front(run.round.spawn(), 3), "성소의 이끼 정령", false);
        dm.hintAll(run, "휴식 — 정령을 우클릭해 회복 또는 무기 개조 선택 (1번) · 준비되면 /dg next");
    }

    void openRest(Player p, Run run) {
        if (run.restChosen.contains(run.round.id() + ":" + p.getUniqueId())) {
            p.sendActionBar(Component.text("이번 휴식에서는 이미 선택했습니다", NamedTextColor.GRAY));
            return;
        }
        Gui g = new Gui(run, "rest");
        g.inv = Bukkit.createInventory(g, 27, Component.text("성소 — 하나만 선택"));
        g.inv.setItem(11, icon(Material.GLISTERING_MELON_SLICE, "회복", -1, "체력 전부 + 재생 10초"));
        g.slots.put(11, "HEAL");
        String kit = Items.kitId(p.getInventory().getItemInMainHand());
        if (kit != null) {
            String[] mods = modsFor(kit);
            g.inv.setItem(13, icon(Material.MOSS_BLOCK, modLabel(mods[0]), -1, "무기: " + kit, "이번 판 동안 유지"));
            g.slots.put(13, kit + ":" + mods[0]);
            g.inv.setItem(15, icon(Material.VINE, modLabel(mods[1]), -1, "무기: " + kit, "이번 판 동안 유지"));
            g.slots.put(15, kit + ":" + mods[1]);
        } else {
            g.inv.setItem(15, icon(Material.BARRIER, "무기 개조", -1, "개조할 무기를 손에 들고 다시 우클릭"));
        }
        p.openInventory(g.inv);
    }

    private void chooseRest(Player p, Run run, String what) {
        if (run.round == null || run.round.type() != RoundType.REST) return;
        String key = run.round.id() + ":" + p.getUniqueId();
        if (!run.restChosen.add(key)) return;
        if (what.equals("HEAL")) {
            AttributeInstance hp = p.getAttribute(Attribute.MAX_HEALTH);
            p.setHealth(hp != null ? hp.getValue() : 20);
            p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, 1));
            p.sendActionBar(Component.text("회복했습니다", NamedTextColor.GREEN));
            return;
        }
        String[] kv = what.split(":");
        run.mods.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).put(kv[0], kv[1]);
        p.playSound(p.getLocation(), Sound.BLOCK_SMITHING_TABLE_USE, 1f, 1f);
        dm.hintAll(run, p.getName() + " — " + modLabel(kv[1]) + " (" + kv[0] + ")");
    }

    private @Nullable String modOf(Player p) {
        Run run = dm.runOf(p);
        if (run == null) return null;
        Map<String, String> m = run.mods.get(p.getUniqueId());
        if (m == null) return null;
        String kit = Items.kitId(p.getInventory().getItemInMainHand());
        return kit != null ? m.get(kit) : null;
    }

    // ================================================================== 피해 훅 (유물·개조)

    private static @Nullable Player attacker(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p) return p;
        if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    /** NORMAL: 보스 체력 임계값 보정(BossManager, HIGH)보다 먼저 증폭해야 50% 클램프·사망 처리가 유지됨. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDeal(EntityDamageByEntityEvent e) {
        if (Damage.isProbe() || e.getEntity() instanceof Player || !(e.getEntity() instanceof LivingEntity target)) return;
        Player p = attacker(e);
        if (p == null || dm.runOf(p) == null) return;
        Run run = dm.runOf(p);
        String mod = modOf(p);
        if ("POWER".equals(mod)) e.setDamage(e.getDamage() * 1.15);
        boolean ranged = e.getDamager() instanceof Projectile;
        String rel = run.relic.get(p.getUniqueId());
        if ("HUNTER".equals(rel) && ranged && run.stillTicks.getOrDefault(p.getUniqueId(), 0) >= 30) {
            e.setDamage(e.getDamage() * 1.5);
            run.stillTicks.put(p.getUniqueId(), 0);
            target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0), 12, 0.3, 0.4, 0.3, 0.1);
        }
        if ("VINE".equals(rel) && !ranged && ThreadLocalRandom.current().nextDouble() < 0.15) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 1));
            target.getWorld().spawnParticle(Particle.BLOCK, target.getLocation().add(0, 0.3, 0), 8, 0.3, 0.1, 0.3, Material.VINE.createBlockData());
        }
        if ("LEECH".equals(mod)) {
            AttributeInstance hp = p.getAttribute(Attribute.MAX_HEALTH);
            double max = hp != null ? hp.getValue() : 20;
            p.setHealth(Math.min(max, p.getHealth() + e.getFinalDamage() * 0.08));
        }
    }

    /** GUARD 개조는 던전 매니저의 치명타 판정(HIGHEST)보다 먼저 적용. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTake(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || Damage.isProbe()) return;
        if ("GUARD".equals(modOf(p))) e.setDamage(e.getDamage() * 0.88);
    }

    @EventHandler
    public void onKill(EntityDeathEvent e) {
        Player p = e.getEntity().getKiller();
        if (p == null || e.getEntity() instanceof Player || !hasRelic(p, Relic.MOSS)) return;
        Run run = dm.runOf(p);
        long now = System.currentTimeMillis();
        if (now < run.relicCd.getOrDefault(p.getUniqueId(), 0L)) return;
        run.relicCd.put(p.getUniqueId(), now + 3000);
        AttributeInstance hp = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(Math.min(hp != null ? hp.getValue() : 20, p.getHealth() + 2));
        p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 2, 0), 1);
    }

    // ================================================================== 특별 이벤트: 덩굴에 갇힌 탐험가

    static final class EventState {
        UUID explorer;
        long endsAt;
        int progress;
        long nextWave;
    }

    void startEvent(Run run) {
        EventState s = new EventState();
        Villager v = npc(run, front(run.round.spawn(), 6), "덩굴에 갇힌 탐험가", false);
        s.explorer = v.getUniqueId();
        s.endsAt = System.currentTimeMillis() + EVENT_SECONDS * 1000L;
        s.nextWave = System.currentTimeMillis() + 3000;
        run.event = s;
        dm.hintAll(run, "특별 이벤트! 탐험가 옆에서 웅크려(Shift) 덩굴을 끊으세요 — " + EVENT_SECONDS + "초 (실패해도 진행)");
        v.getWorld().playSound(v.getLocation(), Sound.ENTITY_VILLAGER_HURT, 1f, 1f);
    }

    /** 0.5초마다. 끝나면 true (매니저가 라운드 클리어). */
    boolean eventTick(Run run) {
        EventState s = run.event;
        if (s == null) return true;
        Entity v = Bukkit.getEntity(s.explorer);
        long now = System.currentTimeMillis();
        if (v == null) return finishEvent(run, false);
        v.getWorld().spawnParticle(Particle.BLOCK, v.getLocation().add(0, 1, 0), 6, 0.4, 0.6, 0.4, Material.VINE.createBlockData());
        for (UUID u : run.aliveMembers()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isSneaking() && p.getWorld() == v.getWorld() && p.getLocation().distanceSquared(v.getLocation()) <= 6.25) {
                s.progress += 10;
                p.getWorld().playSound(v.getLocation(), Sound.BLOCK_VINE_BREAK, 0.8f, 1.2f);
            }
        }
        int pct = Math.min(100, s.progress * 100 / RESCUE_TICKS);
        long left = Math.max(0, (s.endsAt - now) / 1000);
        for (UUID u : run.members) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendActionBar(Component.text("구조 " + pct + "% · 남은 시간 " + left + "초", NamedTextColor.AQUA));
        }
        if (s.progress >= RESCUE_TICKS) return finishEvent(run, true);
        if (now >= s.endsAt) return finishEvent(run, false);
        if (now >= s.nextWave) {
            s.nextWave = now + 8000;
            dm.spawnExtra(run, "vine_zombie", 2);
        }
        return false;
    }

    private boolean finishEvent(Run run, boolean saved) {
        if (saved) {
            dm.hintAll(run, "탐험가 구조 성공! 보급품: 황금 사과 1 + 에메랄드 " + (4 * Math.max(1, run.stage)));
            for (UUID u : run.aliveMembers()) {
                Player p = Bukkit.getPlayer(u);
                if (p == null) continue;
                give(p, new ItemStack(Material.GOLDEN_APPLE));
                give(p, new ItemStack(Material.EMERALD, 4 * Math.max(1, run.stage)));
            }
        } else {
            dm.hintAll(run, "탐험가를 구하지 못했습니다... 길은 계속됩니다");
        }
        cleanup(run);
        return true;
    }
}
