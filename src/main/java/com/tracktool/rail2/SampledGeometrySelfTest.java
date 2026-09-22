package com.tracktool.rail2;

import com.tracktool.rail.plan.Alignment;
import com.tracktool.rail.plan.ArcElement;
import com.tracktool.rail.plan.CantProfile;
import com.tracktool.rail.plan.Element;
import com.tracktool.rail.plan.SpiralElement;
import com.tracktool.rail.plan.VerticalProfile;

import java.util.ArrayList;
import java.util.List;

/** 纯 Java 离线自检（不启动游戏、不碰 RTM）：
 *  ① PlanGeometry 是否把 plan 的 Alignment 正确转成"相对起点"的量；
 *  ② SampledGeometry（送给客户端 / 存档的点表）与源几何的逐点偏差有多大。
 *
 *  <p>运行：{@code java -cp build/classes/java/main com.tracktool.rail2.SampledGeometrySelfTest} </p> */
public final class SampledGeometrySelfTest {

    private SampledGeometrySelfTest() {
    }

    /** 照 PlanBuilder.buildCurve 的结构搭一条"缓和+圆+缓和"线形。 */
    private static Alignment curve(double r, double deltaDeg, double l, double rise, double rv,
                                   double x0, double y0, double z0, double yaw0, double cantDeg) {
        double sign = 1.0D;
        double kappa = sign / r;
        double deltaRad = Math.toRadians(deltaDeg);
        double spiralTurn = l / (2.0D * r);
        double arcLen = (deltaRad - 2.0D * spiralTurn) * r;
        List<Element> els = new ArrayList<Element>();
        els.add(new SpiralElement(0.0D, kappa, l));
        els.add(new ArcElement(sign * r, arcLen));
        els.add(new SpiralElement(kappa, 0.0D, l));
        double total = l + arcLen + l;
        VerticalProfile vp = VerticalProfile.create(total, y0, 0.0D, rise, rv);
        CantProfile cp = CantProfile.rampPlateauRamp(total, l, l, cantDeg);
        Alignment al = new Alignment(els, vp, cp);
        al.placeAt(x0, z0, yaw0);
        return al;
    }

    public static void main(String[] args) {
        double x0 = 1024.5D;
        double y0 = 64.0D;
        double z0 = -2048.5D;
        Alignment al = curve(300.0D, 45.0D, 40.0D, 6.0D, 15000.0D, x0, y0, z0, 37.0D, 4.0D);
        PlanGeometry plan = new PlanGeometry(al, x0, y0, z0);

        System.out.printf("线形长度: %.6f m（手算 2*40 + (45°-2*40/600)*300 = %.6f）%n",
                plan.length(), 80.0D + (Math.toRadians(45.0D) - 80.0D / 600.0D) * 300.0D);
        System.out.printf("起点相对量: x=%.9f z=%.9f y=%.9f （应当全为 0）%n",
                plan.x(0.0D), plan.z(0.0D), plan.height(0.0D));

        SampledGeometry sampled = SampledGeometry.of(plan);
        System.out.printf("采样点数: %d（间距 %.2f m）%n", sampled.pointCount(),
                plan.length() / (sampled.pointCount() - 1));

        double maxPos = 0.0D;
        double maxY = 0.0D;
        double maxYaw = 0.0D;
        double maxPitch = 0.0D;
        double maxRoll = 0.0D;
        int n = 20000;
        for (int i = 0; i <= n; i++) {
            double t = plan.length() * i / n;
            double dx = sampled.x(t) - plan.x(t);
            double dz = sampled.z(t) - plan.z(t);
            maxPos = Math.max(maxPos, Math.sqrt(dx * dx + dz * dz));
            maxY = Math.max(maxY, Math.abs(sampled.height(t) - plan.height(t)));
            double dy = Math.abs(normalize(sampled.yaw(t) - plan.yaw(t)));
            maxYaw = Math.max(maxYaw, dy);
            maxPitch = Math.max(maxPitch, Math.abs(sampled.pitch(t) - plan.pitch(t)));
            maxRoll = Math.max(maxRoll, Math.abs(sampled.roll(t) - plan.roll(t)));
        }
        System.out.printf("点表 vs 源几何最大偏差: 平面 %.6f m, 高程 %.6f m, 朝向 %.6f°, 坡度 %.6f°, 超高 %.6f%n",
                maxPos, maxY, maxYaw, maxPitch, maxRoll);

        // 里程反查（RTM 的 getNearlestPoint 用）
        double tp = plan.length() * 0.37D;
        double back = sampled.nearestT(sampled.x(tp), sampled.z(tp));
        System.out.printf("里程反查偏差: %.6f m%n", Math.abs(back - tp));

        boolean ok = maxPos < 0.02D && maxY < 0.02D && maxYaw < 0.2D && Math.abs(back - tp) < 0.5D
                && Math.abs(plan.x(0.0D)) < 1.0E-9D && Math.abs(plan.z(0.0D)) < 1.0E-9D;
        System.out.println(ok ? "结论: PASS" : "结论: FAIL");
        if (!ok) {
            System.exit(1);
        }
    }

    private static double normalize(double deg) {
        while (deg > 180.0D) {
            deg -= 360.0D;
        }
        while (deg < -180.0D) {
            deg += 360.0D;
        }
        return deg;
    }
}
