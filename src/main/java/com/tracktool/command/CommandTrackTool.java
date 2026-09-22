package com.tracktool.command;

import com.tracktool.rail.PlacementQueue;
import com.tracktool.rail.RailEnd;
import com.tracktool.rail.SelectionManager;
import com.tracktool.rail.TrackSpec;
import com.tracktool.rail.plan.PlanBuilder;
import com.tracktool.rail.plan.RailPlan;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code /tracktool} - the command side of every GUI action, as required by the
 * brief (back, confirm, undo, cancel, plus status and diagnostics).
 */
public class CommandTrackTool extends CommandBase {

    @Override
    public String getName() {
        return "tracktool";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/tracktool <confirm|back|undo|cancel|status|mode|param|plan|help>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        SelectionManager.Session session = SelectionManager.INSTANCE.get(player);
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();

        switch (sub) {
            case "confirm":
                SelectionManager.INSTANCE.confirm(player, session);
                return;
            case "back":
                SelectionManager.INSTANCE.handleAction(player, SelectionManager.ACTION_BACK);
                return;
            case "undo":
                SelectionManager.INSTANCE.handleAction(player, SelectionManager.ACTION_UNDO);
                return;
            case "cancel":
                SelectionManager.INSTANCE.handleAction(player, SelectionManager.ACTION_CLEAR);
                sender.sendMessage(new TextComponentTranslation("tracktool.msg.cleared"));
                return;
            case "status":
                this.status(sender, session);
                return;
            case "mode":
                if (args.length < 2) {
                    throw new CommandException("tracktool.cmd.mode_usage");
                }
                String m = args[1].toLowerCase();
                // "connect" also starts with 'c', so it must be tested first.
                if (m.startsWith("s")) {
                    session.spec.mode = TrackSpec.MODE_STRAIGHT;
                } else if (m.startsWith("conn") || m.startsWith("j") || m.startsWith("l")) {
                    session.spec.mode = TrackSpec.MODE_CONNECT;
                } else if (m.startsWith("c")) {
                    session.spec.mode = TrackSpec.MODE_CURVE;
                } else {
                    throw new CommandException("tracktool.cmd.mode_usage");
                }
                SelectionManager.INSTANCE.sync(player, session);
                return;
            case "param":
                if (args.length < 3) {
                    throw new CommandException("tracktool.cmd.param_usage");
                }
                this.param(sender, session, args[1], args[2]);
                SelectionManager.INSTANCE.sync(player, session);
                return;
            case "plan":
                this.plan(sender, session);
                return;
            case "test":
                if (args.length < 2) {
                    throw new CommandException("tracktool.cmd.test_usage");
                }
                String sub2 = args[1].toLowerCase();
        if (sub2.equals("custom")) {
            TestHarness.custom(player, session, args);
            return;
        }
                if ("base".equals(sub2) || "rail".equals(sub2)) {
                    TestHarness.base(player, session, args);
                } else if ("select".equals(sub2)) {
                    TestHarness.select(player, session);
                } else if ("selectat".equals(sub2)) {
                    TestHarness.selectAt(player, args);
                } else if ("railcheck".equals(sub2)) {
                    TestHarness.railcheck(player, args);
                } else if ("cell".equals(sub2)) {
                    TestHarness.cell(player, args);
                } else if ("joints".equals(sub2)) {
                    TestHarness.joints(player, args);
                } else if ("clientcheck".equals(sub2)) {
                    com.tracktool.net.Packets.sendTo(player, new com.tracktool.net.Packets.ExactRail.Diag());
                    player.sendMessage(new TextComponentString(TextFormatting.GRAY
                            + "[clientcheck] 已请求客户端自检，结果会打在聊天栏"));
                } else if ("dump".equals(sub2)) {
                    TestHarness.dump(player, args);
                } else if ("selftest".equals(sub2)) {
                    TestHarness.selftest(player, session);
                } else if ("standards".equals(sub2)) {
                    TestHarness.standards(sender);
                } else {
                    throw new CommandException("tracktool.cmd.test_usage");
                }
                return;
            case "gui":
                SelectionManager.INSTANCE.sync(player, session, true);
                return;
            case "custom":
                TestHarness.custom(player, session, args);
                return;
            case "exact": {
                // 查询/切换 A 方案（自定零量化几何）。用法：/tracktool exact [true|false]
                boolean now = com.tracktool.rail2.ExactRailGate.enabled();
                if (args.length > 1 && "true".equalsIgnoreCase(args[1])) {
                    com.tracktool.rail2.ExactRailGate.setEnabled(true);
                    now = true;
                } else if (args.length > 1 && "false".equalsIgnoreCase(args[1])) {
                    com.tracktool.rail2.ExactRailGate.setEnabled(false);
                    now = false;
                }
                if (args.length > 2 && "seg".equalsIgnoreCase(args[1])) {
                    // /tracktool exact seg <米>   0 = 整条线一个核心（对照用）
                    double v;
                    try {
                        v = Double.parseDouble(args[2]);
                    } catch (NumberFormatException e) {
                        throw new CommandException("usage: /tracktool exact seg <米，0=不分段>");
                    }
                    com.tracktool.rail2.ExactRailGate.setSegmentLength(v);
                    player.sendMessage(new TextComponentString(TextFormatting.AQUA
                            + "[tracktool] 每个核心覆盖 " + (v <= 0.0D ? "整条线" : (v + " 米"))
                            + "（几何不受影响：每段都是同一条解析几何的一段里程）"));
                    return;
                }
                if (args.length > 1 && "selftest".equalsIgnoreCase(args[1])) {
                    // 游戏内自检：不需要铺轨，直接验证几何（也顺便证明 rail2 已随 jar 加载）
                    double[] p = {300.0D, 45.0D, 30.0D, 105.0D, 0.0D, 15000.0D, 1.0D, 180.0D, 0.0D, 0.0D, 0.0D};
                    com.tracktool.rail2.AlignmentGeometry g = com.tracktool.rail2.ExactRailGeometryCodec.build(p);
                    double theo = 2 * 30.0D + 300.0D * (Math.toRadians(45.0D) - 2 * (30.0D / (2 * 300.0D)));
                    player.sendMessage(new net.minecraft.util.text.TextComponentString(
                            String.format("[ttx] L=%.3f (theory %.3f, diff %.6f)  endYaw=%.4f (expect 225)  roll@Ls=%.5f roll@arc=%.5f",
                                    g.length(), theo, Math.abs(g.length() - theo), g.yaw(g.length()),
                                    g.roll(30.0D), g.roll(g.length() * 0.5D))));
                    player.sendMessage(new net.minecraft.util.text.TextComponentString(
                            "[ttx] exact geometry = " + now + "  (rail2 loaded ok)"));
                    return;
                }
                player.sendMessage(new net.minecraft.util.text.TextComponentString(
                        "[tracktool] exact geometry = " + now));
                return;
            }
            case "help":
            default:
                sender.sendMessage(new TextComponentString(TextFormatting.AQUA + this.getUsage(sender)));
                return;
        }
    }

    private void status(ICommandSender sender, SelectionManager.Session s) {
        sender.sendMessage(new TextComponentString(TextFormatting.AQUA + "track-tool " + com.tracktool.TrackToolCore.VERSION));
        sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "mode=" + s.spec.mode
                + " ends=" + s.ends.size() + " busy=" + s.busy + " undo=" + s.undoStack.size()));
        for (RailEnd e : s.ends) {
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "  " + e.describe()));
        }
        if (s.primary() != null) {
            RailPlan plan = PlanBuilder.build(s.spec, s.primary(), s.secondary());
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "plan ok=" + plan.ok
                    + " len=" + String.format("%.1f", plan.totalLength)
                    + " segs=" + plan.segmentCount()
                    + " dev=" + String.format("%.3f", plan.maxDeviation)
                    + (plan.errorKey == null ? "" : " err=" + plan.errorKey)));
        }
        sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "queue=" + PlacementQueue.INSTANCE.queueSize()));
    }

    private void param(ICommandSender sender, SelectionManager.Session s, String key, String value)
            throws CommandException {
        TrackSpec spec = s.spec;
        try {
            switch (key.toLowerCase()) {
                case "radius":
                    spec.radiusM = (int) Double.parseDouble(value);
                    break;
                case "angle":
                    spec.angleDeg = Double.parseDouble(value);
                    break;
                case "cant":
                    spec.cantDeg = com.tracktool.util.Geo.clamp(Double.parseDouble(value),
                            -TrackSpec.MAX_CANT_DEG, TrackSpec.MAX_CANT_DEG);
                    break;
                case "transition":
                    spec.transitionLength = Double.parseDouble(value);
                    spec.autoTransition = false;
                    break;
                case "autotransition":
                    spec.autoTransition = Boolean.parseBoolean(value);
                    break;
                case "length":
                    spec.straightLengthM = (int) Double.parseDouble(value);
                    break;
                case "rise":
                    spec.riseM = (int) Double.parseDouble(value);
                    break;
                case "lines":
                    String[] parts = value.split(":");
                    if (parts.length == 2) {
                        spec.linesLeft = (int) Double.parseDouble(parts[0]);
                        spec.linesRight = (int) Double.parseDouble(parts[1]);
                    }
                    break;
                case "spacing":
                    spec.spacingM = (int) Double.parseDouble(value);
                    break;
                case "dir":
                    spec.turnLeft = value.toLowerCase().startsWith("l");
                    break;
                case "rail":
                    spec.railResource = value;
                    break;
                case "ballast":
                    spec.ballastBlock = value;
                    break;
                default:
                    throw new CommandException("tracktool.cmd.unknown_param", key);
            }
        } catch (NumberFormatException e) {
            throw new CommandException("tracktool.err.not_number");
        }
        sender.sendMessage(new TextComponentTranslation("tracktool.cmd.param_set", key, value));
    }

    private void plan(ICommandSender sender, SelectionManager.Session s) {
        if (s.primary() == null) {
            sender.sendMessage(new TextComponentTranslation("tracktool.msg.need_end"));
            return;
        }
        RailPlan plan = PlanBuilder.build(s.spec, s.primary(), s.secondary());
        if (!plan.ok) {
            sender.sendMessage(new TextComponentTranslation(plan.errorKey));
            return;
        }
        sender.sendMessage(new TextComponentString(TextFormatting.AQUA + "elements:"));
        for (com.tracktool.rail.plan.Alignment.ElementInfo e : plan.elements) {
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + String.format(
                    "  %s len=%.1f turn=%.2f R=%.1f", e.typeKey, e.length, e.turnDeg, e.radius)));
        }
        sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "max deviation = "
                + String.format("%.4f", plan.maxDeviation) + " m"));
        int bad = 0;
        for (com.tracktool.rail.plan.PlanSegment seg : plan.segments) {
            if (seg.degenerate) {
                bad++;
            }
        }
        sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "degenerate segments = " + bad));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args,
                                          @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "confirm", "back", "undo", "cancel", "status",
                    "mode", "param", "plan", "help");
        }
        if (args.length == 2 && "mode".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "straight", "curve", "connect");
        }
        if (args.length == 2 && "param".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "radius", "angle", "cant", "transition", "length",
                    "rise", "lines", "spacing", "dir", "rail", "ballast");
        }
        return Collections.emptyList();
    }

    @Override
    public List<String> getAliases() {
        List<String> list = new ArrayList<String>();
        list.add("tt");
        return list;
    }
}
