import com.fsixel.deruyrpg.boss.moss.MossAnim;
import com.fsixel.deruyrpg.boss.moss.MossModel;
import com.fsixel.deruyrpg.boss.moss.MossModel.Bone;
import com.fsixel.deruyrpg.boss.moss.MossModel.Model;
import com.fsixel.deruyrpg.boss.moss.MossModel.Pose;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/** 개발용: 핵심 프레임 포즈 확인 + 내려치기 순간 주먹 높이 출력. */
public class AnimCheck {
    static Vector3f pt(Model m, Pose p, Bone b, Vector3f v) {
        return MossModel.boneMatrices(m, p).get(b).transformPosition(new Vector3f(v));
    }

    public static void main(String[] a) throws Exception {
        Model m1 = MossModel.PHASE1, m2 = MossModel.PHASE2;
        for (int t : new int[]{0, MossAnim.P1_SLAM_RAISE, MossAnim.P1_SLAM_HIT}) {
            System.out.printf("P1 내려치기 t=%d 왼주먹 %s%n", t, pt(m1, MossAnim.p1Slam(t, true), Bone.FORE_L, m1.fistL).toString(java.text.NumberFormat.getNumberInstance()));
        }
        System.out.printf("P1 회전 손목 %s%n", pt(m1, MossAnim.p1Spin(20, 1), Bone.FORE_L, m1.wristL).toString(java.text.NumberFormat.getNumberInstance()));
        System.out.printf("P1 가시 오른손 %s%n", pt(m1, MossAnim.p1Reach(14, 1), Bone.FORE_R, m1.fistR).toString(java.text.NumberFormat.getNumberInstance()));
        for (int t : new int[]{0, MossAnim.P2_SLAM_RAISE, MossAnim.P2_SLAM_HIT}) {
            System.out.printf("P2 곤봉 t=%d %s%n", t, pt(m2, MossAnim.p2Slam(t), Bone.FORE_L, m2.fistL).toString(java.text.NumberFormat.getNumberInstance()));
        }
        System.out.printf("P2 덩굴 꽂기 끝 %s%n", pt(m2, MossAnim.p2VinePlunge(MossAnim.P2_VINE_HIT), Bone.FORE_R, m2.fistR).toString(java.text.NumberFormat.getNumberInstance()));

        Object[][] frames = {
                {m1, MossAnim.p1Slam(MossAnim.P1_SLAM_RAISE, true), "P1 내려치기(들기)"},
                {m1, MossAnim.p1Slam(MossAnim.P1_SLAM_HIT, true), "P1 내려치기(착지)"},
                {m1, MossAnim.p1Spin(20, 1), "P1 회전 덩굴"},
                {m1, MossAnim.p1Reach(14, 1), "P1 가시 발아"},
                {m1, MossAnim.p1Hunch(24, 1), "각성: 숙임"},
                {m2, MossAnim.p2Rise(90, 0.75f), "각성: 포효"},
                {m2, MossAnim.p2Slam(MossAnim.P2_SLAM_RAISE), "P2 곤봉(들기)"},
                {m2, MossAnim.p2Slam(MossAnim.P2_SLAM_HIT), "P2 곤봉(착지)"},
                {m2, MossAnim.p2VinePlunge(MossAnim.P2_VINE_HIT), "P2 덩굴 꽂기"},
                {m2, MossAnim.p2Stagger(10, 1), "P2 그로기"},
        };
        int px = 40, cell = px * 9;
        BufferedImage img = new BufferedImage(cell * 5, (px * 8 + 20) * 4, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x1E2328));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        for (int i = 0; i < frames.length; i++) {
            int col = i % 5, row = (i / 5) * 2;
            for (int v = 0; v < 2; v++) {
                int ox = col * cell + cell / 2, oy = (row + v) * (px * 8 + 20) + px * 8 + 10;
                ModelPreview.draw(g, (Model) frames[i][0], (Pose) frames[i][1], ox, oy, px, v, frames[i][2] + (v == 0 ? " 정면" : " 측면"));
            }
        }
        ImageIO.write(img, "png", new File(a.length > 0 ? a[0] : "anim.png"));
    }
}
