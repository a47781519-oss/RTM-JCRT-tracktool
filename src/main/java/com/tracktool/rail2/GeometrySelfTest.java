package com.tracktool.rail2;

/** 离线几何自检：不需要游戏，直接验证解析线形的长度/朝向/超高/竖曲线是否自洽。
 *  运行：java -cp build/libs/tracktool-0.1.0.jar com.tracktool.rail2.GeometrySelfTest */
public final class GeometrySelfTest {

    public static void main(String[] args) {
        double R = 300.0D, turn = 45.0D, ls = 30.0D, cant = 105.0D, rise = 0.0D, Rv = 15000.0D;
        AlignmentGeometry g = new AlignmentGeometry(R, turn, ls, cant, rise, Rv, 1.0D, 180.0D);
        System.out.println("=== AlignmentGeometry 自检 ===");
        System.out.printf("有效缓和曲线 Ls=%.3f  圆曲线 Lc=%.3f  总长 L=%.3f%n",
                g.effectiveSpiralLength(), g.arcLength(), g.length());
        double theo = 2 * ls + R * (Math.toRadians(turn) - 2 * (ls / (2 * R)));
        System.out.printf("理论总长=%.3f  偏差=%.6f m%n", theo, Math.abs(g.length() - theo));

        double yEnd = g.yaw(g.length());
        System.out.printf("终点朝向=%.4f°（起点180°，左转%.1f° ⇒ 期望 %.1f°）  偏差=%.6f°%n",
                yEnd, turn, 180 + turn, Math.abs(yEnd - (180 + turn)));

        System.out.println("--- 超高剖面（应：0 → 线性升至满值 → 圆弧恒定 → 回 0）---");
        double[] ts = {0.0D, ls * 0.25D, ls * 0.5D, ls, ls + g.arcLength() * 0.5D,
                ls + g.arcLength(), g.length() - ls * 0.5D, g.length()};
        for (double t : ts) {
            System.out.printf("  t=%7.2f  roll=%.5f  yaw=%8.4f°  pitch=%6.4f°  x=%9.4f z=%9.4f%n",
                    t, g.roll(t), g.yaw(t), g.pitch(t), g.x(t), g.z(t));
        }

        System.out.println("--- 数值自洽检查 ---");
        double maxErr = 0.0D;
        int n = 1000;
        for (int i = 0; i <= n; i++) {
            double t = g.length() * i / n;
            // 用相邻点差分数值验证弧长微分 = 1（即参数化按弧长）
            double h = 0.01D;
            double t1 = Math.min(g.length(), t + h);
            double dx = g.x(t1) - g.x(t);
            double dz = g.z(t1) - g.z(t);
            double chord = Math.sqrt(dx * dx + dz * dz);
            maxErr = Math.max(maxErr, Math.abs(chord - (t1 - t)));
        }
        System.out.printf("弧长参数化误差（1000 点，步长0.01m）最大=%.6f m%n", maxErr);

        AlignmentGeometry gr = new AlignmentGeometry(R, turn, ls, cant, 5.0D, Rv, 1.0D, 180.0D);
        System.out.println("--- 抬高 5 m 的纵向剖面 ---");
        double[] tv = {0.0D, 20.0D, gr.length() * 0.5D, gr.length() - 20.0D, gr.length()};
        for (double t : tv) {
            System.out.printf("  t=%7.2f  height=%.4f  pitch=%6.4f°%n", t, gr.height(t), gr.pitch(t));
        }
        System.out.println("=== 自检结束 ===");
    }
}