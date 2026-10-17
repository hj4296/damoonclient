package net.damoon.core.gui;

import net.damoon.core.DamoonCore;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.client.GuiModList;

import java.io.IOException;

/** Damoon 메인 메뉴 (1.8.9) */
public class GuiDamoonMainMenu extends GuiScreen {
    private static final ResourceLocation LOGO = new ResourceLocation("damoon", "textures/gui/logo.png");
    private long openedAt;

    @Override
    public void initGui() {
        openedAt = System.currentTimeMillis();
        int w = 200, x = this.width / 2 - w / 2, y = this.height / 2 + 4;
        this.buttonList.clear();
        this.buttonList.add(new GuiButton(1, x, y, w, 20, I18n.format("menu.singleplayer")));
        this.buttonList.add(new GuiButton(2, x, y + 24, w, 20, I18n.format("menu.multiplayer")));
        this.buttonList.add(new GuiButton(3, x, y + 48, w / 2 - 2, 20, "✦ Damoon 설정"));
        this.buttonList.add(new GuiButton(6, x + w / 2 + 2, y + 48, w / 2 - 2, 20, "모드 목록"));
        this.buttonList.add(new GuiButton(4, x, y + 76, w / 2 - 2, 20, I18n.format("menu.options")));
        this.buttonList.add(new GuiButton(5, x + w / 2 + 2, y + 76, w / 2 - 2, 20, I18n.format("menu.quit")));
    }

    @Override
    protected void actionPerformed(GuiButton b) throws IOException {
        switch (b.id) {
            case 1: this.mc.displayGuiScreen(new GuiSelectWorld(this)); break;
            case 2: this.mc.displayGuiScreen(new GuiMultiplayer(this)); break;
            case 3: this.mc.displayGuiScreen(new GuiDamoonMenu(this)); break;
            case 4: this.mc.displayGuiScreen(new GuiOptions(this, this.mc.gameSettings)); break;
            case 5: this.mc.shutdown(); break;
            case 6: this.mc.displayGuiScreen(new GuiModList(this)); break;
            default: break;
        }
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        // ESC로 메인 메뉴가 닫히지 않게
        if (key != 1) super.keyTyped(c, key);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawGradientRect(0, 0, this.width, this.height, 0xFF0B0D14, 0xFF1A2030);
        long t = System.currentTimeMillis();
        for (int i = 0; i < 70; i++) {
            int sx = (int) ((i * 7919L) % Math.max(1, this.width));
            int sy = (int) ((i * 104729L) % Math.max(1, this.height * 2 / 3));
            int a = (int) (90 + 80 * Math.sin(t / 900.0 + i));
            Gui.drawRect(sx, sy, sx + 1, sy + 1, (a << 24) | 0xFFFFFF);
        }

        float fade = Math.min(1f, (System.currentTimeMillis() - openedAt) / 600f);
        int alpha = Math.max(4, (int) (fade * 255)) << 24;
        int cx = this.width / 2, top = this.height / 2 - 96;

        GlStateManager.enableBlend();
        GlStateManager.color(1f, 1f, 1f, fade);
        this.mc.getTextureManager().bindTexture(LOGO);
        Gui.drawModalRectWithCustomSizedTexture(cx - 92, top, 0, 0, 56, 56, 56, 56);
        GlStateManager.color(1f, 1f, 1f, 1f);

        GlStateManager.pushMatrix();
        GlStateManager.scale(3f, 3f, 1f);
        this.fontRendererObj.drawStringWithShadow("DAMOON", (cx - 28) / 3f, (top + 8) / 3f, 0xFFFFFF | alpha);
        GlStateManager.popMatrix();
        this.fontRendererObj.drawString("C  L  I  E  N  T", cx - 26, top + 40, 0xF3C969 | alpha);

        this.fontRendererObj.drawString("Minecraft 1.8.9  ·  Damoon Core " + DamoonCore.VERSION, 4, this.height - 12, 0x8B93A7 | alpha);
        String c = "Copyright Mojang AB. Do not distribute!";
        this.fontRendererObj.drawString(c, this.width - this.fontRendererObj.getStringWidth(c) - 4, this.height - 12, 0x8B93A7 | alpha);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
