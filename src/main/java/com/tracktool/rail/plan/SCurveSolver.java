package com.tracktool.rail.plan;

import com.tracktool.rail.RailEnd;
import com.tracktool.rail.RailStandards;
import com.tracktool.rail.TrackSpec;
import com.tracktool.util.Geo;

import java.util.ArrayList;
import java.util.List;

/**
 * 连接模式的 <b>S 形（反向曲线）解算器</b>：
 * <pre>
 *   直线? – 缓和 – 圆 – 缓和 – 直线? – 缓和 – 圆 – 缓和 – 直线?
 * </pre>
 * 两个圆曲线<b>转向相反</b>，这正是 S 形的定义；三段直线都可以退化为 0。
 *
 * <p><b>什么时候用它</b>：由 {@link #isReverseShape} 判定。判据是选线里的老办法 ——
 * 把弦当基准看两端各自的偏角</p>
 * <pre>
 *   β1 = 弦向 − 起点朝向        （起点要往哪边偏才能看见终点）
 *   β2 = 终点朝向 − 弦向        （到了终点还要再偏多少）
 * </pre>
 * <p>单向弯道（缓和–圆–缓和）必然 β1、β2 <b>同号</b>（一路朝同一边转）；两者<b>反号</b>
 * 就说明线路必须先朝一边弯、再朝另一边弯回来 —— 这就是 S 形。最典型的是
 * "两端平行、横向错开"：此时 Δ=0、β2=−β1，{@link ConnectSolver} 的单弯解算器只能报
 * {@code connect_parallel}，而这里能接上。</p>
 *
 * <p><b>为什么半径与缓和曲线长不让玩家调</b>：S 形有 7 个自由度（三段直线 + 两个转角 +
 * 两个半径），端点只提供 3 个方程。再让玩家塞两个数进来，剩下的自由度既收不敛到唯一解，
 * 也极容易无解。所以这里固定<b>两段等半径 + GB50090 自动缓和曲线长</b>，把自由度降到
 * (α, R) 两个，再<b>取能放得下的最大半径</b> —— 与单弯的自动解算是同一条哲学。
 * 玩家仍能调的只有超高。</p>
 *
 * <p><b>几何</b>用三切线多边形：{@code P1 →(a·u1)→ V1 →(b·um)→ V2 →(c·u2)→ P2}，
 * 其中 V1、V2 是两个弯道各自的交点（PI），um 是中间那条公切线的方向。闭合条件就是</p>
 * <pre>
 *   a·u1 + b·um + c·u2 = P2 − P1            （2 个方程、3 个未知数 ⇒ 解集是一条直线）
 *   a ≥ T1 ,  b ≥ T1 + T2 ,  c ≥ T2         T = (R+p)·tan(|转角|/2) + q
 *   l1 = a − T1 ,  lm = b − T1 − T2 ,  l2 = c − T2      （三段直线，均可为 0）
 * </pre>
 * <p>关键性质：<b>解集直线与 R 无关</b>，只有下界 T1、T2 随 R 单调增大 ⇒ 可行性对 R 单调
 * ⇒ 二分求最大半径是严格正确的，不是碰运气。</p>
 *
 * <p>自检（{@code gradlew sCurveTest}）覆盖了平行错开、小角度反向、大角度反向等情形，
 * 全部要求终点误差 &lt; 1 mm、朝向误差 &lt; 0.01°。</p>
 */
public final class SCurveSolver {

    /** 判定为 S 形所需的最小偏角（度）：比这更小就当共线，交给原来的直线/单弯分支。 */
    private static final double DEAD_DEG = 0.05D;
    /** 单侧转角上限（度）：再大就不是"连接"而是掉头了，交给兜底解。 */
    private static final double MAX_TURN_DEG = 150.0D;
    /** 端点朝向与弦向的夹角上限（度）：超过说明两端基本背离，S 形也救不回来。 */
    private static final double MAX_OFFSET_DEG = 110.0D;

    private static final double MIN_RADIUS = 5.0D;
    private static final double MAX_RADIUS = 50000.0D;

    private SCurveSolver() {
    }

    /** 两端是否构成 S 形（反向曲线）。判据见类注释。 */
    public static boolean isReverseShape(RailEnd from, RailEnd to) {
        // 只看位置与朝向，不碰 rp：调用方（PlanBuilder）已经拦过 isValid，
        // 而不碰 RailPosition 才能让这段判定在离线自检里跑（RTM 类是 SRG 混淆的）。
        if (from == null || to == null) {
            return false;
        }
        double th1 = from.outwardYaw;
        double th2 = Geo.normalize360(to.outwardYaw + 180.0D);
        double vx = to.x - from.x;
        double vz = to.z - from.z;
        if (Math.sqrt(vx * vx + vz * vz) < 0.5D) {
            return false;
        }
        double chordYaw = Geo.yawOf(vx, vz);
        double b1 = Geo.angleDiff(chordYaw, th1);
        double b2 = Geo.angleDiff(th2, chordYaw);
        if (Math.abs(b1) < DEAD_DEG || Math.abs(b2) < DEAD_DEG) {
            return false;                   // 其中一端正对着弦 ⇒ 单弯就能接
        }
        if (Math.abs(b1) > MAX_OFFSET_DEG || Math.abs(b2) > MAX_OFFSET_DEG) {
            return false;                   // 两端基本背离，交给原来的报错路径
        }
        return b1 * b2 < 0.0D;
    }

    /** 一个候选 S 形解。 */
    static final class Fit {
        double alphaDeg;                    // 第一段转角（带符号，正 = 左）
        double gammaDeg;                    // 第二段转角（与 alpha 反号）
        double r;                           // 两段共用的半径
        double ls1;
        double ls2;
        double arc1;
        double arc2;
        double l1;
        double lm;
        double l2;
        double total;
    }

    /**
     * 解一条 S 形连接线；无解时在 {@code plan} 上落错误码并返回 null。
     *
     * @return 已装配好竖曲线与超高的线形（尚未 placeAt）
     */
    public static Alignment solve(TrackSpec spec, RailEnd from, RailEnd to, RailPlan plan) {
        double th1 = from.outwardYaw;
        double th2 = Geo.normalize360(to.outwardYaw + 180.0D);
        double deltaDeg = Geo.angleDiff(th2, th1);
        double vx = to.x - from.x;
        double vz = to.z - from.z;
        double chord = Math.sqrt(vx * vx + vz * vz);
        if (chord < 0.5D) {
            plan.addError("tracktool.err.too_close", null);
            return null;
        }
        // 转到"起点朝向为 +Z"的局部坐标：x = 左向分量，z = 前向分量
        double vn = vx * Geo.leftX(th1) + vz * Geo.leftZ(th1);
        double vf = vx * Geo.dirX(th1) + vz * Geo.dirZ(th1);

        Fit best = null;
        // 两个分支：第一段左转（α>0，则 γ<0）或右转（α<0，则 γ>0）。都试，取半径大的。
        for (int branch = 0; branch < 2; branch++) {
            double lo;
            double hi;
            if (branch == 0) {
                lo = Math.max(0.0D, deltaDeg) + 0.05D;
                hi = MAX_TURN_DEG;
            } else {
                lo = -MAX_TURN_DEG;
                hi = Math.min(0.0D, deltaDeg) - 0.05D;
            }
            if (hi - lo < 0.01D) {
                continue;
            }
            Fit b = searchBranch(lo, hi, deltaDeg, vn, vf);
            if (b != null && (best == null || better(b, best))) {
                best = b;
            }
        }
        if (best == null) {
            plan.addError("tracktool.err.connect_s_failed", null);
            return null;
        }
        if (best.total > chord * 6.0D) {
            plan.warnDetour = true;
            plan.detourRatio = best.total / chord;
        }

        double k1 = (best.alphaDeg >= 0.0D ? 1.0D : -1.0D) / best.r;
        double k2 = (best.gammaDeg >= 0.0D ? 1.0D : -1.0D) / best.r;
        List<Element> els = new ArrayList<Element>();
        if (best.l1 > 0.01D) {
            els.add(new LineElement(best.l1));
        }
        els.add(new SpiralElement(0.0D, k1, best.ls1));
        els.add(new ArcElement(1.0D / k1, best.arc1));
        els.add(new SpiralElement(k1, 0.0D, best.ls1));
        if (best.lm > 0.01D) {
            els.add(new LineElement(best.lm));
        }
        els.add(new SpiralElement(0.0D, k2, best.ls2));
        els.add(new ArcElement(1.0D / k2, best.arc2));
        els.add(new SpiralElement(k2, 0.0D, best.ls2));
        if (best.l2 > 0.01D) {
            els.add(new LineElement(best.l2));
        }

        Alignment a = assemble(spec, from, to, best, els);
        // ★ 与单弯解算同一条铁律：接不上的线宁可不铺
        if (!ConnectSolver.closes(a, from, to)) {
            plan.addError("tracktool.err.connect_s_failed", null);
            return null;
        }
        plan.sCurve = true;
        plan.solvedRadius = best.r;
        plan.solvedTransition = Math.min(best.ls1, best.ls2);
        return a;
    }

    /**
     * 诊断用（{@code SCurveSelfTest}）：固定第一段转角时能放下的最大半径，放不下返回 -1。
     * {@code vn/vf} 是终点在"起点朝向为 +Z"局部坐标里的左向 / 前向分量。
     */
    public static double maxRadiusAt(double alphaDeg, double deltaDeg, double vn, double vf) {
        Fit f = fitMaxRadius(alphaDeg, deltaDeg, vn, vf, 40);
        return f == null ? -1.0D : f.r;
    }

    /** 诊断用：给定转角与半径时的分段长 {ls1, arc1, l1, lm, l2}；放不下返回 null。 */
    public static double[] probe(double alphaDeg, double deltaDeg, double vn, double vf, double r) {
        Fit f = fit(alphaDeg, deltaDeg, vn, vf, r);
        return f == null ? null : new double[]{f.ls1, f.arc1, f.l1, f.lm, f.l2};
    }

    /** 半径大的优先；半径相当时取更短的。 */
    private static boolean better(Fit a, Fit b) {
        if (a.r > b.r * 1.001D) {
            return true;
        }
        if (b.r > a.r * 1.001D) {
            return false;
        }
        return a.total < b.total;
    }

    /** 在一个转角区间里粗扫 + 细化，找"能放下的最大半径"对应的那个 α。 */
    private static Fit searchBranch(double lo, double hi, double deltaDeg, double vn, double vf) {
        Fit best = null;
        double bestAlpha = 0.0D;
        int coarse = 48;
        for (int i = 0; i <= coarse; i++) {
            double alpha = lo + (hi - lo) * i / coarse;
            Fit f = fitMaxRadius(alpha, deltaDeg, vn, vf, 18);
            if (f != null && (best == null || better(f, best))) {
                best = f;
                bestAlpha = alpha;
            }
        }
        if (best == null) {
            return null;
        }
        double span = (hi - lo) / coarse;
        for (int pass = 0; pass < 3; pass++) {
            for (int i = -8; i <= 8; i++) {
                double alpha = bestAlpha + span * i / 8.0D;
                if (alpha < lo || alpha > hi) {
                    continue;
                }
                Fit f = fitMaxRadius(alpha, deltaDeg, vn, vf, 22);
                if (f != null && better(f, best)) {
                    best = f;
                    bestAlpha = alpha;
                }
            }
            span *= 0.35D;
        }
        // 胜出的 α 再用高精度重算一次，作为最终结果
        Fit fin = fitMaxRadius(bestAlpha, deltaDeg, vn, vf, 34);
        return fin != null ? fin : best;
    }

    /**
     * 固定 α，二分出能放下的最大半径。
     *
     * <p><b>注意：可行半径是一段区间，不是半直线。</b>上端由切线长卡住
     * （R 越大 T 越长，这一段确实单调）；<b>下端</b>却被缓和曲线的 0.5 m 下限卡住：
     * 转角小时 {@code R·α} 本来就短，两条 0.5 m 缓和曲线一上来圆曲线就没了。
     * 所以不能拿 {@code MIN_RADIUS} 当天然可行的二分下端——那样会把所有小转角
     * （也就是大半径）的解全部误判为无解。</p>
     */
    private static Fit fitMaxRadius(double alphaDeg, double deltaDeg, double vn, double vf, int iters) {
        double tmin = Math.min(Math.abs(alphaDeg), Math.abs(deltaDeg - alphaDeg)) * Geo.RAD;
        if (tmin <= 0.0D) {
            return null;
        }
        // 圆曲线不被吃光的解析下界：缓和曲线取下限 0.5 m 时需 R·α > 0.51
        double lo = Math.max(MIN_RADIUS, 0.55D / tmin);
        double hi = MAX_RADIUS;
        if (fit(alphaDeg, deltaDeg, vn, vf, lo) == null) {
            // 解析下界还不行（切线长还没追上），沿对数阶梯往上找一个可行点
            double seed = -1.0D;
            int rungs = 28;
            double stepFactor = Math.pow(MAX_RADIUS / lo, 1.0D / rungs);
            double r = lo;
            for (int i = 0; i <= rungs; i++, r *= stepFactor) {
                if (fit(alphaDeg, deltaDeg, vn, vf, r) != null) {
                    seed = r;
                    break;
                }
            }
            if (seed < 0.0D) {
                return null;                // 这个 α 上真的无解
            }
            lo = seed;
        }
        for (int i = 0; i < iters; i++) {
            double mid = 0.5D * (lo + hi);
            if (fit(alphaDeg, deltaDeg, vn, vf, mid) != null) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return fit(alphaDeg, deltaDeg, vn, vf, lo);
    }

    /**
     * 给定第一段转角 α 与半径 R，求三段直线；放不下就返回 null。
     *
     * <p>局部坐标以起点朝向为 +Z：{@code u1=(0,1)}、{@code um=(sinα,cosα)}、{@code u2=(sinΔ,cosΔ)}。</p>
     */
    static Fit fit(double alphaDeg, double deltaDeg, double vn, double vf, double r) {
        double gammaDeg = deltaDeg - alphaDeg;
        if (alphaDeg * gammaDeg >= 0.0D) {
            return null;                    // 同向 ⇒ 不是 S
        }
        double a1 = Math.abs(alphaDeg);
        double a2 = Math.abs(gammaDeg);
        if (a1 < 0.01D || a2 < 0.01D || a1 > MAX_TURN_DEG || a2 > MAX_TURN_DEG) {
            return null;
        }
        double t1rad = a1 * Geo.RAD;
        double t2rad = a2 * Geo.RAD;
        double ls1 = autoSpiral(r, t1rad);
        double ls2 = autoSpiral(r, t2rad);
        double[] pq1 = ConnectSolver.spiralShift(r, ls1);
        double[] pq2 = ConnectSolver.spiralShift(r, ls2);
        double tan1 = (r + pq1[0]) * Math.tan(t1rad * 0.5D) + pq1[1];
        double tan2 = (r + pq2[0]) * Math.tan(t2rad * 0.5D) + pq2[1];

        double umx = Geo.dirX(alphaDeg);
        double umz = Geo.dirZ(alphaDeg);
        double u2x = Geo.dirX(deltaDeg);
        double u2z = Geo.dirZ(deltaDeg);
        double d12 = cross(0.0D, 1.0D, umx, umz);
        double d23 = cross(umx, umz, u2x, u2z);
        double d13 = cross(0.0D, 1.0D, u2x, u2z);

        // 通解 = 特解 + t·核；核 = (cross(um,u2), cross(u2,u1), cross(u1,um))
        double n1 = d23;
        double n2 = -d13;
        double n3 = d12;
        double a0;
        double b0;
        double c0;
        double m12 = Math.abs(d12);
        double m23 = Math.abs(d23);
        double m13 = Math.abs(d13);
        if (m12 >= m23 && m12 >= m13) {              // 用 u1、um 解，c = 0
            if (m12 < 1.0E-9D) {
                return null;
            }
            a0 = cross(vn, vf, umx, umz) / d12;
            b0 = cross(0.0D, 1.0D, vn, vf) / d12;
            c0 = 0.0D;
        } else if (m23 >= m13) {                     // 用 um、u2 解，a = 0
            if (m23 < 1.0E-9D) {
                return null;
            }
            a0 = 0.0D;
            b0 = cross(vn, vf, u2x, u2z) / d23;
            c0 = cross(umx, umz, vn, vf) / d23;
        } else {                                     // 用 u1、u2 解，b = 0
            if (m13 < 1.0E-9D) {
                return null;
            }
            a0 = cross(vn, vf, u2x, u2z) / d13;
            b0 = 0.0D;
            c0 = cross(0.0D, 1.0D, vn, vf) / d13;
        }

        // 三个下界各自给出 t 的一段区间，求交
        double[] lim = {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
        lim = bound(a0, n1, tan1, lim);
        lim = bound(b0, n2, tan1 + tan2, lim);
        lim = bound(c0, n3, tan2, lim);
        double tlo = lim[0];
        double thi = lim[1];
        if (tlo > thi + 1.0E-7D) {
            return null;
        }
        // 直线总长随 t 线性变化，最优必在区间端点
        double slope = n1 + n2 + n3;
        double t = slope > 0.0D ? tlo : thi;
        if (Double.isInfinite(t) || Double.isNaN(t)) {
            t = slope > 0.0D ? thi : tlo;
        }
        if (Double.isInfinite(t) || Double.isNaN(t)) {
            t = 0.0D;
        }
        double a = a0 + t * n1;
        double b = b0 + t * n2;
        double c = c0 + t * n3;
        double l1 = a - tan1;
        double lm = b - tan1 - tan2;
        double l2 = c - tan2;
        if (l1 < -0.02D || lm < -0.02D || l2 < -0.02D) {
            return null;
        }
        double arc1 = r * t1rad - ls1;
        double arc2 = r * t2rad - ls2;
        if (arc1 <= 0.01D || arc2 <= 0.01D) {
            return null;
        }
        Fit f = new Fit();
        f.alphaDeg = alphaDeg;
        f.gammaDeg = gammaDeg;
        f.r = r;
        f.ls1 = ls1;
        f.ls2 = ls2;
        f.arc1 = arc1;
        f.arc2 = arc2;
        f.l1 = Math.max(0.0D, l1);
        f.lm = Math.max(0.0D, lm);
        f.l2 = Math.max(0.0D, l2);
        f.total = f.l1 + ls1 + arc1 + ls1 + f.lm + ls2 + arc2 + ls2 + f.l2;
        return f;
    }

    /** 把约束 {@code v0 + t·n ≥ minValue} 并进 t 的可行区间。 */
    private static double[] bound(double v0, double n, double minValue, double[] cur) {
        if (Math.abs(n) < 1.0E-12D) {
            if (v0 < minValue - 1.0E-6D) {
                return new double[]{1.0D, -1.0D};   // 空区间
            }
            return cur;
        }
        double t = (minValue - v0) / n;
        if (n > 0.0D) {
            return new double[]{Math.max(cur[0], t), cur[1]};
        }
        return new double[]{cur[0], Math.min(cur[1], t)};
    }

    private static double cross(double x1, double z1, double x2, double z2) {
        return x1 * z2 - z1 * x2;
    }

    /**
     * S 形的缓和曲线长：<b>固定走 GB50090 自动表</b>，不看玩家填的那个数
     * （S 形里缓和曲线长不可调，原因见类注释）。上限仍按 {@code 0.45·转角·R} 夹紧，
     * 保证圆曲线段不会被两条缓和曲线吃光。
     */
    private static double autoSpiral(double r, double turnRad) {
        double ls = RailStandards.easementLength(160, Math.max(1.0D, r));
        double maxSpiral = turnRad * r * 0.45D;
        return Geo.clamp(ls, 0.5D, Math.max(0.5D, maxSpiral));
    }

    /**
     * 竖曲线 + 超高。
     *
     * <p>超高必须<b>跟着曲率符号走</b>：第一段是左弯就一个方向、第二段右弯就反向，
     * 中间（第一弯的出缓和曲线 → 第二弯的入缓和曲线）必须<b>经过 0</b>。
     * 直接在两端之间线性插值是错的 —— 那会让其中一段弯道的超高方向反过来，
     * 车过去就是"外轨往里倒"。</p>
     */
    private static Alignment assemble(TrackSpec spec, RailEnd from, RailEnd to, Fit f, List<Element> els) {
        double total = f.total;
        double p0 = Math.tan(from.outwardPitch * Geo.RAD);
        double pe = Math.tan(-to.outwardPitch * Geo.RAD);
        VerticalProfile vp = VerticalProfile.create(total, from.y, p0, pe, to.y - from.y, spec.verticalRadius);

        double inv = spec.cantInvert ? -1.0D : 1.0D;
        double base = spec.cantMagnitude();
        double mag1;
        double mag2;
        if (spec.connectCantAdaptive) {
            // 自适应：两段各自跟着自己那一端既有的超高走，输入框里的数不参与
            mag1 = Math.abs(from.jointCant);
            mag2 = Math.abs(to.existingCant);
        } else {
            // 与单弯一致："不得小于两端"，免得接头处先扭平再扭回去
            mag1 = Math.max(base, Math.abs(from.jointCant));
            mag2 = Math.max(base, Math.abs(to.existingCant));
        }
        double peak1 = mag1 * (f.alphaDeg > 0.0D ? -1.0D : 1.0D) * inv;
        double peak2 = mag2 * (f.gammaDeg > 0.0D ? -1.0D : 1.0D) * inv;

        double s1 = f.l1;
        double s2 = s1 + f.ls1;
        double s3 = s2 + f.arc1;
        double s4 = s3 + f.ls1;
        double s5 = s4 + f.lm;
        double s6 = s5 + f.ls2;
        double s7 = s6 + f.arc2;
        double[] ss = {0.0D, s1, s2, s3, s4, s5, s6, s7, total};
        double[] cc = {from.jointCant, from.jointCant, peak1, peak1, 0.0D, 0.0D,
                       peak2, peak2, to.existingCant};
        for (int i = 1; i < ss.length; i++) {
            if (ss[i] <= ss[i - 1]) {
                ss[i] = ss[i - 1] + 1.0E-4D;
            }
        }
        ss[ss.length - 1] = Math.max(ss[ss.length - 1], total);
        return new Alignment(els, vp, CantProfile.of(ss, cc));
    }
}
