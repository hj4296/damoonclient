package net.damoon.bot;

import net.damoon.bot.path.Goal;
import net.damoon.bot.path.PathExec;
import net.minecraft.core.BlockPos;

/**
 * 땅속 깊은 곳에서 땅 위로 올라가기: 한 번에 멀리 길을 찾으면 너무 오래 걸리므로 10칸씩 끊어서 올라감.
 * 길찾기가 계속 실패하면 계단을 파서 올라감.
 */
public final class Climb {
    private int legY = Integer.MIN_VALUE, fails, bestY = Integer.MIN_VALUE, bestTick;
    private PitEscape stairs;
    public String info = "";

    /** 올라가는 중이면 true, 목표 높이에 닿았거나 포기하면 false */
    public boolean tick(Bot bot, int targetY) {
        BlockPos f = PathExec.feet(bot.p());
        if (f.getY() >= targetY && bot.p().onGround()) { info = "도착"; return false; }
        if (stairs != null) {
            info = "계단 파며 올라가는 중 — " + stairs.info;
            if (stairs.tick(bot)) return true;
            stairs = null;
            fails = 0;
            legY = Integer.MIN_VALUE;
            bestTick = bot.ticks;
        }
        // 30초 넘게 높이가 안 늘면 (길찾기가 실패만 반복) 바로 계단을 파서 올라감
        if (f.getY() > bestY) { bestY = f.getY(); bestTick = bot.ticks; }
        if (bestTick == 0) bestTick = bot.ticks;
        if (bot.ticks - bestTick > 20 * 30) {
            bot.nav.stop();
            stairs = new PitEscape();
            stairs.force = true;
            bestTick = bot.ticks;
            bot.log("30초 동안 못 올라감 → 계단을 파서 올라감");
            return true;
        }
        if (legY == Integer.MIN_VALUE || f.getY() >= legY) legY = Math.min(targetY, f.getY() + 10);
        info = "땅 위로 올라가는 중 (y " + f.getY() + " → " + targetY + ")";
        Nav.R r = bot.nav.go(new Goal.Above(legY));
        if (r == Nav.R.FAILED) {
            bot.nav.stop();
            legY = Integer.MIN_VALUE;
            if (++fails >= 2) { stairs = new PitEscape(); stairs.force = true; }
        }
        if (r == Nav.R.ARRIVED) { fails = 0; legY = Integer.MIN_VALUE; }
        return true;
    }
}
