package com.tracktool.rail2;

import com.tracktool.TrackToolConfig;
import com.tracktool.TrackToolCore;
import com.tracktool.rail.RailPlacer;
import jp.ngt.rtm.rail.BlockLargeRailBase;
import jp.ngt.rtm.rail.TileEntityLargeRailBase;
import jp.ngt.rtm.rail.TileEntityLargeRailCore;
import jp.ngt.rtm.rail.util.RailMap;
import jp.ngt.rtm.rail.util.RailPosition;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>铺轨前</b>清理新线路路基范围里的旧轨道底座，然后才交给 RTM 铺。
 *
 * <h3>为什么必须在铺之前做</h3>
 * <p>RTM 的 {@code RailMap.setRail} 逐格处理（字节码偏移 94–152）：</p>
 * <pre>
 *   Block b = getBlock(x, y, z);
 *   if (!(b instanceof BlockLargeRailBase) || b == largeRailBase) {
 *       setBlock(x, y, z, largeRailBase);                       // 同一种方块 ⇒ MC 视为无变化，旧 TE 原样留着
 *       ((TileEntityLargeRailBase) getTileEntity(x, y, z)).setStartPoint(新核心);
 *   }                                                            // 别的 BlockLargeRailBase（核心、道岔底座）⇒ 整格跳过
 * </pre>
 * <p>也就是说旧底座不会被清掉：落在新线正好那一格的被「顺手接管」，旧轨道本身照样在（照样渲染）；
 * 旧核心、道岔底座那一格新线直接空着；高度差一两格的旧底座根本不在新线的方块表里，原样留在线上 ——
 * 没 TE 的是整格实心碰撞箱，有 TE 找不到核心的是卡在旧高度的 1/16 薄片，列车都会被顶住。</p>
 *
 * <h3>范围</h3>
 * <p>就是这条线 RTM 真正要写的那些格子（{@code getRailBlockList}）加两端接头格，
 * 每一列再往下 {@value #BELOW} 格、往上 {@value #ABOVE} 格 —— 旧的试铺线路因为超高/坡度取整，
 * 常常整体高一格或低一格。<b>水平方向不外扩</b>：隔壁的平行线不会被波及。</p>
 *
 * <h3>怎么处置</h3>
 * <ul>
 *   <li><b>无主</b>（{@link RoadbedVerdict.Kind#ORPHAN}）：直接清掉，<b>不进撤销记录</b> —— 撤销时把实心墙还回来毫无意义；</li>
 *   <li><b>别的活轨道的一格底座</b>：这一格先记进撤销记录再清掉；</li>
 *   <li><b>别的活轨道的核心</b>：说明整条旧轨道压在新线上（最常见的就是从同一个端点重铺），
 *       整条移除 —— 它的每一格和核心都先记进撤销记录，「撤销铺设」能原样还回来。</li>
 *   <li><b>受保护的</b>：端点所在的那条既有轨道（连接模式是两条）、本次操作里已经铺下的核心，一律不动。</li>
 * </ul>
 * <p>拆的时候都是<b>先摘 TE 再清方块</b>：{@code BlockLargeRailBase.breakBlock} 会 {@code getCore()}
 * 之后对整条轨道调 {@code breakRail}，TE 没了 getCore 才返回 null，否则清一格会顺着链子把整条轨道拆掉。</p>
 */
public final class RoadbedPreClear {

    /** 每一列往下多看几格。 */
    public static final int BELOW = 1;
    /** 每一列往上多看几格。 */
    public static final int ABOVE = 2;

    private RoadbedPreClear() {
    }

    /** 一次清理的结果；同一次操作的多段/多线累加在 {@link RailPlacer.UndoRecord#cleared} 里。 */
    public static final class Tally {
        public int orphans;
        public int foreignCells;
        /** 整条移除的旧轨道核心坐标。 */
        public final Set<BlockPos> removedRails = new LinkedHashSet<BlockPos>();
        /** 被清掉一部分底座、但核心不在新线范围内的旧轨道（报给玩家，方便他自己去敲核心）。 */
        public final Set<BlockPos> touchedRails = new LinkedHashSet<BlockPos>();

        public boolean isEmpty() {
            return this.orphans == 0 && this.foreignCells == 0 && this.removedRails.isEmpty();
        }

        public void addAll(Tally o) {
            this.orphans += o.orphans;
            this.foreignCells += o.foreignCells;
            this.removedRails.addAll(o.removedRails);
            this.touchedRails.addAll(o.touchedRails);
            this.touchedRails.removeAll(this.removedRails);
        }
    }

    /**
     * 铺一条（一段）轨道之前调用。
     *
     * @param footprint RTM 即将写入的格子（{@code map.getRailBlockList(prop, true)}）加接头格
     * @param undo      本次操作的撤销记录；为 null 时只清无主的
     */
    public static Tally clear(World world, List<int[]> footprint, Collection<BlockPos> extra,
                              RailPlacer.UndoRecord undo) {
        Tally tally = new Tally();
        if (world == null || world.isRemote || !TrackToolConfig.preClearRoadbed) {
            return tally;
        }
        Set<BlockPos> scan = new LinkedHashSet<BlockPos>();
        if (footprint != null) {
            for (int[] b : footprint) {
                addColumn(scan, b[0], b[1], b[2]);
            }
        }
        if (extra != null) {
            for (BlockPos p : extra) {
                addColumn(scan, p.getX(), p.getY(), p.getZ());
            }
        }
        Set<BlockPos> guarded = protectedCores(undo);

        List<BlockPos> orphans = new ArrayList<BlockPos>();
        Map<BlockPos, BlockPos> foreignCells = new LinkedHashMap<BlockPos, BlockPos>();   // 格子 → 所属核心
        Set<BlockPos> foreignRails = new LinkedHashSet<BlockPos>();
        for (BlockPos p : scan) {
            if (p.getY() < 0 || p.getY() > 255) {
                continue;
            }
            IBlockState st = world.getBlockState(p);
            if (!(st.getBlock() instanceof BlockLargeRailBase)) {
                continue;
            }
            Cell c = inspect(world, p, st);
            RoadbedVerdict.Kind k = c.isCore
                    ? RoadbedVerdict.ofCore(guarded.contains(p), c.hasTe, c.coreHasRailPositions)
                    : RoadbedVerdict.ofBase(c.hasTe, c.owner != null,
                            c.owner != null && guarded.contains(c.owner), c.ownerLive);
            if (undo == null || undo.rollbackOnly) {
                k = RoadbedVerdict.withoutUndo(k);
            }
            switch (k) {
                case ORPHAN:
                    orphans.add(p);
                    break;
                case FOREIGN_CELL:
                    foreignCells.put(p, c.owner);
                    break;
                case FOREIGN_RAIL:
                    foreignRails.add(p);
                    break;
                default:
                    break;
            }
        }

        // ① 整条压在新线上的旧轨道：连同它的每一格一起移除（记进撤销记录）
        for (BlockPos core : foreignRails) {
            int n = removeWholeRail(world, core, undo);
            if (n >= 0) {
                tally.removedRails.add(core);
                TrackToolCore.info("pre-clear: 整条移除压在新线上的旧轨道 核心=%s（%d 格）", core, n);
            } else {
                // 枚举不出它的格子：核心留着不动（只拆核心会把它整条底座变成无主的墙），
                // 落在新线范围里、属于它的底座由下面 ② 照常记录后清除。
                TrackToolCore.warn("pre-clear: 读不出旧轨道 %s 的方块表：核心保留，只清它落在新线范围内的底座", core);
            }
        }
        // ② 别的活轨道落在新线范围里的底座：逐格记录后清掉
        for (Map.Entry<BlockPos, BlockPos> e : foreignCells.entrySet()) {
            BlockPos p = e.getKey();
            if (tally.removedRails.contains(e.getValue())) {
                continue;                           // 已随整条轨道一起移除
            }
            if (!(world.getBlockState(p).getBlock() instanceof BlockLargeRailBase)) {
                continue;
            }
            undo.add(world, p, world.getBlockState(p));
            detachAndClear(world, p);
            tally.foreignCells++;
            tally.touchedRails.add(e.getValue());
        }
        // ③ 无主的：直接清，不记（撤销时把一堵实心墙还回来没有意义）
        for (BlockPos p : orphans) {
            if (!(world.getBlockState(p).getBlock() instanceof BlockLargeRailBase)) {
                continue;
            }
            detachAndClear(world, p);
            tally.orphans++;
        }
        tally.touchedRails.removeAll(tally.removedRails);
        if (!tally.isEmpty()) {
            TrackToolCore.info("pre-clear: 无主 %d 格，别的轨道底座 %d 格，整条移除 %d 条（扫描 %d 格）",
                    tally.orphans, tally.foreignCells, tally.removedRails.size(), scan.size());
        }
        if (undo != null) {
            undo.cleared.addAll(tally);
        }
        return tally;
    }

    /**
     * 撤销之后的复查：把区域里「无主的」和「指向刚被撤掉的核心的」底座全部清掉。
     *
     * <p>快照还原本身是对的，但它只能还原<b>快照里有的格子</b>。凡是没进快照的（铺的时候那格所在区块还没加载、
     * 旧路径的快照盒子装满了、RTM 实际铺的格子与方块表有出入 …），撤销之后就成了没有核心的底座 ——
     * 正是「撤回之后路基清不干净」。这一遍按世界实况兜底，不依赖快照是否完整。</p>
     *
     * @param region      要复查的格子（会按列上下外扩）
     * @param undoneCores 本次撤销移除的核心
     * @return 清掉的格数
     */
    public static int sweepAfterUndo(World world, Collection<BlockPos> region, Set<BlockPos> undoneCores) {
        if (world == null || world.isRemote || region == null) {
            return 0;
        }
        Set<BlockPos> scan = new LinkedHashSet<BlockPos>();
        for (BlockPos p : region) {
            addColumn(scan, p.getX(), p.getY(), p.getZ());
        }
        List<BlockPos> doomed = new ArrayList<BlockPos>();
        for (BlockPos p : scan) {
            if (p.getY() < 0 || p.getY() > 255) {
                continue;
            }
            IBlockState st = world.getBlockState(p);
            if (!(st.getBlock() instanceof BlockLargeRailBase)) {
                continue;
            }
            Cell c = inspect(world, p, st);
            boolean leftover;
            if (c.isCore) {
                leftover = undoneCores.contains(p) || !c.hasTe || !c.coreHasRailPositions;
            } else {
                leftover = RoadbedVerdict.leftoverAfterUndo(c.hasTe, c.owner != null,
                        c.owner != null && undoneCores.contains(c.owner), c.ownerLive);
            }
            if (leftover) {
                doomed.add(p);
            }
        }
        for (BlockPos p : doomed) {
            detachAndClear(world, p);
        }
        if (!doomed.isEmpty()) {
            TrackToolCore.info("undo: 复查清掉 %d 块残留路基（扫描 %d 格）", doomed.size(), scan.size());
        }
        return doomed.size();
    }

    /**
     * 一颗核心实际占用的格子：它每张 RailMap 的方块表，再加上表里每格周围 ±1 格内 startPoint 指向它的底座
     * （读档后核心的 railmap 可能退回贝塞尔，算出来的表与当初真实铺下的差一两格，这圈外扩把它们找回来）。
     */
    public static Set<BlockPos> cellsOf(World world, BlockPos corePos) {
        Set<BlockPos> out = new LinkedHashSet<BlockPos>();
        TileEntity te = world.getTileEntity(corePos);
        if (!(te instanceof TileEntityLargeRailCore)) {
            return out;
        }
        TileEntityLargeRailCore core = (TileEntityLargeRailCore) te;
        List<int[]> listed = new ArrayList<int[]>();
        try {
            RailMap[] maps = core.getAllRailMaps();
            if (maps != null) {
                for (RailMap rm : maps) {
                    if (rm != null) {
                        listed.addAll(rm.getRailBlockList(core.getResourceState(), true));
                    }
                }
            }
        } catch (Throwable t) {
            TrackToolCore.warn("读核心 %s 的方块表失败: %s", corePos, t.toString());
        }
        Set<BlockPos> seen = new HashSet<BlockPos>();
        for (int[] b : listed) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = -BELOW; dy <= ABOVE; dy++) {
                        BlockPos p = new BlockPos(b[0] + dx, b[1] + dy, b[2] + dz);
                        if (!seen.add(p) || p.getY() < 0 || p.getY() > 255) {
                            continue;
                        }
                        IBlockState st = world.getBlockState(p);
                        if (!(st.getBlock() instanceof BlockLargeRailBase) || p.equals(corePos)) {
                            continue;
                        }
                        Cell c = inspect(world, p, st);
                        if (corePos.equals(c.owner)) {
                            out.add(p);
                        }
                    }
                }
            }
        }
        return out;
    }

    /** @return 移除的格数；读不出这条轨道的格子时返回 -1（什么都不动） */
    private static int removeWholeRail(World world, BlockPos corePos, RailPlacer.UndoRecord undo) {
        if (undo == null) {
            return -1;
        }
        Set<BlockPos> cells = cellsOf(world, corePos);
        if (cells.isEmpty()) {
            return -1;
        }
        for (BlockPos p : cells) {
            undo.add(world, p, world.getBlockState(p));
            detachAndClear(world, p);
        }
        // 核心最后拆、记录在底座之后 ⇒ 撤销倒序还原时核心先回来，底座再回来指向它
        undo.add(world, corePos, world.getBlockState(corePos));
        detachAndClear(world, corePos);
        return cells.size() + 1;
    }

    /** 先摘 TE 再清方块：否则 breakBlock → getCore → breakRail 会把 TE 指向的那整条轨道拆掉。 */
    public static void detachAndClear(World world, BlockPos p) {
        world.removeTileEntity(p);
        world.setBlockToAir(p);
    }

    private static Set<BlockPos> protectedCores(RailPlacer.UndoRecord undo) {
        Set<BlockPos> out = new HashSet<BlockPos>();
        if (undo != null) {
            out.addAll(undo.cores);
            out.addAll(undo.protectedCores);
        }
        return out;
    }

    private static void addColumn(Set<BlockPos> scan, int x, int y, int z) {
        for (int dy = -BELOW; dy <= ABOVE; dy++) {
            scan.add(new BlockPos(x, y + dy, z));
        }
    }

    /** 从世界里读出一格轨道方块的归属事实。 */
    private static final class Cell {
        boolean isCore;
        boolean hasTe;
        boolean coreHasRailPositions;
        BlockPos owner;
        boolean ownerLive;
    }

    private static Cell inspect(World world, BlockPos p, IBlockState st) {
        Cell c = new Cell();
        c.isCore = ((BlockLargeRailBase) st.getBlock()).isCore();
        // CHECK：不许懒建 TE —— world.getTileEntity 会当场给没 TE 的底座建一个，把「实心墙」伪装成「有主」
        Chunk chunk = world.getChunk(p.getX() >> 4, p.getZ() >> 4);
        TileEntity te = chunk.getTileEntity(p, Chunk.EnumCreateEntityType.CHECK);
        if (c.isCore) {
            c.hasTe = te instanceof TileEntityLargeRailCore;
            c.coreHasRailPositions = c.hasTe && hasRailPositions((TileEntityLargeRailCore) te);
            c.owner = p;
            c.ownerLive = c.coreHasRailPositions;
            return c;
        }
        c.hasTe = te instanceof TileEntityLargeRailBase;
        if (!c.hasTe) {
            return c;
        }
        int[] sp = ((TileEntityLargeRailBase) te).getStartPoint();
        if (sp == null || sp.length < 3) {
            return c;
        }
        c.owner = new BlockPos(sp[0], sp[1], sp[2]);
        if (c.owner.getY() < 0 || c.owner.getY() > 255) {
            return c;
        }
        // 核心所在区块没加载就加载它：这里要的是确定的结论（判错一次就是一堵墙或一条被误拆的轨道），
        // 而同一条旧轨道的核心只有一颗，代价有限。
        TileEntity ownerTe = world.getTileEntity(c.owner);
        c.ownerLive = ownerTe instanceof TileEntityLargeRailCore && !ownerTe.isInvalid()
                && hasRailPositions((TileEntityLargeRailCore) ownerTe);
        return c;
    }

    private static boolean hasRailPositions(TileEntityLargeRailCore core) {
        RailPosition[] rps = core.getRailPositions();
        return rps != null && rps.length >= 2 && rps[0] != null && rps[1] != null;
    }
}
