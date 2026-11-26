package com.fsixel.deruyrpg.api;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 무기 하나 = WeaponKit 하나.
 *
 * 입력 규칙 (코어가 감지해서 호출):
 *   P            : onPassiveTick (5틱마다, 무기를 주손에 들고 있을 때)
 *   우클릭        : onRightClick
 *   좌클릭        : onLeftClick (허공/블록 스윙) + onMeleeHit (엔티티 타격)
 *   Shift+우클릭  : onShiftRightClick
 *   F (손 바꾸기) : onUltimate  — 피버 게이지가 가득 찼을 때만 호출됨. 게이지 소모·피버 시작은 코어가 처리.
 *
 * 무기가 추가 이벤트(투사체 명중 등)가 필요하면 이 클래스에서 org.bukkit.event.Listener 를 같이 구현하면
 * KitRegistry 가 자동으로 리스너 등록해 준다.
 */
public interface WeaponKit {

    /** 고유 id (소문자). 예: "sword" */
    String id();

    /** 표시 이름. 예: "검" */
    String displayName();

    /** 지급용 아이템. 반드시 Items.tag(item, id()) 로 태그를 붙일 것. */
    ItemStack createItem();

    default void onPassiveTick(SkillContext ctx) {}

    default void onRightClick(SkillContext ctx) {}

    default void onShiftRightClick(SkillContext ctx) {}

    default void onLeftClick(SkillContext ctx) {}

    /** 이 무기로 근접 타격했을 때 (좌클릭 평타 판정). event 의 피해량을 수정해도 됨. */
    default void onMeleeHit(SkillContext ctx, LivingEntity target, EntityDamageByEntityEvent event) {}

    /** 이 무기를 든 플레이어가 피해를 받을 때 (방패/토템 등). */
    default void onDamaged(SkillContext ctx, EntityDamageEvent event) {}

    /** 궁극기. 피버 게이지가 가득일 때만 호출됨. */
    default void onUltimate(SkillContext ctx) {}

    /** 피버 지속 시간(틱). 무기별로 바꾸고 싶으면 오버라이드. */
    default int feverDurationTicks() {
        return 120;
    }

    /** 이 무기가 마나(경험치 바)를 쓰는지. true 면 들고 있는 동안 경험치 바에 마나가 표시됨. */
    default boolean usesMana() {
        return false;
    }

    /** 액션바에 보여줄 쿨다운 슬롯 이름들. */
    default String[] cooldownSlots() {
        return new String[]{Slot.RIGHT, Slot.SHIFT_RIGHT};
    }

    /** 무기가 손에서 빠질 때 정리용 (소환물 제거 등). */
    default void onUnequip(Player player) {}

    /** 표준 슬롯 이름. 쿨다운 키로 사용. */
    final class Slot {
        public static final String RIGHT = "우";
        public static final String LEFT = "좌";
        public static final String SHIFT_RIGHT = "⇧우";

        private Slot() {}
    }
}
