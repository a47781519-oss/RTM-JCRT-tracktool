package com.tracktool.rail2;

import jp.ngt.rtm.rail.util.RailMap;
import jp.ngt.rtm.rail.util.RailPosition;

/** 把"自定解析几何"接进 RTM 的轨道图抽象类。
 *
 *  <p>RTM 的渲染与车辆都通过 {@code RailMap} 的抽象方法取几何（SuperRailBuilderX 与
 *  RTM 自带的 {@code RailMapCustom} 都是这样接入的）。本类把这些方法<b>全部委托</b>给
 *  {@link ExactRailGeometry}，于是几何完全由我们决定：不经过半格锚点链、不使用
 *  {@code RailMapBasic} 的逐段贝塞尔放样、也不依赖脚本引擎。</p>
 *
 *  <p>两个端点 {@code RailPosition} 只作<b>承载</b>（容器需要它们），不参与几何生成。</p>
 *
 *  <p>单位约定（第 53 轮由字节码确认）：{@code getRailYaw/getRailPitch} 返回 <b>度</b>，
 *  {@code getRailRoll} 返回超高比值；{@code getRailPos} 返回 <b>[Z, X]</b>（顺序不能反）。</p>
 */
public final class ExactRailMap extends RailMap {

    private final RailPosition startRP;
    private final RailPosition endRP;
    private final ExactRailGeometry geo;
    private final double baseX;
    private final double baseY;
    private final double baseZ;

    public ExactRailMap(RailPosition startRP, RailPosition endRP, ExactRailGeometry geo) {
        this.startRP = startRP;
        this.endRP = endRP;
        this.geo = geo;
        this.baseX = startRP.posX;
        this.baseY = startRP.posY;
        this.baseZ = startRP.posZ;
    }

    private double t(int split, int index) {
        if (split <= 0) {
            return 0.0D;
        }
        double len = geo.length();
        double f = (double) index / (double) split;
        return len * (f < 0.0D ? 0.0D : (f > 1.0D ? 1.0D : f));
    }

    @Override
    public RailPosition getStartRP() {
        return startRP;
    }

    @Override
    public RailPosition getEndRP() {
        return endRP;
    }

    @Override
    public double getLength() {
        return geo.length();
    }

    @Override
    public int getNearlestPoint(int split, double x, double z) {
        double len = geo.length();
        if (len <= 0.0D) {
            return 0;
        }
        double tt = geo.nearestT(x - baseX, z - baseZ);
        return (int) Math.round(split * tt / len);
    }

    /** 注意：RTM 约定返回 {@code [Z, X]}。 */
    @Override
    public double[] getRailPos(int split, int index) {
        double tt = t(split, index);
        return new double[]{baseZ + geo.z(tt), baseX + geo.x(tt)};
    }

    @Override
    public double getRailHeight(int split, int index) {
        return baseY + geo.height(t(split, index));
    }

    @Override
    public float getRailYaw(int split, int index) {
        return (float) geo.yaw(t(split, index));
    }

    @Override
    public float getRailPitch(int split, int index) {
        return (float) geo.pitch(t(split, index));
    }

    @Override
    public float getRailRoll(int split, int index) {
        return (float) geo.roll(t(split, index));
    }
}