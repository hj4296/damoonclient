package net.damoon.bot;

import net.damoon.bot.path.Goal;
import net.damoon.bot.path.PathExec;
import net.damoon.bot.path.PathFinder;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 목표까지 길을 찾고(여러 틱에 나눠서) 따라가기. 실패하면 다시 찾음. */
public final class Nav {
    public enum R { RUNNING, ARRIVED, FAILED }

    private final Bot bot;
    private Goal goal;
    private PathFinder finder;
    private PathExec exec;
    private int fails;
    private int waitTicks;
    public boolean allowBreak = true;
    public boolean allowPlace = true;
    public String info = "";
    private final Set<Long> avoid = new HashSet<>();

    Nav(Bot bot) { this.bot = bot; }

    public Goal goal() { return goal; }
    public PathExec exec() { return exec; }

    public void stop() {
        bot.trace += " NAVSTOP";
        goal = null;
        finder = null;
        exec = null;
        fails = 0;
        avoid.clear();
        Act.stopDig(bot);
    }

    public R go(Goal g) {
        LocalPlayer p = bot.p();
        if (goal == null || !goal.equals(g)) {
            stop();
            goal = g;
        }
        BlockPos f = PathExec.feet(p);
        if (g.isIn(f.getX(), f.getY(), f.getZ()) && (p.onGround() || p.isInWater())) {
            exec = null;
            finder = null;
            Act.stopDig(bot);
            info = "도착";
            return R.ARRIVED;
        }
        if (exec != null) {
            PathExec.R r = exec.tick(bot);
            if (r == PathExec.R.RUNNING) {
                info = "이동 " + exec.index() + "/" + (exec.path.size() - 1) + " " + exec.target();
                return R.RUNNING;
            }
            Act.stopDig(bot);
            if (r == PathExec.R.FAILED) {
                fails++;
                bot.log("경로 실패: " + exec.why + " (" + fails + "회)");
                if (exec.why.contains("못 캐는") || exec.why.contains("끼임")) avoid.add(exec.target().pos().asLong());
                waitTicks = 5;
            }
            exec = null;
            if (fails > 10) return R.FAILED;
        }
        if (waitTicks > 0) { waitTicks--; return R.RUNNING; }
        if (finder == null) {
            if (!(p.onGround() || p.isInWater() || p.onClimbable())) return R.RUNNING;
            int blocks = allowPlace ? Inv.count(p, Inv.throwaway()) : 0;
            finder = new PathFinder(p.level(), p, f, g, blocks);
            finder.canBreak = allowBreak;
            finder.avoid.addAll(avoid);
        }
        PathFinder.Status s = finder.step(18_000_000L);
        info = "길찾는 중… " + finder.expanded();
        if (s == PathFinder.Status.RUNNING) return R.RUNNING;
        List<PathFinder.Node> path = finder.path();
        finder = null;
        if (path == null || path.size() < 2) {
            fails++;
            bot.log("길 없음 (" + s + ", " + fails + "회)");
            waitTicks = 20;
            return fails > 10 ? R.FAILED : R.RUNNING;
        }
        exec = new PathExec(path);
        return R.RUNNING;
    }
}
