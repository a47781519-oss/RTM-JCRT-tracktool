package com.tracktool.rail;

import com.tracktool.util.Geo;

/**
 * Chinese railway reference values from GB 50090-2006, used as GUI defaults and
 * as the reference table shown while editing.
 *
 * <p>RealTrainMod never validates any of this - it is guidance for the player,
 * and every value can be overridden. Sources:</p>
 * <ul>
 *   <li>table 3.1.5-1 "easement length" (preferred values) - 160/140/120 km/h only,</li>
 *   <li>table 3.1.5-2 "minimum easement length" (general / difficult) - all speeds,</li>
 *   <li>table 3.1.2 minimum curve radius,</li>
 *   <li>equilibrium superelevation {@code h = 11.8 V^2 / R}, based on a
 *       rail-centreline distance of 1500 mm, capped at 150 mm and quantised to
 *       5 mm,</li>
 *   <li>vertical curves: {@code Rv = 15000 m} at 160 km/h when the grade break
 *       exceeds 1 per mille, {@code Rv = 10000 m} below 160 km/h above 3 per
 *       mille, with {@code L = Rv * |di| / 1000}.</li>
 * </ul>
 */
public final class RailStandards {

    /** Design speeds with a preferred-value table, plus the fallback speeds. */
    public static final int[] SPEEDS = {160, 140, 120, 100, 80};

    private static final double[][] PREF_R = {
            {1600, 1800, 2000, 2500, 2800, 3000, 3500, 4000, 4500, 5000, 6000, 7000, 8000, 10000, 12000},
            {1200, 1400, 1600, 1800, 2000, 2500, 2800, 3000, 3500, 4000, 4500, 5000, 6000, 7000, 8000, 10000, 12000},
            {800, 1000, 1200, 1400, 1600, 1800, 2000, 2500, 2800, 3000, 3500, 4000, 4500, 5000, 6000, 7000, 8000, 10000, 12000},
            null,
            null
    };

    private static final double[][] PREF_L = {
            {190, 170, 150, 120, 110, 100, 90, 80, 70, 70, 70, 70, 60, 50, 40},
            {190, 150, 130, 120, 100, 90, 90, 80, 70, 60, 60, 60, 50, 50, 40, 40, 40},
            {180, 140, 120, 100, 90, 80, 70, 60, 60, 50, 50, 50, 40, 40, 40, 40, 40, 40, 40},
            null,
            null
    };

    private static final double[][] MIN_R = {
            {1600, 1800, 2000, 2500, 2800, 3000, 3500, 4000, 4500, 5000, 6000, 7000, 8000, 10000, 12000},
            {1200, 1400, 1600, 1800, 2000, 2500, 2800, 3000, 3500, 4000, 4500, 5000, 6000, 7000, 8000, 10000, 12000},
            {800, 1000, 1200, 1400, 1600, 1800, 2000, 2500, 2800, 3000, 3500, 4000, 4500, 5000, 6000, 7000, 8000, 10000, 12000},
            {550, 600, 700, 800, 1000, 1200, 1400, 1600, 1800, 2000, 2500, 2800, 3000, 3500, 4000, 4500, 5000, 6000, 7000, 8000, 10000, 12000},
            {500, 550, 600, 700, 800, 1000, 1200, 1400, 1600, 1800, 2000, 2500, 2800, 3000, 3500, 4000, 4500, 5000, 6000, 7000, 8000, 10000, 12000}
    };

    private static final double[][] MIN_L_NORMAL = {
            {170, 160, 140, 110, 100, 90, 90, 80, 70, 70, 70, 70, 60, 50, 40},
            {150, 130, 110, 100, 90, 80, 80, 70, 70, 60, 60, 60, 50, 50, 40, 30, 20},
            {150, 120, 90, 80, 70, 70, 60, 60, 50, 50, 50, 50, 40, 40, 30, 30, 20, 20, 20},
            {130, 120, 100, 80, 70, 60, 60, 50, 50, 50, 40, 40, 40, 40, 30, 30, 20, 20, 20, 20, 20, 20},
            {60, 60, 60, 50, 50, 40, 40, 40, 40, 30, 30, 30, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20}
    };

    private static final double[][] MIN_L_HARD = {
            {160, 140, 120, 100, 90, 80, 70, 70, 60, 60, 50, 50, 50, 40, 40},
            {130, 110, 100, 80, 80, 70, 60, 50, 50, 40, 40, 40, 30, 30, 30, 20, 20},
            {130, 100, 80, 70, 60, 60, 50, 40, 40, 40, 40, 30, 30, 30, 20, 20, 20, 20, 20},
            {110, 100, 90, 70, 60, 50, 40, 40, 40, 40, 30, 30, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20},
            {60, 50, 50, 40, 40, 30, 30, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20}
    };

    /** Minimum curve radius {general, difficult} per design speed (table 3.1.2). */
    private static final double[][] MIN_CURVE_RADIUS = {
            {2000, 1600},
            {1600, 1200},
            {1200, 800},
            {800, 600},
            {600, 500}
    };

    /** Rail centreline distance used by GB 50090, mm. */
    public static final double CANT_BASE_MM = 1500.0D;
    /** Maximum superelevation, mm. */
    public static final double MAX_CANT_MM = 150.0D;
    /** Minimum superelevation, mm. */
    public static final double MIN_CANT_MM = 5.0D;
    /** Superelevation is always a multiple of this, mm. */
    public static final double CANT_STEP_MM = 5.0D;
    /** Allowed cant deficiency, mm (general / difficult). */
    public static final double[] CANT_DEFICIENCY = {70.0D, 90.0D};
    /** Preferred cant gradient (superelevation ramp), per mille. */
    public static final double PREFERRED_CANT_GRADIENT = 1.0D;
    /** Vertical curve radius required at 160 km/h once the grade break exceeds 1 per mille. */
    public static final double DEFAULT_VERTICAL_RADIUS = 15000.0D;
    /** Vertical curve radius required below 160 km/h once the break exceeds 3 per mille. */
    public static final double VERTICAL_RADIUS_SLOW = 10000.0D;
    /** Standard gauge fallback, mm. */
    public static final double DEFAULT_GAUGE_MM = 1435.0D;

    private RailStandards() {
    }

    /** Index into {@link #SPEEDS} of the closest tabulated design speed. */
    public static int speedIndex(int kmh) {
        int best = 0;
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < SPEEDS.length; i++) {
            int d = Math.abs(SPEEDS[i] - kmh);
            if (d < bestDiff) {
                bestDiff = d;
                best = i;
            }
        }
        return best;
    }

    /** {general, difficult} minimum curve radius for a design speed. */
    public static double[] minimumCurveRadius(int speedKmh) {
        return MIN_CURVE_RADIUS[speedIndex(speedKmh)].clone();
    }

    /**
     * Preferred easement length of table 3.1.5-1, linearly interpolated between
     * tabulated radii, rounded up to 10 m with a floor of 20 m. Speeds without a
     * preferred table (100/80 km/h) fall back to table 3.1.5-2.
     */
    public static double easementLength(int speedKmh, double radius) {
        int si = speedIndex(speedKmh);
        if (PREF_R[si] == null) {
            return minimumEasementLength(speedKmh, radius, false);
        }
        return interpolate(PREF_R[si], PREF_L[si], radius);
    }

    /** Minimum easement length of table 3.1.5-2. */
    public static double minimumEasementLength(int speedKmh, double radius, boolean difficult) {
        int si = speedIndex(speedKmh);
        double[] r = MIN_R[si];
        double[] l = difficult ? MIN_L_HARD[si] : MIN_L_NORMAL[si];
        return interpolate(r, l, radius);
    }

    private static double interpolate(double[] radii, double[] values, double radius) {
        double out;
        if (radius <= radii[0]) {
            // Below the tabulated range the design speed itself is not valid;
            // the smallest tabulated easement is the best available guidance.
            out = values[0];
        } else if (radius >= radii[radii.length - 1]) {
            out = values[values.length - 1];
        } else {
            out = values[values.length - 1];
            for (int i = 0; i < radii.length - 1; i++) {
                if (radius <= radii[i + 1]) {
                    double f = (radius - radii[i]) / (radii[i + 1] - radii[i]);
                    out = values[i] + (values[i + 1] - values[i]) * f;
                    break;
                }
            }
        }
        out = Math.ceil(out / 10.0D) * 10.0D;
        return Math.max(20.0D, out);
    }

    /** {radii, easement lengths} of the reference table for a design speed. */
    public static double[][] tableForSpeed(int speedKmh) {
        int si = speedIndex(speedKmh);
        double[] r = PREF_R[si] != null ? PREF_R[si] : MIN_R[si];
        double[] l = PREF_R[si] != null ? PREF_L[si] : MIN_L_NORMAL[si];
        return new double[][]{r.clone(), l.clone()};
    }

    /** True when the design speed has a preferred-value table (160/140/120). */
    public static boolean hasPreferredTable(int speedKmh) {
        return PREF_R[speedIndex(speedKmh)] != null;
    }

    /**
     * Equilibrium superelevation, mm: {@code h = 11.8 V^2 / R}. The coefficient
     * comes from a rail-centreline distance of {@value #CANT_BASE_MM} mm
     * (1500 / (3.6^2 * 9.81) = 11.8). Capped at 150 mm and rounded to 5 mm.
     */
    public static double equilibriumCantMm(int speedKmh, double radius) {
        if (radius <= 1.0D) {
            return MAX_CANT_MM;
        }
        double h = 11.8D * speedKmh * speedKmh / radius;
        h = Math.min(MAX_CANT_MM, h);
        h = Math.round(h / CANT_STEP_MM) * CANT_STEP_MM;
        return Math.max(MIN_CANT_MM, h);
    }

    /** Converts a cant in mm into RTM's cant angle in degrees. */
    public static double cantMmToDegrees(double mm, double gaugeMm) {
        double g = gaugeMm <= 1.0D ? CANT_BASE_MM : gaugeMm;
        double ratio = Geo.clamp(mm / g, -0.9D, 0.9D);
        return Math.asin(ratio) * Geo.DEG;
    }

    /** Converts an RTM cant angle in degrees back into millimetres of raise. */
    public static double cantDegreesToMm(double deg, double gaugeMm) {
        double g = gaugeMm <= 1.0D ? CANT_BASE_MM : gaugeMm;
        return Math.sin(deg * Geo.RAD) * g;
    }

    /** Cant gradient implied by a cant and a transition length, per mille. */
    public static double cantGradientPermille(double cantDeg, double gaugeMm, double transitionLength) {
        if (transitionLength <= 0.1D) {
            return 0.0D;
        }
        double mm = Math.abs(cantDegreesToMm(cantDeg, gaugeMm));
        return mm / transitionLength;
    }

    /**
     * Transition length implied by a cant and a cant gradient: with the cant in
     * millimetres and the gradient in per mille, {@code L[m] = h[mm] / i}.
     */
    public static double transitionForGradient(double cantDeg, double gaugeMm, double gradientPermille) {
        double i = Math.abs(gradientPermille) < 1.0E-6D ? PREFERRED_CANT_GRADIENT : Math.abs(gradientPermille);
        double mm = Math.abs(cantDegreesToMm(cantDeg, gaugeMm));
        return mm / i;
    }

    /**
     * Vertical curve length for a grade break: {@code L = Rv * |di| / 1000} with
     * the grade break expressed in per mille.
     */
    public static double verticalCurveLength(double radius, double gradeChangePermille) {
        return Math.abs(radius) * Math.abs(gradeChangePermille) / 1000.0D;
    }
}
