package com.tracktool.rail.plan;

import com.tracktool.util.Geo;

/** Circular arc with a signed radius (positive radius = turn left). */
public final class ArcElement extends Element {

    /** Signed radius in metres; |R| is the geometric radius. */
    public final double signedRadius;

    public ArcElement(double signedRadius, double length) {
        super(length);
        this.signedRadius = signedRadius;
    }

    /** Arc length needed for a given turn angle (degrees). */
    public static double lengthFor(double signedRadius, double turnDeg) {
        return Math.abs(signedRadius) * Math.abs(turnDeg) * Geo.RAD;
    }

    @Override
    public double curvatureAt(double s) {
        return 1.0D / this.signedRadius;
    }

    @Override
    public void pointAt(double s, double[] out) {
        double t = this.clampS(s);
        double k = 1.0D / this.signedRadius;
        if (Math.abs(k) < 1.0E-9D) {
            out[0] = this.startX + Geo.dirX(this.startYaw) * t;
            out[1] = this.startZ + Geo.dirZ(this.startYaw) * t;
            return;
        }
        double r = this.signedRadius;
        // Centre is r to the left of the start point.
        double cx = this.startX + Geo.leftX(this.startYaw) * r;
        double cz = this.startZ + Geo.leftZ(this.startYaw) * r;
        double a0 = Math.atan2(this.startX - cx, this.startZ - cz);
        double a1 = a0 + t / r;
        out[0] = cx + Math.sin(a1) * Math.abs(r);
        out[1] = cz + Math.cos(a1) * Math.abs(r);
    }

    @Override
    public String typeKey() {
        return "tracktool.element.arc";
    }
}
