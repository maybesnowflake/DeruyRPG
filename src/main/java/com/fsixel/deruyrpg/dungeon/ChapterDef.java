package com.fsixel.deruyrpg.dungeon;

import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * 챕터 = 여러 스테이지(라운드의 stage 번호로 구분). 스테이지 경계를 넘어갈 때 stageRewards 만큼 목숨 +.
 *
 * @param nextChapter 클리어 후 이어지는 챕터 id (없으면 기본 월드로 귀환)
 */
public record ChapterDef(
        String id,
        String name,
        String start,
        @Nullable String nextChapter,
        Map<Integer, Integer> stageRewards,
        Map<String, RoundDef> rounds
) {
    public @Nullable RoundDef round(String id) {
        return rounds.get(id);
    }
}
