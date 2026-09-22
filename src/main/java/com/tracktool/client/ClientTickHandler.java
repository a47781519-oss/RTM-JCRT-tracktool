package com.tracktool.client;

import com.tracktool.client.gui.GuiRailStaff;
import com.tracktool.net.Packets;
import com.tracktool.rail.SelectionManager;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** Client tick / input glue. */
@SideOnly(Side.CLIENT)
public class ClientTickHandler {

    private static boolean lastStaff;
    private static boolean langChecked;

    /** One-shot language diagnostic, logged once the world is up. */
    private static void checkLang(Minecraft mc) {
        if (langChecked || mc.player == null) {
            return;
        }
        langChecked = true;
        String title = net.minecraft.client.resources.I18n.format("tracktool.gui.title");
        com.tracktool.TrackToolCore.info("i18n check: locale=%s title='%s'", mc.gameSettings.language, title);
        StringBuilder domains = new StringBuilder();
        for (String d : mc.getResourceManager().getResourceDomains()) {
            domains.append(d).append(' ');
        }
        com.tracktool.TrackToolCore.info("i18n domains: %s", domains);
        com.tracktool.TrackToolCore.info("i18n currentLanguage=%s gsLanguage=%s pathCase=%s",
                mc.getLanguageManager().getCurrentLanguage().getLanguageCode(),
                mc.gameSettings.language,
                new net.minecraft.util.ResourceLocation("tracktool", "lang/zh_CN.lang").getPath());
        probe(mc, "tracktool", "models/item/rail_staff.json");
        probe(mc, "tracktool", "textures/items/rail_staff.png");
        probe(mc, "tracktool", "lang/zh_cn.lang");
        probe(mc, "tracktool", "lang/zh_CN.lang");
        probe(mc, "tracktool", "lang/en_us.lang");
        probe(mc, "tracktool", "lang/zzz.txt");
        probe(mc, "tracktool", "pack.mcmeta");
        probe(mc, "rtm", "lang/zh_CN.lang");
        probe(mc, "rtm", "lang/zh_cn.lang");
        probe(mc, "minecraft", "lang/zh_cn.lang");
    }

    private static void probe(Minecraft mc, String domain, String path) {
        net.minecraft.util.ResourceLocation loc = new net.minecraft.util.ResourceLocation(domain, path);
        String exists;
        try {
            exists = String.valueOf(mc.getResourceManager().getResource(loc) != null);
        } catch (Throwable t) {
            exists = t.getClass().getSimpleName();
        }
        com.tracktool.TrackToolCore.info("i18n probe %s -> %s", loc, exists);
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.currentScreen != null) {
            return;
        }
        if (KeyBindings.openGui != null && KeyBindings.openGui.isPressed()) {
            ClientBridge.INSTANCE.openIfStaff(mc.player);
        }
        if (KeyBindings.cancel != null && KeyBindings.cancel.isPressed()) {
            Packets.sendToServer(new Packets.Action(SelectionManager.ACTION_CLEAR));
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        // A 方案：为附近"自定几何"的轨道核心补注入（默认关闭；开启后每 16 tick 扫一次，半径 64）
        net.minecraft.client.Minecraft ttmc = net.minecraft.client.Minecraft.getMinecraft();
        if (ttmc.player != null && event.phase == TickEvent.Phase.END && (ttmc.player.ticksExisted & 15) == 0) {
            // 半径 128；带采样点表的核心不受半径限制（见 swapNearbyClient）
            com.tracktool.rail2.ExactRailGate.clientTick(ttmc.player.world,
                    ttmc.player.posX, ttmc.player.posY, ttmc.player.posZ, 128.0D);
        }
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) {
            return;
        }
        // 轨道 TE 防护：防止区块数据包在 Chunk.read 里被 RTM 的 getBlockType()==null 打断（见 RailTileGuard）
        RailTileGuard.tick(mc);
        // /tracktool test clientcheck 请求的客户端自检（结果直接打到聊天栏，也会进 latest.log）
        if (com.tracktool.rail2.ExactRailInjector.diagRequested) {
            com.tracktool.rail2.ExactRailInjector.diagRequested = false;
            java.util.List<String> lines = new java.util.ArrayList<String>();
            com.tracktool.rail2.ExactRailInjector.clientDiag(mc.player.world,
                    mc.player.posX, mc.player.posY, mc.player.posZ, lines);
            for (String s : lines) {
                mc.player.sendMessage(new net.minecraft.util.text.TextComponentString(s));
            }
        }
        checkLang(mc);
        boolean holding = mc.player.getHeldItemMainhand().getItem() instanceof com.tracktool.item.ItemRailStaff
                || mc.player.getHeldItemOffhand().getItem() instanceof com.tracktool.item.ItemRailStaff;
        if (holding != lastStaff) {
            lastStaff = holding;
            if (!holding && mc.currentScreen instanceof GuiRailStaff) {
                mc.displayGuiScreen(null);
            }
        }
    }
}
