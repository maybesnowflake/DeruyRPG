package com.fsixel.deruyrpg.dungeon;

import com.fsixel.deruyrpg.DeruyRPG;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * 정글 챕터 커스텀 몹 (바닐라 엔티티 + 장비 + 변형 능력). dungeon.yml 웨이브에 "vine_zombie:3" 처럼 씀.
 * 규칙: 위험한 행동은 반드시 소리·입자로 먼저 예고 (docs/BOSS.md 가독성 기준).
 */
final class DungeonMobs implements Listener {

    /** 몹 id → 처치 에메랄드 배율 (엘리트는 4) */
    static final Map<String, Integer> WORTH = Map.ofEntries(
            Map.entry("vine_zombie", 1), Map.entry("jungle_spider", 1), Map.entry("spore_shroom", 1),
            Map.entry("poison_sniper", 1), Map.entry("swamp_slime", 1), Map.entry("drowned_harpooner", 1),
            Map.entry("swamp_witch", 4), Map.entry("jungle_axeman", 1), Map.entry("jungle_crossbowman", 1),
            Map.entry("jungle_shaman", 2), Map.entry("moss_boar", 2), Map.entry("creaking_warden", 4));

    private final DeruyRPG plugin;
    private final NamespacedKey key;
    /** 몹 → 다음 능력 사용 가능 틱 */
    private final Map<UUID, Long> nextSkill = new HashMap<>();
    /** 플레이어 → 덩굴 구속 재적용 가능 틱 */
    private final Map<UUID, Long> rootGrace = new HashMap<>();
    private final Set<UUID> tracked = new HashSet<>();
    private long tick;

    DungeonMobs(DeruyRPG plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "dungeon_mob");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 5, 5);
    }

    static boolean has(String id) {
        return WORTH.containsKey(id);
    }

    @Nullable String idOf(Entity e) {
        return e.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    /** 커스텀 몹 소환. 모르는 id 면 null. */
    @Nullable LivingEntity spawn(String id, Location at) {
        LivingEntity le = switch (id) {
            case "vine_zombie" -> zombieLike(at, EntityType.ZOMBIE, "덩굴 좀비", Material.MOSS_BLOCK, Color.fromRGB(0x4E7A2A), null);
            case "jungle_spider" -> named(at.getWorld().spawn(at, Spider.class), "정글 거미");
            case "spore_shroom" -> {
                LivingEntity z = zombieLike(at, EntityType.ZOMBIE, "포자 버섯", Material.RED_MUSHROOM_BLOCK, Color.fromRGB(0x9C3B2F), null);
                attr(z, Attribute.MOVEMENT_SPEED, 0.17);
                attr(z, Attribute.SCALE, 0.8);
                yield z;
            }
            case "poison_sniper" -> {
                Bogged b = at.getWorld().spawn(at, Bogged.class);
                b.getEquipment().setHelmet(leather(Material.LEATHER_HELMET, Color.fromRGB(0x3E5F24)));
                attr(b, Attribute.MOVEMENT_SPEED, 0.0); // 제자리 저격수
                attr(b, Attribute.FOLLOW_RANGE, 24);
                yield named(b, "독 저격수");
            }
            case "swamp_slime" -> {
                Slime s = at.getWorld().spawn(at, Slime.class);
                s.setSize(2);
                yield named(s, "늪 슬라임");
            }
            case "drowned_harpooner" -> {
                Drowned d = at.getWorld().spawn(at, Drowned.class);
                d.getEquipment().setItemInMainHand(new ItemStack(Material.TRIDENT));
                d.getEquipment().setItemInMainHandDropChance(0);
                yield named(d, "익사체 작살병");
            }
            case "swamp_witch" -> {
                Witch w = at.getWorld().spawn(at, Witch.class);
                w.getEquipment().setItemInMainHand(new ItemStack(Material.WARPED_FUNGUS_ON_A_STICK));
                attr(w, Attribute.MAX_HEALTH, 50);
                w.setHealth(50);
                yield named(w, "늪 마녀");
            }
            case "jungle_axeman" -> {
                Vindicator v = at.getWorld().spawn(at, Vindicator.class);
                v.getEquipment().setItemInMainHand(new ItemStack(Material.STONE_AXE));
                v.getEquipment().setChestplate(leather(Material.LEATHER_CHESTPLATE, Color.fromRGB(0x6B8E23)));
                dropNothing(v.getEquipment());
                yield named(v, "정글 우민 도끼병");
            }
            case "jungle_crossbowman" -> {
                Pillager p = at.getWorld().spawn(at, Pillager.class);
                p.getEquipment().setChestplate(leather(Material.LEATHER_CHESTPLATE, Color.fromRGB(0x6B8E23)));
                dropNothing(p.getEquipment());
                yield named(p, "정글 우민 쇠뇌병");
            }
            case "jungle_shaman" -> {
                Evoker ev = at.getWorld().spawn(at, Evoker.class);
                yield named(ev, "정글 주술사");
            }
            case "moss_boar" -> {
                Hoglin h = at.getWorld().spawn(at, Hoglin.class);
                h.setImmuneToZombification(true);
                h.setIsAbleToBeHunted(false);
                yield named(h, "이끼 멧돼지");
            }
            case "creaking_warden" -> {
                Creaking c = at.getWorld().spawn(at, Creaking.class);
                attr(c, Attribute.MAX_HEALTH, 60);
                c.setHealth(60);
                yield named(c, "Creaking 파수꾼");
            }
            default -> null;
        };
        if (le == null) return null;
        le.getPersistentDataContainer().set(key, PersistentDataType.STRING, id);
        tracked.add(le.getUniqueId());
        nextSkill.put(le.getUniqueId(), tick + 40 + new Random().nextInt(40));
        return le;
    }

    // ------------------------------------------------------------------ 생성 도우미

    private LivingEntity zombieLike(Location at, EntityType type, String name, Material head, Color armor, @Nullable Material hand) {
        Zombie z = (Zombie) at.getWorld().spawnEntity(at, type);
        z.setAdult();
        z.setShouldBurnInDay(false);
        EntityEquipment eq = z.getEquipment();
        eq.setHelmet(new ItemStack(head));
        eq.setChestplate(leather(Material.LEATHER_CHESTPLATE, armor));
        if (hand != null) eq.setItemInMainHand(new ItemStack(hand));
        dropNothing(eq);
        return named(z, name);
    }

    private static LivingEntity named(LivingEntity e, String name) {
        e.customName(Component.text(name, NamedTextColor.GREEN));
        e.setCustomNameVisible(false);
        return e;
    }

    private static ItemStack leather(Material m, Color c) {
        ItemStack it = new ItemStack(m);
        if (it.getItemMeta() instanceof LeatherArmorMeta lm) {
            lm.setColor(c);
            it.setItemMeta(lm);
        }
        return it;
    }

    private static void dropNothing(EntityEquipment eq) {
        eq.setHelmetDropChance(0);
        eq.setChestplateDropChance(0);
        eq.setItemInMainHandDropChance(0);
    }

    private static void attr(LivingEntity e, Attribute a, double v) {
        AttributeInstance ai = e.getAttribute(a);
        if (ai != null) ai.setBaseValue(v);
    }

    // ------------------------------------------------------------------ 능력 (5틱마다)

    private void tick() {
        tick += 5;
        Iterator<UUID> it = tracked.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof Mob m) || !m.isValid() || m.isDead()) {
                it.remove();
                nextSkill.remove(id);
                continue;
            }
            String type = idOf(m);
            if (type == null) continue;
            if (type.equals("drowned_harpooner") && m.isInWater()) {
                m.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 15, 1, false, false));
            }
            LivingEntity target = m.getTarget();
            if (!(target instanceof Player p) || p.getGameMode() == GameMode.SPECTATOR) continue;
            if (tick < nextSkill.getOrDefault(id, 0L)) continue;
            double d = m.getLocation().distance(p.getLocation());
            switch (type) {
                case "jungle_spider" -> {
                    if (d < 3 || d > 10) continue;
                    windup(m, Sound.ENTITY_SPIDER_AMBIENT, Particle.ITEM_COBWEB, 12, () -> web(m, p));
                    nextSkill.put(id, tick + 100);
                }
                case "spore_shroom" -> {
                    if (d > 3) continue;
                    nextSkill.put(id, Long.MAX_VALUE);
                    attr(m, Attribute.MOVEMENT_SPEED, 0);
                    attr(m, Attribute.SCALE, 1.3);
                    windup(m, Sound.ENTITY_CREEPER_PRIMED, Particle.SPORE_BLOSSOM_AIR, 30, () -> burst(m));
                }
                case "swamp_witch" -> {
                    if (d > 12) continue;
                    windup(m, Sound.ENTITY_WITCH_CELEBRATE, Particle.WITCH, 16, () -> witchCast(m, p));
                    nextSkill.put(id, tick + 160);
                }
                case "moss_boar" -> {
                    if (d < 4 || d > 14) continue;
                    windup(m, Sound.ENTITY_HOGLIN_ANGRY, Particle.CLOUD, 15, () -> charge(m, p));
                    nextSkill.put(id, tick + 120);
                }
                default -> nextSkill.put(id, Long.MAX_VALUE);
            }
        }
    }

    /** 예고(소리+입자) 후 실행. 예고 중엔 멈춤 (구속 효과 — 청크가 언로드돼도 시간이 지나면 자연 해제). */
    private void windup(Mob m, Sound sound, Particle particle, int ticks, Runnable then) {
        m.getWorld().playSound(m.getLocation(), sound, 1.2f, 0.8f);
        m.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 10, false, false));
        new org.bukkit.scheduler.BukkitRunnable() {
            int t;

            @Override
            public void run() {
                if (!m.isValid() || m.isDead()) {
                    cancel();
                    return;
                }
                if (t % 3 == 0) {
                    m.getWorld().spawnParticle(particle, m.getLocation().add(0, m.getHeight() + 0.3, 0), 6, 0.3, 0.2, 0.3, 0.01);
                }
                if (++t >= ticks) {
                    cancel();
                    then.run();
                }
            }
        }.runTaskTimer(plugin, 1, 1);
    }

    private void web(Mob m, Player p) {
        Snowball s = m.launchProjectile(Snowball.class, p.getEyeLocation().subtract(m.getEyeLocation()).toVector().normalize().multiply(1.3));
        s.setItem(new ItemStack(Material.COBWEB));
        s.getPersistentDataContainer().set(key, PersistentDataType.STRING, "web");
    }

    private void burst(Mob m) {
        Location l = m.getLocation();
        AreaEffectCloud c = l.getWorld().spawn(l, AreaEffectCloud.class);
        c.setRadius(3f);
        c.setDuration(80);
        c.setRadiusPerTick(-0.02f);
        c.setColor(Color.fromRGB(0x7FBF3F));
        c.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 60, 0), true);
        l.getWorld().playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.6f);
        l.getWorld().spawnParticle(Particle.SPORE_BLOSSOM_AIR, l.add(0, 1, 0), 40, 1.5, 0.8, 1.5, 0.02);
        m.setHealth(0);
    }

    private void witchCast(Mob m, Player p) {
        for (Entity e : m.getNearbyEntities(8, 4, 8)) {
            if (e instanceof Mob ally && idOf(ally) != null && ally != m) {
                ally.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 120, 0));
                ally.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 120, 0));
                ally.getWorld().spawnParticle(Particle.WITCH, ally.getLocation().add(0, 1.5, 0), 8, 0.3, 0.3, 0.3, 0);
            }
        }
        // 낚싯대 끌어오기
        Vector pull = m.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
        if (pull.lengthSquared() > 1) {
            p.setVelocity(pull.normalize().multiply(0.9).setY(0.35));
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1f, 0.8f);
        }
    }

    private void charge(Mob m, Player p) {
        Vector dir = p.getLocation().toVector().subtract(m.getLocation().toVector()).setY(0).normalize();
        m.setVelocity(dir.multiply(1.6).setY(0.15));
        m.getWorld().playSound(m.getLocation(), Sound.ENTITY_HOGLIN_ATTACK, 1.2f, 0.9f);
        UUID target = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!m.isValid()) return;
            Player tp = Bukkit.getPlayer(target);
            boolean hit = tp != null && tp.getWorld().equals(m.getWorld()) && tp.getLocation().distanceSquared(m.getLocation()) < 4;
            if (hit) {
                tp.damage(6, m);
            } else { // 빗나가면 잠깐 기절 → 공격 기회
                m.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 6, false, true));
                m.getWorld().spawnParticle(Particle.CRIT, m.getLocation().add(0, 1.2, 0), 12, 0.3, 0.2, 0.3, 0);
            }
        }, 12);
    }

    // ------------------------------------------------------------------ 이벤트

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Entity d = e.getDamager();
        if ("vine_zombie".equals(idOf(d))) {
            long now = tick;
            if (rootGrace.getOrDefault(p.getUniqueId(), 0L) <= now && !p.hasPotionEffect(PotionEffectType.SLOWNESS)) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20, 2));
                rootGrace.put(p.getUniqueId(), now + 60 + 20);
                p.getWorld().spawnParticle(Particle.BLOCK, p.getLocation().add(0, 0.3, 0), 10, 0.3, 0.1, 0.3, Material.VINE.createBlockData());
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onProjectile(ProjectileHitEvent e) {
        if (!"web".equals(e.getEntity().getPersistentDataContainer().get(key, PersistentDataType.STRING))) return;
        if (e.getHitEntity() instanceof Player p && !p.hasPotionEffect(PotionEffectType.SLOWNESS)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 3));
            p.getWorld().spawnParticle(Particle.ITEM_COBWEB, p.getLocation().add(0, 1, 0), 15, 0.4, 0.5, 0.4, 0);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (!"swamp_slime".equals(idOf(e.getEntity()))) return;
        Location l = e.getEntity().getLocation();
        AreaEffectCloud c = l.getWorld().spawn(l, AreaEffectCloud.class);
        c.setRadius(2f);
        c.setDuration(60);
        c.setColor(Color.fromRGB(0x4F7A28));
        c.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 40, 0), true);
    }

    /** 주술사는 송곳니만 (소환수 Vex 없음). */
    @EventHandler(ignoreCancelled = true)
    public void onVex(CreatureSpawnEvent e) {
        if (e.getEntityType() != EntityType.VEX) return;
        for (Entity near : e.getEntity().getNearbyEntities(4, 4, 4)) {
            if ("jungle_shaman".equals(idOf(near))) {
                e.setCancelled(true);
                return;
            }
        }
    }
}
