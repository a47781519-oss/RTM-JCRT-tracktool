package com.tracktool.item;

import com.tracktool.TrackToolCore;
import jp.ngt.ngtlib.util.NGTRegHandler;
import net.minecraft.item.Item;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

/** Item registration. */
public final class ModItems {

    public static Item railStaff;

    private ModItems() {
    }

    public static void init() {
        railStaff = NGTRegHandler.register(new ItemRailStaff(), "rail_staff", "tracktool:rail_staff",
                ModCreativeTabs.RTM_TRACK, TrackToolCore.MODID);
        TrackToolCore.info("registered item %s", railStaff.getRegistryName());
    }
}
