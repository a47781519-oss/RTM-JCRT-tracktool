package com.tracktool.rail2;

/** 几何参数的编解码：用于把"解析线形参数"通过 RailPosition.scriptArgs 跨端同步。
 *
 *  <p>为什么用 scriptArgs：RTM 会把 RailPosition 的该字段随 TE 一起同步到客户端（它自己就用它传脚本参数）。
 *  <b>但绝不设置 scriptName</b> —— 字节码显示 createRailMap() 里 hasScript() 为真会去建 RailMapCustom，
 *  而本环境脚本引擎为 null，会直接抛异常。保持 scriptName 为空 ⇒ RTM 建一个无害的 RailMapBasic，
 *  我们两端各自反射覆盖成 ExactRailMap 即可。</p>
 *
 *  <p>格式：{@code ttx1;R=..;turn=..;ls=..;cant=..;rise=..;Rv=..;dir=..;yaw=..;x=..;y=..;z=..}
 *  （Double.toString 保证往返精确）。</p> */
public final class ExactRailGeometryCodec {

    public static final String PREFIX = "ttx1";

    /** 参数顺序固定：R, turn, ls, cant, rise, Rv, dir, yaw0, baseX, baseY, baseZ。 */
    public static final int N = 11;

    private ExactRailGeometryCodec() {
    }

    public static String encode(double[] p) {
        StringBuilder sb = new StringBuilder(PREFIX);
        sb.append(";R=").append(p[0]).append(";turn=").append(p[1]).append(";ls=").append(p[2])
          .append(";cant=").append(p[3]).append(";rise=").append(p[4]).append(";Rv=").append(p[5])
          .append(";dir=").append(p[6]).append(";yaw=").append(p[7])
          .append(";x=").append(p[8]).append(";y=").append(p[9]).append(";z=").append(p[10]);
        return sb.toString();
    }

    /** @return 解析出的 11 个参数；不是本模组的格式时返回 null（调用方应保持原行为）。 */
    public static double[] decode(String s) {
        if (s == null || !s.startsWith(PREFIX)) {
            return null;
        }
        double[] p = new double[N];
        boolean[] seen = new boolean[N];
        String[] keys = {"R", "turn", "ls", "cant", "rise", "Rv", "dir", "yaw", "x", "y", "z"};
        String[] parts = s.split(";");
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = parts[i].substring(0, eq);
            String v = parts[i].substring(eq + 1);
            for (int j = 0; j < keys.length; j++) {
                if (keys[j].equals(k)) {
                    try {
                        p[j] = Double.parseDouble(v);
                        seen[j] = true;
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
            }
        }
        for (boolean b : seen) {
            if (!b) {
                return null;
            }
        }
        return p;
    }

    public static AlignmentGeometry build(double[] p) {
        return new AlignmentGeometry(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7]);
    }

    /** 离线自检：往返编解码是否精确。 */
    public static void main(String[] args) {
        double[] p = {300.0D, 45.0D, 30.0D, 105.0D, 5.0D, 15000.0D, 1.0D, 180.0D, -2399.5D, 4.0D, -816.0D};
        String s = encode(p);
        System.out.println("编码: " + s);
        double[] q = decode(s);
        boolean ok = q != null && q.length == p.length;
        double maxDiff = 0.0D;
        if (ok) {
            for (int i = 0; i < p.length; i++) {
                maxDiff = Math.max(maxDiff, Math.abs(q[i] - p[i]));
            }
        }
        System.out.printf("往返最大误差=%.17g  %s%n", maxDiff, (ok && maxDiff == 0.0D) ? "PASS(精确)" : "FAIL");
        System.out.println("非法输入返回: " + decode("rtm:something") + " / " + decode(null));
        AlignmentGeometry g = build(p);
        System.out.printf("构建几何: L=%.3f  终点yaw=%.4f°  起点x=%.1f z=%.1f%n",
                g.length(), g.yaw(g.length()), p[8], p[10]);
    }
}