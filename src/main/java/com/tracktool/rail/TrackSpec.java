package com.tracktool.rail;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Every parameter the GUI can set, for all three modes. This is the single
 * object that the client sends to the server and that both sides feed into
 * {@link com.tracktool.rail.plan.PlanBuilder}, so the preview and the generated
 * rail come from byte-identical inputs.
 *
 * <p>All integral parameters are rounded with one shared policy
 * ({@link com.tracktool.util.Geo#roundHalfUp}) before being stored, so client
 * and server always agree.</p>
 */
public final class TrackSpec {

    public static final int MODE_STRAIGHT = 0;
    public static final int MODE_CURVE = 1;
    public static final int MODE_CONNECT = 2;

    /**
     * 参数包的<b>线上格式版本</b>。字段一增删就必须 +1 ——
     * 客户端与服务端装的 jar 不一致时，版本号相同但布局不同会<b>静默读错</b>，
     * 表现成"参数明明填对了、铺出来却是另一条线"，极难查。
     * v2：连接模式新增 {@code connectCantAdaptive}（第 62 轮）。
     */
    public static final int VERSION = 2;

    /** 本实例是从哪个版本的包读出来的（本地新建的对象为 {@link #VERSION}）。 */
    public transient int wireVersion = VERSION;

    public int mode = MODE_STRAIGHT;

    // ---- common ----
    /** Track raise over the generated length, metres (integral). */
    public int riseM;
    /** Vertical curve radius, metres. */
    public double verticalRadius = 1500.0D;
    /** Number of parallel tracks to the left / right of the selected one. */
    public int linesLeft;
    public int linesRight;
    /** Parallel track spacing, metres (integral). */
    public int spacingM = 4;
    /** Rail model pack resource name; empty string = RTM default. */
    public String railResource = "";
    /** Ballast block registry name; empty string = the pack default. */
    public String ballastBlock = "";
    public int ballastMeta;
    public float ballastHeight = 0.0625F;

    // ---- straight ----
    /** Straight length, metres (integral). */
    public int straightLengthM = 50;

    // ---- curve ----
    /** Curve radius, metres (integral). */
    public int radiusM = 300;
    /** Total turn angle, degrees. */
    public double angleDeg = 45.0D;
    /** Superelevation of the outer rail, degrees. */
    public double cantDeg = 0.0D;
    /** Cant gradient, per mille (1 = 1/1000). Used to derive the transition length. */
    public double cantGradientPermille = 1.0D;
    /** Transition (easement) length, metres. */
    public double transitionLength = 40.0D;
    /** True = derive the transition length from the GB50090 table instead. */
    public boolean autoTransition = true;
    /** True = turn left relative to the tangent. */
    public boolean turnLeft = true;
    /** Flip the superelevation sign (outer rail selection). */
    public boolean cantInvert = false;

    // ---- connect ----
    /** Transition length used at both ends of the connection. */
    public double connectTransition = 30.0D;
    /** Per-element transition/radius overrides, applied in element order. */
    public final List<Double> connectElementRadius = new ArrayList<Double>();
    public final List<Double> connectElementTransition = new ArrayList<Double>();
    public final List<Double> connectElementCant = new ArrayList<Double>();
    /** True = re-solve the connection instead of using the overrides. */
    public boolean connectAutoSolve = true;
    /**
     * 连接模式：<b>外轨超高自适应</b>。
     *
     * <p>勾上以后不再用"超高"输入框那个值，而是让整段连接线的超高在<b>两端既有超高之间平滑线性过渡</b>
     * （首尾各自等于所接轨道在接头处的超高，中间按里程线性插值）。补缺口时两端超高一样 ⇒ 全程常数；
     * 从满超高弯道接到平直线 ⇒ 一路顺坡降到 0，不会在中间冒出一个用户设定的平台。</p>
     */
    public boolean connectCantAdaptive = false;

    public void write(ByteBuf buf) {
        buf.writeByte(VERSION);
        buf.writeByte(this.mode);
        buf.writeInt(this.riseM);
        buf.writeDouble(this.verticalRadius);
        buf.writeInt(this.linesLeft);
        buf.writeInt(this.linesRight);
        buf.writeInt(this.spacingM);
        ByteBufUtils.writeUTF8String(buf, this.railResource == null ? "" : this.railResource);
        ByteBufUtils.writeUTF8String(buf, this.ballastBlock == null ? "" : this.ballastBlock);
        buf.writeInt(this.ballastMeta);
        buf.writeFloat(this.ballastHeight);
        buf.writeInt(this.straightLengthM);
        buf.writeInt(this.radiusM);
        buf.writeDouble(this.angleDeg);
        buf.writeDouble(this.cantDeg);
        buf.writeDouble(this.cantGradientPermille);
        buf.writeDouble(this.transitionLength);
        buf.writeBoolean(this.autoTransition);
        buf.writeBoolean(this.turnLeft);
        buf.writeBoolean(this.cantInvert);
        buf.writeDouble(this.connectTransition);
        buf.writeBoolean(this.connectAutoSolve);
        buf.writeBoolean(this.connectCantAdaptive);
        writeList(buf, this.connectElementRadius);
        writeList(buf, this.connectElementTransition);
        writeList(buf, this.connectElementCant);
    }

    public static TrackSpec read(ByteBuf buf) {
        TrackSpec s = new TrackSpec();
        int ver = buf.readByte() & 0xFF;
        s.wireVersion = ver;
        if (ver != VERSION) {
            // Still read the payload with the same layout; unknown versions are
            // rejected by the caller.
            TrackToolCoreHolder.markVersionMismatch();
        }
        s.mode = buf.readByte() & 0xFF;
        s.riseM = buf.readInt();
        s.verticalRadius = buf.readDouble();
        s.linesLeft = buf.readInt();
        s.linesRight = buf.readInt();
        s.spacingM = buf.readInt();
        s.railResource = ByteBufUtils.readUTF8String(buf);
        s.ballastBlock = ByteBufUtils.readUTF8String(buf);
        s.ballastMeta = buf.readInt();
        s.ballastHeight = buf.readFloat();
        s.straightLengthM = buf.readInt();
        s.radiusM = buf.readInt();
        s.angleDeg = buf.readDouble();
        s.cantDeg = buf.readDouble();
        // 超高夹紧到 RTM 画得出来的范围（见 MAX_CANT_DEG）；在这里夹，GUI 里显示的就是真正生效的值
        if (s.cantDeg > MAX_CANT_DEG) {
            s.cantDeg = MAX_CANT_DEG;
        } else if (s.cantDeg < -MAX_CANT_DEG) {
            s.cantDeg = -MAX_CANT_DEG;
        }
        s.cantGradientPermille = buf.readDouble();
        s.transitionLength = buf.readDouble();
        s.autoTransition = buf.readBoolean();
        s.turnLeft = buf.readBoolean();
        s.cantInvert = buf.readBoolean();
        s.connectTransition = buf.readDouble();
        s.connectAutoSolve = buf.readBoolean();
        s.connectCantAdaptive = buf.readBoolean();
        s.connectElementRadius.addAll(readList(buf));
        s.connectElementTransition.addAll(readList(buf));
        s.connectElementCant.addAll(readList(buf));
        return s;
    }

    private static void writeList(ByteBuf buf, List<Double> list) {
        buf.writeShort(list.size());
        for (Double d : list) {
            buf.writeDouble(d == null ? 0.0D : d);
        }
    }

    private static List<Double> readList(ByteBuf buf) {
        int n = buf.readShort();
        List<Double> list = new ArrayList<Double>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            list.add(buf.readDouble());
        }
        return list;
    }

    /** Signed curvature sign for the requested turning direction: left is positive. */
    public double curvatureSign() {
        return this.turnLeft ? 1.0D : -1.0D;
    }

    /**
     * Signed cant in degrees, already resolved so that the <em>outer</em> rail of
     * the curve is the raised one.
     *
     * <p>RTM renders a rail by rotating its model about the direction of travel
     * with {@code glRotatef(getCant(...), 0, 0, 1)} in the rail's local frame, so
     * a positive roll lifts the local +X side - the left rail while travelling
     * forward. The outer rail of a left-hand curve is the right one, hence the
     * negative sign for a left turn.</p>
     */
    public double signedCant() {
        double sign = this.turnLeft ? -1.0D : 1.0D;
        if (this.cantInvert) {
            sign = -sign;
        }
        return this.cantMagnitude() * sign;
    }

    /**
     * 超高上限（度）—— <b>不是随便定的，是 RTM 渲染路基的硬限制</b>。
     *
     * <p>RTM 的 {@code TileEntityLargeRailBase.getBlockHeights} 把路基顶面按超高平面倾斜：
     * 每个角点的高度 = {@code 轨面高 − y + sin(超高)·(该角点到中心线的距离)}，<b>不做任何夹紧</b>；
     * 而钢轨模型只在中心线处抬高 {@code |sin(超高)|·1.5}。于是超高越大，路基外侧爬得越高，
     * 超过约 12° 之后路基顶面就高过轨面 —— 钢轨被自己的路基埋掉，看上去就是
     * "只剩一条光秃秃的道砟带、轨道不见了"（第 57 轮用户实测截图：近端超高≈0 处路基平整、
     * 中段超高 20° 处路基外侧变成一堵一格多高的斜墙，道枕被吞掉）。</p>
     *
     * <p>取 10°：仍是 GB 50090 允许的最大超高（150 mm / 1500 mm ⇒ 5.74°）的近两倍，
     * 够用而不会把道床画成赛车场。</p>
     */
    public static final double MAX_CANT_DEG = 10.0D;

    /** 夹紧后的超高绝对值（度）。 */
    public double cantMagnitude() {
        double c = Math.abs(this.cantDeg);
        return c > MAX_CANT_DEG ? MAX_CANT_DEG : c;
    }

    /** Offsets of the parallel tracks, metres, left positive. */
    public double[] parallelOffsets() {
        int left = Math.max(0, this.linesLeft);
        int right = Math.max(0, this.linesRight);
        if (left == 0 && right == 0) {
            return new double[]{0.0D};
        }
        double[] out = new double[1 + left + right];
        int n = 0;
        out[n++] = 0.0D;
        for (int i = left; i >= 1; i--) {
            out[n++] = i * (double) this.spacingM;
        }
        for (int i = 1; i <= right; i++) {
            out[n++] = -i * (double) this.spacingM;
        }
        return out;
    }

    /** Small holder to avoid a circular import in {@link #read(ByteBuf)}. */
    static final class TrackToolCoreHolder {
        static boolean mismatch;

        static void markVersionMismatch() {
            mismatch = true;
        }
    }
}
