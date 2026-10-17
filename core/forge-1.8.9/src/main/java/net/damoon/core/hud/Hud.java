package net.damoon.core.hud;

import net.damoon.core.Config;
import net.damoon.core.Events;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.MathHelper;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Damoon HUD: FPS, CPS, 핑, 좌표, 키스트로크, 갑옷, 포션 */
public final class Hud {
    public static final int BG = 0x90000000;
    public static final int ACCENT = 0xFFF3C969;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int DIM = 0xFFB8BECC;

    public abstract static class Element {
        public final String id;
        Element(String id) { this.id = id; }
        public abstract int[] size(Minecraft mc);
        public abstract int[] defaultPos(int w, int h);
        public abstract void draw(Minecraft mc, int x, int y);
    }

    public static final List<Element> ELEMENTS = Arrays.asList(
            new TextElement("fps", 4, 4) { String text(Minecraft mc) { return Minecraft.getDebugFPS() + " FPS"; } },
            new TextElement("cps", 4, 22) { String text(Minecraft mc) { return Events.cpsLeft() + " | " + Events.cpsRight() + " CPS"; } },
            new TextElement("ping", 4, 40) { String text(Minecraft mc) { return ping(mc) + " ms"; } },
            new Coords(), new Keystrokes(), new Armor(), new Potions()
    );

    private Hud() {}

    public static void render(Minecraft mc) {
        if (mc.gameSettings.hideGUI || mc.thePlayer == null) return;
        for (Element e : ELEMENTS) {
            if (!Config.on(e.id)) continue;
            int[] p = position(e, mc);
            e.draw(mc, p[0], p[1]);
        }
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    public static int[] position(Element e, Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth(), sh = sr.getScaledHeight();
        int[] p = Config.pos(e.id);
        if (p == null) p = e.defaultPos(sw, sh);
        int[] s = e.size(mc);
        return new int[]{Math.max(0, Math.min(p[0], sw - s[0])), Math.max(0, Math.min(p[1], sh - s[1]))};
    }

    static int ping(Minecraft mc) {
        if (mc.getNetHandler() == null || mc.thePlayer == null) return 0;
        NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
        return info == null ? 0 : info.getResponseTime();
    }

    static void text(Minecraft mc, String s, int x, int y, int color) {
        mc.fontRendererObj.drawStringWithShadow(s, x, y, color);
    }

    /* ---------- 글자 한 줄 ---------- */
    abstract static class TextElement extends Element {
        private final int dx, dy;
        TextElement(String id, int dx, int dy) { super(id); this.dx = dx; this.dy = dy; }
        abstract String text(Minecraft mc);
        public int[] defaultPos(int w, int h) { return new int[]{dx, dy}; }
        public int[] size(Minecraft mc) { return new int[]{mc.fontRendererObj.getStringWidth(text(mc)) + 10, 16}; }
        public void draw(Minecraft mc, int x, int y) {
            String t = text(mc);
            int w = mc.fontRendererObj.getStringWidth(t) + 10;
            Gui.drawRect(x, y, x + w, y + 16, BG);
            Gui.drawRect(x, y, x + 2, y + 16, ACCENT);
            text(mc, t, x + 6, y + 4, WHITE);
        }
    }

    static final class Coords extends Element {
        Coords() { super("coords"); }
        public int[] defaultPos(int w, int h) { return new int[]{4, 58}; }
        private String l1(Minecraft mc) {
            if (mc.thePlayer == null) return "XYZ 0 / 0 / 0";
            return "XYZ " + MathHelper.floor_double(mc.thePlayer.posX) + " / " + MathHelper.floor_double(mc.thePlayer.posY)
                    + " / " + MathHelper.floor_double(mc.thePlayer.posZ);
        }
        private String l2(Minecraft mc) {
            if (mc.thePlayer == null) return "방향 -";
            switch (mc.thePlayer.getHorizontalFacing()) {
                case NORTH: return "방향 북 (-Z)";
                case SOUTH: return "방향 남 (+Z)";
                case EAST: return "방향 동 (+X)";
                case WEST: return "방향 서 (-X)";
                default: return "방향 -";
            }
        }
        public int[] size(Minecraft mc) {
            FontRenderer f = mc.fontRendererObj;
            return new int[]{Math.max(f.getStringWidth(l1(mc)), f.getStringWidth(l2(mc))) + 10, 26};
        }
        public void draw(Minecraft mc, int x, int y) {
            int[] s = size(mc);
            Gui.drawRect(x, y, x + s[0], y + s[1], BG);
            Gui.drawRect(x, y, x + 2, y + s[1], ACCENT);
            text(mc, l1(mc), x + 6, y + 4, WHITE);
            text(mc, l2(mc), x + 6, y + 14, DIM);
        }
    }

    static final class Keystrokes extends Element {
        private static final int K = 22, GAP = 2;
        Keystrokes() { super("keystrokes"); }
        public int[] defaultPos(int w, int h) { return new int[]{4, 90}; }
        public int[] size(Minecraft mc) { return new int[]{K * 3 + GAP * 2, K * 3 + GAP * 3 + 10}; }
        private void key(Minecraft mc, int x, int y, int w, int h, String label, boolean down) {
            Gui.drawRect(x, y, x + w, y + h, down ? 0xE0F3C969 : BG);
            FontRenderer f = mc.fontRendererObj;
            int tx = x + (w - f.getStringWidth(label)) / 2;
            if (down) f.drawString(label, tx, y + (h - 8) / 2, 0xFF1B1407);
            else f.drawStringWithShadow(label, tx, y + (h - 8) / 2, WHITE);
        }
        private static String name(KeyBinding k, String fallback) {
            int code = k.getKeyCode();
            if (code < 0) return fallback;
            String s = Keyboard.getKeyName(code);
            return s != null && s.length() <= 3 ? s : fallback;
        }
        public void draw(Minecraft mc, int x, int y) {
            GameSettings o = mc.gameSettings;
            key(mc, x + K + GAP, y, K, K, name(o.keyBindForward, "W"), o.keyBindForward.isKeyDown());
            int r2 = y + K + GAP;
            key(mc, x, r2, K, K, name(o.keyBindLeft, "A"), o.keyBindLeft.isKeyDown());
            key(mc, x + K + GAP, r2, K, K, name(o.keyBindBack, "S"), o.keyBindBack.isKeyDown());
            key(mc, x + (K + GAP) * 2, r2, K, K, name(o.keyBindRight, "D"), o.keyBindRight.isKeyDown());
            int r3 = r2 + K + GAP, half = (K * 3 + GAP * 2 - GAP) / 2;
            key(mc, x, r3, half, K, "LMB " + Events.cpsLeft(), o.keyBindAttack.isKeyDown());
            key(mc, x + half + GAP, r3, half, K, "RMB " + Events.cpsRight(), o.keyBindUseItem.isKeyDown());
            int r4 = r3 + K + GAP;
            boolean jump = o.keyBindJump.isKeyDown();
            Gui.drawRect(x, r4, x + K * 3 + GAP * 2, r4 + 10, jump ? 0xE0F3C969 : BG);
            Gui.drawRect(x + 20, r4 + 4, x + K * 3 + GAP * 2 - 20, r4 + 5, jump ? 0xFF1B1407 : WHITE);
        }
    }

    static final class Armor extends Element {
        Armor() { super("armor"); }
        public int[] defaultPos(int w, int h) { return new int[]{w - 62, h - 90}; }
        public int[] size(Minecraft mc) { return new int[]{58, 4 * 18 + 4}; }
        public void draw(Minecraft mc, int x, int y) {
            if (mc.thePlayer == null) return;
            int row = 0;
            for (int i = 3; i >= 0; i--) {
                ItemStack s = mc.thePlayer.inventory.armorItemInSlot(i);
                int yy = y + 2 + row * 18;
                row++;
                if (s == null) continue;
                RenderHelper.enableGUIStandardItemLighting();
                mc.getRenderItem().renderItemAndEffectIntoGUI(s, x + 2, yy);
                RenderHelper.disableStandardItemLighting();
                if (s.isItemStackDamageable()) {
                    int left = s.getMaxDamage() - s.getItemDamage();
                    float ratio = (float) left / s.getMaxDamage();
                    int color = ratio > 0.5f ? 0xFF7CF29A : ratio > 0.2f ? 0xFFF3C969 : 0xFFFF6B6B;
                    text(mc, String.valueOf(left), x + 22, yy + 4, color);
                }
            }
        }
    }

    static final class Potions extends Element {
        Potions() { super("potions"); }
        public int[] defaultPos(int w, int h) { return new int[]{w - 134, 4}; }
        private List<String[]> lines(Minecraft mc) {
            List<String[]> out = new ArrayList<String[]>();
            if (mc.thePlayer == null) return out;
            for (PotionEffect e : mc.thePlayer.getActivePotionEffects()) {
                Potion p = Potion.potionTypes[e.getPotionID()];
                if (p == null) continue;
                String name = I18n.format(p.getName());
                if (e.getAmplifier() > 0) name += " " + I18n.format("enchantment.level." + (e.getAmplifier() + 1));
                out.add(new String[]{name, Potion.getDurationString(e)});
            }
            return out;
        }
        public int[] size(Minecraft mc) { return new int[]{130, Math.max(16, lines(mc).size() * 12 + 6)}; }
        public void draw(Minecraft mc, int x, int y) {
            List<String[]> ls = lines(mc);
            if (ls.isEmpty()) return;
            Gui.drawRect(x, y, x + 130, y + ls.size() * 12 + 6, BG);
            Gui.drawRect(x + 128, y, x + 130, y + ls.size() * 12 + 6, ACCENT);
            int yy = y + 4;
            for (String[] l : ls) {
                text(mc, l[0], x + 4, yy, WHITE);
                text(mc, l[1], x + 124 - mc.fontRendererObj.getStringWidth(l[1]), yy, DIM);
                yy += 12;
            }
        }
    }
}
