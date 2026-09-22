package com.tracktool.item;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * The dedicated "RTM铺轨" creative category.
 *
 * <p>RTM has its own tabs ({@code rtm_railway}, {@code rtm_industry},
 * {@code rtm_tools}) but they belong to RTM's class, so track-tool brings its
 * own tab instead of injecting into someone else's. The label
 * {@code rtm_track} resolves through {@code itemGroup.rtm_track}, which the
 * language files translate as "RTM铺轨".</p>
 */
public final class ModCreativeTabs extends CreativeTabs {

    public static final CreativeTabs RTM_TRACK = new ModCreativeTabs("rtm_track");

    private ModCreativeTabs(String label) {
        super(label);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public ItemStack createIcon() {
        return ModItems.railStaff == null ? ItemStack.EMPTY : new ItemStack(ModItems.railStaff);
    }
}
