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
    /** 높은 포물선으로 쏨 (가만히 있는 표적만) */
    public boolean high;

    private boolean snapped;

    private void release(Bot bot) {
        bot.ctl.use = false;
        cooldown = 5;
        aimedTicks = 0;
        shots++;
    }

    private static boolean pathHitsNeutral(Bot bot, Vec3 from, float yaw, float pitch, Vec3 target) {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        double v = 3.0;
        double vx = -Math.sin(yr) * Math.cos(pr) * v, vy = -Math.sin(pr) * v, vz = Math.cos(yr) * Math.cos(pr) * v;
        Vec3 pos = from;
        double dist = from.distanceTo(target);
        for (int t = 0; t < 60 && pos.distanceTo(from) < dist + 1; t++) {
            Vec3 next = pos.add(vx, vy, vz);
            net.minecraft.world.phys.AABB seg = new net.minecraft.world.phys.AABB(pos, next).inflate(0.6);
            for (var e : bot.level().getEntities(bot.p(), seg, Act::neutral)) {
                if (e.getBoundingBox().inflate(0.4).clip(pos, next).isPresent()) return true;
            }
            pos = next;
            vx *= 0.99; vy = vy * 0.99 - 0.05; vz *= 0.99;
        }
        return false;
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
        double[] s = high && vel == null ? Ballistics.solveHigh(from, aim, Ballistics.FULL) : Ballistics.solve(from, aim, Ballistics.FULL);
        if (vel != null) {
            for (int k = 0; k < 4 && s != null; k++) {
                aim = target.add(vel.scale(s[1]));
                s = Ballistics.solve(from, aim, Ballistics.FULL);
            }
        }
        if (s == null) return false;
        float yaw = Ctl.angles(from, aim)[0];
        float pitch = (float) s[0];
        // 화살 길에 중립 몹(좀비화 피글린 등)이 있으면 쏘지 않음 (무리 전체가 화냄)
        if (pathHitsNeutral(bot, from, yaw, pitch, aim)) {
            if (p.isUsingItem() && p.getTicksUsingItem() >= 21) bot.ctl.use = true; // 당긴 채 기다림
            bot.ctl.look(yaw, pitch);
            return false;
        }
        bot.ctl.look(yaw, pitch);
        bot.ctl.speed = 20f;
        bot.ctl.use = true;
        boolean drawn = p.isUsingItem() && p.getUseItem().is(Items.BOW) && p.getTicksUsingItem() >= 21;
        if (bot.ticks % 10 == 0) bot.dbg(String.format("활: 사용중=%s 손=%s 당김=%d 주손=%s 스냅=%s", p.isUsingItem(), p.getUseItem().getItem(), p.getTicksUsingItem(), p.getMainHandItem().getItem(), snapped));
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
