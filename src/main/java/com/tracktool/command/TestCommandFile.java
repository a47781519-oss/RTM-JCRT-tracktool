package com.tracktool.command;

import com.tracktool.TrackToolConfig;
import com.tracktool.TrackToolCore;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Development/acceptance harness: runs commands written into a file.
 *
 * <p>The game window cannot always be driven by synthetic keyboard input (the
 * Windows input desktop and IME can swallow injected keys), so acceptance tests
 * are driven from the file system instead: write a line into
 * {@code config/tracktool/commands.txt} and it is executed on the next second,
 * as the first online player. Disabled by default -
 * see {@code testCommandFile} in {@code config/tracktool.cfg}.</p>
 */
public class TestCommandFile {

    private static final File FILE = new File("config/tracktool/commands.txt");
    private static long lastCheck;

    public TestCommandFile() {
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !TrackToolConfig.testCommandFile) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCheck < 1000L) {
            return;
        }
        lastCheck = now;
        MinecraftServer server = net.minecraftforge.fml.common.FMLCommonHandler.instance()
                .getMinecraftServerInstance();
        if (server == null || !FILE.isFile()) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(FILE.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        if (lines.isEmpty()) {
            return;
        }
        // Consume the file first so a slow command cannot be executed twice.
        try {
            Files.move(FILE.toPath(), new File(FILE.getParentFile(), "commands.done.txt").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // keep going; worst case the command runs twice
        }
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            // "@PlayerName command" runs the command as that player, so a
            // two-player scenario can be driven from a single file.
            EntityPlayerMP target = null;
            if (line.startsWith("@")) {
                int sp = line.indexOf(' ');
                if (sp > 0) {
                    String name = line.substring(1, sp);
                    target = server.getPlayerList().getPlayerByUsername(name);
                    line = line.substring(sp + 1).trim();
                    if (target == null) {
                        TrackToolCore.warn("[testcmd] no such player: %s", name);
                        continue;
                    }
                }
            }
            if (target == null && !server.getPlayerList().getPlayers().isEmpty()) {
                target = server.getPlayerList().getPlayers().get(0);
            }
            try {
                net.minecraft.command.ICommandSender sender = target != null ? target : server;
                server.getCommandManager().executeCommand(sender, line);
                TrackToolCore.info("[testcmd] %s%s", target == null ? "" : target.getName() + " ", line);
            } catch (Throwable t) {
                TrackToolCore.warn("[testcmd] failed: %s -> %s", line, t.toString());
            }
        }
    }
}
