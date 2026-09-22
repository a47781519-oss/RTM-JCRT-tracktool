package com.tracktool.client;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;

/**
 * Optional convenience keys. The brief requires buttons and commands rather
 * than vanilla keys, so both bindings are unbound by default and the mod stays
 * fully usable through the GUI and the {@code /tracktool} command.
 */
@SideOnly(Side.CLIENT)
public final class KeyBindings {

    public static KeyBinding openGui;
    public static KeyBinding cancel;

    private KeyBindings() {
    }

    public static void init() {
        openGui = new KeyBinding("key.tracktool.open", Keyboard.KEY_G, "key.categories.tracktool");
        cancel = new KeyBinding("key.tracktool.cancel", Keyboard.KEY_NONE, "key.categories.tracktool");
        ClientRegistry.registerKeyBinding(openGui);
        ClientRegistry.registerKeyBinding(cancel);
    }
}
