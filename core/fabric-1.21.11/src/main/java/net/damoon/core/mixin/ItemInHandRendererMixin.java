package net.damoon.core.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.damoon.core.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 1.7 애니메이션 (1인칭) */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {

    /** 1.7 휘두르기: 공격 후 아이템이 내려갔다 올라오는 쿨다운 모션 제거 */
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;getItemSwapScale(F)F"))
    private float damoon$oldSwing(LocalPlayer player, float partialTick) {
        return Config.on("oldswing") ? 1.0F : player.getItemSwapScale(partialTick);
    }

    /** 1.7 칼 막기: 칼을 들고 우클릭을 누르고 있으면 1.7 막기 자세. 그 상태로 공격하면 블록히트 모션 */
    @Inject(method = "renderArmWithItem", at = @At(value = "INVOKE", ordinal = 1,
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"))
    private void damoon$swordBlock(AbstractClientPlayer player, float partialTick, float pitch, InteractionHand hand, float swing,
                                   ItemStack stack, float equip, PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (!Config.on("oldblock") || hand != InteractionHand.MAIN_HAND || mc.screen != null) return;
        if (!stack.is(ItemTags.SWORDS) || !mc.options.keyUse.isDown() || player.isUsingItem()) return;
        HumanoidArm arm = player.getMainArm();
        int q = arm == HumanoidArm.RIGHT ? 1 : -1;
        // 1.7/1.8 의 doBlockTransformations 와 같은 자세
        pose.translate(q * -0.14142136F, 0.08F, 0.14142136F);
        pose.mulPose(Axis.XP.rotationDegrees(-102.25F));
        pose.mulPose(Axis.YP.rotationDegrees(q * 13.365F));
        pose.mulPose(Axis.ZP.rotationDegrees(q * 78.05F));
    }
}
