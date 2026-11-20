package net.damoon.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 게임 밖에서 봇을 조종하는 통로: bot/cmd.txt 를 읽어 실행하고 bot/status.txt 에 상태를 씀 */
public final class Control {
    private Control() {}

    private static int pollTick;

    public static void poll(Bot bot) {
        if (++pollTick % 10 != 0) return;
        try {
            Path f = Bot.dir().resolve("cmd.txt");
            if (!Files.exists(f)) return;
            String s = Files.readString(f, StandardCharsets.UTF_8);
            Files.delete(f);
            for (String line : s.split("\n")) {
                if (!line.isBlank()) bot.command(line);
            }
        } catch (Exception e) {
            Bot.LOG.error("명령 파일 오류", e);
        }
    }

    public static void writeStatus(Bot bot) {
        try {
            StringBuilder sb = new StringBuilder();
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            sb.append("screen=").append(mc.screen == null ? "-" : mc.screen.getClass().getSimpleName()).append('\n');
            sb.append("ticks=").append(bot.ticks).append(" trace=").append(String.join(" | ", bot.traceHist)).append(" exec=").append(bot.nav.exec() == null ? "-" : bot.nav.exec().target().toString()).append('\n');
            sb.append("running=").append(bot.running).append(" victory=").append(bot.victory).append(" deaths=").append(bot.deaths).append('\n');
            if (p != null) {
                sb.append(String.format("pos=%.1f %.1f %.1f dim=%s hp=%.1f food=%d%n", p.getX(), p.getY(), p.getZ(),
                        p.level().dimension().location().getPath(), p.getHealth(), p.getFoodData().getFoodLevel()));
            }
            for (String h : bot.hud()) sb.append(h.replaceAll("§.", "")).append('\n');
            Files.writeString(Bot.dir().resolve("status.txt"), sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
