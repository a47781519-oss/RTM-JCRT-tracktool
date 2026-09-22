package com.tracktool.rail2;

/** 轨道几何抽象：所有量以沿轨里程 t 为参数。
 *  A 方案核心接口——几何由我们自己的解析线形决定，不经 RTM 半格锚点链，也不用 RailMapBasic 放样。
 *  单位：yaw/pitch 为度（RTM 字节码确认），roll 为超高比值。 */
public interface ExactRailGeometry {

    /** 总长（米）。 */
    double length();

    /** 里程 t 处 x（精确 double，无量化）。 */
    double x(double t);

    /** 里程 t 处 z。 */
    double z(double t);

    /** 里程 t 处高程（相对起点）。 */
    double height(double t);

    /** 朝向，度。 */
    double yaw(double t);

    /** 坡度，度。 */
    double pitch(double t);

    /** 超高（侧倾）比值，弧度。 */
    double roll(double t);

    /** 里程反查（供 RTM 的 getNearlestPoint 使用）：粗采样后二分细化。 */
    default double nearestT(double px, double pz) {
        int n = 256;
        double len = length();
        double bestT = 0.0D;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i <= n; i++) {
            double t = len * i / n;
            double d = sq(x(t) - px) + sq(z(t) - pz);
            if (d < bestD) {
                bestD = d;
                bestT = t;
            }
        }
        double step = len / n;
        for (int it = 0; it < 24; it++) {
            step *= 0.5D;
            double t1 = Math.max(0.0D, bestT - step);
            double t2 = Math.min(len, bestT + step);
            double d1 = sq(x(t1) - px) + sq(z(t1) - pz);
            double d2 = sq(x(t2) - px) + sq(z(t2) - pz);
            if (d1 < bestD) {
                bestD = d1;
                bestT = t1;
            }
            if (d2 < bestD) {
                bestD = d2;
                bestT = t2;
            }
        }
        return bestT;
    }

    static double sq(double v) {
        return v * v;
    }
}