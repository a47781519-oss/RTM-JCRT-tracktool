package com.tracktool.rail;

import com.tracktool.util.Geo;
import jp.ngt.rtm.rail.util.RailPosition;
import net.minecraft.util.math.BlockPos;

/**
 * One end of an existing RTM rail, as selected by the staff.
 *
 * <p>All the geometry needed to continue the track smoothly is derived here:
 * the outward heading, the outward grade and the physical roll, each expressed
 * in the frame of the <em>new</em> rail that will leave this joint.</p>
 */
public final class RailEnd {

    public int dim;
    public BlockPos corePos = BlockPos.ORIGIN;
    /** 0 = the core's StartRP, 1 = its EndRP. */
    public int endIndex;
    /** The rail position this end is anchored at (a copy, safe to modify). */
    public RailPosition rp;
    /** Rail shape description of the core, for tooltips. */
    public String shapeName = "";

    /** Joint world position (exactly what RTM will reconstruct). */
    public double x;
    public double y;
    public double z;

    /** Heading the new rail must leave the joint with, degrees. */
    public double outwardYaw;
    /** Pitch (grade angle in degrees) the new rail starts with, in its own frame. */
    public double outwardPitch;
    /** Roll the new rail starts with, in its own frame, degrees. */
    public double jointCant;
    /** Roll of the existing rail at the joint, in the existing rail's frame. */
    public double existingCant;

    /** Recomputes every derived value from {@link #rp} and {@link #endIndex}. */
    public void derive() {
        if (this.rp == null) {
            return;
        }
        this.x = this.rp.posX;
        this.y = this.rp.posY;
        this.z = this.rp.posZ;
        // The rail body leaves the start anchor along +anchorYaw and arrives at
        // the end anchor from -anchorYaw; either way the outward continuation
        // points at anchorYaw + 180.
        this.outwardYaw = Geo.normalize360(this.rp.anchorYaw + 180.0D);
        // Travelling outward from the start reverses the direction of travel,
        // which flips the sign of the grade in the new rail's own frame.
        this.outwardPitch = this.endIndex == 0 ? -this.rp.anchorPitch : this.rp.anchorPitch;
        this.existingCant = this.rp.cantEdge;
        // Roll is measured about the direction of travel, so reversing the
        // travel direction negates it. Both selection cases end up as
        // -cantEdge in the new rail's frame.
        this.jointCant = -this.rp.cantEdge;
    }

    public boolean isValid() {
        return this.rp != null;
    }

    public String describe() {
        return String.format("%s [%d,%d,%d] end=%d yaw=%.1f pitch=%.2f",
                this.corePos, this.rp.blockX, this.rp.blockY, this.rp.blockZ,
                this.endIndex, this.outwardYaw, this.outwardPitch);
    }

    public RailEnd copy() {
        RailEnd e = new RailEnd();
        e.dim = this.dim;
        e.corePos = this.corePos;
        e.endIndex = this.endIndex;
        e.rp = this.rp;
        e.shapeName = this.shapeName;
        e.x = this.x;
        e.y = this.y;
        e.z = this.z;
        e.outwardYaw = this.outwardYaw;
        e.outwardPitch = this.outwardPitch;
        e.jointCant = this.jointCant;
        e.existingCant = this.existingCant;
        return e;
    }
}
