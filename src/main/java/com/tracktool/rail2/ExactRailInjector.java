package com.tracktool.rail2;

import jp.ngt.rtm.rail.TileEntityLargeRailCore;
import jp.ngt.rtm.rail.util.RailMap;
import jp.ngt.rtm.rail.util.RailMapBasic;
import jp.ngt.rtm.rail.util.RailPosition;
import net.minecraft.world.World;

import java.lang.reflect.Field;

/** 把 RailMapBasic 的两个 ILine 换成我们的解析线形（不改变对象类型）。
 *
 *  <p>做法：{@code core.railmap}（由 createRailMap() 建出的 RailMapBasic）内部的
 *  {@code lineHorizontal/lineVertical} 是两个 ILine 引用 —— 直接换掉它们，
 *  几何就由 {@link ExactLine} 提供，而 {@code railmap} 依旧是 {@code RailMapBasic}
 *  ⇒ 渲染器与车辆的既有判断照常工作（此前"全透明"就是因为换了子类）。</p>
 *
 *  <p>安全性：<b>只有两端 RailPosition.scriptArgs 带 ttx1; 参数的轨道才会被替换</b>，
 *  原版轨道与其它模组的轨道一律不动。</p> */
public final class ExactRailInjector {

    /** 本模组轨道的标记：写在两端 RP 的 anchorLengthHorizontal 上（该字段随 TE 同步到客户端，
     *  而 scriptArgs 不会 —— 实测 CLIENT-NO-PARAMS scriptArgs=null 已证实）。
     *  我们的几何不使用 anchorLength，故该值只作标记；anchorLengthVertical 用来携带缓和曲线长。 */
    public static final float MARK = -12345.0F;

    public static boolean hasMark(RailPosition[] rps) {
        return rps != null && rps.length >= 2
                && Math.abs(rps[0].anchorLengthHorizontal - MARK) < 1.0E-3F;
    }

    /** 由两端 RP 反推 [R, turn, ls, cant, rise, Rv, dir, yaw0, x, y, z]（与 ExactRailGeometryCodec 同序）。 */
    public static double[] paramsFromRPs(RailPosition a, RailPosition b) {
        double x0 = a.posX;
        double y0 = a.posY;
        double z0 = a.posZ;
        double yaw0 = a.anchorYaw;
        double yaw1 = b.anchorYaw - 180.0D;                 // 终点锚点存反向
        double dx = b.posX - x0;
        double dz = b.posZ - z0;
        double chord = Math.sqrt(dx * dx + dz * dz);
        double turn = yaw1 - yaw0;
        while (turn > 180.0D) {
            turn -= 360.0D;
        }
        while (turn < -180.0D) {
            turn += 360.0D;
        }
        double absTurn = Math.abs(turn);
        double dir = turn >= 0.0D ? 1.0D : -1.0D;
        double r = absTurn < 1.0E-3D
                ? 1.0E6D
                : Math.abs(chord / (2.0D * Math.sin(Math.toRadians(absTurn) * 0.5D)));
        double ls = Math.max(0.0D, (double) a.anchorLengthVertical);
        if (ls <= 0.0D) {
            ls = Math.min(40.0D, r * Math.toRadians(absTurn) * 0.25D);
        }
        double rise = b.posY - y0;
        return new double[]{r, absTurn, ls, 0.0D, rise, 15000.0D, dir, yaw0, x0, y0, z0};
    }
    private static final java.util.concurrent.ConcurrentHashMap<Long, String> ARGS = new java.util.concurrent.ConcurrentHashMap<Long, String>();
    /** 客户端：核心坐标 → 服务端送来的采样点表（与服务端铺设所用几何逐点同源）。 */
    private static final java.util.concurrent.ConcurrentHashMap<Long, SampledGeometry> SAMPLES =
            new java.util.concurrent.ConcurrentHashMap<Long, SampledGeometry>();

    public static void rememberArgs(int x, int y, int z, String args) {
        if (args != null && args.startsWith("ttx1")) {
            ARGS.put(key(x, y, z), args);
        }
    }

    /** 客户端收包后登记采样点表；实际替换在 tick 扫描里做（与 rememberArgs 同样的并发安全约定）。 */
    public static void rememberSamples(int x, int y, int z, SampledGeometry geo) {
        if (geo != null && geo.pointCount() >= 2) {
            SAMPLES.put(key(x, y, z), geo);
        }
    }

    /** 客户端：核心坐标 → 服务端【真实铺下去的那张方块表】。 */
    private static final java.util.concurrent.ConcurrentHashMap<Long, int[][]> BLOCKS =
            new java.util.concurrent.ConcurrentHashMap<Long, int[][]>();

    /** 最近一次铺设真实铺下的方块表（发包与补发都用它）。 */
    public static volatile int[][] lastBlockTable = null;

    public static void rememberBlocks(int x, int y, int z, int[][] table) {
        if (table != null && table.length > 0) {
            BLOCKS.put(key(x, y, z), table);
        }
    }

    /**
     * 把服务端的方块表塞进客户端 RailMap 的 {@code rails} 缓存。
     *
     * <p>为什么必须这么做：客户端画路基走的是自己算的
     * {@code createRailList}：{@code floor(x + sin(yaw±90°)·d)}。
     * 客户端几何是<b>采样点折线</b>（与服务端解析几何差 ~0.4 mm），
     * 只要某个采样点离格子边界不到这 0.4 mm，{@code floor} 就会翻到隔壁格
     * ⇒ 客户端要画的格子里没有轨道方块（画成碎块 = 空洞），而真正铺到的那格它又不画。
     * 227 m 的弯道大约 5400 次 floor 判定，落在 ±0.4 mm 边界内的期望≈4~5 格
     * —— 与"部分空洞"的数量级正好对上。直接用服务端的表就彻底消除这个误差。</p>
     */
    /** 由 {@code /tracktool test clientcheck} 置位，客户端 tick 里执行一次自检。 */
    public static volatile boolean diagRequested = false;

    /**
     * 客户端自检：把"客户端到底按什么在画路基"逐项报出来。
     *
     * <p>服务端已经证明方块一格不缺，所以剩下的可能性只有三种，这里一次分清：
     * ① 客户端几何不是我们的；② 客户端方块表不是服务端下发的那张；
     * ③ 表是对的，但客户端世界里那些格子取不到底座 TE（RTM 就会把它画成碎块）。</p>
     */
    public static void clientDiag(World world, double px, double py, double pz, java.util.List<String> out) {
        if (world == null || !world.isRemote) {
            return;
        }
        TileEntityLargeRailCore best = null;
        double bestD = Double.MAX_VALUE;
        for (Object o : snapshotTiles(world)) {
            if (!(o instanceof TileEntityLargeRailCore)) {
                continue;
            }
            TileEntityLargeRailCore c = (TileEntityLargeRailCore) o;
            double d = c.getPos().distanceSq(px, py, pz);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        if (best == null) {
            out.add("[clientdiag] 附近没有轨道核心");
            return;
        }
        try {
            init();
            Object map = railmapField.get(best);
            if (map == null) {
                best.getRailMap(null);
                map = railmapField.get(best);
            }
            Object curH = map == null ? null : lineHField.get(map);
            double len = map instanceof RailMapBasic ? ((RailMapBasic) map).getLength() : -1.0D;
            out.add(String.format("[clientdiag] core=%s railmap=%s 线形=%s len=%.3f",
                    best.getPos(), map == null ? "null" : map.getClass().getSimpleName(),
                    curH == null ? "null" : curH.getClass().getSimpleName(), len));

            int[][] table = BLOCKS.get(key(best.getPos().getX(), best.getPos().getY(), best.getPos().getZ()));
            java.util.List<int[]> cur = map == null ? null
                    : ((jp.ngt.rtm.rail.util.RailMap) map).getRailBlockList(best.getResourceState(), false);
            int curN = cur == null ? -1 : cur.size();
            int tabN = table == null ? -1 : table.length;
            int diff = 0;
            if (cur != null && table != null) {
                java.util.HashSet<Long> set = new java.util.HashSet<Long>();
                for (int[] b : table) {
                    set.add(key(b[0], b[1], b[2]));
                }
                for (int[] b : cur) {
                    if (!set.contains(key(b[0], b[1], b[2]))) {
                        diff++;
                    }
                }
            }
            out.add(String.format("[clientdiag] 方块表: 客户端在用 %d 格 / 服务端下发 %d 格 / 客户端多出来的 %d 格",
                    curN, tabN, diff));

            int noTe = 0;
            int noBlock = 0;
            String first = null;
            if (cur != null) {
                for (int[] b : cur) {
                    net.minecraft.util.math.BlockPos p = new net.minecraft.util.math.BlockPos(b[0], b[1], b[2]);
                    net.minecraft.block.Block blk = world.getBlockState(p).getBlock();
                    if (!(blk instanceof jp.ngt.rtm.rail.BlockLargeRailBase)) {
                        noBlock++;
                        if (first == null) {
                            first = "缺方块 " + p + "=" + blk.getRegistryName();
                        }
                        continue;
                    }
                    if (world.getTileEntity(p) == null) {
                        noTe++;
                        if (first == null) {
                            first = "缺TE " + p;
                        }
                    }
                }
            }
            out.add(String.format("[clientdiag] 客户端世界: 缺方块 %d 格 / 有方块但缺TE %d 格%s",
                    noBlock, noTe, first == null ? "" : "，首个: " + first));
        } catch (Throwable t) {
            out.add("[clientdiag] 异常: " + t);
        }
        railRenderDiag(world, px, py, pz, 200.0D, out);
    }

    /**
     * 客户端"钢轨到底画在哪"自检 —— 这是"路基在、钢轨不见/跑到隔壁线上"这类问题的唯一判据。
     *
     * <p>按 RTM 渲染器的原式逐项复算（源码 {@code RailPartsRendererBase.createRailPos} +
     * {@code renderStaticParts}）：</p>
     * <pre>
     *   rev     = RailPosition.REVISION[rp0.direction]
     *   moveX   = stPoint[1] − (startPoint[0] + 0.5 + rev[0])
     *   世界X   = startPoint[0] + (rp0.posX − rp0.blockX) + moveX + (cur[1] − st[1])
     *   世界Y   = startPoint[1] + (rp0.posY − rp0.blockY − 0.0625) + (getRailHeight − startRP.posY)
     * </pre>
     * <p>算出来的点再与<b>本核心自己的路基方块表</b>比最近距离：正常 &lt; 1.2 m
     * （钢轨中心线落在方块中心之间）；&gt; 2 m 就说明钢轨没画在自己的路基上。</p>
     *
     * <p>结果逐行打进 latest.log（聊天栏只给一行汇总，中文在聊天栏也看得清）。</p>
     */
    public static void railRenderDiag(World world, double px, double py, double pz,
                                      double radius, java.util.List<String> out) {
        if (world == null || !world.isRemote) {
            return;
        }
        int n = 0;
        int off = 0;
        int noGl = 0;
        double worstAll = 0.0D;
        String worstCore = "-";
        try {
            init();
            double r2 = radius * radius;
            System.out.println("[raildiag] ---- 客户端钢轨渲染自检（半径 " + (int) radius + " m）----");
            for (Object o : snapshotTiles(world)) {
                if (!(o instanceof TileEntityLargeRailCore)) {
                    continue;
                }
                TileEntityLargeRailCore core = (TileEntityLargeRailCore) o;
                if (core.getPos().distanceSq(px, py, pz) > r2) {
                    continue;
                }
                n++;
                String row = railRow(world, core);
                System.out.println(row);
                Double d = ROW_WORST;
                if (d != null && d > worstAll) {
                    worstAll = d;
                    worstCore = String.valueOf(core.getPos());
                }
                if (d != null && d > 2.0D) {
                    off++;
                }
                if (ROW_NO_GL) {
                    noGl++;
                }
            }
        } catch (Throwable t) {
            out.add("[raildiag] 异常: " + t);
            return;
        }
        out.add(String.format("[raildiag] 核心 %d 个：钢轨离开自己路基的 %d 个，没有 GL 列表的 %d 个，最大偏差 %.2f m @ %s（逐核心明细见 latest.log）",
                n, off, noGl, worstAll, worstCore));
    }

    private static Double ROW_WORST;
    private static boolean ROW_NO_GL;

    /** 单个核心一行；副作用写进 {@link #ROW_WORST} / {@link #ROW_NO_GL}（本方法只在客户端单线程调用）。 */
    private static String railRow(World world, TileEntityLargeRailCore core) {
        ROW_WORST = null;
        ROW_NO_GL = false;
        StringBuilder sb = new StringBuilder("[raildiag] ").append(core.getPos());
        try {
            Object map = railmapField.get(core);
            if (map == null) {
                core.getRailMap(null);
                map = railmapField.get(core);
            }
            if (!(map instanceof RailMapBasic)) {
                return sb.append(" railmap=").append(map == null ? "null" : map.getClass().getSimpleName()).toString();
            }
            RailMapBasic rm = (RailMapBasic) map;
            Object curH = lineHField.get(map);
            boolean exact = curH instanceof ExactLine;
            double len = rm.getLength();
            int max = (int) (len * 2.0D);
            RailPosition[] rps = core.getRailPositions();
            RailPosition rp0 = rps == null || rps.length == 0 ? null : rps[0];
            sb.append(" 线形=").append(curH == null ? "null" : curH.getClass().getSimpleName())
              .append(exact ? "(本模组)" : "(RTM贝塞尔!)")
              .append(String.format(" len=%.3f max=%d", len, max));
            if (rp0 == null || max < 1) {
                return sb.append(" ← RP/长度异常").toString();
            }
            int[] sp = core.getStartPoint();
            float[] rev = RailPosition.REVISION[rp0.direction & 7];
            double[] st = rm.getRailPos(max, 0);
            double moveX = st[1] - (sp[0] + 0.5D + rev[0]);
            double moveZ = st[0] - (sp[2] + 0.5D + rev[1]);
            double baseX = sp[0] + (rp0.posX - rp0.blockX) + moveX;
            double baseZ = sp[2] + (rp0.posZ - rp0.blockZ) + moveZ;
            double baseY = sp[1] + (rp0.posY - rp0.blockY - 0.0625D) - rm.getStartRP().posY;

            int[][] table = BLOCKS.get(key(core.getPos().getX(), core.getPos().getY(), core.getPos().getZ()));
            java.util.List<int[]> cells = table != null ? java.util.Arrays.asList(table)
                    : rm.getRailBlockList(core.getResourceState(), false);
            double worst = 0.0D;
            double wx0 = 0.0D;
            double wz0 = 0.0D;
            double wy0 = 0.0D;
            for (int i = 0; i <= max; i++) {
                double[] cur = rm.getRailPos(max, i);
                double wx = baseX + (cur[1] - st[1]);
                double wz = baseZ + (cur[0] - st[0]);
                double wy = baseY + rm.getRailHeight(max, i);
                if (i == 0) {
                    wx0 = wx;
                    wz0 = wz;
                    wy0 = wy;
                }
                double best = Double.MAX_VALUE;
                for (int[] b : cells) {
                    double dx = wx - (b[0] + 0.5D);
                    double dz = wz - (b[2] + 0.5D);
                    double dd = Math.sqrt(dx * dx + dz * dz);
                    if (dd < best) {
                        best = dd;
                    }
                }
                if (best > worst) {
                    worst = best;
                }
            }
            ROW_WORST = worst;
            sb.append(String.format(" 起点(%.2f,%.2f,%.2f) 钢轨离路基最大 %.2f m 表=%d格%s",
                    wx0, wy0, wz0, worst, cells.size(), worst > 2.0D ? " ←← 钢轨没在自己的路基上" : ""));
            sb.append(String.format(" cant=%.1f/%.1f/%.1f", rp0.cantEdge, rp0.cantCenter,
                    rps.length > 1 ? -rps[1].cantEdge : 0.0F));
            sb.append(" 钢轨Y=").append(String.format("%.3f", wy0));
            sb.append(ballastTop(world, core, rm, max, baseX, baseZ, baseY, st));
            sb.append(glInfo(core));
        } catch (Throwable t) {
            sb.append(" 异常 ").append(t);
        }
        return sb.toString();
    }

    /**
     * 路基"顶面"到底画到多高 —— 决定钢轨会不会被路基埋掉。
     *
     * <p>RTM 的 {@code TileEntityLargeRailBase.getBlockHeights} 把路基顶面按<b>超高平面</b>倾斜：
     * {@code fa[i] = railHeight − y + sin(cant)·(该角点到中心线的距离)·(±1)}，<b>不做任何夹紧</b>。
     * 超高大时路基边缘能抬到一格以上；若这个正负号与钢轨模型的滚转方向相反，
     * 钢轨就会被自己的路基顶面盖住 —— 表现正是"路基还在、钢轨不见了"。</p>
     */
    private static String ballastTop(World world, TileEntityLargeRailCore core, RailMapBasic rm,
                                     int max, double baseX, double baseZ, double baseY, double[] st) {
        try {
            int mid = max / 2;
            double[] cur = rm.getRailPos(max, mid);
            double wx = baseX + (cur[1] - st[1]);
            double wz = baseZ + (cur[0] - st[0]);
            double wy = baseY + rm.getRailHeight(max, mid);
            int bx = (int) Math.floor(wx);
            int bz = (int) Math.floor(wz);
            int by = core.getPos().getY();
            net.minecraft.tileentity.TileEntity te =
                    world.getTileEntity(new net.minecraft.util.math.BlockPos(bx, by, bz));
            if (!(te instanceof jp.ngt.rtm.rail.TileEntityLargeRailBase)) {
                return " 中点路基TE=无";
            }
            float bh = core.getResourceState().blockHeight;
            float[] fa = ((jp.ngt.rtm.rail.TileEntityLargeRailBase) te).getBlockHeights(bx, by, bz, bh, false);
            float lo = Math.min(Math.min(fa[0], fa[1]), Math.min(fa[2], fa[3]));
            float hi = Math.max(Math.max(fa[0], fa[1]), Math.max(fa[2], fa[3]));
            return String.format(" 中点钢轨Y=%.3f 该格路基顶面 %.3f..%.3f%s",
                    wy, by + lo, by + hi, (by + hi) > wy + 0.17D ? "  ←← 路基高过钢轨（被埋）" : "");
        } catch (Throwable t) {
            return " 路基顶面? " + t;
        }
    }

    /** 客户端专有字段（专用服务端没有）：钢轨/路基的 GL 列表与重绘标志。 */
    private static String glInfo(TileEntityLargeRailCore core) {
        StringBuilder sb = new StringBuilder();
        try {
            Object gl = readField(core, "glLists");
            Object blk = readField(core, "railBlocks");
            Object rr = readField(core, "shouldRerenderRail");
            Object rb = readField(core, "shouldRerenderBlock");
            boolean noGl = gl == null || java.lang.reflect.Array.getLength(gl) == 0
                    || java.lang.reflect.Array.get(gl, 0) == null;
            ROW_NO_GL = noGl;
            sb.append(" gl钢轨=").append(noGl ? "无" : "有")
              .append(" gl路基=").append(blk == null ? "无" : "有")
              .append(" 重绘标志=").append(rr).append("/").append(rb);
            Object modelSet = core.getResourceState().getResourceSet();
            sb.append(" 模型=").append(modelSet == null ? "null" : modelSet.getClass().getSimpleName());
        } catch (Throwable t) {
            sb.append(" gl? ").append(t);
        }
        return sb.toString();
    }

    private static Object readField(Object o, String name) {
        try {
            Field f = TileEntityLargeRailCore.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 每个 railmap 对象已经装进去的那张表（RTM 重建 railmap 后会自动失效 ⇒ 下次再装一次）。
     *  不能只比较"格数是否相同"：客户端自己算出来的表往往<b>格数一样、个别格子不同</b>（A 格换成了 B 格）。 */
    private static final java.util.Map<Object, int[][]> APPLIED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, int[][]>());

    /** 确保这个 railmap 用的是服务端下发的方块表（<b>只在客户端做</b>：
     *  服务端自己铺的表才是权威，单人模式下静态表是两端共享的，不加这道判断会在服务端线程上
     *  拿客户端世界去比对，打出一堆假的"缺 N 格"）。 */
    private static void ensureBlockTable(TileEntityLargeRailCore core, Object map) {
        net.minecraft.world.World w = core.getWorld();
        if (w == null || !w.isRemote) {
            return;
        }
        int[][] table = BLOCKS.get(key(core.getPos().getX(), core.getPos().getY(), core.getPos().getZ()));
        if (table == null || map == null) {
            return;
        }
        if (APPLIED.get(map) == table) {
            return;
        }
        applyBlockTable(map, table);
        APPLIED.put(map, table);
        markDirtyForRender(core);
    }

    /**
     * 客户端：方块表装好之后，数一数有多少格在客户端世界里取不到底座 TE。
     * RTM 对这些格子会画 {@code renderMissingBlock} —— 用的正是<b>基岩贴图</b>，
     * 也就是玩家看到的"基岩路基"。把数量与首个坐标打进日志，便于定位是服务端没铺还是客户端没同步。
     */
    private static java.lang.reflect.Method reportMethod;
    private static boolean reportUnavailable;

    /**
     * 客户端自检的转发口 —— <b>必须走反射</b>。
     *
     * <p>实现放在 {@code com.tracktool.client.ClientMissingReport}：那段代码用到
     * {@code Minecraft.getMinecraft().world}，而该字段的类型是
     * {@code net.minecraft.client.multiplayer.WorldClient}。只要这行字节码待在本类里，
     * JVM <b>校验本类时</b>就要加载 {@code WorldClient} 做赋值兼容性检查，
     * 专用服务端上没有这个类 ⇒ {@code NoClassDefFoundError} ⇒ <b>整个 ExactRailInjector 不可用</b>，
     * 铺设时 {@code ExactRailLayer.place} 一碰它就抛异常并回退到旧分段路径
     * （第 64 轮服务器日志实证：<i>place 抛异常: NoClassDefFoundError: …/WorldClient …
     * 未走新路径 ⇒ 旧分段路径</i> —— 接头折角、两段错开、上坡不平滑全是它导致的）。
     * try/catch 兜不住这种情况：异常发生在<b>本类被链接的那一刻</b>，不在任何 try 块里。</p>
     */
    private static void reportMissing(int[][] table) {
        if (reportUnavailable) {
            return;
        }
        try {
            if (reportMethod == null) {
                Class<?> c = Class.forName("com.tracktool.client.ClientMissingReport");
                reportMethod = c.getMethod("report", int[][].class);
            }
            reportMethod.invoke(null, (Object) table);
        } catch (Throwable t) {
            reportUnavailable = true;       // 专用服务端：没有这个类，别再试了
        }
    }

    private static void applyBlockTable(Object map, int[][] table) {
        if (table == null || table.length == 0) {
            return;
        }
        try {
            if (railsField == null) {
                railsField = field(RailMap.class, "rails");
            }
            Object obj = railsField.get(map);
            if (!(obj instanceof java.util.List)) {
                return;
            }
            @SuppressWarnings("unchecked")
            java.util.List<int[]> list = (java.util.List<int[]>) obj;
            list.clear();
            for (int[] b : table) {
                list.add(new int[]{b[0], b[1], b[2]});
            }
            reportMissing(table);
        } catch (Throwable t) {
            System.out.println("[tracktool-exact] 方块表注入失败: " + t);
        }
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    /** 最近一次铺设成功时【核心方块】的位置（发包必须用它，客户端是按核心坐标查表的）。 */
    public static volatile net.minecraft.util.math.BlockPos lastCorePos = null;

    /** 最近一次铺设的采样点表（发给客户端 / 存档都用它）。 */
    public static volatile SampledGeometry lastSamples = null;

    /**
     * 取一份<b>快照</b>再遍历 —— 绝不能直接 for-each {@code world.loadedTileEntityList}。
     *
     * <p>我们在循环体里会调 {@code world.getTileEntity(...)}（装方块表、自检都要），
     * 而客户端的 {@code Chunk.getTileEntity} 遇到"区块里登记了但还没实例化"的方块时会
     * <b>当场创建 TE 并塞进 {@code loadedTileEntityList}</b> ⇒ 迭代器直接
     * {@code ConcurrentModificationException}。铺长线路（带超高/坡度时方块更多）正好最容易触发
     * —— 第 65 轮用户实测客户端崩溃就是这个（{@code swapNearbyClient} 第 732 行）。</p>
     */
    private static java.util.List<Object> snapshotTiles(World world) {
        try {
            return new java.util.ArrayList<Object>(world.loadedTileEntityList);
        } catch (Throwable t) {
            // 极端情况下拷贝本身也可能撞上并发修改：这一 tick 就跳过，下一 tick 再来
            return java.util.Collections.emptyList();
        }
    }

    private static Field railmapField;
    private static Field lineHField;
    private static Field lineVField;
    private static Field lengthField;
    private static Field railsField;

    private static boolean loggedNoParams = false;

    private ExactRailInjector() {
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static synchronized void init() throws NoSuchFieldException {
        if (railmapField == null) {
            railmapField = field(TileEntityLargeRailCore.class, "railmap");
        }
        if (lineHField == null) {
            lineHField = field(RailMapBasic.class, "lineHorizontal");
        }
        if (lineVField == null) {
            lineVField = field(RailMapBasic.class, "lineVertical");
        }
    }

    /** 用【指定的几何】替换核心轨道图的两条 ILine（对象类型仍是 RailMapBasic ⇒ 渲染器照常）。
     *
     *  <p>服务端铺设后必须用<b>铺设时那一个几何对象</b>调用本方法 —— 若再从参数串重建
     *  AlignmentGeometry，就又变成"铺设一套、渲染另一套"。</p> */
    public static boolean swapWith(TileEntityLargeRailCore core, RailPosition startRP, ExactRailGeometry geo, String tag) {
        try {
            init();
            Object map = railmapField.get(core);
            if (map == null) {
                // 读档后 railmap 是懒建的（源码 TileEntityLargeRailCore.getRailMap: railmap==null ⇒ createRailMap()）；
                // 先逼它建出来，否则这里永远拿不到 RailMapBasic，补注入会一直失败。
                core.getRailMap(null);
                map = railmapField.get(core);
            }
            if (!(map instanceof RailMapBasic)) {
                return false;
            }
            Object curH = lineHField.get(map);
            if (curH instanceof ExactLine && ((ExactLine) curH).geometry() == geo) {
                // 已是本几何 ⇒ 不重复替换、不刷屏；但方块表可能是替换之后才到的，补一次
                ensureBlockTable(core, map);
                return true;
            }
            lineHField.set(map, new ExactLine(geo, startRP, false));
            lineVField.set(map, new ExactLine(geo, startRP, true));
            // ★★ 换线之后必须把"旧几何留下的缓存"一并清掉，否则客户端仍按贝塞尔渲染
            //    （第 48 轮实测三个现象——道枕稀疏 / 路基与钢轨错位 / 路基有空洞——都是这里来的）：
            //    ① RailMapBasic.length 是懒缓存（源码 getLength(): if (length <= 0) …）。
            //       渲染取样数 max = (int)(getLength() * 2)（RailPartsRendererBase.createRailPos），
            //       旧长度 ⇒ 取样点数与实际长度不匹配 ⇒ 道枕间距变稀疏。
            //    ② RailMap.rails（路基方块表）也是懒缓存：客户端渲染路基走的是
            //       getRailBlockList(state, false)（RenderRailBlock.renderBlocks），缓存不清
            //       ⇒ 客户端按【旧几何】的方块表去画路基 ⇒ 与服务端实际铺的方块错位、还会出现空洞
            //       （那一格没有轨道方块 ⇒ renderMissingBlock）。
            clearStaleCaches(map);
            // ★ 有服务端方块表就直接用它（消除客户端 floor 量化误差造成的"空洞"）
            ensureBlockTable(core, map);
            markDirtyForRender(core);
            System.out.println("[tracktool-exact] SWAPPED lines (" + tag + ") at " + core.getPos()
                    + " len=" + String.format("%.3f", geo.length()));
            return true;
        } catch (Throwable t) {
            System.out.println("[tracktool-exact] swapWith failed: " + t);
            return false;
        }
    }

    /** 清掉 RailMapBasic 里按【旧线形】算出来的两处懒缓存：总长 length、路基方块表 rails。 */
    private static void clearStaleCaches(Object map) {
        try {
            if (lengthField == null) {
                lengthField = field(RailMapBasic.class, "length");
            }
            lengthField.setDouble(map, 0.0D);                 // 下次 getLength() 会用我们的线形重算
        } catch (Throwable t) {
            System.out.println("[tracktool-exact] length 缓存未清: " + t);
        }
        try {
            if (railsField == null) {
                railsField = field(RailMap.class, "rails");   // protected final List<int[]>
            }
            Object list = railsField.get(map);
            if (list instanceof java.util.List) {
                ((java.util.List<?>) list).clear();           // 下次 getRailBlockList 会按新几何重建
            }
        } catch (Throwable t) {
            System.out.println("[tracktool-exact] rails 缓存未清: " + t);
        }
    }

    /** 客户端：把已经编译好的 GL 列表标脏，让 RTM 按新几何重画钢轨与路基。
     *
     *  <p>{@code shouldRerenderRail / shouldRerenderBlock} 是 {@code @SideOnly(CLIENT)} 字段，
     *  专用服务端会被剥掉 ⇒ 一律走反射 + try/catch，并且只在客户端世界里做。</p> */
    private static void markDirtyForRender(TileEntityLargeRailCore core) {
        try {
            net.minecraft.world.World world = core.getWorld();
            if (world == null || !world.isRemote) {
                return;
            }
            setBooleanField(core, "shouldRerenderRail");
            setBooleanField(core, "shouldRerenderBlock");
            jp.ngt.ngtlib.block.BlockUtil.markBlockForUpdate(world, core.getPos());
        } catch (Throwable t) {
            System.out.println("[tracktool-exact] 标脏失败: " + t);
        }
    }

    private static void setBooleanField(TileEntityLargeRailCore core, String name) {
        try {
            java.lang.reflect.Field f = TileEntityLargeRailCore.class.getDeclaredField(name);
            f.setAccessible(true);
            f.setBoolean(core, true);
        } catch (Throwable ignored) {
            // 专用服务端上这两个字段不存在，属正常
        }
    }

    /** 客户端：用服务端送来的采样点表替换。 */
    public static boolean swapFromSamples(TileEntityLargeRailCore core, RailPosition[] rps, SampledGeometry geo) {
        return swapWith(core, rps[0], geo, "samples/" + geo.pointCount() + "pt");
    }

    public static boolean swapFromArgs(TileEntityLargeRailCore core, RailPosition[] rps, String args) {
        try {
            init();
            Object map = railmapField.get(core);
            if (!(map instanceof RailMapBasic)) {
                return false;
            }
            double[] p = ExactRailGeometryCodec.decode(args);
            if (p == null) {
                return false;
            }
            AlignmentGeometry g = ExactRailGeometryCodec.build(p);
            lineHField.set(map, new ExactLine(g, rps[0], false));
            lineVField.set(map, new ExactLine(g, rps[0], true));
            System.out.println("[tracktool-exact] SWAPPED from packet at " + core.getPos() + " len=" + String.format("%.3f", g.length()));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 客户端：用两端 RP 反推的几何参数替换（不依赖 scriptArgs）。 */
    public static boolean swapFromRPs(TileEntityLargeRailCore core, RailPosition[] rps) {
        try {
            init();
            Object map = railmapField.get(core);
            if (!(map instanceof RailMapBasic)) {
                return false;
            }
            double[] p = paramsFromRPs(rps[0], rps[1]);
            AlignmentGeometry g = ExactRailGeometryCodec.build(p);
            lineHField.set(map, new ExactLine(g, rps[0], false));
            lineVField.set(map, new ExactLine(g, rps[0], true));
            System.out.println("[tracktool-exact] SWAPPED from RPs at " + core.getPos()
                    + " len=" + String.format("%.3f", g.length()));
            return true;
        } catch (Throwable t) {
            System.out.println("[tracktool-exact] swapFromRPs failed: " + t);
            return false;
        }
    }

    /** 这张轨道图是不是已经换成了我们的解析线形（两端点要按几何取，而不是按 RailPosition 取）。 */
    public static boolean isExactMap(Object map) {
        try {
            if (!(map instanceof RailMapBasic)) {
                return false;
            }
            init();
            return lineHField.get(map) instanceof ExactLine;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否本模组铺设的轨道（参数写在 scriptArgs，随 TE 同步到客户端）。 */
    public static boolean isExact(TileEntityLargeRailCore core) {
        RailPosition[] rps = core.getRailPositions();
        return rps != null && rps.length > 0 && ExactRailGeometryCodec.decode(rps[0].scriptArgs) != null;
    }

    /** 把一个核心的几何换成解析线形；返回是否替换成功（原版轨道返回 false）。 */
    public static boolean swapCore(TileEntityLargeRailCore core) {
        try {
            init();
            RailPosition[] rps = core.getRailPositions();
            if (rps == null || rps.length == 0) {
                return false;
            }
            double[] p = ExactRailGeometryCodec.decode(rps[0].scriptArgs);
            if (p == null) {
                return false;                       // 不是本模组的轨道 ⇒ 不动
            }
            Object map = railmapField.get(core);
            if (!(map instanceof RailMapBasic)) {
                return false;
            }
            AlignmentGeometry g = ExactRailGeometryCodec.build(p);
            lineHField.set(map, new ExactLine(g, rps[0], false));
            lineVField.set(map, new ExactLine(g, rps[0], true));
            System.out.println("[tracktool-exact] SWAPPED lines at " + core.getPos()
                    + " len=" + String.format("%.3f", g.length()));
            return true;
        } catch (Throwable t) {
            System.out.println("[tracktool-exact] swap failed: " + t);
            return false;
        }
    }

    /** 客户端：扫描玩家附近已加载的核心并替换（每 N tick 一次，半径限制）。 */
    public static int swapNearbyClient(World world, double px, double py, double pz, double radius) {
        if (world == null || !world.isRemote) {
            return 0;
        }
        int done = 0;
        int seen = 0;
        double r2 = radius * radius;
        for (Object o : snapshotTiles(world)) {
            if (!(o instanceof TileEntityLargeRailCore)) {
                continue;
            }
            TileEntityLargeRailCore core = (TileEntityLargeRailCore) o;
            // ① 采样点表优先：与服务端铺设所用几何逐点同源（预览/实际/渲染三者一致）
            SampledGeometry sampled = SAMPLES.get(key(core.getPos().getX(), core.getPos().getY(), core.getPos().getZ()));
            // ★ 有点表的核心【不受扫描半径限制】：连接模式/长线路的核心可能在线路另一端，
            //   之前半径 64 会把它漏掉 ⇒ 客户端一直用 RTM 的贝塞尔渲染
            //   ⇒ 路基画成基岩（renderMissingBlock 用的就是基岩贴图）、车看着不沿轨道走。
            if (sampled == null) {
                double dx = core.getPos().getX() + 0.5D - px;
                double dy = core.getPos().getY() + 0.5D - py;
                double dz = core.getPos().getZ() + 0.5D - pz;
                if (dx * dx + dy * dy + dz * dz > r2) {
                    continue;
                }
            }
            seen++;
            if (sampled != null) {
                RailPosition[] rp0 = core.getRailPositions();
                if (rp0 != null && rp0.length >= 2 && swapFromSamples(core, rp0, sampled)) {
                    done++;
                    continue;
                }
            }
            // ② 退路：参数串（只能重建"缓和+圆+缓和"，与 plan 线形可能有细微差）
            String argsPkt = ARGS.get(key(core.getPos().getX(), core.getPos().getY(), core.getPos().getZ()));
            if (argsPkt != null) {
                RailPosition[] rp2 = core.getRailPositions();
                if (rp2 != null && rp2.length >= 2 && swapFromArgs(core, rp2, argsPkt)) {
                    done++;
                }
                continue;
            }
            if (!isExact(core)) {
                RailPosition[] rr = core.getRailPositions();
                if (hasMark(rr)) {
                    swapFromRPs(core, rr);          // 客户端：scriptArgs 不会同步，改用标记 + 反推
                    done++;
                } else if (!loggedNoParams) {
                    loggedNoParams = true;
                    System.out.println("[tracktool-exact] CLIENT-NO-PARAMS at " + core.getPos()
                            + " mark=" + (rr != null && rr.length > 0 ? rr[0].anchorLengthHorizontal : -1.0F));
                }
                continue;
            }
            if (swapCore(core)) {
                done++;
            }
        }
        return done;
    }
}