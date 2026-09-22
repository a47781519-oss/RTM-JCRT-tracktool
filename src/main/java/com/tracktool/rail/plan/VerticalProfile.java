package com.tracktool.rail.plan;

import com.tracktool.util.Geo;

/**
 * Vertical alignment. The grade (pitch) is piecewise linear in arc length, so
 * the height profile is composed of parabolic vertical curves - exactly the
 * "vertical transition + straight grade + vertical transition" shape a railway
 * uses, and the only way to get a smooth grade out of RTM (whose native
 * {@code anchorLengthVertical = 0} makes the grade jump at the joint).
 */
public final class VerticalProfile {

    public final double length;
    public final double y0;
    /** tan(grade) at s=0, positive = climbing along the direction of travel. */
    public final double p0;
    /** tan(grade) at s=length. */
    public final double p2;
    public final double totalRise;
    /** True when a constant-grade phase exists between the two vertical curves. */
    public boolean twoPhase;

    public double easeIn;
    public double easeOut;
    public double midPitch;

    private VerticalProfile(double length, double y0, double p0, double p2, double totalRise) {
        this.length = length;
        this.y0 = y0;
        this.p0 = p0;
        this.p2 = p2;
        this.totalRise = totalRise;
    }

    public static VerticalProfile create(double length, double y0, double startPitchTan, double totalRise,
                                         double verticalRadius) {
        return create(length, y0, startPitchTan, 0.0D, totalRise, verticalRadius);
    }

    public static VerticalProfile create(double length, double y0, double startPitchTan, double endPitchTan,
                                         double totalRise, double verticalRadius) {
        VerticalProfile vp = new VerticalProfile(Math.max(0.5D, length), y0, startPitchTan, endPitchTan, totalRise);
        vp.solve(verticalRadius);
        return vp;
    }

    /**
     * Solves the middle grade so that the integrated rise matches
     * {@link #totalRise}, then sizes the two vertical curves as {@code Rv * di}.
     * Falls back to a symmetric two-phase (pure parabolic) profile when the
     * vertical curves would eat the whole length.
     */
    private void solve(double rv) {
        double l = this.length;
        double e1 = l / 3.0D;
        double e2 = l / 3.0D;
        double p1 = 0.0D;
        boolean ok = false;
        for (int i = 0; i < 6; i++) {
            double denom = l - (e1 + e2) * 0.5D;
            if (denom < 1.0E-3D) {
                break;
            }
            p1 = (this.totalRise - this.p0 * e1 * 0.5D - this.p2 * e2 * 0.5D) / denom;
            double lv1 = Math.abs(rv) * Math.abs(p1 - this.p0);
            double lv2 = Math.abs(rv) * Math.abs(p1 - this.p2);
            double ne1 = Math.min(lv1, l / 3.0D);
            double ne2 = Math.min(lv2, l / 3.0D);
            if (Math.abs(ne1 - e1) < 1.0E-4D && Math.abs(ne2 - e2) < 1.0E-4D) {
                e1 = ne1;
                e2 = ne2;
                ok = true;
                break;
            }
            e1 = ne1;
            e2 = ne2;
            ok = true;
        }
        if (!ok || e1 + e2 > l * 0.98D) {
            // Not enough room for a constant grade: blend directly, which is
            // always solvable and still smooth in pitch.
            this.twoPhase = true;
            this.easeIn = l * 0.5D;
            this.easeOut = l * 0.5D;
            this.midPitch = 2.0D * this.totalRise / l - (this.p0 + this.p2) * 0.5D;
            return;
        }
        this.twoPhase = false;
        this.easeIn = Math.max(0.0D, e1);
        this.easeOut = Math.max(0.0D, e2);
        this.midPitch = p1;
    }

    /** Grade (tan of the pitch angle) at arc length s. */
    public double pitchTanAt(double s) {
        double t = Geo.clamp(s, 0.0D, this.length);
        double l1 = this.length - this.easeOut;
        if (this.easeIn > 1.0E-6D && t < this.easeIn) {
            return this.p0 + (this.midPitch - this.p0) * (t / this.easeIn);
        }
        if (t <= l1 || this.easeOut <= 1.0E-6D) {
            return this.midPitch;
        }
        double u = (t - l1) / this.easeOut;
        return this.midPitch + (this.p2 - this.midPitch) * u;
    }

    /** Pitch angle in degrees at arc length s. */
    public double pitchDegAt(double s) {
        return Math.atan(this.pitchTanAt(s)) * Geo.DEG;
    }

    /** Height at arc length s. */
    public double yAt(double s) {
        double t = Geo.clamp(s, 0.0D, this.length);
        double e1 = this.easeIn;
        double e2 = this.easeOut;
        double l1 = this.length - e2;
        double y = this.y0;
        double head = Math.min(t, e1);
        y += this.p0 * head + (this.midPitch - this.p0) * head * head / (2.0D * Math.max(1.0E-9D, e1));
        if (t <= e1) {
            return y;
        }
        double mid = Math.max(0.0D, Math.min(t, l1) - e1);
        y += this.midPitch * mid;
        if (t <= l1) {
            return y;
        }
        double u = t - l1;
        y += this.midPitch * u + (this.p2 - this.midPitch) * u * u / (2.0D * Math.max(1.0E-9D, e2));
        return y;
    }

    /** True when the whole profile is level. */
    public boolean isLevel() {
        return Math.abs(this.p0) < 1.0E-9D && Math.abs(this.midPitch) < 1.0E-9D
                && Math.abs(this.p2) < 1.0E-9D;
    }
}
