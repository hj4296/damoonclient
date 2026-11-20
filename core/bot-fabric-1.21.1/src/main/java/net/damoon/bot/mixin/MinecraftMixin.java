package net.damoon.bot.mixin;

import net.damoon.bot.Act;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    /** 봇이 블록을 캐는 동안에는 바닐라가 매 틱 캐기를 취소하지 않게 함 */
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void damoonbot$keepDigging(boolean leftClick, CallbackInfo ci) {
        if (Act.isDigging()) ci.cancel();
    }
}
