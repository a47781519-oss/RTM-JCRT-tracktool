package com.tracktool.net;

import com.tracktool.TrackToolCore;
import net.minecraftforge.fml.relauncher.Side;

/**
 * track-tool's own network channel.
 *
 * <p>RealTrainMod registers its packets on the {@code "rtm"} channel with
 * discriminators derived from an internal registration counter, so an add-on
 * must never add messages there. Every message here travels on the
 * {@code "tracktool"} channel instead.</p>
 */
public final class TrackToolNetwork {

    private TrackToolNetwork() {
    }

    public static void init() {
        int id = 0;
        TrackToolCore.NETWORK.registerMessage(Packets.Select.class, Packets.Select.class, id++, Side.SERVER);
        TrackToolCore.NETWORK.registerMessage(Packets.Params.class, Packets.Params.class, id++, Side.SERVER);
        TrackToolCore.NETWORK.registerMessage(Packets.Action.class, Packets.Action.class, id++, Side.SERVER);
        TrackToolCore.NETWORK.registerMessage(Packets.State.Handler.class, Packets.State.class, id++, Side.CLIENT);
        TrackToolCore.NETWORK.registerMessage(Packets.ExactRail.Handler.class, Packets.ExactRail.class, id++, Side.CLIENT);
        TrackToolCore.NETWORK.registerMessage(Packets.Progress.Handler.class, Packets.Progress.class, id++, Side.CLIENT);
        TrackToolCore.NETWORK.registerMessage(Packets.Result.Handler.class, Packets.Result.class, id++, Side.CLIENT);
        TrackToolCore.NETWORK.registerMessage(Packets.ExactRail.Diag.Handler.class,
                Packets.ExactRail.Diag.class, id++, Side.CLIENT);
        // 新消息一律加在最后：前面的 id 不动
        TrackToolCore.NETWORK.registerMessage(Packets.ExactRailForget.Handler.class,
                Packets.ExactRailForget.class, id++, Side.CLIENT);
    }
}
