package net.damoon.bot;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

import java.util.List;

public class DamoonBot implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(mc -> Bot.I.tick());

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
                ClientCommandManager.literal("bot")
                        .then(ClientCommandManager.argument("args", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    Bot.I.command(StringArgumentType.getString(ctx, "args"));
                                    return 1;
                                }))));

        HudRenderCallback.EVENT.register((g, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) return;
            List<String> lines = Bot.I.hud();
            Font f = mc.font;
            int w = 0;
            for (String s : lines) w = Math.max(w, f.width(s));
            int h = lines.size() * 10 + 4;
            g.fill(2, 2, 6 + w, 2 + h, 0x90000000);
            int y = 4;
            for (String s : lines) {
                g.drawString(f, s, 4, y, 0xFFFFFF, true);
                y += 10;
            }
        });
        Bot.LOG.info("Damoon Bot 준비 완료");
    }
}
