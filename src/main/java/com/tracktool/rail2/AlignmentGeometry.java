package com.tracktool.rail2;

/** 解析线形：缓和曲线 + 圆曲线 + 缓和曲线（水平），配竖曲线纵向剖面与超高顺坡。
 *  A 方案里"几何完全自定"的实现体：所有量按里程 t 解析/数值积分求出，无任何格点量化。
 *  yaw/pitch 单位为度（与 RTM 字节码确认一致），roll 为超高比值。 */
public final class AlignmentGeometry implements ExactRailGeometry {

    private static final double STEP = 0.05D;

    private final double radius;
    private final double riseM;
    private final double verticalR;
    private final double dir;
    private final double cantRatio;
    private final double spiralLen;
    private final double arcLen;
    private final double totalLen;

    private final double[] tabX;
    private final double[] tabZ;
    private final double[] tabYaw;

    public AlignmentGeometry(double radius, double turnDeg, double spiralLen, double cantMm,
                            double riseM, double verticalR, double dir, double yaw0Deg) {
        this.radius = Math.max(1.0D, radius);
        this.riseM = riseM;
        this.verticalR = verticalR;
        this.dir = dir < 0.0D ? -1.0D : 1.0D;
        this.cantRatio = cantMm / 1000.0D;

        double turn = Math.toRadians(Math.max(1.0E-3D, turnDeg));
        double ls = Math.max(0.0D, spiralLen);
        double spiralTurn = ls > 0.0D ? ls / (2.0D * this.radius) : 0.0D;
        double arcTurn = turn - 2.0D * spiralTurn;
        if (arcTurn < 0.0D && spiralTurn > 0.0D) {
            ls = ls * ((turn * 0.5D) / spiralTurn);
            spiralTurn = ls / (2.0D * this.radius);
            arcTurn = Math.max(0.0D, turn - 2.0D * spiralTurn);
        }
        this.spiralLen = ls;
        this.arcLen = this.radius * Math.max(0.0D, arcTurn);
        this.totalLen = 2.0D * ls + this.arcLen;

        int n = (int) Math.ceil(this.totalLen / STEP) + 2;
        this.tabX = new double[n];
        this.tabZ = new double[n];
        this.tabYaw = new double[n];
        double x = 0.0D;
        double z = 0.0D;
        double yaw = Math.toRadians(yaw0Deg);
        for (int i = 0; i < n; i++) {
            this.tabX[i] = x;
            this.tabZ[i] = z;
            this.tabYaw[i] = yaw;
            double k = curvature(i * STEP + STEP * 0.5D);
            yaw += k * STEP;
            x += Math.sin(yaw) * STEP;
            z += Math.cos(yaw) * STEP;
        }
    }

    private double curvature(double t) {
        double k = 1.0D / radius;
        if (spiralLen <= 1.0E-9D) {
            return (t >= 0.0D && t <= totalLen) ? k * dir : 0.0D;
        }
        if (t <= 0.0D) {
            return 0.0D;
        }
        if (t < spiralLen) {
            return k * (t / spiralLen) * dir;
        }
        if (t < spiralLen + arcLen) {
            return k * dir;
        }
        double rest = totalLen - t;
        return rest < spiralLen ? k * Math.max(0.0D, rest / spiralLen) * dir : 0.0D;
    }

    private double lerp(double[] arr, double t) {
        t = Math.max(0.0D, Math.min(totalLen, t));
        int i = (int) (t / STEP);
        if (i >= arr.length - 1) {
            return arr[arr.length - 1];
        }
        double f = (t - i * STEP) / STEP;
        return arr[i] + (arr[i + 1] - arr[i]) * f;
    }

    @Override
    public double length() {
        return totalLen;
    }

    @Override
    public double x(double t) {
        return lerp(tabX, t);
    }

    @Override
    public double z(double t) {
        return lerp(tabZ, t);
    }

    @Override
    public double height(double t) {
        t = Math.max(0.0D, Math.min(totalLen, t));
        if (Math.abs(riseM) < 1.0E-9D || totalLen <= 1.0E-9D) {
            return 0.0D;
        }
        double grade = riseM / totalLen;
        double lv = verticalR > 0.0D ? Math.min(totalLen * 0.5D, verticalR * Math.abs(grade)) : 0.0D;
        if (lv < 1.0E-6D) {
            return riseM * (t / totalLen);
        }
        double half = lv * 0.5D;
        if (t <= half) {
            return grade * t * t / lv;
        }
        if (t >= totalLen - half) {
            double rest = totalLen - t;
            return riseM - grade * rest * rest / lv;
        }
        return grade * half * half / lv + grade * (t - half);
    }

    @Override
    public double yaw(double t) {
        return Math.toDegrees(lerp(tabYaw, t));
    }

    @Override
    public double pitch(double t) {
        t = Math.max(0.0D, Math.min(totalLen, t));
        if (Math.abs(riseM) < 1.0E-9D || totalLen <= 1.0E-9D) {
            return 0.0D;
        }
        double grade = riseM / totalLen;
        double lv = verticalR > 0.0D ? Math.min(totalLen * 0.5D, verticalR * Math.abs(grade)) : 0.0D;
        double slope = grade;
        if (lv > 1.0E-6D) {
            double half = lv * 0.5D;
            if (t <= half) {
                slope = grade * t / lv;
            } else if (t >= totalLen - half) {
                slope = grade * (totalLen - t) / lv;
            }
        }
        return Math.toDegrees(Math.atan(slope));
    }

    @Override
    public double roll(double t) {
        t = Math.max(0.0D, Math.min(totalLen, t));
        if (Math.abs(cantRatio) < 1.0E-9D) {
            return 0.0D;
        }
        if (spiralLen <= 1.0E-9D) {
            return cantRatio;
        }
        if (t < spiralLen) {
            return cantRatio * (t / spiralLen);
        }
        if (t < spiralLen + arcLen) {
            return cantRatio;
        }
        double rest = Math.max(0.0D, totalLen - t);
        return cantRatio * Math.min(1.0D, rest / spiralLen);
    }

    public double arcLength() {
        return arcLen;
    }

    public double effectiveSpiralLength() {
        return spiralLen;
    }
}