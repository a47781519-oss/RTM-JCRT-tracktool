package com.tracktool.rail2;

/** 采样点表几何：把服务端【实际用来铺设的那条几何】按等里程采样，逐点送到客户端。
 *
 *  <p>为什么不送参数串：参数串只能重建"缓和+圆+缓和"这一种线形，而 plan 的线形可能带竖曲线、
 *  多元素、平行偏移 —— 参数无法精确重现 ⇒ 两端必然有差。改送采样点后，客户端渲染用的几何
 *  与服务端铺方块用的几何<b>逐点同源</b>。</p>
 *
 *  <p>坐标一律是【相对起点 RP】的偏移（与 {@link ExactLine} 的 base+geo 约定一致）。
 *  yaw 在采样时做了连续化（消除 ±360 跳变），所以线性插值不会在 0/360 交界处翻转。</p> */
public final class SampledGeometry implements ExactRailGeometry {

    /** 采样间距（米）。R=300 时线性插值的矢高误差 ≈ 1/(8·300) = 0.4 mm，可忽略。 */
    public static final double STEP_M = 1.0D;
    public static final int MAX_POINTS = 1024;

    private final double len;
    private final float[] dx;
    private final float[] dz;
    private final float[] dy;
    private final float[] yaw;
    private final float[] pitch;
    private final float[] roll;

    public SampledGeometry(double len, float[] dx, float[] dz, float[] dy,
                           float[] yaw, float[] pitch, float[] roll) {
        this.len = len;
        this.dx = dx;
        this.dz = dz;
        this.dy = dy;
        this.yaw = yaw;
        this.pitch = pitch;
        this.roll = roll;
    }

    /** 服务端：按 {@link #STEP_M} 采样一条几何（点数 = 段数 + 1）。 */
    public static SampledGeometry of(ExactRailGeometry g) {
        double length = g.length();
        int seg = (int) Math.ceil(length / STEP_M);
        if (seg < 8) {
            seg = 8;
        }
        if (seg > MAX_POINTS - 1) {
            seg = MAX_POINTS - 1;
        }
        int n = seg + 1;
        float[] ax = new float[n];
        float[] az = new float[n];
        float[] ay = new float[n];
        float[] ayaw = new float[n];
        float[] apitch = new float[n];
        float[] aroll = new float[n];
        double prevYaw = 0.0D;
        for (int i = 0; i < n; i++) {
            double t = length * i / seg;
            ax[i] = (float) g.x(t);
            az[i] = (float) g.z(t);
            ay[i] = (float) g.height(t);
            double y = g.yaw(t);
            if (i > 0) {
                while (y - prevYaw > 180.0D) {
                    y -= 360.0D;
                }
                while (y - prevYaw < -180.0D) {
                    y += 360.0D;
                }
            }
            prevYaw = y;
            ayaw[i] = (float) y;
            apitch[i] = (float) g.pitch(t);
            aroll[i] = (float) g.roll(t);
        }
        return new SampledGeometry(length, ax, az, ay, ayaw, apitch, aroll);
    }

    public int pointCount() {
        return dx.length;
    }

    public double rawLength() {
        return len;
    }

    public float[] arrX() {
        return dx;
    }

    public float[] arrZ() {
        return dz;
    }

    public float[] arrY() {
        return dy;
    }

    public float[] arrYaw() {
        return yaw;
    }

    public float[] arrPitch() {
        return pitch;
    }

    public float[] arrRoll() {
        return roll;
    }

    private double lerp(float[] a, double t) {
        int seg = a.length - 1;
        if (seg <= 0 || len <= 0.0D) {
            return a.length > 0 ? a[0] : 0.0D;
        }
        double f = t / len * seg;
        if (f <= 0.0D) {
            return a[0];
        }
        if (f >= seg) {
            return a[seg];
        }
        int i = (int) f;
        double r = f - i;
        return a[i] + (a[i + 1] - a[i]) * r;
    }

    @Override
    public double length() {
        return len;
    }

    @Override
    public double x(double t) {
        return lerp(dx, t);
    }

    @Override
    public double z(double t) {
        return lerp(dz, t);
    }

    @Override
    public double height(double t) {
        return lerp(dy, t);
    }

    @Override
    public double yaw(double t) {
        return lerp(yaw, t);
    }

    @Override
    public double pitch(double t) {
        return lerp(pitch, t);
    }

    @Override
    public double roll(double t) {
        return lerp(roll, t);
    }
}
