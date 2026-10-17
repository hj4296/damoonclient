package net.damoon.core.hud;

import net.damoon.core.Config;
import net.damoon.core.Cps;
import net.damoon.core.screen.HudEditScreen;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Damoon HUD: FPS, CPS, 핑, 좌표, 키스트로크, 갑옷, 포션 */
public final class Hud {
    public static final int BG = 0x90000000;
    public static final int ACCENT = 0xFFF3C969;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int DIM = 0xFFB8BECC;

    public interface Element {
        String id();
        int[] size(Minecraft mc);
        int[] defaultPos(int screenW, int screenH);
        void draw(GuiGraphics g, Minecraft mc, int x, int y);
    }

    public static final List<Element> ELEMENTS = List.of(
            new TextElement("fps", 4, 4) { String text(Minecraft mc) { return mc.getFps() + " FPS"; } },
            new TextElement("cps", 4, 22) { String text(Minecraft mc) { return Cps.left() + " | " + Cps.right() + " CPS"; } },
            new TextElement("ping", 4, 40) { String text(Minecraft mc) { return ping(mc) + " ms"; } },
            new Coords(),
            new Keystrokes(),
            new Armor(),
            new Potions()
    );

    private Hud() {}

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.screen instanceof HudEditScreen) return;
        for (Element e : ELEMENTS) {
            if (!Config.on(e.id())) continue;
            int[] p = position(e, mc);
            e.draw(g, mc, p[0], p[1]);
        }
    }

    /** 저장된 위치 (없으면 기본 위치), 화면 밖으로 나가지 않게 보정 */
    public static int[] position(Element e, Minecraft mc) {
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        int[] p = Config.INSTANCE.positions.get(e.id());
        if (p == null || p.length < 2) p = e.defaultPos(sw, sh);
        int[] s = e.size(mc);
        return new int[]{Math.max(0, Math.min(p[0], sw - s[0])), Math.max(0, Math.min(p[1], sh - s[1]))};
    }

    static int ping(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null) return 0;
        PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info == null ? 0 : info.getLatency();
    }

    /* ---------- 공통: 배경 박스 + 글자 ---------- */
    abstract static class TextElement implements Element {
        private final String id;
        private final int dx, dy;
        TextElement(String id, int dx, int dy) { this.id = id; this.dx = dx; this.dy = dy; }
        abstract String text(Minecraft mc);
        public String id() { return id; }
        public int[] defaultPos(int w, int h) { return new int[]{dx, dy}; }
        public int[] size(Minecraft mc) { return new int[]{mc.font.width(text(mc)) + 10, 16}; }
        public void draw(GuiGraphics g, Minecraft mc, int x, int y) {
            String t = text(mc);
            int w = mc.font.width(t) + 10;
            g.fill(x, y, x + w, y + 16, BG);
            g.fill(x, y, x + 2, y + 16, ACCENT);
            g.drawString(mc.font, t, x + 6, y + 4, WHITE, true);
        }
    }

    static final class Coords implements Element {
        public String id() { return "coords"; }
        public int[] defaultPos(int w, int h) { return new int[]{4, 58}; }
        public int[] size(Minecraft mc) { return new int[]{Math.max(mc.font.width(line1(mc)), mc.font.width(line2(mc))) + 10, 26}; }
        private String line1(Minecraft mc) {
            Player p = mc.player;
            if (p == null) return "XYZ 0 / 0 / 0";
            return "XYZ " + p.getBlockX() + " / " + p.getBlockY() + " / " + p.getBlockZ();
        }
        private String line2(Minecraft mc) {
            if (mc.player == null) return "방향 -";
            return switch (mc.player.getDirection()) {
                case NORTH -> "방향 북 (-Z)";
                case SOUTH -> "방향 남 (+Z)";
                case EAST -> "방향 동 (+X)";
                case WEST -> "방향 서 (-X)";
                default -> "방향 -";
            };
        }
        public void draw(GuiGraphics g, Minecraft mc, int x, int y) {
            int[] s = size(mc);
            g.fill(x, y, x + s[0], y + s[1], BG);
            g.fill(x, y, x + 2, y + s[1], ACCENT);
            g.drawString(mc.font, line1(mc), x + 6, y + 4, WHITE, true);
            g.drawString(mc.font, line2(mc), x + 6, y + 14, DIM, true);
        }
    }

    static final class Keystrokes implements Element {
        private static final int K = 22, GAP = 2;
        public String id() { return "keystrokes"; }
        public int[] defaultPos(int w, int h) { return new int[]{4, 90}; }
        public int[] size(Minecraft mc) { return new int[]{K * 3 + GAP * 2, K * 3 + GAP * 3 + 10}; }
        private void key(GuiGraphics g, Font f, int x, int y, int w, int h, String label, boolean down) {
            g.fill(x, y, x + w, y + h, down ? 0xE0F3C969 : BG);
            g.drawCenteredString(f, label, x + w / 2, y + (h - 8) / 2, down ? 0xFF1B1407 : WHITE);
        }
        public void draw(GuiGraphics g, Minecraft mc, int x, int y) {
            var o = mc.options;
            Font f = mc.font;
            key(g, f, x + K + GAP, y, K, K, label(o.keyUp, "W"), o.keyUp.isDown());
            int r2 = y + K + GAP;
            key(g, f, x, r2, K, K, label(o.keyLeft, "A"), o.keyLeft.isDown());
            key(g, f, x + K + GAP, r2, K, K, label(o.keyDown, "S"), o.keyDown.isDown());
            key(g, f, x + (K + GAP) * 2, r2, K, K, label(o.keyRight, "D"), o.keyRight.isDown());
            int r3 = r2 + K + GAP, half = (K * 3 + GAP * 2 - GAP) / 2;
            key(g, f, x, r3, half, K, "LMB " + Cps.left(), o.keyAttack.isDown());
            key(g, f, x + half + GAP, r3, half, K, "RMB " + Cps.right(), o.keyUse.isDown());
            int r4 = r3 + K + GAP;
            g.fill(x, r4, x + K * 3 + GAP * 2, r4 + 10, o.keyJump.isDown() ? 0xE0F3C969 : BG);
            g.fill(x + 20, r4 + 4, x + K * 3 + GAP * 2 - 20, r4 + 5, o.keyJump.isDown() ? 0xFF1B1407 : WHITE);
        }
        private static String label(KeyMapping k, String fallback) {
            String s = k.getTranslatedKeyMessage().getString();
            return s.length() <= 3 ? s.toUpperCase() : fallback;
        }
    }

    static final class Armor implements Element {
        public String id() { return "armor"; }
        public int[] defaultPos(int w, int h) { return new int[]{w - 62, h - 90}; }
        public int[] size(Minecraft mc) { return new int[]{58, 4 * 18 + 4}; }
        public void draw(GuiGraphics g, Minecraft mc, int x, int y) {
            if (mc.player == null) return;
            EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
            int row = 0;
            for (EquipmentSlot slot : slots) {
                ItemStack s = mc.player.getItemBySlot(slot);
                int yy = y + 2 + row * 18;
                row++;
                if (s.isEmpty()) continue;
                g.renderItem(s, x + 2, yy);
                if (s.isDamageableItem()) {
                    int left = s.getMaxDamage() - s.getDamageValue();
                    float ratio = (float) left / s.getMaxDamage();
                    int color = ratio > 0.5f ? 0xFF7CF29A : ratio > 0.2f ? 0xFFF3C969 : 0xFFFF6B6B;
                    g.drawString(mc.font, String.valueOf(left), x + 22, yy + 4, color, true);
                }
            }
        }
    }

    static final class Potions implements Element {
        public String id() { return "potions"; }
        public int[] defaultPos(int w, int h) { return new int[]{w - 134, 4}; }
        private List<String[]> lines(Minecraft mc) {
            List<String[]> out = new ArrayList<>();
            if (mc.player == null) return out;
            for (MobEffectInstance e : mc.player.getActiveEffects()) {
                String name = e.getEffect().value().getDisplayName().getString();
                if (e.getAmplifier() > 0) name += " " + roman(e.getAmplifier() + 1);
                String time;
                if (e.isInfiniteDuration()) time = "∞";
                else {
                    int sec = e.getDuration() / 20;
                    time = String.format("%d:%02d", sec / 60, sec % 60);
                }
                out.add(new String[]{name, time});
            }
            return out;
        }
        public int[] size(Minecraft mc) { return new int[]{130, Math.max(16, lines(mc).size() * 12 + 6)}; }
        public void draw(GuiGraphics g, Minecraft mc, int x, int y) {
            List<String[]> ls = lines(mc);
            if (ls.isEmpty()) return;
            g.fill(x, y, x + 130, y + ls.size() * 12 + 6, BG);
            g.fill(x + 128, y, x + 130, y + ls.size() * 12 + 6, ACCENT);
            int yy = y + 4;
            for (String[] l : ls) {
                g.drawString(mc.font, l[0], x + 4, yy, WHITE, true);
                g.drawString(mc.font, l[1], x + 124 - mc.font.width(l[1]), yy, DIM, true);
                yy += 12;
            }
        }
        private static String roman(int n) {
            String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
            return n < r.length ? r[n] : String.valueOf(n);
        }
    }
}
