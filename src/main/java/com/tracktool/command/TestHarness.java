package com.tracktool.command;

import com.tracktool.rail.RailGrid;
import com.tracktool.rail.RailPlacer;
import com.tracktool.rail.SelectionManager;
import com.tracktool.rail.TrackSpec;
import com.tracktool.rail.plan.PlanBuilder;
import com.tracktool.rail.plan.RailPlan;
import com.tracktool.util.Geo;
import jp.ngt.rtm.RTMRail;
import com.tracktool.rail.CustomRailSpike;
import jp.ngt.rtm.modelpack.state.ResourceStateRail;
import jp.ngt.rtm.rail.BlockLargeRailBase;
import jp.ngt.rtm.rail.TileEntityLargeRailCore;
import jp.ngt.rtm.rail.util.RailPosition;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Test harness used during development and acceptance testing:
 * builds a reference RTM rail (identical to what two markers would produce),
 * dumps the NBT that RTM really stores, and reports engine self-checks.
 */
public final class TestHarness {

    private TestHarness() {
    }

    /**
     * {@code /tracktool test joints [半径]} —— 把附近所有轨道核心的<b>端点</b>两两配对，
     * 报告"平面重合但高度对不上"的接头。
     *
     * <p>为什么需要它：接头对不对齐用肉眼看很难判，而且一旦某一段被铺错了高度，
     * 从它接着往下铺会把错误一路传下去（第 58 轮实测：一条第 57 轮遗留的旧连接段
     * 让后面连铺三次都差 0.2333 m）。这里直接按几何算，谁错一目了然。</p>
     *
     * <p>比的是 <b>{@code getRailHeight}（渲染高）</b>，也就是玩家真正看到的轨面：
     * 它等于"设计高 + {@code |sin(超高)|·1.5}"。第 59 轮吃过亏 —— 只比设计高时本命令报
     * "0 处对不上"，可现场钢轨仍然不齐，因为差的是<b>超高</b>：连接段中间超高掉到 0，
     * 轨面跟着下沉 0.26 m。所以这里把超高一并报出来（取绝对值：接头两侧行车方向可能相反，
     * 超高符号本就该相反，而抬升量用的是 {@code |sin|}）。</p>
     */
    public static void joints(EntityPlayerMP player, String[] args) {
        double radius = 64.0D;
        if (args.length > 2) {
            try {
                radius = Math.max(4.0D, Math.min(512.0D, Double.parseDouble(args[2])));
            } catch (NumberFormatException ignored) {
                // 用默认值
            }
        }
        // {x, 渲染高, z, coreX, coreY, coreZ, endIndex, |超高|, 设计高}
        List<double[]> ends = new ArrayList<double[]>();
        int twisted = 0;
        double r2 = radius * radius;
        for (Object o : player.world.loadedTileEntityList) {
            if (!(o instanceof TileEntityLargeRailCore)) {
                continue;
            }
            TileEntityLargeRailCore core = (TileEntityLargeRailCore) o;
            if (core.getPos().distanceSq(player.posX, player.posY, player.posZ) > r2) {
                continue;
            }
            try {
                jp.ngt.rtm.rail.util.RailMap rm = core.getRailMap(null);
                if (rm == null) {
                    continue;
                }
                for (int e = 0; e < 2; e++) {
                    int idx = e == 0 ? 0 : 256;
                    double[] p = rm.getRailPos(256, idx);
                    ends.add(new double[]{p[1], rm.getRailHeight(256, idx), p[0],
                            core.getPos().getX(), core.getPos().getY(), core.getPos().getZ(), e,
                            Math.abs(rm.getRailRoll(256, idx)),
                            com.tracktool.rail.RailRayTrace.designHeight(rm, 256, idx)});
                }
                // ★ 段内超高塌陷/鼓包：中点的 |超高| 跑到两端之外就是"19 m 内先扭平再扭回去"，
                //   接头本身量不出来（两端是对的），但现场看就是钢轨不齐 —— 第 59 轮的真凶。
                double ca = Math.abs(rm.getRailRoll(256, 0));
                double cm = Math.abs(rm.getRailRoll(256, 128));
                double cb = Math.abs(rm.getRailRoll(256, 256));
                double lo = Math.min(ca, cb);
                double hi = Math.max(ca, cb);
                if (cm < lo - 0.5D || cm > hi + 0.5D) {
                    twisted++;
                    player.sendMessage(new TextComponentString(TextFormatting.RED + String.format(
                            "[joints] 核心(%d,%d,%d) 段内超高异常：首 %.2f° 中 %.2f° 末 %.2f°（中点跑出两端之外，轨面会先沉后升 %.3f m）",
                            core.getPos().getX(), core.getPos().getY(), core.getPos().getZ(),
                            ca, cm, cb,
                            Math.abs(Math.sin(Math.toRadians(cm)) - Math.sin(Math.toRadians(lo))) * 1.5D)));
                }
            } catch (Throwable ignored) {
                // 拿不到几何的核心跳过
            }
        }
        int pairs = 0;
        int bad = 0;
        double worst = 0.0D;
        for (int i = 0; i < ends.size(); i++) {
            for (int j = i + 1; j < ends.size(); j++) {
                double[] a = ends.get(i);
                double[] b = ends.get(j);
                if (a[3] == b[3] && a[4] == b[4] && a[5] == b[5]) {
                    continue;                           // 同一个核心的两端
                }
                double d = Math.hypot(a[0] - b[0], a[2] - b[2]);
                if (d > 0.5D) {
                    continue;                           // 不是同一个接头
                }
                pairs++;
                double dy = a[1] - b[1];                     // 渲染高之差 —— 玩家看到的那一个
                double dc = a[7] - b[7];                     // |超高| 之差
                if (Math.abs(dy) > 0.02D || Math.abs(dc) > 0.2D) {
                    bad++;
                    if (Math.abs(dy) > Math.abs(worst)) {
                        worst = dy;
                    }
                    player.sendMessage(new TextComponentString(TextFormatting.RED + String.format(
                            "[joints] (%.2f,%.2f) 渲染高差 %+.4f m（设计高差 %+.4f，超高 %.2f° vs %.2f°）："
                                    + "核心(%d,%d,%d)端%d ↔ 核心(%d,%d,%d)端%d",
                            a[0], a[2], dy, a[8] - b[8], a[7], b[7],
                            (int) a[3], (int) a[4], (int) a[5], (int) a[6],
                            (int) b[3], (int) b[4], (int) b[5], (int) b[6])));
                }
            }
        }
        player.sendMessage(new TextComponentString((bad == 0 && twisted == 0 ? TextFormatting.GREEN : TextFormatting.RED)
                + String.format("[joints] 半径 %.0f m：端点 %d 个，接头 %d 处，接头对不上的 %d 处%s，段内超高异常的 %d 段",
                radius, ends.size(), pairs, bad,
                bad == 0 ? "" : String.format("（最大渲染高差 %+.4f m）", worst), twisted)));
    }

    /**
     * Server-side selection using the player's own eye position and look
     * direction - the exact code path a right click triggers, without depending
     * on the client being able to inject a real mouse click.
     */
    public static void select(EntityPlayerMP player, SelectionManager.Session session) {
        double ex = player.posX;
        double ey = player.posY + player.getEyeHeight();
        double ez = player.posZ;
        float yawRad = (float) Math.toRadians(-player.rotationYaw);
        float pitchRad = (float) Math.toRadians(player.rotationPitch);
        double dx = Math.sin(yawRad) * Math.cos(pitchRad);
        double dy = -Math.sin(pitchRad);
        double dz = Math.cos(yawRad) * Math.cos(pitchRad);
        SelectionManager.INSTANCE.handleSelect(player, ex, ey, ez, dx, dy, dz,
                com.tracktool.TrackToolConfig.selectDistance);
    }

    /**
     * {@code /tracktool test selectat <x> <y> <z>} —— 按坐标选端点（自动化测试用）。
     *
     * <p>{@link #select} 依赖玩家视角，而 {@code /tp} 设的俯仰角会被客户端自己的相机覆盖，
     * 脚本化测试里不可靠；这里直接从目标格上方垂直向下投一条射线，等价于"站在那一格上往下点"。
     * 仍然走 {@code handleSelect} 的全部校验（含 withinReach），所以玩家要站在附近。</p>
     */
    public static void selectAt(EntityPlayerMP player, String[] args) throws CommandException {
        if (args.length < 5) {
            throw new CommandException("usage: /tracktool test selectat <x> <y> <z>");
        }
        double x = parse(args[2]);
        double y = parse(args[3]);
        double z = parse(args[4]);
        SelectionManager.INSTANCE.handleSelect(player,
                Math.floor(x) + 0.5D, y + 3.0D, Math.floor(z) + 0.5D,
                0.0D, -1.0D, 0.0D, 8.0D);
    }

    /** Lays one plain RTM rail core in front of the player, like two markers would. */
    /** /tracktool test custom <R> <angle> —— 脚本轨道图铺整条弯道一个核心（跳出 0.5m 锚点网格）。 */
    public static void custom(EntityPlayerMP player, SelectionManager.Session session, String[] args)
            throws CommandException {
        double radius = 300.0D;
        double angle = 45.0D;
        try {
            if (args.length > 2) {
                radius = Double.parseDouble(args[2]);
            }
            if (args.length > 3) {
                angle = Double.parseDouble(args[3]);
            }
        } catch (Throwable ignored) {
        }
        player.sendMessage(new TextComponentString("\u00a7e[spike] R=" + radius + " angle=" + angle));
        ResourceStateRail prop = SelectionManager.INSTANCE.resolveRailState(player, session.spec);
        if (prop == null) {
            throw new CommandException("tracktool.err.no_rail");
        }
        BlockPos p = player.getPosition();
        double yaw = -player.rotationYaw;
        double x = p.getX() + 0.5D;
        double z = p.getZ() + 0.5D;
        double y = p.getY();
        RailPosition a = RailGrid.make(x, z, y, yaw, 0.0D, 0);
        double ls = 30.0D;
        double len = 2.0D * ls + radius * Math.toRadians(angle) - ls;
        double ex = x + Math.sin(Math.toRadians(yaw)) * len;
        double ez = z + Math.cos(Math.toRadians(yaw)) * len;
        RailPosition b = RailGrid.make(ex, ez, y, yaw, 0.0D, 0);
        boolean ok = CustomRailSpike.place(player.world, a, b, prop,
                "len0=10.0,len1=10.0,r=40.0,yaw=90.0,cant=15.0,endH=0.0");
        player.sendMessage(new TextComponentString(ok
                ? "\u00a7a[spike] 自定义轨道图已创建，详情见日志"
                : "\u00a7c[spike] 创建失败，原因见日志"));
    }
    public static void base(EntityPlayerMP player, SelectionManager.Session session, String[] args)
            throws CommandException {
        double len = 20.0D;
        double turnDeg = 0.0D;
        if (args.length > 2) {
            len = parse(args[2]);
        }
        if (args.length > 3) {
            turnDeg = parse(args[3]);
        }
        ResourceStateRail prop = SelectionManager.INSTANCE.resolveRailState(player, session.spec);
        if (prop == null) {
            throw new CommandException("tracktool.msg.no_rail_pack");
        }
        double yaw = Geo.normalize360(-player.rotationYaw);
        double x = Math.floor(player.posX) + 0.5D;
        double z = Math.floor(player.posZ) + 0.5D;
        double y = Math.floor(player.posY) + 0.0625D * 2;
        if (Math.abs(z - Math.rint(z)) < 1.0E-6D) {
            z += 0.5D;
        }
        // One core, straight or a single constant-radius arc.
        RailPosition a = RailGrid.make(x, z, y, yaw, 0.0D, 0);
        double endX;
        double endZ;
        double endYaw;
        if (Math.abs(turnDeg) < 1.0E-6D) {
            endX = x + Geo.dirX(yaw) * len;
            endZ = z + Geo.dirZ(yaw) * len;
            endYaw = yaw;
        } else {
            double r = len / (Math.abs(turnDeg) * Geo.RAD);
            double sign = turnDeg > 0 ? 1.0D : -1.0D;
            double cx = x + Geo.leftX(yaw) * r * sign;
            double cz = z + Geo.leftZ(yaw) * r * sign;
            double a0 = Math.atan2(x - cx, z - cz);
            double a1 = a0 + sign * Math.abs(turnDeg) * Geo.RAD;
            endX = cx + Math.sin(a1) * r;
            endZ = cz + Math.cos(a1) * r;
            endYaw = Geo.normalize360(yaw + turnDeg);
        }
        double[] snapped = RailGrid.snap(endX, endZ, endYaw);
        RailPosition b = RailGrid.make(snapped[0], snapped[1], y, endYaw, 0.0D, 0);
        // The end anchor measures its control handle in its own outward frame.
        b.anchorYaw = (float) Geo.normalize360(endYaw + 180.0D);
        double chord = Math.sqrt(Geo.dist2(a.posX, a.posZ, b.posX, b.posZ));
        if (chord < 1.0D) {
            throw new CommandException("tracktool.err.too_short");
        }
        float tangent = (float) (chord / 3.0D);
        a.anchorLengthHorizontal = tangent;
        b.anchorLengthHorizontal = tangent;
        a.anchorLengthVertical = tangent;
        b.anchorLengthVertical = tangent;
        boolean ok = RailPlacer.place(player.world, a, b, prop, player.isCreative(), null);
        if (!ok) {
            throw new CommandException("tracktool.err.place_failed");
        }
        player.sendMessage(new TextComponentString(TextFormatting.AQUA
                + String.format("base rail %.1fm turn=%.1f  (%d,%d,%d)d%d -> (%d,%d,%d)d%d",
                len, turnDeg, a.blockX, a.blockY, a.blockZ, a.direction,
                b.blockX, b.blockY, b.blockZ, b.direction)));
        player.sendMessage(new TextComponentString(TextFormatting.GRAY
                + String.format("anchors  (%.2f,%.2f,%.2f) -> (%.2f,%.2f,%.2f)",
                a.posX, a.posY, a.posZ, b.posX, b.posY, b.posZ)));
    }

    /** Dumps the rail cores around the player exactly as RTM stored them. */
    public static void dump(EntityPlayerMP player, String[] args) throws CommandException {
        int radius = args.length > 2 ? (int) parse(args[2]) : 32;
        List<String> lines = new ArrayList<String>();
        BlockPos origin = new BlockPos(player);
        int found = 0;
        int baseBlocks = 0;
        int[] firstBase = null;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                for (int y = -8; y <= 8; y++) {
                    BlockPos p = origin.add(x, y, z);
                    if (!player.world.isBlockLoaded(p)) {
                        continue;
                    }
                    if (player.world.getBlockState(p).getBlock() instanceof BlockLargeRailBase) {
                        baseBlocks++;
                        if (firstBase == null) {
                            firstBase = new int[]{p.getX(), p.getY(), p.getZ()};
                        }
                    }
                    if (player.world.getBlockState(p).getBlock() != RTMRail.largeRailCore) {
                        continue;
                    }
                    TileEntityLargeRailCore core = BlockLargeRailBase.getCore(player.world, p);
                    if (core == null) {
                        continue;
                    }
                    found++;
                    NBTTagCompound nbt = new NBTTagCompound();
                    core.writeToNBT(nbt);
                    lines.add("=== core at " + p + " ===");
                    lines.add(nbt.toString());
                    RailPosition[] rps = core.getRailPositions();
                    if (rps != null) {
                        for (int i = 0; i < rps.length; i++) {
                            RailPosition rp = rps[i];
                            lines.add(String.format("  rp%d block=(%d,%d,%d) dir=%d h=%d pos=(%.3f,%.4f,%.3f) "
                                            + "yaw=%.2f pitch=%.2f lenH=%.3f lenV=%.3f cEdge=%.2f cCenter=%.2f",
                                    i, rp.blockX, rp.blockY, rp.blockZ, rp.direction, rp.height & 0xFF,
                                    rp.posX, rp.posY, rp.posZ, rp.anchorYaw, rp.anchorPitch,
                                    rp.anchorLengthHorizontal, rp.anchorLengthVertical,
                                    rp.cantEdge, rp.cantCenter));
                        }
                        if (core.getRailMap(null) != null) {
                            lines.add(String.format("  railMap length=%.3f", core.getRailMap(null).getLength()));
                        }
                    }
                }
            }
        }
        File dir = new File("tracktool");
        dir.mkdirs();
        File out = new File(dir, "raildump.txt");
        try {
            PrintWriter pw = new PrintWriter(new FileWriter(out));
            for (String s : lines) {
                pw.println(s);
            }
            pw.close();
        } catch (Exception e) {
            player.sendMessage(new TextComponentString("dump failed: " + e));
            return;
        }
        player.sendMessage(new TextComponentString(TextFormatting.AQUA
                + "dumped " + found + " cores (" + baseBlocks + " rail blocks) from "
                + origin + " r=" + radius + " to " + out.getAbsolutePath()
                + (firstBase == null ? "" : " first=" + firstBase[0] + "," + firstBase[1] + "," + firstBase[2])));
    }

    /**
     * {@code /tracktool test railcheck [采样数]} —— 用【和"放车"完全相同的那条查找链】验证轨道是否可用。
     *
     * <p>RTM 放车的判定（源码 item/ItemTrain.java:106）：
     * {@code TileEntityLargeRailBase.getRailMapFromCoordinates(world, player, x, y, z)}
     * → {@code getRailFromCoordinates}（从 y 往下找第一格 BlockLargeRailBase）
     * → {@code base.getRailMap(entity)} → {@code base.getRailCore()}
     * （取 <b>startPoint 那一格</b>的 TE，必须是核心）。</p>
     *
     * <p>所以本命令沿着轨道几何逐点走，报告三个数：
     * <b>base</b>=找到底座方块的点数、<b>core</b>=底座的 startPoint 指到本核心的点数、
     * <b>map</b>=能拿到 RailMap 的点数。三者都等于采样数才能在整条弯道上放车。</p>
     */
    public static void railcheck(EntityPlayerMP player, String[] args) {
        int n = 32;
        if (args.length > 2) {
            try {
                n = Math.max(4, Math.min(256, Integer.parseInt(args[2])));
            } catch (Throwable ignored) {
            }
        }
        TileEntityLargeRailCore core = null;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < player.world.loadedTileEntityList.size(); i++) {
            net.minecraft.tileentity.TileEntity te;
            try {
                te = player.world.loadedTileEntityList.get(i);
            } catch (Throwable t) {
                break;
            }
            if (!(te instanceof TileEntityLargeRailCore)) {
                continue;
            }
            double d = te.getPos().distanceSq(player.posX, player.posY, player.posZ);
            if (d < best) {
                best = d;
                core = (TileEntityLargeRailCore) te;
            }
        }
        if (core == null) {
            player.sendMessage(new TextComponentString(TextFormatting.RED + "[railcheck] 附近没有轨道核心"));
            return;
        }
        jp.ngt.rtm.rail.util.RailMap rm = core.getRailMap(null);
        if (rm == null) {
            player.sendMessage(new TextComponentString(TextFormatting.RED + "[railcheck] 核心没有 railmap"));
            return;
        }
        boolean exactGeo = false;
        try {
            java.lang.reflect.Field f = jp.ngt.rtm.rail.util.RailMapBasic.class.getDeclaredField("lineHorizontal");
            f.setAccessible(true);
            exactGeo = rm instanceof jp.ngt.rtm.rail.util.RailMapBasic
                    && f.get(rm) instanceof com.tracktool.rail2.ExactLine;
        } catch (Throwable ignored) {
        }
        int hitBase = 0;
        int hitCore = 0;
        int hitMap = 0;
        String firstBad = null;
        for (int i = 0; i <= n; i++) {
            double[] p = rm.getRailPos(n, i);
            double x = p[1];
            double z = p[0];
            double y = rm.getRailHeight(n, i);
            jp.ngt.rtm.rail.TileEntityLargeRailBase base =
                    jp.ngt.rtm.rail.TileEntityLargeRailBase.getRailFromCoordinates(player.world, x, y + 1.0D, z, 0);
            if (base == null) {
                if (firstBad == null) {
                    firstBad = String.format("i=%d 无底座 @%.1f,%.1f,%.1f", i, x, y, z);
                }
                continue;
            }
            hitBase++;
            int[] sp = base.getStartPoint();
            boolean linked = sp != null && sp.length >= 3
                    && sp[0] == core.getPos().getX() && sp[1] == core.getPos().getY() && sp[2] == core.getPos().getZ();
            if (linked) {
                hitCore++;
            } else if (firstBad == null) {
                firstBad = String.format("i=%d 底座 startPoint=(%d,%d,%d) 指不到核心 %s",
                        i, sp == null ? -1 : sp[0], sp == null ? -1 : sp[1], sp == null ? -1 : sp[2], core.getPos());
            }
            if (jp.ngt.rtm.rail.TileEntityLargeRailBase
                    .getRailMapFromCoordinates(player.world, player, x, y + 1.0D, z) != null) {
                hitMap++;
            }
        }
        // ★ 路基"空洞"自检：核心自己的方块表 vs 世界里真实的方块。
        //   服务端这边缺 ⇒ 是铺设漏了；服务端不缺而你眼睛看到有洞 ⇒ 是客户端渲染/方块表不同步。
        int tableSize = 0;
        int tableMiss = 0;
        String firstHole = null;
        try {
            java.util.List<int[]> list = rm.getRailBlockList(core.getResourceState(), true);
            tableSize = list.size();
            for (int[] b : list) {
                net.minecraft.block.Block blk =
                        player.world.getBlockState(new BlockPos(b[0], b[1], b[2])).getBlock();
                if (blk instanceof BlockLargeRailBase) {
                    continue;                  // 核心方块也是 BlockLargeRailBase 的子类，算命中
                }
                tableMiss++;
                if (firstHole == null) {
                    firstHole = "(" + b[0] + "," + b[1] + "," + b[2] + ")=" + blk.getRegistryName();
                }
            }
        } catch (Throwable t) {
            firstHole = "扫描异常 " + t;
        }
        player.sendMessage(new TextComponentString((tableMiss == 0 ? TextFormatting.GREEN : TextFormatting.RED)
                + String.format("[railcheck] 方块表 %d 格，世界里缺 %d 格%s",
                tableSize, tableMiss, firstHole == null ? "" : "，首个: " + firstHole)));

        // /tracktool test railcheck <n> fix —— 就地补铺：用核心【当前的几何】重跑一遍 setRail，
        // 缺的格子会被补上并重新写好 startPoint（与铺设时走的是同一条 RTM 原生序列）。
        boolean doFix = false;
        for (String a : args) {
            if ("fix".equalsIgnoreCase(a)) {
                doFix = true;
            }
        }
        if (doFix && tableMiss > 0) {
            try {
                rm.setRail(player.world, RTMRail.largeRailBase,
                        core.getPos().getX(), core.getPos().getY(), core.getPos().getZ(), core.getResourceState());
                int after = 0;
                for (int[] b : rm.getRailBlockList(core.getResourceState(), true)) {
                    if (!(player.world.getBlockState(new BlockPos(b[0], b[1], b[2])).getBlock()
                            instanceof BlockLargeRailBase)) {
                        after++;
                    }
                }
                player.sendMessage(new TextComponentString(TextFormatting.AQUA
                        + String.format("[railcheck] 补铺完成：缺 %d -> %d", tableMiss, after)));
            } catch (Throwable t) {
                player.sendMessage(new TextComponentString(TextFormatting.RED + "[railcheck] 补铺失败: " + t));
            }
        }

        boolean ok = hitBase == n + 1 && hitCore == n + 1 && hitMap == n + 1;
        player.sendMessage(new TextComponentString((ok ? TextFormatting.GREEN : TextFormatting.RED)
                + String.format("[railcheck] core=%s len=%.2f 几何=%s  base=%d/%d core=%d/%d map=%d/%d",
                core.getPos(), rm.getLength(), exactGeo ? "自定解析(ExactLine)" : "RTM贝塞尔",
                hitBase, n + 1, hitCore, n + 1, hitMap, n + 1)));
        if (firstBad != null) {
            player.sendMessage(new TextComponentString(TextFormatting.YELLOW + "[railcheck] 首个问题点: " + firstBad));
        }
        player.sendMessage(new TextComponentString(TextFormatting.GRAY
                + (ok ? "结论: 整条轨道都能放车（与 ItemTrain 同一条判定链）"
                      : "结论: 这些点放不了车 —— map 少 = 车辆找不到轨道")));
    }

    /**
     * {@code /tracktool test cell [x y z]} —— 把某一格看个透：
     * 世界里是什么方块、有没有底座 TE、它的 startPoint 指向谁、那个核心的路基方块与高度、
     * 以及这一格【在不在最近核心的方块表里】、离中心线多远。
     * 不给坐标就查玩家脚下那一格。
     */
    public static void cell(EntityPlayerMP player, String[] args) throws CommandException {
        int x;
        int y;
        int z;
        if (args.length >= 5) {
            x = (int) parse(args[2]);
            y = (int) parse(args[3]);
            z = (int) parse(args[4]);
        } else {
            x = net.minecraft.util.math.MathHelper.floor(player.posX);
            y = net.minecraft.util.math.MathHelper.floor(player.posY);
            z = net.minecraft.util.math.MathHelper.floor(player.posZ);
        }
        BlockPos pos = new BlockPos(x, y, z);
        net.minecraft.block.Block blk = player.world.getBlockState(pos).getBlock();
        net.minecraft.tileentity.TileEntity te = player.world.getTileEntity(pos);
        StringBuilder sb = new StringBuilder();
        sb.append("[cell] ").append(pos).append(" 方块=").append(blk.getRegistryName());
        if (te instanceof jp.ngt.rtm.rail.TileEntityLargeRailBase) {
            int[] sp = ((jp.ngt.rtm.rail.TileEntityLargeRailBase) te).getStartPoint();
            sb.append(" TE=").append(te.getClass().getSimpleName())
              .append(" startPoint=(").append(sp[0]).append(',').append(sp[1]).append(',').append(sp[2]).append(')');
            TileEntityLargeRailCore owner = ((jp.ngt.rtm.rail.TileEntityLargeRailBase) te).getRailCore();
            sb.append(" 属于核心=").append(owner == null ? "无(孤块)" : owner.getPos().toString());
            if (owner != null) {
                ResourceStateRail p = owner.getResourceState();
                sb.append(" 路基=").append(p.block == null ? "null" : p.block.getRegistryName())
                  .append(" 高=").append(p.blockHeight);
                float[] fa = ((jp.ngt.rtm.rail.TileEntityLargeRailBase) te).getBlockHeights(x, y, z, p.blockHeight, false);
                if (fa != null) {
                    sb.append(String.format(" 四角高=%.3f/%.3f/%.3f/%.3f", fa[0], fa[1], fa[2], fa[3]));
                }
            }
        } else {
            sb.append(" 没有底座 TE");
        }
        player.sendMessage(new TextComponentString(TextFormatting.AQUA + sb.toString()));

        // 这一格在不在最近核心的方块表里
        TileEntityLargeRailCore core = null;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < player.world.loadedTileEntityList.size(); i++) {
            net.minecraft.tileentity.TileEntity t;
            try {
                t = player.world.loadedTileEntityList.get(i);
            } catch (Throwable ex) {
                break;
            }
            if (!(t instanceof TileEntityLargeRailCore)) {
                continue;
            }
            double d = t.getPos().distanceSq(x, y, z);
            if (d < best) {
                best = d;
                core = (TileEntityLargeRailCore) t;
            }
        }
        if (core == null) {
            return;
        }
        jp.ngt.rtm.rail.util.RailMap rm = core.getRailMap(null);
        boolean inTable = false;
        int n = 0;
        if (rm != null) {
            for (int[] b : rm.getRailBlockList(core.getResourceState(), true)) {
                n++;
                if (b[0] == x && b[1] == y && b[2] == z) {
                    inTable = true;
                }
            }
        }
        String perp = "";
        if (rm != null) {
            int idx = rm.getNearlestPoint(256, x + 0.5D, z + 0.5D);
            double[] rp = rm.getRailPos(256, Math.max(0, idx));
            double dx = x + 0.5D - rp[1];
            double dz = z + 0.5D - rp[0];
            perp = String.format(" 离中心线=%.2f m（最近点 %.1f,%.1f）", Math.sqrt(dx * dx + dz * dz), rp[1], rp[0]);
        }
        player.sendMessage(new TextComponentString((inTable ? TextFormatting.GREEN : TextFormatting.RED)
                + "[cell] 最近核心=" + core.getPos() + " 方块表 " + n + " 格，本格"
                + (inTable ? "在表里" : "【不在表里】") + perp));
    }

    /** Runs the planner self-checks and prints the engine-level report. */
    public static void selftest(EntityPlayerMP player, SelectionManager.Session session) {
        player.sendMessage(new TextComponentString(TextFormatting.AQUA + "track-tool self test"));
        RailPlan plan = PlanBuilder.build(session.spec, session.primary(), session.secondary());
        if (!plan.ok) {
            player.sendMessage(new TextComponentString(TextFormatting.RED + "plan failed: " + plan.errorKey));
            return;
        }
        PlanBuilder.verifySegments(plan);
        player.sendMessage(new TextComponentString(TextFormatting.GRAY + String.format(
                "segments=%d verified=%d L=%.1f turn=%.1f maxDev=%.4f m",
                plan.segmentCount(), plan.verifiedSegments, plan.totalLength, plan.totalTurn,
                plan.maxDeviation)));
        int bad = 0;
        for (com.tracktool.rail.plan.PlanSegment s : plan.segments) {
            if (s.degenerate) {
                bad++;
            }
        }
        player.sendMessage(new TextComponentString(TextFormatting.GRAY
                + "degenerate=" + bad + " elements=" + plan.elements.size()));
        // Worst five segments, so a crooked curve can be traced to its cause.
        List<com.tracktool.rail.plan.PlanSegment> sorted =
                new ArrayList<com.tracktool.rail.plan.PlanSegment>(plan.segments);
        sorted.sort((p, q) -> Double.compare(q.deviation, p.deviation));
        for (int i = 0; i < Math.min(5, sorted.size()); i++) {
            player.sendMessage(new TextComponentString(TextFormatting.GRAY + "  " + sorted.get(i).describe()));
        }
        // Sample the geometry continuity: the turn between consecutive segments
        // must never jump (a worm-like curve would show a jump here).
        double worstKink = 0.0D;
        for (int i = 1; i < plan.segments.size(); i++) {
            double y0 = plan.segments.get(i - 1).yawDeg();
            double y1 = plan.segments.get(i).yawDeg();
            worstKink = Math.max(worstKink, Math.abs(Geo.angleDiff(y1, y0)));
        }
        player.sendMessage(new TextComponentString(TextFormatting.GRAY
                + String.format("max heading step between cores = %.2f deg", worstKink)));
    }

    /** Prints the built-in standards tables so they can be checked against the source. */
    public static void standards(ICommandSender sender) {
        for (int speed : com.tracktool.rail.RailStandards.SPEEDS) {
            double[][] table = com.tracktool.rail.RailStandards.tableForSpeed(speed);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < table[0].length; i++) {
                sb.append(String.format("%.0f/%.0f ", table[0][i], table[1][i]));
            }
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY.toString()
                    + speed + " km/h: " + sb));
        }
        sender.sendMessage(new TextComponentString(TextFormatting.GRAY
                + "easement(160,3000)=" + com.tracktool.rail.RailStandards.easementLength(160, 3000)
                + " (160,2200)=" + com.tracktool.rail.RailStandards.easementLength(160, 2200)
                + " cant(160,2000)=" + com.tracktool.rail.RailStandards.equilibriumCantMm(160, 2000)
                + "mm"));
        TrackSpec s = new TrackSpec();
        sender.sendMessage(new TextComponentString(TextFormatting.GRAY
                + "spec defaults: mode=" + s.mode + " R=" + s.radiusM + " angle=" + s.angleDeg
                + " transition=" + s.transitionLength + " verticalR=" + s.verticalRadius));
    }

    private static double parse(String s) throws CommandException {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new CommandException("tracktool.err.not_number");
        }
    }
}
