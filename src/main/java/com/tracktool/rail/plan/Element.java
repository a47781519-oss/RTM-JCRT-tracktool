package com.tracktool.rail.plan;

import com.tracktool.util.Geo;

/**
 * One horizontal element of an alignment, parameterised by arc length.
 *
 * <p>Positive curvature means the heading increases, which in RTM's yaw
 * convention ({@code yaw = atan2(dx, dz)}) is a turn to the left.</p>
 */
public abstract class Element {

    /** Arc length of the element in metres. */
    public final double length;
    protected double startX;
    protected double startZ;
    /** Heading at s=0, degrees, RTM yaw convention. */
    protected double startYaw;

    protected Element(double length) {
        this.length = length < 0.0D ? 0.0D : length;
    }

    /** Places the element so that it starts at (x, z) heading yawDeg. */
    public void placeAt(double x, double z, double yawDeg) {
        this.startX = x;
        this.startZ = z;
        this.startYaw = yawDeg;
    }

    public double startX() {
        return this.startX;
    }

    public double startZ() {
        return this.startZ;
    }

    public double startYaw() {
        return this.startYaw;
    }

    public double endX() {
        double[] p = new double[2];
        this.pointAt(this.length, p);
        return p[0];
    }

    public double endZ() {
        double[] p = new double[2];
        this.pointAt(this.length, p);
        return p[1];
    }

    public double endYaw() {
        return this.yawAt(this.length);
    }

    /** Signed curvature (1/m) at arc length s. */
    public abstract double curvatureAt(double s);

    /** Fills out[0]=x, out[1]=z for arc length s (clamped to [0, length]). */
    public abstract void pointAt(double s, double[] out);

    /** Heading in degrees at arc length s. */
    public double yawAt(double s) {
        return this.startYaw + this.turnAt(s);
    }

    /** Signed turn (degrees) accumulated from the start up to s. */
    public double turnAt(double s) {
        double t = this.clampS(s);
        if (this.length <= 0.0D) {
            return 0.0D;
        }
        double k0 = this.curvatureAt(0.0D);
        double k1 = this.curvatureAt(this.length);
        // The integral of a linearly varying curvature is exact.
        return (k0 * t + (k1 - k0) * t * t / (2.0D * this.length)) * Geo.DEG;
    }

    protected double clampS(double s) {
        return s < 0.0D ? 0.0D : (s > this.length ? this.length : s);
    }

    /** Left normal at arc length s: out[0]=x, out[1]=z. */
    public void leftNormalAt(double s, double[] out) {
        double yaw = this.yawAt(s);
        out[0] = Geo.leftX(yaw);
        out[1] = Geo.leftZ(yaw);
    }

    public abstract String typeKey();
}
