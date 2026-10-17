package net.damoon.core;

import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Properties;

/** config/damoon.properties 에 저장되는 설정 */
public final class Config {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Properties P = new Properties();

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
            {"custommenu", "Damoon 메인 메뉴", "true"},
    };

    private Config() {}

    private static File file() {
        return new File(Minecraft.getMinecraft().mcDataDir, "config/damoon.properties");
    }

    public static boolean on(String id) {
        String v = P.getProperty("on." + id);
        if (v != null) return Boolean.parseBoolean(v);
        for (String[] m : MODULES) if (m[0].equals(id)) return Boolean.parseBoolean(m[2]);
        return false;
    }

    public static void toggle(String id) {
        P.setProperty("on." + id, String.valueOf(!on(id)));
        save();
    }

    public static int[] pos(String id) {
        String v = P.getProperty("pos." + id);
        if (v == null) return null;
        try {
            String[] s = v.split(",");
            return new int[]{Integer.parseInt(s[0].trim()), Integer.parseInt(s[1].trim())};
        } catch (Exception e) {
            return null;
        }
    }

    public static void setPos(String id, int x, int y) {
        P.setProperty("pos." + id, x + "," + y);
    }

    public static void resetPositions() {
        for (Object k : P.keySet().toArray()) if (k.toString().startsWith("pos.")) P.remove(k);
        save();
    }

    public static float zoomLevel() {
        try { return Float.parseFloat(P.getProperty("zoomLevel", "4")); } catch (Exception e) { return 4f; }
    }

    public static void setZoomLevel(float z) {
        P.setProperty("zoomLevel", String.valueOf(z));
    }

    public static void load() {
        File f = file();
        if (!f.exists()) return;
        Reader r = null;
        try {
            r = new InputStreamReader(new FileInputStream(f), UTF8);
            P.load(r);
        } catch (Exception e) {
            DamoonCore.LOG.warn("Damoon 설정을 읽지 못했습니다.", e);
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
        }
    }

    public static void save() {
        File f = file();
        Writer w = null;
        try {
            f.getParentFile().mkdirs();
            w = new OutputStreamWriter(new FileOutputStream(f), UTF8);
            P.store(w, "Damoon Client");
        } catch (Exception e) {
            DamoonCore.LOG.warn("Damoon 설정 저장 실패", e);
        } finally {
            if (w != null) try { w.close(); } catch (Exception ignored) {}
        }
    }
}
