package net.damoon.bot;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;

/**
 * 사람처럼 싸우는 근접전 (PvP 기술):
 * - 거리 유지: 칼 끝 거리(2.6~3칸)에서 때리고, 너무 붙으면 방패 들고 뒤로 물러남
 * - 쿨다운 동안엔 방패로 막음
 * - 상대가 하나면 점프 치명타(1.5배), 여럿이면 달리며 때려 넉백으로 거리를 벌림
 * - 기다리는 동안 좌우로 움직여 투사체를 피함
 */
public final class Melee {
    private Melee() {}

    private static int strafeTick;

    public static String fight(Bot bot, LivingEntity t, boolean approach) {
        LocalPlayer p = bot.p();
        Inv.selectWeapon(p);
        bot.ctl.lookAt(p, t.getBoundingBox().getCenter());
        double r = Act.reach(p, t.getBoundingBox());
        float charge = p.getAttackStrengthScale(0.5f);
        boolean shield = p.getOffhandItem().is(Items.SHIELD);
        boolean blocking = p.isUsingItem() && p.getUseItem().is(Items.SHIELD);
        int crowd = 0;
        for (Entity e : bot.level().entitiesForRendering()) if (e != t && Reflex.hostile(e) && e.distanceTo(p) < 6) crowd++;

        // 쿨다운 중: 방패 들고 거리 조절 (너무 붙으면 뒤로, 멀면 그대로 기다림)
        if (charge < 0.85f) {
            if (shield && r < 4.5) bot.ctl.use = true;
            if (r < 2.2 && Reflex.safeBehind(p)) bot.ctl.back = true;
            // 좌우로 조금씩 움직여 화살·화염구를 피함
            if (r > 2.5 && ++strafeTick % 40 < 20) bot.ctl.left = true; else if (r > 2.5) bot.ctl.right = true;
            return blocking ? "방패로 막으며 거리 유지" : "쿨다운 기다리는 중";
        }
        if (blocking) { bot.ctl.use = false; return "방패 내림"; }
        // 다가가기 (여럿이면 달려서 넉백 공격 준비)
        if (r > 2.9 && approach) {
            bot.ctl.fwd = true;
            bot.ctl.sprint = (r > 5 || crowd > 0) && p.getFoodData().getFoodLevel() > 6;
            if (p.horizontalCollision && p.onGround()) bot.ctl.jump = true;
        }
        if (r > 3.0) return "다가가는 중";
        boolean headroom = p.level().getBlockState(p.blockPosition().above(2)).getCollisionShape(p.level(), p.blockPosition().above(2)).isEmpty();
        if (crowd > 0 && p.isSprinting()) {
            // 여럿: 달리며 때려 넉백
            if (bot.ctl.aimed(p, 25) && Act.attack(bot, t, 0.9f)) return "달리며 넉백 공격";
            return "달리는 중";
        }
        // 하나: 점프 치명타
        if (p.onGround() && headroom && !p.isInWater() && !p.onClimbable()) {
            bot.ctl.jump = true;
            bot.ctl.sprint = false;
            return "점프!";
        }
        boolean falling = !p.onGround() && p.getDeltaMovement().y < -0.05;
        if (bot.ctl.aimed(p, 25) && (falling || !headroom || p.isInWater())) {
            if (Act.attack(bot, t, 0.9f)) return falling ? "치명타!" : "공격";
        }
        return "공격 준비";
    }
}
