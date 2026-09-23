package com.tracktool.rail;

import jp.ngt.rtm.rail.util.RailMapBasic;
import jp.ngt.rtm.rail.util.RailPosition;

import java.lang.reflect.Constructor;

/**
 * {@code new RailMapBasic(start, end)} 的等价替身。
 *
 * <p>fixRTM / AppleExtended 把两参构造器标成了 deprecated：它就是 {@code this(start, end, 0)}
 * 再加一句 {@code Deprecation.found(...)} —— 每调一次就往日志里打一整段堆栈
 * （第 73 轮服务器日志：9 分钟 352 段，全是预览校验 {@code PlanSegment.verify} 触发的）。</p>
 *
 * <p>这里在有三参构造器 {@code (RailPosition, RailPosition, int)} 时直接调它、版本号传 0，
 * 行为与两参版本<b>完全一致</b>（反编译实证），只是不再刷屏；原版 RTM 没有三参构造器，照旧走两参。</p>
 */
public final class RailMaps {

    private static volatile boolean probed;
    private static volatile Constructor<RailMapBasic> versioned;

    private RailMaps() {
    }

    public static RailMapBasic basic(RailPosition start, RailPosition end) {
        if (!probed) {
            try {
                versioned = RailMapBasic.class.getConstructor(RailPosition.class, RailPosition.class, int.class);
            } catch (Throwable t) {
                versioned = null;           // 原版 RTM：没有带版本号的构造器
            }
            probed = true;
        }
        Constructor<RailMapBasic> c = versioned;
        if (c != null) {
            try {
                return c.newInstance(start, end, 0);
            } catch (Throwable ignored) {
                // 落到下面的两参版本（同样的参数，异常也会同样抛出给调用方）
            }
        }
        return new RailMapBasic(start, end);
    }
}
