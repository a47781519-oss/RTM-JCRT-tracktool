package com.tracktool.rail.plan;

import com.tracktool.rail.RailGrid;
import com.tracktool.util.Geo;
import jp.ngt.rtm.rail.util.RailMapBasic;
import jp.ngt.rtm.rail.util.RailPosition;

import java.util.ArrayList;
import java.util.List;

/**
 * One generated RTM rail core: a start/end {@link RailPosition} pair, exactly
 * what {@code BlockMarker.createNormalRail} stores in a
 * {@code TileEntityLargeRailCore}.
 */
public final class PlanSegment {

    public final RailPosition start;
    public final RailPosition end;

    /** Arc length range of the design alignment covered by this segment. */
    public final double s0;
    public final double s1;
    /** Index of the parallel line this segment belongs to (0 = the selected one). */
    public final int lineIndex;

    /** Maximum deviation (metres) between the design centreline and RTM's own curve. */
    public double deviation;
    /** True when RTM would silently turn this segment into a straight line. */
    public boolean degenerate;

    public PlanSegment(RailPosition start, RailPosition end, double s0, double s1, int lineIndex) {
        this.start = start;
        this.end = end;
        this.s0 = s0;
        this.s1 = s1;
        this.lineIndex = lineIndex;
    }

    public double chordLength() {
        double dx = this.end.posX - this.start.posX;
        double dz = this.end.posZ - this.start.posZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Rebuilds the segment through RTM's own {@link RailMapBasic} and measures
     * how far the real curve strays from the designed alignment. This is the
     * "verify against the source, not just the maths" step: it catches RTM's
     * straight-line shortcut and any lattice snapping damage.
     *
     * @param design sampled design points: index 0=x, 1=z, 2=y
     */
    public double verify(Alignment design, double[] tmp) {
        double maxDev = 0.0D;
        RailMapBasic rm;
        try {
            rm = new RailMapBasic(this.start, this.end);
        } catch (Throwable t) {
            this.degenerate = true;
            this.deviation = Double.MAX_VALUE;
            return this.deviation;
        }
        double len = rm.getLength();
        int split = (int) Math.max(8.0D, Math.min(4000.0D, len * 8.0D));
        int count = split;
        for (int i = 0; i <= count; i++) {
            int idx = (int) Math.round((double) i / count * split);
            double[] p = rm.getRailPos(split, idx);
            double y = rm.getRailHeight(split, idx);
            double wx = p[1];
            double wz = p[0];
            double s = this.s0 + (this.s1 - this.s0) * i / count;
            design.eval(s, tmp);
            double dx = wx - tmp[0];
            double dz = wz - tmp[1];
            double dy = y - tmp[2];
            double dev = Math.sqrt(dx * dx + dz * dz + dy * dy);
            if (dev > maxDev) {
                maxDev = dev;
            }
        }
        // A straight-line shortcut shows up as a large mid-span deviation.
        this.deviation = maxDev;
        this.degenerate = maxDev > 1.0D;
        return maxDev;
    }

    /** Copies a RailPosition so the joint keeps RTM's exact block/direction/height. */
    public static RailPosition copyOf(RailPosition rp) {
        RailPosition c = RailGrid.makeRaw(rp.blockX, rp.blockY, rp.blockZ, rp.direction, rp.height & 0xFF);
        c.anchorYaw = rp.anchorYaw;
        c.anchorPitch = rp.anchorPitch;
        c.anchorLengthHorizontal = rp.anchorLengthHorizontal;
        c.anchorLengthVertical = rp.anchorLengthVertical;
        c.cantCenter = rp.cantCenter;
        c.cantEdge = rp.cantEdge;
        c.cantRandom = rp.cantRandom;
        c.constLimitHP = rp.constLimitHP;
        c.constLimitHN = rp.constLimitHN;
        c.constLimitWP = rp.constLimitWP;
        c.constLimitWN = rp.constLimitWN;
        return c;
    }

    /** Sanity check: both anchors must sit exactly on the lattice. */
    public boolean checkLattice() {
        return check(this.start) && check(this.end);
    }

    private static boolean check(RailPosition rp) {
        if (!RailGrid.isReachable(rp.posX, rp.posZ)) {
            return false;
        }
        double ex = rp.blockX + 0.5D + REV(rp.direction, 0) - rp.posX;
        double ez = rp.blockZ + 0.5D + REV(rp.direction, 1) - rp.posZ;
        return Math.abs(ex) < 1.0E-6D && Math.abs(ez) < 1.0E-6D;
    }

    private static double REV(byte dir, int axis) {
        return RailPosition.REVISION[dir & 7][axis];
    }

    /** Distance between the two anchors (3D). */
    public double length3d() {
        double dx = this.end.posX - this.start.posX;
        double dy = this.end.posY - this.start.posY;
        double dz = this.end.posZ - this.start.posZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public List<int[]> tileFootprint() {
        List<int[]> out = new ArrayList<int[]>(2);
        out.add(new int[]{this.start.blockX, this.start.blockY, this.start.blockZ});
        out.add(new int[]{this.end.blockX, this.end.blockY, this.end.blockZ});
        return out;
    }

    public String describe() {
        return String.format("seg[%.1f-%.1f] (%d,%d,%d)dir%d -> (%d,%d,%d)dir%d len=%.2f dev=%.3f",
                this.s0, this.s1, this.start.blockX, this.start.blockY, this.start.blockZ, this.start.direction,
                this.end.blockX, this.end.blockY, this.end.blockZ, this.end.direction,
                this.length3d(), this.deviation);
    }

    public double yawDeg() {
        return Geo.yawOf(this.end.posX - this.start.posX, this.end.posZ - this.start.posZ);
    }
}
