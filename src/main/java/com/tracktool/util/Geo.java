package com.tracktool.util;

/** Small angle / vector helpers shared by client and server (must stay deterministic). */
public final class Geo {

    private Geo() {
    }

    public static final double DEG = 180.0D / Math.PI;
    public static final double RAD = Math.PI / 180.0D;

    /**
     * RTM yaw of a direction vector. RTM measures the yaw of a rail from +Z towards +X,
     * i.e. {@code dir = (sin(yaw), cos(yaw))} in (X, Z) and {@code yaw = atan2(dx, dz)}.
     */
    public static double yawOf(double dx, double dz) {
        return normalize360(Math.atan2(dx, dz) * DEG);
    }

    public static double dirX(double yawDeg) {
        return Math.sin(yawDeg * RAD);
    }

    public static double dirZ(double yawDeg) {
        return Math.cos(yawDeg * RAD);
    }

    /** Normal to the left of a heading: {@code up x forward}. */
    public static double leftX(double yawDeg) {
        return Math.cos(yawDeg * RAD);
    }

    public static double leftZ(double yawDeg) {
        return -Math.sin(yawDeg * RAD);
    }

    public static double normalize360(double deg) {
        double d = deg % 360.0D;
        if (d < 0.0D) {
            d += 360.0D;
        }
        return d;
    }

    /** Wrap to [-180, 180), matching NGTMath.wrapAngle. */
    public static float wrapAngle(float deg) {
        float d = deg % 360.0F;
        if (d >= 180.0F) {
            d -= 360.0F;
        }
        if (d < -180.0F) {
            d += 360.0F;
        }
        return d;
    }

    public static double wrapAngle(double deg) {
        double d = deg % 360.0D;
        if (d >= 180.0D) {
            d -= 360.0D;
        }
        if (d < -180.0D) {
            d += 360.0D;
        }
        return d;
    }

    /** Shortest signed difference a-b in degrees, in [-180, 180). */
    public static double angleDiff(double a, double b) {
        return wrapAngle(a - b);
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    public static double dist2(double x0, double z0, double x1, double z1) {
        double dx = x1 - x0;
        double dz = z1 - z0;
        return dx * dx + dz * dz;
    }

    /** Round half away from zero: the single rounding policy of the whole mod. */
    public static int roundHalfUp(double v) {
        return (int) Math.floor(Math.abs(v) + 0.5D) * (v < 0.0D ? -1 : 1);
    }

    public static double roundHalfUpD(double v, int decimals) {
        double f = Math.pow(10.0D, decimals);
        return Math.floor(Math.abs(v) * f + 0.5D) / f * (v < 0.0D ? -1 : 1);
    }
}
