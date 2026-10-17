package net.damoon.core;

import com.mojang.blaze3d.platform.InputConstants;
import net.damoon.core.hud.Hud;
import net.damoon.core.screen.DamoonMenuScreen;
import net.damoon.core.screen.DamoonTitleScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DamoonCore implements ClientModInitializer {
    public static final String VERSION = "1.0.0";
    public static final Logger LOG = LoggerFactory.getLogger("Damoon");

    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("damoon", "main"));
    public static KeyMapping zoomKey;
    public static KeyMapping menuKey;

    @Override
    public void onInitializeClient() {
        Config.load();
        zoomKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.damoon.zoom", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, CATEGORY));
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.damoon.menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, CATEGORY));

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("damoon", "hud"), Hud::render);
        ClientTickEvents.END_CLIENT_TICK.register(DamoonCore::tick);
        LOG.info("Damoon Core {} 로드 완료", VERSION);
    }

    private static void tick(Minecraft mc) {
        while (menuKey.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new DamoonMenuScreen(null));
        }
        Zoom.tick(mc);
        Fullbright.tick(mc);
        if (Config.on("togglesprint") && mc.player != null && mc.screen == null) {
            mc.options.keySprint.setDown(true);
        }
        // 기본 타이틀 화면을 Damoon 메인 메뉴로 교체
        if (Config.on("custommenu") && mc.screen instanceof TitleScreen) {
            mc.setScreen(new DamoonTitleScreen());
        }
    }
}
