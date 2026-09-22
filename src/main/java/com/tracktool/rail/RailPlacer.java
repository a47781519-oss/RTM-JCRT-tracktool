package com.tracktool.rail;

import jp.ngt.ngtlib.block.BlockUtil;
import jp.ngt.rtm.RTMRail;
import jp.ngt.rtm.modelpack.state.ResourceStateRail;
import jp.ngt.rtm.rail.BlockLargeRailBase;
import jp.ngt.rtm.rail.TileEntityLargeRailCore;
import jp.ngt.rtm.rail.util.RailMap;
import jp.ngt.rtm.rail.util.RailMapBasic;
import jp.ngt.rtm.rail.util.RailPosition;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes a planned rail into the world using RealTrainMod's own primitives.
 *
 * <p>The call sequence is copied from {@code BlockMarker.createNormalRail} so
 * the result is indistinguishable from a rail a player would have laid with two
 * markers: rail base blocks and ballast from {@code RailMap.setRail}, then a
 * {@code TileEntityLargeRailCore} carrying the two {@link RailPosition}s, the
 * resource state (rail model + ballast), the start point and finally the
 * network packet.</p>
 */
public final class RailPlacer {

    private RailPlacer() {
    }

    /** Why a plan cannot be placed, or null when it is fine. */
    public static String precheck(World world, List<PlanSegmentView> segments, ResourceStateRail prop,
                                  boolean creative, boolean ignoreObstacles) {
        for (PlanSegmentView v : segments) {
            if (v.start.blockY <= 0 || v.end.blockY <= 0) {
                // RTM's PacketCustom refuses y <= 0; fail early with a clear message.
                return "tracktool.err.y_too_low";
            }
        }
        if (creative || ignoreObstacles) {
            return null;
        }
        for (PlanSegmentView v : segments) {
            RailMapBasic rm;
            try {
                rm = new RailMapBasic(v.start, v.end);
            } catch (Throwable t) {
                return "tracktool.err.geometry";
            }
            if (!rm.canPlaceRail(world, false, prop)) {
                return "tracktool.err.obstacle";
            }
        }
        return null;
    }

    /** Minimal view of a plan segment, so this class stays independent of the planner. */
    public static final class PlanSegmentView {
        public final RailPosition start;
        public final RailPosition end;

        public PlanSegmentView(RailPosition start, RailPosition end) {
            this.start = start;
            this.end = end;
        }
    }

    /**
     * Places one rail core.
     *
     * @return true on success
     */
    public static boolean place(World world, RailPosition start, RailPosition end, ResourceStateRail prop,
                                boolean creative, UndoRecord undo) {
        RailMapBasic railMap;
        try {
            railMap = new RailMapBasic(start, end);
        } catch (Throwable t) {
            TrackToolCoreHolder.log("RailMapBasic failed", t);
            return false;
        }
        if (!creative && !railMap.canPlaceRail(world, false, prop)) {
            TrackToolCoreHolder.warn("canPlaceRail false at (%d,%d,%d)", start.blockX, start.blockY, start.blockZ);
            return false;
        }
        // ★ 铺之前先清掉新线路路基范围里的旧底座（见 RoadbedPreClear）：RTM 的 setRail 不清它们，
        //   只会把正好重合的那格"接管"过来，其余的原样留在线上挡车。
        //   必须在 snapshot 之前：清掉的无主方块不该进撤销记录（撤销时把实心墙还回来毫无意义）。
        try {
            com.tracktool.rail2.RoadbedPreClear.clear(world, railMap.getRailBlockList(prop, true),
                    java.util.Arrays.asList(start.getNeighborBlockPos(), end.getNeighborBlockPos()), undo);
        } catch (Throwable t) {
            TrackToolCoreHolder.log("pre-clear failed", t);
        }
        snapshot(world, start, end, undo);
        try {
            BlockPos sp = chooseCoreOrigin(world, railMap, prop, start);
            if (sp == null) {
                TrackToolCoreHolder.warn("no free core position for segment (%d,%d,%d)-(%d,%d,%d)",
                        start.blockX, start.blockY, start.blockZ, end.blockX, end.blockY, end.blockZ);
                return false;
            }
            railMap.setRail(world, RTMRail.largeRailBase, sp.getX(), sp.getY(), sp.getZ(), prop);
            // ★★ 核心方块一放下、railPositions 还没写进去之前，这颗核心是"空的"；
            //    中途失败若把它留在世界里，服务端发 TE 更新包时 writeRailData 会 NPE（RTM 不判空）
            //    ⇒ 整个服务器崩（第 63 轮服务器崩溃报告）。armed 保证所有出口都清理。
            boolean armed = true;
            try {
            boolean placed = world.setBlockState(sp, RTMRail.largeRailCore.getDefaultState(), 3);
            TileEntity tile = world.getTileEntity(sp);
            if (!(tile instanceof TileEntityLargeRailCore)) {
                TrackToolCoreHolder.warn("core tile missing at %s placed=%s block=%s tile=%s",
                        sp, placed, world.getBlockState(sp),
                        tile == null ? "null" : tile.getClass().getName());
                return false;
            }
            TileEntityLargeRailCore core = (TileEntityLargeRailCore) tile;
            core.setRailPositions(new RailPosition[]{start, end});
            armed = false;                      // 有 railPositions 了，不再是"空核心"
            core.getResourceState().readFromNBT(prop.writeToNBT());
            core.setStartPoint(sp.getX(), sp.getY(), sp.getZ());
            core.createRailMap();
            core.sendPacket();
            // H1 vs H2 diagnostic: is the core square itself part of the rail block list,
            // and is the ground under it intact? Read this from the server log after one lay.
            boolean inRailList = false;
            try {
                for (int[] b : railMap.getRailBlockList(prop, true)) {
                    if (b[0] == sp.getX() && b[1] == sp.getY() && b[2] == sp.getZ()) {
                        inRailList = true;
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            TrackToolCoreHolder.warn("core diag %s: coreBlock=%s belowBlock=%s inRailList=%s",
                    sp, world.getBlockState(sp), world.getBlockState(sp.down()), inRailList);
            if (undo != null) {
                undo.cores.add(sp);
            }
            // The far anchor must not keep a marker block sitting on the rail.
            Block endBlock = BlockUtil.getBlock(world, end.blockX, end.blockY, end.blockZ);
            if (endBlock == jp.ngt.rtm.RTMBlock.marker || endBlock == jp.ngt.rtm.RTMBlock.markerSwitch) {
                BlockUtil.setAir(world, end.blockX, end.blockY, end.blockZ);
            }
            return true;
            } finally {
                if (armed) {
                    sweepBrokenCore(world, sp);
                }
            }
        } catch (Throwable t) {
            TrackToolCoreHolder.log("rail placement failed", t);
            return false;
        }
    }

    /**
     * Picks where the rail core block will live.
     *
     * <p>Putting the core on a square that already holds <em>another</em> rail's
     * base block is destructive: changing the block type there makes Minecraft
     * call {@code BlockLargeRailBase.breakBlock}, which runs
     * {@code RailMap.breakRail} and wipes the whole existing rail - including
     * the one the player just extended. Overlapping rail base blocks are fine
     * (same block, so no state change, and only the ownership field is
     * rewritten), so the core simply moves to the first square of the new
     * segment that no other rail occupies yet.</p>
     *
     * @return the position to use, or null when the whole segment lies on
     *         somebody else's rail
     */
    private static BlockPos chooseCoreOrigin(World world, RailMapBasic railMap, ResourceStateRail prop,
                                             RailPosition start) {
        BlockPos anchor0 = new BlockPos(start.blockX, start.blockY, start.blockZ);
        if (world.isBlockLoaded(anchor0)
                && !(world.getBlockState(anchor0).getBlock() instanceof BlockLargeRailBase)) {
            return anchor0;
        }
        List<int[]> blocks;
        try {
            blocks = railMap.getRailBlockList(prop, true);
        } catch (Throwable t) {
            blocks = null;
        }
        if (blocks != null) {
            for (int[] b : blocks) {
                BlockPos p = new BlockPos(b[0], b[1], b[2]);
                if (!world.isBlockLoaded(p)) {
                    continue;
                }
                IBlockState st = world.getBlockState(p);
                if (st.getBlock() instanceof BlockLargeRailBase) {
                    continue;
                }
                // Prefer the rail's own anchor when it is free, so the layout
                // stays as close to RTM's as possible.
                if (p.getX() == start.blockX && p.getY() == start.blockY && p.getZ() == start.blockZ) {
                    return p;
                }
                if (isPerpendicularOk(world, p, start)) {
                    return p;
                }
            }
            for (int[] b : blocks) {
                BlockPos p = new BlockPos(b[0], b[1], b[2]);
                if (world.isBlockLoaded(p) && !(world.getBlockState(p).getBlock() instanceof BlockLargeRailBase)) {
                    return p;
                }
            }
        }
        BlockPos anchor = anchor0;
        // The whole segment lies on top of existing rail - the player is
        // re-laying this piece of track. Use the anchor anyway: Minecraft's
        // breakBlock will retire the old rail there, which is the expected
        // "replace what I just laid over" behaviour (RTM's own item instead
        // steals the blocks and leaves an orphaned core behind).
        TrackToolCoreHolder.warn("segment fully overlaps an existing rail at %s - replacing it", anchor);
        return anchor;
    }

    /** Keeps the core close to the anchor: within a few metres along the rail. */
    private static boolean isPerpendicularOk(World world, BlockPos p, RailPosition start) {
        double dx = p.getX() + 0.5D - start.posX;
        double dz = p.getZ() + 0.5D - start.posZ;
        return dx * dx + dz * dz <= 36.0D;
    }

    /**
     * Removes a previously placed core, exactly like breaking the rail block.
     */
    public static void breakCore(World world, RailPosition start, RailPosition end, ResourceStateRail prop) {
        try {
            RailMapBasic rm = new RailMapBasic(start, end);
            TileEntity tile = BlockUtil.getTileEntity(world, start.blockX, start.blockY, start.blockZ);
            if (tile instanceof TileEntityLargeRailCore) {
                ((TileEntityLargeRailCore) tile).getRailMap(null);
                rm.breakRail(world, prop, (TileEntityLargeRailCore) tile);
            } else {
                world.setBlockToAir(new BlockPos(start.blockX, start.blockY, start.blockZ));
            }
        } catch (Throwable t) {
            TrackToolCoreHolder.log("rail removal failed", t);
        }
    }

    /** Snapshot every block a segment may touch so the whole edit can be undone. */
    private static void snapshot(World world, RailPosition a, RailPosition b, UndoRecord undo) {
        if (undo == null || undo.isFull()) {
            return;
        }
        int minX = (int) Math.floor(Math.min(a.posX, b.posX)) - 3;
        int maxX = (int) Math.floor(Math.max(a.posX, b.posX)) + 3;
        int minZ = (int) Math.floor(Math.min(a.posZ, b.posZ)) - 3;
        int maxZ = (int) Math.floor(Math.max(a.posZ, b.posZ)) + 3;
        int minY = (int) Math.floor(Math.min(a.posY, b.posY)) - 1;
        int maxY = (int) Math.floor(Math.max(a.posY, b.posY)) + 6;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!world.isBlockLoaded(new BlockPos(x, minY, z))) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    // Air is recorded too: without it a rollback would leave the
                    // generated rail floating in what used to be empty space.
                    undo.add(world, p, world.getBlockState(p));
                    if (undo.isFull()) {
                        return;
                    }
                }
            }
        }
    }

    /**
     * Restores a snapshot; used by undo and by failure rollback.
     *
     * <p>★ 轨道核心必须连 TE 数据一起还原，否则会把存档搞崩（第 62 轮用户实测）：
     * {@code RailMapBasic} 的核心 TE 里 {@code railPositions} 是从 NBT 读出来的，
     * 只把方块状态摆回去会得到一个<b>没有 railPositions 的空核心</b>；
     * RTM 的 {@code TileEntityLargeRailCore.writeRailData} 直接写
     * {@code railPositions[0].writeToNBT()} <b>不判空</b>，于是区块打包发给玩家时
     * 抛 NullPointerException ⇒ "Exception ticking world" ⇒ 世界再也进不去。
     * 同理，本次铺下的核心必须<b>整块清成空气</b>，只 {@code removeTileEntity}
     * 会留下一个核心方块，MC 之后会给它懒建一个同样空的 TE，照样崩。</p>
     */
    public static int restore(World world, UndoRecord undo) {
        // ⓪ 先记下本次铺下的核心【实际】占了哪些格子 —— 核心一撤就再也读不到了。
        //   快照只管它记下来的格子；没进快照的（铺的时候区块没加载、快照装满、RTM 实际铺的与方块表有出入）
        //   撤销后会变成没有核心的底座，也就是"撤回之后路基清不干净"。④ 按这份清单兜底。
        java.util.Set<BlockPos> undoneCores = new java.util.HashSet<BlockPos>(undo.cores);
        java.util.List<BlockPos> recheck = new ArrayList<BlockPos>();
        for (BlockPos core : undo.cores) {
            recheck.add(core);
            try {
                recheck.addAll(com.tracktool.rail2.RoadbedPreClear.cellsOf(world, core));
            } catch (Throwable t) {
                TrackToolCoreHolder.log("undo: 读核心占用格失败 " + core, t);
            }
        }
        // ① 先把本次铺下的核心整块清掉（留着方块 = 留着一颗随时会崩的雷）
        //   ★ 不再跳过未加载的区块：跳过就等于把那几段轨道永远留在世界里（长线路铺完走远了再撤销，
        //     核心或底座所在的区块多半已经卸载）。getBlockState 会把区块读进来，撤销是一次性的显式操作，值得。
        for (BlockPos p : undo.cores) {
            if (world.getBlockState(p).getBlock() instanceof jp.ngt.rtm.rail.BlockLargeRailBase) {
                world.removeTileEntity(p);
                world.setBlockToAir(p);
            }
        }
        // ② 再按快照倒序还原；核心方块要连同当初那份 TE 数据一起还原
        for (int i = undo.positions.size() - 1; i >= 0; i--) {
            BlockPos p = undo.positions.get(i);
            IBlockState st = undo.states.get(i);
            world.setBlockState(p, st, 3);
            NBTTagCompound nbt = undo.tiles.get(p);
            if (nbt == null) {
                continue;
            }
            TileEntity te = world.getTileEntity(p);
            if (te != null) {
                try {
                    te.readFromNBT(nbt.copy());
                    te.markDirty();
                    world.notifyBlockUpdate(p, st, st, 3);
                } catch (Throwable t) {
                    TrackToolCoreHolder.log("undo: TE 还原失败 " + p, t);
                }
            }
        }
        // ③ 兜底：还原之后若还有"没有 railPositions 的空核心"，一律清掉
        for (int i = 0; i < undo.positions.size(); i++) {
            sweepBrokenCore(world, undo.positions.get(i));
        }
        for (BlockPos p : undo.cores) {
            sweepBrokenCore(world, p);
        }
        // ④ 按世界实况复查：本次核心占过的格子，加上快照里现在仍是轨道方块的格子，
        //   凡是无主的、或者还指向刚撤掉的核心的底座，全部清掉。
        //   别人的活轨道（包括刚从快照里还原回来的那些）不动 —— 判据见 RoadbedVerdict.leftoverAfterUndo。
        for (int i = 0; i < undo.positions.size(); i++) {
            BlockPos p = undo.positions.get(i);
            if (world.getBlockState(p).getBlock() instanceof jp.ngt.rtm.rail.BlockLargeRailBase) {
                recheck.add(p);
            }
        }
        int cleaned = 0;
        try {
            cleaned = com.tracktool.rail2.RoadbedPreClear.sweepAfterUndo(world, recheck, undoneCores);
        } catch (Throwable t) {
            TrackToolCoreHolder.log("undo: 残留复查失败", t);
        }
        return cleaned;
    }

    /** 清掉一颗"没有 railPositions"的空核心（它会让服务端在打包区块时 NPE）。 */
    public static boolean sweepBrokenCore(World world, BlockPos p) {
        if (world == null || p == null || !world.isBlockLoaded(p)) {
            return false;
        }
        TileEntity te = world.getTileEntity(p);
        if (!(te instanceof jp.ngt.rtm.rail.TileEntityLargeRailCore)) {
            return false;
        }
        RailPosition[] rps = ((jp.ngt.rtm.rail.TileEntityLargeRailCore) te).getRailPositions();
        if (rps != null && rps.length >= 2 && rps[0] != null && rps[1] != null) {
            return false;
        }
        world.removeTileEntity(p);
        world.setBlockToAir(p);
        com.tracktool.TrackToolCore.warn("清掉一颗空轨道核心（没有 railPositions，留着会让存档崩）: %s", p);
        return true;
    }

    /** Undo record holding the blocks a single generation touched. */
    public static final class UndoRecord {
        public static final int MAX_BLOCKS = 400000;

        public final List<BlockPos> positions = new ArrayList<BlockPos>();
        public final List<IBlockState> states = new ArrayList<IBlockState>();
        public final List<BlockPos> cores = new ArrayList<BlockPos>();
        /** 被覆盖的轨道 TE 的 NBT（只存轨道类，别的方块用不着）。 */
        public final java.util.Map<BlockPos, NBTTagCompound> tiles =
                new java.util.HashMap<BlockPos, NBTTagCompound>();
        /**
         * 铺轨前清理不许碰的核心：端点所在的既有轨道（连接模式是两条）。
         * 本次操作里已经铺下的核心在 {@link #cores} 里，同样受保护。
         */
        public final java.util.Set<BlockPos> protectedCores = new java.util.HashSet<BlockPos>();
        /** 本次操作的铺轨前清理累计（多段、多线加在一起），铺完报给玩家。 */
        public final com.tracktool.rail2.RoadbedPreClear.Tally cleared =
                new com.tracktool.rail2.RoadbedPreClear.Tally();
        /**
         * 只用于失败回滚、成功后即丢弃的记录。这种记录不会进撤销栈，所以铺轨前清理拿到它时
         * 只许清无主方块，别人还活着的轨道一格都不许动（动了就撤不回来）。
         */
        public boolean rollbackOnly;
        public String label = "";
        public int dim;
        public long time = System.currentTimeMillis();

        public void add(BlockPos p, IBlockState st) {
            this.positions.add(p);
            this.states.add(st);
        }

        /** 带 TE 快照的版本：轨道核心/底座必须走这一条，否则撤回会造出空核心。 */
        public void add(World world, BlockPos p, IBlockState st) {
            this.add(p, st);
            if (!(st.getBlock() instanceof jp.ngt.rtm.rail.BlockLargeRailBase)) {
                return;
            }
            if (this.tiles.containsKey(p)) {
                return;
            }
            try {
                TileEntity te = world.getTileEntity(p);
                if (te instanceof jp.ngt.rtm.rail.TileEntityLargeRailBase) {
                    this.tiles.put(p, te.writeToNBT(new NBTTagCompound()));
                }
            } catch (Throwable ignored) {
                // 拿不到就算了，第 ③ 步的兜底会把空核心清掉
            }
        }

        public boolean isFull() {
            return this.positions.size() >= MAX_BLOCKS;
        }

        public int size() {
            return this.positions.size();
        }
    }

    static final class TrackToolCoreHolder {
        static void log(String msg, Throwable t) {
            com.tracktool.TrackToolCore.warn("%s: %s", msg, t.toString());
        }

        static void warn(String fmt, Object... args) {
            com.tracktool.TrackToolCore.warn(fmt, args);
        }
    }
}
