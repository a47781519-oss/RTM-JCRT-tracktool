package com.tracktool.rail.plan;

import com.tracktool.rail.RailEnd;
import com.tracktool.rail.RailStandards;
import com.tracktool.rail.TrackSpec;
import com.tracktool.util.Geo;

import java.util.ArrayList;
import java.util.List;

/**
 * 连接模式：把两个已选端点用一条<b>直线 – 缓和曲线 – 圆曲线 – 缓和曲线 – 直线</b>的线路连起来。
 *
 * <p>这正是铁路选线里最常规的做法：两端的切线延长后交于一点（交点 PI），在交点两侧各留出
 * 切线长 T，中间放一个"缓和+圆+缓和"的弯道。几何关系（标准公式，其中 p/q 由本模组的
 * 缓和曲线实体实测得出，不用级数近似）：</p>
 *
 * <pre>
 *   T = (R + p)·tan(Δ/2) + q
 *   p = 圆曲线因缓和曲线产生的内移量      q = 缓和曲线的切线增量
 *   直线段长 = 端点到交点的距离 − T
 * </pre>
 *
 * <ul>
 *   <li><b>自动解算</b>（{@code connectAutoSolve}）：二分求<b>能放下的最大半径</b>
 *       （T 随 R 单调递增，约束是两侧直线段都不能为负）；</li>
 *   <li><b>手动</b>：用玩家给的 {@code radiusM}；放不下时直接报错并告诉最大可用半径，
 *       而不是悄悄换一个形状；</li>
 *   <li>缓和曲线长与超高的规则<b>与弯道生成完全一致</b>（GB50090 表 / 玩家值，再按 Δ·R·0.45 夹紧）。</li>
 * </ul>
 *
 * <p>只有当这种形状在几何上无解时（两端朝向背离、切线交点落在端点后方等），
 * 才退回旧的双圆弧解（会绕远，{@code plan.warnDetour} 会置位提示玩家换一端）。</p>
 */
public final class ConnectSolver {

    private static final double EPS = 1.0E-9D;

    private ConnectSolver() {
    }

    public static Alignment solve(TrackSpec spec, RailEnd from, RailEnd to, RailPlan plan) {
        Alignment a = solveTangentCurve(spec, from, to, plan);
        if (a != null || !plan.ok) {
            return a;                       // 有解，或已经给出了明确错误（如半径过大）
        }
        a = solveBiarc(spec, from, to, plan);
        // ★ 兜底解必须自证闭合：旧版双圆弧在"背对背"等情形下会给出根本接不上的结果
        //   （离线自检实测终点差 300 m），宁可明确报错也不能铺出一条接不上的线。
        if (a != null && closes(a, from, to)) {
            return a;
        }
        if (plan.ok) {
            plan.addError("tracktool.err.connect_facing", null);
        }
        return null;
    }

    /** 端点闭合校验：位置 &lt; 0.5 m 且朝向 &lt; 1°。 */
    private static boolean closes(Alignment a, RailEnd from, RailEnd to) {
        try {
            a.placeAt(from.x, from.z, from.outwardYaw);
            double[] o = new double[7];
            a.eval(a.length, o);
            double posErr = Math.hypot(o[0] - to.x, o[1] - to.z);
            double yawErr = Math.abs(Geo.angleDiff(o[3], Geo.normalize360(to.outwardYaw + 180.0D)));
            return posErr < 0.5D && yawErr < 1.0D;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 直线 - 缓和 - 圆 - 缓和 - 直线
    // ------------------------------------------------------------------

    private static Alignment solveTangentCurve(TrackSpec spec, RailEnd from, RailEnd to, RailPlan plan) {
        double th1 = from.outwardYaw;
        // 新轨道朝对面那个接头开过去，所以它到达时的朝向与对方的"向外方向"相反
        double th2 = Geo.normalize360(to.outwardYaw + 180.0D);
        double deltaDeg = Geo.angleDiff(th2, th1);
        double vx = to.x - from.x;
        double vz = to.z - from.z;
        double chord = Math.sqrt(vx * vx + vz * vz);
        if (chord < 0.5D) {
            plan.addError("tracktool.err.too_close", null);
            return null;
        }
        double u1x = Geo.dirX(th1);
        double u1z = Geo.dirZ(th1);
        double u2x = Geo.dirX(th2);
        double u2z = Geo.dirZ(th2);

        // 两端平行：共线就直接一条直线；错开的话单个弯道接不上（几何上需要 S 形），直接明说
        if (Math.abs(deltaDeg) < 0.05D) {
            double cross = vx * u1z - vz * u1x;
            if (Math.abs(cross) > 0.10D) {
                plan.addError("tracktool.err.connect_parallel", String.format("%.1f", Math.abs(cross)));
                return null;
            }
            if (vx * u1x + vz * u1z <= 0.0D) {
                plan.addError("tracktool.err.connect_facing", null);   // 共线但朝向背离
                return null;
            }
            List<Element> els = new ArrayList<Element>();
            els.add(new LineElement(chord));
            return assemble(spec, from, to, plan, els, chord, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
        }

        double det = u1x * u2z - u1z * u2x;
        if (Math.abs(det) < 1.0E-9D) {
            return null;
        }
        // P1 + t1·u1 = P2 − t2·u2  ⇒ 切线交点
        double t1 = (vx * u2z - vz * u2x) / det;
        double t2 = (u1x * vz - u1z * vx) / det;
        if (t1 <= 0.5D || t2 <= 0.5D) {
            return null;                    // 交点在端点后方：这种形状接不上
        }
        double tmax = Math.min(t1, t2);
        double deltaRad = Math.toRadians(Math.abs(deltaDeg));
        double sign = deltaDeg >= 0.0D ? 1.0D : -1.0D;

        double r;
        if (spec.connectAutoSolve) {
            double lo = 1.0D;
            double hi = 100000.0D;
            if (tangentLength(lo, deltaRad, spec) > tmax) {
                plan.addError("tracktool.err.connect_failed", null);
                return null;                // 连最小半径都放不下
            }
            for (int i = 0; i < 80; i++) {
                double mid = 0.5D * (lo + hi);
                if (tangentLength(mid, deltaRad, spec) <= tmax) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            r = lo;
        } else {
            r = Math.max(1.0D, spec.radiusM);
            if (tangentLength(r, deltaRad, spec) > tmax) {
                // 明确报错并给出上限，而不是悄悄换形状
                double lo = 1.0D;
                double hi = r;
                for (int i = 0; i < 60; i++) {
                    double mid = 0.5D * (lo + hi);
                    if (tangentLength(mid, deltaRad, spec) <= tmax) {
                        lo = mid;
                    } else {
                        hi = mid;
                    }
                }
                plan.addError("tracktool.err.connect_radius", String.format("%.0f", lo));
                return null;
            }
        }

        double ls = spiralLength(r, deltaRad, spec);
        double[] pq = spiralShift(r, ls);
        double tangent = (r + pq[0]) * Math.tan(deltaRad * 0.5D) + pq[1];
        double l1 = t1 - tangent;
        double l2 = t2 - tangent;
        if (l1 < -0.05D || l2 < -0.05D) {
            return null;
        }
        l1 = Math.max(0.0D, l1);
        l2 = Math.max(0.0D, l2);
        double arcLen = r * deltaRad - ls;
        if (arcLen <= 0.01D) {
            return null;
        }
        double kappa = sign / r;

        List<Element> els = new ArrayList<Element>();
        if (l1 > 0.01D) {
            els.add(new LineElement(l1));
        }
        els.add(new SpiralElement(0.0D, kappa, ls));
        els.add(new ArcElement(sign * r, arcLen));
        els.add(new SpiralElement(kappa, 0.0D, ls));
        if (l2 > 0.01D) {
            els.add(new LineElement(l2));
        }
        double total = l1 + ls + arcLen + ls + l2;

        plan.solvedRadius = r;
        plan.solvedTransition = ls;
        return assemble(spec, from, to, plan, els, total, l1, ls, arcLen, l2, sign);
    }

    /** 切线长 T = (R + p)·tan(Δ/2) + q。 */
    private static double tangentLength(double r, double deltaRad, TrackSpec spec) {
        double ls = spiralLength(r, deltaRad, spec);
        double[] pq = spiralShift(r, ls);
        return (r + pq[0]) * Math.tan(deltaRad * 0.5D) + pq[1];
    }

    /** 缓和曲线长：规则与弯道生成完全一致。 */
    private static double spiralLength(double r, double deltaRad, TrackSpec spec) {
        double ls = spec.autoTransition
                ? RailStandards.easementLength(160, (int) Math.round(r))
                : spec.transitionLength;
        double maxSpiral = deltaRad * r * 0.9D * 0.5D;
        return Geo.clamp(ls, 0.5D, Math.max(0.5D, maxSpiral));
    }

    /**
     * 缓和曲线的内移量 p 与切线增量 q —— 直接拿本模组的缓和曲线实体量出来，
     * 不用级数近似，于是装配出来的线路端点是精确闭合的。
     */
    private static double[] spiralShift(double r, double ls) {
        SpiralElement sp = new SpiralElement(0.0D, 1.0D / r, ls);
        sp.placeAt(0.0D, 0.0D, 0.0D);       // 起点在原点、朝向 +Z，左手边为 +X
        double[] e = new double[2];
        sp.pointAt(ls, e);
        double phi = sp.yawAt(ls);
        double cx = e[0] + Geo.leftX(phi) * r;   // 圆心
        double cz = e[1] + Geo.leftZ(phi) * r;
        return new double[]{cx - r, cz};
    }

    /** 竖曲线 + 超高 + 组装。 */
    private static Alignment assemble(TrackSpec spec, RailEnd from, RailEnd to, RailPlan plan,
                                      List<Element> els, double total,
                                      double l1, double ls, double arcLen, double l2, double sign) {
        double p0 = Math.tan(from.outwardPitch * Geo.RAD);
        double pe = Math.tan(-to.outwardPitch * Geo.RAD);
        double rise = to.y - from.y;
        VerticalProfile vp = VerticalProfile.create(total, from.y, p0, pe, rise, spec.verticalRadius);

        CantProfile cp;
        if (spec.connectCantAdaptive) {
            // 外轨超高自适应：整段在两端既有超高之间按里程线性过渡，不理会输入框里的数
            cp = CantProfile.of(new double[]{0.0D, total},
                    new double[]{from.jointCant, to.existingCant});
        } else if (ls > 0.0D && arcLen > 0.0D) {
            double peak = peakCant(spec, from, to, sign);
            double[] ss = {0.0D, Math.max(0.01D, l1), l1 + ls, l1 + ls + arcLen,
                           Math.min(total - 0.01D, l1 + ls + arcLen + ls), total};
            double[] cc = {from.jointCant, from.jointCant, peak, peak, to.existingCant, to.existingCant};
            for (int i = 1; i < ss.length; i++) {
                if (ss[i] <= ss[i - 1]) {
                    ss[i] = ss[i - 1] + 1.0E-4D;
                }
            }
            ss[ss.length - 1] = Math.max(ss[ss.length - 1], total);
            cp = CantProfile.of(ss, cc);
        } else {
            cp = CantProfile.of(new double[]{0.0D, total},
                    new double[]{from.jointCant, to.existingCant});
        }
        return new Alignment(els, vp, cp);
    }

    /**
     * 连接段圆曲线上的超高平台值。
     *
     * <p><b>不能只听 GUI 里的那个数</b>：在一条已有超高的弯道中间打掉一节再补，两端都是满超高
     * （比如 −10°），而连接模式的超高字段默认是 0 ⇒ 平台取 0 ⇒ 19 m 之内先把轨道扭平再扭回去。
     * 第 59 轮存档实证：连接段采样表 <code>超高 −10.00 / 0.00 / −10.00</code>，而两侧邻段全程 −10.00。
     * 这不只是"看着别扭"——RTM 的 {@code getRailHeight} 会额外加 {@code |sin(超高)|·1.5}，
     * 超高从 10° 掉到 0° 意味着钢轨在中间<b>下沉 0.26 m 再升回来</b>，
     * 正是用户看到的"铁轨不齐平"。（接头处高度其实是对的，所以 <code>/tracktool test joints</code>
     * 只比设计高时报 0 处 —— 诊断量错了对象，本轮一并改成比渲染高。）</p>
     *
     * <p>规则：平台的绝对值<b>不得小于两端</b>，符号跟着占优的那一端；两端都比用户设定小时，
     * 才用用户设定。这样"弯道中间补一节"必然得到与两侧一致的常数超高，
     * 而"从直线接一条新弯道"（两端超高 0）仍然完全按用户填的走。</p>
     */
    private static double peakCant(TrackSpec spec, RailEnd from, RailEnd to, double sign) {
        double peak = spec.cantMagnitude() * (sign > 0.0D ? -1.0D : 1.0D) * (spec.cantInvert ? -1.0D : 1.0D);
        double c0 = from.jointCant;
        double c1 = to.existingCant;
        double dominant = Math.abs(c0) >= Math.abs(c1) ? c0 : c1;
        return Math.abs(dominant) > Math.abs(peak) ? dominant : peak;
    }

    // ------------------------------------------------------------------
    // 兜底：双圆弧（只在上面那种形状无解时用；会绕远）
    // ------------------------------------------------------------------

    private static final class Biarc {
        double thetaJ;
        double w1;
        double w2;
        double r1;
        double r2;
        double l1;
        double l2;
        boolean valid;
        double score;
    }

    private static Alignment solveBiarc(TrackSpec spec, RailEnd from, RailEnd to, RailPlan plan) {
        double px1 = from.x;
        double pz1 = from.z;
        double px2 = to.x;
        double pz2 = to.z;
        double th1 = from.outwardYaw;
        double th2 = Geo.normalize360(to.outwardYaw + 180.0D);

        double vx = px2 - px1;
        double vz = pz2 - pz1;
        double chord = Math.sqrt(vx * vx + vz * vz);
        if (chord < 0.5D) {
            plan.addError("tracktool.err.too_close", null);
            return null;
        }

        Biarc best = null;
        int steps = 1440;
        for (int i = 0; i <= steps; i++) {
            double thetaJ = i * 360.0D / steps;
            Biarc c = evaluate(px1, pz1, th1, px2, pz2, th2, thetaJ);
            if (c.valid && (best == null || c.score < best.score)) {
                best = c;
            }
        }
        if (best == null) {
            plan.addError("tracktool.err.connect_failed", null);
            return null;
        }
        double centre = best.thetaJ;
        double span = 360.0D / steps;
        for (int pass = 0; pass < 3; pass++) {
            span *= 0.5D;
            for (int i = -20; i <= 20; i++) {
                double thetaJ = centre + i * span / 20.0D;
                Biarc c = evaluate(px1, pz1, th1, px2, pz2, th2, thetaJ);
                if (c.valid && c.score < best.score) {
                    best = c;
                    centre = thetaJ;
                }
            }
        }

        List<Element> elements = new ArrayList<Element>();
        double turn1 = Geo.wrapAngle(best.thetaJ - th1);
        double turn2 = Geo.wrapAngle(th2 - best.thetaJ);
        if (Math.abs(turn1) > 0.02D && isFinite(best.r1)) {
            elements.add(new ArcElement(best.r1, Math.abs(best.l1)));
        } else {
            elements.add(new LineElement(Math.abs(best.w1)));
        }
        if (Math.abs(turn2) > 0.02D && isFinite(best.r2)) {
            elements.add(new ArcElement(best.r2, Math.abs(best.l2)));
        } else {
            elements.add(new LineElement(Math.abs(best.w2)));
        }

        double total = 0.0D;
        for (Element e : elements) {
            total += e.length;
        }
        if (total > chord * 3.0D) {
            plan.warnDetour = true;
            plan.detourRatio = total / chord;
        }
        return assemble(spec, from, to, plan, elements, total, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v) && Math.abs(v) < 1.0E7D;
    }

    private static Biarc evaluate(double px1, double pz1, double th1,
                                  double px2, double pz2, double th2, double thetaJ) {
        Biarc c = new Biarc();
        c.thetaJ = thetaJ;
        double bis1 = (th1 + thetaJ) * 0.5D;
        double bis2 = (thetaJ + th2) * 0.5D;
        double a1x = Geo.dirX(bis1);
        double a1z = Geo.dirZ(bis1);
        double a2x = Geo.dirX(bis2);
        double a2z = Geo.dirZ(bis2);
        double vx = px2 - px1;
        double vz = pz2 - pz1;
        double det = a1x * (-a2z) - a1z * (-a2x);
        if (Math.abs(det) < 1.0E-7D) {
            return c;
        }
        double w1 = (vx * (-a2z) - vz * (-a2x)) / det;
        double w2 = (a1x * vz - a1z * vx) / det;
        if (w1 <= EPS || w2 <= EPS) {
            return c;
        }
        double phi1 = Math.toRadians(Geo.wrapAngle(thetaJ - th1));
        double phi2 = Math.toRadians(Geo.wrapAngle(th2 - thetaJ));
        double s1 = Math.sin(phi1 * 0.5D);
        double s2 = Math.sin(phi2 * 0.5D);
        double r1 = Math.abs(s1) < 1.0E-6D ? Double.POSITIVE_INFINITY : w1 / (2.0D * s1);
        double r2 = Math.abs(s2) < 1.0E-6D ? Double.POSITIVE_INFINITY : w2 / (2.0D * s2);
        double l1 = isFinite(r1) ? r1 * phi1 : w1;
        double l2 = isFinite(r2) ? r2 * phi2 : w2;
        if (!isFinite(l1) || !isFinite(l2) || l1 <= 0.02D || l2 <= 0.02D) {
            return c;
        }
        c.w1 = w1;
        c.w2 = w2;
        c.r1 = r1;
        c.r2 = r2;
        c.l1 = l1;
        c.l2 = l2;
        double k1 = isFinite(r1) ? 1.0D / Math.abs(r1) : 0.0D;
        double k2 = isFinite(r2) ? 1.0D / Math.abs(r2) : 0.0D;
        c.score = Math.max(k1, k2) + (Math.abs(l1) + Math.abs(l2)) * 2.0E-5D;
        c.valid = true;
        return c;
    }
}
