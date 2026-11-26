package com.fsixel.deruyrpg.weapons.shield;

import com.fsixel.deruyrpg.DeruyRPG;
import com.fsixel.deruyrpg.api.SkillContext;
import com.fsixel.deruyrpg.api.WeaponKit;
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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

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
            "P  철벽(반사·넉백 면역) / 재생 + 1회 부활",
            "우  막기 / 나에게 재생",
            "⇧우  넉백+구속 / 아군 회복",
            "F  성역"
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
    private static final double HEAL_RADIUS = 6.0;
    private static final double HEAL_AMOUNT = 6.0;
    private static final float HEAL_ALLY_MANA = 0.1f;

    private final Map<UUID, Integer> lastSwap = new ConcurrentHashMap<>();
    private final Map<UUID, Sanctuary> sanctuaries = new ConcurrentHashMap<>();

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
            p.sendActionBar(Component.text("불사의 토템 폼 — 부활 " + (hasCharge(next) ? "충전됨" : "소모됨"),
                    NamedTextColor.GOLD));
        } else {
            Fx.sound(l, Sound.ITEM_ARMOR_EQUIP_IRON, 1f, 1f);
            l.getWorld().spawnParticle(Particle.ENCHANTED_HIT, l, 10, 0.3, 0.4, 0.3, 0.05);
            p.sendActionBar(Component.text("방패 폼", NamedTextColor.BLUE));
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
        if (!isTotem(ctx.item())) return;
        // 재생 I: 지속시간이 50틱 경계를 지나야 회복되므로 매번 덮어쓰지 않고 거의 끝났을 때만 갱신
        PotionEffect cur = p.getPotionEffect(PotionEffectType.REGENERATION);
        if (cur == null || (cur.getAmplifier() == 0 && cur.getDuration() <= 10)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 110, 0, true, false));
        }
    }

    /** 철벽: 막은 피해 일부 반사. */
    @Override
    public void onDamaged(SkillContext ctx, EntityDamageEvent event) {
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
            p.sendActionBar(Component.text("불사의 토템 발동! (성역으로 재충전)", NamedTextColor.GOLD));
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
        if (ctx.onCooldown(Slot.RIGHT) || !ctx.tryMana(SELF_REGEN_MANA)) return;
        ctx.tryCooldown(Slot.RIGHT, SELF_REGEN_CD);
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 80, ctx.isFever() ? 2 : 1, false, true));
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

    /** 방패 폼 ⇧우: 전방 넉백 + 짧은 구속. */
    private void shieldBash(SkillContext ctx) {
        Player p = ctx.player();
        if (ctx.onCooldown(Slot.SHIFT_RIGHT) || !ctx.tryMana(BASH_MANA)) return;
        ctx.tryCooldown(Slot.SHIFT_RIGHT, BASH_CD);
        int slowAmp = ctx.isFever() ? 2 : 1;
        for (LivingEntity e : Targets.frontBox(p, 3, 4, 2)) {
            Targets.push(e, p.getLocation(), 1.3, 0.35);
            e.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, slowAmp, false, true));
        }
        Location front = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(1.5)).add(0, 1, 0);
        front.getWorld().spawnParticle(Particle.SWEEP_ATTACK, front, 3, 0.6, 0.3, 0.6, 0);
        front.getWorld().spawnParticle(Particle.CLOUD, front, 15, 0.6, 0.4, 0.6, 0.05);
        Fx.sound(front, Sound.ITEM_SHIELD_BLOCK, 1f, 0.7f);
        Fx.sound(front, Sound.ENTITY_IRON_GOLEM_ATTACK, 0.8f, 1f);
    }

    /** 불토 폼 ⇧우: 아군(자신 포함) 6HP 즉시 회복 + 마나 +0.1. */
    private void groupHeal(SkillContext ctx) {
        Player p = ctx.player();
        if (ctx.onCooldown(Slot.SHIFT_RIGHT) || !ctx.tryMana(HEAL_MANA)) return;
        ctx.tryCooldown(Slot.SHIFT_RIGHT, HEAL_CD);
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
    }

    /** 돔 안 아군 치명 피해 → 취소하고 부활. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLethal(EntityDamageEvent e) {
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

    @Override
    public void onUnequip(Player player) {
        lastSwap.remove(player.getUniqueId());
        Sanctuary s = sanctuaries.remove(player.getUniqueId());
        if (s != null) s.end();
        Sanctuary.cleanup(player);
    }
}
