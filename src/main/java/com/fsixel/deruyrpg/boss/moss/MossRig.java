package com.fsixel.deruyrpg.boss.moss;

import com.fsixel.deruyrpg.boss.moss.MossModel.Bone;
import com.fsixel.deruyrpg.boss.moss.MossModel.Model;
import com.fsixel.deruyrpg.boss.moss.MossModel.Part;
import com.fsixel.deruyrpg.boss.moss.MossModel.Pose;
import com.fsixel.deruyrpg.util.Targets;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.persistence.PersistentDataType;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 모델 1개(페이즈 1 또는 2)를 BlockDisplay 묶음으로 보여주는 리그.
 * 모든 디스플레이는 yaw/pitch 0, 같은 위치(앵커)에 소환하고 transformation 행렬로만 배치한다.
 * persistent=false + markSummon + BOSS_KEY → 서버 종료·/reload·청크 언로드 때 남지 않음.
 */
public final class MossRig {

    /** 보스가 만든 모든 엔티티(표시 블록·히트박스·연출·졸개)에 붙는 태그. /rpg boss check 로 남은 개수 확인. */
    public static final NamespacedKey BOSS_KEY = new NamespacedKey("deruyrpg", "moss_boss");

    private final Model model;
    private final List<BlockDisplay> displays = new ArrayList<>();
    private Location anchor;
    private float yaw;
    private Pose pose = new Pose();
    private Map<Bone, Matrix4f> bones;

    public MossRig(Model model, Location anchor, float yaw, Pose pose, float grow) {
        this.model = model;
        this.anchor = flat(anchor);
        this.yaw = yaw;
        this.pose = pose;
        this.bones = MossModel.boneMatrices(model, pose);
        for (Part part : model.parts) {
            BlockDisplay d = anchor.getWorld().spawn(this.anchor, BlockDisplay.class, e -> {
                e.setBlock(Bukkit.createBlockData(part.block()));
                e.setPersistent(false);
                e.setViewRange(2.0f);
                e.setDisplayWidth(12f);
                e.setDisplayHeight(12f);
                e.setTeleportDuration(2);
                e.setInterpolationDuration(0);
                if (part.glow()) e.setBrightness(new Display.Brightness(15, 15));
                tag(e);
                e.setTransformationMatrix(matrix(part, grow));
            });
            displays.add(d);
        }
        if (!displays.isEmpty()) {
            displays.getFirst().setShadowRadius(model.slimeSize * 0.4f);
            displays.getFirst().setShadowStrength(0.8f);
        }
    }

    public static void tag(org.bukkit.entity.Entity e) {
        e.getPersistentDataContainer().set(BOSS_KEY, PersistentDataType.BYTE, (byte) 1);
        Targets.markSummon(e);
    }

    public static boolean isTagged(org.bukkit.entity.Entity e) {
        return e.getPersistentDataContainer().has(BOSS_KEY, PersistentDataType.BYTE);
    }

    private static Location flat(Location l) {
        Location c = l.clone();
        c.setYaw(0);
        c.setPitch(0);
        return c;
    }

    public Model model() {
        return model;
    }

    public List<BlockDisplay> displays() {
        return displays;
    }

    private Matrix4f matrix(Part part, float grow) {
        return new Matrix4f().rotateY((float) Math.toRadians(-yaw))
                .mul(MossModel.partMatrix(bones.get(part.bone()), part, grow));
    }

    /** 포즈 적용. grow = 파트별 크기 배율(null 이면 전부 1). interp = 보간 틱. */
    public void apply(Pose pose, Location anchor, float yaw, float[] grow, int interp) {
        this.pose = pose;
        this.yaw = yaw;
        this.bones = MossModel.boneMatrices(model, pose);
        Location flat = flat(anchor);
        boolean moved = flat.distanceSquared(this.anchor) > 1e-4;
        this.anchor = flat;
        for (int i = 0; i < displays.size(); i++) {
            BlockDisplay d = displays.get(i);
            if (!d.isValid()) continue;
            if (moved) d.teleport(flat);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(interp);
            d.setTransformationMatrix(matrix(model.parts.get(i), grow == null ? 1f : grow[i]));
        }
    }

    /** 현재 포즈에서 모델 좌표(본 기준) → 월드 좌표. */
    public Location world(Bone bone, Vector3f modelPoint) {
        return toWorld(model, pose, bone, modelPoint, anchor, yaw);
    }

    /** 임의 포즈에서 모델 좌표 → 월드 좌표 (내려치기 예측 지점 계산용). */
    public static Location toWorld(Model model, Pose pose, Bone bone, Vector3f modelPoint, Location anchor, float yaw) {
        Matrix4f m = new Matrix4f().rotateY((float) Math.toRadians(-yaw)).mul(MossModel.boneMatrices(model, pose).get(bone));
        Vector3f v = m.transformPosition(new Vector3f(modelPoint));
        return anchor.clone().add(v.x, v.y, v.z);
    }

    /** 사망 연출: 각 블록이 제각각 무너져 내림 (보간 ticks). */
    public void crumble(int ticks) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < displays.size(); i++) {
            BlockDisplay d = displays.get(i);
            if (!d.isValid()) continue;
            Part part = model.parts.get(i);
            Matrix4f m = matrix(part, 0.85f);
            Vector3f pos = m.getTranslation(new Vector3f());
            Matrix4f fall = new Matrix4f()
                    .translate(r.nextFloat(-0.8f, 0.8f), -pos.y * 0.9f - 0.2f, r.nextFloat(-0.8f, 0.8f))
                    .mul(m)
                    .rotateLocalY(r.nextFloat(-0.6f, 0.6f));
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(ticks);
            d.setTransformationMatrix(fall);
        }
    }

    /** 부품 하나의 현재 월드 중심 (파티클용). */
    public Location partCenter(int index) {
        Part p = model.parts.get(index);
        return world(p.bone(), p.center());
    }

    public void remove() {
        for (BlockDisplay d : displays) d.remove();
        displays.clear();
    }
}
