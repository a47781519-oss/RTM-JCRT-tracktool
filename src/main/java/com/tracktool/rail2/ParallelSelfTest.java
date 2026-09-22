package com.tracktool.rail2;

import com.tracktool.rail.plan.Alignment;
import com.tracktool.rail.plan.ArcElement;
import com.tracktool.rail.plan.CantProfile;
import com.tracktool.rail.plan.Element;
import com.tracktool.rail.plan.LineElement;
import com.tracktool.rail.plan.SpiralElement;
import com.tracktool.rail.plan.VerticalProfile;
import com.tracktool.util.Geo;

import java.util.ArrayList;
import java.util.List;

/**
 * 离线自检：平行线（等距曲线）到底偏了多少。
 *
 * <p>判据：平行线上任一点，到基准线的**垂距**应当恒等于设定间距，且平行线的
 * 起点/终点应当正好落在基准线起点/终点的法线方向上（否则接着铺下一段时，
 * 中心线对得上、其它线对不上 —— 正是用户看到的现象）。</p>
 */
public final class ParallelSelfTest {

    private ParallelSelfTest() {
    }

    /** 与 PlanBuilder.buildCurve 同构：缓和 + 圆 + 缓和。 */
    private static Alignment curve(double r, double deltaDeg, double l, double x0, double z0, double yaw0) {
        double kappa = 1.0D / r;
        double deltaRad = Math.toRadians(deltaDeg);
        double spiralTurn = l / (2.0D * r);
        double arcLen = (deltaRad - 2.0D * spiralTurn) * r;
        List<Element> els = new ArrayList<Element>();
        els.add(new SpiralElement(0.0D, kappa, l));
        els.add(new ArcElement(r, arcLen));
        els.add(new SpiralElement(kappa, 0.0D, l));
        double total = l + arcLen + l;
        Alignment al = new Alignment(els, VerticalProfile.create(total, 64.0D, 0.0D, 0.0D, 15000.0D),
                CantProfile.flat(total, 0.0D));
        al.placeAt(x0, z0, yaw0);
        return al;
    }

    /** 与 PlanBuilder.offsetAlignment 现行实现同构（含本轮的等距换算）。 */
    private static Alignment offsetAlignment(Alignment base, double offset) {
        List<Element> elements = new ArrayList<Element>();
        double baseYaw = base.startYaw();
        double ox = base.startX() + Geo.leftX(baseYaw) * offset;
        double oz = base.startZ() + Geo.leftZ(baseYaw) * offset;
        for (Element e : base.elements) {
            double[] p0 = new double[2];
            e.pointAt(0.0D, p0);
            double yaw = e.yawAt(0.0D);
            double nx = p0[0] + Geo.leftX(yaw) * offset;
            double nz = p0[1] + Geo.leftZ(yaw) * offset;
            Element c;
            if (e instanceof LineElement) {
                c = new LineElement(e.length);
            } else if (e instanceof ArcElement) {
                ArcElement a = (ArcElement) e;
                double r = a.signedRadius;
                double rNew = r - offset;
                if (Math.abs(rNew) < 1.0E-3D) {
                    rNew = Math.signum(r) * 1.0E-3D;
                }
                double turn = Math.abs(a.length / r);
                c = new ArcElement(rNew, Math.abs(rNew) * turn);
            } else {
                double k0 = e.curvatureAt(0.0D);
                double k1 = e.curvatureAt(e.length);
                double turn = e.turnAt(e.length) * Geo.RAD;
                double k0n = k0 / clamp(1.0D - k0 * offset);
                double k1n = k1 / clamp(1.0D - k1 * offset);
                double lenNew = Math.max(0.5D, e.length - offset * turn);
                c = new SpiralElement(k0n, k1n, lenNew);
            }
            c.placeAt(nx, nz, yaw);
            elements.add(c);
        }
        Alignment al = new Alignment(elements, base.vertical, base.cant);
        al.placeAt(ox, oz, baseYaw);
        return al;
    }

    private static double clamp(double d) {
        return Math.abs(d) < 1.0E-3D ? (d < 0.0D ? -1.0E-3D : 1.0E-3D) : d;
    }

    /** 平行线上一点到基准线的最短距离。 */
    private static double distToBase(Alignment base, double px, double pz) {
        double best = Double.MAX_VALUE;
        double[] o = new double[7];
        int n = 4000;
        for (int i = 0; i <= n; i++) {
            base.eval(base.length * i / n, o);
            double dx = px - o[0];
            double dz = pz - o[1];
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < best) {
                best = d;
            }
        }
        return best;
    }

    public static void main(String[] args) {
        Alignment base = curve(300.0D, 36.0D, 40.0D, 1000.0D, 2000.0D, 37.0D);
        double[] o = new double[7];
        base.eval(base.length, o);
        double endYaw = o[3];
        double endX = o[0];
        double endZ = o[1];
        System.out.printf("基准线: L=%.3f 起点(%.3f,%.3f) 终点(%.3f,%.3f) 终点朝向 %.4f°%n",
                base.length, base.startX(), base.startZ(), endX, endZ, endYaw);

        System.out.println();
        System.out.println("间距  平行线长度   终点应在        终点实际        终点偏差    中段垂距误差(最大)");
        for (double d : new double[]{4.0D, 8.0D, 12.0D, -4.0D, -8.0D, -12.0D}) {
            Alignment off = offsetAlignment(base, d);
            double[] q = new double[7];
            off.eval(off.length, q);
            double wantX = endX + Geo.leftX(endYaw) * d;
            double wantZ = endZ + Geo.leftZ(endYaw) * d;
            double endErr = Math.hypot(q[0] - wantX, q[1] - wantZ);

            double worst = 0.0D;
            for (int i = 0; i <= 200; i++) {
                double s = off.length * i / 200.0D;
                off.eval(s, q);
                double dist = distToBase(base, q[0], q[1]);
                worst = Math.max(worst, Math.abs(dist - Math.abs(d)));
            }
            System.out.printf("%5.1f %10.3f  (%9.3f,%9.3f) (%9.3f,%9.3f) %8.3f m %12.3f m%n",
                    d, off.length, wantX, wantZ, q[0], q[1], endErr, worst);
        }

        System.out.println();
        System.out.println("== OffsetGeometry（精确等距，新路径实际使用的那套） ==");
        System.out.println("间距  长度        终点偏差     垂距误差(最大)  朝向误差(最大)");
        boolean allOk = true;
        for (double d : new double[]{4.0D, 8.0D, 12.0D, -4.0D, -8.0D, -12.0D}) {
            OffsetGeometry g = new OffsetGeometry(base, d, 0.0D, 0.0D, 0.0D);
            double gx = g.x(g.length());
            double gz = g.z(g.length());
            double wantX = endX + Geo.leftX(endYaw) * d;
            double wantZ = endZ + Geo.leftZ(endYaw) * d;
            double endErr = Math.hypot(gx - wantX, gz - wantZ);
            double worst = 0.0D;
            double worstYaw = 0.0D;
            for (int i = 0; i <= 200; i++) {
                double t = g.length() * i / 200.0D;
                double dist = distToBase(base, g.x(t), g.z(t));
                worst = Math.max(worst, Math.abs(dist - Math.abs(d)));
                // 同一里程处，等距线朝向应与基准线在对应点的朝向一致
                base.eval(base.length * i / 200.0D, o);
            }
            System.out.printf("%5.1f %10.3f %11.4f m %13.4f m %13.4f°%n",
                    d, g.length(), endErr, worst, worstYaw);
            if (endErr > 0.01D || worst > 0.01D) {
                allOk = false;
            }
        }
        System.out.println(allOk ? "结论: PASS（误差 < 1 cm）" : "结论: FAIL");
    }
}
