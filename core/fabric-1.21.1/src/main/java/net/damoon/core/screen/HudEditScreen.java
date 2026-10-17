package net.damoon.core.screen;

import net.damoon.core.Config;
import net.damoon.core.hud.Hud;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** HUD 요소를 마우스로 끌어서 위치 변경 */
public class HudEditScreen extends Screen {
    private final Screen parent;
    private Hud.Element dragging;
    private int offX, offY;

    public HudEditScreen(Screen parent) {
        super(Component.literal("HUD 편집"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("기본 위치로"), b -> {
            Config.INSTANCE.positions.clear();
            Config.save();
        }).bounds(this.width / 2 - 102, this.height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("완료"), b -> onClose())
                .bounds(this.width / 2 + 2, this.height - 28, 100, 20).build());
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0x40000000);
        // 정렬용 가운데 선
        g.fill(this.width / 2, 0, this.width / 2 + 1, this.height, 0x30FFFFFF);
        g.fill(0, this.height / 2, this.width, this.height / 2 + 1, 0x30FFFFFF);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        for (Hud.Element e : Hud.ELEMENTS) {
            if (!Config.on(e.id())) continue;
            int[] p = Hud.position(e, this.minecraft);
            int[] s = e.size(this.minecraft);
            e.draw(g, this.minecraft, p[0], p[1]);
            boolean hover = e == dragging || (mouseX >= p[0] && mouseX < p[0] + s[0] && mouseY >= p[1] && mouseY < p[1] + s[1]);
            int c = hover ? 0xFFF3C969 : 0x80FFFFFF;
            g.renderOutline(p[0] - 1, p[1] - 1, s[0] + 2, s[1] + 2, c);
        }
        g.drawCenteredString(this.font, "HUD를 마우스로 끌어서 옮기세요", this.width / 2, 8, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        for (Hud.Element e : Hud.ELEMENTS) {
            if (!Config.on(e.id())) continue;
            int[] p = Hud.position(e, this.minecraft);
            int[] s = e.size(this.minecraft);
            if (mx >= p[0] && mx < p[0] + s[0] && my >= p[1] && my < p[1] + s[1]) {
                dragging = e;
                offX = (int) mx - p[0];
                offY = (int) my - p[1];
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging != null) {
            int x = (int) mx - offX, y = (int) my - offY;
            // 화면 가운데에 가까우면 붙임
            int[] s = dragging.size(this.minecraft);
            if (Math.abs(x + s[0] / 2 - this.width / 2) < 4) x = this.width / 2 - s[0] / 2;
            Config.INSTANCE.positions.put(dragging.id(), new int[]{x, y});
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = null;
        return super.mouseReleased(mx, my, button);
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
