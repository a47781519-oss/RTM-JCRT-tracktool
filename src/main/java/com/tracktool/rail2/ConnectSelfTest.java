package com.tracktool.rail2;

import com.tracktool.rail.RailEnd;
import com.tracktool.rail.TrackSpec;
import com.tracktool.rail.plan.Alignment;
import com.tracktool.rail.plan.ConnectSolver;
import com.tracktool.rail.plan.RailPlan;
import com.tracktool.util.Geo;

/** 离线自检：连接模式解出来的线路，端点是否真的落在两个接头上（位置 + 朝向）。 */
public final class ConnectSelfTest {

    private ConnectSelfTest() {
    }

    private static RailEnd end(double x, double y, double z, double outwardYaw) {
        RailEnd e = new RailEnd();
        e.x = x;
        e.y = y;
        e.z = z;
        e.outwardYaw = Geo.normalize360(outwardYaw);
        e.outwardPitch = 0.0D;
        e.jointCant = 0.0D;
        e.existingCant = 0.0D;
        return e;
    }

    private static void run(String name, RailEnd from, RailEnd to, boolean auto, int manualRadius) {
        TrackSpec spec = new TrackSpec();
        spec.mode = TrackSpec.MODE_CONNECT;
        spec.connectAutoSolve = auto;
        spec.radiusM = manualRadius;
        spec.autoTransition = true;
        spec.cantDeg = 3.0D;
        spec.verticalRadius = 15000.0D;
        RailPlan plan = new RailPlan();
        Alignment a = ConnectSolver.solve(spec, from, to, plan);
        if (a == null) {
            System.out.printf("%-26s 无解: %s %s%n", name, plan.errorKey, plan.errorArg == null ? "" : plan.errorArg);
            return;
        }
        a.placeAt(from.x, from.z, from.outwardYaw);
        double[] o = new double[7];
        a.eval(a.length, o);
        double wantYaw = Geo.normalize360(to.outwardYaw + 180.0D);
        double posErr = Math.hypot(o[0] - to.x, o[1] - to.z);
        double yawErr = Math.abs(Geo.angleDiff(o[3], wantYaw));
        a.eval(0.0D, o);
        double startErr = Math.hypot(o[0] - from.x, o[1] - from.z);
        StringBuilder shape = new StringBuilder();
        for (Alignment.ElementInfo e : a.describe()) {
            shape.append(e.typeKey.replace("tracktool.element.", "")).append(String.format("(%.1f)", e.length)).append(' ');
        }
        System.out.printf("%-26s L=%7.2f R=%8.1f ls=%5.1f 起点误差=%.4f 终点误差=%.4f 朝向误差=%.4f°%n    %s%n",
                name, a.length, plan.solvedRadius, plan.solvedTransition, startErr, posErr, yawErr, shape);
    }

    public static void main(String[] args) {
        // 注意：outwardYaw = 既有轨道【向外延伸】的方向；连接线到达该端点时的朝向 = outwardYaw + 180
        // 1) 直角相交：从 (0,0) 朝 +Z 出发，到 (200,200) 时朝 +X（⇒ outwardYaw = 90+180 = 270）
        run("相交 90°", end(0, 64, 0, 0.0D), end(200, 64, 200, 270.0D), true, 300);
        // 2) 小转角 20°
        run("相交 20°", end(0, 64, 0, 0.0D), end(60, 64, 300, 200.0D), true, 300);
        // 3) 右转 45°
        run("相交 -45°", end(0, 64, 0, 0.0D), end(-150, 64, 200, 135.0D), true, 300);
        // 4) 手动半径（够用）
        run("手动 R=200", end(0, 64, 0, 0.0D), end(200, 64, 200, 270.0D), false, 200);
        // 5) 手动半径（放不下 ⇒ 应报错并给出上限）
        run("手动 R=5000", end(0, 64, 0, 0.0D), end(200, 64, 200, 270.0D), false, 5000);
        // 6) 共线：应当是一条直线
        run("共线直连", end(0, 64, 0, 0.0D), end(0, 64, 150, 180.0D), true, 300);
        // 7) 背对背（两端都朝外）：本形状无解 ⇒ 退回双圆弧
        run("背对背(退回双圆弧)", end(0, 64, 0, 180.0D), end(0, 64, 150, 0.0D), true, 300);
        // 8) 带高差
        run("带高差 10 m", end(0, 64, 0, 0.0D), end(200, 74, 200, 270.0D), true, 300);
        // 9) 平行错开（S 形需求）：本形状无解 ⇒ 退回双圆弧
        run("平行错开 8 m", end(0, 64, 0, 0.0D), end(8, 64, 200, 180.0D), true, 300);
    }
}
