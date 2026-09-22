package com.tracktool.client;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 客户端专用：方块表装好之后，数一数有多少格在<b>客户端世界</b>里取不到底座 TE。
 *
 * <p>RTM 对这些格子会画 {@code renderMissingBlock} —— 用的正是<b>基岩贴图</b>，
 * 也就是玩家看到的"基岩路基"。</p>
 *
 * <p><b>为什么单独一个类</b>（第 64 轮服务器实测）：这段代码里的
 * {@code Minecraft.getMinecraft().world} 字段类型是
 * {@code net.minecraft.client.multiplayer.WorldClient}。只要这行字节码待在
 * {@code ExactRailInjector} 里，JVM <b>校验该类时</b>就要加载 {@code WorldClient} 来做
 * 赋值兼容性检查；专用服务端上根本没有这个类 ⇒
 * {@code NoClassDefFoundError: net/minecraft/client/multiplayer/WorldClient}
 * ⇒ 整个 {@code ExactRailInjector} 不可用 ⇒ 铺设时 {@code place()} 一碰它就抛异常、
 * 回退到旧分段路径（服务器日志实证：<i>place 抛异常 … 未走新路径 ⇒ 旧分段路径</i>），
 * 表现就是接头折角、两段错开、上坡不平滑。</p>
 *
 * <p>所以客户端专有的东西必须单独成类，由 {@code ExactRailInjector} <b>反射</b>调用：
 * 专用服务端上这个类压根不会被加载。</p>
 */
public final class ClientMissingReport {

    private ClientMissingReport() {
    }

    /** 由 {@code ExactRailInjector.reportMissing} 反射调用。 */
    public static void report(int[][] table) {
        World world = net.minecraft.client.Minecraft.getMinecraft().world;
        if (world == null || table == null) {
            return;
        }
        int miss = 0;
        int[] first = null;
        for (int[] b : table) {
            BlockPos p = new BlockPos(b[0], b[1], b[2]);
            if (!(world.getBlockState(p).getBlock() instanceof jp.ngt.rtm.rail.BlockLargeRailBase)
                    || world.getTileEntity(p) == null) {
                miss++;
                if (first == null) {
                    first = b;
                }
            }
        }
        if (miss > 0 && first != null) {
            BlockPos p = new BlockPos(first[0], first[1], first[2]);
            System.out.println("[tracktool-exact] 客户端方块表缺 " + miss + "/" + table.length
                    + " 格（RTM 会把它们画成基岩），首个 " + p + "="
                    + world.getBlockState(p).getBlock().getRegistryName()
                    + " te=" + (world.getTileEntity(p) == null ? "null" : "ok"));
        }
    }
}
