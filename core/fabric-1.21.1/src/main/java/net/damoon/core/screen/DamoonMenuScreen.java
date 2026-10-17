package net.damoon.core.screen;

import net.damoon.core.Config;
import net.damoon.core.DamoonCore;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** 오른쪽 Shift로 여는 Damoon 메뉴: 기능 켜기/끄기 + HUD 위치 편집 */
public class DamoonMenuScreen extends Screen {
    private final Screen parent;

    public DamoonMenuScreen(Screen parent) {
        super(Component.literal("Damoon Client"));
        this.parent = parent;
    }

    private static Component label(String[] m) {
        boolean on = Config.on(m[0]);
        return Component.literal((on ? "§a● " : "§7○ ") + "§f" + m[1] + (on ? "  §a켜짐" : "  §7꺼짐"));
    }

    @Override
    protected void init() {
        int cols = 2, bw = 150, gap = 6;
        int totalW = cols * bw + gap;
        int x0 = this.width / 2 - totalW / 2;
        int y0 = Math.max(48, this.height / 2 - 90);
        String[][] mods = Config.MODULES;
        for (int i = 0; i < mods.length; i++) {
            String[] m = mods[i];
            int x = x0 + (i % cols) * (bw + gap);
            int y = y0 + (i / cols) * 24;
            addRenderableWidget(Button.builder(label(m), b -> {
                Config.toggle(m[0]);
                b.setMessage(label(m));
            }).bounds(x, y, bw, 20).build());
        }
        int by = y0 + ((mods.length + 1) / cols) * 24 + 10;
        Button edit = Button.builder(Component.literal("✥ HUD 위치 편집"),
                b -> this.minecraft.setScreen(new HudEditScreen(this))).bounds(x0, by, bw, 20).build();
        edit.active = this.minecraft.player != null;
        if (!edit.active) edit.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("월드에 들어간 뒤 사용할 수 있어요")));
        addRenderableWidget(edit);
        addRenderableWidget(Button.builder(Component.literal("완료"), b -> onClose()).bounds(x0 + bw + gap, by, bw, 20).build());
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (this.minecraft.level == null) g.fillGradient(0, 0, this.width, this.height, 0xFF0B0D14, 0xFF1A2030);
        else g.fill(0, 0, this.width, this.height, 0xA0080A10);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int y = Math.max(48, this.height / 2 - 90) - 30;
        g.drawCenteredString(this.font, "§lDAMOON §r§6CLIENT", this.width / 2, y, 0xFFFFFFFF);
        g.drawCenteredString(this.font, "줌: C (휠로 배율)  ·  메뉴: 오른쪽 Shift  ·  v" + DamoonCore.VERSION, this.width / 2, y + 13, 0xFF8B93A7);
    }

    @Override
    public void onClose() {
        Config.save();
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
