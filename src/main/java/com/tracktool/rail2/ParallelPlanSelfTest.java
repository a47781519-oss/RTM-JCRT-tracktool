package com.tracktool.rail2;

import com.tracktool.rail.RailEnd;
import com.tracktool.rail.TrackSpec;
import com.tracktool.rail.plan.Alignment;
import com.tracktool.rail.plan.PlanBuilder;
import com.tracktool.rail.plan.PlanSegment;
import com.tracktool.rail.plan.RailPlan;
import jp.ngt.rtm.rail.util.RailPosition;

/**
 * 离线复现"铺平行线"的那条真实代码路径（PlanBuilder → placeExactLine 选用的几何），
 * 逐点量两条线之间的横向距离：应当恒等于线间距。
 */
public final class ParallelPlanSelfTest {

    private ParallelPlanSelfTest() {
    }

    private static ExactRailGeometry geoFor(RailPlan plan, double off, RailPosition ra) {
        return Math.abs(off) < 1.0E-9D
                ? new PlanGeometry(plan.alignments.get(0), ra)
                : new OffsetGeometry(plan.alignments.get(0), off, ra);
    }

    private static void run(String name, double cantDeg, int linesLeft, int linesRight, boolean turnLeft) {
        TrackSpec spec = new TrackSpec();
        spec.mode = TrackSpec.MODE_CURVE;
        spec.radiusM = 300;
        spec.angleDeg = 45.0D;
        spec.cantDeg = cantDeg;
        spec.autoTransition = true;
        spec.turnLeft = turnLeft;
        spec.spacingM = 4;
        spec.linesLeft = linesLeft;
        spec.linesRight = linesRight;
        spec.verticalRadius = 15000.0D;

        RailEnd from = new RailEnd();
        double[] snapped = com.tracktool.rail.RailGrid.snap(1000.0D, 2000.0D, 37.0D);
        from.rp = com.tracktool.rail.RailGrid.make(snapped[0], snapped[1], 64.0D, 37.0D, 0.0D, 0);
        from.x = from.rp.posX;
        from.y = from.rp.posY;
        from.z = from.rp.posZ;
        from.outwardYaw = 37.0D;

        RailPlan plan = PlanBuilder.build(spec, from, null);
        if (!plan.ok) {
            System.out.println(name + " 规划失败: " + plan.errorKey);
            return;
        }
        double[] offsets = spec.parallelOffsets();
        System.out.printf("%s  线数=%d offsets=%s%n", name, plan.alignments.size(), java.util.Arrays.toString(offsets));

        // 每条线：取它首段 start 当基准 RP，按 placeExactLine 的规则选几何
        ExactRailGeometry[] geos = new ExactRailGeometry[offsets.length];
        RailPosition[] ras = new RailPosition[offsets.length];
        for (int li = 0; li < offsets.length; li++) {
            RailPosition ra = null;
            for (PlanSegment seg : plan.segments) {
                if (seg.lineIndex == li) {
                    ra = seg.start;
                    break;
                }
            }
            if (ra == null) {
                System.out.println("  线" + li + " 没有分段");
                return;
            }
            ras[li] = ra;
            geos[li] = geoFor(plan, offsets[li], ra);
        }

        // 逐点量：线 li 上的点到线 0 的最短距离，应当 = |offsets[li]|
        Alignment base = plan.alignments.get(0);
        double[] o = new double[7];
        for (int li = 1; li < offsets.length; li++) {
            double worst = 0.0D;
            double worstT = 0.0D;
            double len = geos[li].length();
            for (int i = 0; i <= 200; i++) {
                double t = len * i / 200.0D;
                double px = ras[li].posX + geos[li].x(t);
                double pz = ras[li].posZ + geos[li].z(t);
                double best = Double.MAX_VALUE;
                for (int j = 0; j <= 2000; j++) {
                    base.eval(base.length * j / 2000.0D, o);
                    double d = Math.hypot(px - o[0], pz - o[1]);
                    if (d < best) {
                        best = d;
                    }
                }
                double err = Math.abs(best - Math.abs(offsets[li]));
                if (err > worst) {
                    worst = err;
                    worstT = t;
                }
            }
            System.out.printf("  线%d offset=%+.1f 长度=%.2f 横向距离误差最大 %.3f m（在 t=%.1f）%s%n",
                    li, offsets[li], len, worst, worstT, worst > 0.5D ? "   ← 异常" : "");
        }
    }

    public static void main(String[] args) {
        run("左1 无超高 左转", 0.0D, 1, 0, true);
        run("左1 超高5° 左转", 5.0D, 1, 0, true);
        run("右1 超高5° 左转", 5.0D, 0, 1, true);
        run("左1 超高5° 右转", 5.0D, 1, 0, false);
        run("左2右2 超高5°", 5.0D, 2, 2, true);
    }
}
