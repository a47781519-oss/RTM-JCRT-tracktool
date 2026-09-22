package com.tracktool.client;

import com.tracktool.CommonProxy;
import com.tracktool.TrackToolCore;
import com.tracktool.item.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** Client proxy: item models, overlay renderer, key bindings and the GUI bridge. */
@SideOnly(Side.CLIENT)
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        ClientBridge.install();
        registerItemModel(ModItems.railStaff, "rail_staff");
    }

    @Override
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new RailOverlayRenderer());
        MinecraftForge.EVENT_BUS.register(new ClientTickHandler());
        KeyBindings.init();
    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
        TrackToolCore.info("client ready");
    }

    @Override
    public boolean isDedicatedServer() {
        return false;
    }

    private static void registerItemModel(Item item, String name) {
        ModelLoader.setCustomModelResourceLocation(item, 0,
                new ModelResourceLocation(TrackToolCore.MODID + ":" + name, "inventory"));
    }

    public static Minecraft mc() {
        return Minecraft.getMinecraft();
    }
}
