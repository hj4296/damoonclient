package net.damoon.bot;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** 활 쏘기: 끝까지 당기고, 탄도·이동 예측으로 조준이 맞으면 놓음 */
public final class Archer {
    private int cooldown;
    private int aimedTicks;
    public int shots;

    public boolean ready() { return cooldown <= 0; }

    private boolean snapped;

    private void release(Bot bot) {
        bot.ctl.use = false;
        cooldown = 5;
        aimedTicks = 0;
        shots++;
    }

    public void reset() {
        cooldown = 0;
        aimedTicks = 0;
    }

    /** 이번 틱에 화살을 놓았으면 true */
    public boolean tick(Bot bot, Vec3 target, Vec3 vel) {
        LocalPlayer p = bot.p();
        if (cooldown > 0) { cooldown--; return false; }
        if (!Inv.has(p, Items.ARROW) && !Inv.has(p, Items.SPECTRAL_ARROW) && !Inv.has(p, Items.TIPPED_ARROW)) return false;
        if (!p.isUsingItem() && !Inv.select(p, Inv.is(Items.BOW))) return false;
        Vec3 from = p.getEyePosition().subtract(0, 0.1, 0);
        Vec3 aim = target;
        double[] s = Ballistics.solve(from, aim, Ballistics.FULL);
        if (vel != null) {
            for (int k = 0; k < 4 && s != null; k++) {
                aim = target.add(vel.scale(s[1]));
                s = Ballistics.solve(from, aim, Ballistics.FULL);
            }
        }
        if (s == null) return false;
        float yaw = Ctl.angles(from, aim)[0];
        float pitch = (float) s[0];
        bot.ctl.look(yaw, pitch);
        bot.ctl.speed = 20f;
        bot.ctl.use = true;
        boolean drawn = p.isUsingItem() && p.getUseItem().is(Items.BOW) && p.getTicksUsingItem() >= 21;
        if (vel == null) {
            // 가만히 있는 표적: 부드럽게 맞춰서 쏨
            aimedTicks = bot.ctl.aimed(p, 0.4f) ? aimedTicks + 1 : 0;
            if (drawn && aimedTicks >= 2) { release(bot); return true; }
            return false;
        }
        // 움직이는 표적: 다 당기면 시점을 한 번에 맞추고 (서버가 먼저 받도록) 다음 틱에 놓음
        if (drawn && snapped) { snapped = false; release(bot); return true; }
        if (drawn) {
            p.setYRot(yaw);
            p.setXRot(pitch);
            p.setYHeadRot(yaw);
            snapped = true;
        }
        return false;
    }
}
