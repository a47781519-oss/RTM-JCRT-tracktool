package com.tracktool.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;

import java.util.List;
import java.util.function.Consumer;

/**
 * Minimal drop-down list widget, drawn with flat rectangles so it needs no
 * texture and cannot be mis-aligned with its hit box.
 */
public class Dropdown extends Gui {

    public interface Provider {
        List<String> options();
    }

    public final Provider provider;
    public final Consumer<Integer> onSelect;
    public int x;
    public int y;
    public int width;
    public int height = 14;
    public int selected;
    public boolean open;
    public boolean enabled = true;
    public String tooltip;
    private int scroll;

    public Dropdown(int x, int y, int width, Provider provider, Consumer<Integer> onSelect) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.provider = provider;
        this.onSelect = onSelect;
    }

    public void move(int nx, int ny) {
        this.x = nx;
        this.y = ny;
        this.open = false;
    }

    public String current() {
        List<String> o = this.provider.options();
        if (o.isEmpty()) {
            return "-";
        }
        if (this.selected < 0 || this.selected >= o.size()) {
            this.selected = 0;
        }
        return o.get(this.selected);
    }

    public boolean mouseClicked(int mx, int my) {
        if (!this.enabled) {
            return false;
        }
        List<String> o = this.provider.options();
        if (this.open) {
            int listY = this.y + this.height;
            int visible = Math.min(o.size(), 8);
            for (int i = 0; i < visible; i++) {
                int iy = listY + i * this.height;
                if (mx >= this.x && mx < this.x + this.width && my >= iy && my < iy + this.height) {
                    this.selected = i + this.scroll;
                    this.open = false;
                    this.onSelect.accept(this.selected);
                    return true;
                }
            }
            this.open = false;
            return false;
        }
        if (mx >= this.x && mx < this.x + this.width && my >= this.y && my < this.y + this.height) {
            this.open = !this.open;
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(int amount) {
        if (!this.open) {
            return false;
        }
        List<String> o = this.provider.options();
        int max = Math.max(0, o.size() - 8);
        this.scroll = Math.max(0, Math.min(max, this.scroll - amount));
        return true;
    }

    public void draw(Minecraft mc, int mouseX, int mouseY) {
        if (!this.enabled) {
            return;
        }
        boolean hovered = mx(mouseX, mouseY, this.x, this.y, this.width, this.height);
        int bg = !this.enabled ? 0x55202020 : (hovered ? 0xFF4A4A4A : 0xFF2E2E2E);
        drawRect(this.x, this.y, this.x + this.width, this.y + this.height, bg);
        drawRect(this.x, this.y, this.x + this.width, this.y + 1, 0xFF6A6A6A);
        drawRect(this.x, this.y + this.height - 1, this.x + this.width, this.y + this.height, 0xFF6A6A6A);
        drawRect(this.x, this.y, this.x + 1, this.y + this.height, 0xFF6A6A6A);
        drawRect(this.x + this.width - 1, this.y, this.x + this.width, this.y + this.height, 0xFF6A6A6A);
        String text = mc.fontRenderer.trimStringToWidth(this.current(), this.width - 12);
        mc.fontRenderer.drawString(text, this.x + 4, this.y + 3, this.enabled ? 0xFFFFFF : 0x808080, false);
        // arrow
        int ax = this.x + this.width - 8;
        int ay = this.y + 5;
        for (int i = 0; i < 4; i++) {
            drawRect(ax + i, ay + i, ax + 8 - i, ay + i + 1, 0xFFCCCCCC);
        }
        if (!this.open) {
            return;
        }
        List<String> o = this.provider.options();
        int visible = Math.min(o.size(), 8);
        int listY = this.y + this.height;
        drawRect(this.x, listY, this.x + this.width, listY + visible * this.height, 0xEE101010);
        for (int i = 0; i < visible; i++) {
            int idx = i + this.scroll;
            if (idx >= o.size()) {
                break;
            }
            int iy = listY + i * this.height;
            boolean h = mx(mouseX, mouseY, this.x, iy, this.width, this.height);
            if (h) {
                drawRect(this.x + 1, iy, this.x + this.width - 1, iy + this.height, 0xFF33507A);
            }
            mc.fontRenderer.drawString(mc.fontRenderer.trimStringToWidth(o.get(idx), this.width - 8),
                    this.x + 4, iy + 3, idx == this.selected ? 0x9CC6FF : 0xFFFFFF, false);
        }
    }

    public void drawTooltip(GuiScreen screen, int mouseX, int mouseY) {
        if (this.tooltip != null && !this.open
                && mx(mouseX, mouseY, this.x, this.y, this.width, this.height)) {
            screen.drawHoveringText(java.util.Collections.singletonList(this.tooltip), mouseX, mouseY);
        }
    }

    private static boolean mx(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseY >= y && mouseX < x + w && mouseY < y + h;
    }
}
