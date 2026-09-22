package com.tracktool.client;

import com.tracktool.net.Packets;
import com.tracktool.rail.RailEnd;
import com.tracktool.rail.TrackSpec;
import com.tracktool.rail.plan.PlanBuilder;
import com.tracktool.rail.plan.RailPlan;

/**
 * Client-side mirror of the authoritative session plus the locally predicted
 * preview plan.
 *
 * <p>The preview is computed from exactly the parameters the server echoed back
 * with exactly the same code, so what the player sees is what will be laid. It
 * is a prediction only: the server recomputes everything before placing.</p>
 */
public final class ClientState {

    public static Packets.State state = new Packets.State();
    public static RailPlan plan = new RailPlan();
    public static boolean hasPlan;
    public static int progressDone;
    public static int progressTotal;
    public static boolean placing;
    public static String lastResult;
    public static long lastResultTime;
    public static long lastParamSync;

    /** Local, optimistic spec so the GUI reacts instantly. */
    public static TrackSpec localSpec = new TrackSpec();

    private ClientState() {
    }

    public static void onState(Packets.State msg) {
        state = msg;
        localSpec = msg.spec;
        rebuild();
        if (!msg.busy && placing) {
            placing = false;
        }
        if (msg.busy) {
            placing = true;
        }
        if (msg.lastMessage != null && msg.lastMessage.length() > 0) {
            net.minecraft.client.Minecraft.getMinecraft().player.sendMessage(
                    new net.minecraft.util.text.TextComponentTranslation(msg.lastMessage));
        }
    }

    public static void onProgress(Packets.Progress msg) {
        progressDone = msg.done;
        progressTotal = msg.total;
        placing = msg.done < msg.total;
    }

    public static void onResult(Packets.Result msg) {
        placing = false;
        lastResult = msg.messageKey;
        lastResultTime = System.currentTimeMillis();
        if (!msg.ok && msg.messageKey != null && msg.messageKey.length() > 0) {
            net.minecraft.client.Minecraft.getMinecraft().player.sendMessage(
                    new net.minecraft.util.text.TextComponentTranslation(msg.messageKey));
        }
    }

    /** Recomputes the preview from the current parameters. */
    public static void rebuild() {
        RailEnd from = state.ends.isEmpty() ? null : state.ends.get(0);
        RailEnd to = state.ends.size() < 2 ? null : state.ends.get(1);
        if (from == null) {
            plan = new RailPlan();
            hasPlan = false;
            return;
        }
        TrackSpec spec = localSpec == null ? new TrackSpec() : localSpec;
        if (spec.mode == TrackSpec.MODE_CONNECT && to == null) {
            plan = new RailPlan();
            hasPlan = false;
            return;
        }
        if (spec.mode != TrackSpec.MODE_CONNECT && to != null) {
            // Only connection mode accepts two ends.
            spec = localSpec;
        }
        RailPlan p = PlanBuilder.build(spec, from, to);
        plan = p;
        hasPlan = p.ok && !p.isEmpty();
    }

    public static void setLocalSpec(TrackSpec spec) {
        localSpec = spec;
        rebuild();
    }

    public static boolean isOperator() {
        return state.operator;
    }
}
