package com.tracktool;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

/** All tunables of track-tool, read from {@code config/tracktool.cfg}. */
public final class TrackToolConfig {

    /** Maximum distance (blocks) the staff ray-marches to find a rail. */
    public static int selectDistance = 16;
    /** Fraction of the rail arc that counts as "middle"; |t-0.5| below this is rejected. */
    public static float middleDeadZone = 0.20f;
    /** Default number of rail cores requested per tick by the deferred placement queue. */
    public static int segmentsPerTick = 4;
    /** Hard budget of block writes per tick while placing. */
    public static int blocksPerTick = 3000;
    /** Default segment length (metres) used when turning an alignment into rail cores. */
    public static double segmentLength = 20.0D;
    /** Draw the translucent dark backdrop behind the GUI panel. */
    public static boolean guiBackdrop = true;
    /** Alpha of the GUI backdrop (0-255). */
    public static int guiBackdropAlpha = 140;
    /** Render the blue translucent preview of the planned rail. */
    public static boolean previewEnabled = true;
    /** Maximum number of undo steps kept per player. */
    public static int maxUndo = 8;
    /** Milliseconds a selection survives without activity before it is dropped. */
    public static long selectionTimeoutMs = 10L * 60L * 1000L;
    /** Packets per second allowed from one client (anti-spam). */
    public static int packetRateLimit = 40;
    /** Force-load chunks that a queued placement touches, so the whole curve gets built. */
    public static boolean forceLoadChunks = true;
    /**
     * 铺完一段后自动清除周围多少格内的<b>无主</b>旧轨道路基（0 = 关闭）。
     * 只清找不到核心的底座方块；还连着核心的轨道（包括邻近的平行线）永不动。
     * 原因见 {@code OrphanRoadbedSweeper}。
     */
    public static int roadbedSweepRadius = 50;
    /**
     * Test harness: execute commands written into config/tracktool/commands.txt.
     * Off by default and only meant for automated acceptance runs.
     */
    public static boolean testCommandFile = false;

    private TrackToolConfig() {
    }

    public static void load(File file) {
        Configuration cfg = new Configuration(file);
        try {
            cfg.load();
            selectDistance = cfg.getInt("selectDistance", "general", selectDistance, 1, 64,
                    "Ray distance (blocks) used when selecting a rail end with the staff.");
            middleDeadZone = cfg.getFloat("middleDeadZone", "general", middleDeadZone, 0.0F, 0.49F,
                    "Clicking the middle of a rail (|t-0.5| < this) is rejected with 'select an end'.");
            segmentLength = cfg.getFloat("segmentLength", "general", (float) segmentLength, 2.0F, 200.0F,
                    "Length (metres) of one generated RTM rail core. Shorter = smoother + cheaper lookups.");
            segmentsPerTick = cfg.getInt("segmentsPerTick", "performance", segmentsPerTick, 1, 64,
                    "Rail cores placed per server tick by the deferred queue.");
            blocksPerTick = cfg.getInt("blocksPerTick", "performance", blocksPerTick, 256, 65536,
                    "Maximum block writes per server tick while placing.");
            forceLoadChunks = cfg.getBoolean("forceLoadChunks", "performance", forceLoadChunks,
                    "Load chunks touched by a queued placement so long curves are always completed.");
            guiBackdrop = cfg.getBoolean("guiBackdrop", "gui", guiBackdrop,
                    "Draw a translucent backdrop behind the GUI panel (background stays see-through).");
            guiBackdropAlpha = cfg.getInt("guiBackdropAlpha", "gui", guiBackdropAlpha, 0, 255,
                    "Alpha of the GUI backdrop, 0 = fully transparent.");
            previewEnabled = cfg.getBoolean("previewEnabled", "gui", previewEnabled,
                    "Render the blue translucent preview of the rail that would be generated.");
            maxUndo = cfg.getInt("maxUndo", "general", maxUndo, 1, 64, "Undo steps kept per player.");
            selectionTimeoutMs = cfg.getInt("selectionTimeoutSeconds", "general", (int) (selectionTimeoutMs / 1000L),
                    30, 86400, "Seconds before an idle selection is dropped.") * 1000L;
            packetRateLimit = cfg.getInt("packetRateLimit", "general", packetRateLimit, 5, 400,
                    "Maximum accepted packets per second from a single player.");
            roadbedSweepRadius = cfg.getInt("roadbedSweepRadius", "general", roadbedSweepRadius, 0, 128,
                    "After each placement, clear ORPHANED old rail base blocks (no owning core) "
                            + "within this many blocks. They have a solid hitbox and block trains. "
                            + "0 disables. Rails that still have their core are never touched.");
            testCommandFile = cfg.getBoolean("testCommandFile", "debug", testCommandFile,
                    "Development harness: run commands written to config/tracktool/commands.txt.");
        } catch (Exception e) {
            TrackToolCore.warn("config load failed: %s", e.toString());
        } finally {
            if (cfg.hasChanged()) {
                cfg.save();
            }
        }
    }
}
