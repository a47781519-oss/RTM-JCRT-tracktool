package com.tracktool.rail.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * The complete result of planning: every RTM rail core that will be created,
 * plus the design polyline used for the translucent preview and the element
 * list shown in the GUI.
 */
public final class RailPlan {

    public final List<PlanSegment> segments = new ArrayList<PlanSegment>();
    public final List<Alignment.ElementInfo> elements = new ArrayList<Alignment.ElementInfo>();
    /** Preview polyline: x, z, y, yaw per point, concatenated. */
    public final List<double[]> preview = new ArrayList<double[]>();
    /** Per parallel line: the design alignment. */
    public final List<Alignment> alignments = new ArrayList<Alignment>();

    public double totalLength;
    public double maxDeviation;
    public double totalTurn;
    /** How many segments were actually checked against RTM's own curve. */
    public int verifiedSegments;
    public boolean ok = true;
    public String errorKey;
    public String errorArg;
    /** 连接模式：两端朝向导致必须绕远路（长度 &gt; 3 倍弦长）时置位，提示玩家换一端。 */
    public boolean warnDetour;
    public double detourRatio;
    /** 连接模式：本次用的是 S 形（反向曲线）解算器。
     *  置位时半径与缓和曲线长由解算器自定，GUI 会把那两个输入框收起来，只留超高可调。 */
    public boolean sCurve;

    /** 连接模式自动解算出的弯道半径 / 缓和曲线长（0 = 没解算过），用于回显给玩家。 */
    public double solvedRadius;
    public double solvedTransition;

    public int segmentCount() {
        return this.segments.size();
    }

    public boolean isEmpty() {
        return this.segments.isEmpty();
    }

    public void addError(String key, String arg) {
        this.ok = false;
        this.errorKey = key;
        this.errorArg = arg;
    }

    /** Rough count of blocks the plan will touch, for progress reporting. */
    public int estimatedBlocks() {
        int n = 0;
        for (PlanSegment s : this.segments) {
            n += (int) (s.length3d() * 3.0D) + 8;
        }
        return n;
    }
}
