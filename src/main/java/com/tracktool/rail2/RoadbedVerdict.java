package com.tracktool.rail2;

/**
 * 路基格子的去留判定 —— 铺轨前清理（{@link RoadbedPreClear}）和撤销后复查共用这一套。
 *
 * <p>单独成类、<b>不引用任何 RTM 类型</b>：RTM 在工程里是 compileOnly，离线自检的 classpath 上没有它，
 * 判定规则写在这里才能不开游戏就验证（{@code gradlew preClearTest}）。调用方负责把世界里读到的事实
 * 折算成下面这几个布尔量。</p>
 *
 * <p>「归属」一律按 RTM 自己的规则认：底座 TE 的 {@code startPoint} 指向谁，它就属于谁
 * （{@code TileEntityLargeRailBase.getRailCore()} 就是这么取的；RTM 的 {@code RailMap.breakRail}
 * 拆轨时也只拆「归自己」或「找不到核心」的格子，字节码偏移 83–152）。</p>
 */
public final class RoadbedVerdict {

    private RoadbedVerdict() {
    }

    public enum Kind {
        /** 不动：属于这次要保护的轨道（端点所在轨道、本次刚铺的轨道）。 */
        KEEP,
        /** 无主：没有 TE、指不到核心、或核心是空的。碰撞箱是实心一格 / 卡在旧高度的薄片，纯属障碍。 */
        ORPHAN,
        /** 别的、还活着的轨道的一格底座，落在新线路的路基范围里。 */
        FOREIGN_CELL,
        /** 别的、还活着的轨道的<b>核心</b>，落在新线路的路基范围里 —— 整条旧轨道都压在新线上。 */
        FOREIGN_RAIL
    }

    /**
     * 普通底座格（非核心）。
     *
     * @param hasTe          方块上有 TileEntityLargeRailBase（用 CHECK 取，不许懒建）
     * @param hasStartPoint  TE 里有一个三元坐标的 startPoint
     * @param ownerProtected startPoint 指向的是受保护的核心
     * @param ownerLive      startPoint 那一格是个核心 TE，且带着完整的 railPositions
     */
    public static Kind ofBase(boolean hasTe, boolean hasStartPoint, boolean ownerProtected, boolean ownerLive) {
        // 先看 TE：没有 TE 的底座哪怕挨着受保护的轨道也是一堵实心墙（getAABBWithState 第一条指令
        // 就是 TE==null ⇒ FULL_BLOCK_AABB），而且 RTM 的 breakRail 碰到它会 NPE 中断 —— 留着只有害。
        if (!hasTe || !hasStartPoint) {
            return Kind.ORPHAN;
        }
        if (ownerProtected) {
            return Kind.KEEP;
        }
        return ownerLive ? Kind.FOREIGN_CELL : Kind.ORPHAN;
    }

    /**
     * 核心格。
     *
     * @param isProtected      这颗核心本身受保护
     * @param hasTe            有 TileEntityLargeRailCore
     * @param hasRailPositions TE 的 railPositions 完整（两端都不为 null）
     */
    public static Kind ofCore(boolean isProtected, boolean hasTe, boolean hasRailPositions) {
        if (isProtected) {
            return Kind.KEEP;
        }
        // 没数据的空核心：RTM 打包区块时 writeRailData 取 railPositions[0] 不判空 ⇒ 服务端 NPE。必须清。
        if (!hasTe || !hasRailPositions) {
            return Kind.ORPHAN;
        }
        return Kind.FOREIGN_RAIL;
    }

    /**
     * 没有撤销记录时只许清无主的：动别人还活着的轨道必须能撤回来，否则就是不可逆的存档损坏。
     */
    public static Kind withoutUndo(Kind k) {
        return k == Kind.FOREIGN_CELL || k == Kind.FOREIGN_RAIL ? Kind.KEEP : k;
    }

    /**
     * 撤销之后的复查：只清「无主」和「指向刚被撤掉的核心」的；别人的活轨道（包括刚从快照里还原回来的）一律留着。
     *
     * @param ownerIsUndoneCore startPoint 指向本次撤销里被移除的某颗核心
     */
    public static boolean leftoverAfterUndo(boolean hasTe, boolean hasStartPoint,
                                            boolean ownerIsUndoneCore, boolean ownerLive) {
        if (!hasTe || !hasStartPoint) {
            return true;
        }
        if (ownerIsUndoneCore) {
            return true;
        }
        return !ownerLive;
    }
}
