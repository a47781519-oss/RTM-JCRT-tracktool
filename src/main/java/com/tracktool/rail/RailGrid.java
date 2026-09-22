package com.tracktool.rail;

import com.tracktool.util.Geo;
import jp.ngt.rtm.rail.util.RailPosition;

/**
 * Bridge between continuous design geometry and RTM's rail lattice.
 *
 * <p>A {@link RailPosition} is stored in NBT only as {@code BlockPos + Direction
 * + Height}, and its world position is rebuilt as
 * {@code posX = blockX + 0.5 + REVISION[dir][0]},
 * {@code posZ = blockZ + 0.5 + REVISION[dir][1]},
 * {@code posY = blockY + (height + 1) / 16}.</p>
 *
 * <p>That means a rail endpoint can only live on the half-block lattice, and
 * never on a point whose X and Z are both integers (the eight reachable offsets
 * are the block edge midpoints and corners, never the corners of four blocks).
 * This class snaps design points onto that lattice while pushing the snapping
 * error into the longitudinal direction, which keeps the heading smooth.</p>
 */
public final class RailGrid {

    private RailGrid() {
    }

    public static boolean isInteger(double v) {
        return Math.abs(v - Math.rint(v)) < 1.0E-6D;
    }

    /** True for a half-integer coordinate (…, -0.5, 0.5, 1.5, …). */
    public static boolean isHalf(double v) {
        double f = v - Math.floor(v);
        return Math.abs(f - 0.5D) < 1.0E-6D;
    }

    /**
     * A point is representable by a RailPosition unless both coordinates are
     * half-integers: {@code REVISION} lists the eight offsets around a block
     * centre but not the centre itself, so anchors live on block edge midpoints
     * (one coordinate integral) or on block corners (both integral).
     */
    public static boolean isReachable(double x, double z) {
        return !(isHalf(x) && isHalf(z));
    }

    /**
     * Snaps (x, z) to the nearest representable rail lattice point.
     *
     * @param yawDeg heading used to decide which candidate is best: error along
     *               the direction of travel is preferred over lateral error.
     * @return {snappedX, snappedZ}
     */
    public static double[] snap(double x, double z, double yawDeg) {
        double bx = Math.rint(x * 2.0D) / 2.0D;
        double bz = Math.rint(z * 2.0D) / 2.0D;
        double nx = Geo.leftX(yawDeg);
        double nz = Geo.leftZ(yawDeg);
        double bestScore = Double.MAX_VALUE;
        double bestX = bx;
        double bestZ = bz;
        boolean found = false;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                double cx = bx + i * 0.5D;
                double cz = bz + j * 0.5D;
                if (!isReachable(cx, cz)) {
                    continue;
                }
                double ex = cx - x;
                double ez = cz - z;
                double lateral = ex * nx + ez * nz;
                double total = ex * ex + ez * ez;
                double score = lateral * lateral * 4.0D + total;
                if (score < bestScore) {
                    bestScore = score;
                    bestX = cx;
                    bestZ = cz;
                    found = true;
                }
            }
        }
        if (!found) {
            // Only reachable when (bx, bz) itself is representable.
            return new double[]{bx, bz};
        }
        return new double[]{bestX, bestZ};
    }

    /**
     * Direction byte (0-7) that reproduces this lattice point, using
     * {@code blockX = floor(x)} / {@code blockZ = floor(z)}; for those blocks the
     * reachable offsets are exactly (x integral ? -0.5 : 0) and
     * (z integral ? -0.5 : 0).
     *
     * @return -1 when the point has no representable direction (both coordinates
     *         half-integers), which {@link #snap} prevents.
     */
    public static int directionFor(double x, double z) {
        boolean ix = isInteger(x);
        boolean iz = isInteger(z);
        if (ix && iz) {
            return 1;   // REVISION[1] = (-0.5, -0.5)
        }
        if (ix) {
            return 2;   // REVISION[2] = (-0.5,  0.0)
        }
        if (iz) {
            return 0;   // REVISION[0] = ( 0.0, -0.5)
        }
        return -1;
    }

    /** Converts a world height (blocks) into the packed blockY/height pair. */
    public static int[] packY(double y) {
        int y16 = (int) Math.round(y * 16.0D);
        if (y16 < 1) {
            y16 = 1;
        }
        if (y16 > 32767) {
            y16 = 32767;
        }
        int blockY = Math.floorDiv(y16 - 1, 16);
        int height = (y16 - 1) - blockY * 16;
        return new int[]{blockY, height};
    }

    /**
     * Builds an RTM RailPosition at an exact lattice point. The returned
     * position reproduces (x, z, y); {@link #verify} double-checks that against
     * RTM's own reconstruction.
     */
    public static RailPosition make(double x, double z, double y, double yawDeg, double pitchDeg, int switchType) {
        int dir = directionFor(x, z);
        double sx = x;
        double sz = z;
        if (dir < 0) {
            // Both coordinates were half-integers: nudge onto a representable
            // neighbour. RailGrid.snap already avoids this, so it is a safety net.
            sx = x + 0.5D;
            dir = directionFor(sx, sz);
        }
        int blockX = (int) Math.floor(sx);
        int blockZ = (int) Math.floor(sz);
        int[] by = packY(y);
        RailPosition rp = new RailPosition(blockX, by[0], blockZ, dir, switchType);
        rp.setHeight((byte) by[1]);
        rp.anchorYaw = (float) Geo.normalize360(yawDeg);
        rp.anchorPitch = (float) pitchDeg;
        rp.anchorLengthHorizontal = -1.0F;
        rp.anchorLengthVertical = 0.0F;
        rp.cantEdge = 0.0F;
        rp.cantCenter = 0.0F;
        rp.cantRandom = 0.0F;
        return rp;
    }

    /** Rebuilds a position from a raw block position + direction + height. */
    public static RailPosition makeRaw(int blockX, int blockY, int blockZ, byte dir, int height) {
        RailPosition rp = new RailPosition(blockX, blockY, blockZ, dir, 0);
        rp.setHeight((byte) height);
        return rp;
    }

    /** Error (metres) between the requested point and what RTM will reconstruct. */
    public static double verify(RailPosition rp, double x, double z, double y) {
        double dx = rp.posX - x;
        double dz = rp.posZ - z;
        double dy = rp.posY - y;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
