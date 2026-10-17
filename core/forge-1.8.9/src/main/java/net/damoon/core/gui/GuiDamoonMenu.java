package net.damoon.core.gui;

import net.damoon.core.Config;
import net.damoon.core.DamoonCore;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import java.io.IOException;

/** 오른쪽 Shift로 여는 Damoon 메뉴: 기능 켜기/끄기 + HUD 위치 편집 */
public class GuiDamoonMenu extends GuiScreen {
    private static final int EDIT = 100, DONE = 101;
    private final GuiScreen parent;

    public GuiDamoonMenu(GuiScreen parent) {
        this.parent = parent;
    }

    private static String label(String[] m) {
        boolean on = Config.on(m[0]);
        return (on ? "§a● " : "§7○ ") + "§f" + m[1] + (on ? "  §a켜짐" : "  §7꺼짐");
    }

    private int top() {
        return Math.max(48, this.height / 2 - 90);
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        int cols = 2, bw = 150, gap = 6;
        int x0 = this.width / 2 - (cols * bw + gap) / 2, y0 = top();
        String[][] mods = Config.MODULES;
        for (int i = 0; i < mods.length; i++) {
            this.buttonList.add(new GuiButton(i, x0 + (i % cols) * (bw + gap), y0 + (i / cols) * 24, bw, 20, label(mods[i])));
        }
        int by = y0 + ((mods.length + 1) / cols) * 24 + 10;
        GuiButton edit = new GuiButton(EDIT, x0, by, bw, 20, "✥ HUD 위치 편집");
        edit.enabled = this.mc.thePlayer != null;
        this.buttonList.add(edit);
        this.buttonList.add(new GuiButton(DONE, x0 + bw + gap, by, bw, 20, "완료"));
    }

    @Override
    protected void actionPerformed(GuiButton b) throws IOException {
        if (b.id == EDIT) {
            this.mc.displayGuiScreen(new GuiHudEdit(this));
        } else if (b.id == DONE) {
            close();
        } else if (b.id >= 0 && b.id < Config.MODULES.length) {
            Config.toggle(Config.MODULES[b.id][0]);
            b.displayString = label(Config.MODULES[b.id]);
        }
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == 1) close();
    }

    private void close() {
        Config.save();
        this.mc.displayGuiScreen(parent);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (this.mc.theWorld == null) this.drawGradientRect(0, 0, this.width, this.height, 0xFF0B0D14, 0xFF1A2030);
        else drawRect(0, 0, this.width, this.height, 0xA0080A10);
        int y = top() - 30;
        this.drawCenteredString(this.fontRendererObj, "§lDAMOON §r§6CLIENT", this.width / 2, y, 0xFFFFFF);
        this.drawCenteredString(this.fontRendererObj, "줌: C (휠로 배율)  ·  메뉴: 오른쪽 Shift  ·  v" + DamoonCore.VERSION,
                this.width / 2, y + 13, 0x8B93A7);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
