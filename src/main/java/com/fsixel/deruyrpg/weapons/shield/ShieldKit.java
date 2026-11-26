package com.fsixel.deruyrpg.weapons.shield;

import com.fsixel.deruyrpg.DeruyRPG;
import com.fsixel.deruyrpg.api.SkillContext;
import com.fsixel.deruyrpg.api.WeaponKit;
import com.fsixel.deruyrpg.core.HudTask;
import com.fsixel.deruyrpg.util.Cc;
import com.fsixel.deruyrpg.util.Damage;
import com.fsixel.deruyrpg.util.Fx;
import com.fsixel.deruyrpg.util.Items;
import com.fsixel.deruyrpg.util.Targets;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 방패 (탱커) — 주손. 좌클릭으로 방패 ↔ 불사의 토템 스왑.
 * 폼과 부활 충전 상태는 아이템 PDC 에 저장 (무기를 바꿔 들어도 유지).
 */
public final class ShieldKit implements WeaponKit, Listener {

    private static final String ID = "shield";
    private static final NamespacedKey FORM_KEY = new NamespacedKey("deruyrpg", "shield_form");
    private static final NamespacedKey CHARGE_KEY = new NamespacedKey("deruyrpg", "shield_revive");
    private static final String FORM_SHIELD = "shield";
    private static final String FORM_TOTEM = "totem";

    private static final String[] LORE = {
            "좌  방패 ↔ 불사의 토템 스왑",
            "P  철벽(반사·넉백 면역) / 재생(들고 있을 때) + 1회 부활",
            "우  막기 / 나에게 재생",
            "⇧우  방패 돌진(넉백+구속 III) / 아군 회복(3칸)",
            "F  성역 (방패 결계·밀쳐내기)",
            "피버  저항 III·흡수 IV·CC 무효"
    };

    // 수치
    private static final double REFLECT = 0.35;
    private static final double REFLECT_FEVER = 0.5;
    private static final int SWAP_DEBOUNCE = 6;
    private static final int BASH_CD = 160;
    private static final float BASH_MANA = 0.1f;
    private static final int SELF_REGEN_CD = 240;
    private static final float SELF_REGEN_MANA = 0.15f;
    private static final int HEAL_CD = 320;
    private static final float HEAL_MANA = 0.3f;
    private static final double HEAL_RADIUS = 3.0;
    private static final int DASH_TICKS = 6;
    private static final double DASH_SPEED = 0.55;
    private static final double DASH_LENGTH = 3.0;
    private static final double DASH_WIDTH = 1.0;
    private static final int BASH_SLOW_TICKS = 50;

    // 폼별 쿨다운 슬롯
    static final String RIGHT_SHIELD = "우·방패";
    static final String SHIFT_SHIELD = "⇧우·방패";
    static final String RIGHT_TOTEM = "우·불토";
    static final String SHIFT_TOTEM = "⇧우·불토";

    private static final Set<PotionEffectType> CC_TYPES = Set.of(
            PotionEffectType.SLOWNESS, PotionEffectType.LEVITATION, PotionEffectType.BLINDNESS,
            PotionEffectType.DARKNESS, PotionEffectType.NAUSEA, PotionEffectType.WEAKNESS,
            PotionEffectType.MINING_FATIGUE);
    private static final double HEAL_AMOUNT = 6.0;
    private static final float HEAL_ALLY_MANA = 0.1f;

    private final Map<UUID, Integer> lastSwap = new ConcurrentHashMap<>();
    private final Map<UUID, Sanctuary> sanctuaries = new ConcurrentHashMap<>();
    /** 불토 폼이 건 재생 (불토를 손에서 놓으면 즉시 제거). */
    private final Map<UUID, OwnedRegen> formRegen = new ConcurrentHashMap<>();
    /** 피버(궁극기) 넉백·CC 면역 종료 틱. */
    private final Map<UUID, Integer> feverGuardUntil = new ConcurrentHashMap<>();
    /** 피버 버프가 지금 걸려 있는 플레이어 (무기를 들고 있는 동안만). */
    private final Set<UUID> feverApplied = ConcurrentHashMap.newKeySet();
    /** 무기를 놓을 때 남아 있던 흡수량 — 다시 들 때 흡수 IV 를 새로 채우지 않도록. */
    private final Map<UUID, Double> feverAbsorbStash = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> dashes = new ConcurrentHashMap<>();
    private BukkitTask regenWatcher;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "방패";
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = Items.weapon(Material.SHIELD, ID, "방패", NamedTextColor.BLUE, LORE);
        item.editMeta(m -> {
            m.getPersistentDataContainer().set(FORM_KEY, PersistentDataType.STRING, FORM_SHIELD);
            m.getPersistentDataContainer().set(CHARGE_KEY, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    @Override
    public boolean usesMana() {
        return true;
    }

    @Override
    public String[] cooldownSlots(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        return isOurs(item) && isTotem(item)
                ? new String[]{RIGHT_TOTEM, SHIFT_TOTEM}
                : new String[]{SHIFT_SHIELD};
    }

    @Override
    public String[] cooldownSlots() {
        return new String[]{SHIFT_SHIELD, RIGHT_TOTEM, SHIFT_TOTEM};
    }

    @Override
    public Component hudExtra(SkillContext ctx) {
        if (!isTotem(ctx.item())) return Component.text("[방패]", NamedTextColor.BLUE);
        return Component.text("[불토] 부활 " + (hasCharge(ctx.item()) ? "●" : "○"), NamedTextColor.GOLD);
    }

    // ------------------------------------------------------------------ 폼

    private static boolean isOurs(ItemStack item) {
        return ID.equals(Items.kitId(item));
    }

    private static boolean isTotem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        String f = item.getItemMeta().getPersistentDataContainer().get(FORM_KEY, PersistentDataType.STRING);
        if (f != null) return FORM_TOTEM.equals(f);
        return item.getType() == Material.TOTEM_OF_UNDYING;
    }

    private static boolean hasCharge(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        Byte b = item.getItemMeta().getPersistentDataContainer().get(CHARGE_KEY, PersistentDataType.BYTE);
        return b == null || b != 0;
    }

    private static void setCharge(ItemStack item, boolean charged) {
        item.editMeta(m -> m.getPersistentDataContainer().set(CHARGE_KEY, PersistentDataType.BYTE, (byte) (charged ? 1 : 0)));
    }

    private void swap(Player p) {
        int now = Bukkit.getCurrentTick();
        Integer last = lastSwap.get(p.getUniqueId());
        if (last != null && now - last < SWAP_DEBOUNCE) return;
        ItemStack cur = p.getInventory().getItemInMainHand();
        if (!isOurs(cur)) return;
        lastSwap.put(p.getUniqueId(), now);

        boolean toTotem = !isTotem(cur);
        if (p.hasActiveItem()) p.clearActiveItem();
        if (!toTotem) stripFormRegen(p);
        ItemStack next = cur.withType(toTotem ? Material.TOTEM_OF_UNDYING : Material.SHIELD);
        next.editMeta(m -> {
            PersistentDataContainer pdc = m.getPersistentDataContainer();
            pdc.set(FORM_KEY, PersistentDataType.STRING, toTotem ? FORM_TOTEM : FORM_SHIELD);
            m.itemName(Component.text(toTotem ? "불사의 토템" : "방패", toTotem ? NamedTextColor.GOLD : NamedTextColor.BLUE)
                    .decoration(TextDecoration.BOLD, true));
        });
        p.getInventory().setItemInMainHand(next);

        Location l = p.getLocation().add(0, 1, 0);
        if (toTotem) {
            Fx.sound(l, Sound.ITEM_ARMOR_EQUIP_GOLD, 1f, 1.2f);
            l.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, l, 10, 0.3, 0.4, 0.3, 0.05);
            HudTask.flash(p, Component.text("불사의 토템 폼 — 부활 " + (hasCharge(next) ? "충전됨" : "소모됨"),
                    NamedTextColor.GOLD), 30);
        } else {
            Fx.sound(l, Sound.ITEM_ARMOR_EQUIP_IRON, 1f, 1f);
            l.getWorld().spawnParticle(Particle.ENCHANTED_HIT, l, 10, 0.3, 0.4, 0.3, 0.05);
            HudTask.flash(p, Component.text("방패 폼", NamedTextColor.BLUE), 30);
        }
    }

    @Override
    public void onLeftClick(SkillContext ctx) {
        swap(ctx.player());
    }

    @Override
    public void onMeleeHit(SkillContext ctx, LivingEntity target, EntityDamageByEntityEvent event) {
        swap(ctx.player());
    }

    // ------------------------------------------------------------------ P

    @Override
    public void onPassiveTick(SkillContext ctx) {
        Player p = ctx.player();
        // 피버 버프: 무기를 놓으면 해제되고, 피버가 남아 있을 때 다시 들면 재적용
        if (ctx.isFever()) {
            if (!feverApplied.contains(p.getUniqueId())) applyFeverBuffs(p, false);
        } else if (feverApplied.remove(p.getUniqueId())) {
            feverGuardUntil.remove(p.getUniqueId());
            feverAbsorbStash.remove(p.getUniqueId());
        }
        if (isFeverGuarded(p)) Cc.cleanse(p);
        if (!isTotem(ctx.item())) {
            stripFormRegen(p);
            return;
        }
        // 재생 I: 지속시간이 50틱 경계를 지나야 회복되므로 매번 덮어쓰지 않고 거의 끝났을 때만 갱신
        PotionEffect cur = p.getPotionEffect(PotionEffectType.REGENERATION);
        OwnedRegen mine = formRegen.get(p.getUniqueId());
        boolean ours = mine != null && mine.matches(p);
        if (cur == null || (ours && cur.getAmplifier() == 0 && cur.getDuration() <= 10)) {
            grantFormRegen(p, 110, 0, true, false);
        }
    }

    // ------------------------------------------------------------------ 재생 추적

    private void grantFormRegen(Player p, int duration, int amp, boolean ambient, boolean particles) {
        OwnedRegen g = OwnedRegen.apply(p, duration, amp, ambient, particles);
        if (g == null) return;
        formRegen.put(p.getUniqueId(), g);
        ensureRegenWatcher();
    }

    private void stripFormRegen(Player p) {
        OwnedRegen g = formRegen.remove(p.getUniqueId());
        if (g != null) g.strip(p);
    }

    /** 매 틱: 불토 폼을 주손에 들고 있지 않으면 우리가 건 재생 즉시 제거 (버리기·인벤 이동 포함). */
    private void ensureRegenWatcher() {
        if (regenWatcher != null && !regenWatcher.isCancelled()) return;
        regenWatcher = Bukkit.getScheduler().runTaskTimer(DeruyRPG.get(), () -> {
            Iterator<Map.Entry<UUID, OwnedRegen>> it = formRegen.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, OwnedRegen> en = it.next();
                Player p = Bukkit.getPlayer(en.getKey());
                OwnedRegen g = en.getValue();
                if (p == null || !p.isOnline() || p.isDead() || g.expired()) {
                    it.remove();
                    continue;
                }
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (!(isOurs(hand) && isTotem(hand))) {
                    g.strip(p);
                    it.remove();
                }
            }
            if (formRegen.isEmpty()) {
                regenWatcher.cancel();
                regenWatcher = null;
            }
        }, 1L, 1L);
    }

    // ------------------------------------------------------------------ 피버

    private boolean isFeverGuarded(Player p) {
        Integer end = feverGuardUntil.get(p.getUniqueId());
        if (end == null) return false;
        if (end > Bukkit.getCurrentTick()) return true;
        feverGuardUntil.remove(p.getUniqueId());
        return false;
    }

    /** 피버(15초, 폼 무관, 방패를 들고 있을 때만): 저항 III + 흡수 IV + 모든 CC 무효 + 넉백 면역. */
    private void applyFeverBuffs(Player p, boolean fresh) {
        UUID id = p.getUniqueId();
        int left = DeruyRPG.get().fever().feverRemaining(p);
        if (left <= 0 || !DeruyRPG.get().fever().isFever(p, ID)) return;
        Double stash = feverAbsorbStash.remove(id);
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, left, 2, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, left, 3, false, true));
        if (!fresh && stash != null) p.setAbsorptionAmount(Math.min(p.getAbsorptionAmount(), stash));
        feverGuardUntil.put(id, Bukkit.getCurrentTick() + left);
        Cc.setImmune(p, left);
        feverApplied.add(id);
    }

    /** 무기를 놓으면 피버 버프 해제 (피버 자체는 남아 있으면 다시 들 때 재적용). 여러 번 불려도 안전. */
    private void stripFeverBuffs(Player p) {
        UUID id = p.getUniqueId();
        feverGuardUntil.remove(id);
        if (!feverApplied.remove(id)) return;
        Cc.clearImmune(p);
        double absorb = p.getAbsorptionAmount();
        removeIfAmp(p, PotionEffectType.RESISTANCE, 2);
        removeIfAmp(p, PotionEffectType.ABSORPTION, 3);
        if (DeruyRPG.get().fever().isFever(p, ID)) feverAbsorbStash.put(id, absorb);
        else feverAbsorbStash.remove(id);
        // 피버 포션이 덮고 있던 성역 버프(저항 I·흡수 II)는 남은 시간만큼 복구
        Sanctuary s = sanctuaries.get(id);
        if (s != null && s.isActive()) s.restoreOwnerBuffs(p, absorb);
    }

    private static void removeIfAmp(Player p, PotionEffectType type, int amp) {
        PotionEffect cur = p.getPotionEffect(type);
        if (cur != null && cur.getAmplifier() == amp) p.removePotionEffect(type);
    }

    /** 피버 중 CC 포션 차단. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPotion(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getNewEffect() == null) return;
        if (!CC_TYPES.contains(e.getNewEffect().getType())) return;
        if (isFeverGuarded(p)) e.setCancelled(true);
    }

    /** 철벽: 막은 피해 일부 반사. */
    @Override
    public void onDamaged(SkillContext ctx, EntityDamageEvent event) {
        if (Damage.isProbe()) return;
        Player p = ctx.player();
        if (isTotem(ctx.item()) || Damage.isSkillDamage()) return;
        if (!(event instanceof EntityDamageByEntityEvent ed)) return;
        Entity src = ed.getDamager();
        if (src instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) src = shooter;
        if (!(src instanceof LivingEntity attacker) || !Targets.isEnemy(p, attacker)) return;
        if (!blocked(p, event, ed.getDamager())) return;

        double amount = event.getDamage() * (ctx.isFever() ? REFLECT_FEVER : REFLECT);
        if (amount <= 0) return;
        Damage.skill(p, attacker, amount);
        Location al = attacker.getLocation().add(0, attacker.getHeight() / 2, 0);
        al.getWorld().spawnParticle(Particle.CRIT, al, 12, 0.3, 0.3, 0.3, 0.2);
        Fx.line(p.getEyeLocation(), al, Particle.ENCHANTED_HIT, 0.5);
        Fx.sound(p.getLocation(), Sound.BLOCK_ANVIL_PLACE, 0.4f, 1.8f);
    }

    @SuppressWarnings("deprecation")
    private static boolean blocked(Player p, EntityDamageEvent event, Entity damager) {
        if (!p.isBlocking()) return false;
        try {
            if (event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)) {
                return event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) < 0;
            }
        } catch (Exception ignored) {
            // 아래 방향 판정으로
        }
        Vector to = damager.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
        Vector look = p.getLocation().getDirection().setY(0);
        return to.lengthSquared() < 1e-6 || look.lengthSquared() < 1e-6 || to.dot(look) > 0;
    }

    /** 막는 중(방패 폼) 또는 성역 중 넉백 면역. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onKnockback(EntityKnockbackEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (isFeverGuarded(p)) {
            e.setCancelled(true);
            return;
        }
        Sanctuary s = sanctuaries.get(p.getUniqueId());
        if (s != null && s.isActive()) {
            e.setCancelled(true);
            return;
        }
        ItemStack item = p.getInventory().getItemInMainHand();
        if (isOurs(item) && !isTotem(item) && p.isBlocking()) e.setCancelled(true);
    }

    /**
     * 불토 폼 1회 부활. 바닐라 토템 발동은 충전이 있을 때만 허용하고, 아이템은 소모되지 않게 되돌림.
     * 충전이 없으면 다른 손의 일반 토템이 있으면 그걸 대신 소모, 없으면 부활 취소.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onResurrect(EntityResurrectEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.isCancelled()) return;
        EquipmentSlot hand = e.getHand();
        if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND) return;
        ItemStack it = p.getInventory().getItem(hand);
        if (!isOurs(it)) return;

        if (isTotem(it) && hasCharge(it)) {
            ItemStack keep = it.clone();
            keep.setAmount(1);
            setCharge(keep, false);
            restoreAfterResurrect(p, hand, keep);
            HudTask.flash(p, Component.text("불사의 토템 발동! (성역으로 재충전)", NamedTextColor.GOLD), 40);
            return;
        }

        EquipmentSlot other = hand == EquipmentSlot.HAND ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
        ItemStack o = p.getInventory().getItem(other);
        if (o != null && o.getType() == Material.TOTEM_OF_UNDYING && Items.kitId(o) == null) {
            restoreAfterResurrect(p, hand, it.clone());
            o.setAmount(o.getAmount() - 1);
            p.getInventory().setItem(other, o.getAmount() > 0 ? o : null);
            return;
        }
        e.setCancelled(true);
    }

    /** 바닐라는 이벤트 후 원래 스택을 줄이므로, 슬롯에 새 스택을 넣고 다음 틱에도 한 번 더 확인. */
    private void restoreAfterResurrect(Player p, EquipmentSlot hand, ItemStack keep) {
        int heldSlot = p.getInventory().getHeldItemSlot();
        p.getInventory().setItem(hand, keep.clone());
        Bukkit.getScheduler().runTask(DeruyRPG.get(), () -> {
            if (!p.isOnline()) return;
            if (hand == EquipmentSlot.HAND) {
                ItemStack now = p.getInventory().getItem(heldSlot);
                if (now == null || now.getType().isAir()) p.getInventory().setItem(heldSlot, keep);
            } else {
                ItemStack now = p.getInventory().getItemInOffHand();
                if (now.getType().isAir()) p.getInventory().setItemInOffHand(keep);
            }
        });
    }

    // ------------------------------------------------------------------ 우 / ⇧우

    @Override
    public void onRightClick(SkillContext ctx) {
        if (!isTotem(ctx.item())) return; // 방패 폼: 바닐라 막기 (주손 방패)
        Player p = ctx.player();
        ctx.cancelVanilla();
        if (ctx.onCooldown(RIGHT_TOTEM) || !ctx.tryMana(SELF_REGEN_MANA)) return;
        ctx.tryCooldown(RIGHT_TOTEM, SELF_REGEN_CD);
        grantFormRegen(p, 80, ctx.isFever() ? 2 : 1, false, true);
        Location l = p.getLocation().add(0, 1, 0);
        l.getWorld().spawnParticle(Particle.HEART, l, 6, 0.4, 0.5, 0.4, 0);
        Fx.sound(l, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.4f);
    }

    @Override
    public void onShiftRightClick(SkillContext ctx) {
        ctx.cancelVanilla();
        if (isTotem(ctx.item())) groupHeal(ctx);
        else shieldBash(ctx);
    }

    /** 방패 폼 ⇧우: 약 3칸 돌진, 경로(폭 1 × 길이 3)의 적 넉백 + 구속 III. */
    private void shieldBash(SkillContext ctx) {
        Player p = ctx.player();
        if (ctx.onCooldown(SHIFT_SHIELD) || !ctx.tryMana(BASH_MANA)) return;
        ctx.tryCooldown(SHIFT_SHIELD, BASH_CD);

        UUID id = p.getUniqueId();
        BukkitTask old = dashes.remove(id);
        if (old != null) old.cancel();

        Location start = p.getLocation();
        Vector dir = start.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
        final Vector forward = dir.normalize();
        final Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        Set<UUID> hit = new HashSet<>();

        Fx.sound(start, Sound.ITEM_SHIELD_BLOCK, 1f, 0.7f);
        Fx.sound(start, Sound.ENTITY_IRON_GOLEM_ATTACK, 0.8f, 1f);

        BukkitTask[] holder = new BukkitTask[1];
        int[] t = {0};
        holder[0] = Bukkit.getScheduler().runTaskTimer(ctx.plugin(), () -> {
            if (!p.isOnline() || p.isDead() || !p.getWorld().equals(start.getWorld()) || t[0]++ >= DASH_TICKS) {
                holder[0].cancel();
                dashes.remove(id, holder[0]);
                return;
            }
            double vy = p.isOnGround() ? 0.0 : Math.min(p.getVelocity().getY(), 0.2);
            p.setVelocity(forward.clone().multiply(DASH_SPEED).setY(vy));
            p.setFallDistance(0);

            Location now = p.getLocation();
            double traveled = now.toVector().subtract(start.toVector()).dot(forward);
            double reach = Math.min(DASH_LENGTH, Math.max(0, traveled) + 1.2);
            for (LivingEntity e : Targets.enemiesAround(p, now, 3.0)) {
                if (hit.contains(e.getUniqueId())) continue;
                Vector rel = e.getLocation().toVector().subtract(start.toVector());
                double f = rel.dot(forward);
                double side = Math.abs(rel.dot(right));
                double halfW = DASH_WIDTH / 2.0 + e.getWidth() / 2.0;
                double y = rel.getY();
                if (f < -0.3 || f > reach || side > halfW || y < -1 || y > 2.5) continue;
                hit.add(e.getUniqueId());
                if (!Damage.canHarm(p, e)) continue; // PvP 꺼짐·보호구역
                e.setVelocity(forward.clone().multiply(1.3).setY(0.4));
                e.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, BASH_SLOW_TICKS, 2, false, true));
                Location el = e.getLocation().add(0, e.getHeight() / 2, 0);
                el.getWorld().spawnParticle(Particle.CRIT, el, 10, 0.3, 0.3, 0.3, 0.2);
                Fx.sound(el, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1f, 0.8f);
            }
            Location fx = now.clone().add(forward.clone().multiply(0.8)).add(0, 1, 0);
            fx.getWorld().spawnParticle(Particle.CLOUD, fx, 4, 0.3, 0.3, 0.3, 0.02);
            fx.getWorld().spawnParticle(Particle.ENCHANTED_HIT, fx, 4, 0.3, 0.4, 0.3, 0.05);
        }, 0L, 1L);
        dashes.put(id, holder[0]);
    }

    /** 불토 폼 ⇧우: 반경 3칸 아군(자신 포함) 6HP 즉시 회복 + 마나 +0.1. */
    private void groupHeal(SkillContext ctx) {
        Player p = ctx.player();
        if (ctx.onCooldown(SHIFT_TOTEM) || !ctx.tryMana(HEAL_MANA)) return;
        ctx.tryCooldown(SHIFT_TOTEM, HEAL_CD);
        for (Player ally : Targets.alliesAround(p, p.getLocation(), HEAL_RADIUS)) {
            if (ally.isDead()) continue;
            AttributeInstance max = ally.getAttribute(Attribute.MAX_HEALTH);
            double maxHp = max != null ? max.getValue() : 20.0;
            ally.setHealth(Math.min(maxHp, ally.getHealth() + HEAL_AMOUNT));
            ctx.mana().add(ally, HEAL_ALLY_MANA);
            Location l = ally.getLocation().add(0, 1, 0);
            l.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, l, 12, 0.4, 0.6, 0.4, 0);
            l.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, l, 8, 0.3, 0.5, 0.3, 0.1);
        }
        Fx.ring(p.getLocation(), Particle.TOTEM_OF_UNDYING, HEAL_RADIUS, 48);
        Fx.sound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.6f);
    }

    // ------------------------------------------------------------------ F 성역

    @Override
    public void onUltimate(SkillContext ctx) {
        Player p = ctx.player();
        Sanctuary old = sanctuaries.remove(p.getUniqueId());
        if (old != null) old.end();

        // 불토 P 재충전
        ItemStack item = p.getInventory().getItemInMainHand();
        if (isOurs(item) && !hasCharge(item)) {
            setCharge(item, true);
            p.getInventory().setItemInMainHand(item);
        }

        UUID id = p.getUniqueId();
        Sanctuary[] holder = new Sanctuary[1];
        Sanctuary s = new Sanctuary(p, () -> sanctuaries.remove(id, holder[0]));
        holder[0] = s;
        sanctuaries.put(id, s);
        s.start(ctx.plugin());
        applyFeverBuffs(p, true);
    }

    /** 돔 안 아군 치명 피해 → 취소하고 부활. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLethal(EntityDamageEvent e) {
        if (Damage.isProbe()) return;
        if (sanctuaries.isEmpty() || !(e.getEntity() instanceof Player victim)) return;
        EntityDamageEvent.DamageCause c = e.getCause();
        if (c == EntityDamageEvent.DamageCause.VOID || c == EntityDamageEvent.DamageCause.KILL
                || c == EntityDamageEvent.DamageCause.SUICIDE || c == EntityDamageEvent.DamageCause.WORLD_BORDER) return;
        if (!isLethal(e, victim)) return;
        for (Sanctuary s : sanctuaries.values()) {
            if (s.tryRevive(victim)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean isLethal(EntityDamageEvent e, Player victim) {
        boolean absorbedInFinal;
        try {
            absorbedInFinal = e.isApplicable(EntityDamageEvent.DamageModifier.ABSORPTION);
        } catch (Exception ex) {
            absorbedInFinal = false;
        }
        double pool = victim.getHealth() + (absorbedInFinal ? 0 : victim.getAbsorptionAmount());
        return e.getFinalDamage() >= pool;
    }

    /** 투사체가 같은 틱에 돔을 뚫고 맞히는 경우 대비. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent e) {
        if (sanctuaries.isEmpty() || e.getHitEntity() == null) return;
        Projectile pr = e.getEntity();
        Entity hit = e.getHitEntity();
        for (Sanctuary s : sanctuaries.values()) {
            if (!s.isActive() || !s.contains(hit.getLocation())) continue;
            Player owner = Bukkit.getPlayer(s.owner());
            if (owner == null || s.isFriendly(owner, pr)) continue;
            Location origin = pr.getOrigin();
            boolean fromOutside = pr.getShooter() instanceof Entity shooter
                    ? !s.contains(shooter.getLocation())
                    : origin == null || !s.contains(origin);
            if (!fromOutside) continue;
            e.setCancelled(true);
            s.deflect(pr);
            return;
        }
    }

    // ------------------------------------------------------------------ 정리

    /** 무기 해제(슬롯 변경·버리기·사망·접속 종료 등). 성역(궁극기)은 끊지 않음. 여러 번 불려도 안전. */
    @Override
    public void onUnequip(Player player) {
        UUID id = player.getUniqueId();
        lastSwap.remove(id);
        stripFormRegen(player);
        BukkitTask dash = dashes.remove(id);
        if (dash != null) dash.cancel();
        stripFeverBuffs(player);
        Sanctuary s = sanctuaries.get(id);
        if (s == null || !s.isActive()) Sanctuary.cleanup(player);
    }

    /** 접속 종료: 성역 즉시 정리 (사망은 성역 자체 타이머가 감지해 종료). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Sanctuary s = sanctuaries.remove(p.getUniqueId());
        if (s != null) s.end();
        Sanctuary.cleanup(p);
    }
}
