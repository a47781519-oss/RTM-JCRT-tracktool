package com.tracktool.rail.plan;

import com.tracktool.util.Geo;

import java.util.ArrayList;
import java.util.List;

/**
 * A complete design alignment: horizontal elements + vertical profile + cant
 * profile, all parameterised by one arc length. Pure maths - no Minecraft and
 * no RTM classes - so client preview and server generation cannot diverge.
 */
public final class Alignment {

    public final List<Element> elements;
    public final VerticalProfile vertical;
    public final CantProfile cant;
    public final double length;

    private final double[] cum;
    private double startX;
    private double startZ;
    private double startYaw;

    public Alignment(List<Element> elements, VerticalProfile vertical, CantProfile cant) {
        this.elements = elements;
        this.vertical = vertical;
        this.cant = cant;
        this.cum = new double[elements.size() + 1];
        double acc = 0.0D;
        for (int i = 0; i < elements.size(); i++) {
            this.cum[i] = acc;
            acc += elements.get(i).length;
        }
        this.cum[elements.size()] = acc;
        this.length = acc;
    }

    /** Chains every element after the previous one, starting at the given pose. */
    public void placeAt(double x, double z, double yawDeg) {
        this.startX = x;
        this.startZ = z;
        this.startYaw = yawDeg;
        double cx = x;
        double cz = z;
        double cyaw = yawDeg;
        for (Element e : this.elements) {
            e.placeAt(cx, cz, cyaw);
            cx = e.endX();
            cz = e.endZ();
            cyaw = e.endYaw();
        }
    }

    public double startX() {
        return this.startX;
    }

    public double startZ() {
        return this.startZ;
    }

    public double startYaw() {
        return this.startYaw;
    }

    public double startPitchDeg() {
        return this.vertical.pitchDegAt(0.0D);
    }

    /** Total turn angle of all horizontal elements, in degrees (signed). */
    public double totalTurn() {
        double t = 0.0D;
        for (Element e : this.elements) {
            t += e.turnAt(e.length);
        }
        return t;
    }

    private Element elementAt(double s) {
        for (int i = 0; i < this.elements.size(); i++) {
            if (s <= this.cum[i + 1] || i == this.elements.size() - 1) {
                return this.elements.get(i);
            }
        }
        return this.elements.isEmpty() ? null : this.elements.get(this.elements.size() - 1);
    }

    private double localS(double s) {
        for (int i = 0; i < this.elements.size(); i++) {
            if (s <= this.cum[i + 1] || i == this.elements.size() - 1) {
                return s - this.cum[i];
            }
        }
        return s;
    }

    /**
     * Samples the alignment.
     *
     * @param out [0]=x [1]=z [2]=y [3]=yaw(deg) [4]=pitch(deg) [5]=cant(deg) [6]=curvature
     */
    public void eval(double s, double[] out) {
        double sc = Geo.clamp(s, 0.0D, this.length);
        Element e = this.elementAt(sc);
        double ls = this.localS(sc);
        double[] p = new double[2];
        if (e == null) {
            out[0] = this.startX;
            out[1] = this.startZ;
            out[3] = this.startYaw;
            out[6] = 0.0D;
        } else {
            e.pointAt(ls, p);
            out[0] = p[0];
            out[1] = p[1];
            out[3] = e.yawAt(ls);
            out[6] = e.curvatureAt(ls);
        }
        out[2] = this.vertical.yAt(sc);
        out[4] = this.vertical.pitchDegAt(sc);
        out[5] = this.cant.cantAt(sc);
    }

    public double yawAt(double s) {
        double[] o = new double[7];
        this.eval(s, o);
        return o[3];
    }

    /** Description of every element, for the GUI element list. */
    public List<ElementInfo> describe() {
        List<ElementInfo> list = new ArrayList<ElementInfo>();
        for (int i = 0; i < this.elements.size(); i++) {
            Element e = this.elements.get(i);
            ElementInfo info = new ElementInfo();
            info.index = i;
            info.typeKey = e.typeKey();
            info.length = e.length;
            info.s0 = this.cum[i];
            info.s1 = this.cum[i + 1];
            info.turnDeg = e.turnAt(e.length);
            if (e instanceof ArcElement) {
                info.radius = ((ArcElement) e).signedRadius;
            } else if (e instanceof SpiralElement) {
                SpiralElement sp = (SpiralElement) e;
                double k = Math.max(Math.abs(sp.k0), Math.abs(sp.k1));
                info.radius = k > 1.0E-9D ? 1.0D / k : 0.0D;
                info.spiralStartRadius = Math.abs(sp.k0) > 1.0E-9D ? 1.0D / Math.abs(sp.k0) : 0.0D;
                info.spiralEndRadius = Math.abs(sp.k1) > 1.0E-9D ? 1.0D / Math.abs(sp.k1) : 0.0D;
            }
            list.add(info);
        }
        return list;
    }

    /** Flat, GUI/report friendly element description. */
    public static final class ElementInfo {
        public int index;
        public String typeKey;
        public double length;
        public double s0;
        public double s1;
        public double turnDeg;
        public double radius;
        public double spiralStartRadius;
        public double spiralEndRadius;
    }
}
