package net.damoon.core;

import net.damoon.core.mixin.OptionInstanceAccessor;
import net.minecraft.client.Minecraft;

/** 밝기를 최대치 이상으로 올려 어두운 곳도 환하게 */
public final class Fullbright {
    private static boolean applied;
    private static double previous = 0.5;

    private Fullbright() {}

    static void tick(Minecraft mc) {
        boolean want = Config.on("fullbright");
        if (want && !applied) {
            previous = mc.options.gamma().get();
            set(mc, 15.0);
            applied = true;
        } else if (!want && applied) {
            set(mc, previous);
            applied = false;
        }
    }

    private static void set(Minecraft mc, double value) {
        ((OptionInstanceAccessor) (Object) mc.options.gamma()).damoon$setValue(value);
    }
}
