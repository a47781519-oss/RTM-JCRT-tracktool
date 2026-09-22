package com.tracktool.client;

import com.tracktool.GuiBridge;
import com.tracktool.TrackToolCore;
import com.tracktool.client.gui.GuiRailStaff;
import com.tracktool.item.ItemRailStaff;
import com.tracktool.net.Packets;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** Wires the network sink and the GUI opener to client-only classes. */
@SideOnly(Side.CLIENT)
public final class ClientBridge implements Packets.ClientSink, GuiBridge.Opener {

    public static final ClientBridge INSTANCE = new ClientBridge();

    private ClientBridge() {
    }

    public static void install() {
        Packets.setScheduler(task -> Minecraft.getMinecraft().addScheduledTask(task));
        Packets.setClientSink(INSTANCE);
        GuiBridge.install(INSTANCE);
    }

    @Override
    public void openIfStaff(EntityPlayer player) {
        ItemStack stack = player.getHeldItemMainhand();
        if (!(stack.getItem() instanceof ItemRailStaff)) {
            stack = player.getHeldItemOffhand();
            if (!(stack.getItem() instanceof ItemRailStaff)) {
                return;
            }
        }
        Minecraft.getMinecraft().displayGuiScreen(new GuiRailStaff());
    }

    @Override
    public void open(EntityPlayer player) {
        openIfStaff(player);
    }

    @Override
    public void onState(Packets.State msg) {
        ClientState.onState(msg);
        if (msg.openGui) {
            Minecraft.getMinecraft().displayGuiScreen(new GuiRailStaff());
            return;
        }
        if (Minecraft.getMinecraft().currentScreen instanceof GuiRailStaff) {
            ((GuiRailStaff) Minecraft.getMinecraft().currentScreen).onStateChanged();
        }
    }

    @Override
    public void onProgress(Packets.Progress msg) {
        ClientState.onProgress(msg);
    }

    @Override
    public void onResult(Packets.Result msg) {
        ClientState.onResult(msg);
        if (msg.ok) {
            Minecraft.getMinecraft().player.sendMessage(new TextComponentTranslation(
                    "tracktool.msg.placed", String.valueOf(msg.segments), String.valueOf(msg.blocks)));
        }
    }

    static void log(String s) {
        TrackToolCore.info("%s", s);
    }
}
