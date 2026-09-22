package com.tracktool.client;

import jp.ngt.rtm.rail.BlockLargeRailBase;
import jp.ngt.rtm.rail.TileEntityLargeRailBase;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 客户端：防止 RTM 的轨道 TE 让区块数据包处理中途崩掉。
 *
 * <h3>现象（第 70 轮用户实测）</h3>
 * <p>在同一处「铺弯道 → 撤销 → 加 4 m 坡度再铺」，有一段轨道看上去被抬起来了。
 * 日志里第二次铺设的同一 tick，客户端连抛 7 次</p>
 * <pre>
 *   NullPointerException at Chunk.read(Chunk.java:1201)
 *     at NetHandlerPlayClient.handleChunkData(NetHandlerPlayClient.java:745)
 * </pre>
 * <p>最后那次撤销又是 7 次；第一次铺设（空地上）一次都没有。</p>
 *
 * <h3>字节码实证</h3>
 * <p>{@code Chunk.read} 第 1201 行（Forge 补丁）：</p>
 * <pre>
 *   if (te.shouldRefresh(world, pos, te.getBlockType().getStateFromMeta(...), getBlockState(pos)))
 * </pre>
 * <p>而 RTM 覆写了 {@code TileEntityLargeRailBase.getBlockType()}：</p>
 * <pre>
 *   if (blockType == null) { Block b = world.getBlockState(pos).getBlock();
 *                            if (b instanceof BlockLargeRailBase) blockType = b; }
 *   return blockType;       // 方块已经不是轨道、且之前没缓存过 ⇒ null
 * </pre>
 * <p>一个区块包把某格轨道换成了别的方块，而那格的 TE 从没被问过 {@code getBlockType}
 * （底座 TE 在客户端既不渲染也不在 tick 里调它，所以几乎全都没缓存）⇒ NPE。
 * {@code handleChunkData} 就此中断：<b>后面的渲染刷新和这个区块里所有 TE 的 NBT 全部没应用</b>，
 * 于是客户端在这几个区块里继续画着旧数据 —— 新旧两段轨道在接头处错开，看上去一段被抬了起来。
 * 这正是为什么只有「撤销后在原地再铺」才出现：第一次铺在空地上，区块里没有轨道 TE。</p>
 *
 * <p>日志里还有旁证：第一次撤销之后，{@code ClientMissingReport} 报了
 * {@code (30,4,1008)=minecraft:air te=ok} —— 客户端在空气格上还留着活的轨道 TE。</p>
 *
 * <h3>两道防线</h3>
 * <ol>
 *   <li><b>缓存方块类型</b>：新出现的轨道 TE，趁它那格还是轨道时调一次 {@code getBlockType()}，
 *       RTM 自己就会把它缓存下来。之后区块包再把这格换掉，{@code getBlockType()} 返回的是缓存的轨道方块，
 *       {@code shouldRefresh} 正常返回 true、TE 被正常作废，不再 NPE。</li>
 *   <li><b>清掉站在非轨道方块上的轨道 TE</b>：它们只可能是残留（客户端建 TE 时那格一定是轨道），
 *       留着就是下一个区块包的地雷。</li>
 * </ol>
 * <p>只动客户端自己的 TE 表，不发包、不改服务端任何东西。</p>
 *
 * <p>★ 客户端专用类（{@link SideOnly}），只从 {@link ClientTickHandler} 调用 ——
 * 专用服务端上根本不会加载它（见 {@link ClientMissingReport} 类注释里的教训）。</p>
 */
@SideOnly(Side.CLIENT)
public final class RailTileGuard {

    /** 清残留时最多看玩家周围几个区块（半径）。 */
    private static final int SWEEP_RADIUS_CHUNKS = 10;

    /** 已经缓存过方块类型的 TE（弱引用：TE 被回收就自动忘掉）。 */
    private static final Set<TileEntity> CACHED =
            Collections.newSetFromMap(new WeakHashMap<TileEntity, Boolean>());

    private static int ticks;
    private static int removedTotal;

    private RailTileGuard() {
    }

    public static void tick(Minecraft mc) {
        if (mc == null || mc.world == null || mc.player == null) {
            return;
        }
        ticks++;
        if ((ticks & 1) == 0) {
            cacheBlockTypes(mc.world);
        }
        if (ticks % 20 == 0) {
            int radius = Math.min(SWEEP_RADIUS_CHUNKS, Math.max(2, mc.gameSettings.renderDistanceChunks));
            sweepStale(mc.world, ((int) Math.floor(mc.player.posX)) >> 4,
                    ((int) Math.floor(mc.player.posZ)) >> 4, radius);
        }
    }

    /** ① 趁轨道 TE 那格还是轨道，让 RTM 把方块类型缓存下来。 */
    private static void cacheBlockTypes(World world) {
        List<TileEntity> list;
        try {
            list = new ArrayList<TileEntity>(world.loadedTileEntityList);
        } catch (Throwable t) {
            return;                         // 撞上并发修改：下一轮再来
        }
        for (TileEntity te : list) {
            if (!(te instanceof TileEntityLargeRailBase) || te.isInvalid() || CACHED.contains(te)) {
                continue;
            }
            try {
                if (world.getBlockState(te.getPos()).getBlock() instanceof BlockLargeRailBase
                        && te.getBlockType() != null) {
                    CACHED.add(te);
                }
            } catch (Throwable ignored) {
                // 单个 TE 出错不影响其它
            }
        }
    }

    /** ② 清掉站在非轨道方块上的轨道 TE（只看已加载的区块，不触发加载）。 */
    private static void sweepStale(World world, int pcx, int pcz, int radius) {
        List<BlockPos> stale = null;
        for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
            for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
                Chunk chunk = world.getChunkProvider().getLoadedChunk(cx, cz);
                if (chunk == null || chunk.isEmpty()) {
                    continue;
                }
                List<Map.Entry<BlockPos, TileEntity>> entries;
                try {
                    entries = new ArrayList<Map.Entry<BlockPos, TileEntity>>(chunk.getTileEntityMap().entrySet());
                } catch (Throwable t) {
                    continue;
                }
                for (Map.Entry<BlockPos, TileEntity> e : entries) {
                    if (!(e.getValue() instanceof TileEntityLargeRailBase)) {
                        continue;
                    }
                    BlockPos p = e.getKey();
                    if (chunk.getBlockState(p).getBlock() instanceof BlockLargeRailBase) {
                        continue;
                    }
                    if (stale == null) {
                        stale = new ArrayList<BlockPos>();
                    }
                    stale.add(p);
                }
            }
        }
        if (stale == null) {
            return;
        }
        for (BlockPos p : stale) {
            try {
                world.removeTileEntity(p);
            } catch (Throwable ignored) {
                // 单个失败不影响其它
            }
        }
        removedTotal += stale.size();
        System.out.println("[tracktool] 客户端清掉 " + stale.size() + " 个站在非轨道方块上的残留轨道 TE（累计 "
                + removedTotal + "）—— 它们会让下一个区块数据包在 Chunk.read 里 NPE");
    }
}
