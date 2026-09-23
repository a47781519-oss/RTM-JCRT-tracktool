package com.tracktool.rail2;

import java.util.HashSet;
import java.util.Set;

/**
 * 分段切点对齐方块边的离线自检（不开游戏）：
 * <pre>
 *   gradlew jointTest
 * </pre>
 *
 * <p>列车在接头处卡死的根因见 {@code ExactRailLayer} 里「接头」那一节：接头落在方块中间时，
 * 包含它的那一列只能归一颗核心，另一侧开过来的车会被夹回端点。本自检验证
 * {@link ExactRailLayer#edgeAlignedCuts} 的切点满足：</p>
 * <ol>
 *   <li>严格递增，每段不短于 2 m；</li>
 *   <li>离等分点不超过 1 m（只挪切点，不改分段数和大致长度）；</li>
 *   <li><b>接头两侧 2 m 内，没有任何一列同时被两侧的中心线经过</b> —— 这就是归属能唯一确定的前提。</li>
 * </ol>
 * <p>直线覆盖 0°~359°（每 7°）× 多种长度与起点；另有圆曲线。</p>
 */
public final class JointAlignSelfTest {

    private JointAlignSelfTest() {
    }

    private static int failures;
    private static int joints;

    /** 直线：从 (0,0) 朝 yaw 方向（RTM 约定 x=sin, z=cos）。 */
    private static ExactRailGeometry straight(final double len, final double yawDeg) {
        final double dx = Math.sin(Math.toRadians(yawDeg));
        final double dz = Math.cos(Math.toRadians(yawDeg));
        return new ExactRailGeometry() {
            public double length() { return len; }
            public double x(double t) { return dx * t; }
            public double z(double t) { return dz * t; }
            public double height(double t) { return 0.0D; }
            public double yaw(double t) { return yawDeg; }
            public double pitch(double t) { return 0.0D; }
            public double roll(double t) { return 0.0D; }
        };
    }

    /** 圆曲线：半径 r，起始朝向 yaw0，左转。 */
    private static ExactRailGeometry arc(final double len, final double r, final double yaw0) {
        return new ExactRailGeometry() {
            public double length() { return len; }
            private double a(double t) { return Math.toRadians(yaw0) + t / r; }
            public double x(double t) { return r * (Math.cos(Math.toRadians(yaw0)) - Math.cos(a(t))); }
            public double z(double t) { return r * (Math.sin(a(t)) - Math.sin(Math.toRadians(yaw0))); }
            public double height(double t) { return 0.0D; }
            public double yaw(double t) { return Math.toDegrees(a(t)); }
            public double pitch(double t) { return 0.0D; }
            public double roll(double t) { return 0.0D; }
        };
    }

    private static long col(ExactRailGeometry g, double bx, double bz, double t) {
        return ((long) Math.floor(bx + g.x(t)) << 32) ^ ((long) Math.floor(bz + g.z(t)) & 0xFFFFFFFFL);
    }

    private static void check(String name, ExactRailGeometry g, double bx, double bz, double segLen) {
        double total = g.length();
        int n = (int) Math.max(1, Math.ceil(total / segLen));
        double[] cuts = ExactRailLayer.edgeAlignedCuts(g, bx, bz, total, n);
        double prev = 0.0D;
        for (int i = 0; i < cuts.length; i++) {
            joints++;
            double t = cuts[i];
            double nominal = total * (i + 1) / n;
            double next = i + 1 < cuts.length ? cuts[i + 1] : total;
            String where = String.format("%s 切点 %d: t=%.4f (等分点 %.3f)", name, i + 1, t, nominal);
            if (t < prev + 2.0D - 1e-9 || next - t < 2.0D - 1e-9) {
                fail(where + " 段长不足 2 m");
            }
            if (Math.abs(t - nominal) > 1.0D + 1e-9) {
                fail(where + " 偏离等分点超过 1 m");
            }
            Set<Long> a = new HashSet<Long>();
            Set<Long> b = new HashSet<Long>();
            for (int k = 1; k <= 40; k++) {
                double d = k * 0.05D;
                if (t - d > prev) {
                    a.add(col(g, bx, bz, t - d));
                }
                if (t + d < next) {
                    b.add(col(g, bx, bz, t + d));
                }
            }
            // 紧贴切点两侧也要分属不同列（切点确实在边上）
            a.add(col(g, bx, bz, t - 1.0E-7D));
            b.add(col(g, bx, bz, t + 1.0E-7D));
            Set<Long> both = new HashSet<Long>(a);
            both.retainAll(b);
            if (!both.isEmpty()) {
                fail(where + " 有 " + both.size() + " 列同时被接头两侧经过（接头不在方块边上）");
            }
            prev = t;
        }
    }

    /** 带坡度的直线（grade = 每米升高，负数为下坡）。 */
    private static ExactRailGeometry slope(final double len, final double yawDeg, final double grade) {
        final double dx = Math.sin(Math.toRadians(yawDeg));
        final double dz = Math.cos(Math.toRadians(yawDeg));
        return new ExactRailGeometry() {
            public double length() { return len; }
            public double x(double t) { return dx * t; }
            public double z(double t) { return dz * t; }
            public double height(double t) { return grade * t; }
            public double yaw(double t) { return yawDeg; }
            public double pitch(double t) { return Math.toDegrees(Math.atan(grade)); }
            public double roll(double t) { return 0.0D; }
        };
    }

    /** RailGrid.packY 的同款量化：y -> {blockY, height}，posY = blockY + (height+1)/16。 */
    private static int[] packY(double y) {
        int y16 = (int) Math.round(y * 16.0D);
        int blockY = Math.floorDiv(y16 - 1, 16);
        return new int[]{blockY, (y16 - 1) - blockY * 16};
    }

    private static int coreChecks;

    /**
     * 核心格：RTM 画钢轨时高度 = 设计高度 + (核心.y − 起点RP.blockY)，所以核心 Y 必须等于 blockY；
     * 该列的轨道格必须在 blockY 或 blockY+1（核心替换它或在它正下方，绝不能浮在钢轨上）；
     * 并且在本段一侧（离起点 ≥ 1 m 的中心线列）。
     */
    private static void checkCore(String name, ExactRailGeometry g, double px, double pz, double designY) {
        int[] py = packY(designY);
        double posY = py[0] + (py[1] + 1) * 0.0625D;
        int bx = (int) Math.floor(px);
        int bz = (int) Math.floor(pz);
        int[] c = ExactRailLayer.coreCellAt(bx, py[0], bz, px, posY, pz, g);
        coreChecks++;
        String where = String.format("%s y=%.4f(blockY=%d)", name, designY, py[0]);
        if (c[1] != py[0]) {
            fail(where + " 核心 Y=" + c[1] + " ≠ blockY —— 钢轨会被画高/画低 " + (c[1] - py[0]) + " 格");
            return;
        }
        boolean fallback = c[0] == bx && c[2] == bz;
        if (fallback) {
            return;                                  // 原生位置：渲染一定对（退路）
        }
        boolean found = false;
        for (double s = 1.0D; s <= g.length() - 1.0D + 1e-9; s += 0.25D) {
            if ((int) Math.floor(px + g.x(s)) == c[0] && (int) Math.floor(pz + g.z(s)) == c[2]) {
                int ry = (int) (posY + g.height(s));
                if (ry == c[1] || ry == c[1] + 1) {
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            fail(where + " 核心列 (" + c[0] + "," + c[2] + ") 不在本段 1 m 之后的中心线上，或那一列轨道格不在 blockY/blockY+1");
        }
    }

    private static int footprintChecks;

    /**
     * 路基占地（{@link ExactRailLayer#footprintOf}）：按 0.25 m 的折线 + 精确格子遍历，
     * 必须覆盖「中心线和两侧偏移线以 5 mm 步长密采样」碰到的每一格，而且不能伸到路基宽度 + 1 格以外。
     */
    private static void checkFootprint(double yawDeg, double startX, double startZ, double len, int halfWidth) {
        double[] offs = new double[1 + 2 * (halfWidth + 1)];
        int k = 0;
        offs[k++] = 0.0D;
        for (int i = 0; i <= halfWidth; i++) {
            offs[k++] = i + 0.25D;
            offs[k++] = -(i + 0.25D);
        }
        double dx = Math.sin(Math.toRadians(yawDeg));
        double dz = Math.cos(Math.toRadians(yawDeg));
        int n = (int) Math.ceil(len / 0.25D);
        double[] xs = new double[n + 1];
        double[] zs = new double[n + 1];
        double[] ys = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            double s = len * i / n;
            xs[i] = startX + dx * s;
            zs[i] = startZ + dz * s;
            ys[i] = 64.0D;
        }
        java.util.Map<Long, Integer> fp = ExactRailLayer.footprintOf(xs, zs, ys, offs);
        footprintChecks++;
        int missing = 0;
        long firstMissing = 0L;
        for (double o : offs) {
            for (double s = 0.0D; s <= len + 1e-9; s += 0.005D) {
                double x = startX + dx * s - dz * o;
                double z = startZ + dz * s + dx * o;
                long key = ((long) (int) Math.floor(x) << 32) ^ (((int) Math.floor(z)) & 0xFFFFFFFFL);
                if (!fp.containsKey(key)) {
                    if (missing == 0) {
                        firstMissing = key;
                    }
                    missing++;
                }
            }
        }
        if (missing > 0) {
            fail(String.format("路基占地 yaw=%.0f hw=%d 漏了密采样碰到的格子（首个 %d,%d）", yawDeg, halfWidth,
                    (int) (firstMissing >> 32), (int) firstMissing));
        }
        double maxOff = halfWidth + 0.25D + 1.5D;       // 最外一条线 + 一格对角
        for (Long key : fp.keySet()) {
            double cx = (int) (key >> 32) + 0.5D - startX;
            double cz = (int) key.longValue() + 0.5D - startZ;
            double along = cx * dx + cz * dz;
            double across = Math.abs(-cx * dz + cz * dx);
            if (across > maxOff || along < -1.5D || along > len + 1.5D) {
                fail(String.format("路基占地 yaw=%.0f hw=%d 多出一格离中心线 %.2f m（路基宽度外）", yawDeg, halfWidth, across));
                break;
            }
        }
    }

    private static void fail(String msg) {
        failures++;
        if (failures <= 20) {
            System.out.println("  ✗ " + msg);
        }
    }

    public static void main(String[] args) {
        double[][] starts = {{100.0, 50.5}, {20.5, 7.0}, {10.0, 10.0}, {0.0, 0.5}, {-37.0, 12.5}};
        double[] lengths = {23.0, 50.0, 97.0, 120.0, 341.6, 999.0};
        for (double[] st : starts) {
            for (double len : lengths) {
                for (int yaw = 0; yaw < 360; yaw += 7) {
                    check(String.format("直线 起点(%.1f,%.1f) L=%.1f yaw=%d", st[0], st[1], len, yaw),
                            straight(len, yaw), st[0], st[1], 20.0D);
                }
                check(String.format("直线 起点(%.1f,%.1f) L=%.1f yaw=45", st[0], st[1], len),
                        straight(len, 45.0D), st[0], st[1], 20.0D);
            }
        }
        double[] radii = {60.0D, 300.0D, 1200.0D};
        for (double r : radii) {
            for (int yaw = 0; yaw < 360; yaw += 30) {
                check(String.format("圆曲线 R=%.0f yaw0=%d", r, yaw), arc(Math.min(r * 1.5D, 400.0D), r, yaw),
                        100.0D, 50.5D, 20.0D);
            }
        }
        // 核心格：平地（各种小数高度，含整数高度 —— RP 会存成下一格 + 15/16）、上下坡 ±10%、各方向
        double[] heights = {73.0D, 73.03125D, 73.0625D, 73.5D, 73.9375D, 73.97D, 112.0D, 64.25D};
        double[] grades = {0.0D, 0.01D, -0.01D, 0.04D, -0.04D, 0.10D, -0.10D};
        for (double y0 : heights) {
            for (double gr : grades) {
                for (int yaw = 0; yaw < 360; yaw += 15) {
                    checkCore(String.format("核心 坡度%.2f yaw=%d", gr, yaw), slope(19.0D, yaw, gr), 100.0D, 50.5D, y0);
                    checkCore(String.format("核心(短段) 坡度%.2f yaw=%d", gr, yaw), slope(3.0D, yaw, gr), 100.0D, 50.5D, y0);
                }
            }
        }

        for (int hw = 1; hw <= 2; hw++) {
            for (int yaw = 0; yaw < 360; yaw += 7) {
                checkFootprint(yaw, 100.3D, 50.7D, 60.0D, hw);
            }
        }

        System.out.println(failures == 0
                ? "[jointTest] " + footprintChecks + " 条路基占地无漏格、无越界；"
                        + joints + " 个接头全部落在方块边上，两侧无共用列；" + coreChecks
                        + " 个核心格 Y 均等于起点 blockY、不浮在钢轨上 —— 通过"
                : "[jointTest] " + failures + " 项失败（共 " + joints + " 个接头）");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
