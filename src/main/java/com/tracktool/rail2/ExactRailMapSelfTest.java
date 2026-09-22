package com.tracktool.rail2;

import com.tracktool.rail.RailGrid;
import jp.ngt.rtm.rail.util.RailPosition;

/** 离线一致性自检：同一个几何，分别走 ExactRailGeometry 与 ExactRailMap 两条路，逐点比对。
 *  验证 [Z,X] 顺序、单位（度/比值）、基准点偏移是否正确。 */
public final class ExactRailMapSelfTest {

    public static void main(String[] args) {
        double[] p = {300.0D, 45.0D, 30.0D, 105.0D, 5.0D, 15000.0D, 1.0D, 180.0D, -2399.5D, 4.0D, -816.0D};
        AlignmentGeometry g = ExactRailGeometryCodec.build(p);
        double L = g.length();
        RailPosition s = RailGrid.make(p[8], p[10], p[9], p[7], 0.0D, 0);
        RailPosition e = RailGrid.make(p[8] + g.x(L), p[10] + g.z(L), p[9] + g.height(L), g.yaw(L), g.pitch(L), 0);
        ExactRailMap m = new ExactRailMap(s, e, g);

        System.out.println("=== ExactRailMap 一致性自检 ===");
        System.out.printf("几何总长=%.3f   核心 RP: start=(%.2f,%.2f,%.2f) end=(%.2f,%.2f,%.2f)%n",
                L, s.posX, s.posY, s.posZ, e.posX, e.posY, e.posZ);
        System.out.printf("map.getLength()=%.3f  (差=%.3e)%n", m.getLength(), Math.abs(m.getLength() - L));

        int n = 64;
        double mp = 0.0D;
        double mh = 0.0D;
        double my = 0.0D;
        double mr = 0.0D;
        double mpi = 0.0D;
        for (int i = 0; i <= n; i++) {
            double t = L * i / n;
            double[] pos = m.getRailPos(n, i);
            mp = Math.max(mp, Math.hypot(pos[0] - (s.posZ + g.z(t)), pos[1] - (s.posX + g.x(t))));
            mh = Math.max(mh, Math.abs(m.getRailHeight(n, i) - (s.posY + g.height(t))));
            my = Math.max(my, Math.abs(m.getRailYaw(n, i) - g.yaw(t)));
            mr = Math.max(mr, Math.abs(m.getRailRoll(n, i) - g.roll(t)));
            mpi = Math.max(mpi, Math.abs(m.getRailPitch(n, i) - g.pitch(t)));
        }
        System.out.printf("逐点最大偏差: pos=%.3e  height=%.3e  yaw=%.3e  pitch=%.3e  roll=%.3e%n", mp, mh, my, mpi, mr);

        int mid = n / 2;
        double tMid = L * 0.5D;
        int near = m.getNearlestPoint(n, s.posX + g.x(tMid), s.posZ + g.z(tMid));
        System.out.printf("nearestT 反查: 期望 %d 实得 %d  ⇒ %s%n", mid, near, (Math.abs(near - mid) <= 1 ? "OK" : "偏差偏大"));

        boolean pass = mp < 1.0E-9D && mh < 1.0E-9D && my < 1.0E-4D && mr < 1.0E-6D && mpi < 1.0E-4D;
        System.out.println(pass ? "*** PASS：ExactRailMap 与几何完全一致 ***" : "*** FAIL ***");
    }
}