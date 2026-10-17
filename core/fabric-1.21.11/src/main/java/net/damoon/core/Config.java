package net.damoon.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** config/damoon.json 에 저장되는 설정 */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 기능 켜짐/꺼짐 */
    public Map<String, Boolean> enabled = new LinkedHashMap<>();
    /** HUD 위치 (화면 좌표, GUI 배율 기준) */
    public Map<String, int[]> positions = new LinkedHashMap<>();
    public double zoomLevel = 4.0;

    public static Config INSTANCE = new Config();

    /** 메뉴에 표시되는 순서대로: id, 이름, 기본값 */
    public static final String[][] MODULES = {
            {"fps", "FPS 표시", "true"},
            {"cps", "CPS 표시", "true"},
            {"keystrokes", "키스트로크", "true"},
            {"coords", "좌표·방향", "true"},
            {"ping", "핑 표시", "true"},
            {"armor", "갑옷 상태", "true"},
            {"potions", "포션 효과", "true"},
            {"zoom", "줌 (C키)", "true"},
            {"fullbright", "풀브라이트", "false"},
            {"togglesprint", "항상 달리기", "false"},
            {"oldblock", "1.7 칼 막기 (블록히트)", "true"},
            {"oldswing", "1.7 휘두르기 (쿨다운 모션 없음)", "true"},
            {"custommenu", "Damoon 메인 메뉴", "true"},
    };

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("damoon.json");
    }

    public static boolean on(String id) {
        Boolean v = INSTANCE.enabled.get(id);
        if (v != null) return v;
        for (String[] m : MODULES) if (m[0].equals(id)) return Boolean.parseBoolean(m[2]);
        return false;
    }

    public static void toggle(String id) {
        INSTANCE.enabled.put(id, !on(id));
        save();
    }

    public static void load() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                Config c = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Config.class);
                if (c != null) {
                    if (c.enabled == null) c.enabled = new LinkedHashMap<>();
                    if (c.positions == null) c.positions = new LinkedHashMap<>();
                    INSTANCE = c;
                }
            }
        } catch (Exception e) {
            DamoonCore.LOG.warn("Damoon 설정을 읽지 못했습니다. 기본값을 사용합니다.", e);
        }
    }

    public static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(INSTANCE), StandardCharsets.UTF_8);
        } catch (Exception e) {
            DamoonCore.LOG.warn("Damoon 설정 저장 실패", e);
        }
    }
}
