package com.tracktool.core;

import net.minecraft.launchwrapper.IClassTransformer;

/** RTM 轨道几何的 ASM 改写器（骨架）。
 *
 *  <p>计划中的改写目标（每个都要先在离线验证、并且可开关）：</p>
 *  <ol>
 *    <li>{@code jp.ngt.rtm.rail.util.RailMapBasic} 的几何取值方法
 *        （{@code getRailPos/getRailHeight/getRailYaw/getRailPitch/getRailRoll}）——
 *        让它们改为"按解析线形求值"，从而**从根本上消除半格网格量化**，
 *        且**保持对象类型仍是 RailMapBasic**（渲染与车辆都按原样工作）。</li>
 *    <li>若第 1 条不可行，再考虑 {@code TileEntityLargeRailCore.createRailMap()}。</li>
 *  </ol>
 *
 *  <p>当前实现：原样返回（无改写）⇒ 零风险。改写逻辑将在下一轮按上述顺序加入，
 *  并通过系统属性 {@code -Dtracktool.asmRail=false} 保留关闭通道。</p> */
public final class ExactRailTransformer implements IClassTransformer {

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        return basicClass;   // 骨架阶段：不做任何修改
    }
}