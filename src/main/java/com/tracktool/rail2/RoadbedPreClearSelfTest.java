package com.tracktool.rail2;

import com.tracktool.rail2.RoadbedVerdict.Kind;

/**
 * 离线自检（{@code gradlew preClearTest}）：铺轨前清理与撤销后复查的去留规则。
 *
 * <p>只测 {@link RoadbedVerdict}：它不引用 RTM，所以不开游戏就能跑。世界读写那一半
 * （{@link RoadbedPreClear}）只是把世界里的事实折算成这些布尔量，再按结论拆方块。</p>
 */
public final class RoadbedPreClearSelfTest {

    private static int pass;
    private static int fail;

    private RoadbedPreClearSelfTest() {
    }

    public static void main(String[] args) {
        // ---- 普通底座：ofBase(hasTe, hasStartPoint, ownerProtected, ownerLive) ----
        check("没有 TE 的底座（实心整格碰撞箱）⇒ 清", Kind.ORPHAN, RoadbedVerdict.ofBase(false, false, false, false));
        check("没有 TE 的底座，哪怕挨着受保护的轨道 ⇒ 仍然清", Kind.ORPHAN, RoadbedVerdict.ofBase(false, false, true, true));
        check("TE 里没有 startPoint ⇒ 清", Kind.ORPHAN, RoadbedVerdict.ofBase(true, false, false, false));
        check("startPoint 指向已经不存在的核心 ⇒ 清", Kind.ORPHAN, RoadbedVerdict.ofBase(true, true, false, false));
        check("端点所在轨道的底座 ⇒ 不动", Kind.KEEP, RoadbedVerdict.ofBase(true, true, true, true));
        check("本次刚铺的轨道的底座 ⇒ 不动", Kind.KEEP, RoadbedVerdict.ofBase(true, true, true, true));
        check("别的活轨道的底座落在新线范围内 ⇒ 记录后清", Kind.FOREIGN_CELL, RoadbedVerdict.ofBase(true, true, false, true));

        // ---- 核心：ofCore(isProtected, hasTe, hasRailPositions) ----
        check("端点所在轨道的核心 ⇒ 不动", Kind.KEEP, RoadbedVerdict.ofCore(true, true, true));
        check("受保护的核心即使数据残缺也不在这里动（交给 BrokenCoreSweeper）", Kind.KEEP, RoadbedVerdict.ofCore(true, true, false));
        check("没有 TE 的核心方块 ⇒ 清", Kind.ORPHAN, RoadbedVerdict.ofCore(false, false, false));
        check("没有 railPositions 的空核心（会让服务端 NPE）⇒ 清", Kind.ORPHAN, RoadbedVerdict.ofCore(false, true, false));
        check("别的活轨道的核心压在新线上 ⇒ 整条移除（可撤销）", Kind.FOREIGN_RAIL, RoadbedVerdict.ofCore(false, true, true));

        // ---- 没有撤销记录时，别人的活轨道一格都不许动 ----
        check("无撤销记录：别的活轨道的底座 ⇒ 不动", Kind.KEEP, RoadbedVerdict.withoutUndo(Kind.FOREIGN_CELL));
        check("无撤销记录：别的活轨道的核心 ⇒ 不动", Kind.KEEP, RoadbedVerdict.withoutUndo(Kind.FOREIGN_RAIL));
        check("无撤销记录：无主的照清", Kind.ORPHAN, RoadbedVerdict.withoutUndo(Kind.ORPHAN));

        // ---- 撤销后的复查：leftoverAfterUndo(hasTe, hasStartPoint, ownerIsUndoneCore, ownerLive) ----
        checkBool("撤销后仍指向刚撤掉的核心的底座 ⇒ 残留，清", true, RoadbedVerdict.leftoverAfterUndo(true, true, true, false));
        checkBool("撤销后没有 TE 的底座 ⇒ 残留，清", true, RoadbedVerdict.leftoverAfterUndo(false, false, false, false));
        checkBool("撤销后指向不存在核心的底座 ⇒ 残留，清", true, RoadbedVerdict.leftoverAfterUndo(true, true, false, false));
        checkBool("从快照还原回来的别人的轨道 ⇒ 不动", false, RoadbedVerdict.leftoverAfterUndo(true, true, false, true));

        System.out.println();
        System.out.println(String.format("[preClearTest] %d 项通过, %d 项失败", pass, fail));
        if (fail > 0) {
            throw new IllegalStateException("preClearTest failed: " + fail);
        }
    }

    private static void check(String what, Kind want, Kind got) {
        record(what, want == got, want + " vs " + got);
    }

    private static void checkBool(String what, boolean want, boolean got) {
        record(what, want == got, want + " vs " + got);
    }

    private static void record(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + what);
        } else {
            fail++;
            System.out.println("  FAIL  " + what + "  (期望/实际 " + detail + ")");
        }
    }
}
