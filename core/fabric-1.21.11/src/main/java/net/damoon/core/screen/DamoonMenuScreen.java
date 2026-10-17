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

    /** 버튼 묶음이 화면 가운데 오도록 시작 높이 계산 (작은 창에서도 잘리지 않게) */
    private int top() {
        int cols = this.width >= 3 * 128 + 2 * 6 + 16 ? 3 : 2;
        int rows = (Config.MODULES.length + cols - 1) / cols;
        int total = rows * 24 + 8 + 20;
        return Math.max(36, (this.height - total) / 2 + 12);
    }

    private static Component label(String[] m) {
        boolean on = Config.on(m[0]);
        return Component.literal((on ? "§a● " : "§7○ ") + "§f" + m[1] + (on ? "  §a켜짐" : "  §7꺼짐"));
    }

    @Override
    protected void init() {
        String[][] mods = Config.MODULES;
        int gap = 6;
        int cols = this.width >= 3 * 128 + 2 * gap + 16 ? 3 : 2;
        int bw = cols == 3 ? 128 : 150;
        int totalW = cols * bw + (cols - 1) * gap;
        int x0 = this.width / 2 - totalW / 2;
        int y0 = top();
        for (int i = 0; i < mods.length; i++) {
            String[] m = mods[i];
            int x = x0 + (i % cols) * (bw + gap);
            int y = y0 + (i / cols) * 24;
            addRenderableWidget(Button.builder(label(m), b -> {
                Config.toggle(m[0]);
                b.setMessage(label(m));
            }).bounds(x, y, bw, 20).build());
        }
        int by = y0 + ((mods.length + cols - 1) / cols) * 24 + 8;
        int half = (totalW - gap) / 2;
        Button edit = Button.builder(Component.literal("✥ HUD 위치 편집"),
                b -> this.minecraft.setScreen(new HudEditScreen(this))).bounds(x0, by, half, 20).build();
        edit.active = this.minecraft.player != null;
        if (!edit.active) edit.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("월드에 들어간 뒤 사용할 수 있어요")));
        addRenderableWidget(edit);
        addRenderableWidget(Button.builder(Component.literal("완료"), b -> onClose()).bounds(x0 + half + gap, by, half, 20).build());
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (this.minecraft.level == null) g.fillGradient(0, 0, this.width, this.height, 0xFF0B0D14, 0xFF1A2030);
        else g.fill(0, 0, this.width, this.height, 0xA0080A10);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int y = top() - 30;
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
