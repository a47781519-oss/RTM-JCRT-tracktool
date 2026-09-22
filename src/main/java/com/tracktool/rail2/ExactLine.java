package com.tracktool.rail2;

import jp.ngt.ngtlib.math.ILine;
import jp.ngt.rtm.rail.util.RailPosition;

/** 用自己的解析线形实现 NGTLib 的 ILine，用于替换 RailMapBasic 的 lineHorizontal / lineVertical。
 *
 *  <p>依据（RailMapBasic 字节码逐条核对）：</p>
 *  <ul>
 *    <li>{@code getRailPos(split,index)} 直接 {@code return lineHorizontal.getPoint(split,index);}（纯转发、不叠加基准）
 *        ⇒ 本类【水平】模式必须返回【绝对世界坐标】，顺序为 <b>[Z, X]</b>。</li>
 *    <li>{@code getRailHeight} 取 {@code lineVertical.getPoint(split,index)[1]} 作为高度
 *        ⇒ 本类【竖直】模式返回 {@code [任意, y]}，y 用绝对高度。</li>
 *    <li>{@code ILine} 只有 4 个方法：getPoint / getNearlestPoint / getSlope / getLength。</li>
 *  </ul>
 *
 *  <p>替换后对象类型仍是 {@code RailMapBasic} ⇒ 渲染器与车辆的既有判断照常工作；
 *  几何不再来自量化的锚点 ⇒ 半格网格抖动从根上消失。</p> */
public final class ExactLine implements ILine {

    private final ExactRailGeometry geo;
    private final double baseX;
    private final double baseY;
    private final double baseZ;
    private final boolean vertical;

    public ExactLine(ExactRailGeometry geo, RailPosition startRP, boolean vertical) {
        this.geo = geo;
        this.baseX = startRP.posX;
        this.baseY = startRP.posY;
        this.baseZ = startRP.posZ;
        this.vertical = vertical;
    }

    /** 供注入器判重用：已经装着同一个几何就不必再换（否则客户端每 16 tick 重复替换并刷屏）。 */
    public ExactRailGeometry geometry() {
        return this.geo;
    }

    private double t(int split, int index) {
        if (split <= 0) {
            return 0.0D;
        }
        double f = (double) index / (double) split;
        if (f < 0.0D) {
            f = 0.0D;
        } else if (f > 1.0D) {
            f = 1.0D;
        }
        return geo.length() * f;
    }

    /** 水平：[Z, X] 绝对坐标；竖直：[0, Y] 绝对高度。 */
    @Override
    public double[] getPoint(int split, int index) {
        double tt = t(split, index);
        if (vertical) {
            return new double[]{0.0D, baseY + geo.height(tt)};
        }
        return new double[]{baseZ + geo.z(tt), baseX + geo.x(tt)};
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

    /** ★ 单位是【弧度角】，不是比值 —— 依据 RailMapBasic 字节码：
     *  getRailYaw  = NGTMath.toDegrees(lineHorizontal.getSlope(s,i))
     *  getRailPitch= NGTMath.toDegrees(lineVertical.getSlope(s,i))
     *  故水平线形的 getSlope 必须返回【朝向角(弧度)】，竖直线形返回【坡度角(弧度)】。 */
    @Override
    public double getSlope(int split, int index) {
        double tt = t(split, index);
        if (vertical) {
            return Math.toRadians(geo.pitch(tt));
        }
        return Math.toRadians(geo.yaw(tt));
    }

    @Override
    public double getLength() {
        return geo.length();
    }
}