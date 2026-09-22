package com.tracktool;

import net.minecraft.entity.player.EntityPlayer;

/**
 * Side-safe bridge to the client GUI.
 *
 * <p>Common code (the staff item) needs to open a screen, but a dedicated
 * server must never load a client-only class. The client proxy installs an
 * opener here during init; on a dedicated server the call is simply a no-op.</p>
 */
public final class GuiBridge {

    /** Implemented by the client. */
    public interface Opener {
        void open(EntityPlayer player);

        void openIfStaff(EntityPlayer player);
    }

    private static Opener opener;

    private GuiBridge() {
    }

    public static void install(Opener o) {
        opener = o;
    }

    public static void open(EntityPlayer player) {
        if (opener != null) {
            opener.openIfStaff(player);
        }
    }

    public static boolean available() {
        return opener != null;
    }
}
