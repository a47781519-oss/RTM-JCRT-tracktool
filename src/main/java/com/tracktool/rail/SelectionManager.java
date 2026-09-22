package com.tracktool.rail;

import com.tracktool.TrackToolConfig;
import com.tracktool.TrackToolCore;
import com.tracktool.net.Packets;
import com.tracktool.rail.plan.PlanBuilder;
import com.tracktool.rail.plan.RailPlan;
import com.tracktool.util.Geo;
import jp.ngt.rtm.modelpack.ModelPackManager;
import jp.ngt.rtm.modelpack.ResourceType;
import jp.ngt.rtm.modelpack.modelset.ModelSetRail;
import jp.ngt.rtm.modelpack.modelset.ResourceSet;
import com.tracktool.rail2.ExactRailGeometry;
import jp.ngt.rtm.modelpack.state.ResourceStateRail;
import jp.ngt.rtm.modelpack.cfg.RailConfig;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-authoritative state of every player's laying session: which rail ends
 * are selected, what the GUI parameters are, the undo stack and the current
 * placement job.
 *
 * <p>The client never decides anything: it sends a look ray, parameters and
 * actions, and receives the authoritative session back. Session data is dropped
 * on logout, on dimension change and after a period of inactivity, as required
 * by the brief.</p>
 */
public final class SelectionManager {

    public static final SelectionManager INSTANCE = new SelectionManager();

    /** 新路径（整条线一个核心）的长度上限，超过就交回旧分段路径：
     *  一个核心 = 一个 GL 列表 + 一张方块表，太长会拖垮渲染与 setRail。 */
    public static final double EXACT_MAX_LENGTH = 2000.0D;

    public static final byte ACTION_CLEAR = 0;
    public static final byte ACTION_BACK = 1;
    public static final byte ACTION_CONFIRM = 2;
    public static final byte ACTION_UNDO = 3;
    public static final byte ACTION_SYNC = 4;
    public static final byte ACTION_DELETE_LAST_RAIL = 5;

    private final Map<UUID, Session> sessions = new HashMap<UUID, Session>();

    private SelectionManager() {
    }

    /** Per-player session. */
    public static final class Session {
        public int dim = Integer.MIN_VALUE;
        public final List<RailEnd> ends = new ArrayList<RailEnd>(2);
        public TrackSpec spec = new TrackSpec();
        public long lastTouch = System.currentTimeMillis();
        public boolean busy;
        public boolean creative = true;
        public int railBlocksPlaced;
        public RailPlacer.UndoRecord lastUndo;
        public final Deque<RailPlacer.UndoRecord> undoStack = new ArrayDeque<RailPlacer.UndoRecord>();
        public ResourceStateRail prop;
        public String lastError;
        public String lastMessage;
        public long packetWindow;
        public int packetCount;

        public RailEnd primary() {
            return this.ends.isEmpty() ? null : this.ends.get(0);
        }

        public RailEnd secondary() {
            return this.ends.size() < 2 ? null : this.ends.get(1);
        }
    }

    public Session get(EntityPlayer player) {
        Session s = this.sessions.get(player.getUniqueID());
        if (s == null) {
            s = new Session();
            s.dim = player.world.provider.getDimension();
            this.sessions.put(player.getUniqueID(), s);
        }
        return s;
    }

    public Session peek(EntityPlayer player) {
        return this.sessions.get(player.getUniqueID());
    }

    public void clear(UUID id) {
        this.sessions.remove(id);
    }

    public void clearAll() {
        this.sessions.clear();
    }

    // ------------------------------------------------------------------
    // events
    // ------------------------------------------------------------------

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || this.sessions.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Session>> it = this.sessions.entrySet().iterator();
        while (it.hasNext()) {
            Session s = it.next().getValue();
            if (!s.busy && now - s.lastTouch > TrackToolConfig.selectionTimeoutMs) {
                it.remove();
            }
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerLoggedOutEvent event) {
        this.clear(event.player.getUniqueID());
        PlacementQueue.INSTANCE.cancelFor(event.player.getUniqueID());
    }

    @SubscribeEvent
    public void onDimensionChange(net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerChangedDimensionEvent event) {
        this.clear(event.player.getUniqueID());
    }

    @SubscribeEvent
    public void onClone(PlayerEvent.Clone event) {
        this.clear(event.getEntityPlayer().getUniqueID());
    }

    // ------------------------------------------------------------------
    // permission & rate limiting
    // ------------------------------------------------------------------

    public static boolean isOperator(EntityPlayer player) {
        if (!(player instanceof EntityPlayerMP)) {
            return true;
        }
        EntityPlayerMP mp = (EntityPlayerMP) player;
        if (mp.server == null || mp.server.isSinglePlayer()) {
            return true;
        }
        try {
            return mp.server.getPlayerList().canSendCommands(mp.getGameProfile());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Anti-spam: at most {@link TrackToolConfig#packetRateLimit} packets per second. */
    public boolean allowPacket(Session s) {
        long now = System.currentTimeMillis();
        if (now - s.packetWindow > 1000L) {
            s.packetWindow = now;
            s.packetCount = 0;
        }
        s.packetCount++;
        return s.packetCount <= TrackToolConfig.packetRateLimit;
    }

    // ------------------------------------------------------------------
    // actions
    // ------------------------------------------------------------------

    /** Handles a look-ray pick request from the client; the server re-does the ray cast. */
    public void handleSelect(EntityPlayerMP player, double ex, double ey, double ez,
                            double dx, double dy, double dz, double maxDist) {
        Session s = this.get(player);
        s.lastTouch = System.currentTimeMillis();
        if (!this.allowPacket(s)) {
            return;
        }
        if (!isOperator(player)) {
            player.sendMessage(new TextComponentTranslation("tracktool.msg.no_permission"));
            this.sync(player, s);
            return;
        }
        if (s.busy) {
            player.sendMessage(new TextComponentTranslation("tracktool.msg.busy"));
            return;
        }
        double dist = Geo.clamp(maxDist, 1.0D, TrackToolConfig.selectDistance);
        RailRayTrace.Hit hit = RailRayTrace.pick(player.world, ex, ey, ez, dx, dy, dz, dist);
        TrackToolCore.info("select from %s eye=(%.2f,%.2f,%.2f) dir=(%.3f,%.3f,%.3f) found=%s middle=%s dist=%.2f",
                player.getName(), ex, ey, ez, dx, dy, dz, hit.found(), hit.middle, hit.distance);
        if (!hit.found()) {
            player.sendMessage(new TextComponentTranslation("tracktool.msg.no_rail"));
            this.sync(player, s);
            return;
        }
        if (!RailRayTrace.withinReach(player, hit.blockPos, 3.0D)) {
            player.sendMessage(new TextComponentTranslation("tracktool.msg.too_far"));
            this.sync(player, s);
            return;
        }
        if (hit.middle) {
            player.sendMessage(new TextComponentTranslation("tracktool.msg.middle"));
            this.sync(player, s);
            return;
        }
        RailEnd end = hit.end;
        // Same end clicked again: toggle it off.
        for (int i = 0; i < s.ends.size(); i++) {
            if (sameEnd(s.ends.get(i), end)) {
                s.ends.remove(i);
                player.sendMessage(new TextComponentTranslation("tracktool.msg.deselected"));
                this.sync(player, s);
                return;
            }
        }
        if (s.ends.size() >= 2) {
            // More than two selections: only the newest one survives.
            s.ends.clear();
            player.sendMessage(new TextComponentTranslation("tracktool.msg.reset_newest"));
        }
        s.ends.add(end);
        player.sendMessage(new TextComponentTranslation(s.ends.size() == 1
                ? "tracktool.msg.selected_one" : "tracktool.msg.selected_two"));
        this.sync(player, s);
    }

    private static boolean sameEnd(RailEnd a, RailEnd b) {
        return a.corePos.equals(b.corePos) && a.endIndex == b.endIndex;
    }

    public void handleParams(EntityPlayerMP player, TrackSpec spec) {
        Session s = this.get(player);
        s.lastTouch = System.currentTimeMillis();
        if (!this.allowPacket(s) || s.busy) {
            return;
        }
        s.spec = spec;
        this.sync(player, s);
    }

    public void handleAction(EntityPlayerMP player, byte action) {
        Session s = this.get(player);
        s.lastTouch = System.currentTimeMillis();
        if (!this.allowPacket(s)) {
            return;
        }
        switch (action) {
            case ACTION_CLEAR:
                s.ends.clear();
                s.lastMessage = "tracktool.msg.cleared";
                break;
            case ACTION_BACK:
                if (!s.ends.isEmpty()) {
                    s.ends.remove(s.ends.size() - 1);
                    s.lastMessage = "tracktool.msg.back_done";
                } else {
                    this.undo(player, s);
                    return;
                }
                break;
            case ACTION_CONFIRM:
                this.confirm(player, s);
                return;
            case ACTION_UNDO:
                this.undo(player, s);
                return;
            case ACTION_DELETE_LAST_RAIL:
                s.ends.clear();
                s.undoStack.clear();
                break;
            case ACTION_SYNC:
            default:
                break;
        }
        this.sync(player, s);
    }

    /** Builds the plan and hands it to the deferred placement queue. */
    public void confirm(EntityPlayerMP player, Session s) {
        if (!isOperator(player)) {
            player.sendMessage(new TextComponentTranslation("tracktool.msg.no_permission"));
            this.sync(player, s);
            return;
        }
        if (s.busy) {
            player.sendMessage(new TextComponentTranslation("tracktool.msg.busy"));
            return;
        }
        RailEnd from = s.primary();
        RailEnd to = s.secondary();
        if (from == null) {
            player.sendMessage(new TextComponentTranslation("tracktool.msg.need_end"));
            this.sync(player, s);
            return;
        }
        RailPlan plan = PlanBuilder.build(s.spec, from, to);
        // A 方案：开启时弯道走"整条一个核心 + 自定零量化几何"；失败自动落到下面的旧路径
        // ★ 连接模式的闭合自检：解出来的线是否真的落在第二个端点上（位置 + 朝向）
        if (s.spec.mode == TrackSpec.MODE_CONNECT && to != null && plan.ok && !plan.alignments.isEmpty()) {
            com.tracktool.rail.plan.Alignment al = plan.alignments.get(0);
            double[] o = new double[7];
            al.eval(al.length, o);
            double wantYaw = Geo.normalize360(to.outwardYaw + 180.0D);
            RailPlacer.TrackToolCoreHolder.warn(
                    "connect 闭合: 终点误差=%s m 朝向误差=%s°｜起点(%s,%s) yaw=%s ｜ 目标(%s,%s) yaw=%s ｜ 实际(%s,%s) yaw=%s",
                    String.format("%.3f", Math.hypot(o[0] - to.x, o[1] - to.z)),
                    String.format("%.3f", Geo.angleDiff(o[3], wantYaw)),
                    String.format("%.2f", from.x), String.format("%.2f", from.z),
                    String.format("%.2f", from.outwardYaw),
                    String.format("%.2f", to.x), String.format("%.2f", to.z), String.format("%.2f", wantYaw),
                    String.format("%.2f", o[0]), String.format("%.2f", o[1]), String.format("%.2f", o[3]));
        }
        RailPlacer.TrackToolCoreHolder.warn("exact 分流: enabled=%s planOk=%s noSecond=%s mode=%s lines=%s len=%s",
                String.valueOf(com.tracktool.rail2.ExactRailGate.enabled()), String.valueOf(plan.ok),
                String.valueOf(to == null), String.valueOf(s.spec.mode), String.valueOf(plan.alignments.size()),
                String.format("%.1f", plan.totalLength));
        // ★ 接管条件：弯道或直线、总长不超过 EXACT_MAX_LENGTH
        //   （整条线一个核心：太长会让单个 GL 列表与方块表过大，交回旧分段路径更稳）。
        //   多线平行：**每条线各铺一个核心**，各自用自己的 plan.alignments.get(li) 当几何
        //   （之前只接管单线，导致平行线全部走旧分段路径 ⇒ 半格量化 ⇒ 弯折）。
        //   连接模式同样接管：ConnectSolver 解出来的也是一条 Alignment，走同一套机制
        //   （端点现在按几何取 ⇒ 与目标轨道的真实端点严丝合缝）。
        boolean exactMode = s.spec.mode == TrackSpec.MODE_CURVE || s.spec.mode == TrackSpec.MODE_STRAIGHT
                || s.spec.mode == TrackSpec.MODE_CONNECT;
        if (com.tracktool.rail2.ExactRailGate.enabled() && plan.ok
                && exactMode && !plan.segments.isEmpty()
                && plan.totalLength <= EXACT_MAX_LENGTH) {
            ResourceStateRail propExact = this.resolveRailState(player, s.spec);
            if (propExact == null) {
                RailPlacer.TrackToolCoreHolder.warn("exact 结果: 取不到轨道包 ⇒ 回退旧路径");
            }
            RailPlacer.UndoRecord undoExact = new RailPlacer.UndoRecord();
            com.tracktool.rail2.ExactRailLayer.resetTableStats();
            int laidLines = 0;
            for (int li = 0; propExact != null && li < plan.alignments.size(); li++) {
                jp.ngt.rtm.rail.util.RailPosition ra = null;
                jp.ngt.rtm.rail.util.RailPosition rb = null;
                for (com.tracktool.rail.plan.PlanSegment seg : plan.segments) {
                    if (seg.lineIndex != li) {
                        continue;
                    }
                    if (ra == null) {
                        ra = seg.start;
                    }
                    rb = seg.end;
                }
                if (ra == null || rb == null) {
                    continue;
                }
                if (this.placeExactLine(player, s, plan, li, ra, rb, propExact, undoExact)) {
                    laidLines++;
                }
            }
            if (laidLines > 0) {
                this.pushUndo(s, undoExact);
                RailPlacer.TrackToolCoreHolder.warn("exact 结果: ★新几何生效（%s 条线，共 %s 个核心，快照 %s 格）",
                        String.valueOf(laidLines), String.valueOf(undoExact.cores.size()),
                        String.valueOf(undoExact.size()));
                player.sendMessage(new TextComponentTranslation("tracktool.msg.placed",
                        String.valueOf(undoExact.cores.size()), String.valueOf(undoExact.size())));
                // ★ 路基没铺上的格子要当场说出来：服务器上被保护插件/权限拦掉 setBlock 时，
                //   表现是"钢轨悬空、下面没有道砟"，不报就只能靠猜（第 64 轮服务器实测）。
                if (com.tracktool.rail2.ExactRailLayer.lastDropped > 0) {
                    player.sendMessage(new net.minecraft.util.text.TextComponentString(
                            net.minecraft.util.text.TextFormatting.RED + String.format(
                                    "[track-tool] 有 %d 格路基没能铺进世界（钢轨会悬空）。"
                                            + "服务器上多半是保护插件/权限拦下了放置，请检查该区域的建筑权限。",
                                    com.tracktool.rail2.ExactRailLayer.lastDropped)));
                }
                s.ends.clear();
                this.sync(player, s);
                return;
            }
        }
        if (com.tracktool.rail2.ExactRailGate.enabled() && plan.ok && exactMode) {
            RailPlacer.TrackToolCoreHolder.warn("exact 结果: 未走新路径 ⇒ 旧分段路径（lines=%s len=%s 上限=%s）",
                    String.valueOf(plan.alignments.size()), String.format("%.1f", plan.totalLength),
                    String.valueOf(EXACT_MAX_LENGTH));
        }
        if (!plan.ok) {
            player.sendMessage(new TextComponentTranslation(plan.errorKey,
                    plan.errorArg == null ? new Object[0] : new Object[]{plan.errorArg}));
            this.sync(player, s);
            return;
        }
        ResourceStateRail prop = this.resolveRailState(player, s.spec);
        if (prop == null) {
            player.sendMessage(new TextComponentTranslation("tracktool.msg.no_rail_pack"));
            this.sync(player, s);
            return;
        }
        boolean creative = player.isCreative();
        s.creative = creative;
        String err = RailPlacer.precheck(player.world, view(plan), prop, creative, !creative);
        if (err != null) {
            player.sendMessage(new TextComponentTranslation(err));
            this.sync(player, s);
            return;
        }
        s.busy = true;
        PlacementQueue.INSTANCE.submit(new PlacementQueue.Task(player.getUniqueID(), player, s, plan, prop, creative));
        s.lastMessage = "tracktool.msg.queued";
        this.sync(player, s);
    }

    private static List<RailPlacer.PlanSegmentView> view(RailPlan plan) {
        List<RailPlacer.PlanSegmentView> list = new ArrayList<RailPlacer.PlanSegmentView>(plan.segments.size());
        for (com.tracktool.rail.plan.PlanSegment seg : plan.segments) {
            list.add(new RailPlacer.PlanSegmentView(seg.start, seg.end));
        }
        return list;
    }

    public void undo(EntityPlayerMP player, Session s) {
        if (s.busy) {
            player.sendMessage(new TextComponentTranslation("tracktool.msg.busy"));
            return;
        }
        RailPlacer.UndoRecord rec = s.undoStack.pollLast();
        if (rec == null) {
            player.sendMessage(new TextComponentTranslation("tracktool.msg.nothing_to_undo"));
            this.sync(player, s);
            return;
        }
        RailPlacer.restore(player.world, rec);
        player.sendMessage(new TextComponentTranslation("tracktool.msg.undone",
                String.valueOf(rec.positions.size())));
        this.sync(player, s);
    }

    /** Pushes a finished job onto the undo stack. */
    public void pushUndo(Session s, RailPlacer.UndoRecord rec) {
        s.lastUndo = rec;
        s.undoStack.addLast(rec);
        while (s.undoStack.size() > TrackToolConfig.maxUndo) {
            s.undoStack.pollFirst();
        }
    }

    /**
     * Resolves the rail model pack and ballast the player asked for, falling
     * back to the selected staff's own state (which is how the staff carries
     * the chosen rail type) and then to RTM's default.
     */
    /**
     * 用新路径铺【一条线】：整条线一个核心 + 与预览同源的几何（{@link com.tracktool.rail2.PlanGeometry}）。
     *
     * <p>多线平行时按线循环调用：第 li 条线用 {@code plan.alignments.get(li)} 当几何，
     * 端点取该线首段的 start 与末段的 end。撤回记录累加进同一个 {@code undo}。</p>
     *
     * @return true 表示这条线铺成功
     */
    private boolean placeExactLine(EntityPlayerMP player, Session s, RailPlan plan, int li,
                                   jp.ngt.rtm.rail.util.RailPosition ra,
                                   jp.ngt.rtm.rail.util.RailPosition rb,
                                   ResourceStateRail prop, RailPlacer.UndoRecord undo) {
        double[] prm;
        if (s.spec.mode == TrackSpec.MODE_CURVE) {
            // ★ 缓和曲线长必须与预览（PlanBuilder）完全一致：
            //   autoTransition ⇒ GB50090 表 easementLength(160, r)；否则用玩家值；再按 maxSpiral 夹紧
            double lsExact = s.spec.autoTransition
                    ? RailStandards.easementLength(160, (int) s.spec.radiusM)
                    : s.spec.transitionLength;
            double maxSpiralExact = Math.toRadians(s.spec.angleDeg) * s.spec.radiusM * 0.9D * 0.5D;
            lsExact = Math.max(0.5D, Math.min(lsExact, maxSpiralExact));
            prm = new double[]{
                s.spec.radiusM, s.spec.angleDeg, lsExact,
                Math.toRadians(s.spec.signedCant()) * 1000.0D,
                s.spec.riseM, s.spec.verticalRadius, s.spec.curvatureSign(),
                ra.anchorYaw, ra.posX, ra.posY, ra.posZ
            };
        } else {
            // 直线：参数串只作"本模组轨道"的标记与兜底，真正的几何走 PlanGeometry
            double rStraight = 1.0E6D;
            prm = new double[]{
                rStraight, Math.toDegrees(Math.max(1.0E-6D, plan.totalLength / rStraight)), 0.5D, 0.0D,
                s.spec.riseM, s.spec.verticalRadius, 1.0D,
                ra.anchorYaw, ra.posX, ra.posY, ra.posZ
            };
        }
        // ★ 几何：中心线直接用 plan 的基准线；平行线用【精确等距几何】
        //   （plan.alignments.get(li) 是"逐元素换算"的近似，缓和曲线段每 4 m 间距会偏 0.1 m，
        //    表现为接着铺时只有中心线对得上 —— 第 50 轮离线实测）。
        double[] offsets = s.spec.parallelOffsets();
        double off = li >= 0 && li < offsets.length ? offsets[li] : 0.0D;
        ExactRailGeometry geoPlan = Math.abs(off) < 1.0E-9D
                ? new com.tracktool.rail2.PlanGeometry(plan.alignments.get(0), ra)
                : new com.tracktool.rail2.OffsetGeometry(plan.alignments.get(0), off, ra);
        if (!com.tracktool.rail2.ExactRailGate.place(player.world, ra, rb, prop, prm, undo, geoPlan)) {
            RailPlacer.TrackToolCoreHolder.warn("exact 第 %s 条线铺设失败", String.valueOf(li));
            return false;
        }
        // ★ 客户端拿【采样点表 + 服务端真实方块表】：与服务端逐点同源，路基不会画错。
        //   线路会按 20 m 切成多个核心（RTM 按核心渲染），所以要逐个核心发包。
        java.util.List<com.tracktool.rail2.ExactRailLayer.Placed> placed =
                com.tracktool.rail2.ExactRailGate.lastPlaced;
        String args = com.tracktool.rail2.ExactRailGeometryCodec.encode(prm);
        int pts = 0;
        int cells = 0;
        for (com.tracktool.rail2.ExactRailLayer.Placed p : placed) {
            if (p.corePos == null || p.samples == null) {
                continue;
            }
            com.tracktool.net.Packets.sendTo(player,
                    new com.tracktool.net.Packets.ExactRail(
                            p.corePos.getX(), p.corePos.getY(), p.corePos.getZ(),
                            args, p.samples, p.blocks));
            pts += p.samples.pointCount();
            cells += p.blocks == null ? 0 : p.blocks.length;
        }
        RailPlacer.TrackToolCoreHolder.warn("exact 同步: 线%s 共 %s 个核心，采样点 %s 个，方块表 %s 格",
                String.valueOf(li), String.valueOf(placed.size()),
                String.valueOf(pts), String.valueOf(cells));
        return true;
    }

    public ResourceStateRail resolveRailState(EntityPlayerMP player, TrackSpec spec) {
        try {
            ResourceType type = jp.ngt.rtm.RTMResource.RAIL;
            ResourceStateRail state = new ResourceStateRail(type, null);
            String name = spec.railResource;
            if (name == null || name.length() == 0) {
                ItemStack held = player.getHeldItemMainhand();
                name = com.tracktool.item.ItemRailStaff.getRailResource(held);
            }
            if (name == null || name.length() == 0) {
                state.setResourceToDefault();
            } else {
                ResourceSet set = ModelPackManager.INSTANCE.getResourceSet(type, name);
                if (set == null || set.isDummy()) {
                    return null;
                }
                state.setResourceName(name);
            }
            ResourceSet set = state.getResourceSet();
            if (set == null || set.isDummy()) {
                return null;
            }
            RailConfig cfg = (RailConfig) ((ModelSetRail) set).getConfig();
            if (spec.ballastBlock != null && spec.ballastBlock.length() > 0) {
                net.minecraft.block.Block b = net.minecraft.block.Block.getBlockFromName(spec.ballastBlock);
                if (b != null) {
                    state.setBlock(b, spec.ballastMeta);
                    state.setHeight(spec.ballastHeight);
                }
            } else if (cfg.defaultBallast != null && cfg.defaultBallast.length > 0) {
                RailConfig.BallastSet bs = cfg.defaultBallast[0];
                net.minecraft.block.Block b = net.minecraft.block.Block.getBlockFromName(bs.blockName);
                if (b == null) {
                    b = net.minecraft.init.Blocks.COBBLESTONE;
                }
                state.setBlock(b, bs.blockMetadata);
                state.setHeight(bs.height <= 0.0F ? 0.0625F : bs.height);
            }
            return state;
        } catch (Throwable t) {
            TrackToolCore.warn("resolveRailState failed: %s", t.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // sync
    // ------------------------------------------------------------------

    public void sync(EntityPlayerMP player, Session s) {
        this.sync(player, s, false);
    }

    /** Sends the authoritative session; {@code openGui} also opens the panel client side. */
    public void sync(EntityPlayerMP player, Session s, boolean openGui) {
        Packets.State pkt = new Packets.State();
        pkt.openGui = openGui;
        pkt.dim = s.dim;
        pkt.spec = s.spec;
        pkt.busy = s.busy;
        pkt.operator = isOperator(player);
        pkt.undoDepth = s.undoStack.size();
        pkt.lastError = s.lastError;
        pkt.lastMessage = s.lastMessage;
        pkt.ends = new ArrayList<RailEnd>(s.ends);
        if (!s.ends.isEmpty()) {
            RailPlan plan = PlanBuilder.build(s.spec, s.primary(), s.secondary());
            // Rebuild every segment through RTM's own RailMapBasic and measure
            // the deviation: the number shown in the GUI is measured, not assumed.
            PlanBuilder.verifySegments(plan);
            pkt.planOk = plan.ok;
            pkt.planError = plan.errorKey;
            pkt.totalLength = plan.totalLength;
            pkt.segmentCount = plan.segmentCount();
            pkt.maxDeviation = plan.maxDeviation;
            pkt.maxCant = plan.alignments.isEmpty() ? 0.0D : plan.alignments.get(0).cant.maxAbs();
            pkt.elements = textOf(plan);
            if (plan.warnDetour) {
                // 连接模式：两端"背对背"时，切线连续的连接必然绕一大圈，不是 bug —— 明确告诉玩家
                pkt.lastMessage = String.format("两端朝向导致必须绕行：连接长度是直线距离的 %.1f 倍，"
                        + "换选对面那一端可能更短", plan.detourRatio);
            }
        }
        s.lastMessage = null;
        Packets.sendTo(player, pkt);
    }

    private static List<String> textOf(RailPlan plan) {
        List<String> out = new ArrayList<String>();
        for (com.tracktool.rail.plan.Alignment.ElementInfo e : plan.elements) {
            String key = e.typeKey;
            String extra = "";
            if (Math.abs(e.radius) > 0.5D) {
                extra = String.format("R=%.0f", e.radius);
            }
            out.add(String.format("%s|%.1f|%.1f|%s", key, e.length, e.turnDeg, extra));
        }
        return out;
    }

    /** Pushes fresh state to a client whose GUI needs rebuilding. */
    public void requestSync(EntityPlayerMP player) {
        Session s = this.get(player);
        this.sync(player, s);
    }
}
