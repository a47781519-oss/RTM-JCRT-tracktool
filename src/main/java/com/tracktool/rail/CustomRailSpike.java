package com.tracktool.rail;

import jp.ngt.rtm.RTMRail;
import jp.ngt.rtm.modelpack.state.ResourceStateRail;
import jp.ngt.rtm.rail.TileEntityLargeRailCore;
import jp.ngt.rtm.rail.util.RailMap;
import jp.ngt.rtm.rail.util.RailMapCustom;
import jp.ngt.rtm.rail.util.RailPosition;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** 最小试验：脚本轨道图铺"整条弯道一个核心"。放回 src 前请先确认 RailPlacer.TrackToolCoreHolder 可见。 */
public final class CustomRailSpike {

    public static final String SCRIPT_NAME = "scripts/LineArc.js";

    /** 依次尝试的脚本名写法（哪个被接受会打进日志）。 */
    private static final String[] SCRIPT_CANDIDATES = {
        "scripts/TrackAlignment.js",
        "scripts/TrackAlignment",
        "TrackAlignment.js",
        "TrackAlignment",
        "minecraft:scripts/TrackAlignment.js",
        "rtm:scripts/TrackAlignment.js",
    };

    private CustomRailSpike() {
    }

    public static String args(double radius, double angleDeg, double spiralLen,
                              double cantMm, double riseM, double rv, double dir) {
        return "R=" + radius + ",angle=" + angleDeg + ",ls=" + spiralLen
                + ",cant=" + cantMm + ",rise=" + riseM + ",Rv=" + rv + ",dir=" + dir;
    }

    public static boolean place(World world, RailPosition start, RailPosition end,
                                ResourceStateRail prop, String args) {
        RailMap map;
        try {
            start.scriptName = SCRIPT_NAME;
            start.scriptArgs = args;
            map = new RailMapCustom(start, start.scriptName, start.scriptArgs);
        } catch (Throwable t) {
            RailPlacer.TrackToolCoreHolder.log("custom rail map failed (script=" + SCRIPT_NAME
                    + ", args=" + args + ")", t);
            return false;
        }
        BlockPos sp = new BlockPos(start.blockX, start.blockY, start.blockZ);
        try {
            map.setRail(world, RTMRail.largeRailBase, sp.getX(), sp.getY(), sp.getZ(), prop);
            world.setBlockState(sp, RTMRail.largeRailCore.getDefaultState(), 3);
            if (!(world.getTileEntity(sp) instanceof TileEntityLargeRailCore)) {
                RailPlacer.TrackToolCoreHolder.warn("spike: core tile missing at %s", sp);
                return false;
            }
            TileEntityLargeRailCore core = (TileEntityLargeRailCore) world.getTileEntity(sp);
            core.setRailPositions(new RailPosition[]{start, end});
            core.getResourceState().readFromNBT(prop.writeToNBT());
            core.setStartPoint(sp.getX(), sp.getY(), sp.getZ());
            core.createRailMap();
            core.sendPacket();
            RailPlacer.TrackToolCoreHolder.warn("spike ok: script=%s length=%s at %s",
                    SCRIPT_NAME, Double.valueOf(map.getLength()), sp);
            return true;
        } catch (Throwable t) {
            RailPlacer.TrackToolCoreHolder.log("spike placement failed", t);
            return false;
        }
    }
}