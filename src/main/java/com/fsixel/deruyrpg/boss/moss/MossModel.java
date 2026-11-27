package com.fsixel.deruyrpg.boss.moss;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 이끼 수문장 블록 모델 (Bukkit 의존 없음 — tools/ModelPreview 로 정면도를 그릴 수 있게).
 *
 * 좌표: 블록 단위, 원점 = 발밑 중심, +Z = 보스 정면, +X = 보스의 왼쪽(정면에서 보면 오른쪽), +Y = 위.
 * 파트 = 바닐라 BlockData 직육면체 1개 (min 모서리 + 크기, 선택적으로 자기 중심 기준 회전).
 * 본(Bone) 계층으로 포즈를 주면 각 파트의 변환 행렬을 계산한다.
 */
public final class MossModel {

    private MossModel() {}

    public enum Bone {
        ROOT(null), BODY(ROOT), HEAD(BODY), CORE(BODY),
        ARM_L(BODY), FORE_L(ARM_L), ARM_R(BODY), FORE_R(ARM_R),
        LEG_L(ROOT), LEG_R(ROOT);

        public final Bone parent;

        Bone(Bone parent) {
            this.parent = parent;
        }
    }

    /** 파트 하나. block = BlockData 문자열 (예: minecraft:moss_block). */
    public record Part(String block, Bone bone, Vector3f min, Vector3f size, Vector3f rotDeg, boolean glow, int order) {
        public Vector3f center() {
            return new Vector3f(size).mul(0.5f).add(min);
        }
    }

    /** 한 페이즈 모델: 본 피벗 + 파트 목록. */
    public static final class Model {
        public final String name;
        public final Map<Bone, Vector3f> pivots = new EnumMap<>(Bone.class);
        public final List<Part> parts = new ArrayList<>();
        /** 손목(덩굴 발사 위치) — 본 FORE_L/FORE_R 기준 모델 좌표 */
        public Vector3f wristL = new Vector3f(), wristR = new Vector3f();
        /** 내려치는 주먹 중심 */
        public Vector3f fistL = new Vector3f(), fistR = new Vector3f();
        /** 히트박스(슬라임 크기) */
        public int slimeSize;

        Model(String name) {
            this.name = name;
        }

        Model pivot(Bone b, float x, float y, float z) {
            pivots.put(b, new Vector3f(x, y, z));
            return this;
        }

        Model box(String block, Bone bone, float x, float y, float z, float sx, float sy, float sz) {
            return box(block, bone, x, y, z, sx, sy, sz, 0, 0, 0, false);
        }

        Model glow(String block, Bone bone, float x, float y, float z, float sx, float sy, float sz) {
            return box(block, bone, x, y, z, sx, sy, sz, 0, 0, 0, true);
        }

        Model box(String block, Bone bone, float x, float y, float z, float sx, float sy, float sz,
                  float rx, float ry, float rz, boolean glow) {
            parts.add(new Part(block.contains(":") ? block : "minecraft:" + block, bone,
                    new Vector3f(x, y, z), new Vector3f(sx, sy, sz), new Vector3f(rx, ry, rz), glow, parts.size()));
            return this;
        }

        /** 좌우 대칭 복사 (x → -x, 본 L↔R, 블록 상태 east↔west). */
        Model mirror(String block, Bone bone, float x, float y, float z, float sx, float sy, float sz,
                     float rx, float ry, float rz) {
            box(block, bone, x, y, z, sx, sy, sz, rx, ry, rz, false);
            box(flip(block), flip(bone), -x - sx, y, z, sx, sy, sz, rx, -ry, -rz, false);
            return this;
        }

        Model mirror(String block, Bone bone, float x, float y, float z, float sx, float sy, float sz) {
            return mirror(block, bone, x, y, z, sx, sy, sz, 0, 0, 0);
        }

        public Vector3f pivot(Bone b) {
            return pivots.getOrDefault(b, new Vector3f());
        }
    }

    static Bone flip(Bone b) {
        return switch (b) {
            case ARM_L -> Bone.ARM_R;
            case ARM_R -> Bone.ARM_L;
            case FORE_L -> Bone.FORE_R;
            case FORE_R -> Bone.FORE_L;
            case LEG_L -> Bone.LEG_R;
            case LEG_R -> Bone.LEG_L;
            default -> b;
        };
    }

    static String flip(String block) {
        return block.replace("east", "@@").replace("west", "east").replace("@@", "west");
    }

    // ================================================================== 포즈

    /** 본별 회전(도)·이동·배율. 기본 = 정자세. */
    public static final class Pose {
        public final Map<Bone, Vector3f> rot = new EnumMap<>(Bone.class);
        public final Map<Bone, Vector3f> off = new EnumMap<>(Bone.class);
        public final Map<Bone, Float> scale = new EnumMap<>(Bone.class);

        public Pose rot(Bone b, float x, float y, float z) {
            rot.computeIfAbsent(b, k -> new Vector3f()).add(x, y, z);
            return this;
        }

        public Pose off(Bone b, float x, float y, float z) {
            off.computeIfAbsent(b, k -> new Vector3f()).add(x, y, z);
            return this;
        }

        public Pose scale(Bone b, float s) {
            scale.merge(b, s, (a, c) -> a * c);
            return this;
        }
    }

    private static final float DEG = (float) (Math.PI / 180.0);

    /** 본의 모델 공간 변환 행렬 (부모 포함). */
    public static Map<Bone, Matrix4f> boneMatrices(Model m, Pose pose) {
        Map<Bone, Matrix4f> out = new EnumMap<>(Bone.class);
        for (Bone b : Bone.values()) {
            Matrix4f parent = b.parent == null ? new Matrix4f() : out.get(b.parent);
            Vector3f p = m.pivot(b);
            Vector3f r = pose.rot.getOrDefault(b, new Vector3f());
            Vector3f o = pose.off.getOrDefault(b, new Vector3f());
            float s = pose.scale.getOrDefault(b, 1f);
            Matrix4f local = new Matrix4f()
                    .translate(p.x + o.x, p.y + o.y, p.z + o.z)
                    .rotate(new Quaternionf().rotateY(r.y * DEG).rotateX(r.x * DEG).rotateZ(r.z * DEG))
                    .scale(s)
                    .translate(-p.x, -p.y, -p.z);
            out.put(b, new Matrix4f(parent).mul(local));
        }
        return out;
    }

    /**
     * 파트의 최종 변환 (BlockDisplay.setTransformationMatrix 용).
     * BlockDisplay 는 (0,0,0)~(1,1,1) 블록을 그리므로: 본 * T(중심) * 파트회전 * S(크기·grow) * T(-0.5)
     */
    public static Matrix4f partMatrix(Matrix4f bone, Part part, float grow) {
        Vector3f c = part.center();
        Vector3f r = part.rotDeg();
        float g = Math.max(0.001f, grow);
        return new Matrix4f(bone)
                .translate(c)
                .rotate(new Quaternionf().rotateY(r.y * DEG).rotateX(r.x * DEG).rotateZ(r.z * DEG))
                .scale(part.size().x * g, part.size().y * g, part.size().z * g)
                .translate(-0.5f, -0.5f, -0.5f);
    }

    // ================================================================== 1페이즈: 이끼 수문장 (귀여운 바위 덩어리)

    public static final Model PHASE1 = buildPhase1();
    public static final Model PHASE2 = buildPhase2();

    private static Model buildPhase1() {
        Model m = new Model("이끼 수문장");
        m.slimeSize = 6; // 3.1칸 정육면체 히트박스
        m.pivot(Bone.ROOT, 0, 0, 0).pivot(Bone.BODY, 0, 0.8f, 0).pivot(Bone.HEAD, 0, 2.85f, 0)
                .pivot(Bone.CORE, 0, 1.9f, 1.0f)
                .pivot(Bone.ARM_L, 1.6f, 2.55f, 0).pivot(Bone.FORE_L, 1.72f, 1.95f, 0)
                .pivot(Bone.ARM_R, -1.6f, 2.55f, 0).pivot(Bone.FORE_R, -1.72f, 1.95f, 0)
                .pivot(Bone.LEG_L, 0.75f, 0.8f, 0).pivot(Bone.LEG_R, -0.75f, 0.8f, 0);
        m.wristL.set(1.72f, 1.05f, 0.3f);
        m.wristR.set(-1.72f, 1.05f, 0.3f);
        m.fistL.set(1.72f, 1.0f, 0);
        m.fistR.set(-1.72f, 1.0f, 0);

        // 다리: 짧고 두꺼운 돌 기둥
        m.mirror("stone", Bone.LEG_L, 0.25f, 0f, -0.5f, 1.0f, 0.85f, 1.0f);

        // 몸통: 넓은 바위 한 덩어리 (아래는 돌, 위는 이끼)
        m.box("stone", Bone.BODY, -1.35f, 0.7f, -0.95f, 2.7f, 1.5f, 1.9f);
        m.box("moss_block", Bone.BODY, -1.42f, 1.6f, -1.02f, 2.84f, 1.25f, 2.04f);
        // 이끼가 흘러내린 자국 (앞·옆) — 돌이 군데군데 드러남
        m.box("moss_block", Bone.BODY, -1.42f, 1.05f, 0.94f, 0.75f, 0.6f, 0.1f);
        m.box("moss_block", Bone.BODY, 0.2f, 1.3f, 0.94f, 0.55f, 0.35f, 0.1f);
        m.box("moss_block", Bone.BODY, 0.95f, 0.95f, 0.94f, 0.47f, 0.7f, 0.1f);
        m.mirror("moss_block", Bone.BODY, 1.34f, 1.15f, -0.6f, 0.1f, 0.5f, 0.9f);
        m.box("moss_carpet", Bone.BODY, -1.0f, 0.72f, 0.96f, 0.5f, 0.06f, 0.05f);
        // 눈 (작고 동글한 초록 발광) — 1페이즈는 순해 보이게
        m.mirror("verdant_froglight", Bone.BODY, 0.3f, 2.0f, 1.0f, 0.32f, 0.36f, 0.06f);
        m.parts.set(m.parts.size() - 1, glowCopy(m.parts.get(m.parts.size() - 1)));
        m.parts.set(m.parts.size() - 2, glowCopy(m.parts.get(m.parts.size() - 2)));

        // 머리: 낮은 이끼 둔덕 + 꼭대기 금 블록 하나
        m.box("moss_block", Bone.HEAD, -0.85f, 2.84f, -0.7f, 1.7f, 0.36f, 1.4f);
        m.box("gold_block", Bone.HEAD, -0.32f, 3.2f, -0.32f, 0.64f, 0.64f, 0.64f);

        // 팔: 짧고 두꺼움 — 어깨는 이끼, 주먹은 돌, 바깥·앞면에 덩굴
        m.mirror("moss_block", Bone.ARM_L, 1.3f, 1.95f, -0.6f, 0.85f, 0.85f, 1.2f);
        m.mirror("cobblestone", Bone.FORE_L, 1.3f, 0.95f, -0.58f, 0.85f, 1.02f, 1.16f);
        m.mirror("vine[east=true,south=true]", Bone.ARM_L, 1.26f, 1.92f, -0.64f, 0.93f, 0.9f, 1.28f);
        m.mirror("vine[east=true,south=true,north=true]", Bone.FORE_L, 1.26f, 0.98f, -0.62f, 0.93f, 1.0f, 1.24f);

        // 꽃: 어깨 위 + 등
        m.box("poppy", Bone.ARM_L, 1.45f, 2.8f, -0.35f, 0.55f, 0.55f, 0.55f);
        m.box("dandelion", Bone.ARM_L, 1.6f, 2.8f, 0.15f, 0.45f, 0.45f, 0.45f);
        m.box("azure_bluet", Bone.ARM_R, -2.05f, 2.8f, -0.2f, 0.55f, 0.55f, 0.55f);
        m.box("allium", Bone.BODY, -0.95f, 2.85f, -1.0f, 0.55f, 0.6f, 0.55f);
        m.box("cornflower", Bone.BODY, 0.35f, 2.85f, -1.0f, 0.5f, 0.55f, 0.5f);
        m.box("oxeye_daisy", Bone.BODY, -0.2f, 2.85f, -1.05f, 0.45f, 0.5f, 0.45f);
        return m;
    }

    private static Part glowCopy(Part p) {
        return new Part(p.block(), p.bone(), p.min(), p.size(), p.rotDeg(), true, p.order());
    }

    // ================================================================== 2페이즈: 각성 (정글 보스 형태)

    private static Model buildPhase2() {
        Model m = new Model("각성한 이끼 수문장");
        m.slimeSize = 9; // 4.7칸
        m.pivot(Bone.ROOT, 0, 0, 0).pivot(Bone.BODY, 0, 1.6f, 0).pivot(Bone.HEAD, 0, 4.4f, 0.5f)
                .pivot(Bone.CORE, 0, 3.5f, 1.3f)
                .pivot(Bone.ARM_L, 2.3f, 4.6f, 0).pivot(Bone.FORE_L, 2.55f, 3.0f, 0)
                .pivot(Bone.ARM_R, -2.3f, 4.6f, 0).pivot(Bone.FORE_R, -2.75f, 3.1f, 0)
                .pivot(Bone.LEG_L, 1.0f, 1.6f, 0).pivot(Bone.LEG_R, -1.0f, 1.6f, 0);
        m.wristL.set(2.55f, 1.6f, 0.4f);
        m.wristR.set(-3.0f, 0.5f, 0.3f);
        m.fistL.set(2.55f, 0.8f, 0);   // 곤봉(덩굴에 묶인 석상) 중심
        m.fistR.set(-3.0f, 0.3f, 0.2f); // 덩굴 팔 끝

        // ---- 다리: 정글 나무 그루터기 + 맹그로브 뿌리 발
        m.mirror("jungle_log[axis=y]", Bone.LEG_L, 0.45f, 0f, -0.65f, 1.25f, 1.8f, 1.25f);
        m.mirror("mangrove_roots", Bone.LEG_L, 0.25f, 0f, -0.9f, 1.7f, 0.55f, 1.8f);
        m.mirror("jungle_log[axis=x]", Bone.LEG_L, 1.4f, 0f, 0.1f, 0.9f, 0.3f, 0.3f, 0, 0, -12);

        // ---- 몸통: 아래 뿌리흙·돌, 위 거대한 이끼 덩어리 + 굽은 등
        m.box("rooted_dirt", Bone.BODY, -1.75f, 1.45f, -1.2f, 3.5f, 1.45f, 2.4f);
        m.box("mossy_cobblestone", Bone.BODY, -1.5f, 1.4f, 0.85f, 1.2f, 0.9f, 0.45f);
        m.box("stone", Bone.BODY, 0.55f, 1.55f, 0.95f, 0.9f, 0.7f, 0.35f);
        m.box("moss_block", Bone.BODY, -2.05f, 2.8f, -1.45f, 4.1f, 1.95f, 2.6f);
        m.box("moss_block", Bone.BODY, -1.65f, 4.4f, -1.75f, 3.3f, 1.0f, 2.0f); // 굽은 등(혹)
        m.box("mangrove_roots", Bone.BODY, -2.12f, 2.55f, -1.52f, 4.24f, 0.4f, 2.74f); // 허리를 감은 뿌리
        m.box("mangrove_roots", Bone.BODY, -1.7f, 4.6f, -1.85f, 3.4f, 0.35f, 2.2f, 0, 0, 0, false);
        // 가슴: 뿌리 갈비뼈가 벌어진 사이로 자수정 핵
        m.mirror("jungle_wood", Bone.BODY, 0.6f, 2.6f, 1.12f, 0.36f, 1.75f, 0.36f, 0, 0, -8);
        m.box("jungle_wood", Bone.BODY, -1.0f, 4.15f, 1.1f, 2.0f, 0.36f, 0.4f);
        m.box("jungle_wood", Bone.BODY, -0.85f, 2.55f, 1.1f, 1.7f, 0.3f, 0.4f);
        m.glow("amethyst_block", Bone.CORE, -0.55f, 2.95f, 1.05f, 1.1f, 1.1f, 0.5f);
        m.box("amethyst_cluster[facing=up]", Bone.CORE, -0.25f, 3.95f, 1.15f, 0.5f, 0.5f, 0.4f);
        m.box("amethyst_cluster[facing=down]", Bone.CORE, -0.25f, 2.55f, 1.15f, 0.5f, 0.5f, 0.4f);
        m.mirror("amethyst_cluster[facing=east]", Bone.CORE, 0.5f, 3.25f, 1.15f, 0.45f, 0.5f, 0.4f);
        // 흘러내린 덩굴 커튼 (앞)
        m.mirror("vine[south=true]", Bone.BODY, 1.1f, 1.7f, 1.16f, 0.85f, 1.6f, 0.1f);
        // 등: 꽃 덤불·진달래
        m.box("flowering_azalea_leaves", Bone.BODY, -1.4f, 5.2f, -1.6f, 1.2f, 0.7f, 1.3f);
        m.box("azalea_leaves", Bone.BODY, 0.3f, 5.2f, -1.7f, 1.1f, 0.6f, 1.2f);
        m.box("peony[half=lower]", Bone.BODY, -0.3f, 5.3f, -1.2f, 0.7f, 0.7f, 0.7f);
        m.box("peony[half=upper]", Bone.BODY, -0.3f, 6.0f, -1.2f, 0.7f, 0.7f, 0.7f);
        m.box("allium", Bone.BODY, 1.0f, 5.2f, -1.1f, 0.6f, 0.7f, 0.6f);
        m.box("cornflower", Bone.BODY, -1.25f, 5.85f, -1.0f, 0.5f, 0.5f, 0.5f);

        // ---- 머리: 어깨 사이로 낮게 파묻힘, 보라색 눈·입, 금 블록 왕관
        m.box("mossy_cobblestone", Bone.HEAD, -0.8f, 4.1f, 0.35f, 1.6f, 1.15f, 1.35f);
        m.box("moss_block", Bone.HEAD, -0.9f, 5.1f, 0.25f, 1.8f, 0.3f, 1.5f);
        m.mirror("amethyst_block", Bone.HEAD, 0.18f, 4.72f, 1.68f, 0.38f, 0.18f, 0.06f);
        m.parts.set(m.parts.size() - 1, glowCopy(m.parts.get(m.parts.size() - 1)));
        m.parts.set(m.parts.size() - 2, glowCopy(m.parts.get(m.parts.size() - 2)));
        m.glow("crying_obsidian", Bone.HEAD, -0.45f, 4.3f, 1.68f, 0.9f, 0.16f, 0.06f);
        m.box("gold_block", Bone.HEAD, -0.25f, 5.35f, 0.75f, 0.5f, 0.5f, 0.5f, 0, 0, 12, false);

        // ---- 어깨: 크게 솟은 이끼 둔덕 + 위로 뻗은 뿌리 가시 (멀리서 실루엣이 확 달라짐)
        m.box("moss_block", Bone.ARM_L, 1.55f, 3.9f, -1.15f, 1.75f, 1.45f, 2.1f);
        m.box("moss_block", Bone.ARM_R, -3.45f, 3.85f, -1.2f, 1.95f, 1.6f, 2.2f);
        m.box("jungle_log[axis=y]", Bone.ARM_L, 2.3f, 5.1f, -0.6f, 0.32f, 1.7f, 0.32f, 0, 0, -28, false);
        m.box("jungle_log[axis=y]", Bone.ARM_L, 2.0f, 5.1f, 0.25f, 0.28f, 1.2f, 0.28f, 18, 0, -15, false);
        m.box("jungle_log[axis=y]", Bone.ARM_R, -2.75f, 5.2f, -0.7f, 0.34f, 1.9f, 0.34f, 0, 0, 30, false);
        m.box("jungle_log[axis=y]", Bone.ARM_R, -2.25f, 5.2f, 0.2f, 0.3f, 1.35f, 0.3f, 15, 0, 18, false);
        m.box("jungle_log[axis=y]", Bone.ARM_R, -3.3f, 5.0f, -0.1f, 0.26f, 1.1f, 0.26f, 0, 0, 50, false);
        m.box("mangrove_roots", Bone.ARM_L, 1.5f, 5.2f, -1.2f, 1.85f, 0.35f, 2.2f);
        m.box("jungle_leaves", Bone.ARM_R, -3.55f, 5.25f, -1.3f, 2.1f, 0.55f, 1.6f);
        m.box("spore_blossom", Bone.ARM_R, -3.1f, 3.6f, 0.4f, 0.6f, 0.3f, 0.6f);

        // ---- 왼팔(보스 기준 왼쪽): 덩굴에 묶인 석상 곤봉 — 땅까지 닿는 거대한 주먹
        m.box("stone", Bone.ARM_L, 2.0f, 2.85f, -0.6f, 1.15f, 1.3f, 1.15f);
        m.box("mossy_stone_bricks", Bone.FORE_L, 1.95f, 1.55f, -0.65f, 1.25f, 1.4f, 1.3f);
        m.box("chiseled_stone_bricks", Bone.FORE_L, 1.6f, 0.0f, -0.95f, 1.95f, 1.6f, 1.9f);
        m.box("mossy_cobblestone", Bone.FORE_L, 1.75f, 1.45f, -0.7f, 1.6f, 0.4f, 1.4f);
        m.box("vine[east=true,west=true,south=true,north=true]", Bone.FORE_L, 1.55f, 0.15f, -1.0f, 2.05f, 1.5f, 2.0f);
        m.box("mangrove_roots", Bone.FORE_L, 1.5f, 0.75f, -1.05f, 2.15f, 0.3f, 2.1f);

        // ---- 오른팔: 길게 늘어진 덩굴 촉수 + 뿌리 손톱
        m.box("jungle_log[axis=y]", Bone.ARM_R, -3.15f, 2.95f, -0.45f, 0.9f, 1.3f, 0.9f, 0, 0, -6, false);
        m.box("vine[west=true,south=true]", Bone.ARM_R, -3.25f, 2.9f, -0.55f, 1.1f, 1.4f, 1.1f, 0, 0, -6, false);
        m.box("jungle_log[axis=y]", Bone.FORE_R, -3.1f, 1.4f, -0.3f, 0.6f, 1.75f, 0.6f, 0, 0, 8, false);
        m.box("jungle_log[axis=y]", Bone.FORE_R, -3.3f, 0.4f, -0.2f, 0.42f, 1.15f, 0.42f, 0, 0, -10, false);
        m.box("vine[west=true,east=true,south=true]", Bone.FORE_R, -3.35f, 0.2f, -0.45f, 0.95f, 2.9f, 0.95f);
        m.box("jungle_leaves", Bone.FORE_R, -3.35f, 2.2f, -0.5f, 1.0f, 0.6f, 1.0f);
        m.box("jungle_log[axis=y]", Bone.FORE_R, -3.75f, 0f, 0.0f, 0.2f, 0.75f, 0.2f, 0, 0, 40, false);
        m.box("jungle_log[axis=y]", Bone.FORE_R, -3.0f, 0f, 0.35f, 0.2f, 0.7f, 0.2f, -35, 0, 0, false);
        m.box("jungle_log[axis=y]", Bone.FORE_R, -3.1f, 0f, -0.35f, 0.2f, 0.7f, 0.2f, 35, 0, -15, false);
        return m;
    }
}
