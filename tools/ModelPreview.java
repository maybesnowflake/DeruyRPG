import com.fsixel.deruyrpg.boss.moss.MossModel;
import com.fsixel.deruyrpg.boss.moss.MossModel.Bone;
import com.fsixel.deruyrpg.boss.moss.MossModel.Model;
import com.fsixel.deruyrpg.boss.moss.MossModel.Part;
import com.fsixel.deruyrpg.boss.moss.MossModel.Pose;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 개발용: 이끼 수문장 모델 정면·측면도 PNG (플러그인 JAR에는 들어가지 않음).
 * 실행: tools/preview.sh
 */
public class ModelPreview {

    record Face(Path2D poly, double depth, Color color, boolean thin) {}

    static Color color(String b) {
        String n = b.replace("minecraft:", "");
        if (n.startsWith("moss_carpet") || n.startsWith("moss_block")) return new Color(0x5A7A2B);
        if (n.startsWith("mossy_cobblestone")) return new Color(0x6E7A5E);
        if (n.startsWith("mossy_stone_bricks")) return new Color(0x737D68);
        if (n.startsWith("chiseled_stone")) return new Color(0x8C8C8C);
        if (n.startsWith("cobblestone")) return new Color(0x7D7D7D);
        if (n.startsWith("stone")) return new Color(0x8A8A8A);
        if (n.startsWith("gold_block")) return new Color(0xF5D33F);
        if (n.startsWith("vine")) return new Color(0x2F6B12);
        if (n.startsWith("verdant_froglight")) return new Color(0xB9F28A);
        if (n.startsWith("amethyst_block")) return new Color(0xA77CF0);
        if (n.startsWith("amethyst_cluster")) return new Color(0xC9A6FF);
        if (n.startsWith("crying_obsidian")) return new Color(0x7A2BD0);
        if (n.startsWith("jungle_log") || n.startsWith("jungle_wood")) return new Color(0x5C4A26);
        if (n.startsWith("mangrove_roots")) return new Color(0x4A3B2A);
        if (n.startsWith("rooted_dirt")) return new Color(0x8A6446);
        if (n.startsWith("jungle_leaves")) return new Color(0x2E8B1E);
        if (n.startsWith("flowering_azalea")) return new Color(0x6E8A3A);
        if (n.startsWith("azalea_leaves")) return new Color(0x5F7F2C);
        if (n.startsWith("spore_blossom")) return new Color(0xD86BB0);
        if (n.startsWith("poppy")) return new Color(0xD52A2A);
        if (n.startsWith("dandelion")) return new Color(0xF2E23A);
        if (n.startsWith("azure_bluet")) return new Color(0xE8E8F0);
        if (n.startsWith("allium")) return new Color(0xB46BE0);
        if (n.startsWith("cornflower")) return new Color(0x4C6FE0);
        if (n.startsWith("oxeye")) return new Color(0xF0F0E0);
        if (n.startsWith("peony")) return new Color(0xE7A2D8);
        return Color.MAGENTA;
    }

    static int b(String b) { return b.contains("vine") ? 110 : thin(b) ? 200 : 255; }

    static boolean thin(String b) {
        return b.contains("vine") || b.contains("roots") || b.contains("cluster") || b.contains("poppy")
                || b.contains("dandelion") || b.contains("bluet") || b.contains("allium") || b.contains("cornflower")
                || b.contains("oxeye") || b.contains("peony") || b.contains("blossom") || b.contains("carpet");
    }

    static final int[][] FACES = {{0, 1, 3, 2}, {4, 5, 7, 6}, {0, 1, 5, 4}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}};

    /** view: 0 = 정면(+Z에서 바라봄), 1 = 왼쪽 측면(+X에서 바라봄) */
    static void draw(Graphics2D g, Model m, Pose pose, int ox, int oy, double px, int view, String title) {
        Map<Bone, Matrix4f> bones = MossModel.boneMatrices(m, pose);
        List<Face> faces = new ArrayList<>();
        for (Part part : m.parts) {
            Matrix4f mat = MossModel.partMatrix(bones.get(part.bone()), part, 1f);
            Vector3f[] c = new Vector3f[8];
            for (int i = 0; i < 8; i++) {
                c[i] = mat.transformPosition(new Vector3f(i & 1, (i >> 1) & 1, (i >> 2) & 1));
            }
            Color base = color(part.block());
            boolean th = thin(part.block());
            for (int[] f : FACES) {
                Path2D poly = new Path2D.Double();
                double depth = 0;
                for (int k = 0; k < 4; k++) {
                    Vector3f v = c[f[k]];
                    double sx = view == 0 ? v.x : -v.z;
                    double x = ox + sx * px, y = oy - v.y * px;
                    if (k == 0) poly.moveTo(x, y); else poly.lineTo(x, y);
                    depth += view == 0 ? v.z : v.x;
                }
                poly.closePath();
                Vector3f e1 = new Vector3f(c[f[1]]).sub(c[f[0]]), e2 = new Vector3f(c[f[3]]).sub(c[f[0]]);
                Vector3f n = e1.cross(e2).normalize();
                float light = 0.72f + 0.28f * Math.abs(view == 0 ? n.z : n.x) + 0.1f * Math.max(0, Math.abs(n.y));
                Color col = new Color(Math.min(255, (int) (base.getRed() * light)),
                        Math.min(255, (int) (base.getGreen() * light)), Math.min(255, (int) (base.getBlue() * light)),
                        b(part.block()));
                faces.add(new Face(poly, depth / 4, col, th));
            }
        }
        faces.sort((a, b) -> Double.compare(a.depth, b.depth));
        for (Face f : faces) {
            g.setColor(f.color);
            g.fill(f.poly);
            g.setColor(new Color(0, 0, 0, f.thin ? 40 : 90));
            g.draw(f.poly);
        }
        // 바닥선·격자
        g.setColor(new Color(255, 255, 255, 60));
        for (int i = -4; i <= 4; i++) g.drawLine((int) (ox + i * px), oy, (int) (ox + i * px), (int) (oy - 7 * px));
        for (int j = 0; j <= 7; j++) g.drawLine((int) (ox - 4 * px), (int) (oy - j * px), (int) (ox + 4 * px), (int) (oy - j * px));
        g.setColor(Color.WHITE);
        g.drawLine((int) (ox - 4.2 * px), oy, (int) (ox + 4.2 * px), oy);
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        g.drawString(title, (int) (ox - 4 * px), (int) (oy - 7.4 * px));
    }

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "preview.png";
        int px = 60;
        BufferedImage img = new BufferedImage(px * 9 * 4 + 40, px * 8 + 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x1E2328));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        int base = px * 8 + 10;
        Pose p1 = com.fsixel.deruyrpg.boss.moss.MossAnim.idle(1, 0);
        Pose p2 = com.fsixel.deruyrpg.boss.moss.MossAnim.idle(2, 0);
        draw(g, MossModel.PHASE1, p1, px * 9 / 2 + 10, base, px, 0, "1페이즈 정면");
        draw(g, MossModel.PHASE1, p1, px * 9 + px * 9 / 2 + 10, base, px, 1, "1페이즈 측면(왼쪽)");
        draw(g, MossModel.PHASE2, p2, px * 18 + px * 9 / 2 + 10, base, px, 0, "2페이즈 정면");
        draw(g, MossModel.PHASE2, p2, px * 27 + px * 9 / 2 + 10, base, px, 1, "2페이즈 측면(왼쪽)");
        ImageIO.write(img, "png", new File(out));
        System.out.println("wrote " + out);
    }
}
