package com.tracktool.rail2;

import jp.ngt.rtm.rail.util.RailPosition;

/**
 * 同一条解析几何的<b>一段里程</b>（[t0, t1]），用来把长线路切成多个 RTM 核心。
 *
 * <p>为什么要切：RTM 是<b>按核心</b>渲染的 —— 一个核心的钢轨/路基是一整个 GL 列表，
 * 核心方块所在区块不在视距内时整段都不画。旧路径每 20 m 一个核心正是这个原因，
 * 但它在每个锚点之间<b>重新拟合贝塞尔</b>，锚点又被量化到半格网格，于是接头处出现弯折。</p>
 *
 * <p>本类只做"取一段"：位置/朝向/坡度/超高<b>全部来自同一条几何</b>，
 * 相邻两段共用同一个里程点 ⇒ 接头处逐点一致，既拿回了分段渲染，又没有弯折。</p>
 *
 * <p>坐标约定与 {@link PlanGeometry} 一致：返回<b>相对本段起点 RP</b> 的量
 * （父几何是相对整条线的起点 RP 的，所以这里要减去两个 RP 之间的偏移）。</p>
 */
public final class SubGeometry implements ExactRailGeometry {

    private final ExactRailGeometry parent;
    private final double t0;
    private final double len;
    private final double dx;
    private final double dy;
    private final double dz;

    public SubGeometry(ExactRailGeometry parent, double t0, double t1,
                       double dx, double dy, double dz) {
        this.parent = parent;
        this.t0 = t0;
        this.len = Math.max(0.0D, t1 - t0);
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
    }

    /**
     * @param lineStart 整条线的起点 RP（父几何的基准）
     * @param segStart  本段起点 RP（本段几何的基准）
     */
    public static SubGeometry of(ExactRailGeometry parent, double t0, double t1,
                                 RailPosition lineStart, RailPosition segStart) {
        return new SubGeometry(parent, t0, t1,
                segStart.posX - lineStart.posX,
                segStart.posY - lineStart.posY,
                segStart.posZ - lineStart.posZ);
    }

    private double pt(double t) {
        double v = this.t0 + (t < 0.0D ? 0.0D : (t > this.len ? this.len : t));
        double pl = this.parent.length();
        return v > pl ? pl : v;
    }

    @Override
    public double length() {
        return this.len;
    }

    @Override
    public double x(double t) {
        return this.parent.x(pt(t)) - this.dx;
    }

    @Override
    public double z(double t) {
        return this.parent.z(pt(t)) - this.dz;
    }

    @Override
    public double height(double t) {
        return this.parent.height(pt(t)) - this.dy;
    }

    @Override
    public double yaw(double t) {
        return this.parent.yaw(pt(t));
    }

    @Override
    public double pitch(double t) {
        return this.parent.pitch(pt(t));
    }

    @Override
    public double roll(double t) {
        return this.parent.roll(pt(t));
    }
}
