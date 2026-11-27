package com.fsixel.deruyrpg.boss.moss;

import com.fsixel.deruyrpg.boss.moss.MossModel.Bone;
import com.fsixel.deruyrpg.boss.moss.MossModel.Pose;

/**
 * 이끼 수문장 포즈 (Bukkit 의존 없음).
 * 팔 회전 메모: 팔은 아래(-Y)로 늘어져 있으므로
 *   rot X 음수 → 앞으로 들어 올림 (-90 = 정면 수평, -160 = 머리 위)
 *   rot Z 양수 → 왼팔(+X)을 옆으로 벌림 (오른팔은 음수)
 *   BODY rot X 양수 → 앞으로 숙임
 */
public final class MossAnim {

    private MossAnim() {}

    public static float clamp01(float f) {
        return f < 0 ? 0 : f > 1 ? 1 : f;
    }

    /** a~b 구간에서 0→1 (부드럽게). */
    public static float seg(float t, float a, float b) {
        float f = clamp01((t - a) / (b - a));
        return f * f * (3 - 2 * f);
    }

    /** a~b 구간에서 0→1 (가속 — 내려치기용). */
    public static float segIn(float t, float a, float b) {
        float f = clamp01((t - a) / (b - a));
        return f * f;
    }

    static float lerp(float a, float b, float f) {
        return a + (b - a) * f;
    }

    // ------------------------------------------------------------------ 기본

    public static Pose idle(int phase, float t) {
        Pose p = new Pose();
        float s = (float) Math.sin(t * 0.15);
        if (phase == 1) {
            // 1페이즈: 통통 튀는 느낌 — 몸이 살짝 눌렸다 펴짐
            p.off(Bone.BODY, 0, 0.04f * s, 0);
            p.rot(Bone.ARM_L, 4 * s, 0, 3);
            p.rot(Bone.ARM_R, 4 * s, 0, -3);
            p.rot(Bone.HEAD, 0, 0, 3 * (float) Math.sin(t * 0.08));
        } else {
            // 2페이즈: 앞으로 숙인 채 무겁게 숨쉼, 덩굴 팔은 흔들림, 핵 맥동
            p.rot(Bone.BODY, 12 + 2 * s, 0, 0);
            p.rot(Bone.HEAD, -10, 0, 0);
            p.rot(Bone.ARM_L, -6 - 2 * s, 0, 4);
            p.rot(Bone.ARM_R, -8, 0, -6 - 3 * s);
            p.rot(Bone.FORE_R, 6 * (float) Math.sin(t * 0.11), 0, -6 * (float) Math.sin(t * 0.09));
            p.rot(Bone.LEG_L, -12, 0, 0);
            p.rot(Bone.LEG_R, -12, 0, 0);
            p.scale(Bone.CORE, corePulse(t));
        }
        return p;
    }

    public static float corePulse(float t) {
        // 심장 박동처럼 두 번 뛰고 쉼 (주기 30틱)
        float c = (t % 30) / 30f;
        float beat = (float) (Math.exp(-Math.pow((c - 0.1) * 14, 2)) + 0.7 * Math.exp(-Math.pow((c - 0.3) * 14, 2)));
        return 1f + 0.18f * beat;
    }

    /** 걷기 (다리 교차 + 좌우 흔들림). 기존 포즈 위에 더한다. */
    public static Pose walk(Pose p, int phase, float t) {
        float s = (float) Math.sin(t * (phase == 1 ? 0.4 : 0.22));
        float amp = phase == 1 ? 22 : 14;
        p.rot(Bone.LEG_L, amp * s, 0, 0);
        p.rot(Bone.LEG_R, -amp * s, 0, 0);
        p.rot(Bone.BODY, 0, 0, (phase == 1 ? 5 : 3) * s);
        p.rot(Bone.ARM_L, -8 * s, 0, 0);
        p.rot(Bone.ARM_R, 8 * s, 0, 0);
        p.off(Bone.ROOT, 0, (phase == 1 ? 0.08f : 0.04f) * Math.abs(s), 0);
        return p;
    }

    // ------------------------------------------------------------------ 1페이즈

    /** 1페이즈 내려치기 시간표 (틱). */
    public static final int P1_SLAM_RAISE = 16, P1_SLAM_HIT = 22, P1_SLAM_END = 36;

    /** left=true 면 왼팔. t = 시작 후 틱. */
    public static Pose p1Slam(float t, boolean left) {
        Pose p = idle(1, t);
        Bone arm = left ? Bone.ARM_L : Bone.ARM_R;
        Bone fore = left ? Bone.FORE_L : Bone.FORE_R;
        float side = left ? 1 : -1;
        float up = seg(t, 0, P1_SLAM_RAISE);
        float down = segIn(t, P1_SLAM_RAISE + 2, P1_SLAM_HIT);
        float back = seg(t, P1_SLAM_HIT + 4, P1_SLAM_END);
        float armX = lerp(lerp(0, -165, up), -55, down);
        armX = lerp(armX, 0, back);
        float lean = lerp(lerp(0, -8, up), 32, down);
        lean = lerp(lean, 0, back);
        p.rot(arm, armX, 0, side * lerp(8 * up, 0, down));
        p.rot(fore, lerp(-25 * up, -10, down) * (1 - back), 0, 0);
        p.rot(Bone.BODY, lean, side * -10 * up * (1 - back), 0);
        p.rot(Bone.HEAD, -lean * 0.4f, 0, 0);
        return p;
    }

    /** 회전 덩굴: f = 팔 벌림 정도 0~1. */
    public static Pose p1Spin(float t, float f) {
        Pose p = idle(1, t);
        p.rot(Bone.ARM_L, -10 * f, 0, 82 * f);
        p.rot(Bone.ARM_R, -10 * f, 0, -82 * f);
        p.rot(Bone.FORE_L, 0, 0, 10 * f);
        p.rot(Bone.FORE_R, 0, 0, -10 * f);
        p.off(Bone.ROOT, 0, 0.05f * f * (float) Math.abs(Math.sin(t * 0.5)), 0);
        return p;
    }

    /** 가시 발아: 웅크리고 오른손을 바닥에 짚음. f = 0~1. */
    public static Pose p1Reach(float t, float f) {
        Pose p = idle(1, t);
        p.rot(Bone.BODY, 30 * f, 0, 0);
        p.rot(Bone.ARM_R, -62 * f, 0, -6 * f);
        p.rot(Bone.ARM_L, -15 * f, 0, 15 * f);
        p.rot(Bone.LEG_L, -25 * f, 0, 0);
        p.rot(Bone.LEG_R, -25 * f, 0, 0);
        p.off(Bone.ROOT, 0, -0.15f * f, 0);
        p.rot(Bone.HEAD, -20 * f, 0, 0);
        return p;
    }

    // ------------------------------------------------------------------ 각성 전환

    /** 1페이즈 모델이 몸을 앞으로 숙이며 떨림. f = 0~1. */
    public static Pose p1Hunch(float t, float f) {
        Pose p = idle(1, t);
        float shake = (float) Math.sin(t * 2.7) * 3 * f;
        p.rot(Bone.BODY, 48 * f, shake, shake * 0.5f);
        p.rot(Bone.ARM_L, 25 * f, 0, -10 * f);
        p.rot(Bone.ARM_R, 25 * f, 0, 10 * f);
        p.rot(Bone.HEAD, 20 * f, 0, 0);
        p.off(Bone.ROOT, 0, -0.25f * f, 0);
        return p;
    }

    /** 2페이즈가 숙인 자세(f=0)에서 일어서며 포효(f=1). */
    public static Pose p2Rise(float t, float f) {
        Pose p = idle(2, t);
        float hunch = 1 - f;
        float roar = (float) Math.sin(Math.PI * clamp01((f - 0.55f) / 0.45f));
        p.rot(Bone.BODY, 45 * hunch - 18 * roar, 0, 0);
        p.rot(Bone.HEAD, 15 * hunch - 25 * roar, 0, 0);
        p.rot(Bone.ARM_L, 20 * hunch - 70 * roar, 0, 35 * roar);
        p.rot(Bone.ARM_R, 20 * hunch - 70 * roar, 0, -35 * roar);
        p.rot(Bone.LEG_L, -20 * hunch, 0, 0);
        p.rot(Bone.LEG_R, -20 * hunch, 0, 0);
        p.off(Bone.ROOT, 0, -0.4f * hunch, 0);
        return p;
    }

    // ------------------------------------------------------------------ 2페이즈 (정글 보스 기술)

    /** 석상 곤봉 내려찍기 시간표. 내려찍는 동안 이동·회전 불가. */
    public static final int P2_SLAM_RAISE = 24, P2_SLAM_HIT = 30, P2_SLAM_END = 54;

    public static Pose p2Slam(float t) {
        Pose p = idle(2, t);
        float up = seg(t, 0, P2_SLAM_RAISE);
        float down = segIn(t, P2_SLAM_RAISE + 2, P2_SLAM_HIT);
        float back = seg(t, P2_SLAM_HIT + 10, P2_SLAM_END);
        float armX = lerp(lerp(0, -165, up), -48, down);
        p.rot(Bone.ARM_L, lerp(armX, 0, back), 0, lerp(lerp(0, -12, up), 0, down));
        p.rot(Bone.FORE_L, lerp(lerp(0, -30, up), 0, down), 0, 0);
        p.rot(Bone.BODY, lerp(lerp(0, -16, up), 26, down) * (1 - back), lerp(-14 * up, 0, down), 0);
        p.rot(Bone.ARM_R, 0, 0, -18 * up * (1 - down));
        return p;
    }

    /** 덩굴 촉수를 바닥에 꽂기 시간표. */
    public static final int P2_VINE_RAISE = 18, P2_VINE_HIT = 23, P2_VINE_END = 44;

    public static Pose p2VinePlunge(float t) {
        Pose p = idle(2, t);
        float up = seg(t, 0, P2_VINE_RAISE);
        float down = segIn(t, P2_VINE_RAISE, P2_VINE_HIT);
        float back = seg(t, P2_VINE_HIT + 12, P2_VINE_END);
        float k = 1 - back;
        p.rot(Bone.ARM_R, lerp(lerp(0, -150, up), -40, down) * k, 0, lerp(-20 * up, 10, down) * k);
        p.rot(Bone.FORE_R, lerp(-40 * up, -10, down) * k, 0, 0);
        p.rot(Bone.BODY, lerp(-10 * up, 22, down) * k, lerp(20 * up, -10, down) * k, 0);
        return p;
    }

    /** 독침 발사: 몸을 부풀림. f = 0~1. */
    public static Pose p2Puff(float t, float f) {
        Pose p = idle(2, t);
        p.scale(Bone.BODY, 1 + 0.06f * f);
        p.scale(Bone.CORE, 1 + 0.4f * f);
        p.rot(Bone.BODY, -10 * f, 0, 0);
        p.rot(Bone.ARM_L, 0, 0, 25 * f);
        p.rot(Bone.ARM_R, 0, 0, -25 * f);
        return p;
    }

    /** 소환 포효: 양팔을 들고 고개를 젖힘. f = 0~1. */
    public static Pose p2Roar(float t, float f) {
        Pose p = idle(2, t);
        float shake = (float) Math.sin(t * 2.3) * 2 * f;
        p.rot(Bone.BODY, -18 * f, shake, 0);
        p.rot(Bone.HEAD, -30 * f, 0, 0);
        p.rot(Bone.ARM_L, -100 * f, 0, 30 * f);
        p.rot(Bone.ARM_R, -100 * f, 0, -30 * f);
        return p;
    }

    /** 그로기(5초 기절, 받는 피해 증가): 털썩 주저앉음. f = 0~1. */
    public static Pose p2Stagger(float t, float f) {
        Pose p = idle(2, t);
        p.rot(Bone.BODY, 20 * f, 0, 6 * f);
        p.rot(Bone.HEAD, 25 * f, 0, 0);
        p.rot(Bone.ARM_L, 15 * f, 0, -8 * f);
        p.rot(Bone.ARM_R, 15 * f, 0, 8 * f);
        p.rot(Bone.LEG_L, 8 * f, 0, -6 * f);
        p.rot(Bone.LEG_R, 8 * f, 0, 6 * f);
        p.off(Bone.ROOT, 0, -0.2f * f, 0);
        p.scale(Bone.CORE, 1 + 0.25f * f * (float) Math.abs(Math.sin(t * 0.6)));
        return p;
    }

    /** 사망: 뒤로 젖혔다가 무너짐. f = 0~1. */
    public static Pose death(int phase, float t, float f) {
        Pose p = idle(phase, t);
        float rear = seg(f, 0, 0.4f), fall = segIn(f, 0.4f, 1f);
        p.rot(Bone.BODY, -25 * rear + 70 * fall, 0, 10 * fall);
        p.rot(Bone.HEAD, -30 * rear + 30 * fall, 0, 0);
        p.rot(Bone.ARM_L, -60 * rear + 70 * fall, 0, 20 * rear);
        p.rot(Bone.ARM_R, -60 * rear + 70 * fall, 0, -20 * rear);
        p.rot(Bone.LEG_L, -50 * fall, 0, 0);
        p.rot(Bone.LEG_R, -50 * fall, 0, 0);
        p.off(Bone.ROOT, 0, -1.0f * fall, 0);
        return p;
    }
}
