package net.damoon.core;

import net.minecraft.client.Minecraft;

/** C키를 누르고 있으면 줌, 휠로 배율 조절, 부드러운 카메라 */
public final class Zoom {
    private static boolean active;
    private static boolean prevSmooth;
    private static double current = 1.0;

    private Zoom() {}

    public static boolean isActive() { return active; }

    static void tick(Minecraft mc) {
        boolean want = Config.on("zoom") && DamoonCore.zoomKey.isDown() && mc.screen == null && mc.player != null;
        if (want && !active) {
            prevSmooth = mc.options.smoothCamera;
            mc.options.smoothCamera = true;
        } else if (!want && active) {
            mc.options.smoothCamera = prevSmooth;
            Config.save(); // 휠로 바꾼 배율 저장
        }
        active = want;
    }

    /** GameRenderer#getFov 결과에 곱해짐 (프레임마다 부드럽게 보간) */
    public static float apply(float fov) {
        double target = active ? Config.INSTANCE.zoomLevel : 1.0;
        current += (target - current) * 0.35;
        if (Math.abs(current - target) < 0.001) current = target;
        return (float) (fov / current);
    }

    /** 줌 중 휠: 배율 조절. true면 핫바 스크롤을 막음 */
    public static boolean onScroll(double amount) {
        if (!active || amount == 0) return false;
        double z = Config.INSTANCE.zoomLevel * (amount > 0 ? 1.2 : 1 / 1.2);
        Config.INSTANCE.zoomLevel = Math.max(1.5, Math.min(50.0, z));
        return true;
    }
}
