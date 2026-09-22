package com.tracktool;

import com.tracktool.command.CommandTrackTool;
import com.tracktool.item.ModItems;
import com.tracktool.net.TrackToolNetwork;
import com.tracktool.rail.PlacementQueue;
import com.tracktool.rail.SelectionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import org.apache.logging.log4j.Logger;

/**
 * track-tool - an RTM add-on that assists laying RTM large rails.
 *
 * <p>Everything the mod builds is plain RealTrainMod data: a chain of
 * {@code RailPosition} pairs persisted in {@code TileEntityLargeRailCore}, so
 * vehicles, model packs, other add-ons and the vanilla RTM rail item keep
 * working with no special cases.</p>
 */
@Mod(modid = TrackToolCore.MODID, name = TrackToolCore.NAME, version = TrackToolCore.VERSION,
        acceptedMinecraftVersions = "[1.12.2]", dependencies = "required-after:rtm")
public final class TrackToolCore {

    public static final String MODID = "tracktool";
    public static final String NAME = "track-tool";
    public static final String VERSION = "0.1.4";

    @Mod.Instance(TrackToolCore.MODID)
    public static TrackToolCore instance;

    @SidedProxy(clientSide = "com.tracktool.client.ClientProxy", serverSide = "com.tracktool.CommonProxy")
    public static CommonProxy proxy;

    /**
     * track-tool's own network channel. RealTrainMod already occupies the
     * {@code "rtm"} channel, so an add-on must never register messages there.
     */
    public static final net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper NETWORK =
            net.minecraftforge.fml.common.network.NetworkRegistry.INSTANCE.newSimpleChannel(MODID);

    private static Logger logger;

    public static Logger log() {
        return logger;
    }

    public static void info(String msg, Object... args) {
        if (logger != null) {
            logger.info(String.format(msg, args));
        }
    }

    public static void warn(String msg, Object... args) {
        if (logger != null) {
            logger.warn(String.format(msg, args));
        }
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        TrackToolConfig.load(event.getSuggestedConfigurationFile());
        TrackToolNetwork.init();
        ModItems.init();
        proxy.preInit(event);
        info("track-tool %s preInit done", VERSION);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(SelectionManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(PlacementQueue.INSTANCE);
        MinecraftForge.EVENT_BUS.register(new com.tracktool.command.TestCommandFile());
        // 读档后把自定几何补回核心，并向附近玩家补发采样点表（否则重进世界 railmap 会退回贝塞尔）
        MinecraftForge.EVENT_BUS.register(new com.tracktool.rail2.ExactRailServerSync());
        // 区块加载时清掉"没有 railPositions 的空核心"：RTM 的 writeRailData 不判空，
        // 留着一颗就会让服务端在打包区块时 NPE，存档再也进不去（第 62 轮实测）
        MinecraftForge.EVENT_BUS.register(new com.tracktool.rail2.BrokenCoreSweeper());
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
        info("track-tool %s ready (RTM rail mode)", VERSION);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandTrackTool());
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        SelectionManager.INSTANCE.clearAll();
        PlacementQueue.INSTANCE.clearAll();
    }
}
