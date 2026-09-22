package com.tracktool.rail.plan;

import com.tracktool.TrackToolConfig;
import com.tracktool.rail.RailEnd;
import com.tracktool.rail.RailGrid;
import com.tracktool.rail.RailStandards;
import com.tracktool.rail.TrackSpec;
import com.tracktool.util.Geo;
import jp.ngt.rtm.rail.util.RailPosition;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns GUI parameters plus the selected rail end(s) into a concrete list of
 * RTM {@link PlanSegment}s.
 *
 * <p>Design decisions worth knowing:</p>
 * <ul>
 *   <li>The curve is the real thing: transition + circular arc + transition,
 *       with the cant ramping up over the entry transition, constant through
 *       the arc and ramping down over the exit transition.</li>
 *   <li>Every element is cut into segments of roughly
 *       {@link TrackToolConfig#segmentLength} metres. Each segment becomes its
 *       own RTM rail core, which is exactly how a player would build a long
 *       curve with several markers - and it keeps RTM's
 *       {@code getNearlestPoint} lookup (O(split), split = length * 32) cheap.</li>
 *   <li>Node headings, grades and cant come from the analytic design, not from
 *       the snapped polyline, so the generated track is G1 continuous at every
 *       joint: no worm-like wiggles even though RTM anchors sit on a half-block
 *       lattice.</li>
 *   <li>Every segment is verified by rebuilding it through RTM's own
 *       {@link jp.ngt.rtm.rail.util.RailMapBasic} and measuring the deviation
 *       from the design.</li>
 * </ul>
 */
public final class PlanBuilder {

    /** Hard cap so a mistyped parameter cannot lock up the server. */
    public static final int MAX_SEGMENTS = 2000;
    public static final int MAX_LENGTH_M = 20000;

    private PlanBuilder() {
    }

    public static RailPlan build(TrackSpec spec, RailEnd from, RailEnd to) {
        RailPlan plan = new RailPlan();
        if (from == null || !from.isValid()) {
            plan.addError("tracktool.err.no_start", null);
            return plan;
        }
        if (spec.mode == TrackSpec.MODE_CONNECT && (to == null || !to.isValid())) {
            plan.addError("tracktool.err.need_two", null);
            return plan;
        }
        if (spec.mode != TrackSpec.MODE_CONNECT && to != null && to.isValid()) {
            // Two ends selected: only connection mode makes sense.
            plan.addError("tracktool.err.two_ends_connect_only", null);
            return plan;
        }

        Alignment base;
        if (spec.mode == TrackSpec.MODE_STRAIGHT) {
            base = buildStraight(spec, from, plan);
        } else if (spec.mode == TrackSpec.MODE_CURVE) {
            base = buildCurve(spec, from, plan);
        } else {
            base = ConnectSolver.solve(spec, from, to, plan);
        }
        if (base == null || base.length < 1.0D) {
            if (plan.ok) {
                plan.addError("tracktool.err.geometry", null);
            }
            return plan;
        }
        base.placeAt(from.x, from.z, from.outwardYaw);

        plan.elements.addAll(base.describe());
        plan.totalLength = base.length;
        plan.totalTurn = base.totalTurn();

        double[] offsets = spec.parallelOffsets();
        double[] tmp = new double[7];
        for (int li = 0; li < offsets.length; li++) {
            Alignment line = offsets[li] == 0.0D ? base : offsetAlignment(base, offsets[li]);
            plan.alignments.add(line);
            buildLineSegments(spec, plan, line, li, offsets[li], from, base);
            if (!plan.ok) {
                return plan;
            }
        }

        // Preview polyline of the selected line, sampled densely (8 points/m).
        samplePreview(base, plan);
        for (PlanSegment s : plan.segments) {
            plan.maxDeviation = Math.max(plan.maxDeviation, s.deviation);
        }
        if (plan.segments.isEmpty()) {
            plan.addError("tracktool.err.too_short", null);
        }
        return plan;
    }

    // ------------------------------------------------------------------
    // alignments
    // ------------------------------------------------------------------

    private static Alignment buildStraight(TrackSpec spec, RailEnd from, RailPlan plan) {
        double len = Geo.clamp(Math.abs(spec.straightLengthM), 1, MAX_LENGTH_M);
        List<Element> elements = new ArrayList<Element>();
        elements.add(new LineElement(len));

        double p0 = Math.tan(from.outwardPitch * Geo.RAD);
        VerticalProfile vp = VerticalProfile.create(len, from.y, p0, spec.riseM, spec.verticalRadius);
        CantProfile cp = cantProfile(len, 0.0D, 0.0D, from.jointCant, 0.0D);
        return new Alignment(elements, vp, cp);
    }

    private static Alignment buildCurve(TrackSpec spec, RailEnd from, RailPlan plan) {
        double r = Geo.clamp(Math.abs(spec.radiusM), 1, 100000);
        double delta = Math.abs(spec.angleDeg);
        if (delta < 0.05D) {
            plan.addError("tracktool.err.zero_angle", null);
            return null;
        }
        if (r < 0.5D) {
            plan.addError("tracktool.err.zero_radius", null);
            return null;
        }
        double sign = spec.turnLeft ? 1.0D : -1.0D;
        double kappa = sign / r;

        // Transition length: GB50090 table by default, otherwise the player's value.
        double l = spec.transitionLength;
        if (spec.autoTransition) {
            l = RailStandards.easementLength(160, r);
        }
        // Leaving a circular arc is mandatory: cap the spirals at 90% of the turn.
        double deltaRad = delta * Geo.RAD;
        double maxSpiral = deltaRad * r * 0.9D * 0.5D;
        l = Geo.clamp(l, 0.5D, maxSpiral);
        if (l < 1.0D) {
            l = Math.min(1.0D, maxSpiral);
        }

        double spiralTurn = l / (2.0D * r);
        double arcTurn = deltaRad - 2.0D * spiralTurn;
        if (arcTurn <= 1.0E-4D) {
            plan.addError("tracktool.err.transition_too_long", null);
            return null;
        }
        double arcLen = arcTurn * r;

        List<Element> elements = new ArrayList<Element>();
        elements.add(new SpiralElement(0.0D, kappa, l));
        elements.add(new ArcElement(sign * r, arcLen));
        elements.add(new SpiralElement(kappa, 0.0D, l));

        double total = l + arcLen + l;
        double p0 = Math.tan(from.outwardPitch * Geo.RAD);
        VerticalProfile vp = VerticalProfile.create(total, from.y, p0, spec.riseM, spec.verticalRadius);
        CantProfile cp = cantProfile(total, l, l, from.jointCant, spec.signedCant());
        return new Alignment(elements, vp, cp);
    }

    /**
     * Railway cant diagram. RTM's own roll formula for one rail core is
     * piecewise linear with knots at t=0, 0.5 and 1
     * ({@code getRailRoll} in {@code RailMapBasic}), so a linear ramp and a
     * constant plateau are reproduced <em>exactly</em> as long as segment
     * boundaries fall on the ramp/plateau corners - which they do, because
     * nodes are always placed on element boundaries.
     */
    private static CantProfile cantProfile(double total, double rampIn, double rampOut,
                                           double startCant, double peakCant) {
        List<double[]> k = new ArrayList<double[]>();
        k.add(new double[]{0.0D, startCant});
        double a = Geo.clamp(rampIn, 0.0D, total * 0.45D);
        double b = Geo.clamp(total - rampOut, a, total);
        if (a > 1.0E-3D) {
            k.add(new double[]{a, peakCant});
        } else {
            k.set(0, new double[]{0.0D, peakCant});
        }
        if (b < total - 1.0E-3D) {
            k.add(new double[]{b, peakCant});
        }
        k.add(new double[]{total, 0.0D});
        double[] ss = new double[k.size()];
        double[] cc = new double[k.size()];
        for (int i = 0; i < k.size(); i++) {
            ss[i] = k.get(i)[0];
            cc[i] = k.get(i)[1];
        }
        return CantProfile.of(ss, cc);
    }

    /** A parallel track: the same alignment shifted sideways along its normal. */
    private static Alignment offsetAlignment(Alignment base, double offset) {
        List<Element> elements = new ArrayList<Element>();
        double baseYaw = base.startYaw();
        double ox = base.startX() + Geo.leftX(baseYaw) * offset;
        double oz = base.startZ() + Geo.leftZ(baseYaw) * offset;
        for (Element e : base.elements) {
            double[] p0 = new double[2];
            e.pointAt(0.0D, p0);
            double yaw = e.yawAt(0.0D);
            double nx = p0[0] + Geo.leftX(yaw) * offset;
            double nz = p0[1] + Geo.leftZ(yaw) * offset;
            Element c;
            if (e instanceof LineElement) {
                c = new LineElement(e.length);
            } else if (e instanceof ArcElement) {
                ArcElement a = (ArcElement) e;
                // Concentric arc: the radius changes by the offset, the turn is the same.
                double r = a.signedRadius;
                double rNew = r - offset;
                if (Math.abs(rNew) < 1.0E-3D) {
                    rNew = Math.signum(r) * 1.0E-3D;
                }
                double turn = Math.abs(a.length / r);
                c = new ArcElement(rNew, Math.abs(rNew) * turn);
            } else {
                // 缓和曲线的平行线：曲率与长度都要按等距曲线换算，否则平行弯道会比基准线"长/短"一截，
                // 各元素接不上（表现为平行线在缓和段前后被拧出折角）。
                //   等距曲线：κ' = κ / (1 − κ·d)，ds' = (1 − κ·d)·ds
                //   ⇒ L' = L − d·Δθ（Δθ = 本元素的总转角），转角保持不变 ⇒ 接头处切线仍然连续。
                double k0 = e.curvatureAt(0.0D);
                double k1 = e.curvatureAt(e.length);
                double turn = e.turnAt(e.length) * Geo.RAD;      // 弧度
                double k0n = safeOffsetCurvature(k0, offset);
                double k1n = safeOffsetCurvature(k1, offset);
                double lenNew = Math.max(0.5D, e.length - offset * turn);
                c = new SpiralElement(k0n, k1n, lenNew);
            }
            c.placeAt(nx, nz, yaw);
            elements.add(c);
        }
        Alignment al = new Alignment(elements, base.vertical, base.cant);
        al.placeAt(ox, oz, baseYaw);
        return al;
    }

    /** 等距曲线的曲率：κ' = κ/(1 − κ·d)；夹紧掉"偏移到曲率中心另一侧"的退化情形。 */
    private static double safeOffsetCurvature(double k, double offset) {
        double denom = 1.0D - k * offset;
        if (Math.abs(denom) < 1.0E-3D) {
            denom = denom < 0.0D ? -1.0E-3D : 1.0E-3D;
        }
        return k / denom;
    }

    // ------------------------------------------------------------------
    // segments
    // ------------------------------------------------------------------

    private static void buildLineSegments(TrackSpec spec, RailPlan plan, Alignment al, int lineIndex,
                                          double offset, RailEnd from, Alignment base) {
        double segLen = Math.max(2.0D, TrackToolConfig.segmentLength);
        List<Double> nodeS = new ArrayList<Double>();
        nodeS.add(0.0D);
        double acc = 0.0D;
        for (Element e : al.elements) {
            int n = Math.max(1, (int) Math.ceil(e.length / segLen));
            for (int i = 1; i <= n; i++) {
                double s = acc + e.length * i / n;
                nodeS.add(s);
            }
            acc += e.length;
        }
        int count = nodeS.size();
        if (count < 2) {
            return;
        }
        double[][] nodes = new double[count][7];
        double[] tmp = new double[7];
        for (int i = 0; i < count; i++) {
            double s = nodeS.get(i);
            al.eval(s, tmp);
            // ★ al 已经是【偏移过】的那条线（offsetAlignment），这里不能再偏移一次：
            //   否则平行线的实际间距是设定值的两倍，且与 plan.alignments.get(li) 对不上
            //   （新路径要用后者当几何，必须两者一致）。offset 只留给需要时的诊断。
            double nx = tmp[0];
            double nz = tmp[1];
            nodes[i][0] = nx;
            nodes[i][1] = nz;
            nodes[i][2] = tmp[2];
            nodes[i][3] = tmp[3];
            nodes[i][4] = tmp[4];
            nodes[i][5] = tmp[5];
            nodes[i][6] = s;
        }

        // F1': select the whole chain's lattice points together, so the lateral
        // error varies smoothly instead of alternating node by node. Offline
        // prototype (tools/sim_lattice_dp.py): jitter max 0.914 -> 0.300 (-67%).
        // Railway: see docs/问题档案-放样精度与核心缺口.md round 33/34.
        double[] idealX = new double[count];
        double[] idealZ = new double[count];
        double[] idealYaw = new double[count];
        for (int i = 0; i < count; i++) {
            idealX[i] = nodes[i][0];
            idealZ[i] = nodes[i][1];
            idealYaw[i] = Math.toRadians(nodes[i][3]);
        }
        double[][] fitted = LatticeChain.fit(idealX, idealZ, idealYaw);
        for (int i = 0; i < count; i++) {
            nodes[i][0] = fitted[i][0];
            nodes[i][1] = fitted[i][1];
        }

        for (int i = 0; i < count - 1; i++) {
            if (plan.segments.size() >= MAX_SEGMENTS) {
                plan.addError("tracktool.err.too_many_segments", String.valueOf(MAX_SEGMENTS));
                return;
            }
            double[] a = nodes[i];
            double[] b = nodes[i + 1];
            double chordH = Math.sqrt(Geo.dist2(a[0], a[1], b[0], b[1]));
            if (chordH < 0.75D) {
                // Too short for RTM's Bezier length estimator; merge by skipping.
                continue;
            }
            RailPosition rp0;
            RailPosition rp1;
            if (lineIndex == 0 && i == 0 && from != null && from.rp != null) {
                // First node of the selected line: reuse the selected anchor so
                // the joint is bit-for-bit identical to the existing rail.
                rp0 = PlanSegment.copyOf(from.rp);
                rp0.anchorYaw = (float) Geo.normalize360(from.outwardYaw);
                rp0.anchorPitch = (float) from.outwardPitch;
                // The design node must equal the anchor RTM already stored.
                a[0] = rp0.posX;
                a[1] = rp0.posZ;
                a[2] = rp0.posY;
            } else {
                rp0 = makeNode(a, false);
            }
            rp1 = makeNode(b, true);

            double tangent = chordH / 3.0D;
            rp0.anchorLengthHorizontal = (float) tangent;
            rp1.anchorLengthHorizontal = (float) tangent;
            rp0.anchorLengthVertical = (float) tangent;
            rp1.anchorLengthVertical = (float) tangent;

            // Hard assertion: the anchor RTM will reconstruct must be exactly the
            // point we designed. A mismatch here means the lattice mapping is
            // wrong and would show up as a crooked curve.
            double e0 = RailGrid.verify(rp0, a[0], a[1], a[2]);
            double e1 = RailGrid.verify(rp1, b[0], b[1], b[2]);
            if (e0 > 1.0E-6D || e1 > 1.0E-6D) {
                plan.addError("tracktool.err.lattice",
                        String.format("%.3f/%.3f", e0, e1));
                return;
            }

            // Roll: linear from a[5] to b[5]; RTM reads -endRP.cantEdge at t=1.
            rp0.cantCenter = (float) ((a[5] + b[5]) * 0.5D);
            rp0.cantEdge = (float) a[5];
            rp1.cantCenter = (float) ((a[5] + b[5]) * 0.5D);
            rp1.cantEdge = (float) (-b[5]);

            PlanSegment seg = new PlanSegment(rp0, rp1, a[6], b[6], lineIndex);
            if (!seg.checkLattice()) {
                plan.addError("tracktool.err.lattice", null);
                return;
            }
            plan.segments.add(seg);
        }
    }

    /**
     * Builds one anchor.
     *
     * <p>RTM measures both control directions in the anchor's own outward frame:
     * a rail leaves its start anchor along {@code +anchorYaw} and arrives at its
     * end anchor from {@code -anchorYaw}, and the vertical Bezier has slope
     * {@code +tan(anchorPitch)} at the start but {@code -tan(anchorPitch)} at the
     * end. An end anchor therefore stores {@code yaw + 180} and {@code -pitch} -
     * which is also what makes RTM recognise an axis-aligned straight rail and
     * take its {@code StraightLine} fast path, exactly like a marker-built rail.</p>
     */
    private static RailPosition makeNode(double[] node, boolean endAnchor) {
        double[] snapped = RailGrid.snap(node[0], node[1], node[3]);
        // Write the lattice point back so the node array, the chord lengths and
        // the reconstruction assertion all talk about the same point.
        node[0] = snapped[0];
        node[1] = snapped[1];
        double yaw = endAnchor ? Geo.normalize360(node[3] + 180.0D) : node[3];
        double pitch = endAnchor ? -node[4] : node[4];
        RailPosition rp = RailGrid.make(snapped[0], snapped[1], node[2], yaw, pitch, 0);
        // Y is quantised to 1/16 by the rail data format; keep the node array in step.
        node[2] = rp.posY;
        return rp;
    }

    private static void samplePreview(Alignment al, RailPlan plan) {
        int n = Math.max(2, (int) Math.ceil(al.length * 8.0D));
        n = Math.min(n, 20000);
        double[] tmp = new double[7];
        for (int i = 0; i <= n; i++) {
            double s = al.length * i / n;
            al.eval(s, tmp);
            plan.preview.add(new double[]{tmp[0], tmp[1], tmp[2], tmp[3], tmp[5]});
        }
    }

    // ------------------------------------------------------------------
    // verification
    // ------------------------------------------------------------------

    /**
     * Rebuilds every segment through RTM's own rail map and records how far it
     * strays from the design. Called explicitly because it is comparatively
     * expensive; the preview skips it.
     *
     * <p>This is the "verify against the source, not just the maths" step: it
     * catches RTM's straight-line shortcut and any lattice snapping damage.</p>
     */
    public static void verifySegments(RailPlan plan) {
        double[] tmp = new double[7];
        double worst = 0.0D;
        int n = plan.segments.size();
        if (n == 0) {
            return;
        }
        // Budget: at most 48 segments are checked, evenly spread over the plan.
        int stride = Math.max(1, (int) Math.ceil(n / 48.0D));
        int checked = 0;
        for (int i = 0; i < n; i += stride) {
            PlanSegment s = plan.segments.get(i);
            int li = s.lineIndex;
            if (li < 0 || li >= plan.alignments.size()) {
                li = 0;
            }
            if (plan.alignments.isEmpty()) {
                return;
            }
            s.verify(plan.alignments.get(li), tmp);
            worst = Math.max(worst, s.deviation);
            checked++;
        }
        plan.maxDeviation = worst;
        plan.verifiedSegments = checked;
    }
}
