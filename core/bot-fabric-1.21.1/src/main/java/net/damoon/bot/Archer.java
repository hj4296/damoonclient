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
        bot.ctl.look(yaw, (float) s[0]);
        bot.ctl.speed = 20f;
        bot.ctl.use = true;
        boolean drawn = p.isUsingItem() && p.getTicksUsingItem() >= 21;
        float tol = vel == null ? 0.4f : 1.2f;
        aimedTicks = bot.ctl.aimed(p, tol) ? aimedTicks + 1 : 0;
        if (drawn && aimedTicks >= 2) {
            bot.ctl.use = false;
            cooldown = 5;
            aimedTicks = 0;
            shots++;
            return true;
        }
        return false;
    }
}
