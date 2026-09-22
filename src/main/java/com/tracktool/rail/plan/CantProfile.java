package com.tracktool.rail.plan;

import com.tracktool.util.Geo;

import java.util.ArrayList;
import java.util.List;

/** Piecewise linear superelevation (cant) profile in degrees, indexed by arc length. */
public final class CantProfile {

    private final double[] s;
    private final double[] c;

    private CantProfile(double[] s, double[] c) {
        this.s = s;
        this.c = c;
    }

    public static CantProfile flat(double length, double cant) {
        return new CantProfile(new double[]{0.0D, length}, new double[]{cant, cant});
    }

    /**
     * The classic railway cant diagram: ramp up over the entry transition,
     * constant through the circular curve, ramp down over the exit transition.
     */
    public static CantProfile rampPlateauRamp(double length, double l1, double l2, double cant) {
        List<double[]> knots = new ArrayList<double[]>();
        knots.add(new double[]{0.0D, 0.0D});
        if (l1 > 1.0E-6D) {
            knots.add(new double[]{l1, cant});
        }
        double l2Start = Math.max(l1, length - l2);
        if (l2Start < length - 1.0E-6D) {
            knots.add(new double[]{l2Start, cant});
        }
        knots.add(new double[]{length, 0.0D});
        double[] ss = new double[knots.size()];
        double[] cc = new double[knots.size()];
        for (int i = 0; i < knots.size(); i++) {
            ss[i] = knots.get(i)[0];
            cc[i] = knots.get(i)[1];
        }
        return new CantProfile(ss, cc);
    }

    public static CantProfile of(double[] lengths, double[] cants) {
        return new CantProfile(lengths.clone(), cants.clone());
    }

    public double cantAt(double sArc) {
        double t = Geo.clamp(sArc, this.s[0], this.s[this.s.length - 1]);
        for (int i = 0; i < this.s.length - 1; i++) {
            if (t <= this.s[i + 1]) {
                double span = this.s[i + 1] - this.s[i];
                if (span <= 1.0E-9D) {
                    return this.c[i + 1];
                }
                double f = (t - this.s[i]) / span;
                return this.c[i] + (this.c[i + 1] - this.c[i]) * f;
            }
        }
        return this.c[this.c.length - 1];
    }

    /** Maximum |cant| of the profile. */
    public double maxAbs() {
        double m = 0.0D;
        for (double v : this.c) {
            m = Math.max(m, Math.abs(v));
        }
        return m;
    }

    public int knotCount() {
        return this.s.length;
    }
}
