package com.tracktool.rail2;

import jp.ngt.ngtlib.block.BlockUtil;
import jp.ngt.rtm.RTMRail;
import jp.ngt.rtm.modelpack.state.ResourceStateRail;
import jp.ngt.rtm.rail.TileEntityLargeRailBase;
import jp.ngt.rtm.rail.TileEntityLargeRailCore;
import jp.ngt.rtm.rail.util.RailPosition;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.lang.reflect.Field;

/** A 方案的铺设层：把"一条弯道 = 一个核心 + 自定几何"落到世界。
 *
 *  <p>关键点：RTM 的 {@code RailMap.setRail(...)} 会调用 {@code createRailList()} 沿
 *  {@code getRailPos(i,j)/getRailHeight(i,j)/getRailYaw(i,j)} 铺设路基与轨道方块 ——
 *  而这些方法已被 {@link ExactRailMap} 全部委托给我们的解析几何，
 *  <b>所以方块布局自动跟着我们的零量化几何走，不需要自己写方块采样</b> ✓</p>
 *
 *  <p>随后用反射把核心的 {@code railmap} 字段覆盖成 {@link ExactRailMap}
 *  （字段名与存在性已由 javap 确认：{@code protected jp.ngt.rtm.rail.util.RailMap railmap;}），
 *  于是渲染与车辆寻轨也走我们的几何。</p>
 *
 *  <p>两端 {@link RailPosition} 只作承载：几何参数经 {@link ExactRailGeometryCodec} 编码进
 *  {@code scriptArgs} 以跨端同步；<b>绝不设 scriptName</b>（否则 createRailMap() 会去建
 *  RailMapCustom，而本环境脚本引擎为 null 会抛异常）。</p> */
public final class ExactRailLayer {

    private static Field railmapField;

    private ExactRailLayer() {
    }

    private static Field railmap() throws NoSuchFieldException {
        if (railmapField == null) {
            Field f = TileEntityLargeRailCore.class.getDeclaredField("railmap");
            f.setAccessible(true);
            railmapField = f;
        }
        return railmapField;
    }

    /** 注入我们的轨道图；失败返回 false（调用方应回退到原版路径）。 */
    public static boolean inject(TileEntityLargeRailCore core, ExactRailMap map) {
        try {
            railmap().set(core, map);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 供客户端注入使用：若 scriptArgs 是我们的格式，则由调用方负责重建 ExactRailMap。 */
    public static boolean isExact(TileEntityLargeRailCore core) {
        RailPosition[] rps = core.getRailPositions();
        return rps != null && rps.length > 0 && ExactRailGeometryCodec.decode(rps[0].scriptArgs) != null;
    }

    /**
     * 铺一条"整条弯道一个核心"的轨道。
     *
     * @param geometry 解析几何（零量化）
     * @param args     由 {@link ExactRailGeometryCodec#encode} 生成的参数串（需同时写入两端 RP 的 scriptArgs）
     * @return true 表示核心与轨道图都建立成功
     */
    public static boolean place(World world, RailPosition start, RailPosition end,
                                ResourceStateRail prop, ExactRailGeometry geometry) {
        return place(world, start, end, prop, geometry, null);
    }

    /** 带撤回记录的版本：铺之前对"轨道方块 + 核心候选格 + 下方一格"做快照，成功时登记核心位置。 */
    public static boolean place(World world, RailPosition start, RailPosition end,
                                ResourceStateRail prop, ExactRailGeometry geometry,
                                com.tracktool.rail.RailPlacer.UndoRecord undo) {
        ExactRailMap map = new ExactRailMap(start, end, geometry);
        BlockPos sp = coreCellNearMid(map, prop, start, geometry);
        try {
            try {
                map.canPlaceRail(world, true, prop);       // ★ 原生序列：先判定/清理旧轨道
            } catch (Throwable ignored) {
            }
            // ★ RTM 的 addRailBlock 会主动跳过两端 RP 的 getNeighborBlockPos()
            //   （原生设计：那一格留给"接在这里的另一条轨道"去铺）。
            //   我们的起点 RP 是从既有轨道端点【原样复制】来的，direction 与对方相同 ⇒
            //   两条轨道排除的是【同一格】⇒ 接头处必然空一格。
            //   实测：/tracktool test cell 在 (222,4,305) 报 air、离中心线 0.50 m、不在任何表里。
            BlockPos[] joints = {start.getNeighborBlockPos(), end.getNeighborBlockPos()};
            // ★ 铺之前先把新线路路基范围里的旧底座清掉（RoadbedPreClear 的类注释有字节码依据）。
            //   RTM 的 setRail 不会清：正好重合的那格被"接管"，旧轨道照样在；高一格/低一格的旧底座
            //   根本不在新线的方块表里，原样留在线上 —— 列车撞到的就是这些。
            //   放在快照之前：无主方块清掉就清掉了，不进撤销记录；别人还活着的轨道由清理器自己先记录再拆。
            try {
                RoadbedPreClear.clear(world, map.getRailBlockList(prop, true),
                        java.util.Arrays.asList(joints), undo);
            } catch (Throwable t) {
                System.out.println("[tracktool-exact] PRE-CLEAR 异常（继续铺设）: " + t);
            }
            if (undo != null) {
                try {
                    // ★ 轨道方块要连 TE 快照一起存：只还原方块状态会造出"没有 railPositions
                    //   的空核心"，RTM 打包区块时 writeRailData 不判空 ⇒ 服务端 NPE ⇒ 存档进不去
                    //   （第 62 轮用户实测：撤回之后世界崩了）。
                    for (int[] b : map.getRailBlockList(prop, true)) {
                        BlockPos p = new BlockPos(b[0], b[1], b[2]);
                        undo.add(world, p, world.getBlockState(p));
                    }
                    for (BlockPos p : joints) {
                        undo.add(world, p, world.getBlockState(p));
                    }
                } catch (Throwable ignored) {
                }
            }
            // ★★ 核心坐标必须与传给 setRail 的坐标【完全一致】（字节码实证，第 47 轮）：
            //   RailMap.setRail(world, block, x, y, z, prop) 对它铺下的每个底座调用
            //   TileEntityLargeRailBase.setStartPoint(x, y, z)（RailMap.setRail 偏移 +149）；
            //   而 TileEntityLargeRailBase.getRailCore() 就是「取 startPoint 那一格的 TE 并要求它是
            //   TileEntityLargeRailCore」（TELargeRailBase.getRailCore +22），
            //   车辆寻轨 getRailMapFromCoordinates → getRailFromCoordinates → getRailMap → getRailCore。
            //   ⇒ 之前"核心放旁边一格、setRail 仍传 sp"会让【全部底座的 startPoint 指向一个没有核心的格子】
            //     ⇒ getRailCore()==null ⇒ 无法在弯道上放车（正是 result C 的功能缺口）。
            //   RTM 原生 BlockMarker.createNormalRail 就是把核心放在起点 RP 那一格（与 setRail 同坐标）。
            // ★★ 但【绝不能把核心放在别人的核心那一格】（第 48 轮实测事故："铺完原本的轨道没了"）：
            //    那一格已经是 rtm:large_rail_core 时，BlockUtil.setBlock 写的是同一个方块 ⇒ 等于无操作，
            //    于是我们拿到的是【别人的核心 TE】，随后 setRailPositions/createRailMap 就把那条轨道
            //    改写成了本次的线形 ⇒ 原来那条轨道消失。
            //    核心坐标不必等于起点 RP 那一格：渲染时 RailPartsRendererBase 用
            //    moveX = stPoint - (startPoint + 0.5 + REVISION) 再叠加 TE 自身的渲染位移，两者相消
            //    ⇒ 核心挪到旁边一格不影响外观；唯一的硬约束是【setRail 必须用同一坐标】（底座 startPoint 由它写）。
            BlockPos corePos = pickCorePos(world, sp);
            if (corePos == null) {
                System.out.println("[tracktool-exact] CORE-POS-NONE 周围全是别的轨道核心 " + sp);
                return false;
            }
            if (!corePos.equals(sp)) {
                System.out.println("[tracktool-exact] CORE-CELL-MOVED " + sp + " -> " + corePos
                        + "（那一格已有别的轨道核心，避免覆盖）");
            }
            map.setRail(world, RTMRail.largeRailBase, corePos.getX(), corePos.getY(), corePos.getZ(), prop);
            // ★ 防 result A（自我清轨）：核心格若已是底座，先把它的 startPoint 指回本格，
            //   这样 setBlock(core) 触发的 breakBlock → getCore() 必定得到 null（本格 TE 是底座不是核心）
            //   ⇒ 不会走 breakRail 把刚铺的轨道清成空气。
            TileEntity preTe = BlockUtil.getTileEntity(world, corePos.getX(), corePos.getY(), corePos.getZ());
            if (preTe instanceof TileEntityLargeRailBase && !(preTe instanceof TileEntityLargeRailCore)) {
                ((TileEntityLargeRailBase) preTe).setStartPoint(corePos.getX(), corePos.getY(), corePos.getZ());
            }
            // ★★ 从这一行起，corePos 上就是一颗【还没有 railPositions 的核心】——
            //    在 setRailPositions 跑到之前如果中途 return/抛异常，这颗空核心就会留在世界里，
            //    服务端下一次给玩家发这个 TE 的更新包时 writeRailData 直接 NPE（RTM 不判空）
            //    ⇒ "Exception ticking world"，服务器当场崩（第 63 轮用户提供的服务器崩溃报告）。
            //    所以下面所有失败出口都必须先把这颗核心清掉，见 finally 里的 armed 判断。
            boolean armed = true;
            try {
            BlockUtil.setBlock(world, corePos.getX(), corePos.getY(), corePos.getZ(), RTMRail.largeRailCore, 0, 3);
            if (!(BlockUtil.getTileEntity(world, corePos.getX(), corePos.getY(), corePos.getZ()) instanceof TileEntityLargeRailCore)) {
                System.out.println("[tracktool-exact] CORE-TILE-MISSING at " + sp
                        + " loaded=" + world.isBlockLoaded(sp)
                        + " block=" + String.valueOf(world.getBlockState(sp))
                        + " below=" + String.valueOf(world.getBlockState(sp.down())));
                return false;
            }
            ExactRailInjector.lastCorePos = new BlockPos(corePos.getX(), corePos.getY(), corePos.getZ());
            if (undo != null) {
                undo.cores.add(corePos);
            }
            TileEntityLargeRailCore core = (TileEntityLargeRailCore)
                    BlockUtil.getTileEntity(world, corePos.getX(), corePos.getY(), corePos.getZ());
            core.setRailPositions(new RailPosition[]{start, end});
            armed = false;                      // 有 railPositions 了，不再是"空核心"
            core.getResourceState().readFromNBT(prop.writeToNBT());
            core.setStartPoint(corePos.getX(), corePos.getY(), corePos.getZ());
            core.createRailMap();
            // ★ 新路线：不替换 railmap 本身（那会导致渲染器不认 ⇒ 全透明），
            //   只把 RailMapBasic 内部的两个 ILine 换成我们的解析线形。
            // ★ 必须用【本次铺设所用的那个几何对象】替换，不能再从参数串重建 ——
            //   否则"铺方块用一套几何、渲染/车辆用另一套"，预览与实际又会对不上。
            if (!ExactRailInjector.swapWith(core, start, geometry, "server")) {
                System.out.println("[tracktool-exact] SWAP-FAILED (railmap 非 RailMapBasic)");
                return false;
            }
            core.sendPacket();
            // ★ 底座↔核心关联的校验与补写（"能不能放车"的唯一硬条件）：
            //   逐格核对 TileEntityLargeRailBase.startPoint 是否等于核心坐标，不等就补写。
            //   正常情况下 setRail 已经写对（fixed=0），这里既是自检也是兜底；
            //   noTE 里应当恰好包含核心自己那一格（它现在是 core 而不是 base）。
            int linkOk = 0;
            int linkFixed = 0;
            int linkNoTe = 0;
            try {
                for (int[] b : map.getRailBlockList(prop, true)) {
                    TileEntity te = BlockUtil.getTileEntity(world, b[0], b[1], b[2]);
                    if (!(te instanceof TileEntityLargeRailBase)) {
                        linkNoTe++;
                        continue;
                    }
                    int[] st = ((TileEntityLargeRailBase) te).getStartPoint();
                    if (st != null && st.length >= 3
                            && st[0] == corePos.getX() && st[1] == corePos.getY() && st[2] == corePos.getZ()) {
                        linkOk++;
                    } else {
                        ((TileEntityLargeRailBase) te).setStartPoint(corePos.getX(), corePos.getY(), corePos.getZ());
                        linkFixed++;
                    }
                }
            } catch (Throwable t) {
                System.out.println("[tracktool-exact] BASE-LINK 校验异常: " + t);
            }
            System.out.println("[tracktool-exact] BASE-LINK core=" + corePos
                    + " ok=" + linkOk + " fixed=" + linkFixed + " noTE=" + linkNoTe);
            // ★ 补上 RTM 主动跳过的两端邻格（见上面 joints 的注释）：不补的话接头处永远是个洞
            java.util.List<int[]> extra = new java.util.ArrayList<int[]>();
            for (BlockPos p : joints) {
                if (p.equals(corePos)) {
                    continue;
                }
                net.minecraft.block.state.IBlockState st = world.getBlockState(p);
                if (st.getBlock() instanceof jp.ngt.rtm.rail.BlockLargeRailBase) {
                    continue;                       // 已经有轨道方块（相邻轨道铺过了）
                }
                if (!st.getBlock().isReplaceable(world, p) && st.getMaterial() != net.minecraft.block.material.Material.AIR) {
                    continue;                       // 别毁坏玩家的方块
                }
                BlockUtil.setBlock(world, p.getX(), p.getY(), p.getZ(), RTMRail.largeRailBase, 0, 3);
                TileEntity jte = BlockUtil.getTileEntity(world, p.getX(), p.getY(), p.getZ());
                if (jte instanceof TileEntityLargeRailBase) {
                    ((TileEntityLargeRailBase) jte).setStartPoint(corePos.getX(), corePos.getY(), corePos.getZ());
                    extra.add(new int[]{p.getX(), p.getY(), p.getZ()});
                }
            }
            System.out.println("[tracktool-exact] JOINT-FILL 补了 " + extra.size() + " 格接头（RTM 原生会跳过两端 RP 的邻格）");
            // ★ 采样点表：发给客户端（渲染同源）+ 写进存档（读档后服务端补注入，否则 railmap 退回贝塞尔）
            SampledGeometry samples = SampledGeometry.of(geometry);
            ExactRailInjector.lastSamples = samples;
            ExactRailPersistence.remember(world, corePos, samples);
            // ★ 真实铺下去的方块表也要给客户端：客户端自己算会因 floor 量化差出几格 ⇒ 路基空洞
            // ★ 再按世界实况兜底一次：表里有而世界里没有的格子会被 RTM 画成基岩贴图，
            //   这里挪到真正铺下去的那一格（正常路径是无操作，成本可以忽略）。
            ExactRailInjector.lastBlockTable =
                    snapTableToWorld(world, withExtra(blockTable(map, prop), extra));
            return true;
            } finally {
                // 任何失败出口（return false / 抛异常）都要把没写进 railPositions 的核心清掉
                if (armed) {
                    com.tracktool.rail.RailPlacer.sweepBrokenCore(world, corePos);
                }
            }
        } catch (Throwable t) {
            System.out.println("[tracktool-exact] place 抛异常: " + t); t.printStackTrace();
            return false;
        }
    }

    /** 读档补发时用：把两端 RP 的邻格（若世界里确实是轨道方块）并进方块表，
     *  否则客户端重进游戏后又会把接头那一格画成洞。 */
    public static int[][] withJointCells(World world, jp.ngt.rtm.rail.util.RailMap rm, int[][] table) {
        if (world == null || rm == null) {
            return table;
        }
        java.util.List<int[]> extra = new java.util.ArrayList<int[]>();
        try {
            BlockPos[] joints = {rm.getStartRP().getNeighborBlockPos(), rm.getEndRP().getNeighborBlockPos()};
            for (BlockPos p : joints) {
                if (world.getBlockState(p).getBlock() instanceof jp.ngt.rtm.rail.BlockLargeRailBase) {
                    extra.add(new int[]{p.getX(), p.getY(), p.getZ()});
                }
            }
        } catch (Throwable ignored) {
        }
        return withExtra(table, extra);
    }

    /**
     * 把方块表<b>对齐到世界里真正存在的轨道方块</b>：同一列上下各找一格，找不到就丢弃。
     *
     * <p>为什么需要：客户端拿这张表画路基，表里有而世界里没有的格子 RTM 会画成
     * {@code renderMissingBlock} —— <b>基岩贴图</b>，也就是玩家看到的"基岩路基"。
     * 第 60 轮的成因是重算表时用错了高度函数（见
     * {@link ExactRailServerSync}）：RTM 的 {@code RailMapBasic.getRailHeight} 比设计高多一份
     * {@code |sin(超高)|·1.5}，铺方块时 {@code int y = (int) 高度} 一取整就整体抬高一格 ——
     * 只在"有超高 <b>且</b> 有坡度"时才暴露，因为平道上 4.0625+0.26 仍然取整到 4。</p>
     *
     * <p>这里按世界实况兜底：老轨道、别的路径算出来的表，一并自愈。</p>
     */
    /** 本次铺设累计：被上下挪动 / 被丢弃的方块表格数（丢弃 = 世界里真的没铺上）。 */
    public static volatile int lastMoved;
    public static volatile int lastDropped;

    /** 一次铺设开始前清零。 */
    public static void resetTableStats() {
        lastMoved = 0;
        lastDropped = 0;
    }

    public static int[][] snapTableToWorld(World world, int[][] table) {
        if (world == null || table == null) {
            return table;
        }
        java.util.List<int[]> out = new java.util.ArrayList<int[]>(table.length);
        int moved = 0;
        int dropped = 0;
        int unloaded = 0;
        for (int[] b : table) {
            if (b == null || b.length < 3) {
                continue;
            }
            // ★ 区块没加载时【不下结论】：读档那一刻 RESYNC 会在大量区块还没载入时跑，
            //   当成"世界里没有"就会把好好的格子丢掉 ⇒ 路基整段消失。原样保留即可。
            if (!world.isBlockLoaded(new BlockPos(b[0], b[1], b[2]))) {
                out.add(b);
                unloaded++;
                continue;
            }
            if (isRailBlock(world, b[0], b[1], b[2])) {
                out.add(b);
            } else if (isRailBlock(world, b[0], b[1] - 1, b[2])) {
                out.add(new int[]{b[0], b[1] - 1, b[2]});
                moved++;
            } else if (isRailBlock(world, b[0], b[1] + 1, b[2])) {
                out.add(new int[]{b[0], b[1] + 1, b[2]});
                moved++;
            } else {
                dropped++;                      // 世界里真没有 ⇒ 不画总好过画成基岩
            }
        }
        if (moved > 0 || dropped > 0) {
            System.out.println("[tracktool-exact] 方块表对齐世界：上下挪了 " + moved + " 格，丢弃 " + dropped
                    + " 格" + (unloaded > 0 ? "，另有 " + unloaded + " 格所在区块未加载（原样保留）" : ""));
        }
        lastMoved += moved;
        lastDropped += dropped;
        return out.toArray(new int[out.size()][]);
    }

    /** 同一列上下探一格时用；调用方已确认该列所在区块是加载的（y±1 在同一区块）。 */
    private static boolean isRailBlock(World world, int x, int y, int z) {
        if (y < 0 || y > 255) {
            return false;
        }
        return world.getBlockState(new BlockPos(x, y, z)).getBlock()
                instanceof jp.ngt.rtm.rail.BlockLargeRailBase;
    }

    /** 把补铺的接头格并进方块表（客户端按这张表画路基）。 */
    static int[][] withExtra(int[][] table, java.util.List<int[]> extra) {
        if (table == null) {
            return extra.isEmpty() ? null : extra.toArray(new int[extra.size()][]);
        }
        if (extra.isEmpty()) {
            return table;
        }
        int[][] out = new int[table.length + extra.size()][];
        System.arraycopy(table, 0, out, 0, table.length);
        for (int i = 0; i < extra.size(); i++) {
            out[table.length + i] = extra.get(i);
        }
        return out;
    }

    /** 一个已铺好的核心：发包给客户端要用到的三样东西。 */
    public static final class Placed {
        public final BlockPos corePos;
        public final SampledGeometry samples;
        public final int[][] blocks;

        Placed(BlockPos corePos, SampledGeometry samples, int[][] blocks) {
            this.corePos = corePos;
            this.samples = samples;
            this.blocks = blocks;
        }
    }

    /**
     * 把一条线按 {@code segLen} 米切成多个核心铺设 —— <b>每段都是同一条解析几何的一段里程</b>
     * （{@link SubGeometry}），所以接头处逐点一致，不会像旧路径那样因为"逐段重新拟合贝塞尔 +
     * 锚点半格量化"而出现弯折。
     *
     * <p>切分的意义在渲染：RTM 是按核心渲染的，一个核心的钢轨/路基是一整个 GL 列表，
     * 核心所在区块出了视距整段就不画 —— 所以长线路必须切。</p>
     *
     * @param segLen 每段长度（&le;0 表示不切，整条一个核心）
     * @return 每个核心的发包材料；空表示失败
     */
    public static java.util.List<Placed> placeSegmented(World world, RailPosition start, RailPosition end,
                                                        ResourceStateRail prop, ExactRailGeometry geometry,
                                                        com.tracktool.rail.RailPlacer.UndoRecord undo,
                                                        double segLen) {
        java.util.List<Placed> out = new java.util.ArrayList<Placed>();
        double total = geometry.length();
        int n = segLen <= 0.0D ? 1 : (int) Math.max(1, Math.ceil(total / segLen));
        if (n == 1) {
            applyCant(start, end, geometry, 0.0D, total);
            if (place(world, start, end, prop, geometry, undo)) {
                out.add(new Placed(ExactRailInjector.lastCorePos,
                        ExactRailInjector.lastSamples, ExactRailInjector.lastBlockTable));
            }
            return out;
        }
        // ★ 切点对齐到中心线穿过方块边界的地方（见 edgeAlignedCuts），不再按 total·i/n 等分
        double[] cuts = edgeAlignedCuts(geometry, start.posX, start.posZ, total, n);
        RailPosition segStart = start;
        double prevT0 = 0.0D;
        BlockPos prevCore = null;
        for (int i = 0; i < n; i++) {
            double t0 = i == 0 ? 0.0D : cuts[i - 1];
            double t1 = i == n - 1 ? total : cuts[i];
            RailPosition segEnd = i == n - 1 ? end : nodeAt(geometry, start, t1, true);
            applyCant(segStart, segEnd, geometry, t0, t1);
            SubGeometry sub = SubGeometry.of(geometry, t0, t1, start, segStart);
            if (!place(world, segStart, segEnd, prop, sub, undo)) {
                // ★ 半条线不算成功：以前这里把已铺好的前几段当结果返回，调用方就报"铺设完成"，
                //   失败那一段 setRail 已经铺下的底座（核心没立起来）则成了无主路基。
                //   返回空表 ⇒ 调用方按撤销记录整体回滚（前几段 + 失败段的底座都在记录里）。
                System.out.println("[tracktool-exact] 分段 " + i + "/" + n + " 铺设失败 ⇒ 整条线按失败处理，交由调用方回滚");
                return new java.util.ArrayList<Placed>();
            }
            BlockPos thisCore = ExactRailInjector.lastCorePos;
            out.add(new Placed(thisCore,
                    ExactRailInjector.lastSamples, ExactRailInjector.lastBlockTable));
            if (prevCore != null && thisCore != null) {
                int[] r = fixJointOwnership(world, geometry, start, prevT0, t0, t1, prevCore, thisCore, undo);
                if (r[0] + r[1] + r[2] > 0) {
                    System.out.println("[tracktool-exact] JOINT-OWNER t=" + String.format("%.2f", t0)
                            + " 改归属 " + r[0] + " 格，补洞 " + r[1] + " 格，错侧核心 " + r[2] + " 格");
                }
            }
            prevCore = thisCore;
            prevT0 = t0;
            // 下一段的起点 RP 与本段终点同一个设计点（各自一份对象，避免两个核心共享同一个 RP）
            segStart = i == n - 1 ? end : nodeAt(geometry, start, t1, false);
        }
        System.out.println("[tracktool-exact] SEGMENTED " + n + " 段 × " + String.format("%.1f", total / n)
                + " m（同一条几何，接头逐点一致）");
        return out;
    }

    // ------------------------------------------------------------------
    // 接头：让列车能从一段开到下一段（第 72 轮：直线接头处卡死、抽搐、突然窜出）
    // ------------------------------------------------------------------
    //
    // RTM 的转向架怎么走（EntityBogie.updateBogiePos，AppleExtended 内置版反编译）：
    //   ① 提议点 p = 当前位置 + 速度·朝向；
    //   ② resetRailObj(p)：取 p 所在那一列的轨道方块，看它属于哪颗核心，不同才换轨；
    //   ③ pIndex = 当前轨道.getNearlestPoint(p) —— 取最近点，天然被夹在 [起点, 终点]。
    // 所以只要接头外侧那一列还归「当前这段」，车就被夹回本段端点、原地不动；状态仍报 MOVE，速度照涨。
    // 等一 tick 的位移大于那一截，p 掉进下一段的格子，就突然换轨、带着累积的速度窜出去 —— 正是用户看到的。
    //
    // 原生 RTM 没这个问题：RailPosition 永远在方块边上（pos = block + 0.5 + REVISION[dir]），
    // 接头两侧的格子各归各的。我们以前按 total·i/n 等分，接头落在方块中间，包含它的那一格只能归一颗核心。
    // 离线复现（同款逻辑的模拟）：沿 -x 的 97 m 直线要 89 km/h 才冲得过去。
    // 三处合起来才根治（少任何一处仍有方向会卡）：切点对齐方块边 + 核心放中点 + 接头归属校正。

    /**
     * 分段切点：在等分点前后 1 m 内，找中心线穿过方块边界的位置。
     * 任何方向上 2 m 长的线都至少跨一条整数网格线，所以总找得到；找不到（或会把某段压到 2 m 以下）就用等分点。
     * 只改切在哪里，不改几何本身。坐标与 {@link #nodeAt} 一致：世界点 = base + geo.x(t)。
     */
    static double[] edgeAlignedCuts(ExactRailGeometry geo, double baseX, double baseZ, double total, int n) {
        double[] cuts = new double[Math.max(0, n - 1)];
        double prev = 0.0D;
        for (int i = 1; i < n; i++) {
            double nominal = total * i / n;
            double best = cellEdgeCrossing(geo, baseX, baseZ, total, nominal, 1.0D);
            if (Double.isNaN(best) || best < prev + 2.0D || best > total - 2.0D) {
                best = nominal;
            }
            cuts[i - 1] = best;
            prev = best;
        }
        return cuts;
    }

    private static double cellEdgeCrossing(ExactRailGeometry geo, double bx, double bz,
                                           double total, double nominal, double radius) {
        double step = 0.01D;
        double best = Double.NaN;
        double tPrev = Math.max(0.0D, nominal - radius);
        long cPrev = cellKey(geo, bx, bz, tPrev);
        double tEnd = Math.min(total, nominal + radius);
        for (double t = tPrev + step; t <= tEnd + 1.0E-9D; t += step) {
            long c = cellKey(geo, bx, bz, t);
            if (c != cPrev) {
                double lo = tPrev;
                double hi = t;
                for (int k = 0; k < 50; k++) {
                    double mid = 0.5D * (lo + hi);
                    if (cellKey(geo, bx, bz, mid) == cPrev) {
                        lo = mid;
                    } else {
                        hi = mid;
                    }
                }
                if (Double.isNaN(best) || Math.abs(hi - nominal) < Math.abs(best - nominal)) {
                    best = hi;
                }
            }
            tPrev = t;
            cPrev = c;
        }
        return best;
    }

    private static long cellKey(ExactRailGeometry geo, double bx, double bz, double t) {
        long cx = (long) Math.floor(bx + geo.x(t));
        long cz = (long) Math.floor(bz + geo.z(t));
        return (cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /**
     * 核心放在本段<b>中点</b>最近的表内格子，而不是起点 RP 那一格。
     *
     * <p>核心方块没法改归属（它的 getRailCore() 永远是自己），而起点 RP 那一格常常在接头的<b>另一侧</b>：
     * 往 -x/-z 走的线，RP 格在接头后方（上一段的地盘）；第一段的起点 RP 是从既有轨道复制来的，
     * 那一格就是<b>既有轨道的最后一格</b>（存档实证：新线第一颗核心 (293,4,921) 正好在锚点轨道 293..388 的范围内）。
     * 车从这一段往回开，过了接头提议点仍落在本段核心那一格 ⇒ 被夹回端点。放在中点，核心永远在自己这一侧。</p>
     */
    private static BlockPos coreCellNearMid(ExactRailMap map, ResourceStateRail prop,
                                            RailPosition start, ExactRailGeometry geo) {
        BlockPos fallback = new BlockPos(start.blockX, start.blockY, start.blockZ);
        try {
            double half = geo.length() * 0.5D;
            double mx = start.posX + geo.x(half);
            double mz = start.posZ + geo.z(half);
            int[] best = null;
            double bestD = Double.MAX_VALUE;
            for (int[] b : map.getRailBlockList(prop, true)) {
                double dx = b[0] + 0.5D - mx;
                double dz = b[2] + 0.5D - mz;
                double d = dx * dx + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = b;
                }
            }
            return best == null ? fallback : new BlockPos(best[0], best[1], best[2]);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * 接头两侧 2 m 内、中心线经过的每一列：按几何归到正确的核心（接头前归前一段，之后归后一段）。
     *
     * <p>为什么还要这一步：RTM 的方块表只看几何采样、还要扣掉两端 RP 的邻格，而我们的 RP 是量化的载体、
     * 几何并不经过它；再加上后铺的一段会把重叠的格子全接管过来（setRail + BASE-LINK）。
     * 所以接头附近谁归谁，必须按几何显式定一遍。切点已对齐方块边 ⇒ 每一列只在一侧 ⇒ 归属唯一。</p>
     *
     * <p>只改当前归这两颗核心之一的底座（别的轨道一格不碰）；核心方块改不了就只计数。
     * 中心线上连一块轨道方块都没有的列（以前 JOINT-FILL 管的那种洞）就补一块、归正确的一侧，并记进撤销。</p>
     *
     * @return {改归属格数, 补洞格数, 被错侧核心占着的列数}
     */
    private static int[] fixJointOwnership(World world, ExactRailGeometry geo, RailPosition lineStart,
                                           double tA0, double tJ, double tB1,
                                           BlockPos coreA, BlockPos coreB,
                                           com.tracktool.rail.RailPlacer.UndoRecord undo) {
        // 列 -> {cx, cz, side(0=A,1=B,2=两侧都有), yMin, yMax}
        java.util.Map<Long, int[]> cols = new java.util.LinkedHashMap<Long, int[]>();
        for (int k = 1; k <= 40; k++) {
            double d = k * 0.05D;
            sampleColumn(cols, geo, lineStart, tJ - d, tJ - d > tA0, 0);
            sampleColumn(cols, geo, lineStart, tJ + d, tJ + d < tB1, 1);
        }
        int fixed = 0;
        int filled = 0;
        int blockedByCore = 0;
        for (int[] c : cols.values()) {
            if (c[2] == 2) {
                continue;                           // 两侧都经过（切在边上就不会出现）：不动
            }
            BlockPos target = c[2] == 0 ? coreA : coreB;
            BlockPos other = c[2] == 0 ? coreB : coreA;
            boolean hasOurs = false;
            boolean hasAnyRail = false;
            for (int y = c[3] - 1; y <= c[4] + 2; y++) {
                BlockPos p = new BlockPos(c[0], y, c[1]);
                if (!(world.getBlockState(p).getBlock() instanceof jp.ngt.rtm.rail.BlockLargeRailBase)) {
                    continue;
                }
                hasAnyRail = true;
                TileEntity te = world.getTileEntity(p);
                if (te instanceof TileEntityLargeRailCore) {
                    if (p.equals(other)) {
                        blockedByCore++;
                    }
                    hasOurs |= p.equals(target) || p.equals(other);
                    continue;
                }
                if (!(te instanceof TileEntityLargeRailBase)) {
                    continue;
                }
                int[] sp = ((TileEntityLargeRailBase) te).getStartPoint();
                boolean ownedByTarget = sp != null && sp.length >= 3
                        && sp[0] == target.getX() && sp[1] == target.getY() && sp[2] == target.getZ();
                boolean ownedByOther = sp != null && sp.length >= 3
                        && sp[0] == other.getX() && sp[1] == other.getY() && sp[2] == other.getZ();
                if (ownedByTarget) {
                    hasOurs = true;
                } else if (ownedByOther) {
                    ((TileEntityLargeRailBase) te).setStartPoint(target.getX(), target.getY(), target.getZ());
                    hasOurs = true;
                    fixed++;
                }
            }
            if (!hasOurs && !hasAnyRail) {
                BlockPos p = new BlockPos(c[0], c[3], c[1]);
                net.minecraft.block.state.IBlockState st = world.getBlockState(p);
                if (st.getBlock().isReplaceable(world, p)
                        || st.getMaterial() == net.minecraft.block.material.Material.AIR) {
                    if (undo != null) {
                        undo.add(world, p, st);
                    }
                    BlockUtil.setBlock(world, p.getX(), p.getY(), p.getZ(), RTMRail.largeRailBase, 0, 3);
                    TileEntity te = BlockUtil.getTileEntity(world, p.getX(), p.getY(), p.getZ());
                    if (te instanceof TileEntityLargeRailBase) {
                        ((TileEntityLargeRailBase) te).setStartPoint(target.getX(), target.getY(), target.getZ());
                        filled++;
                    }
                }
            }
        }
        return new int[]{fixed, filled, blockedByCore};
    }

    private static void sampleColumn(java.util.Map<Long, int[]> cols, ExactRailGeometry geo,
                                     RailPosition lineStart, double t, boolean inRange, int side) {
        if (!inRange) {
            return;
        }
        int cx = (int) Math.floor(lineStart.posX + geo.x(t));
        int cz = (int) Math.floor(lineStart.posZ + geo.z(t));
        int y = (int) (lineStart.posY + geo.height(t));
        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        int[] c = cols.get(key);
        if (c == null) {
            cols.put(key, new int[]{cx, cz, side, y, y});
            return;
        }
        if (c[2] != side) {
            c[2] = 2;
        }
        c[3] = Math.min(c[3], y);
        c[4] = Math.max(c[4], y);
    }

    /**
     * 把一段的<b>超高</b>写进两端 RailPosition。
     *
     * <p>超高不走我们的 ILine —— {@code RailMapBasic.getRailRoll} 只读两端 RP 的
     * {@code cantEdge / cantCenter}（字节码实证）：
     * {@code t=2·index/split}；{@code t≤1} 时 roll = (1−t)·start.cantEdge + t·start.cantCenter，
     * {@code t>1} 时 roll = (t−1)·(−end.cantEdge) + (2−t)·start.cantCenter。
     * 也就是"起点值 → 中点值 → 终点值"的折线。</p>
     *
     * <p>所以按本段的三个里程点取设计超高写进去即可：分段之后每段各自插值，
     * 合起来就是设计的超高顺坡曲线。（此前新路径用的是分段表首尾的 RP，超高恒为 0；
     * 切段之后中间节点又没写 cant，于是接头处会出现莫名其妙的扭转。）</p>
     */
    private static void applyCant(RailPosition a, RailPosition b, ExactRailGeometry geo, double t0, double t1) {
        try {
            float c0 = (float) geo.roll(t0);
            float cm = (float) geo.roll((t0 + t1) * 0.5D);
            float c1 = (float) geo.roll(t1);
            a.cantEdge = c0;
            a.cantCenter = cm;
            b.cantCenter = cm;             // RailMapBasic 构造时也会把它对齐成 start 的值
            b.cantEdge = -c1;
        } catch (Throwable ignored) {
            // 几何不支持超高就保持原样
        }
    }

    /**
     * 在里程 t 处造一个承载用的 RailPosition。
     *
     * <p>RailPosition 只能落在"半格网格 × 8 方向"上，所以这里会量化 —— 但<b>几何并不经过它</b>
     * （渲染时 startPoint 与 TE 位移相消，端点也已改为按几何取），它只是 RTM 存储结构需要的载体。</p>
     *
     * @param endAnchor 终点锚点要存 yaw+180 / -pitch（RTM 的约定）
     */
    private static RailPosition nodeAt(ExactRailGeometry geo, RailPosition lineStart, double t, boolean endAnchor) {
        double x = lineStart.posX + geo.x(t);
        double z = lineStart.posZ + geo.z(t);
        double y = lineStart.posY + geo.height(t);
        double yaw = geo.yaw(t);
        double pitch = geo.pitch(t);
        double[] snapped = com.tracktool.rail.RailGrid.snap(x, z, yaw);
        RailPosition rp = com.tracktool.rail.RailGrid.make(snapped[0], snapped[1], y,
                endAnchor ? com.tracktool.util.Geo.normalize360(yaw + 180.0D) : yaw,
                endAnchor ? -pitch : pitch, 0);
        return rp;
    }

    /** 取一张 {x,y,z} 方块表（服务端真实铺设用的那一张）。 */
    public static int[][] blockTable(jp.ngt.rtm.rail.util.RailMap map, ResourceStateRail prop) {
        try {
            java.util.List<int[]> list = map.getRailBlockList(prop, true);
            int[][] out = new int[list.size()][];
            for (int i = 0; i < list.size(); i++) {
                int[] b = list.get(i);
                out[i] = new int[]{b[0], b[1], b[2]};
            }
            return out;
        } catch (Throwable t) {
            System.out.println("[tracktool-exact] 取方块表失败: " + t);
            return null;
        }
    }

    /** 选核心落点：优先起点那一格，若该格已被【别的轨道核心】占着就往旁边挪，全被占则返回 null。 */
    private static BlockPos pickCorePos(World world, BlockPos sp) {
        int[][] offs = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                        {1, 1}, {-1, -1}, {1, -1}, {-1, 1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}};
        for (int[] o : offs) {
            BlockPos cand = sp.add(o[0], 0, o[1]);
            if (BlockUtil.getTileEntity(world, cand.getX(), cand.getY(), cand.getZ())
                    instanceof TileEntityLargeRailCore) {
                continue;                       // 别人的核心：动它就等于把那条轨道改写/毁掉
            }
            return cand;
        }
        return null;
    }

    /** 反射可用的自检（不触碰世界）：确认字段存在且可写。 */
    public static boolean reflectiveAccessOk() {
        try {
            return railmap() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 离线自检入口（不需要游戏世界）。 */
    public static void main(String[] args) {
        System.out.println("railmap 字段反射可用: " + reflectiveAccessOk());
        double[] p = {300.0D, 45.0D, 30.0D, 105.0D, 0.0D, 15000.0D, 1.0D, 180.0D, -2399.5D, 4.0D, -816.0D};
        String s = ExactRailGeometryCodec.encode(p);
        double[] q = ExactRailGeometryCodec.decode(s);
        System.out.println("参数往返: " + (q != null ? "OK" : "FAIL") + "  args=" + s);
        AlignmentGeometry g = ExactRailGeometryCodec.build(p);
        System.out.printf("几何: L=%.3f  终点yaw=%.4f°%n", g.length(), g.yaw(g.length()));
    }


    // ===== 客户端注入 =====
    // 客户端收到 PacketLargeRailCore 后会自己 createRailMap()（于是退回 RailMapBasic）。
    // 这里在客户端 tick 里扫描玩家附近已加载的核心，凡是"参数是本模组格式"的（isExact）
    // 就补注入一次我们的 ExactRailMap，使渲染与本地预测也用零量化几何。
    // 选择"扫描"而不是挂事件，是为了避免对 RTM 内部读包流程做任何假设。

    /** @return 本次注入的核心数量 */
    public static int injectNearbyClient(World world, double px, double py, double pz, double radius) {
        if (world == null || !world.isRemote) {
            return 0;
        }
        int done = 0;
        double r2 = radius * radius;
        for (Object o : world.loadedTileEntityList) {
            if (!(o instanceof TileEntityLargeRailCore)) {
                continue;
            }
            TileEntityLargeRailCore core = (TileEntityLargeRailCore) o;
            if (!isExact(core)) {
                continue;
            }
            double dx = core.getPos().getX() + 0.5D - px;
            double dy = core.getPos().getY() + 0.5D - py;
            double dz = core.getPos().getZ() + 0.5D - pz;
            if (dx * dx + dy * dy + dz * dz > r2) {
                continue;
            }
            RailPosition[] rps = core.getRailPositions();
            if (rps == null || rps.length < 2) {
                continue;
            }
            double[] p = ExactRailGeometryCodec.decode(rps[0].scriptArgs);
            if (p == null) {
                continue;
            }
            if (inject(core, new ExactRailMap(rps[0], rps[1], ExactRailGeometryCodec.build(p)))) {
                done++;
            }
        }
        return done;
    }}