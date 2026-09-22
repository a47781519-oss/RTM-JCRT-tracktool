package com.tracktool.rail2;

import com.tracktool.rail.RailEnd;
import com.tracktool.rail.TrackSpec;
import com.tracktool.rail.plan.Alignment;
import com.tracktool.rail.plan.ConnectSolver;
import com.tracktool.rail.plan.RailPlan;
import com.tracktool.rail.plan.SCurveSolver;
import com.tracktool.util.Geo;

/**
 * S 形（反向曲线）解算器的离线自检 —— 纯数学，不用开游戏：
 * <pre>
 *   gradlew sCurveTest
 * </pre>
 *
 * <p>每个算例都验四件事：①判定确实走了 S 形分支；②终点<b>位置</b>闭合；
 * ③终点<b>朝向</b>闭合；④曲率序列确实是"正–负"或"负–正"（真的是 S，不是偷偷退化成单弯）。</p>
 *
 * <p>闭合判据取 1 mm / 0.01°，比线上那道 0.5 m / 1° 的关卡严格得多 ——
 * 装配用的切线长公式必须是精确的，不能靠关卡放水。</p>
 */
public final class SCurveSelfTest {

    private SCurveSelfTest() {
    }

    private static int failures;

    /**
     * 造一个端点。{@code yaw} 是 RailEnd 的<b>朝外方向</b>（outwardYaw），
     * 即新轨道从这个接头“向外长”的方向。终点那一端请用 {@link #arrive}。
     */
    private static RailEnd end(double x, double z, double yaw) {
        RailEnd e = new RailEnd();
        e.x = x;
        e.y = 64.0D;
        e.z = z;
        e.outwardYaw = Geo.normalize360(yaw);
        e.outwardPitch = 0.0D;
        e.jointCant = 0.0D;
        e.existingCant = 0.0D;
        // 不造 RailPosition：RTM 的类在离线环境里是 SRG 混淆的，一碰就 NoSuchMethodError。
        // S 形解算全程只用 x/z/outwardYaw/超高，用不着 rp。
        return e;
    }

    /**
     * 终点端：给的是新轨道<b>到达时的朝向</b>。
     * RailEnd.outwardYaw 存的是接头的朝外方向，而新轨道是迎着它开过来的，
     * 所以差 180°——两条同向的平行线，远端接头的 outwardYaw 是反过来指着你的。
     */
    private static RailEnd arrive(double x, double z, double arrivalYaw) {
        return end(x, z, arrivalYaw + 180.0D);
    }

    private static TrackSpec spec() {
        TrackSpec s = new TrackSpec();
        s.mode = TrackSpec.MODE_CONNECT;
        s.cantDeg = 4.0D;
        s.verticalRadius = 15000.0D;
        s.connectAutoSolve = true;
        // S 形下这两个值应当被忽略：故意填成离谱值，看结果会不会被带跑
        s.radiusM = 7;
        s.transitionLength = 999.0D;
        s.autoTransition = false;
        return s;
    }

    private static void run(String name, RailEnd from, RailEnd to, boolean expectS) {
        boolean isS = SCurveSolver.isReverseShape(from, to);
        if (isS != expectS) {
            System.out.printf("%-34s  判定错误：isReverseShape=%s，期望 %s%n", name, isS, expectS);
            failures++;
            return;
        }
        if (!expectS) {
            System.out.printf("%-34s  正确地判为非 S 形%n", name);
            return;
        }
        RailPlan plan = new RailPlan();
        Alignment a = ConnectSolver.solve(spec(), from, to, plan);
        if (a == null) {
            System.out.printf("%-34s  无解：%s%n", name, plan.errorKey);
            failures++;
            return;
        }
        if (!plan.sCurve) {
            System.out.printf("%-34s  走的不是 S 形分支%n", name);
            failures++;
            return;
        }
        a.placeAt(from.x, from.z, from.outwardYaw);
        double[] o = new double[7];
        a.eval(a.length, o);
        double posErr = Math.hypot(o[0] - to.x, o[1] - to.z);
        double yawErr = Math.abs(Geo.angleDiff(o[3], Geo.normalize360(to.outwardYaw + 180.0D)));

        // 曲率必须真的换向一次
        double kmin = 0.0D;
        double kmax = 0.0D;
        for (int i = 0; i <= 400; i++) {
            a.eval(a.length * i / 400.0D, o);
            kmin = Math.min(kmin, o[6]);
            kmax = Math.max(kmax, o[6]);
        }
        boolean reversed = kmin < -1.0E-6D && kmax > 1.0E-6D;

        // 超高必须与曲率同向换号，且中间过 0
        double cmin = 0.0D;
        double cmax = 0.0D;
        for (int i = 0; i <= 400; i++) {
            a.eval(a.length * i / 400.0D, o);
            cmin = Math.min(cmin, o[5]);
            cmax = Math.max(cmax, o[5]);
        }

        boolean ok = posErr < 0.001D && yawErr < 0.01D && reversed;
        if (!ok) {
            failures++;
        }
        StringBuilder shape = new StringBuilder();
        for (com.tracktool.rail.plan.Alignment.ElementInfo e : a.describe()) {
            if (shape.length() > 0) {
                shape.append('-');
            }
            String k = e.typeKey;
            shape.append(k.endsWith("line") ? "直" : (k.endsWith("spiral") ? "缓" : "圆"))
                 .append(String.format("%.0f", e.length));
        }
        System.out.printf("    组成 %s%n", shape);
        System.out.printf("%-34s  %s  R=%7.1f Ls=%5.1f 全长=%8.2f  终点误差=%.6f m / %.6f°  "
                        + "曲率 [%.5f, %.5f]  超高 [%.2f, %.2f]%n",
                name, ok ? "✓" : "✗ 失败",
                plan.solvedRadius, plan.solvedTransition, a.length, posErr, yawErr,
                kmin, kmax, cmin, cmax);
    }

    /** 诊断用：扫 alpha，看"能放下的最大半径"随转角的真实形状。 */
    private static void scan(double leftOffset, double forward) {
        System.out.printf("%n--- 扫描：平行错开 左%.0fm / 前%.0fm ---%n", leftOffset, forward);
        for (double alpha = 0.25D; alpha <= 30.0D; alpha += 0.25D) {
            double r = SCurveSolver.maxRadiusAt(alpha, 0.0D, leftOffset, forward);
            if (r < 0.0D) {
                continue;
            }
            double[] q = SCurveSolver.probe(alpha, 0.0D, leftOffset, forward, r);
            if (q != null) {
                System.out.printf("  alpha=%6.2f°  Rmax=%9.1f  Ls=%6.1f  arc=%7.2f  l1=%7.2f lm=%7.2f l2=%7.2f%n",
                        alpha, r, q[0], q[1], q[2], q[3], q[4]);
            }
        }
    }

    public static void main(String[] args) {
        if (args.length > 0 && "scan".equals(args[0])) {
            scan(4, 200);
            scan(4, 80);
            return;
        }
        System.out.println("=== S 形解算器自检 ===");

        // ① 最典型：两端平行、横向错开（旧解算器只能报 connect_parallel）
        run("平行错开 左4m / 前200m", end(0, 0, 0), arrive(4, 200, 0), true);
        run("平行错开 右4m / 前200m", end(0, 0, 0), arrive(-4, 200, 0), true);
        run("平行错开 左20m / 前300m", end(0, 0, 0), arrive(20, 300, 0), true);
        run("平行错开 左50m / 前600m", end(0, 0, 0), arrive(50, 600, 0), true);
        run("平行错开 左4m / 前80m（紧）", end(0, 0, 0), arrive(4, 80, 0), true);

        // ② 起点朝向不为 0（局部坐标变换必须正确）
        run("平行错开 起点朝向37°", end(100, 200, 37), rotEnd(100, 200, 37, 12, 250, 0), true);

        // ③ 两端不平行、但偏角反号 ⇒ 仍是 S
        run("反向 到达-10° 左错开", end(0, 0, 0), arrive(30, 400, -10), true);
        run("反向 到达+10° 右错开", end(0, 0, 0), arrive(-30, 400, 10), true);
        run("反向 Δ=+30° 大错开", end(0, 0, 0), arrive(-60, 500, 30), true);

        // ④ 不该判成 S 的：普通单向弯道 / 共线直线
        run("单向左弯 45°", end(0, 0, 0), curveEnd(300, 45), false);
        run("单向右弯 45°", end(0, 0, 0), curveEnd(-300, 45), false);
        run("共线直线", end(0, 0, 0), arrive(0, 200, 0), false);

        System.out.println(failures == 0
                ? "=== 全部通过 ==="
                : "=== 失败 " + failures + " 项 ===");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /** 起点在 (x,z) 朝向 yaw0 时，局部偏移 (left, forward) 处、朝向 yaw0+dYaw 的终点。 */
    private static RailEnd rotEnd(double x, double z, double yaw0,
                                  double left, double forward, double dYaw) {
        double wx = x + Geo.leftX(yaw0) * left + Geo.dirX(yaw0) * forward;
        double wz = z + Geo.leftZ(yaw0) * left + Geo.dirZ(yaw0) * forward;
        return arrive(wx, wz, yaw0 + dYaw);
    }

    /** 从原点朝 +Z 出发、半径 r（正=左）、转 turnDeg 之后那一端（用来造"非 S"的算例）。 */
    private static RailEnd curveEnd(double r, double turnDeg) {
        double t = Math.toRadians(turnDeg);
        double cx = Geo.leftX(0.0D) * r;
        double cz = Geo.leftZ(0.0D) * r;
        double a0 = Math.atan2(0.0D - cx, 0.0D - cz);
        double a1 = a0 + (r > 0 ? t : -t);
        double x = cx + Math.sin(a1) * Math.abs(r);
        double z = cz + Math.cos(a1) * Math.abs(r);
        return arrive(x, z, r > 0 ? turnDeg : -turnDeg);
    }
}
