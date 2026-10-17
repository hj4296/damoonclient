package net.damoon.core.screen;

import net.damoon.core.DamoonCore;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Damoon 메인 메뉴 */
public class DamoonTitleScreen extends Screen {
    private static final ResourceLocation LOGO = ResourceLocation.fromNamespaceAndPath("damoon", "textures/gui/logo.png");
    private long openedAt;

    public DamoonTitleScreen() {
        super(Component.literal("Damoon Client"));
    }

    @Override
    protected void init() {
        openedAt = System.currentTimeMillis();
        int w = 200, x = this.width / 2 - w / 2, y = this.height / 2 + 4;
        addRenderableWidget(Button.builder(Component.translatable("menu.singleplayer"),
                b -> this.minecraft.setScreen(new SelectWorldScreen(this))).bounds(x, y, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("menu.multiplayer"),
                b -> this.minecraft.setScreen(new JoinMultiplayerScreen(this))).bounds(x, y + 24, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("✦ Damoon 설정"),
                b -> this.minecraft.setScreen(new DamoonMenuScreen(this))).bounds(x, y + 48, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("menu.options"),
                b -> this.minecraft.setScreen(new OptionsScreen(this, this.minecraft.options))).bounds(x, y + 76, w / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("menu.quit"),
                b -> this.minecraft.stop()).bounds(x + w / 2 + 2, y + 76, w / 2 - 2, 20).build());
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 밤하늘 그라데이션 + 은은한 별
        g.fillGradient(0, 0, this.width, this.height, 0xFF0B0D14, 0xFF1A2030);
        long t = System.currentTimeMillis();
        for (int i = 0; i < 70; i++) {
            int sx = (int) ((i * 7919L) % Math.max(1, this.width));
            int sy = (int) ((i * 104729L) % Math.max(1, this.height * 2 / 3));
            int a = (int) (90 + 80 * Math.sin(t / 900.0 + i));
            g.fill(sx, sy, sx + 1, sy + 1, (a << 24) | 0xFFFFFF);
        }
        g.fillGradient(0, this.height - 60, this.width, this.height, 0x00000000, 0x60000000);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        float fade = Math.min(1f, (System.currentTimeMillis() - openedAt) / 600f);
        int alpha = (int) (fade * 255) << 24;

        int logo = 56;
        int cx = this.width / 2;
        int top = this.height / 2 - 96;
        g.blit(LOGO, cx - 92, top, 0, 0, logo, logo, logo, logo);

        g.pose().pushPose();
        g.pose().scale(3f, 3f, 1f);
        g.drawString(this.font, "DAMOON", (cx - 28) / 3, (top + 8) / 3, 0xFFFFFF | alpha, true);
        g.pose().popPose();
        g.drawString(this.font, "C  L  I  E  N  T", cx - 26, top + 40, 0xF3C969 | alpha, false);

        g.drawString(this.font, "Minecraft " + SharedConstants.getCurrentVersion().getName() + "  ·  Damoon Core " + DamoonCore.VERSION,
                4, this.height - 12, 0x8B93A7 | alpha, false);
        String c = "Copyright Mojang AB. Do not distribute!";
        g.drawString(this.font, c, this.width - this.font.width(c) - 4, this.height - 12, 0x8B93A7 | alpha, false);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
