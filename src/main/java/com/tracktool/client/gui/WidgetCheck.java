package com.tracktool.client.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

/** Check box: a flat box with a tick, drawn without textures. */
public class WidgetCheck extends GuiButton {

    public boolean checked;
    public boolean visible = true;

    public WidgetCheck(int id, int x, int y, int w, boolean checked) {
        super(id, x, y, w, 12, "");
        this.checked = checked;
    }

    @Override
    public void drawButton(net.minecraft.client.Minecraft mc, int mouseX, int mouseY, float partial) {
        if (!this.visible) {
            return;
        }
        this.hovered = mouseX >= this.x && mouseY >= this.y && mouseX < this.x + this.width
                && mouseY < this.y + this.height;
        int bg = this.enabled ? (this.hovered ? 0xFF4A4A4A : 0xFF2E2E2E) : 0x55202020;
        drawRect(this.x, this.y, this.x + this.width, this.y + this.height, bg);
        drawRect(this.x, this.y, this.x + 1, this.y + this.height, 0xFF6A6A6A);
        drawRect(this.x, this.y, this.x + this.width, this.y + 1, 0xFF6A6A6A);
        int boxSize = this.height - 4;
        int bx = this.x + 2;
        int by = this.y + 2;
        drawRect(bx, by, bx + boxSize, by + boxSize, this.checked ? 0xFF3D7BD8 : 0xFF1A1A1A);
        drawRect(bx, by, bx + boxSize, by + 1, 0xFF888888);
        drawRect(bx, by + boxSize - 1, bx + boxSize, by + boxSize, 0xFF888888);
        drawRect(bx, by, bx + 1, by + boxSize, 0xFF888888);
        drawRect(bx + boxSize - 1, by, bx + boxSize, by + boxSize, 0xFF888888);
        if (this.checked) {
            drawRect(bx + 2, by + boxSize / 2 - 1, bx + boxSize - 2, by + boxSize / 2, 0xFFFFFFFF);
            drawRect(bx + boxSize / 2 - 1, by + 2, bx + boxSize / 2, by + boxSize - 2, 0xFFFFFFFF);
        }
        int color = this.enabled ? 0xFFFFFF : 0x808080;
        mc.fontRenderer.drawString(this.displayString, bx + boxSize + 4, this.y + 2, color, false);
    }

    /** Total width needed for the tick box plus its caption. */
    public int neededWidth(net.minecraft.client.Minecraft mc) {
        return this.height + 4 + mc.fontRenderer.getStringWidth(this.displayString) + 2;
    }

    public static WidgetCheck create(int id, int x, int y, String text, boolean checked, GuiScreen screen) {
        WidgetCheck w = new WidgetCheck(id, x, y, 60, checked);
        w.displayString = text;
        return w;
    }
}
