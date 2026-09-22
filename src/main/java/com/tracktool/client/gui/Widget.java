package com.tracktool.client.gui;

import net.minecraft.client.gui.GuiButton;

/** A small flat widget base used by the staff GUI (no textures, no overlap). */
public class Widget extends GuiButton {

    // ★ 不要再声明 visible：GuiButton 本身就有这个字段。
    //   之前这里声明了一个同名字段，把父类的遮蔽掉了 ——
    //   凡是按 GuiButton 类型赋值的地方（updateWidgets 的裁剪）写的是父类字段，
    //   而 drawButton 读的是本类字段（恒为 true）⇒ 滚上去的按钮照画不误，
    //   表现就是"轨道模型按钮浮在顶部状态文字上"。
    public String tooltip;
    public boolean accent;

    public Widget(int id, int x, int y, int w, int h, String text) {
        super(id, x, y, w, h, text);
    }

    public Widget tip(String t) {
        this.tooltip = t;
        return this;
    }

    public Widget accent() {
        this.accent = true;
        return this;
    }

    @Override
    public void drawButton(net.minecraft.client.Minecraft mc, int mouseX, int mouseY, float partial) {
        if (!this.visible) {
            return;
        }
        this.hovered = mouseX >= this.x && mouseY >= this.y && mouseX < this.x + this.width
                && mouseY < this.y + this.height;
        int bg;
        if (!this.enabled) {
            bg = 0x55202020;
        } else if (this.accent) {
            bg = this.hovered ? 0xFF3D7BD8 : 0xFF2B5FA8;
        } else {
            bg = this.hovered ? 0xFF4A4A4A : 0xFF2E2E2E;
        }
        drawRect(this.x, this.y, this.x + this.width, this.y + this.height, bg);
        int border = this.enabled ? (this.accent ? 0xFF9CC6FF : 0xFF6A6A6A) : 0xFF3A3A3A;
        drawRect(this.x, this.y, this.x + this.width, this.y + 1, border);
        drawRect(this.x, this.y + this.height - 1, this.x + this.width, this.y + this.height, border);
        drawRect(this.x, this.y, this.x + 1, this.y + this.height, border);
        drawRect(this.x + this.width - 1, this.y, this.x + this.width, this.y + this.height, border);
        int color = this.enabled ? 0xFFFFFF : 0x808080;
        this.drawCenteredString(mc.fontRenderer, this.displayString, this.x + this.width / 2,
                this.y + (this.height - 8) / 2, color);
    }
}
