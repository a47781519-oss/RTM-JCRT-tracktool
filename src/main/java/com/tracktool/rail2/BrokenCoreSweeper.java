package com.tracktool.rail2;

import jp.ngt.rtm.rail.TileEntityLargeRailCore;
import jp.ngt.rtm.rail.util.RailPosition;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 清理"空轨道核心"——没有 {@code railPositions} 的 {@link TileEntityLargeRailCore}。
 *
 * <p>为什么必须有：RTM 的 {@code TileEntityLargeRailCore.writeRailData}（源码第 106 行）是</p>
 * <pre>
 *   nbt.setTag("StartRP", this.railPositions[0].writeToNBT());
 * </pre>
 * <p><b>不判空</b>。而 {@code readRailData} 在 NBT 里没有 {@code StartRP} 时会让
 * {@code railPositions} 保持 {@code null}。于是只要世界里存在一颗"有方块、没数据"的核心，
 * 服务端给玩家打包这个区块（{@code SPacketChunkData}）时就会 NPE ——
 * 崩溃信息是 <i>Exception ticking world</i>，而且<b>每次进世界都会立刻再崩一次</b>，
 * 存档等于废了（第 62 轮用户实测：撤回之后再也进不去）。</p>
 *
 * <p>空核心是怎么来的：撤回时只把方块状态摆回去、或者只 {@code removeTileEntity} 而留下核心方块，
 * MC 都会给它懒建一个全新的空 TE。根因已在 {@code RailPlacer.restore} 修掉，
 * 本类负责<b>把已经坏掉的存档救回来</b>：区块一加载就扫一遍，
 * 发现空核心立刻补上两个合法的 RailPosition（这一步是同步的，保证同 tick 内打包不再 NPE），
 * 然后排进队列，在下一个服务端 tick 把方块清成空气。</p>
 */
public final class BrokenCoreSweeper {

    private final List<int[]> pending = new ArrayList<int[]>();   // {dim, x, y, z}
    private int total;

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        World world = event.getWorld();
        if (world == null || world.isRemote) {
            return;
        }
        Chunk chunk = event.getChunk();
        if (chunk == null) {
            return;
        }
        List<BlockPos> bad = null;
        try {
            for (TileEntity te : chunk.getTileEntityMap().values()) {
                if (!(te instanceof TileEntityLargeRailCore)) {
                    continue;
                }
                TileEntityLargeRailCore core = (TileEntityLargeRailCore) te;
                if (!isBroken(core)) {
                    continue;
                }
                // ① 立刻补成合法状态：本 tick 若被打包发送，writeRailData 才不会 NPE
                BlockPos p = core.getPos();
                RailPosition a = new RailPosition(p.getX(), p.getY(), p.getZ(), 0, 0);
                RailPosition b = new RailPosition(p.getX(), p.getY(), p.getZ(), 0, 0);
                core.setRailPositions(new RailPosition[]{a, b});
                if (bad == null) {
                    bad = new ArrayList<BlockPos>();
                }
                bad.add(p);
            }
        } catch (Throwable t) {
            System.out.println("[tracktool] 空核心扫描异常: " + t);
            return;
        }
        if (bad == null) {
            return;
        }
        int dim = world.provider.getDimension();
        synchronized (this.pending) {
            for (BlockPos p : bad) {
                this.pending.add(new int[]{dim, p.getX(), p.getY(), p.getZ()});
            }
        }
    }

    private int scanCounter;

    /**
     * ② 下一个 tick 真正把方块清掉（区块加载回调里改方块不安全）；
     *    顺带每 200 tick（10 秒）巡一遍已加载的 TE —— 区块<b>一直没卸载</b>的服务器
     *    光靠 {@code ChunkEvent.Load} 是扫不到的。
     */
    @SubscribeEvent
    public void onServerTick(net.minecraftforge.fml.common.gameevent.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END) {
            return;
        }
        if (++this.scanCounter >= 200) {
            this.scanCounter = 0;
            scanLoaded();
        }
        List<int[]> batch;
        synchronized (this.pending) {
            if (this.pending.isEmpty()) {
                return;
            }
            batch = new ArrayList<int[]>(this.pending);
            this.pending.clear();
        }
        net.minecraft.server.MinecraftServer server =
                net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return;
        }
        for (int[] e : batch) {
            try {
                World w = server.getWorld(e[0]);
                BlockPos p = new BlockPos(e[1], e[2], e[3]);
                if (w == null || !w.isBlockLoaded(p)) {
                    continue;
                }
                if (!(w.getBlockState(p).getBlock() instanceof jp.ngt.rtm.rail.BlockLargeRailBase)) {
                    continue;
                }
                w.removeTileEntity(p);
                w.setBlockToAir(p);
                this.total++;
            } catch (Throwable ignored) {
                // 单个失败不影响其它
            }
        }
        if (this.total > 0) {
            System.out.println("[tracktool] 已清理空轨道核心 " + this.total
                    + " 颗（它们会让服务端在打包区块时 NPE）");
            this.total = 0;
        }
    }

    /** 巡查所有已加载的核心（区块常驻的服务器用得上）。 */
    private void scanLoaded() {
        net.minecraft.server.MinecraftServer server =
                net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null || server.worlds == null) {
            return;
        }
        for (net.minecraft.world.WorldServer w : server.worlds) {
            if (w == null) {
                continue;
            }
            int dim = w.provider.getDimension();
            // 用下标遍历：世界 tick 期间列表可能被改动
            for (int i = 0; i < w.loadedTileEntityList.size(); i++) {
                TileEntity te;
                try {
                    te = w.loadedTileEntityList.get(i);
                } catch (Throwable t) {
                    break;
                }
                if (!(te instanceof TileEntityLargeRailCore) || te.isInvalid()) {
                    continue;
                }
                TileEntityLargeRailCore core = (TileEntityLargeRailCore) te;
                if (!isBroken(core)) {
                    continue;
                }
                BlockPos p = core.getPos();
                core.setRailPositions(new RailPosition[]{
                        new RailPosition(p.getX(), p.getY(), p.getZ(), 0, 0),
                        new RailPosition(p.getX(), p.getY(), p.getZ(), 0, 0)});
                synchronized (this.pending) {
                    this.pending.add(new int[]{dim, p.getX(), p.getY(), p.getZ()});
                }
            }
        }
    }

    private static boolean isBroken(TileEntityLargeRailCore core) {
        RailPosition[] rps = core.getRailPositions();
        return rps == null || rps.length < 2 || rps[0] == null || rps[1] == null;
    }
}
