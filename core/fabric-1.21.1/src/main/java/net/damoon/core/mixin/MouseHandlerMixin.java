package net.damoon.core.mixin;

import net.damoon.core.Cps;
import net.damoon.core.Zoom;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void damoon$zoomScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        if (Zoom.onScroll(yOffset)) ci.cancel();
    }

    @Inject(method = "onPress", at = @At("HEAD"))
    private void damoon$countClick(long window, int button, int action, int modifiers, CallbackInfo ci) {
        if (action == 1) Cps.click(button); // 1 = GLFW_PRESS
    }
}
