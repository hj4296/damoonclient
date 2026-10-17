package net.damoon.core.gui;

import net.damoon.core.Config;
import net.damoon.core.hud.Hud;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import java.io.IOException;

/** HUD 요소를 마우스로 끌어서 위치 변경 (1.8.9) */
public class GuiHudEdit extends GuiScreen {
    private final GuiScreen parent;
    private Hud.Element dragging;
    private int offX, offY;

    public GuiHudEdit(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.buttonList.add(new GuiButton(0, this.width / 2 - 102, this.height - 28, 100, 20, "기본 위치로"));
        this.buttonList.add(new GuiButton(1, this.width / 2 + 2, this.height - 28, 100, 20, "완료"));
    }

    @Override
    protected void actionPerformed(GuiButton b) throws IOException {
        if (b.id == 0) Config.resetPositions();
        else close();
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == 1) close();
    }

    private void close() {
        Config.save();
        this.mc.displayGuiScreen(parent);
    }

    private static void outline(int x, int y, int w, int h, int c) {
        drawRect(x, y, x + w, y + 1, c);
        drawRect(x, y + h - 1, x + w, y + h, c);
        drawRect(x, y, x + 1, y + h, c);
        drawRect(x + w - 1, y, x + w, y + h, c);
    }

    private Hud.Element at(int mx, int my) {
        for (Hud.Element e : Hud.ELEMENTS) {
            if (!Config.on(e.id)) continue;
            int[] p = Hud.position(e, this.mc), s = e.size(this.mc);
            if (mx >= p[0] && mx < p[0] + s[0] && my >= p[1] && my < p[1] + s[1]) return e;
        }
        return null;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, this.width, this.height, 0x40000000);
        drawRect(this.width / 2, 0, this.width / 2 + 1, this.height, 0x30FFFFFF);
        drawRect(0, this.height / 2, this.width, this.height / 2 + 1, 0x30FFFFFF);
        Hud.Element hover = dragging != null ? dragging : at(mouseX, mouseY);
        for (Hud.Element e : Hud.ELEMENTS) {
            if (!Config.on(e.id)) continue;
            int[] p = Hud.position(e, this.mc), s = e.size(this.mc);
            e.draw(this.mc, p[0], p[1]);
            outline(p[0] - 1, p[1] - 1, s[0] + 2, s[1] + 2, e == hover ? 0xFFF3C969 : 0x80FFFFFF);
        }
        this.drawCenteredString(this.fontRendererObj, "HUD를 마우스로 끌어서 옮기세요", this.width / 2, 8, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) throws IOException {
        super.mouseClicked(mx, my, button);
        Hud.Element e = at(mx, my);
        if (e != null && button == 0) {
            int[] p = Hud.position(e, this.mc);
            dragging = e;
            offX = mx - p[0];
            offY = my - p[1];
        }
    }

    @Override
    protected void mouseClickMove(int mx, int my, int button, long time) {
        if (dragging == null) return;
        int[] s = dragging.size(this.mc);
        int x = mx - offX, y = my - offY;
        if (Math.abs(x + s[0] / 2 - this.width / 2) < 4) x = this.width / 2 - s[0] / 2;
        Config.setPos(dragging.id, x, y);
    }

    @Override
    protected void mouseReleased(int mx, int my, int state) {
        super.mouseReleased(mx, my, state);
        dragging = null;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
