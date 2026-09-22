package com.tracktool.client.gui;

import jp.ngt.rtm.RTMResource;
import jp.ngt.rtm.modelpack.IResourceSelector;
import jp.ngt.rtm.modelpack.modelset.ModelSetRail;
import jp.ngt.rtm.modelpack.state.ResourceState;
import jp.ngt.rtm.modelpack.state.ResourceStateRail;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * 让"选轨道模型"直接用 RTM 自己的选择界面（{@code jp.ngt.rtm.gui.GuiSelectModel}）。
 *
 * <p>RTM 的选择界面只认一个 {@link IResourceSelector}：它从 {@code getResourceState()}
 * 取当前选中项，玩家点了某个模型后，它会把名字写回这个 state，再回调
 * {@code closeGui(state)}。所以这里只要提供一个"挂在铺轨权杖上的" ResourceState，
 * 在回调里把选中的名字交给 {@link GuiRailStaff} 并把界面切回去即可 —— 机制完全是 RTM 原版的，
 * 模型列表、搜索框、颜色、自定义参数全都跟玩家在 RTM 里看到的一样。</p>
 */
@SideOnly(Side.CLIENT)
public final class RailModelSelector implements IResourceSelector<ModelSetRail> {

    private final ResourceStateRail state = new ResourceStateRail(RTMResource.RAIL, null);
    private final GuiScreen parent;
    private final java.util.function.Consumer<String> onPicked;

    public RailModelSelector(GuiScreen parent, String current, java.util.function.Consumer<String> onPicked) {
        this.parent = parent;
        this.onPicked = onPicked;
        try {
            if (current != null && current.length() > 0) {
                this.state.setResourceName(current);
            } else {
                this.state.setResourceToDefault();
            }
        } catch (Throwable t) {
            this.state.setResourceToDefault();
        }
    }

    @Override
    public ResourceState<ModelSetRail> getResourceState() {
        return this.state;
    }

    @Override
    public void updateResourceState() {
        // 轨道包只在铺设时才用得到，这里不需要即时应用
    }

    @Override
    public int[] getSelectorPos() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) {
            return new int[]{0, 0, 0};
        }
        return new int[]{(int) mc.player.posX, (int) mc.player.posY, (int) mc.player.posZ};
    }

    @Override
    @SideOnly(Side.CLIENT)
    public boolean closeGui(ResourceState selected) {
        if (selected != null && this.onPicked != null) {
            String name = selected.getResourceName();
            this.onPicked.accept(name == null ? "" : name);
        }
        // 自己把界面切回铺轨面板；返回 false 让 RTM 不要再把屏幕置空
        Minecraft.getMinecraft().displayGuiScreen(this.parent);
        return false;
    }
}
