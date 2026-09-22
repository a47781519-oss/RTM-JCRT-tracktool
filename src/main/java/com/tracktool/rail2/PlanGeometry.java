package com.tracktool.rail2;

import com.tracktool.rail.plan.Alignment;
import jp.ngt.rtm.rail.util.RailPosition;

/** 直接复用【预览所用的那条线形】（plan.Alignment）的几何实现。
 *
 *  <p>为什么需要它：此前铺设用的是另写的 AlignmentGeometry（自己积分、自己的闭合规则），
 *  与预览的 Alignment 是两套实现 ⇒ 永远对不上（用户实测"预览对不上实际"）。
 *  本类把 ExactRailGeometry 变成 Alignment 的薄适配器 ⇒ 铺设与预览**同源**，必然一致。</p>
 *
 *  <p>依据 Alignment.eval(s, out) 的契约（源码确认）：
 *  out[0]=x, out[1]=z, out[2]=y, out[3]=yaw, out[4]=pitchDeg, out[5]=cant, out[6]=曲率。
 *  <b>Alignment 返回的是世界绝对坐标</b>（placeAt(from.x, from.z, from.outwardYaw) 放样过），
 *  而 {@link ExactLine} / {@link ExactRailMap} 的约定是 <b>base(起点 RP) + geo</b>，
 *  所以这里必须减去起点基准，返回【相对起点】的量。</p> */
public final class PlanGeometry implements ExactRailGeometry {

    private final Alignment alignment;
    private final double baseX;
    private final double baseY;
    private final double baseZ;

    /** 单条目缓存：ExactLine / ExactRailMap 会对同一个 t 连着取 x/z/y/yaw/pitch，避免重复 eval。 */
    private double cacheT = Double.NaN;
    private final double[] cache = new double[7];

    public PlanGeometry(Alignment alignment, double baseX, double baseY, double baseZ) {
        this.alignment = alignment;
        this.baseX = baseX;
        this.baseY = baseY;
        this.baseZ = baseZ;
    }

    /** 基准取【铺设时传给 ExactLine 的那个起点 RP】，两边必须是同一个点。 */
    public PlanGeometry(Alignment alignment, RailPosition startRP) {
        this(alignment, startRP.posX, startRP.posY, startRP.posZ);
    }

    private double at(double t, int idx) {
        synchronized (this.cache) {
            if (t != this.cacheT) {
                this.alignment.eval(t, this.cache);
                this.cacheT = t;
            }
            return this.cache[idx];
        }
    }

    @Override
    public double length() {
        return alignment.length;
    }

    @Override
    public double x(double t) {
        return at(t, 0) - baseX;
    }

    @Override
    public double z(double t) {
        return at(t, 1) - baseZ;
    }

    @Override
    public double height(double t) {
        return at(t, 2) - baseY;
    }

    @Override
    public double yaw(double t) {
        return at(t, 3);
    }

    @Override
    public double pitch(double t) {
        return at(t, 4);
    }

    /** 超高：Alignment 的 cant 单位是【度】（CantProfile 注释），与 RailPosition.cant* 同单位。 */
    @Override
    public double roll(double t) {
        return at(t, 5);
    }
}
