package com.tracktool.rail;

import com.tracktool.TrackToolConfig;
import com.tracktool.TrackToolCore;
import com.tracktool.util.Geo;
import jp.ngt.rtm.RTMBlock;
import jp.ngt.rtm.rail.BlockLargeRailBase;
import jp.ngt.rtm.rail.TileEntityLargeRailCore;
import jp.ngt.rtm.rail.util.RailMap;
import jp.ngt.rtm.rail.util.RailPosition;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Client-independent ray cast used to pick a rail end.
 *
 * <p>RTM rail blocks are one sixteenth of a block tall, so a plain block ray
 * trace is unusable - the player would have to aim at a sliver. Instead the
 * look ray is marched in small steps and every rail block it passes through is
 * tested against its real collision box (grown slightly for forgiving aim).</p>
 */
public final class RailRayTrace {

    private static final double STEP = 0.05D;
    /**
     * Aim tolerance. RTM rails are only 1/16 of a block tall and their real
     * collision box is unusable, so the pick box is deliberately generous:
     * missing a rail because the crosshair was 10 cm high would be far worse
     * than occasionally selecting a rail the player was obviously pointing at.
     */
    private static final double AIM_TOLERANCE = 0.3D;

    private RailRayTrace() {
    }

    /** Result of a pick attempt. */
    public static final class Hit {
        public RailEnd end;
        /** True when the rail was hit but too far from either end. */
        public boolean middle;
        public BlockPos blockPos = BlockPos.ORIGIN;
        public double distance;
        public double overlap;

        public boolean found() {
            return this.end != null;
        }
    }

    public static Hit pick(World world, double ex, double ey, double ez,
                           double dx, double dy, double dz, double maxDist) {
        Hit hit = new Hit();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-6D) {
            return hit;
        }
        dx /= len;
        dy /= len;
        dz /= len;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        // Per-block cache: the expensive part (block state + owning core) is done once
        // per block, but the box test must run on EVERY step. Testing only the first
        // step inside a block made steep rays impossible: looking straight down, the
        // first sample inside the rail's block is near its TOP face (y ≈ 4.95), far
        // above the 1/16-tall rail box, so the rail could never be picked.
        BlockPos cachedPos = null;
        AxisAlignedBB cachedBox = null;
        for (double t = 0.0D; t <= maxDist; t += STEP) {
            double px = ex + dx * t;
            double py = ey + dy * t;
            double pz = ez + dz * t;
            mp.setPos((int) Math.floor(px), (int) Math.floor(py), (int) Math.floor(pz));
            if (!world.isBlockLoaded(mp)) {
                cachedPos = null;
                cachedBox = null;
                continue;
            }
            if (cachedPos == null || !cachedPos.equals(mp)) {
                cachedPos = mp.toImmutable();
                IBlockState state = world.getBlockState(cachedPos);
                if (state.getBlock() instanceof BlockLargeRailBase) {
                    // Do NOT ask the block for its collision box: BlockLargeRailBase
                    // computes it at BlockPos.ORIGIN, which yields a degenerate box, so
                    // neither Minecraft's ray trace nor anything else can ever hit a
                    // rail block that way. Build the box from the rail's own height.
                    AxisAlignedBB box = railBox(world, cachedPos, state);
                    cachedBox = box == null ? null : box.grow(AIM_TOLERANCE);
                } else {
                    cachedBox = null;
                }
            }
            if (cachedBox == null) {
                continue;
            }
            if (px < cachedBox.minX || px > cachedBox.maxX || py < cachedBox.minY || py > cachedBox.maxY
                    || pz < cachedBox.minZ || pz > cachedBox.maxZ) {
                continue;
            }
            TileEntityLargeRailCore core = BlockLargeRailBase.getCore(world, mp);
            if (core == null || !core.isLoaded()) {
                continue;
            }
            hit.blockPos = mp.toImmutable();
            hit.distance = t;
            return resolve(world, core, px, py, pz, hit);
        }
        return hit;
    }

    /**
     * Hit box of a rail block, derived from the ballast height of the rail core
     * that owns it (with a floor so a thin rail is still clickable).
     */
    private static AxisAlignedBB railBox(net.minecraft.world.IBlockAccess world, BlockPos pos, IBlockState state) {
        double h = 0.3D;
        try {
            TileEntityLargeRailCore core = BlockLargeRailBase.getCore(world, pos);
            if (core != null && core.getResourceState() != null) {
                h = Math.max(0.3D, core.getResourceState().blockHeight + 0.08D);
            }
        } catch (Throwable ignored) {
            // fall back to the default thickness
        }
        return new AxisAlignedBB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0D, pos.getY() + h, pos.getZ() + 1.0D);
    }

    /** Decides which end of the hit rail the player aimed at. */
    public static Hit resolve(World world, TileEntityLargeRailCore core, double px, double py, double pz, Hit hit) {
        RailPosition[] rps = core.getRailPositions();
        if (rps == null || rps.length < 2) {
            return hit;
        }
        RailPosition a = rps[0];
        RailPosition b = rps[1];

        double fraction = -1.0D;
        RailMap rm = core.getRailMap(null);
        if (rm != null && rm.getLength() > 0.5D) {
            int split = 256;
            int idx = rm.getNearlestPoint(split, px, pz);
            if (idx >= 0) {
                fraction = (double) idx / split;
            }
        }
        if (fraction < 0.0D) {
            double la = Geo.dist2(px, pz, a.posX, a.posZ) + (py - a.posY) * (py - a.posY);
            double lb = Geo.dist2(px, pz, b.posX, b.posZ) + (py - b.posY) * (py - b.posY);
            fraction = la <= lb ? 0.0D : 1.0D;
        }
        if (fraction > 0.25D && fraction < 0.75D && Math.abs(fraction - 0.5D) < TrackToolConfig.middleDeadZone) {
            hit.middle = true;
            return hit;
        }
        int endIndex = fraction < 0.5D ? 0 : 1;
        RailPosition rp = endIndex == 0 ? a : b;
        RailEnd end = new RailEnd();
        end.dim = world.provider.getDimension();
        end.corePos = core.getPos();
        end.endIndex = endIndex;
        end.rp = PlanSegmentCopy.copyOf(rp);
        end.shapeName = safeShapeName(core);
        end.derive();
        // ★ 新路径铺的轨道：几何由解析线形决定，【不经过 RailPosition】
        //   （RP 只能落在"半格网格 × 8 方向"上，是旧分段路径的承载点，最多差 ~0.35 m）。
        //   端点必须按几何取，否则：① 黄色端点框画偏；② 从这个端点接着铺时，
        //   新线的起点会落在 RP 上而不是上一条轨道真正的端点上 ⇒ 接头错位。
        try {
            if (rm != null && com.tracktool.rail2.ExactRailInjector.isExactMap(rm)) {
                int split = 256;
                int idx = endIndex == 0 ? 0 : split;
                double[] gp = rm.getRailPos(split, idx);
                end.x = gp[1];
                end.z = gp[0];
                end.y = designHeight(rm, split, idx);
                double gYaw = rm.getRailYaw(split, idx);
                double gPitch = rm.getRailPitch(split, idx);
                end.outwardYaw = Geo.normalize360(endIndex == 0 ? gYaw + 180.0D : gYaw);
                end.outwardPitch = endIndex == 0 ? -gPitch : gPitch;
            }
        } catch (Throwable ignored) {
            // 取不到就沿用 RailPosition 的值
        }
        hit.end = end;
        return hit;
    }

    /**
     * 端点的<b>设计轨面高</b>（竖曲线上的高度），<b>不含 RTM 的超高抬升</b>。
     *
     * <p>RTM 源码 {@code RailMapBasic.getRailHeight}：</p>
     * <pre>
     *   height = lineVertical.getPoint(split,index)[1];
     *   if (cant != 0) height += |NGTMath.sin(cant)| * 3.0f * 0.5f;   // 超高抬升，railWidth=3
     * </pre>
     * <p>也就是说 {@code getRailHeight} 返回的是"设计高 + 超高抬升"。直接拿它当新线路的
     * 起点高度，等于把抬升量算进了设计高；渲染时 RTM 还会再加一次 ⇒ 接头处凭空高出
     * {@code |sin(超高)|·1.5} 米（超高 10° 时 0.26 m）。第 58 轮用户实测"打掉一节再用连接模式
     * 铺，对不上"就是这个：存档里连接段的轨面 Y=4.30~4.32，两侧邻段都是 4.06。</p>
     *
     * <p>所以这里把抬升量减回去，取回真正的设计高。用 RTM 自己的 {@code NGTMath.sin}
     * （入参是<b>度</b>）保证与它加上去的那一份完全抵消。</p>
     */
    public static double designHeight(jp.ngt.rtm.rail.util.RailMap rm, int split, int idx) {
        double h = rm.getRailHeight(split, idx);
        float cant = rm.getRailRoll(split, idx);
        if (cant != 0.0F) {
            h -= Math.abs(jp.ngt.ngtlib.math.NGTMath.sin(cant) * 3.0F * 0.5F);
        }
        return h;
    }

    private static String safeShapeName(TileEntityLargeRailCore core) {
        try {
            String s = core.getRailShapeName();
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    /** True when the entity may reach this block. */
    public static boolean withinReach(EntityPlayer player, BlockPos pos, double extra) {
        double d = player.getDistanceSq(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        double max = TrackToolConfig.selectDistance + extra;
        return d <= max * max;
    }

    /** True for blocks that RTM considers replaceable when laying rail. */
    public static boolean isMarker(IBlockState state) {
        return state.getBlock() == RTMBlock.marker || state.getBlock() == RTMBlock.markerSwitch;
    }

    static final class PlanSegmentCopy {
        static RailPosition copyOf(RailPosition rp) {
            return com.tracktool.rail.plan.PlanSegment.copyOf(rp);
        }
    }
}
