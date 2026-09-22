package com.tracktool.rail2;

import com.tracktool.rail.plan.Alignment;
import com.tracktool.util.Geo;
import jp.ngt.rtm.rail.util.RailPosition;

/**
 * 平行线的<b>精确等距几何</b>：基准线 {@link Alignment} 向左偏移 {@code offset} 米。
 *
 * <p>为什么不能用"把每个元素换算一遍"的做法：缓和曲线的等距线<b>不是</b>缓和曲线。
 * 第 50 轮离线实测，用"线性曲率 + 换算长度"近似时，终点偏差 <b>0.1 m / 每 4 m 间距</b>
 * （12 m 间距偏 0.30 m），表现就是"接着铺下一段时只有中心线对得上"。</p>
 *
 * <p>正确做法是按定义走：等距线上的点 = 基准线上的点 + 法向 × d，切线方向与基准线相同，
 * 而两者的<b>里程不同</b>：{@code ds' = (1 − κ·d)·ds} ⇒ {@code s'(s) = s − d·θ(s)}。
 * 这里预先采样出 s'(s) 的单调表，用二分把"等距线里程 t"反查回"基准线里程 s"，
 * 于是位置/朝向/坡度/超高全部直接取基准线在 s 处的值 —— 没有任何近似。</p>
 *
 * <p>坐标约定与 {@link PlanGeometry} 一致：返回<b>相对起点 RP</b> 的量（ExactLine 会把基准加回去）。</p>
 */
public final class OffsetGeometry implements ExactRailGeometry {

    /** 采样步长（基准线里程），0.05 m ⇒ 线性插值的里程误差 ~1e-6 m。 */
    private static final double STEP = 0.05D;

    private final Alignment base;
    private final double offset;
    private final double baseX;
    private final double baseY;
    private final double baseZ;

    /** sPrime[i] = 基准线里程 i*STEP 处对应的等距线里程。单调不减。 */
    private final double[] sPrime;
    private final double totalLen;

    private double cacheT = Double.NaN;
    private final double[] cache = new double[7];

    public OffsetGeometry(Alignment base, double offset, double baseX, double baseY, double baseZ) {
        this.base = base;
        this.offset = offset;
        this.baseX = baseX;
        this.baseY = baseY;
        this.baseZ = baseZ;
        int n = (int) Math.ceil(base.length / STEP) + 1;
        this.sPrime = new double[n + 1];
        double[] o = new double[7];
        double acc = 0.0D;
        this.sPrime[0] = 0.0D;
        for (int i = 1; i <= n; i++) {
            double s0 = Math.min(base.length, (i - 1) * STEP);
            double s1 = Math.min(base.length, i * STEP);
            base.eval((s0 + s1) * 0.5D, o);
            // ds' = (1 − κ·d)·ds；夹紧防止"偏移越过曲率中心"时里程反向
            double f = 1.0D - o[6] * offset;
            if (f < 0.05D) {
                f = 0.05D;
            }
            acc += f * (s1 - s0);
            this.sPrime[i] = acc;
        }
        this.totalLen = acc;
    }

    public OffsetGeometry(Alignment base, double offset, RailPosition startRP) {
        this(base, offset, startRP.posX, startRP.posY, startRP.posZ);
    }

    /** 等距线里程 t ⇒ 基准线里程 s（在单调表上二分 + 线性插值）。 */
    private double baseS(double t) {
        if (t <= 0.0D) {
            return 0.0D;
        }
        if (t >= this.totalLen) {
            return this.base.length;
        }
        int lo = 0;
        int hi = this.sPrime.length - 1;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (this.sPrime[mid] <= t) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double d0 = this.sPrime[lo];
        double d1 = this.sPrime[hi];
        double f = d1 - d0 < 1.0E-12D ? 0.0D : (t - d0) / (d1 - d0);
        return Math.min(this.base.length, (lo + f) * STEP);
    }

    private double[] at(double t) {
        synchronized (this.cache) {
            if (t != this.cacheT) {
                this.base.eval(baseS(t), this.cache);
                this.cacheT = t;
            }
            return this.cache;
        }
    }

    @Override
    public double length() {
        return this.totalLen;
    }

    @Override
    public double x(double t) {
        synchronized (this.cache) {
            double[] o = at(t);
            return o[0] + Geo.leftX(o[3]) * this.offset - this.baseX;
        }
    }

    @Override
    public double z(double t) {
        synchronized (this.cache) {
            double[] o = at(t);
            return o[1] + Geo.leftZ(o[3]) * this.offset - this.baseZ;
        }
    }

    @Override
    public double height(double t) {
        synchronized (this.cache) {
            return at(t)[2] - this.baseY;
        }
    }

    /** 等距线与基准线的切线方向处处相同。 */
    @Override
    public double yaw(double t) {
        synchronized (this.cache) {
            return at(t)[3];
        }
    }

    @Override
    public double pitch(double t) {
        synchronized (this.cache) {
            return at(t)[4];
        }
    }

    @Override
    public double roll(double t) {
        synchronized (this.cache) {
            return at(t)[5];
        }
    }
}
