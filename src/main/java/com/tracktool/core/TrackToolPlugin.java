package com.tracktool.core;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import java.util.Map;

/** track-tool 的 ASM 核心插件：在类加载期改写 RTM 的轨道几何相关方法。
 *
 *  <p>为什么要 ASM：此前尝试"自己写一个 RailMap 子类 + 反射注入 railmap 字段"，
 *  落库能成功但**渲染侧画不出模型**（表现为"只见线框/全透明"），
 *  说明 RTM 的渲染通道对 RailMap 的实现有硬性假设。ASM 可以直接把 RTM 的
 *  几何求值替换成我们的解析线形，绕开这类假设。</p>
 *
 *  <p>当前阶段：**只搭骨架**（transformer 原样返回，不做任何修改）⇒ 对游戏零影响。
 *  后续按证据逐个加入改写目标，且每个目标都要有开关。</p> */
@IFMLLoadingPlugin.Name("tracktool-core")
@IFMLLoadingPlugin.MCVersion("1.12.2")
public final class TrackToolPlugin implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        return new String[]{"com.tracktool.core.ExactRailTransformer"};
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}