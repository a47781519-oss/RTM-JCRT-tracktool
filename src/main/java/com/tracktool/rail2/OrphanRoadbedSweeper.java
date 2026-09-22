package com.tracktool.rail2;

import com.tracktool.TrackToolConfig;
import com.tracktool.TrackToolCore;
import jp.ngt.rtm.rail.BlockLargeRailBase;
import jp.ngt.rtm.rail.TileEntityLargeRailBase;
import jp.ngt.rtm.rail.TileEntityLargeRailCore;
import jp.ngt.rtm.rail.util.RailPosition;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * 铺完一段轨道后，自动清掉周围<b>无主的旧轨道路基方块</b>。
 *
 * <p><b>要解决的现象</b>（第 66 轮用户实测）：在连接模式铺完 S 形之后，列车沿线会
 * 「撞到东西」但又不脱轨。原因不在线形 —— 是老轨道留下的 {@code BlockLargeRailBase}
 * 还在世界里。</p>
 *
 * <p><b>字节码实证</b>（{@code BlockLargeRailBase.getAABBWithState}，第一条指令就是）：</p>
 * <pre>
 *   if (world.getTileEntity(pos) == null) return Block.FULL_BLOCK_AABB;
 *   float h = avg(te.getBlockHeights(...)); return new AABB(0,0,0, 1, max(h, 1/16), 1);
 * </pre>
 * <p>所以没有 TE 的路基方块是<b>整整一格的实心碰撞箱</b>——一堵看不见的墙。
 * 有 TE 但找不到核心的，{@code getBlockHeights} 会回退到 {@code THICKNESS}，
 * 碰撞箱只有 1/16 格高，但它卡在<b>旧轨道那个 Y</b> 上，与新线的高程、超高毫无关系。
 * 两种都是挡路的死方块，而 RTM 的列车是贴着 RailMap 走的，
 * 撞上只会被顶住、不会脱轨 —— 正好对上用户描述的症状。</p>
 *
 * <p>（顺带澄清：{@code AABB_ADD_Y = 256} 那一口只在 {@code preventMobMovement}
 * 且实体是 {@code EntityLiving} 时才加，是用来拦怪的，与列车无关。）</p>
 *
 * <h3>无主路基是怎么来的</h3>
 * <p>{@code BlockLargeRailBase.breakBlock} 是先 {@code getCore(world, pos)}、
 * 拿到核心再 {@code RailMap.breakRail} 把整条轨道一起拆掉。核心所在的区块<b>没加载</b>时
 * {@code getCore} 返回 null，这一步就整个跳过了 —— 玩家以为把旧轨道敲掉了，
 * 实际只掉了敲中的那一块，其余底座全部留在原地变成无主方块。
 * 长轨道跨好几个区块，这事非常容易发生。我们自己的 {@code RailMap.setRail}
 * 覆盖别人的底座时也会留下同样的东西。</p>
 *
 * <h3>判据：只清「无主」的，不碰活着的轨道</h3>
 * <p>{@link TileEntityLargeRailBase#getRailCore()} 就是拿 {@code startPoint} 去
 * {@code world.getTileEntity} 再 {@code instanceof TileEntityLargeRailCore}。
 * 所以<b>核心还在 = 轨道还活着</b>，这种底座一律不动；
 * 只有核心不见了、或者核心是颗没有 {@code railPositions} 的空核心，才判为无主并清除。</p>
 *
 * <p><b>为什么不能照字面「把 50 格内的旧路基全清掉」</b>：本模组支持一次铺
 * 左右若干条平行线（线间距最小 1 m）。按方块类型无差别清理的话，铺第 4 条线时
 * 会把前 3 条连同玩家早先修好的邻线一起抹掉 —— 那是不可逆的存档损坏。
 * 「无主」这个判据既能除掉挡车的那些方块（它们恰恰全是无主的），
 * 又保证任何一条还连着核心的轨道都不会掉一块。</p>
 *
 * <p>清理是<b>顺带</b>做的：只扫已经加载的区块，不为了扫地去强加载区块
 * （4 km 的线加 50 格走廊就是好几千个区块）。扫不到的那些，
 * {@link BrokenCoreSweeper} 会在区块加载时接着处理。</p>
 */
public final class OrphanRoadbedSweeper {

    public static final OrphanRoadbedSweeper INSTANCE = new OrphanRoadbedSweeper();

    /** 每 tick 最多扫几个区块（要遍历方块，比只看 TE 表贵，所以取得保守）。 */
    private static final int CHUNKS_PER_TICK = 8;
    /** 一个作业最多扫多少区块，防止超长线把内存吃光。 */
    private static final int MAX_CHUNKS = 20000;

    private final List<Job> jobs = new ArrayList<Job>();

    private OrphanRoadbedSweeper() {
    }

    /** 一次铺设的清扫作业。 */
    private static final class Job {
        int dim;
        long[] chunks;
        int cursor;
        int minY;
        int maxY;
        Set<Long> ourCores;
        EntityPlayerMP player;
        int removed;
        int scanned;
    }

    /**
     * 铺完一段之后排一次清扫。
     *
     * @param touched  本次动过的所有方块（用来圈出走廊范围）
     * @param ourCores 本次铺下的核心，永远豁免（它们万一有问题交给 BrokenCoreSweeper）
     */
    public void enqueue(World world, EntityPlayerMP player,
                        Collection<BlockPos> touched, Collection<BlockPos> ourCores) {
        if (world == null || world.isRemote || touched == null || touched.isEmpty()) {
            return;
        }
        int radius = TrackToolConfig.roadbedSweepRadius;
        if (radius <= 0) {
            return;                         // 配置里关掉了
        }
        int rc = (radius + 15) / 16;        // 走廊半径换成区块数
        Set<Long> seeds = new HashSet<Long>();
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (BlockPos p : touched) {
            seeds.add(chunkKey(p.getX() >> 4, p.getZ() >> 4));
            minY = Math.min(minY, p.getY());
            maxY = Math.max(maxY, p.getY());
        }
        Set<Long> corridor = new HashSet<Long>();
        for (Long seed : seeds) {
            int cx = (int) (seed.longValue() >> 32);
            int cz = (int) (seed.longValue() & 0xFFFFFFFFL);
            for (int dx = -rc; dx <= rc && corridor.size() < MAX_CHUNKS; dx++) {
                for (int dz = -rc; dz <= rc; dz++) {
                    corridor.add(chunkKey(cx + dx, cz + dz));
                }
            }
        }
        Job job = new Job();
        job.dim = world.provider.getDimension();
        job.chunks = new long[corridor.size()];
        int i = 0;
        for (Long k : corridor) {
            job.chunks[i++] = k.longValue();
        }
        job.minY = minY - radius;
        job.maxY = maxY + radius;
        job.player = player;
        job.ourCores = new HashSet<Long>();
        if (ourCores != null) {
            for (BlockPos p : ourCores) {
                job.ourCores.add(posKey(p));
            }
        }
        this.jobs.add(job);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || this.jobs.isEmpty()) {
            return;
        }
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            this.jobs.clear();
            return;
        }
        for (Iterator<Job> it = this.jobs.iterator(); it.hasNext(); ) {
            Job job = it.next();
            World world = server.getWorld(job.dim);
            if (world == null) {
                it.remove();
                continue;
            }
            int budget = CHUNKS_PER_TICK;
            while (budget-- > 0 && job.cursor < job.chunks.length) {
                long key = job.chunks[job.cursor++];
                try {
                    sweepChunk(world, job, (int) (key >> 32), (int) (key & 0xFFFFFFFFL));
                } catch (Throwable t) {
                    TrackToolCore.warn("roadbed sweep failed in chunk: %s", t.toString());
                }
            }
            if (job.cursor >= job.chunks.length) {
                report(job);
                it.remove();
            }
        }
    }

    private static void sweepChunk(World world, Job job, int cx, int cz) {
        // 只扫已加载的区块：为了扫地去加载几千个区块，代价远大于收益。
        Chunk chunk = world.getChunkProvider().getLoadedChunk(cx, cz);
        if (chunk == null) {
            return;
        }
        job.scanned++;
        ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
        int yLo = Math.max(0, job.minY);
        int yHi = Math.min(255, job.maxY);
        List<BlockPos> doomed = null;
        for (int sy = yLo >> 4; sy <= (yHi >> 4) && sy < sections.length; sy++) {
            ExtendedBlockStorage sec = sections[sy];
            if (sec == null || sec == Chunk.NULL_BLOCK_STORAGE || sec.isEmpty()) {
                continue;
            }
            for (int ly = 0; ly < 16; ly++) {
                int wy = (sy << 4) | ly;
                if (wy < yLo || wy > yHi) {
                    continue;
                }
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        IBlockState bs = sec.get(lx, ly, lz);
                        if (!(bs.getBlock() instanceof BlockLargeRailBase)) {
                            continue;
                        }
                        if (((BlockLargeRailBase) bs.getBlock()).isCore()) {
                            continue;       // 核心交给 BrokenCoreSweeper，这里只管底座
                        }
                        BlockPos p = new BlockPos((cx << 4) | lx, wy, (cz << 4) | lz);
                        if (job.ourCores.contains(posKey(p)) || !isOrphan(world, chunk, p)) {
                            continue;
                        }
                        if (doomed == null) {
                            doomed = new ArrayList<BlockPos>();
                        }
                        doomed.add(p);
                    }
                }
            }
        }
        if (doomed == null) {
            return;
        }
        // 收集完再改：上面读的是区块自己的 storage 数组，边读边 setBlockToAir 是在拆自己的脚下。
        for (BlockPos p : doomed) {
            if (!(world.getBlockState(p).getBlock() instanceof BlockLargeRailBase)) {
                continue;                   // 这一拍之间变了，不碰
            }
            // 先摘 TE 再清方块：BlockLargeRailBase.breakBlock 会 getCore() 之后
            // 对整条轨道调 RailMap.breakRail。TE 没了 getCore 就返回 null，
            // 这一步才保证只清掉眼前这一块，不会顺着链子拆别的东西。
            world.removeTileEntity(p);
            world.setBlockToAir(p);
            job.removed++;
        }
    }

    /**
     * 无主 = 这块底座背后没有活着的核心。三种情形：
     * <ol>
     *   <li><b>连 TE 都没有</b>——这种最狠：{@code getAABBWithState} 开头就是
     *       {@code if (getTileEntity(pos) == null) return FULL_BLOCK_AABB}，
     *       也就是<b>一整格实心碰撞箱</b>，列车碰上就是一堵墙；</li>
     *   <li>TE 的 {@code startPoint} 指不到核心（核心被拆了）；</li>
     *   <li>指到的是颗没有 {@code railPositions} 的空核心。</li>
     * </ol>
     * 后两种的碰撞箱只有 1/16 格高（{@code getBlockHeights} 找不到核心就回退到
     * {@code THICKNESS}），但它让在旧轨道那个 Y 上，跟新线的高程毫无关系，
     * 新线从它下方穿过时照样是个卡子。
     *
     * <p>用 {@code EnumCreateEntityType.CHECK} 而不是 {@code world.getTileEntity}：
     * 后者会<b>当场懒建一个 TE</b>，那就把情形 1 偷偷变成情形 2，也会把新 TE
     * 写进区块的 TE 表（又是一个 CME 源）。</p>
     */
    private static boolean isOrphan(World world, Chunk chunk, BlockPos p) {
        try {
            TileEntity te = chunk.getTileEntity(p, Chunk.EnumCreateEntityType.CHECK);
            if (!(te instanceof TileEntityLargeRailBase)) {
                return true;                // 情形 1：没 TE = 整格实心碰撞箱
            }
            TileEntityLargeRailBase base = (TileEntityLargeRailBase) te;
            int[] sp = base.getStartPoint();
            if (sp == null || sp.length < 3) {
                return true;
            }
            if (!world.isBlockLoaded(new BlockPos(sp[0], sp[1], sp[2]))) {
                return false;               // 核心区块没加载，判不了 ⇒ 一律放过
            }
            TileEntityLargeRailCore core = base.getRailCore();
            if (core == null || core.isInvalid()) {
                return true;                // 情形 2
            }
            RailPosition[] rps = core.getRailPositions();
            return rps == null || rps.length < 2 || rps[0] == null || rps[1] == null;   // 情形 3
        } catch (Throwable t) {
            return false;                   // 判不出来就别删
        }
    }

    private static void report(Job job) {
        if (job.removed <= 0) {
            return;
        }
        TrackToolCore.info("orphan roadbed sweep: removed %d blocks over %d loaded chunks",
                job.removed, job.scanned);
        if (job.player != null && !job.player.isDead && job.player.connection != null) {
            job.player.sendMessage(new TextComponentTranslation(
                    "tracktool.msg.roadbed_swept", String.valueOf(job.removed)));
        }
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static long posKey(BlockPos p) {
        return ((long) (p.getX() & 0x3FFFFFF) << 38)
                | ((long) (p.getZ() & 0x3FFFFFF) << 12)
                | (p.getY() & 0xFFF);
    }
}
