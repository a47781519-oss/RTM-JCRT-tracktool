package com.tracktool.rail.plan;

import com.tracktool.util.Geo;

/**
 * Railway transition (easement / clothoid): curvature varies linearly with arc
 * length from {@code k0} to {@code k1}.
 *
 * <p>The position is obtained by numerically integrating
 * {@code dx = sin(theta) ds, dz = cos(theta) ds} with
 * {@code theta(s) = theta0 + k0*s + (k1-k0)*s^2/(2L)} (Simpson, 256 steps),
 * then linearly interpolated. Heading and curvature stay analytic so the
 * generated track has an exact, smooth tangent.</p>
 */
public final class SpiralElement extends Element {

    public final double k0;
    public final double k1;

    private static final int N = 256;
    private final double[] tabX = new double[N + 1];
    private final double[] tabZ = new double[N + 1];

    public SpiralElement(double k0, double k1, double length) {
        super(length);
        this.k0 = k0;
        this.k1 = k1;
    }

    /** Transition length so that the curvature changes by {@code dk} at a rate of 1/rate. */
    public static double lengthForCurvatureChange(double dk, double ratePerMetre) {
        return Math.abs(dk) / Math.max(1.0E-9D, Math.abs(ratePerMetre));
    }

    @Override
    public void placeAt(double x, double z, double yawDeg) {
        super.placeAt(x, z, yawDeg);
        this.buildTable();
    }

    private void buildTable() {
        int n = N;
        double h = this.length / n;
        double yaw0 = this.startYaw * Geo.RAD;
        for (int i = 0; i <= n; i++) {
            double s = i * h;
            double turn = this.k0 * s + (this.k1 - this.k0) * s * s / (2.0D * Math.max(1.0E-9D, this.length));
            double th = yaw0 + turn;
            this.tabX[i] = Math.sin(th);
            this.tabZ[i] = Math.cos(th);
        }
        // Cumulative Simpson / trapezoid integration of the unit tangent.
        double accX = 0.0D;
        double accZ = 0.0D;
        double prevX = this.tabX[0];
        double prevZ = this.tabZ[0];
        this.tabX[0] = 0.0D;
        this.tabZ[0] = 0.0D;
        for (int i = 1; i <= n; i++) {
            double curX = this.tabX[i];
            double curZ = this.tabZ[i];
            accX += (prevX + curX) * 0.5D * h;
            accZ += (prevZ + curZ) * 0.5D * h;
            this.tabX[i] = accX;
            this.tabZ[i] = accZ;
            prevX = curX;
            prevZ = curZ;
        }
    }

    @Override
    public double curvatureAt(double s) {
        if (this.length <= 0.0D) {
            return this.k0;
        }
        double t = this.clampS(s) / this.length;
        return this.k0 + (this.k1 - this.k0) * t;
    }

    @Override
    public void pointAt(double s, double[] out) {
        double t = this.clampS(s);
        if (this.length <= 0.0D) {
            out[0] = this.startX;
            out[1] = this.startZ;
            return;
        }
        double f = t / this.length * N;
        int i = (int) f;
        if (i >= N) {
            i = N - 1;
        }
        double frac = f - i;
        double lx = this.tabX[i] + (this.tabX[i + 1] - this.tabX[i]) * frac;
        double lz = this.tabZ[i] + (this.tabZ[i + 1] - this.tabZ[i]) * frac;
        out[0] = this.startX + lx;
        out[1] = this.startZ + lz;
    }

    @Override
    public String typeKey() {
        return "tracktool.element.spiral";
    }
}
