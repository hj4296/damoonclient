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
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 1.7 애니메이션 (1인칭) */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {

    @Shadow
    protected abstract void swingArm(float swingProgress, PoseStack poseStack, int side, HumanoidArm arm);

    /** 지금 그리는 손이 '1.7 칼 막기' 상태인지 (렌더 스레드 전용) */
    @Unique
    private boolean damoon$blocking;

    /** 1.7 휘두르기: 공격 후 아이템이 내려갔다 올라오는 쿨다운 모션 제거 */
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;getItemSwapScale(F)F"))
    private float damoon$oldSwing(LocalPlayer player, float partialTick) {
        return Config.on("oldswing") ? 1.0F : player.getItemSwapScale(partialTick);
    }

    @Inject(method = "renderArmWithItem", at = @At("HEAD"))
    private void damoon$checkBlocking(AbstractClientPlayer player, float partialTick, float pitch, InteractionHand hand, float swing,
                                      ItemStack stack, float equip, PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        damoon$blocking = Config.on("oldblock") && hand == InteractionHand.MAIN_HAND && mc.screen == null
                && stack.is(ItemTags.SWORDS) && mc.options.keyUse.isDown() && !player.isUsingItem();
    }

    /**
     * 칼 휘두르기(WHACK) 모션. 막는 중이면 1.7처럼 팔을 옮기지 않고 회전만 해서
     * 막기 자세 그대로 칼이 '톡' 내려갔다 올라오게 함 (블록히트).
     * ordinal 2 = renderArmWithItem 안의 세 번째 swingArm 호출 (일반 아이템 휘두르기)
     */
    @Redirect(method = "renderArmWithItem", at = @At(value = "INVOKE", ordinal = 2,
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;swingArm(FLcom/mojang/blaze3d/vertex/PoseStack;ILnet/minecraft/world/entity/HumanoidArm;)V"))
    private void damoon$blockHitSwing(ItemInHandRenderer self, float swing, PoseStack pose, int side, HumanoidArm arm) {
        if (!damoon$blocking) {
            this.swingArm(swing, pose, side, arm);
            return;
        }
        float a = Mth.sin(swing * swing * (float) Math.PI);
        float b = Mth.sin(Mth.sqrt(swing) * (float) Math.PI);
        pose.mulPose(Axis.YP.rotationDegrees(side * (45.0F + a * -20.0F)));
        pose.mulPose(Axis.ZP.rotationDegrees(side * b * -20.0F));
        pose.mulPose(Axis.XP.rotationDegrees(b * -40.0F));
        pose.mulPose(Axis.YP.rotationDegrees(side * -45.0F));
    }

    /** 1.7 칼 막기 자세 (1.7/1.8 의 doBlockTransformations 와 같은 각도) */
    @Inject(method = "renderArmWithItem", at = @At(value = "INVOKE", ordinal = 1,
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"))
    private void damoon$swordBlock(AbstractClientPlayer player, float partialTick, float pitch, InteractionHand hand, float swing,
                                   ItemStack stack, float equip, PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        if (!damoon$blocking) return;
        int q = player.getMainArm() == HumanoidArm.RIGHT ? 1 : -1;
        pose.translate(q * -0.14142136F, 0.08F, 0.14142136F);
        pose.mulPose(Axis.XP.rotationDegrees(-102.25F));
        pose.mulPose(Axis.YP.rotationDegrees(q * 13.365F));
        pose.mulPose(Axis.ZP.rotationDegrees(q * 78.05F));
    }
}
