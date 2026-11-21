package net.damoon.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 월드 폴더에 저장하는 봇 기억 (게임을 다시 켜도 남음): 네더에서 돌아갈 포털 자리 등.
 * 사람이 좌표를 메모해 두는 것과 같음.
 */
public final class Memory {
    private Memory() {}

    private static Path file() {
        MinecraftServer s = Minecraft.getInstance().getSingleplayerServer();
        return s == null ? null : s.getWorldPath(LevelResource.ROOT).resolve("damoonbot-memory.properties");
    }

    private static Properties load() {
        Properties pr = new Properties();
        Path f = file();
        if (f != null && Files.exists(f)) {
            try (Reader r = Files.newBufferedReader(f)) { pr.load(r); } catch (IOException ignored) {}
        }
        return pr;
    }

    public static String get(String key) {
        return load().getProperty(key);
    }

    public static void put(String key, String value) {
        Path f = file();
        if (f == null) return;
        Properties pr = load();
        if (value == null) pr.remove(key); else pr.setProperty(key, value);
        try (Writer w = Files.newBufferedWriter(f)) { pr.store(w, "Damoon Bot memory"); } catch (IOException ignored) {}
    }

    public static BlockPos getPos(String key) {
        String v = get(key);
        if (v == null) return null;
        try {
            String[] a = v.split(",");
            return new BlockPos(Integer.parseInt(a[0].trim()), Integer.parseInt(a[1].trim()), Integer.parseInt(a[2].trim()));
        } catch (RuntimeException e) { return null; }
    }

    public static void putPos(String key, BlockPos b) {
        put(key, b == null ? null : b.getX() + "," + b.getY() + "," + b.getZ());
    }
}
