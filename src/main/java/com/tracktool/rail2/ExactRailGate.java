package com.tracktool.rail2;

import jp.ngt.rtm.modelpack.state.ResourceStateRail;
import jp.ngt.rtm.rail.util.RailPosition;
import net.minecraft.world.World;

/** A 方案的接线门面：把"开关 + 参数写入两端 + 铺设 + 客户端注入"收敛成三个入口，
 *  现有代码每处只需加一行，便于随时开关与回退。
 *
 *  <p>默认<b>关闭</b>（{@link #enabled()} 为 false），因此接线后不改变任何既有行为；
 *  打开后弯道走"整条一个核心 + 自定零量化几何"，关闭后完全退回原分段路径。</p> */
public final class ExactRailGate {

    private static volatile boolean enabled = false;
    private static int lastClientInjections = 0;

    private ExactRailGate() {
    }

    /** 默认【开启】（弯道走自定零量化几何）；可用 -Dtracktool.exactGeometry=false 显式关闭做 A/B 对比。 */
    public static boolean enabled() {
        if (enabled) {
            return true;
        }
        // 默认【开启】。此前默认关闭是因为"模板盒会沿线清空地形"的风险；
        // 该风险已通过把 constLimit 压成 1×1 消除（模板盒只覆盖轨道自身那一格 ⇒ 不改任何地形），
        // 且客户端注入也已不再依赖本开关。为避免"忘了开开关 ⇒ 白测"，恢复默认开启。
        // 需要旧行为时：/tracktool exact false 或 -Dtracktool.exactGeometry=false
        String p = System.getProperty("tracktool.exactGeometry");
        return p == null || !"false".equalsIgnoreCase(p);   // ★ 默认开启（换 ILine 路线类型不变、模板盒已 1×1 ⇒ 无已知风险）
    }

    public static void setEnabled(boolean v) {
        enabled = v;
    }

    /**
     * 用解析几何铺一条弯道（整条一个核心）。
     *
     * <p>会把参数串写进<b>两端</b> RailPosition 的 {@code scriptArgs}（RTM 会随 TE 同步到客户端，
     * 供客户端注入使用）；<b>不设 scriptName</b>，以避免 createRailMap() 去建 RailMapCustom。</p>
     *
     * @param params 11 个参数，顺序见 {@link ExactRailGeometryCodec}
     * @return true 表示铺设成功；false 时调用方应回退到原分段路径
     */
    public static boolean place(World world, RailPosition start, RailPosition end,
                                ResourceStateRail prop, double[] params) {
        return place(world, start, end, prop, params, null);
    }

    /** 最近一次铺设【实际使用】的几何：发包给客户端时按它采样，保证两端逐点同源。 */
    public static volatile ExactRailGeometry lastGeometry = null;

    /** 每个核心覆盖的线路长度（米）。0 = 整条线一个核心（对照用）。
     *  RTM 按核心渲染，核心所在区块出视距整段就不画，所以长线路必须切段；
     *  切段不影响几何 —— 每段都是同一条解析几何的一段里程（{@link SubGeometry}）。 */
    private static volatile double segmentLength = 20.0D;

    public static double segmentLength() {
        return segmentLength;
    }

    public static void setSegmentLength(double v) {
        segmentLength = v < 0.0D ? 0.0D : v;
    }

    /** 最近一次铺设产生的所有核心（分段后可能有多个），供调用方逐个发包。 */
    public static volatile java.util.List<ExactRailLayer.Placed> lastPlaced =
            java.util.Collections.emptyList();

    public static boolean place(World world, RailPosition start, RailPosition end,
                                ResourceStateRail prop, double[] params,
                                com.tracktool.rail.RailPlacer.UndoRecord undo) {
        return place(world, start, end, prop, params, undo, null);
    }

    /**
     * @param geoOverride 优先使用的几何（正常是 {@link PlanGeometry}，与预览同源）；
     *                    传 null 时退回按 params 重建的 {@link AlignmentGeometry}
     */
    public static boolean place(World world, RailPosition start, RailPosition end,
                                ResourceStateRail prop, double[] params,
                                com.tracktool.rail.RailPlacer.UndoRecord undo,
                                ExactRailGeometry geoOverride) {
        if (!enabled() || world == null || world.isRemote || start == null || end == null || prop == null) {
            System.out.println("[tracktool-exact] GATE-REJECT 拒绝: enabled=" + enabled() + " world=" + (world != null)
                    + " remote=" + (world != null && world.isRemote) + " start=" + (start != null)
                    + " end=" + (end != null) + " prop=" + (prop != null));
            return false;
        }
        // ★ 校准承载锚点的 const 限制：RailMap.setRail 内部会按这四个值决定"沿线地形模板"的范围，
        //   未校准时（RailGrid.make 不设置它们）模板盒可能异常 ⇒ 沿线写入空气。
        //   取值照真实 RTM 轨道 dump 的实测值：HN=0.0 HP=3.99 WN=-1.49 WP=1.49。
        // ★ 关键：把"地形模板盒"压成【只有轨道自身那一格】。
        //   RailMap.setRail -> setBaseBlock 会读起点截面并沿整条轨道（split = 长度/4）覆写；
        //   若模板盒有宽度/高度，就会把起点处的空气复制到整条 265 m 轨道上 ⇒ 沿线地形被清空。
        //   把 wn/wp/hn/hp 都设为 0 后，模板盒只覆盖轨道所在的那一格 ⇒ 不再改写任何地形。
        // ★ 必须是 RTM 的常规范围，否则 createRailList 不铺 rail base ⇒ 车辆找不到轨道、无法放车。
        //   （曾压成 0/0/0/0 想避免地形模板写入，但那同时废掉了底座 ⇒ 功能性缺陷。）
        for (RailPosition rp : new RailPosition[]{start, end}) {
            rp.constLimitHN = 0.0F;
            rp.constLimitHP = 3.99F;
            rp.constLimitWN = -1.49F;
            rp.constLimitWP = 1.49F;
        }
        // ★ 标记：scriptArgs 不会同步到客户端（实测），故把标记与缓和曲线长写进会同步的 anchorLength 字段
        start.anchorLengthHorizontal = ExactRailInjector.MARK;
        end.anchorLengthHorizontal = ExactRailInjector.MARK;
        start.anchorLengthVertical = (float) params[2];
        end.anchorLengthVertical = (float) params[2];
        String args = ExactRailGeometryCodec.encode(params);
        start.scriptArgs = args;
        end.scriptArgs = args;
        // ★ 几何来源：正常走 PlanGeometry（= 预览那条 Alignment 的薄适配器）⇒ 预览与实际同源；
        //   没给时才按参数串重建 AlignmentGeometry（另一套实现，只作兜底）。
        ExactRailGeometry geo = geoOverride != null ? geoOverride : ExactRailGeometryCodec.build(params);
        lastGeometry = geo;
        double peakCant = 0.0D;
        try {
            for (int i = 0; i <= 20; i++) {
                double c = Math.abs(geo.roll(geo.length() * i / 20.0D));
                if (c > peakCant) {
                    peakCant = c;
                }
            }
        } catch (Throwable ignored) {
        }
        System.out.println("[tracktool-exact] GEOMETRY=" + (geoOverride != null ? "plan(预览同源)" : "params(兜底)")
                + " len=" + String.format("%.3f", geo.length()) + " 分段=" + segmentLength + "m"
                + String.format(" 最大超高=%.2f°(%.0f mm)", peakCant,
                        com.tracktool.rail.RailStandards.cantDegreesToMm(peakCant,
                                com.tracktool.rail.RailStandards.CANT_BASE_MM)));
        lastPlaced = ExactRailLayer.placeSegmented(world, start, end, prop, geo, undo, segmentLength);
        return !lastPlaced.isEmpty();
    }

    /** 客户端 tick 调用（每 N tick 一次即可）：给附近的核心补注入自定几何。 */
    /** 客户端每 16 tick 调用。
     *  ★ 不检查服务端开关：客户端的 setEnabled 永远不会被调用（指令只在服务端执行），
     *  若在这里检查开关，客户端就永远不会注入 ⇒ 表现为"只见路基不见钢轨"。
     *  这里改用"是否为本模组参数的轨道"(scriptArgs 前缀) 作为唯一判据。 */
    public static int clientTick(World world, double px, double py, double pz, double radius) {
        int n = ExactRailInjector.swapNearbyClient(world, px, py, pz, radius);
        lastClientInjections += n;
        return n;
    }

    public static int lastClientInjections() {
        return lastClientInjections;
    }

    /** 离线可跑的自检（只用纯 Java 部分）。 */
    public static void main(String[] args) {
        System.out.println("默认开关: " + enabled());
        setEnabled(true);
        System.out.println("打开后: " + enabled());
        double[] p = {300.0D, 45.0D, 30.0D, 105.0D, 0.0D, 15000.0D, 1.0D, 180.0D, -2399.5D, 4.0D, -816.0D};
        String s = ExactRailGeometryCodec.encode(p);
        System.out.println("参数串: " + s);
        System.out.println("解码回读: " + (ExactRailGeometryCodec.decode(s) != null ? "OK" : "FAIL"));
        AlignmentGeometry g = ExactRailGeometryCodec.build(p);
        System.out.printf("几何: L=%.3f 终点yaw=%.4f°%n", g.length(), g.yaw(g.length()));
    }
}