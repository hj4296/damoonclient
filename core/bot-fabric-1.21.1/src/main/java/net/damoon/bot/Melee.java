package net.damoon.bot;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;

/**
 * 사람처럼 싸우는 근접전: 칼 쿨다운 동안엔 방패로 막고, 다 차면 방패를 내리고
 * 점프해서 떨어지는 순간 치명타(1.5배)로 벰. 닿지 않으면 다가감.
 */
public final class Melee {
    private Melee() {}

    /** @return 이번 틱 상태 설명 */
    public static String fight(Bot bot, LivingEntity t, boolean approach) {
        LocalPlayer p = bot.p();
        Inv.selectWeapon(p);
        bot.ctl.lookAt(p, t.getBoundingBox().getCenter());
        double r = Act.reach(p, t.getBoundingBox());
        float charge = p.getAttackStrengthScale(0.5f);
        boolean shield = p.getOffhandItem().is(Items.SHIELD);
        boolean blocking = p.isUsingItem() && p.getUseItem().is(Items.SHIELD);
        // 쿨다운 동안 방패
        if (shield && charge < 0.85f && r < 4.5) {
            bot.ctl.use = true;
            return "방패로 막으며 기다리는 중";
        }
        if (blocking) { bot.ctl.use = false; return "방패 내림"; }
        if (r > 2.7 && approach) {
            bot.ctl.fwd = true;
            bot.ctl.sprint = r > 5 && p.getFoodData().getFoodLevel() > 6;
            if (p.horizontalCollision && p.onGround()) bot.ctl.jump = true;
        }
        if (r > 3.0) return "다가가는 중";
        boolean headroom = p.level().getBlockState(p.blockPosition().above(2)).getCollisionShape(p.level(), p.blockPosition().above(2)).isEmpty();
        // 점프 치명타
        if (p.onGround() && charge >= 0.9f && headroom && !p.isInWater() && !p.onClimbable()) {
            bot.ctl.jump = true;
            return "점프!";
        }
        boolean falling = !p.onGround() && p.getDeltaMovement().y < -0.05;
        if (bot.ctl.aimed(p, 25) && charge >= 0.92f && (falling || !headroom || p.isInWater())) {
            if (Act.attack(bot, t, 0.92f)) return falling ? "치명타!" : "공격";
        }
        return "공격 준비";
    }
}
