package net.damoon.core;

import net.damoon.core.gui.GuiDamoonMainMenu;
import net.damoon.core.gui.GuiDamoonMenu;
import net.damoon.core.gui.GuiHudEdit;
import net.damoon.core.hud.Hud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayDeque;

public class Events {
    private final Minecraft mc = Minecraft.getMinecraft();

    /* ---------- CPS ---------- */
    private static final ArrayDeque<Long> LEFT = new ArrayDeque<Long>();
    private static final ArrayDeque<Long> RIGHT = new ArrayDeque<Long>();

    private static int count(ArrayDeque<Long> q) {
        long limit = System.currentTimeMillis() - 1000;
        while (!q.isEmpty() && q.peekFirst() < limit) q.pollFirst();
        return q.size();
    }
    public static int cpsLeft() { return count(LEFT); }
    public static int cpsRight() { return count(RIGHT); }

    /* ---------- 줌 / 풀브라이트 상태 ---------- */
    private boolean zooming;
    private boolean prevSmooth;
    private float zoomCurrent = 1f;
    private boolean fullbrightApplied;
    private float prevGamma = 0.5f;

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;

        if (DamoonCore.menuKey.isPressed() && mc.currentScreen == null) {
            mc.displayGuiScreen(new GuiDamoonMenu(null));
        }

        // 줌
        boolean want = Config.on("zoom") && mc.currentScreen == null && mc.thePlayer != null
                && DamoonCore.zoomKey.getKeyCode() != 0 && DamoonCore.zoomKey.isKeyDown();
        if (want && !zooming) {
            prevSmooth = mc.gameSettings.smoothCamera;
            mc.gameSettings.smoothCamera = true;
        } else if (!want && zooming) {
            mc.gameSettings.smoothCamera = prevSmooth;
            Config.save();
        }
        zooming = want;

        // 풀브라이트
        if (Config.on("fullbright") && !fullbrightApplied) {
            prevGamma = mc.gameSettings.gammaSetting;
            mc.gameSettings.gammaSetting = 100f;
            fullbrightApplied = true;
        } else if (!Config.on("fullbright") && fullbrightApplied) {
            mc.gameSettings.gammaSetting = prevGamma;
            fullbrightApplied = false;
        }

        // 항상 달리기
        if (Config.on("togglesprint") && mc.thePlayer != null && mc.currentScreen == null) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), true);
        }
    }

    @SubscribeEvent
    public void onFov(EntityViewRenderEvent.FOVModifier e) {
        float target = zooming ? Config.zoomLevel() : 1f;
        zoomCurrent += (target - zoomCurrent) * 0.35f;
        if (Math.abs(zoomCurrent - target) < 0.001f) zoomCurrent = target;
        e.setFOV(e.getFOV() / zoomCurrent);
    }

    @SubscribeEvent
    public void onMouse(MouseEvent e) {
        if (e.buttonstate) {
            long now = System.currentTimeMillis();
            if (e.button == 0) LEFT.addLast(now);
            else if (e.button == 1) RIGHT.addLast(now);
        }
        if (zooming && e.dwheel != 0) {
            float z = Config.zoomLevel() * (e.dwheel > 0 ? 1.2f : 1 / 1.2f);
            Config.setZoomLevel(Math.max(1.5f, Math.min(50f, z)));
            e.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post e) {
        if (e.type != RenderGameOverlayEvent.ElementType.ALL) return;
        if (mc.gameSettings.showDebugInfo || mc.currentScreen instanceof GuiHudEdit) return;
        Hud.render(mc);
    }

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent e) {
        if (Config.on("custommenu") && e.gui instanceof GuiMainMenu) {
            e.gui = new GuiDamoonMainMenu();
        }
    }
}
