package com.fsixel.deruyrpg.dungeon;

/** 라운드 종류. 전투형(COMBAT·ELITE)은 몹 전멸, 보스형은 보스 처치, 나머지는 트리거 블록 또는 /rpg dungeon next 로 클리어. */
public enum RoundType {
    COMBAT("전투"), ELITE("엘리트"), PUZZLE("퍼즐"), SHOP("상점"), REST("휴식"), EVENT("이벤트"),
    MINIBOSS("미니보스"), BOSS("보스");

    public final String label;

    RoundType(String label) {
        this.label = label;
    }

    public boolean combat() {
        return this == COMBAT || this == ELITE;
    }

    public boolean boss() {
        return this == MINIBOSS || this == BOSS;
    }
}
