package com.tracktool.rail.plan;

import com.tracktool.util.Geo;

/** Straight element: constant heading, zero curvature. */
public final class LineElement extends Element {

    public LineElement(double length) {
        super(length);
    }

    @Override
    public double curvatureAt(double s) {
        return 0.0D;
    }

    @Override
    public void pointAt(double s, double[] out) {
        double t = this.clampS(s);
        out[0] = this.startX + Geo.dirX(this.startYaw) * t;
        out[1] = this.startZ + Geo.dirZ(this.startYaw) * t;
    }

    @Override
    public String typeKey() {
        return "tracktool.element.line";
    }
}
