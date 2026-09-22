package com.tracktool.rail.plan;

/**
 * 整链格点选点（F1′）。
 *
 * <p>背景：RTM 的轨道锚点只能落在半格网格上（x/z 皆为整数或半整数，且不可同时为半整数），
 * 而设计线形是解析的。旧做法是"每个节点各自取最近的合法格点"，导致横向偏差逐节点正负交替
 * —— 观感上就是用户反馈的"类似蚯蚓的左右抖动"（测试 G8/G14）。</p>
 *
 * <p>本类用动态规划在"每个节点的全部邻近合法格点"中选一组，使
 * {@code Σ (实际转角 − 设计转角)² + 0.01 × 位置偏移} 最小，即用"离设计线稍远一点、但偏差平滑变化"
 * 换取抖动大幅下降。离线原型（tools/sim_lattice_dp.py，R=300/45°、20 m 分段、14 节点）实测：
 * 抖动最大 0.9141 → 0.3003（−67%），抖动均值 0.2891 → 0.1818（−37%），
 * 静态横向偏差最大 0.315 → 0.432（可接受）。</p>
 *
 * <p><b>状态</b>：算法已就位并单独可测；尚未接入 {@link PlanBuilder}
 * （接入 = 收集 idealX/idealZ/yaw 后调用 {@link #fit}，把返回值写回节点数组）。
 * 详见 docs/问题档案-放样精度与核心缺口.md 第 34 轮的待实施规格。</p>
 */
public final class LatticeChain {

    private static final double POS_WEIGHT = 0.01D;

    private LatticeChain() {
    }

    /**
     * @param idealX 设计线形上各节点的 x
     * @param idealZ 设计线形上各节点的 z
     * @param yaw    各节点的设计切线朝向（弧度，RTM 约定 yaw = atan2(dx, dz)）
     * @return double[n][2]，每个节点选定的 (x, z)
     */
    public static double[][] fit(double[] idealX, double[] idealZ, double[] yaw) {
        int n = idealX.length;
        double[][] out = new double[n][2];
        if (n == 0) {
            return out;
        }
        if (n == 1) {
            double[] c = nearest(idealX[0], idealZ[0]);
            out[0][0] = c[0];
            out[0][1] = c[1];
            return out;
        }
        double[][][] cand = new double[n][][];
        for (int i = 0; i < n; i++) {
            cand[i] = window(idealX[i], idealZ[i], i == 0 || i == n - 1 ? 1 : 1);
        }
        // 首节点固定为最近格点，作为整链锚点
        double[] first = nearest(idealX[0], idealZ[0]);
        cand[0] = new double[][]{first};

        int m = cand[1].length;
        double[][] cost = new double[n][];
        int[][] back = new int[n][];
        cost[1] = new double[m];
        back[1] = new int[m];
        for (int j = 0; j < m; j++) {
            cost[1][j] = POS_WEIGHT * dist(cand[1][j], idealX[1], idealZ[1]);
            back[1][j] = 0;
        }
        // 状态 (k-1 的候选 i, k 的候选 j)，代价含 k-2 的候选
        int[][][] prevIdx = new int[n][][];
        prevIdx[1] = new int[m][];
        for (int j = 0; j < m; j++) {
            prevIdx[1][j] = new int[]{0};
        }
        for (int k = 2; k < n; k++) {
            int mk = cand[k].length;
            cost[k] = new double[mk];
            back[k] = new int[mk];
            prevIdx[k] = new int[mk][];
            for (int j = 0; j < mk; j++) {
                double best = Double.MAX_VALUE;
                int bestI = -1;
                int bestH = -1;
                double pen = POS_WEIGHT * dist(cand[k][j], idealX[k], idealZ[k]);
                for (int i = 0; i < m; i++) {
                    if (cost[k - 1][i] == Double.MAX_VALUE) {
                        continue;
                    }
                    for (int h : prevIdx[k - 1][i]) {
                        double err = turn(cand[k - 2][h], cand[k - 1][i], cand[k][j])
                                - wrapPi(yaw[k] - yaw[k - 1]);
                        double v = cost[k - 1][i] + err * err + pen;
                        if (v < best) {
                            best = v;
                            bestI = i;
                            bestH = h;
                        }
                    }
                }
                cost[k][j] = best == Double.MAX_VALUE ? Double.MAX_VALUE : best;
                back[k][j] = bestI;
                prevIdx[k][j] = new int[]{bestH};
            }
        }
        int j = 0;
        double best = Double.MAX_VALUE;
        for (int t = 0; t < cost[n - 1].length; t++) {
            if (cost[n - 1][t] < best) {
                best = cost[n - 1][t];
                j = t;
            }
        }
        out[n - 1][0] = cand[n - 1][j][0];
        out[n - 1][1] = cand[n - 1][j][1];
        for (int k = n - 1; k >= 1; k--) {
            int i = back[k][j];
            if (i < 0) {
                i = 0;
            }
            out[k - 1][0] = cand[k - 1][i][0];
            out[k - 1][1] = cand[k - 1][i][1];
            j = i;
        }
        return out;
    }

    /** ±1 方块窗口内的全部合法格点。 */
    private static double[][] window(double px, double pz, int radius) {
        int bx = (int) Math.floor(px);
        int bz = (int) Math.floor(pz);
        double[][] tmp = new double[(2 * radius + 1) * (2 * radius + 1) * 8][];
        int k = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int a = -1; a <= 1; a++) {
                    for (int b = -1; b <= 1; b++) {
                        if (a == 0 && b == 0) {
                            continue;
                        }
                        tmp[k++] = new double[]{bx + dx + 0.5D + a * 0.5D, bz + dz + 0.5D + b * 0.5D};
                    }
                }
            }
        }
        double[][] out = new double[k][];
        System.arraycopy(tmp, 0, out, 0, k);
        return out;
    }

    private static double[] nearest(double px, double pz) {
        double[][] c = window(px, pz, 0);
        double[] best = c[0];
        double bd = Double.MAX_VALUE;
        for (double[] p : c) {
            double d = dist(p, px, pz);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private static double dist(double[] p, double x, double z) {
        return Math.hypot(p[0] - x, p[1] - z);
    }

    private static double turn(double[] a, double[] b, double[] c) {
        double t1 = Math.atan2(b[0] - a[0], b[1] - a[1]);
        double t2 = Math.atan2(c[0] - b[0], c[1] - b[1]);
        return wrapPi(t2 - t1);
    }

    private static double wrapPi(double a) {
        while (a > Math.PI) {
            a -= 2 * Math.PI;
        }
        while (a < -Math.PI) {
            a += 2 * Math.PI;
        }
        return a;
    }
}
